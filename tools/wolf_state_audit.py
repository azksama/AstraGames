"""Check every command124 slot against the shared VM/preflight admission ledger."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from wolf_system_audit import contracts

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'wolf-native/src/main/kotlin/fr/astragames/wolf/WolfStateSupport.kt'
DOC = ROOT / 'docs/wolf-native/STATE_READ_COVERAGE.md'
GROUPS = [('actor', 'Personnage'), ('position', 'Position'), ('general', 'Général'), ('picture', 'Image')]


def inventory(source: str) -> list[dict]:
    sets = contracts(source)
    rows = []
    for category, (name, label) in enumerate(GROUPS, 1):
        admitted = sets[name]
        catalog = sets[name + 'Catalog']
        if admitted - catalog:
            raise ValueError(f'{name}: admitted fields outside catalog')
        for field in sorted(catalog):
            rows.append(dict(category=category, kind=label, field=field, read=field in admitted))
    return rows


def document(rows: list[dict]) -> str:
    lines = [
        '# Commande124 : catalogue des lectures d’état', '',
        'Astra 1.12.0-beta.19. Les tables du PE officiel Wolf3.729 contiennent 149 emplacements numérotés : '
        '**113 lectures admises et 36 emplacements refusés**, dont des slots réservés. '
        'Ce catalogue est distinct des 183 variables Sys/SysS ; il ne mesure pas la compatibilité globale d’un jeu.', '',
        'Source exécutable : `WolfStateSupport.kt`, utilisée par le validateur de commandes partagé avec le préflight. '
        'Vérifier sans réseau : `python tools/wolf_state_audit.py --check`. '
        'Les tests JVM parcourent les 256 valeurs possibles de chaque catégorie, y compris les champs non catalogués. '
        'Les flags, profils de Game.dat, types de ressources et indices dynamiques restent contrôlés séparément.', '',
        'Le [contrat REA](STATE_READ_REA.md) donne les adresses, tables, preuves et unités de chaque champ. '
        'L’admission requiert la VM **et l’hôte Android** : mémoire PSS/disponible mesurée, temps de rendu, '
        'positions/durées des décodeurs audio, événements sous le pointeur, boutons/molette et propriétés des images. '
        'Un temps audio absent renvoie 0 comme dans le lecteur original ; une mémoire hôte absente produit une erreur explicite. '
        'Les champs MIDI utilisent les ticks SMF, les autres pistes les millisecondes.', '',
        'Les champs personnage20..22 (pattern picture et résidus pixel) et picture13..20 (quadrilatères) restent refusés. '
        'Les champs position réservés, certaines recherches/coordonnées de personnages et pixels précis non raccordés restent refusés. '
        'La recherche d’événement sous le pointeur nécessite un profil≥3.40 ; les conversions écran/carte et recherches de pictures nécessitent≥3.50. '
        'La table de langue doit être connue. Les mesures CPU/rendu représentent l’implémentation Android, pas une exécution DxLib Windows. '
        'Le pad Android utilise le profil de boutons compatible DirectInput d’Astra, sans inventer un périphérique Windows.', '',
        'La sauvegarde native v23 conserve les durées totale/restante des images et reprend les tweens. '
        'Les anciennes sauvegardes à tween migrent leurs horloges avant une nouvelle écriture. '
        'Les temps transitoires, boutons physiques et décodeurs ne sont pas restaurés à partir de valeurs inventées. '
        'Les sauvegardes natives et Windows restent distinctes.', '',
        '| Catégorie | Emplacements | Lectures | Refus |', '|---|---:|---:|---:|',
    ]
    for _, label in GROUPS:
        group = [row for row in rows if row['kind'] == label]
        read = sum(row['read'] for row in group)
        lines.append(f'| {label} | {len(group)} | {read} | {len(group) - read} |')
    lines += ['', '## Tous les emplacements', '', '| Catégorie | Champ | Lecture native |', '|---|---:|:---:|']
    for row in rows:
        lines.append(f"| {row['kind']} | {row['field']} | {'oui' if row['read'] else 'refus'} |")
    return '\n'.join(lines) + '\n'


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true'); parser.add_argument('--json', type=Path)
    args = parser.parse_args()
    rows = inventory(SOURCE.read_text(encoding='utf-8'))
    output = document(rows)
    if args.check:
        if DOC.read_text(encoding='utf-8') != output:
            raise SystemExit('State read coverage document is stale')
    else:
        DOC.write_text(output, encoding='utf-8')
    if args.json:
        args.json.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(total=len(rows), native_reads=sum(row['read'] for row in rows), refused=sum(not row['read'] for row in rows))))


if __name__ == '__main__':
    main()
