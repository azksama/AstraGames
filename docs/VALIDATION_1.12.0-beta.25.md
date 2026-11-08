# Validation de la préversion 1.12.0-beta.25

Application `fr.astragames.app`, version `1.12.0-beta.25`, code Android `53`.
Base : release publique beta.24 (`3e94816`), complétée par la refonte des métadonnées
et les corrections de la [revue des gestes](GESTURES_REVIEW_2026-11-08.md).

| Identité | Valeur |
| --- | --- |
| APK | `Astra-1.12.0-beta.25.apk` |
| SHA-256 APK | `ff5dcec4043d9efbd471b2b419bf138e8b2867e882be8d6d83f485130f9f9f74` |
| SHA-256 certificat | `7425b524d8daac7759c58362e5f97a3bc5aa99d0645c2d8f0cd680490a4ad793` |
| Variante | Release non débogable, R8 et réduction des ressources actifs |
| SDK et ABI | minSdk 26, targetSdk 36 ; ARM64 et x86_64 |

## Exécutions de cette passe

Android Emulator x86_64, API 36 pour les parcours et API 36.1 pour la mise à jour.
Les tests Android utilisent exclusivement le SDK, l’instrumentation, ADB et Logcat.

| Contrôle | Résultat |
| --- | --- |
| Assemblages debug, instrumentation et release | Réussis |
| Android Lint release | 0 erreur, 131 avertissements |
| Ensemble des tests d’interface | 71 réussis, 1 ignoré |
| Gestes, bibliothèque et commandes Wolf | 34 réussis |
| Métadonnées, Room, réseau réel, MKXP-Z et presets Wolf | 36 réussis |
| Reprise finale des gestes et pincement du recadrage | 7 réussis |
| Paysage 1280 × 720, sélecteur avec texte 200 % compris | 9 réussis |
| Gestes avec animations système désactivées | 4 réussis |
| Mise à jour publique beta.24 → beta.25 | Réussie, avant et après ouverture de l’interface |
| Signature et alignement ZIP 16 Kio | Vérifiés avec les outils SDK |
| Inspection ELF 16 Kio | 30 bibliothèques passent |

Les suites se recouvrent : **122 tests distincts réussis**, 161 exécutions réussies
dans cette matrice. Le test ignoré est
`GodotUiTest.installedOfficialGodotPluginsShowAsInstalledWithoutDownloadDuplicates` :
le plugin officiel n’est pas installé sur cet AVD. Il n’est pas compté comme réussi.
La première passe exploratoire de 39 tests n’est pas ajoutée à ces totaux.

Les 131 avertissements lint restent visibles, notamment des suggestions KTX,
de dépendances et des chaînes non externalisées. Aucun résultat JVM ni test
sur téléphone physique n’est présenté comme exécuté dans cette intervention.

## Métadonnées et réseau

VNDB a réellement enrichi le jeu témoin avec développeur, description et une
jaquette décodable de 600 × 900. La fiche F95Zone actuelle a fourni sa version et
l’indication de pièces jointes masquées aux visiteurs. Les fixtures instrumentées
couvrent les cookies membres, leur renouvellement, les sessions refusées,
redirections, sources différées, replis d’images et réponses non décodables.
Aucun cookie réel n’est inclus dans les preuves publiques.

La session personnelle F95Zone de l’utilisateur et le téléchargement authentifié
avec son compte n’ont pas été exercés. La vérification réelle en ligne porte sur
le mode visiteur. Ryuugames et tous les moteurs externes ne sont pas universellement
qualifiés. Le [bilan initial](VALIDATION_METADATA_2026-11-08.md) détaille les correctifs.

## Mise à jour du véritable APK release

La référence installée est l’APK public beta.24 exact, SHA-256
`f9bed24855f5931f707796bc4ca50c3e52bb09f8b4b2b8809eab20765c669ebc`.
L’APK beta.25 signé est installé par remplacement, sans désinstallation.
Le certificat historique est identique ; aucune clé n’est incluse dans les assets.

Une sonde indépendante, écrite avec les seules API Android, compare les
**31 tables SQLite** par type et contenu, le fichier des préférences et un fichier
de backup, ainsi que la date de première installation. Le catalogue contient un
jeu témoin favori. Ces comparaisons passent après installation, puis après ouverture
de la bibliothèque, de la fiche, de l’éditeur et de l’import dans le code minifié.
Fermer l’import et l’éditeur ne modifie pas les données.

La sonde ne dépend pas des noms R8 et n’ajoute aucun hook à l’application.
Son code et sa construction se trouvent sous `scripts/release-probe/`. Elle n’est
pas distribuée comme APK utilisateur. Aucun jeu n’est lancé pendant l’essai de mise
à jour ; il ne revendique pas une migration de schéma, qui reste en version 9.

## Runtimes et portée

La fixture MKXP-Z XP exerce réellement les commandes tactiles et accessibles.
Les tests de contrôles Wolf n’établissent pas une nouvelle compatibilité de tous
les jeux Wine. Les qualifications antérieures de beta.24 restent historiques.

Sur les 30 bibliothèques embarquées, 24 sont identiques octet pour octet à la
beta.24. Les six ponts reconstruits (`libastra_exec_bridge`, `libgladiorenderer`,
`libwinlator`, deux ABI) conservent des sections `.text` identiques selon les outils
NDK ; leurs fichiers complets diffèrent. Les sources natives MKXP-Z et notices sont
reprises sans modification, avec les empreintes de beta.24.

L’inspection ZIP/ELF ne constitue pas une exécution sur un appareil ARM64 16 Kio.
Le signalement RELRO déjà connu de zstd ARM64 reste distinct de l’alignement.
Aucun essai de cette passe ne qualifie un téléphone physique, tous les plugins,
jeux commerciaux, RTP ou extensions Windows.

Sources et notices accompagnent l’APK. `SOURCE_MANIFEST.json` identifie le contenu
source et le commit ; `VALIDATION_SUMMARY.json` résume les résultats et
`SHA256SUMS.txt` permet de vérifier les assets téléchargés.
