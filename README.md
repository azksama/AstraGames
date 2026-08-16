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
- recherche accessible par bouton flottant, avec moteur en liste et multi-tags regroupés par catégorie ;
- création, édition, suppression, tri et classement multiple des tags par catégories ;
- sélection rapide et groupée des tags pour chaque jeu, sans affectation globale implicite ;
- dossiers et sous-dossiers Astra virtuels navigables, renommables et supprimables sans supprimer les jeux ;
- panneau de filtres compact par moteur, source, dossier Astra, dossier parent système réel, tags, état et tri, sans exposer les sous-dossiers comme entrées séparées ;
- collections intelligentes intégrées et personnalisables avec règles moteur, tag recherchable et trié alphabétiquement, dossier, favori, jaquette, dates et durée de jeu, combinables en ET/OU ;
- résolution guidée des doublons : comparaison côte à côte, choix du principal, fusion des métadonnées et de l’historique, récupération des sauvegardes et stratégie de conflits ;
- suivi du temps de jeu par sessions et affichage de la durée cumulée ;
- gestionnaire de runtimes JoiPlay avec détection dynamique des variantes installées, catalogue officiel mis en cache sept jours et notification des mises à jour ;
- suppression d'un jeu avec exclusion persistante des scans, restauration depuis les paramètres et suppression physique optionnelle explicitement confirmée ;
- sauvegarde et restauration ZIP du catalogue, des profils et des jaquettes, avec raccourci vers le dossier choisi ;
- choix automatique et manuel de jaquettes dans le moteur sélectionné (Yandex, Google, Qwant, Bing, DuckDuckGo ou Ecosia), sans filtre de contenu ajouté par Astra, sélection de l’image affichée, ouverture navigateur, choix local et recadrage libre ;
- enrichissement silencieux des nouveaux jeux via VNDB (sans importer ses tags), puis recherche du thread F95Zone avec le moteur sélectionné et URL canonique conservée dans la fiche ;
- import F95Zone pendant l'ajout ou l'édition : lien conservé dans la fiche, sélection des tags puis choix d'une image recadrable ;
- assistant séquentiel de configuration des nouveaux jeux après chaque scan, avec actions fixes protégées du clavier et des barres système ;
- édition complète des fiches et date du dernier lancement ;
- ajout textuel de tags par virgules ou crochets pendant la configuration et l’édition, avec réutilisation automatique des tags existants ;
- thèmes clair, sombre, système et couleurs dynamiques ;
- flou des jaquettes désactivable, automatique au démarrage ou manuel, avec bascule rapide depuis l’accueil ;
- navigation compacte flottante à trois entrées, glissement horizontal rapide entre les écrans principaux et interface adaptée aux tablettes/pliables ;
- Baseline Profile embarqué et module de génération Macrobenchmark pour accélérer le démarrage et les parcours principaux ;
- copie rapide du nom d’une jaquette en touchant son libellé et raccourci vers le dossier de sauvegarde d’un jeu ;
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
launcher/    JoiPlay et futur MTool
settings/    DataStore
worker/      WorkManager
ui/          ViewModel, navigation et écrans Compose
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

Le test instrumenté Room vérifie SQLite et la synchronisation de l'index FTS sur un appareil ou un émulateur :

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Le module Baseline Profile se compile sans lancer de test sur l’appareil :

```powershell
.\gradlew.bat :baselineprofile:assembleBenchmarkRelease :baselineprofile:assembleNonMinifiedRelease
```

La génération réelle reste volontairement explicite avec `:app:generateBaselineProfile`, car elle nécessite un appareil Android 13+ compatible ou un appareil géré dédié.

## Limites de preuve

Le build et les tests locaux valident le code de l'application. Un lancement réel de jeu exige une installation compatible de JoiPlay, le runtime correspondant et un jeu accessible par un chemin physique que JoiPlay sait lire.
