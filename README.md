# Astra

Astra est une application Android native qui indexe plusieurs dossiers de jeux, détecte leurs moteurs et les lance avec JoiPlay ou, pour Wolf RPG, avec le moteur Windows intégré. Le projet est écrit en Kotlin, Jetpack Compose et Material 3.

La [recherche REA du futur moteur Wolf Android](docs/wolf-native/README.md) documente les exécutables, formats et jalons de reconstruction. Elle ne constitue pas encore un interpréteur natif jouable.

## Fonctions incluses

- onboarding et sélection de dossiers avec `ACTION_OPEN_DOCUMENT_TREE` ;
- onboarding guidé avec choix de langue, import de tags, dossier de jeux et vérification des runtimes JoiPlay ;
- interface disponible en français, anglais (par défaut), espagnol, russe, allemand, chinois et japonais, avec conservation des titres, tags et recherches Unicode ;
- permissions SAF persistantes, sans `MANAGE_EXTERNAL_STORAGE` ;
- sources multiples, activation, suppression, scan individuel ou global ;
- scan ciblé depuis **Paramètres → Sources et scan → Sous-dossier…** : navigation dans une source déjà autorisée, puis analyse du dossier choisi avec les options de récursivité de la source. Ce scan ajoute ou retrouve les jeux sans marquer les autres absents ; le scan complet reste nécessaire pour vérifier les suppressions ;
- scan récursif hors thread principal (garde-fous de 128 niveaux et 100 000 dossiers), avec arrêt et remontée dès qu'un dossier contient un exécutable, et écran de progression indiquant source, chemin, profondeur, dossiers et jeux ;
- détection RPG Maker MV/MZ, Ren'Py, HTML5, Tyrano, Construct, Twine, Electron et Wolf RPG ;
- moteur Wolf intégré expérimental : Wine, Box64 sur ARM64, affichage et commandes dans Astra, import SAF et synchronisation prudente des sauvegardes ;
- fingerprint, reconnaissance de déplacement, doublons et jeux manquants ;
- rapport détaillé et persistant après chaque scan : ajoutés, actualisés, déjà connus, déplacés, manquants, ignorés et erreurs avec chemin et raison ;
- Room avec index FTS4, historique des scans et sessions de lancement ;
- bibliothèque en grille réglable sur 2, 3 ou 4 colonnes ou en liste, favoris, sélection multiple et actions rapides ;
- recherche accessible depuis les champs de l’accueil et de Jeux ou par glissement vers le bas en haut de liste, avec moteur en liste et multi-tags regroupés par catégorie ;
- création, édition, suppression, tri et classement multiple des tags par catégories ;
- sélection rapide et groupée des tags pour chaque jeu, sans affectation globale implicite ;
- dossiers et sous-dossiers Astra virtuels navigables, renommables et supprimables sans supprimer les jeux ;
- panneau de filtres compact par moteur, source, dossier Astra, dossier parent système réel, tags, état et tri, sans exposer les sous-dossiers comme entrées séparées ;
- collections intelligentes intégrées et personnalisables avec règles moteur, tag recherchable et trié alphabétiquement, dossier, favori, jaquette, disponibilité, dates et durée de jeu ; éditeur avec aperçu réel des résultats et choix de toutes les règles ou au moins une ;
- résolution guidée des doublons : comparaison côte à côte, choix du principal, fusion des métadonnées et de l’historique, récupération des sauvegardes et stratégie de conflits ;
- suivi du temps de jeu par sessions et affichage de la durée cumulée ;
- gestionnaire de runtimes JoiPlay avec détection dynamique des variantes installées, catalogue officiel mis en cache sept jours et notification des mises à jour ;
- suppression d'un jeu avec exclusion persistante des scans, restauration depuis les paramètres et suppression physique optionnelle explicitement confirmée ;
- sauvegarde et restauration chiffrée du catalogue, des profils, des jaquettes et des journaux de sauvegardes/mods, avec raccourci vers le dossier choisi ;
- choix automatique et manuel de jaquettes dans le moteur sélectionné (Yandex, Google, Qwant, Bing, DuckDuckGo ou Ecosia), sans filtre de contenu ajouté par Astra, sélection de l’image affichée, ouverture navigateur, choix local et recadrage libre ;
- enrichissement silencieux des nouveaux jeux via VNDB (sans importer ses tags), puis recherche de la page de métadonnées avec le moteur sélectionné et URL canonique conservée dans la fiche ;
- choix entre plusieurs sources de métadonnées, avec leurs liens conservés séparément ;
- import de métadonnées pendant l'ajout ou l'édition : lien conservé dans la fiche, sélection des tags puis choix d'une image recadrable ;
- assistant séquentiel de configuration des nouveaux jeux après chaque scan, avec actions fixes protégées du clavier et des barres système ;
- édition complète des fiches et date du dernier lancement ;
- ajout textuel de tags par virgules ou crochets pendant la configuration et l’édition, avec réutilisation automatique des tags existants ;
- refonte UI/UX inspirée de `design_astra.pen` : polices Geist/Inter embarquées, affiches panoramiques, listes compactes et réglages en lignes ;
- palette Astra par défaut, personnalisable avec le curseur de teinte dans Apparence : fond `#09090f`, cartes et menu `#191e29`, bordures `#2b3242`, actions `#302147`, textes secondaires `#c9b7ff` et titres blancs ;
- flou des jaquettes désactivable, automatique au démarrage ou manuel, avec bascule rapide depuis Jeux ;
- navigation flottante élargie à quatre entrées (Accueil, Jeux, Collections, Paramètres), sans bande opaque derrière la pilule et maintenue en place sous le clavier Android ;
- en-têtes qui défilent avec les pages, avec recherche ancrée en haut de Jeux après leur disparition ;
- accueil avec icône Astra/GAMES, dernière partie en affiche panoramique, bouton de reprise et dix derniers jeux lancés dans une grille à défilement vertical ; bibliothèque complète accessible par Jeux ou Tout voir ;
- accès Historique et Mises à jour dans la section Activité de l’accueil ;
- navigation latérale sur grand écran et en paysage, panneaux côte à côte adaptés à la maquette, largeur de lecture limitée et commandes accessibles avec texte agrandi ;
- volets des paramètres fermés par défaut et verrouillage aux couleurs d’Astra avec le vrai logo et saisie du code masquée ;
- Baseline Profile embarqué et module de génération Macrobenchmark pour accélérer le démarrage et les parcours principaux ;
- copie rapide du nom d’une jaquette en touchant son libellé et raccourci vers le dossier de sauvegarde d’un jeu ;
- verrouillage biométrique et code chiffré, historique des sessions, bouton Outils, éditeur de sauvegardes et gestionnaire de mods ;
- traduction locale des textes RPG Maker MV/MZ avec Google Translate (ML Kit), sans compte ni clé API, cache réutilisable et restauration des originaux ;
- analyse des textes déclenchée uniquement par l’utilisateur, avec résultat persistant et réutilisation des extractions lorsque les fichiers restent identiques ;
- mises à jour d’Astra depuis les releases GitHub publiques, vérification et téléchargement périodiques, contrôle SHA-256 et signature APK avant confirmation Android ;
- mode manuel de traduction MV/MZ : export JSON pour l’IA de votre choix, commandes protégées par marqueurs, import validé et restauration des originaux ;
- payload et Intent JoiPlay sans appel à `ShortcutActivity` ;
- diagnostic de compatibilité par jeu et profils de lancement personnalisables (moteur, dossier, fichier d'entrée, arguments ou application externe) avec test direct ;
- scan au lancement et worker périodique prêt à être planifié ;
- abstraction `CoverProvider` et `MToolLauncher` pour les évolutions.

## Jaquettes et métadonnées

Les recherches d’images et de métadonnées utilisent le moteur choisi dans les paramètres (Yandex, Google, Qwant, Bing, DuckDuckGo ou Ecosia). Aucun paramètre de filtrage de contenu n’est ajouté par Astra. Les résultats d’images sont recherchés automatiquement à l’ouverture, et un bouton ouvre la même recherche dans le navigateur système. L’utilisateur peut aussi afficher les résultats dans le WebView intégré. Les liens de métadonnées sont validés et normalisés en HTTPS avant leur utilisation. VNDB complète silencieusement jaquette, description et développeur lors de l’ajout, sans importer ses tags ; le bouton des paramètres relance cet enrichissement sur les fiches incomplètes.

## Architecture

```text
core/        modèles, accès fichiers, recherche, métadonnées
data/local  Room, DAO et entités
data/scanner détection, normalisation, fingerprint et scan SAF
data/repository orchestration des données
data/saves   codecs, découverte SAF, écriture vérifiée et backups
data/mods    import ZIP, installation et restauration journalisées
data/backup  archives chiffrées du catalogue
launcher/    JoiPlay et futur MTool
translation/ ML Kit, extraction MV/MZ, cache et restauration journalisée
updates/     releases GitHub, téléchargement vérifié et installation Android
settings/    DataStore
worker/      WorkManager
ui/          navigation, écrans par domaine, état et contrôleur des outils
```

L'injection est volontairement explicite via `AppContainer` : elle garde le démarrage lisible et permet de remplacer les dépendances dans les tests sans framework supplémentaire.

## Compiler

Prérequis : JDK 17, Android SDK 36 et Build Tools 36.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

Les tests locaux couvrent la normalisation, les signatures moteur imbriquées, le fingerprint, les doublons/déplacements, le parseur FTS, les modes multi-tags, les runtimes Ren'Py, les parseurs de métadonnées communautaires, de VNDB et du catalogue JoiPlay, ainsi que le payload JoiPlay.

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Les tests instrumentés vérifient Room/FTS, les archives du catalogue, les sauvegardes et les mods, sur fichiers locaux et URI SAF imbriquées :

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Le module Baseline Profile se compile sans lancer de test sur l’appareil :

```powershell
.\gradlew.bat :baselineprofile:assembleBenchmarkRelease :baselineprofile:assembleNonMinifiedRelease
```

La génération réelle reste volontairement explicite avec `:app:generateBaselineProfile`, car elle nécessite un appareil Android 13+ compatible ou un appareil géré dédié.

## Sauvegardes de jeux et mods

L’éditeur reconnaît les sauvegardes Ren’Py ZIP contenant `log` (pickle), les anciens conteneurs bruts/zlib, RPG Maker MV (LZ-String), MZ (base64/zlib) et RGSS XP/VX/VX Ace (Marshal, y compris plusieurs flux). Il conserve le conteneur d’origine, crée une copie de sécurité avant écriture et relit le résultat. Une sauvegarde modifiée depuis l’ouverture de l’éditeur doit être rechargée. Les structures non prises en charge sont refusées avant écriture ; la compatibilité n’est pas universelle pour les jeux utilisant un format personnalisé.

Après modification, Ren’Py peut demander d’autoriser le chargement de la sauvegarde. Les métadonnées et la capture restent dans l’archive ; la signature du contenu original n’est pas réutilisée. Les tests utilisent des fixtures synthétiques générées par CPython, avec des références partagées, des cycles et des caractères Unicode. Vérification indépendante après les tests Gradle :

```powershell
python -X utf8 scripts/verify-renpy-fixtures.py
```

Depuis **Outils → Mods**, sélectionner un dépôt, importer le ZIP puis installer le mod du moteur concerné. Le dépôt contient des sous-dossiers `RenPy`, `RPGMakerMV`, `RPGMakerMZ`, etc. Une archive peut fournir ce manifeste à sa racine :

```json
{
  "formatVersion": 1,
  "id": "example.mod",
  "name": "Example mod",
  "engines": ["RenPy"],
  "installMode": "OVERLAY",
  "target": "game",
  "filesRoot": "files"
}
```

`files/` contient les fichiers à installer, relativement à `target`. `COPY` accepte uniquement les nouveaux fichiers ; `OVERLAY` ajoute ou remplace avec backup (`REPLACE` reste un alias historique). Sans manifeste, le moteur du jeu sélectionné et l’arborescence du ZIP déterminent la destination. Un dossier d’emballage est retiré sans écraser les niveaux significatifs `game`, `www` ou `js`.

L’import ZIP est limité à 4 000 entrées et 200 Mio décompressés. La désinstallation vérifie les fichiers avant de restaurer les originaux. Un conflit avec une modification ultérieure est signalé ; une installation interrompue conserve son journal et propose **Restaurer**. Les mods RPG Maker doivent inclure leur configuration d’activation lorsque nécessaire : copier un plugin JavaScript isolé ne le déclare pas automatiquement dans `js/plugins.js`.

Les archives du catalogue `.astra` utilisent la clé Android Keystore de l’installation actuelle. Elles ne constituent pas une migration vers un autre téléphone ou après désinstallation de l’app. Les anciens ZIP non chiffrés restent importables (schémas 5 à 9). Les jeux et leurs permissions SAF ne sont pas embarqués dans ces archives.

Les bilans et limites des vérifications figurent dans l’[audit du 5 octobre](docs/AUDIT_2026-10-05.md), la [revue sauvegardes/mods du 16 septembre](docs/AUDIT_2026-09-16.md) et la [revue technique/UI du 19 septembre](docs/AUDIT_2026-09-19.md).

## Traduction RPG Maker MV/MZ

Le mode **Fichier pour IA** permet d’exporter tous les textes standards pris en charge, avec leur contexte, puis d’importer le fichier traduit. Il ne nécessite ni modèle ML Kit ni compte connecté dans Astra. Seuls les champs `translation` doivent changer ; les marqueurs `⟦ASTRA_…⟧` protègent les commandes RPGM. Le jeu doit rester dans la même version entre export et import. Voir le [guide complet et le prompt prêt à utiliser](docs/MANUAL_TRANSLATION.md).

Depuis **Fiche du jeu → Outils → Traduire le jeu**, toucher **Analyser les textes**, choisir la langue source et la langue cible, fermer le jeu puis toucher **Traduire avec Google**. Google Translate (ML Kit) télécharge les modèles nécessaires au premier usage, puis traduit sur l’appareil sans compte ni clé API. Le téléchargement utilise uniquement le Wi-Fi par défaut ; cette option peut être désactivée. Une fois les modèles disponibles, la traduction fonctionne hors connexion. Garder Astra ouvert pendant le traitement ; après un arrêt, les fragments déjà traduits sont réutilisés au prochain essai.

L’ouverture de l’écran ne lance aucune extraction. Une analyse terminée est conservée dans le stockage privé de l’application et réaffichée à la prochaine ouverture. **Actualiser l’analyse** permet de la relancer explicitement. Avant traduction, export ou import, les empreintes des fichiers sont contrôlées et les fichiers modifiés sont relus ; une analyse interrompue ou invalide ne remplace pas le dernier résultat complet.

Le module lit les JSON standards dans `data` ou `www/data` : dialogues, choix, noms affichés, descriptions, termes des menus et messages de combat. Les commandes de mise en forme et variables comme `\N[1]`, `\V[2]`, `\C[3]`, `%1` et les retours à la ligne sont conservés. Les textes identiques sont traduits une seule fois, avec un cache distinct pour chaque paire de langues. Les scripts, notes, noms de ressources, images, contenus propres aux plugins et sauvegardes du joueur ne sont pas traduits. XP, VX et VX Ace ne sont pas couverts par ce module.

Le temps restant est estimé après quelques fragments, à partir des caractères effectivement traités pendant cette tentative ; le téléchargement des modèles et les traductions déjà en cache sont exclus de ce calcul. Les retours à la ligne ajoutés par le modèle dans un fragment sont normalisés en espaces, sans toucher aux retours à la ligne du jeu. Une sortie vide ou contenant des commandes inattendues laisse le fragment original en place au lieu de bloquer toute la traduction. Le bilan indique le nombre de fragments uniques conservés et reste disponible après réouverture de l’écran. Ces fragments ne sont pas mis en cache comme des traductions réussies ; ils peuvent être retentés après restauration des originaux.

Tous les originaux sont sauvegardés et relus avant application, dans `data/.astra-translation` (ou `www/data/.astra-translation`). **Conserver ce dossier**, qui reste auprès du jeu et n’est pas inclus dans l’archive du catalogue. **Restaurer les originaux** annule la traduction à l’octet près. La restauration refuse d’écraser un fichier modifié depuis par un autre outil. Les écritures interrompues sont journalisées ; si une reprise automatique échoue, ce même écran permet de relancer la restauration. Une interruption pendant la création initiale du journal, ou un stockage endommagé, peut nécessiter de récupérer manuellement les fichiers depuis le sous-dossier `original`.

Les mods installés via Astra doivent être désinstallés avant traduction ou export manuel ; restaurer ensuite les originaux avant d’installer des mods ou une mise à jour du jeu. Limites de traitement : 2 000 fichiers, 16 Mio par fichier et 64 Mio de JSON cumulés. Les textes automatiques longs sont découpés en fragments de 3 500 caractères maximum, sans couper les commandes. Le fichier manuel est limité à 64 Mio et 200 000 entrées. Il n’y a pas de remise en page automatique : une traduction plus longue peut déborder des fenêtres du jeu, et certaines polices ne contiennent pas les caractères de la langue cible. La qualité et la durée dépendent du texte, de la paire de langues et du téléphone ; traduire un jeu complet en quelques secondes n’est pas garanti.

La traduction automatique est fournie par Google Translate, sans garantie d’exactitude. [Fonctionnement de ML Kit](https://developers.google.com/ml-kit/language/translation) et [conditions d’utilisation](https://developers.google.com/ml-kit/language/translation/translation-terms). Les paires sans anglais utilisent l’anglais comme langue intermédiaire, ce qui peut affecter la qualité.

Le test réel du modèle est explicite et utilise uniquement des textes synthétiques :

```powershell
adb shell am instrument -w -r -e class fr.astragames.app.translation.GameTranslationTest -e liveTranslation true fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
```

Sans l’argument `liveTranslation`, le test nécessitant les modèles est ignoré ; les tests d’extraction, de stockage local/SAF, d’interface et de restauration restent exécutables sans téléchargement.

## Mises à jour d’Astra

Ouvrir **Paramètres → Mises à jour d’Astra**. Astra interroge par défaut les releases stables du [dépôt public](https://github.com/azksama/AstraGames/releases), sans compte ni jeton. L’option **Recevoir les versions bêta**, désactivée par défaut, inclut aussi les préversions. Son changement relance la vérification et retire toute mise à jour en attente du canal précédent. La version la plus récente est choisie en respectant l’ordre des versions (bêta 2, bêta 10, puis stable), sans retour automatique vers une ancienne version. Le choix est conservé après fermeture et utilisé aussi par les vérifications automatiques.

La vérification périodique et le téléchargement automatique sont activés par défaut, avec un réseau non facturé (Wi-Fi) requis pour le téléchargement. La cadence demandée est de 12 heures ; Android peut la décaler selon la batterie et le réseau.

Une notification ouvre l’écran lorsqu’un APK est prêt. **Télécharger et installer** enchaîne le téléchargement manuel et l’ouverture de l’installateur ; **Installer la mise à jour** utilise le fichier déjà prêt. Android demande l’autorisation d’installer depuis Astra au premier usage, puis la confirmation de chaque installation. Astra ne peut pas effectuer une installation silencieuse sur un appareil Android ordinaire.

Le fichier doit correspondre à la taille et au SHA-256 annoncés par GitHub, au package Astra, à une version supérieure et à la signature de l’installation actuelle. Ces contrôles sont répétés avant de donner accès au fichier à l’installateur. Les APK non signés sont exclus. Les versions distribuées avec la clé de développement locale peuvent se mettre à jour entre elles ; une installation signée avec une autre clé doit disposer d’un APK compatible.

## Limites de preuve

Les jeux délégués à JoiPlay nécessitent son runtime et un chemin physique accessible. Wolf RPG utilise son moteur intégré : les échantillons officiels 2.2961 et 3.729 ont été lancés et manipulés sur Android Emulator API 36.1 x86_64. Le parcours ARM64/Box64 est compilé, mais pas validé sur un téléphone ARM64 ; aucune garantie de compatibilité universelle. Voir le [guide et les preuves Wolf](docs/wolf-windows-runtime.md).


## Jouer à Wolf RPG dans Astra

Disponible dans la [préversion 1.12.0-beta.13](https://github.com/azksama/AstraGames/releases/tag/v1.12.0-beta.13). Depuis beta.2, activer **Paramètres → Mises à jour d’Astra → Recevoir les versions bêta**, puis rechercher la mise à jour. Les versions antérieures nécessitent une première installation manuelle de cet APK depuis GitHub.

Ajouter le dossier contenant le jeu Windows décompressé, puis lancer son scan. Astra détecte
`Game.exe` avec `Data.wolf` ou `Data/BasicData/Game.dat`. Ouvrir la fiche et toucher **Jouer** ;
le profil **Astra · Wolf intégré** utilise le jeu original sans conversion ni application externe.
Au premier lancement, Astra télécharge et vérifie les composants et prépare Windows.
Depuis la bêta 10, **Outils → Réglages Wolf RPG → Fichiers du jeu** utilise le dossier d’origine
si son accès direct est possible. Pour un dossier local sur Android 11+, le bouton **Accès à tous
les fichiers · réglages Android** permet d’accorder l’autorisation facultative nécessaire.
Les ressources ne sont alors pas importées et le jeu écrit directement dans son dossier.
La copie privée reste disponible pour les fournisseurs cloud, les accès refusés ou les conflits
de sauvegarde. Chaque jeu conserve son propre préfixe Windows ; son premier démarrage peut
encore demander une initialisation, même sans import. Les composants sont partagés entre jeux.

Les flèches, Valider, Retour et Shift sont disponibles à l'écran ; clavier et boutons de manette
sont également routés au jeu. **Quitter** ferme le moteur et synchronise les fichiers modifiés.
En cas de conflit ou de dossier non accessible, Astra conserve la sauvegarde privée et propose
son export ZIP. **Outils → Dossier des sauvegardes** affiche les chemins réels et permet l’export
des sauvegardes du jeu ainsi que des données utilisateur de son préfixe Windows, sous `Windows/`.
Le dossier peut s’appeler `SaveData` ou `Saves`, sans être nécessairement `Save`.
Les réglages logiciels de `Game.ini` sont privés en mode copie et temporaires en mode direct :
la configuration d’origine est conservée et restaurée sans effacer les préférences écrites par le jeu.
Les archives du catalogue Astra n'incluent pas cette copie des jeux ni leurs préfixes Windows.
Les notices sont accessibles dans **Paramètres → Moteur Wolf · licences**.
Les essais du lancement direct et de la récupération des sauvegardes sont détaillés dans la
[validation de la bêta 10](docs/VALIDATION_1.12.0-beta.10.md).

La bêta 11 récupère les anciens processus Windows et audio appartenant à Astra après un arrêt
forcé, puis reprend le lancement sans réinitialiser les sauvegardes. Une nouvelle session attend
la fermeture de la précédente. L’écran de chargement indique huit étapes, les sous-étapes,
le temps écoulé, ainsi que les octets téléchargés ou les fichiers extraits lorsqu’ils sont disponibles.
Les commandes et le compteur restent masqués pendant la préparation, les consoles Windows
et les images noires ; ils apparaissent avec la première image du jeu. Une attente trop longue
ou un arrêt du jeu avant cette image fournit une erreur avec le diagnostic.

Un toucher bref de l’image avec deux doigts envoie **Retour**, en respectant sa touche configurée.
**Outils → Réglages Wolf RPG → Zoom à deux doigts** permet le pincement de 1× à 4× et le
déplacement de l’image agrandie avec deux doigts. Ce réglage est désactivé par défaut, mémorisé
par jeu et immédiat ; le désactiver restaure le cadrage. Le pincement ne déclenche pas Retour.
Voir les [tests de reprise, de chargement et de gestes](docs/VALIDATION_1.12.0-beta.11.md).

La bêta 12 reprend un jeu déjà lancé depuis son dossier d’origine sans relire les anciens index
d’import ni interroger son fournisseur SAF. La récupération de la configuration, la vérification
du dossier et une éventuelle migration des sauvegardes affichent chacune leur détail. La préparation
et les écritures du diagnostic s’effectuent hors du fil d’affichage. **Diagnostic du lancement**
reste accessible sur l’écran de chargement ; une attente de plus de 20 secondes sans changement
de sous-étape ajoute un état des threads Astra au rapport, sans conclure automatiquement à un crash.
Voir les [preuves et limites de la bêta 12](docs/VALIDATION_1.12.0-beta.12.md).

La bêta 13 accélère aussi le premier passage d’une ancienne copie privée au dossier d’origine.
La récupération utilise le dossier physique accessible, sans recherches SAF fichier par fichier,
et affiche les nombres de fichiers vérifiés, récupérés et en conflit. Les ressources inchangées
restent en cache ; l’index est enregistré en fin de récupération, également en cas d’annulation.
La version précédente d’une sauvegarde remplacée reste sauvegardée ; une divergence conserve
la copie privée utilisable et exportable. Les dossiers accessibles uniquement par SAF utilisent
des lectures groupées des noms et une nouvelle vérification avant chaque remplacement.
Voir les [preuves et limites de la bêta 13](docs/VALIDATION_1.12.0-beta.13.md).

Si le lancement s’arrête, ouvrir **Paramètres → Diagnostic Wolf RPG**, activer **Mode debug Wolf RPG**,
puis relancer le jeu. Le rapport indique la dernière étape, le code de sortie, les exceptions et les
journaux de Box64/Wine et de l’audio. Le mode debug ajoute les détails du chargeur Wine, de Box64,
un test de démarrage de Box64 avant Wine sur ARM64, et Logcat limité au processus Astra.
Les erreurs détectées proposent aussi **Voir le rapport de diagnostic**. Après une session interrompue,
Astra présente automatiquement le rapport à sa réouverture, après déverrouillage si nécessaire.
Fermer cet aperçu conserve le rapport dans les paramètres ; **Partager le rapport** permet son export.
La bêta 4 corrige le crash natif Zstd pendant l’installation de Box64 dans l’APK optimisé et la reprise
d’une extraction contenant des liens symboliques existants. Voir les [preuves de validation](docs/VALIDATION_1.12.0-beta.4.md).

La bêta 5 supervise le serveur Wine, attend sa fermeture et conserve son journal séparément.
La préparation regroupe les requêtes SAF par dossier et actualise sa progression une fois par seconde.
Les commandes utilisent une croix à gauche et les actions à droite ; **Menu** permet de les masquer
ou de quitter. Le rendu a été contrôlé en portrait et paysage sur émulateur.
Voir les [preuves et limites de la bêta 5](docs/VALIDATION_1.12.0-beta.5.md).

La bêta 6 réutilise la copie du jeu sans relire tous ses fichiers. Les copies déjà initialisées
sur la bêta 5 sont reprises. Les dossiers Save/Saves et fichiers .sav/.save connus restent
vérifiés à chaque lancement ; les conflits restent locaux. Pour une mise à jour externe,
un mod externe ou une sauvegarde dans un emplacement personnalisé, utiliser **Fiche du jeu →
Outils → Actualiser les fichiers du jeu**, puis relancer. Les mods installés/désinstallés dans
Astra déclenchent cette actualisation automatiquement. Aucun effacement des données n’est nécessaire.
La synchronisation vérifie le contenu des sauvegardes et des fichiers nouveaux/modifiés ;
les ressources inchangées sont identifiées par taille/date. Au premier arrêt après migration,
les anciens fichiers sans index de dates sont encore vérifiés par hash.
Le diagnostic joint `Game_ErrorLog.txt`, en distinguant un fichier historique d’un fichier modifié
pendant la partie, et préserve le début des logs. Voir les [mesures et limites de la bêta 6](docs/VALIDATION_1.12.0-beta.6.md).

La bêta 7 ajoute **Outils → Réglages Wolf RPG**, également accessibles dans **Menu → Réglages du jeu** :
profils Équilibré/Rapide/Stable, affichage de 800×600 à 1920×1080, lissage, limite de 30/60 images/s
et compteur des images reçues. Dans cette version, le bureau était à 1280×960 par défaut ; l’image du jeu est agrandie
en conservant ses proportions. La définition interne reste celle du jeu. Les profils Box64 et
la résolution prennent effet au prochain lancement ; les autres réglages sont immédiats.
Les notifications de dessin sont regroupées et le debug n’active plus la trace de chaque exception.
Le profil Stable conserve les paramètres CPU de la bêta 6.

**Menu → Modifier les touches** permet de glisser chaque bouton et de toucher un bouton pour changer
son action. Taille, opacité, remappage et positions portrait/paysage sont mémorisés par jeu.
Le tactile propose le clic à l’endroit touché (déplacement si le jeu le gère) ou le maintien d’une
direction depuis le centre de l’image. Ce second mode ne calcule pas un chemin vers une destination.
Voir les [preuves et limites de la bêta 7](docs/VALIDATION_1.12.0-beta.7.md), notamment la distinction
entre les FPS mesurés sur l’émulateur x86_64 et les performances ARM64 encore à confirmer sur appareil.

La bêta 8 transfère uniquement les zones modifiées de l’image et réutilise les images identiques,
y compris lorsque Wine transmet une fenêtre entière pour une petite modification de texte.
Les dessins sont regroupés sur le rafraîchissement Android, dans la limite 30/60 choisie.
Le compteur devient **Images modifiées** : une valeur faible sur un écran immobile est normale,
et ce compteur ne mesure pas la cadence interne de Wolf. Le diagnostic ajoute le coût de
soumission du rendu, le volume des transferts et leurs tailles. Les mesures sur émulateur
réduisent environ de moitié le coût d’un transfert de texte ; un minimum permanent de 30–60 FPS
sur téléphone n’est pas établi. Voir les [mesures de la bêta 8](docs/VALIDATION_1.12.0-beta.8.md).

La bêta 9 utilise toute la surface de l’écran et cadre directement l’image du jeu, indépendamment
du format du bureau Windows. **Menu → Réglages du jeu → Cadrage** propose trois choix immédiats,
mémorisés par jeu : **Image entière** (défaut, agrandissement maximal sans déformation),
**Remplir** (recadrage sans déformation) et **Étirer** (plein écran avec déformation).
Les marges nécessaires au format du jeu restent en mode Image entière ; les deux autres modes
remplissent l’écran. Les commandes restent accessibles autour des encoches et des barres système.
L’affichage Windows automatique utilise 1280×960 lors d’un lancement en portrait et 1280×720
en paysage. Une définition choisie manuellement reste conservée. Ce bureau ne modifie pas
la définition interne du jeu : seuls les jeux qui dessinent en 16:9 fournissent une image 16:9 complète.

Les transferts des fenêtres logicielles utilisent un instantané réutilisé de la zone modifiée :
Wine peut préparer les images suivantes pendant les transferts du pilote graphique.
Les buffers partagés et les images GPU gardent leur synchronisation complète.
Le diagnostic mesure aussi le temps du verrou des images. Voir les
[mesures et limites de la bêta 9](docs/VALIDATION_1.12.0-beta.9.md).

Les cinq dernières sessions sont conservées localement, avec rotation des logs (512 Kio × deux
fichiers et 64 Kio de début par flux). Aucun envoi automatique ; l’export texte peut contenir le nom du jeu et des
chemins. Les rapports sont exclus de la sauvegarde système. Android 11 ou supérieur peut fournir
la raison d’un arrêt du processus Astra ; si elle manque, le rapport le précise sans inventer
de cause. Le mode debug aide à diagnostiquer le crash ; il ne garantit pas sa résolution.
