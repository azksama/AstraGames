"""Reconcile Wolf's documented system IDs with the actual Kotlin admission contract.

No game assets, native binary execution or network dependency. The documented ID
inventory was checked against the two official help pages on 2026-10-11. Labels
describe Astra's implementation, rather than copying the manual's paragraphs.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'wolf-native/src/main/kotlin/fr/astragames/wolf/WolfSystemSupport.kt'
DOC = ROOT / 'docs/wolf-native/SYSTEM_VARIABLE_COVERAGE.md'

NUMBER_GROUPS = [
    ([0], 'portrait du message'), ([1, 2], 'position du message'), ([3, 4], 'position des choix'),
    ([5, 6], 'curseur de pause'), ([7], 'choix initial'), ([8], 'taille du texte'),
    ([9, 10], 'progression du message'), ([11], 'progression des images texte'),
    ([12, 13], 'message et événement en cours'), ([14], 'fenêtre des choix'),
    ([15, 16], 'répétition des directions'), ([17, 18, 19], 'sons des choix'), ([20], 'choix courant'),
    ([21], 'ombre du texte'), ([22], 'mélange du curseur'), ([23], 'taille ruby'),
    ([24, 25], 'chargement et emplacement'), ([26, 27], 'marges des choix'), ([28, 29, 30], 'temps de jeu'),
    ([31, 32], 'cadences mesurées'), ([33], 'saut de rendu'), ([34], 'position verticale des sprites'),
    ([35], 'héros en mouvement'), ([36], 'ombre du héros'), ([37], 'distance entre compagnons'),
    ([38, 39], 'permissions et résultat de saisie'), ([40, 41, 42], 'géométrie et rendu de saisie'),
    (list(range(43, 49)), 'réception réseau'), (list(range(52, 58)), 'liaisons des commandes'),
    ([59, 60], 'répétition valider et retour'), (list(range(61, 66)), 'brouillard'),
    ([66, 67, 68], 'arrière-plan'), ([71, 72], 'position du pointeur'), ([73], 'tactile des dialogues'),
    ([74], 'curseur visible'), (list(range(77, 83)), 'horloge locale'), ([84, 85], 'format et backend'),
    (list(range(87, 92)), 'ombres des compagnons'), ([93, 94], 'panoramique audio'),
    ([95, 96, 97, 98, 99], 'paramètres et canal audio'), ([100, 101, 102], 'gains audio'),
    ([104, 105], 'caméra'), ([108], 'instructions du tick'), ([110], 'état aléatoire'),
    ([111], 'focus Android'), ([112], 'mode joueur ou test éditeur'), ([114, 115], 'versions de compatibilité'),
    ([116, 117, 118], 'dimensions logiques'), ([120], 'origine du reset'),
    ([122, 123], 'filtres de carte et image'), ([125], 'épaisseur de texte'), ([127], 'tuiles exclues du save'),
    ([129, 130], 'vitesse diagonale'), ([131, 132], 'centre caméra'), ([134, 135], 'liaisons du pad'),
    ([136, 137, 138], 'fenêtre Windows'), ([139], 'extension de carte Pro'), ([140], 'filtre de présentation'),
    ([141], 'langue interne Pro'), ([143], 'ensemble Pro complet'), ([146], 'profil DirectInput'),
    ([148], 'échelle au prochain lancement'), ([150], 'conversion du texte'),
    (list(range(155, 161)), 'réglages des données du jeu'), ([163], 'conversion tactile Windows'),
]
STRING_GROUPS = [
    (list(range(1, 7)), 'noms des commandes'), (list(range(10, 16)), 'sprites de la partie'),
    ([19], 'identification Astra'), ([20, 21], 'proxy'), ([22], 'réception texte continue'),
    ([25, 26, 27], 'message et encadrement'), ([30, 31], 'arrière-plan et brouillard'),
    ([33, 34], 'pistes audio'), (list(range(40, 50)), 'historique des choix affichés'),
    ([52], 'racine Data'), ([54], 'dernier diagnostic du moteur'), ([55], 'pile des événements'),
    ([57], 'presse-papiers'), ([60], 'titre de la tâche Android'),
    ([62, 63, 64, 65, 67], 'polices du jeu'), ([68], 'saisie en cours'),
    ([70, 71], 'pad connecté'), ([73], 'racine BasicData'), ([74], 'autre BasicData au prochain lancement'),
    ([75], 'arguments Windows'), ([76], 'langue Android'), ([82], 'moniteur de performance Wolf'),
]

def contracts(source: str) -> dict[str, set[int]]:
    expressions = dict(re.findall(r'^    val (\w+) = ([^\n]+(?:\n        [^\n]+)*)', source, re.MULTILINE))
    expressions = {name: expression for name, expression in expressions.items()
        if name not in {'unsupportedNumbers', 'unsupportedStrings'}}
    parsed: dict[str, set[int]] = {}
    def parse(name: str) -> set[int]:
        if name in parsed: return parsed[name]
        expression = re.sub(r'\s+', '', expressions[name])
        tokens = re.findall(r'\([0-9]+\.\.[0-9]+\)(?:\.toSet\(\))?|setOf\([0-9, ]+\)|[A-Za-z][A-Za-z0-9_]*|[0-9]+|[+-]', expression)
        if ''.join(tokens) != expression: raise ValueError(f'Unsupported admission expression: {name}')
        result: set[int] = set(); add = True
        for token in tokens:
            if token in ('+', '-'): add = token == '+'; continue
            if token.startswith('setOf'): atom = {int(n) for n in re.findall(r'\d+', token)}
            elif '..' in token:
                a, b = map(int, re.findall(r'\d+', token)); atom = set(range(a, b + 1))
            elif token.isdigit(): atom = {int(token)}
            else: atom = parse(token)
            result = result | atom if add else result - atom
        parsed[name] = result
        return result
    for name in expressions: parse(name)
    return parsed

def inventory(source: str, c: dict[str, set[int]]) -> list[dict]:
    result = []
    for kind, groups, reads, writes, save_reads, save_writes, unsupported in [
        ('Sys', NUMBER_GROUPS + [([119], 'échelle logique Astra déjà portée')], 'numericReads', 'numericWrites', 'numericSaveReads', 'numericSaveWrites', 'unsupportedNumbers'),
        ('SysS', STRING_GROUPS, 'stringReads', 'stringWrites', 'stringSaveReads', 'stringSaveWrites', 'unsupportedStrings'),
    ]:
        block = re.search(r'^    val ' + unsupported + r' = mapOf\((.*?)\)\r?\n', source, re.MULTILINE | re.DOTALL)
        if block is None: raise ValueError(f'Missing explicit refusals: {unsupported}')
        refused = {int(n) for n in re.findall(r'(\d+) to "', block.group(1))}
        if c[reads] & refused: raise ValueError(f'{kind}: IDs both admitted and refused')
        labels = {id: label for ids, label in groups for id in ids}
        ids = set(labels)
        if ids != c[reads] | refused: raise ValueError(f'{kind}: undocumented or unclassified IDs: {ids ^ (c[reads] | refused)}')
        if c[writes] - c[reads] or c[save_reads] - c[reads] or c[save_writes] - c[writes]:
            raise ValueError(f'{kind}: contradictory admission subsets')
        for id in sorted(ids):
            result.append(dict(kind=kind, id=id, feature=labels[id], read=id in c[reads], write=id in c[writes],
                save_read=id in c[save_reads], save_write=id in c[save_writes]))
    return result

def document(rows: list[dict]) -> str:
    lines = [
        '# Variables système Wolf : couverture vérifiée', '',
        'Catalogue contrôlé le 11 octobre 2026 pour Astra 1.12.0-beta.19. Les 182 identifiants de l’aide officielle normale/Pro et Sys119 (échelle logique portée précédemment) ont une admission explicite : 159 lectures natives et 24 fonctions refusées. Cette admission ne certifie pas la compatibilité complète d’un jeu ni toutes les variantes d’une commande.', '',
        'Le contrat exécutable est `WolfSystemSupport.kt`, partagé par `Variables.kt`, `Runtime.kt` et `Capabilities.kt`. Reproduire le rapprochement sans réseau : `python tools/wolf_system_audit.py --check`. Les tests parcourent toutes les lectures admises au démarrage et toutes les lectures isolées admises d’une sauvegarde, ainsi que les effets modifiés.', '',
        'Sources : [aide officielle normale](https://silversecond.com/WolfRPGEditor/Help/06systemvalue.html), [aide Pro](https://silversecond.com/WolfRPGEditor/Help/06systemvalue_pro.html). Les numéros 51/58 sont des séparateurs d’interface, exclus du catalogue. Les plages et les titres `SysS 1` avec espace sont développés ; les renvois Pro sont dédupliqués.', '',
        '## Valeurs, effets et limites', '',
        '- Le constructeur W3 `00959290` et l’initialiseur `00959ec0` du Game.exe officiel 3.729, SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`, établissent notamment les positions −1, la police 12/22/28 selon résolution, les messages à 120 caractères/s avec 1 tick initial, les images texte à 1 000 000, la répétition 16/5, les ombres et les gains. Les globals statiques nuls de Sys21/22/23 ne prouvent pas tout état après initialisation. Le profil froid Astra utilise ces valeurs qualifiées ; ce n’est pas une comparaison exhaustive du constructeur W2.',
        '- REA `009e26c0` établit les clamps de panoramique ±255, centre caméra ±99 999 et reset 0..2. Le centre affecte aussi le seuil du suivi de caméra. `0093a810` établit Park–Miller et le rejet au-dessus de 2 milliards. Les preuves sont de décompilation du binaire épinglé, sans prétendre disposer du code source original. Références locales privées : `build/wolf-research/beta18-system-*.txt` ; IDs de preuves consignés dans `SYSTEM_VARIABLE_REA.md`.',
        '- Sys114 décrit Game.dat ; Sys115 annonce la cible de compatibilité du moteur Astra 3.72. Sys112=0 désigne le lecteur, indépendamment du mode debug ; Sys143=0 car l’ensemble Pro n’est pas complètement porté. Sys85=0 désigne le rendu Android accéléré, pas un pilote DirectX Windows.',
        '- Sys31 mesure les ticks et Sys32 les images réellement redessinées : ils ne renvoient pas artificiellement la cadence cible. Sys77..82 lisent l’horloge locale. Sys111 suit le focus/lifecycle Android ; SysS70 vient du premier pad physique connecté, y compris avant une première touche et après déconnexion. La classification Windows SysS71 reste UNKNOWN pour un pad Android non classifié, vide sans pad. Les sticks/POV Windows Sys134/135/146 ne sont pas simulés.',
        '- Sys43..47 proviennent du worker HTTP réel, avec callbacks périmés ignorés ; SysS22 (buffer texte progressif) et le proxy restent refusés. SysS68 suit les caractères Unicode de l’éditeur Android, accepte une écriture du jeu sans casser sa limite, puis renvoie <<NoData>> hors saisie. SysS54 expose le dernier avis du moteur, SysS55 une pile structurée Astra, sans garantir le format japonais exact de Windows.',
        '- Les champs transitoires du host ne sont pas inventés lors d’une lecture isolée de sauvegarde. Les sous-ensembles de la table sont vérifiés au préflight et à l’exécution, avec les champs DB/ordinaires déjà portés. La restauration ajoute les defaults manquants des anciennes sauvegardes ; une sauvegarde capture configuration et monde, pas un worker ni un éditeur ouvert. RNG, caméra, audio et titre restaurent leurs effets. Les sauvegardes natives restent distinctes des saves Windows.',
        '- Les restrictions existantes de profil restent effectives (polices/facteurs 3.x, Sys140 à partir de 3.55, Sys148 à partir de 3.30, Sys150 à partir de 3.50, autres variantes par commande). Une valeur ou variante dynamique n’est pas prouvée par la seule présence d’un identifiant. Les références indéterminées sont revérifiées à leur résolution ; les identifiants réservés/inconnus restent refusés.', '',
        '## Préflight complet du jeu', '',
        'Tous les communs, conditions et cartes déclarées sont inspectés avant lancement, en modes Automatique et Natif. Les références statiques numériques, SysS et échappements texte utilisent le lexer de la VM ; les doubles backslash littéraux sont ignorés. Le diagnostic conserve la liste complète bornée à 512 incompatibilités distinctes et indique si le scan est incomplet. L’aperçu de 12 lignes affiche le nombre restant. Automatique relance Winlator ; Natif arrête la préparation avec ce rapport, sans essayer une fonction déjà refusée. Les indices calculés et ressources générées ne peuvent pas être intégralement déterminés statiquement.', '',
        '## Catalogue', '',
        'L/E : lecture/écriture de la session. SL/SE : lecture/écriture isolée dans une sauvegarde native. Une case « — » est un refus, pas une valeur 0 implicite. Les unités et clamps sont contrôlés par la VM ; les propriétés de fenêtre Windows, filtres non portés, presse-papiers, autre BasicData et perfmonitor Wolf demandent encore un contrat Android.', '',
        '| Identifiant | Fonction Astra | L | E | SL | SE |', '|---|---|:---:|:---:|:---:|:---:|',
    ]
    for row in rows:
        flags = [('oui' if row[key] else '—') for key in ['read', 'write', 'save_read', 'save_write']]
        lines.append(f"| {row['kind']}{row['id']} | {row['feature']} | " + ' | '.join(flags) + ' |')
    return '\n'.join(lines) + '\n'

def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument('--check', action='store_true'); parser.add_argument('--json', type=Path)
    args = parser.parse_args(); source = SOURCE.read_text(encoding='utf-8'); c = contracts(source); rows = inventory(source, c)
    output = document(rows)
    if args.check:
        if DOC.read_text(encoding='utf-8') != output: raise SystemExit('System variable coverage document is stale')
    else: DOC.write_text(output, encoding='utf-8')
    if args.json: args.json.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(total=len(rows), native_reads=sum(x['read'] for x in rows), refused=sum(not x['read'] for x in rows),
        numeric_reads=len(c['numericReads']), string_reads=len(c['stringReads']), save_numeric_reads=len(c['numericSaveReads']), save_string_reads=len(c['stringSaveReads']))))

if __name__ == '__main__': main()
