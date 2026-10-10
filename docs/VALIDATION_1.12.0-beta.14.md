# Validation Astra 1.12.0-beta.14

Version Android **42**, cœur natif `wolf-native-kotlin-1`, secours Windows `wine9-astra-2`.
L'implémentation native est **partielle**. Le parcours natif indépendant décrit ci-dessous fonctionne ;
les deux exemples officiels restent exécutés par Winlator. Cette livraison ne certifie pas tous les
jeux Wolf, la sauvegarde Windows en natif, ni une cadence permanente de 30/60 FPS.

## Code livré

- Module Kotlin/JVM `wolf-native` : lecteurs des formats, variables et bases de données, VM à
  propriétaire unique, cartes à la demande, diagnostics localisés, snapshots privés vérifiés.
- Hôte Android : lecture du dossier local ou d'un sous-dossier SAF sans import général,
  rendu Canvas avec caches, MediaPlayer, contrôles personnalisables, tactile, pincement et deux doigts.
- Choix par jeu **Automatique / Natif expérimental / Winlator**. Automatique conserve le moteur
  Windows d'un jeu qui possède déjà son préfixe ou un état de stockage Windows. Pour un nouveau jeu,
  les fonctions non portées déclenchent un secours accompagné d'un rapport natif terminé.
- Sauvegarde, chargement et export natifs séparés des sauvegardes Windows. Les menus suspendent
  la VM et l'audio ; perte de focus et fermeture libèrent les entrées.

La [documentation d'implémentation](wolf-native/IMPLEMENTATION.md) décrit les chemins et contrats ;
la [couverture réelle](wolf-native/RUNTIME_COVERAGE.md) décrit les commandes, variantes et limites.
La couche Windows existante est conservée, avec ses index, migrations, restauration et sauvegardes.

## Build et tests JVM

Commande finale, JDK Android Studio et SDK 36 :

```powershell
.\gradlew.bat :wolf-native:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

Build réussi en **1 min 8 s**. Résultats XML : **233 tests de l'application + 34 tests du cœur natif**,
sans échec, erreur ou test ignoré. Lint application : **0 erreur, 98 avertissements** ; ce résultat
ne signifie pas que les avertissements ont tous été corrigés.

Les 13 tests de formats incluent les deux corpus officiels locaux, CP932/UTF-8 et LZ4, les troncatures,
budgets, positions des champs DB et chemins. Les 21 tests de VM couvrent aussi branches, boucles,
communs, entrées, collision pleine tuile, déplacement, pics, dialogue, isolement et snapshots.
Les fixtures officielles sont facultatives dans une copie du dépôt : trois tests utilisant ces
fichiers locaux peuvent être ignorés s'ils ne sont pas installés. Elles ne sont pas distribuées.

Les **34 empreintes des fichiers de recherche** de `wolf-native/fixtures.json` ont été revérifiées :
aucun original modifié. Les corpus comprennent huit cartes, 450 événements communs et 53 723 commandes.
Lire ces données n'est pas une preuve de jouabilité.

## Tests Android SDK

Exclusivement Android Emulator officiel, ADB, instrumentation et Logcat. Une instance isolée
**API 36.1 x86_64** utilise le rendu SwiftShader. L'instance préexistante, dont le stockage était
insuffisant, est laissée intacte. La confirmation Android du plein écran a été validée sur l'instance
isolée avant les tests d'interface : sa première apparition capturait le focus.

Le lot de régression Windows compte 61 cas ; deux sondes manuelles de mort du processus sont ignorées
faute d'arguments. Le lot final compte 32 cas. Après déduplication des 14 cas communs :
**77 tests Android distincts réussis, aucun échec, deux sondes ignorées**.

Les 17 tests natifs vérifient le lancement, le secours, l'accès local/SAF au bon sous-dossier, les
limites de chemin, le premier rendu, choix multilignes et défilement, gestes, sauvegardes/export,
conservation de la sauvegarde Windows et décodage audio réel. Le nouveau test de la fiche de
sauvegardes permet d'exporter les fichiers natifs sans fichiers Windows. Les régressions couvrent
aussi l'écran de chargement, options, commandes, gestes, reprise, diagnostics, fichiers et migrations.

Une fixture de stockage Windows de **70 002 fichiers** mesure 60 856 ms pour la copie initiale,
**1 020 ms** pour la préparation du cache existant et 8 472 ms pour la synchronisation.
Ce test ne mesure ni la lecture de 70 000 images ni le démarrage complet d'un jeu réel en natif.

## APK R8 réel : parcours natif

L'APK final signé, **non debuggable**, est installé sur l'émulateur. Une instrumentation SDK
indépendante de toute classe de l'application exerce le binaire obfusqué :

1. Lancement d'un jeu de test dont les fichiers Wolf binaires et images sont écrits indépendamment.
2. Contrôle de l'image du titre, puis Valider et nouvelle partie sur une carte avec personnage.
3. Sauvegarde native avec nouvelle date d'écriture, chargement de l'emplacement et retour sur la carte.
4. Ouverture/fermeture des réglages, fermeture normale et rapport de session terminé.
5. Vérification que `Save/original.sav`, la sentinelle Windows de cette fixture, est intacte.

Commandes visibles à **1 152 ms** dans cette sonde. Une exécution debug de la même petite fixture
mesure **165 ms** depuis le début de la préparation : 13 fichiers lus, sept répertoires consultés,
2 785 octets. Ces temps ne sont pas ceux d'un jeu commercial. Les captures du titre, de la carte,
du chargement restauré et des réglages sont examinées. Une première capture de choix a révélé
un chevauchement des libellés multilignes ; le rendu mesure maintenant leur hauteur et un test
vérifie aussi la sélection après défilement.

## APK R8 réel : secours Winlator

Deux copies appartenant au test, des exemples officiels **Wolf 2.29.6.1 et Wolf 3.72.9**, sont lancées
par **WolfNativeActivity en mode Automatique**, puis observées dans WolfRuntimeActivity :

- Les rapports natifs terminés expliquent le secours. Les commandes inconnues ne sont pas ignorées
  silencieusement. Les tests JVM verrouillent aussi les premiers refus d'exécution natifs : variable
  système `9000115` de la commande 111 dans le commun 48 de Wolf 2 ; commande 221 dans le commun 48 de Wolf 3.
- La sonde contrôle une image de partie non vide, envoie Valider, ferme normalement et vérifie
  le serveur Wine supervisé terminé avec code 0. Les captures montrent réellement la carte,
  le personnage et le dialogue de bienvenue des exemples, pas une image de remplacement.
- Le mode **DIRECT** utilise les fichiers d'origine de ces copies : aucun nouvel import du jeu.
  Les configurations temporaires sont restaurées, avec journal clôturé. Les écritures de réglages
  propres à Wolf sont conservées : Wolf 3 crée lui-même un Game.ini initialement absent. Un premier
  test attendait à tort sa suppression ; son assertion a été corrigée, sans modification du code
  de restauration, puis la sonde complète a été rejouée.
- Première installation partagée de Wine et préfixe Wolf 2 : commandes à **82 317 ms**, contrôle de
  la scène à 97 288 ms. Nouveau préfixe Wolf 3, Wine déjà installé : **72 569 ms**, scène à 87 488 ms.
  Les archives avaient été téléchargées auparavant ; l'installateur réel vérifie leurs empreintes,
  extrait et configure le moteur. Le réseau de téléchargement du runtime n'est pas mesuré ici.
- Second lancement Wolf 3, préfixe existant : commandes à **8 047 ms**, scène contrôlée à
  **22 973 ms**, dont environ 14,5 secondes d'attentes fixes de la sonde. Le moteur Windows
  existant et ses fichiers restent conservés ; les octets de Game.ini sont identiques à ceux du départ.

Ces vérifications prouvent l'exécution des deux exemples via le secours, pas leur portage natif,
une partie d'une heure, le chargement d'une sauvegarde Windows de l'utilisateur ou la récupération
d'une mort réelle du processus dans le nouvel hôte natif.

## Artefact et journaux

APK `Astra-1.12.0-beta.14.apk`, 37 642 217 octets, package `fr.astragames.app`, code 42,
Android minimum 26 et cible 36. SHA-256 :

```text
fa85174a358a135055d16d6381375d607fc539acc812e42562bd5b518e9bca63
```

Signature v2/v3 et alignement ZIP 16 Kio vérifiés avec les outils officiels du SDK. Le certificat
de développement historique d'Astra correspond à celui de la bêta 13 ; cette publication n'utilise
pas une nouvelle identité de signature. Les notices MIT du lecteur sont incluses dans l'APK.

Journaux locaux dans `build/wolf-research/` : `beta14-render-final-build.log`,
`beta14-windows-regressions.log`, `beta14-final-sdk-tests.log`, `beta14-native-release-probe.log`,
`beta14-wolf22961-release-probe.log`, `beta14-wolf3729-release-verified.log`,
`beta14-wolf3729-release-warm.log`. Le récapitulatif nominatif et leurs SHA-256 sont dans
`beta14-test-summary.json`. Captures dans `beta14-captures/`. Le mapping R8 reste produit par Gradle
dans `app/build/outputs/mapping/release/`.

## Travail natif restant

Les bibliothèques propriétaires et archives Wolf protégées, la totalité des commandes, variables
système, routes, physics, collisions fines, peaux de fenêtres, pagination du texte, variantes des pics
et synthèse musicale Windows ne sont pas portées. Les sauvegardes Windows n'ont pas de lecteur natif.
L'admission automatique reste volontairement stricte et peut refuser un jeu avant son lancement.

Le Samsung ARM64 et Dragon Blood de l'utilisateur ne sont pas exécutés ici. La compatibilité
universelle et les objectifs permanents de FPS ne sont donc **pas atteints**. Automatique/Winlator
restent les modes adaptés aux jeux actuels jusqu'aux comparaisons différentielles et validations réelles.
