# Wolf Winlator — beta.21

## Périmètre du produit

Wolf RPG est lancé uniquement par `WolfRuntimeActivity` avec Winlator, Wine et Box64. Le module `wolf-native`, l’hôte `app/wolfnative`, leurs tests, le codec Theora/Vorbis propre à cet hôte, les notices générées et l’analyseur de compatibilité natif ont été retirés du build et des sources actives. Les documents de recherche et validations antérieures restent des archives historiques.

Les préférences historiques `AUTO` et `NATIVE` sont interprétées comme `WINLATOR`. Leur lecture conserve les réglages graphiques, touches et positions. Leur prochaine sauvegarde normalise seulement le moteur avec les réglages choisis. Un profil explicitement configuré pour une application externe conserve ce comportement.

Les sauvegardes natives existantes ne sont ni supprimées ni converties. `WolfLegacySaves` est un exporteur en lecture seule, sans VM ni parseur natif. **Outils → Dossier des sauvegardes** montre et exporte `.astrawolf`, `.previous` ainsi que les fichiers de récupération `.bak`/`.new`. Les chemins sont confinés au dossier privé du jeu. Ces fichiers ne sont pas chargeables par Wine.

## Moteur choisi et détection

Le moteur d’un profil de lancement était utilisé au lancement mais pas dans la fiche ou le catalogue. `LaunchProfileResolver.displayGame` applique maintenant ce choix aux données présentées : bibliothèque, index des filtres et fiches. `GameRepository` combine les flux des jeux et profils ; enregistrer ou supprimer un profil actualise immédiatement la projection.

La détection brute reste inchangée dans Room. Le dialogue de profil affiche toujours le résultat du scan pour son option automatique ; un nouveau scan ne supprime pas le choix manuel. Réinitialiser le profil réaffiche la détection initiale.

Un dossier physique contenant `startup.tjs` affiche un avertissement KiriKiri/TJS lorsque le profil Wolf est imposé. Aucun changement automatique de moteur n’est effectué. Cette signature et les lignes KiriKiri du rapport utilisateur ne prouvent pas la compatibilité Wine de ce jeu.

## Optimisation de batterie

**Paramètres → Optimisation de la batterie** lit `PowerManager.isIgnoringBatteryOptimizations` et propose la demande système `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` pour `package:fr.astragames.app`. L’état est relu à chaque reprise de l’écran. Aucun booléen local ne prétend que l’autorisation a été accordée.

Si l’exemption existe déjà, le bouton ouvre la liste Android correspondante ; si une activité de réglage est indisponible, une autre page système est essayée, puis une indication manuelle est affichée. Le choix appartient à Android et à l’utilisateur. Cette exemption concerne l’arrière-plan et ne promet pas de gain FPS en premier plan ni de suppression des politiques supplémentaires du constructeur.

Référence : [documentation Android Doze/App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby).

## Plugins Ren’Py versionnés

La liste de paquets autorisés rejetait les variantes modernes malgré leur action de lancement correcte. L’APK officiel « Ren’Py 8.5 Plugin » a été téléchargé depuis le [catalogue officiel JoiPlay](https://joiplay.net/assets/json/downloads.json), puis inspecté avec les outils Android SDK `aapt2` et `apksigner`. Il a ensuite été installé dans l’émulateur SDK pour qualifier sa découverte, sans lancer de jeu Ren’Py :

- paquet : `cyou.joiplay.runtime.renpy.v8d4d1` ; libellé : `Ren’Py 8.5.0 Plugin for JoiPlay` ; version `1.01.00-patreon` ;
- activité exportée : `cyou.joiplay.runtime.renpy.v8d4d1.PermissionActivity` ; actions `cyou.joiplay.runtime.renpy.run` et `cyou.joiplay.runtime.renpy8.run` ;
- taille : 55 997 840 octets ; SHA-256 APK `e82444a80e127472547819b23bf41295790bc8c877cc13c773504ef52ff805f2` ;
- certificat : `CN=JoiPlay, O=JoiPlay, L=Istanbul, C=TR` ; SHA-256 `17ae227147e8ac4c3daab4a5b7baa63af16f9608b4ae5048cb018c7b4bbfd1f7`.

Les variantes `cyou.joiplay.runtime.renpy.v7d…d…`/`v8d…d…` sont admises uniquement avec ce certificat ou son historique de rotation Android valide. Les suffixes arbitraires, signatures inconnues, activités non exportées/désactivées et permissions inaccessibles sont refusés. Les versions se classent numériquement. Les paquets historiques exacts conservent leur politique existante.

Les requêtes de visibilité Android déclarent déjà les deux actions, donc couvrent le paquet versionné sans `QUERY_ALL_PACKAGES`. Le diagnostic distingue le plugin **Android installé** des bibliothèques et exécutables Ren’Py fournis avec le jeu PC. Il ne contourne pas un plugin réellement absent.

## Déverrouillage et navigation

Le `NavController` était créé après une branche qui quittait la composition lorsque l’application était verrouillée. Le déverrouillage recréait donc la pile sur Accueil. Le contrôleur est désormais conservé hors de cette branche. `AppLockBoundary` mémorise l’état enregistrable des pages avec `SaveableStateHolder`, tout en retirant entièrement les pages privées de la composition pendant le verrouillage.

L’écran et son brouillon sont restaurés après authentification. Les éléments privés n’existent pas dans l’arbre d’accessibilité pendant le verrouillage. Ce changement ne relance pas une session Wine terminée.

## Vérifications

Constats effectués : inspection réelle du manifeste et de la signature de l’APK Ren’Py officiel ; compilation autonome du probe de release Winlator par `javac` avec le SDK Android 36 ; suppression des classes du moteur natif dans le mapping R8 et des bibliothèques dédiées dans l’APK. Les références aux dossiers historiques nécessaires à l’export des sauvegardes sont conservées. Les 260 tests JVM passent, ainsi que les 68 tests SDK de régression et les tests ciblés batterie, verrouillage et plugin installé.

Tests ci-dessous exécutés avec succès dans les lots JVM et Android SDK de beta.21 :

- JVM `JoiPlayTrustTest` : paquet versionné signé, refus de suffixe/signature/moteur incorrects, tri numérique ; `JoiPlayPayloadBuilderTest` : projection Wolf sans altérer la détection.
- SDK `AstraDatabaseTest.profileEngineUpdatesCatalogueAndSearchAndResetRestoresDetection` : changement de profil, rescan, remise à zéro et recherche ; `WolfOptionsInputTest.legacyNativePreferenceAlwaysUsesWinlatorWithoutChangingOtherPreferencesOrSaves` : migration et préservation des réglages/sauvegardes.
- SDK `WolfLegacySavesTest` et `WolfSavesUiTest` : absence de création de dossiers à la lecture, ZIP et originaux byte-identiques, affichage/export des anciennes sauvegardes.
- SDK `BatteryOptimizationTest` : état réel `PowerManager`, permission déclarée et Intent limité au paquet Astra.
- SDK `LockUiTest.unlockingReturnsToTheSamePageAndDraftWithoutExposingItWhileLocked` : navigation réelle Compose, note saisie, faux code refusé, code de fixture accepté, page/brouillon/pile restaurés et contenu absent pendant le verrouillage.
- SDK `RenPyRuntimeDiscoveryTest` : opt-in `-e renpyOfficialPlugin true`, avec APK officiel installé, résolution réelle depuis le contexte cible Astra et découverte dans le gestionnaire. Le test ne lance pas un jeu Ren’Py commercial.

`scripts/wolf-runtime/ReleaseRuntimeProbe.java` qualifie désormais uniquement le moteur Wine. Les anciens chemins natif/secours sont retirés ; les branches Wine, copie historique, migration, interruption et restauration de configuration restent présentes. Le probe se connecte à UIAutomation avant le lancement pour éviter une perte de focus au premier screenshot.

Les mesures de rendu, contrôles et cheat sont documentées par leurs vérifications dédiées. Aucun résultat d’émulateur x86_64 ne garantit un minimum de FPS sur tous les téléphones ou la compatibilité de tous les jeux.
