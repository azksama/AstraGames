# Astra

Astra est une application Android native qui indexe plusieurs dossiers de jeux, détecte leurs moteurs et les lance avec JoiPlay. Le projet est écrit en Kotlin, Jetpack Compose et Material 3.

## Fonctions incluses

- onboarding et sélection de dossiers avec `ACTION_OPEN_DOCUMENT_TREE` ;
- onboarding guidé avec choix de langue, import de tags, dossier de jeux et vérification des runtimes JoiPlay ;
- interface disponible en français, anglais (par défaut), espagnol, russe, allemand, chinois et japonais, avec conservation des titres, tags et recherches Unicode ;
- permissions SAF persistantes, sans `MANAGE_EXTERNAL_STORAGE` ;
- sources multiples, activation, suppression, scan individuel ou global ;
- scan toujours récursif de profondeur illimitée hors thread principal, avec arrêt et remontée dès qu'un dossier contient un exécutable, et écran de progression indiquant source, chemin, profondeur, dossiers et jeux ;
- détection RPG Maker MV/MZ, Ren'Py, HTML5, Tyrano, Construct, Twine et Electron ;
- fingerprint, reconnaissance de déplacement, doublons et jeux manquants ;
- rapport détaillé et persistant après chaque scan : ajoutés, actualisés, déjà connus, déplacés, manquants, ignorés et erreurs avec chemin et raison ;
- Room avec index FTS4, historique des scans et sessions de lancement ;
- bibliothèque en grille réglable sur 2, 3 ou 4 colonnes ou en liste, favoris, sélection multiple et actions rapides ;
- recherche accessible par glissement vers le bas en haut de liste, avec moteur en liste et multi-tags regroupés par catégorie ;
- création, édition, suppression, tri et classement multiple des tags par catégories ;
- sélection rapide et groupée des tags pour chaque jeu, sans affectation globale implicite ;
- dossiers et sous-dossiers Astra virtuels navigables, renommables et supprimables sans supprimer les jeux ;
- panneau de filtres compact par moteur, source, dossier Astra, dossier parent système réel, tags, état et tri, sans exposer les sous-dossiers comme entrées séparées ;
- collections intelligentes intégrées et personnalisables avec règles moteur, tag recherchable et trié alphabétiquement, dossier, favori, jaquette, dates et durée de jeu ; les nouvelles collections appliquent toutes leurs règles, les anciennes conservent leur mode enregistré ;
- résolution guidée des doublons : comparaison côte à côte, choix du principal, fusion des métadonnées et de l’historique, récupération des sauvegardes et stratégie de conflits ;
- suivi du temps de jeu par sessions et affichage de la durée cumulée ;
- gestionnaire de runtimes JoiPlay avec détection dynamique des variantes installées, catalogue officiel mis en cache sept jours et notification des mises à jour ;
- suppression d'un jeu avec exclusion persistante des scans, restauration depuis les paramètres et suppression physique optionnelle explicitement confirmée ;
- sauvegarde et restauration chiffrée du catalogue, des profils, des jaquettes et des journaux de sauvegardes/mods, avec raccourci vers le dossier choisi ;
- choix automatique et manuel de jaquettes dans le moteur sélectionné (Yandex, Google, Qwant, Bing, DuckDuckGo ou Ecosia), sans filtre de contenu ajouté par Astra, sélection de l’image affichée, ouverture navigateur, choix local et recadrage libre ;
- enrichissement silencieux des nouveaux jeux via VNDB (sans importer ses tags), puis recherche du thread F95Zone avec le moteur sélectionné et URL canonique conservée dans la fiche ;
- import F95Zone pendant l'ajout ou l'édition : lien conservé dans la fiche, sélection des tags puis choix d'une image recadrable ;
- assistant séquentiel de configuration des nouveaux jeux après chaque scan, avec actions fixes protégées du clavier et des barres système ;
- édition complète des fiches et date du dernier lancement ;
- ajout textuel de tags par virgules ou crochets pendant la configuration et l’édition, avec réutilisation automatique des tags existants ;
- palette Astra fixe : fond `#09090f`, cartes et menu `#716b82`, actions `#302147`, textes secondaires `#c9b7ff` et titres blancs ;
- flou des jaquettes désactivable, automatique au démarrage ou manuel, avec bascule rapide depuis l’accueil ;
- navigation flottante élargie à quatre entrées (Accueil, Jeux, Collections, Paramètres), sans bande opaque derrière la pilule ;
- accueil avec icône Astra/GAMES, dernière partie en affiche panoramique, bouton de reprise et deux jeux précédents ; bibliothèque complète accessible par Jeux ou Tout voir ;
- Baseline Profile embarqué et module de génération Macrobenchmark pour accélérer le démarrage et les parcours principaux ;
- copie rapide du nom d’une jaquette en touchant son libellé et raccourci vers le dossier de sauvegarde d’un jeu ;
- verrouillage biométrique et code chiffré, historique des sessions, bouton Outils, éditeur de sauvegardes et gestionnaire de mods ;
- traduction locale des textes RPG Maker MV/MZ avec Google Translate (ML Kit), sans compte ni clé API, cache réutilisable et restauration des originaux ;
- payload et Intent JoiPlay sans appel à `ShortcutActivity` ;
- diagnostic de compatibilité par jeu et profils de lancement personnalisables (moteur, dossier, fichier d'entrée, arguments ou application externe) avec test direct ;
- scan au lancement et worker périodique prêt à être planifié ;
- abstraction `CoverProvider` et `MToolLauncher` pour les évolutions.

## Jaquettes et F95Zone

Les recherches d’images et F95Zone utilisent le moteur choisi dans les paramètres (Yandex, Google, Qwant, Bing, DuckDuckGo ou Ecosia), avec la requête directe. La recherche F95Zone conserve le terme `f95zone.to` mais n’utilise plus l’opérateur `site:`. Aucun paramètre de filtrage de contenu n’est ajouté par Astra. Les résultats d’images sont recherchés automatiquement à l’ouverture, et un bouton ouvre la même recherche dans le navigateur système. L’utilisateur peut aussi afficher les résultats dans le WebView intégré. Tout thread accepté est réduit à l’URL canonique HTTPS F95Zone, terminée juste après son identifiant. VNDB complète silencieusement jaquette, description et développeur lors de l’ajout, sans importer ses tags ; le bouton des paramètres relance cet enrichissement sur les fiches incomplètes.

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

Les tests locaux couvrent la normalisation, les signatures moteur imbriquées, le fingerprint, les doublons/déplacements, le parseur FTS, les modes multi-tags, les runtimes Ren'Py, les parseurs F95Zone, VNDB et du catalogue JoiPlay, ainsi que le payload JoiPlay.

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

Les archives du catalogue `.astra` utilisent la clé Android Keystore de l’installation actuelle. Elles ne constituent pas une migration vers un autre téléphone ou après désinstallation de l’app. Les anciens ZIP non chiffrés restent importables (schémas 5 à 8). Les jeux et leurs permissions SAF ne sont pas embarqués dans ces archives.

Les bilans et limites des vérifications figurent dans la [revue sauvegardes/mods du 16 septembre](docs/AUDIT_2026-09-16.md) et la [revue technique/UI du 19 septembre](docs/AUDIT_2026-09-19.md).

## Traduction locale RPG Maker MV/MZ

Depuis **Fiche du jeu → Outils → Traduire le jeu**, choisir la langue source et la langue cible, fermer le jeu puis toucher **Traduire avec Google**. Google Translate (ML Kit) télécharge les modèles nécessaires au premier usage, puis traduit sur l’appareil sans compte ni clé API. Le téléchargement utilise uniquement le Wi-Fi par défaut ; cette option peut être désactivée. Une fois les modèles disponibles, la traduction fonctionne hors connexion. Garder Astra ouvert pendant le traitement ; après un arrêt, les fragments déjà traduits sont réutilisés au prochain essai.

Le module lit les JSON standards dans `data` ou `www/data` : dialogues, choix, noms affichés, descriptions, termes des menus et messages de combat. Les commandes de mise en forme et variables comme `\N[1]`, `\V[2]`, `\C[3]`, `%1` et les retours à la ligne sont conservés. Les textes identiques sont traduits une seule fois, avec un cache distinct pour chaque paire de langues. Les scripts, notes, noms de ressources, images, contenus propres aux plugins et sauvegardes du joueur ne sont pas traduits. XP, VX et VX Ace ne sont pas couverts par ce module.

Le temps restant est estimé après quelques fragments, à partir des caractères effectivement traités pendant cette tentative ; le téléchargement des modèles et les traductions déjà en cache sont exclus de ce calcul. Les retours à la ligne ajoutés par le modèle dans un fragment sont normalisés en espaces, sans toucher aux retours à la ligne du jeu. Une sortie vide ou contenant des commandes inattendues laisse le fragment original en place au lieu de bloquer toute la traduction. Le bilan indique le nombre de fragments uniques conservés et reste disponible après réouverture de l’écran. Ces fragments ne sont pas mis en cache comme des traductions réussies ; ils peuvent être retentés après restauration des originaux.

Tous les originaux sont sauvegardés et relus avant application, dans `data/.astra-translation` (ou `www/data/.astra-translation`). **Conserver ce dossier**, qui reste auprès du jeu et n’est pas inclus dans l’archive du catalogue. **Restaurer les originaux** annule la traduction à l’octet près. La restauration refuse d’écraser un fichier modifié depuis par un autre outil. Les écritures interrompues sont journalisées ; si une reprise automatique échoue, ce même écran permet de relancer la restauration. Une interruption pendant la création initiale du journal, ou un stockage endommagé, peut nécessiter de récupérer manuellement les fichiers depuis le sous-dossier `original`.

Les mods installés via Astra doivent être désinstallés avant traduction ; restaurer ensuite les originaux avant d’installer des mods ou une mise à jour du jeu. Limites de traitement : 2 000 fichiers, 16 Mio par fichier, 64 Mio de JSON cumulés et 4 000 caractères par fragment. Il n’y a pas de remise en page automatique : une traduction plus longue peut déborder des fenêtres du jeu, et certaines polices ne contiennent pas les caractères de la langue cible. La qualité et la durée dépendent du texte, de la paire de langues et du téléphone ; traduire un jeu complet en quelques secondes n’est pas garanti.

La traduction automatique est fournie par Google Translate, sans garantie d’exactitude. [Fonctionnement de ML Kit](https://developers.google.com/ml-kit/language/translation) et [conditions d’utilisation](https://developers.google.com/ml-kit/language/translation/translation-terms). Les paires sans anglais utilisent l’anglais comme langue intermédiaire, ce qui peut affecter la qualité.

Le test réel du modèle est explicite et utilise uniquement des textes synthétiques :

```powershell
adb shell am instrument -w -r -e class fr.astragames.app.translation.GameTranslationTest -e liveTranslation true fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
```

Sans l’argument `liveTranslation`, le test nécessitant les modèles est ignoré ; les tests d’extraction, de stockage local/SAF, d’interface et de restauration restent exécutables sans téléchargement.

## Limites de preuve

Le build et les tests locaux valident le code de l'application. Un lancement réel de jeu exige une installation compatible de JoiPlay, le runtime correspondant et un jeu accessible par un chemin physique que JoiPlay sait lire.
