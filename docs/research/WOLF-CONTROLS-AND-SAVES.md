# Contrôles exportables et sauvegardes Wolf

Ces deux lots étendent Astra après l’analyse MTool du 12 octobre 2026. Les contrôles sont une implémentation propre à Astra. Le lecteur de sauvegardes utilise la référence libre [Sinflower/WolfSave](https://github.com/Sinflower/WolfSave/tree/537ceba4262368c61bfb9c4fa5333341a115c662), figée au commit `537ceba4262368c61bfb9c4fa5333341a115c662`, sous licence MIT. Aucun exécutable, DLL, bytecode ou ressource MTool n’est intégré.

## Contrôles

Le menu de jeu ouvre l’éditeur Astra existant. Un bouton peut recevoir une combinaison de une à quatre touches, une bascule qui maintient la combinaison, ou une séquence temporisée. La recherche de touche accepte son libellé, son nom X11 et son code physique. Les anciens boutons à touche unique continuent à fonctionner.

Les modificateurs Ctrl, Shift et Alt sont pressés avant les autres touches et relâchés après elles. Chaque doigt, bascule, séquence et direction du joystick possède sa source d’entrée. Une touche partagée reste pressée tant qu’au moins une source la maintient. Le joystick conserve un fonctionnement maintenu, y compris si les directions ont des combinaisons assignées.

Une séquence contient de une à seize étapes. Chaque étape dure entre 20 et 2 000 ms, comporte au plus quatre touches distinctes, et une liste vide représente une pause. La durée totale est limitée à dix secondes. Un second appui arrête la séquence. Le menu, la pause, la perte de focus, le masquage, le détachement et le changement de preset annulent les étapes en attente et relâchent les sources. Une fin de geste antérieure à cette annulation ne réactive pas une bascule.

### Format de preset

Le document JSON UTF-8 est limité à 256 Kio et seize niveaux d’imbrication. Son enveloppe est versionnée indépendamment de la disposition des boutons :

```json
{
  "format": "astra-wolf-controls",
  "version": 1,
  "name": "Mon joystick",
  "layout": {
    "version": 3,
    "customControls": [],
    "movementMode": "JOYSTICK",
    "opacity": 75,
    "size": 100,
    "action_accept": {
      "keys": ["KEY_CTRL_L", "KEY_ENTER"],
      "mode": "HOLD"
    },
    "position_false_accept": [0.86, 0.76],
    "position_true_accept": [0.88, 0.68]
  }
}
```

Les noms de touche exacts proviennent de `WolfKeyboard` et de `XKeycode` ; un code numérique n’est accepté que s’il est entier, fini et correspond à une touche physique connue. Les champs inconnus et les versions futures sont refusés. Un preset contient au plus 24 boutons personnalisés, dont les IDs suivent `custom_[a-f0-9]{32}`. Les IDs standards sont `up`, `left`, `right`, `down`, `accept`, `back`, `shift` et `joystick` ; ce dernier ne reçoit pas une action clavier.

| Valeur | Limites d’import |
|---|---|
| Nom de preset | 1 à 48 caractères, sans caractères de contrôle |
| Libellé de bouton | 1 à 16 caractères, sans caractères de contrôle |
| Opacité globale ou individuelle | 15 à 100 % |
| Taille globale | 70 à 150 % |
| Taille individuelle | 50 à 200 % |
| Position portrait/paysage | Deux nombres finis entre 0 et 1 |
| Nombre de presets locaux | 32 |

La validation du fichier précède toute écriture. L’import ne remplace pas un preset portant le même nom : il ajoute un suffixe ` (2)`, puis ` (3)`, etc. Les anciennes dispositions v1 et v2 sont migrées vers v3 en conservant les touches et les positions reconnues. Les valeurs anciennes stockées localement sont normalisées ; les fichiers importés invalides sont refusés au lieu d’être corrigés silencieusement. Les presets contiennent uniquement les contrôles, sans chemins de jeu, scripts, réglages de runtime ou sauvegardes.

`WolfTouchControls` expose les callbacks optionnels `exportPreset(suggestedFilename, json)` et `importPreset()`. L’Activity ouvre les documents SAF ; une lecture réussie appelle `controls.importPreset(json)` sur le thread UI. La méthode valide, sauvegarde, applique le preset et retourne son nom effectif. Un import invalide conserve la disposition et les entrées actuelles.

Sources principales : `WolfControlAction.kt`, `WolfControlPresetCodec.kt`, `WolfControlLayout.kt`, `WolfControlPresets.kt`, `WolfControlDialogs.kt`, `WolfKeyboard.kt` et `WolfTouchControls.kt` dans `app/src/main/java/fr/astragames/app/windows/`.

## Sauvegardes Wolf hors jeu

`SaveCodec.read("WOLF_RPG", bytes)` parcourt les sept sections du format WolfSave. `SaveCodec.patch(...)` accepte uniquement les chemins numériques reconnus, vérifie les valeurs, écrit dans une copie, recalcule le checksum, rechiffre et relit la copie. Le gestionnaire de sauvegardes reste responsable de la copie de secours, de l’écriture et de la restauration des fichiers.

Le format a un en-tête de vingt octets, trois tours XOR basés sur le générateur MSVCRT et un checksum égal à la somme des octets déchiffrés depuis l’octet 20, modulo 256. L’implémentation Kotlin reproduit l’arithmétique MSVCRT sur 32 bits ; elle ne dépend pas du générateur aléatoire de Java ni de la libc Android. Les graines et les autres octets de l’en-tête sont conservés.

Le numéro interne de format est un entier de seize bits et n’est pas le numéro de version de l’éditeur Wolf. Le lecteur accepte les structures décrites par la référence jusqu’au format `0x8e`, après parcours complet et vérification de la terminaison. Cette borne ne constitue pas une garantie pour toutes les versions ou tous les jeux Wolf. Les structures inconnues, tronquées, excessives ou comportant des données supplémentaires sont refusées. La sauvegarde réelle du moteur 3.729 a un format/chiffrement différent de cette référence et reste explicitement refusée.

| Champs | Lecture | Écriture |
|---|---|---|
| Nom du jeu, numéro de format, encodage, checksum | Oui | Non |
| Banque normale 0, `root.variables[id]` | Format `0x80` prouvé | Entier signé 32 bits, checksum valide |
| Cellules numériques de la base de variables, `root.variableDatabase.types[t].rows[r].fields[f]` | Structures déclarées par le format | Entier signé 32 bits, checksum valide |
| Cellules texte de la base de variables | Aperçu CP932 ou UTF-8 | Non |
| Autres banques, compteurs, IDs de données et état interne | Conservés par le parcours | Non |
| Format ou chiffre inconnu | Refus explicite | Refus explicite |

Une sauvegarde reconnue avec checksum incorrect est affichée en lecture seule. Les chaînes sont conservées octet pour octet ; leur aperçu est borné à 8 192 octets. La lecture est limitée à 64 Mio, 500 000 opérations de structure et 100 000 cellules déclarées par catégorie concernée. Les compteurs et tailles sont vérifiés avant allocation ou déplacement dans le fichier.

La découverte source reconnaît `.sav` dans `Save`, `save`, `SaveData`, `Data/Save`, `Data/SaveData`, `Save_MTool`, `Data/Save_MTool` et, si applicable, à la racine. Les répertoires `Save_MTool` servent à retrouver des sauvegardes existantes ; leur présence n’implique pas un format différent. La découverte des fichiers privés au runtime Wine est séparée et associée au jeu par le gestionnaire.

Les restrictions de cet éditeur de fichiers sont indépendantes du nouvel adaptateur de variables en cours de jeu. Un refus hors jeu du format Wolf 3.729 ne désactive pas les capacités du runtime 3.729 reconnues par l’adaptateur.

Sources : `app/src/main/java/fr/astragames/app/data/saves/WolfSaveCodec.kt`, `SaveCodec.kt` et `GameSaves.kt`. La notice est embarquée dans `app/src/main/assets/licenses/WolfSave-MIT.txt` et affichée par le dialogue de licences Wolf.

## Preuves obtenues

Les fixtures synthétiques de `app/src/test/resources/saves/wolf/` couvrent quinze versions internes (`96`, `100`, `105`, `111`, `114`, `115`, `118`, `128`, `129`, `137`, `138`, `139`, `140`, `141`, `142`), chacune en CP932 et UTF-8. Le générateur `scripts/tests/generate-wolf-save-fixtures.py` utilise directement `msvcrt.dll` sur Windows pour produire le chiffrement, indépendamment du code Kotlin testé. Les trente fichiers ont été lus par les fonctions C++ `Parse` originales de WolfSave : sections, cellules numériques, checksum et fin exacte concordent. Le harness ne remplace que l’IO Windows et les sorties auxiliaires, sans modifier les sept fonctions de parcours.

Une sauvegarde de 195 337 octets produite par l’exécutable officiel Wolf 2.2961 donne le format `0x80`, dix banques d’état dont la banque normale 0, 18 variables normales, 17 580 cellules numériques et 1 538 cellules texte de base de variables. Les sections et les cellules ont été comparées intégralement entre le C++ de référence et le Kotlin de production. L’affectation contrôlée de la variable normale 17 à `271828` se retrouve à l’offset `14257`. Le patch à `314159` ne modifie que les octets `2`, `14257` et `14258` : checksum et octets effectivement différents de cette variable.

| Artefact | SHA-256 |
|---|---|
| `Game.exe` officiel Wolf 2.2961, inchangé | `795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0` |
| Sauvegarde témoin 2.2961 | `841ae6b9bdbfcea168c91260c0fa445123f5eb336f2b44c58f6329923e11846d` |
| Copie patchée par le Kotlin | `26a67b594055a573e32c1d647f0585d8d123c7a6cd66855f2cee647ac921878c` |
| Sauvegarde 3.729, refusée | `cbf4f4a427c35a9ae52557dc10aa672943b0e8f3dd416cbaa3a01445dc936e4d` |

Le moteur original a ensuite été relancé dans deux nouveaux dossiers Windows Win32. La copie des données officielles ajoute uniquement `SaveLoad[1,1]` à l’événement d’initialisation, sans affectation de variable ni sauvegarde. Le helper Astra observe la variable `2000017` initialement à `0`, puis à `271828` avec le fichier témoin et à `314159` avec le fichier patché. Les hashes de l’exécutable et des deux sauvegardes restent identiques à ceux ci-dessus. Cela prouve le rechargement par le moteur original de la modification hors jeu pour cette version.

Ces preuves sous Windows ne sont pas une validation Wine Android ni une mesure de FPS. Les essais Android sont centralisés sur Android Studio Emulator avec le SDK, ADB, instrumentation et Logcat. Ils doivent être rapportés séparément, de même que les jeux/appareils physiques.

Les preuves volumineuses sont conservées localement dans `build/`, ignoré par Git :

- `build/mtool-implementation/wolf-save-golden-reference/results.json` : trente lectures comparées au C++.
- `build/mtool-implementation/wolf-save-reference-real-w2-corrected.json` : lecture C++ complète de la sauvegarde officielle.
- `build/mtool-implementation/wolf-save-kotlin-real-w2.var17-314159.json` : lecture et patch Kotlin de production.
- `build/mtool-implementation/wolf-save-var17-patch-manifest.json` : hashes, offsets, comparaison et preuve de rechargement.
- `build/wolf-research/wolf-data-proof/offline-reload-control-proof.json` et `offline-reload-proof.json` : rechargements différentiels Windows Win32.

## Tests à intégrer à la validation de l’application

| Classe | Cas couverts |
|---|---|
| `WolfControlPresetCodecTest` (7 JVM) | JSON versionné, migrations, positions, tailles, opacité, touches, IDs, données étrangères, combinaisons, macros, limites |
| `WolfHeldKeysTest` (3 JVM) | Propriété des touches partagées, réconciliation joystick, relâchement idempotent |
| `WolfControlActionTest` (5 instrumentation) | Touches réellement émises, bascule, multitouch, annulation, macros, perte de focus et changement de preset |
| `WolfControlPresetUiTest` (1 instrumentation) | Édition Astra, combinaison, bascule, export et import à travers les actions UI |
| `WolfSaveCodecTest` (8 JVM) | Trente fixtures, vecteur MSVCRT, patch minimal, checksum, versions, troncatures, valeurs/types, compteurs hostiles et unsigned |
| `WolfRealSaveCodecTest` (3 JVM, fixtures locales facultatives) | Lecture C++/Kotlin, patch exact de la vraie save W2, refus de la vraie save W3 |

Les tests réels locaux sont ignorés si les fichiers de preuve ne sont pas présents ; un succès CI de la suite générique ne les remplace pas. Les régressions à conserver comprennent `WolfControlLayoutTest`, `WolfTouchControlsTest`, `WolfControlEditorTest`, `SaveCodecRegressionTest` et `SaveSafetyTest`. La présence de ces tests dans le dépôt n’est pas une preuve de leur passage : les résultats Gradle et instrumentation doivent accompagner la livraison.
