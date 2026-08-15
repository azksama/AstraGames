# Astra Games

Astra Games est une application Android native qui indexe plusieurs dossiers de jeux, détecte leurs moteurs et les lance avec JoiPlay. Le projet est écrit en Kotlin, Jetpack Compose et Material 3.

## Fonctions incluses

- onboarding et sélection de dossiers avec `ACTION_OPEN_DOCUMENT_TREE` ;
- permissions SAF persistantes, sans `MANAGE_EXTERNAL_STORAGE` ;
- sources multiples, activation, suppression, scan individuel ou global ;
- scan toujours récursif de profondeur illimitée hors thread principal, avec arrêt et remontée dès qu'un dossier contient un exécutable, et écran de progression indiquant source, chemin, profondeur, dossiers et jeux ;
- détection RPG Maker MV/MZ, Ren'Py, HTML5, Tyrano, Construct, Twine et Electron ;
- fingerprint, reconnaissance de déplacement, doublons et jeux manquants ;
- rapport détaillé et persistant après chaque scan : ajoutés, actualisés, déjà connus, déplacés, manquants, ignorés et erreurs avec chemin et raison ;
- Room avec index FTS4, historique des scans et sessions de lancement ;
- bibliothèque en grille réglable sur 2, 3 ou 4 colonnes ou en liste, favoris, collections intelligentes ;
- recherche accessible par bouton flottant, avec moteur en liste et multi-tags regroupés par catégorie ;
- création, édition, suppression, tri et classement multiple des tags par catégories ;
- sélection rapide et groupée des tags pour chaque jeu, sans affectation globale implicite ;
- dossiers et sous-dossiers Astra virtuels navigables, renommables et supprimables sans supprimer les jeux ;
- filtre de bibliothèque par dossier Astra incluant automatiquement toute son arborescence ;
- collections intelligentes cliquables : favoris, ajouts récents, jeux récemment lancés, jamais joués, manquants et sans jaquette ;
- détection des groupes de doublons avec affichage de leurs emplacements ;
- suppression d'un jeu avec exclusion persistante des scans, restauration depuis les paramètres et suppression physique optionnelle explicitement confirmée ;
- sauvegarde et restauration ZIP du catalogue, des profils et des jaquettes, avec raccourci vers le dossier choisi ;
- recherche de 10 jaquettes par scraping de Google Images, choix local et recadrage libre via URI Android sécurisée ;
- import F95Zone pendant l'ajout ou l'édition : sélection des tags puis choix d'une image recadrable ;
- assistant séquentiel de configuration des nouveaux jeux après chaque scan ;
- édition complète des fiches et date du dernier lancement ;
- thèmes clair, sombre, système et couleurs dynamiques ;
- navigation compacte flottante à trois entrées et glissement horizontal sur toute la page entre les écrans principaux ;
- payload et Intent JoiPlay sans appel à `ShortcutActivity` ;
- diagnostic de compatibilité par jeu et profils de lancement personnalisables (moteur, dossier, fichier d'entrée, arguments ou application externe) avec test direct ;
- scan au lancement et worker périodique prêt à être planifié ;
- abstraction `CoverProvider` et `MToolLauncher` pour les évolutions.

## Jaquettes et F95Zone

La recherche Google Images se fait directement depuis l'application, sans clé API. Comme tout scraping, sa disponibilité dépend du HTML et des protections momentanément servis par Google ; le choix et le recadrage d'une image locale restent toujours disponibles. L'import F95Zone accepte exclusivement les liens HTTPS `f95zone.to/threads/…` et affiche les tags puis les images avant l'application des données.

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

Les tests locaux couvrent la normalisation, les signatures moteur imbriquées, le fingerprint, les doublons/déplacements, le parseur FTS, les modes multi-tags, les runtimes Ren'Py, le parseur F95Zone et le payload JoiPlay.

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Le test instrumenté Room vérifie SQLite et la synchronisation de l'index FTS sur un appareil ou un émulateur :

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Limites de preuve

Le build et les tests locaux valident le code de l'application. Un lancement réel de jeu exige une installation compatible de JoiPlay, le runtime correspondant et un jeu accessible par un chemin physique que JoiPlay sait lire.
