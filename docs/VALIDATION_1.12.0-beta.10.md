# Validation Astra 1.12.0-beta.10

Version Android 38, runtime partagé `wine9-astra-2`. Cette livraison permet le lancement d’un
jeu Wolf depuis son dossier d’origine et rend les sauvegardes privées accessibles depuis sa fiche.

## Stockage et sauvegardes

- Mode par défaut **Dossier d’origine si accessible**, avec contrôle réel de la lecture de
  l’exécutable et de l’écriture dans le dossier. Le jeu n’est pas importé en mode direct.
- L’autorisation Android **Accès à tous les fichiers** est facultative et proposée dans les
  réglages Wolf. Sans accès natif, un fournisseur SAF ou cloud conserve le mode copie.
- Les anciennes copies restent conservées. Avant la migration, les écritures privées sont
  synchronisées avec sauvegarde de la version source. Un conflit maintient la copie privée ;
  aucune progression divergente n’est écrasée. Revenir au mode copie déclenche son actualisation.
- `Game.ini` reçoit temporairement les modes logiciel et fenêtré. Son original est journalisé
  avant modification et récupérable après un arrêt du processus. La restauration préserve les
  préférences écrites par le jeu et rejette les modifications concurrentes des deux modes.
- **Fiche du jeu → Outils → Dossier des sauvegardes** affiche le dossier effectivement utilisé,
  les fichiers repérés et leurs chemins, puis propose l’export ZIP sans relancer le jeu.
- Le repérage inclut `SaveData`, `SaveFiles`, `Save`, `Saves`, `.sav`, `.save`, les écritures
  privées et les données utilisateur Windows du préfixe. Les données Windows sont exportées
  sous `Windows/drive_c/users/…` ; elles restent privées et ne sont pas synchronisées dans la source.
- Les anciens fichiers utilisateur Windows sont repérés sans nouvelle écriture du jeu. Les
  fichiers système habituels sont filtrés et les liens symboliques ne sont pas suivis.
- Le diagnostic indique le mode utilisé, le dossier de travail, le préfixe et les fichiers repérés.

## Preuves

- **228 tests JVM**, aucune erreur ni échec. Builds debug, instrumentation et release R8 réussis.
- Lint : aucune erreur, 79 avertissements dans l’application et 6 dans le module Windows.
- **24 tests Android distincts** passent : huit tests existants de stockage/import/synchronisation,
  neuf tests de lancement direct et de récupération, deux tests de l’écran des sauvegardes et
  cinq tests des options et des entrées. L’écran est capturé et examiné en français.
- Fixture de **70 001 fichiers** : préparation directe en **29 ms**, **zéro fichier de jeu copié**,
  aucun dossier privé `game/` ni manifeste d’import créé. Ce chiffre mesure la sélection et la
  préparation du dossier, pas la création de Windows ni le chargement du moteur du jeu.
- Jeu officiel Wolf 3.729, 672 fichiers, lancé depuis `/sdcard/Download/Astra-Wolf-beta10/game`
  avec les composants Wine réels. L’APK R8 signé affiche sa carte, accepte Valider et termine
  Wine proprement. Le test exige le mode `DIRECT`, l’absence de copie privée et la restauration
  de la configuration source. Signature v2/v3 et alignement ZIP vérifiés.
- Avec le préfixe déjà initialisé : commandes prêtes en **1 457 ms**, scène contrôlée à
  **16 434 ms**, dont 14,5 secondes d’attentes fixes de la sonde. APK non debuggable.
- Le premier passage avec un préfixe neuf a affiché la scène à **97 147 ms** : le contrôle de
  durée réservé aux préfixes prêts a rejeté ce passage. Il démontre que supprimer l’import ne
  supprime pas l’initialisation Windows. Le jeu a également créé des préférences dans `Game.ini` ;
  ces préférences sont conservées lors de la restauration des modes, comme vérifié séparément.

Preuves locales : `build/wolf-research/beta10-final-build-verified.log`,
`beta10-storage-tests.log` (les huit tests existants passent ; une assertion de chemin d’un
nouveau test a ensuite été corrigée pour comparer les chemins canoniques),
`beta10-final-storage-ui.log`, `beta10-final-saves-tests.log`, `beta10-direct-benchmark.txt`,
`beta10-release-direct-verified.log`, `beta10-signature.log`, `beta10-direct-game.png`
et `beta10-saves-ui.png`.

## Limites

Tests effectués exclusivement avec Android Studio Emulator API 36.1 x86_64, ADB et
l’instrumentation du SDK. Aucun téléphone Samsung ni Dragon Blood n’est testé ici. Le délai
de Windows, la vitesse du support partagé et le chargement propre au jeu restent variables.
Une sauvegarde doit être effectuée dans le jeu ; quitter Astra ne crée pas une sauvegarde de
la partie. Un format ou emplacement particulier non repéré reste possible : l’écran le précise.
