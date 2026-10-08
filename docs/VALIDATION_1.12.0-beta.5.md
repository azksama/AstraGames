# Validation Astra 1.12.0-beta.5

Code Android 33, minSdk 26, targetSdk 36. Runtime `wine9-astra-2` et composants épinglés inchangés.

## Échec signalé et réponse

Le rapport complet fourni par l’utilisateur montre une préparation de 72 846 fichiers,
un test Box64 réussi, puis `wineboot` terminé avec le code 0. Le processus suivant
refuse de lancer le jeu : `a wine server seems to be running, but I cannot connect to it`,
avec `ECONNREFUSED`, puis sort avec le code 1. Un serveur garde le verrou du préfixe.
Les messages `libastra_exec_bridge.so` de l’extrait ne sont pas présentés comme la cause
certaine de cet arrêt. Le rapport personnel et ses chemins ne sont pas distribués.

Le lancement nettoie désormais le serveur de ce préfixe (`-k`), attend la libération
du verrou (`-w`, délai borné), puis garde une référence au processus serveur lancé
au premier plan (`-f -p60`). La persistance de 60 secondes sans client couvre le passage
de l’initialisation au jeu et évite un serveur orphelin permanent après un arrêt d’Astra.
La fermeture attend également le serveur et détruit le processus supervisé si nécessaire.
Le dossier de travail du jeu correspond maintenant au dossier de son exécutable.

Ces options sont documentées dans le [code officiel Wine 9](https://github.com/wine-mirror/wine/blob/wine-9.0/server/main.c).
Le défaut exact du serveur ARM64 n’a pas été reproduit localement : ce correctif traite
son cycle de vie et rend les prochains échecs observables, sans promettre le fonctionnement
du jeu personnel sur le Samsung concerné.

## Préparation, diagnostic et commandes

- Une requête SAF lit les noms et types des enfants par dossier, au lieu de plusieurs
  requêtes par fichier. Progression et métadonnées de diagnostic limitées à une mise
  à jour par seconde, avec compteur final exact.
- Une copie identique ne remplace plus le fichier local final. Les contenus restent
  vérifiés par hash ; la source est encore lue. Aucun raccourci par date/taille qui
  ignorerait un changement de sauvegarde. Points de reprise du manifeste toutes les
  2 048 entrées traitées et à la sortie de l’import. Originaux et conflits préservés.
- Journaux distincts : événements, jeu, serveur, initialisation, audio, fermeture,
  Android. Box64 LOG=1 conserve ses erreurs et signaux sans tracer chaque appel libc.
  L’aperçu réserve une place à chaque flux ; Android ne masque plus les dernières
  erreurs Wine. Le message précis de serveur inaccessible reçoit une explication
  dans l’en-tête, y compris pour les rapports précédents encore conservés.
- Sept flux au maximum, deux fichiers de 512 Kio chacun, cinq sessions : environ
  35 Mio au maximum hors métadonnées. Export complet à la demande, aucun envoi automatique.
- Croix directionnelle à gauche, Valider/Retour/Shift à droite, menu indépendant
  pour masquer les commandes ou quitter. Maintien simultané direction/action,
  libération sur annulation, changement d’orientation et passage en arrière-plan.
  Le paysage rend au jeu l’espace de l’ancienne barre inférieure.

## Preuves du 8 octobre 2026

- 219 tests JVM, zéro échec ; test de régression d’un rapport volumineux contenant
  l’erreur Wine au milieu du texte et un flux Android très long.
- 17 tests Android ciblés distincts réussis : import SAF imbriqué avec noms japonais,
  1 000 fichiers sans une notification par fichier, non-remplacement des fichiers
  identiques, changement source, conservation des conflits, diagnostics, récupération,
  touches simultanées/annulation, sécurité des mises à jour. Deux tests conditionnels
  de mort du processus ne sont pas exécutés dans ce passage.
- Jeu officiel Wolf 3.729 : nouveau préfixe Windows, affichage effectif, commande
  Valider, rotation portrait/paysage, Menu → Quitter, diagnostic finalisé et arrêt
  du serveur terminé. Captures inspectées dans les deux orientations.
- **APK signé de release avec R8, non débogable** : second lancement avec le préfixe
  existant, scène du jeu rendue, entrée acceptée, fermeture propre et preuve dans le
  journal de la supervision puis de la fin du serveur. Capture inspectée ; le probe
  rejette une image vide via sa diversité de pixels avant la vérification visuelle.
- Builds debug, instrumentation et release réussis ; lint : zéro erreur, 69 avertissements.
  Les positions gauche/droite des commandes sont physiques, comme sur une manette.
- Version 33, signature identique aux précédentes et alignement ZIP vérifiés avec le SDK.

Toutes les preuves d’exécution concernent Android Emulator API 36.1 x86_64. Pas de
mesure de performance sur les 72 846 fichiers personnels, ni de validation ARM64 réelle.
L’APK peut être mis à jour sans effacer les données, les téléchargements ou le préfixe.

## Reproduction

Avec le runtime et l’échantillon officiel déjà préparés sur l’émulateur dédié :

```text
adb -s emulator-5584 shell am instrument -w -e wolfIntegration true -e wolfSample game3729 -e wolfDebug true -e class fr.astragames.app.windows.WolfIntegratedRuntimeTest fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
```

Pour l’APK de release signé avec la clé locale habituelle, installer ce dernier puis
compiler l’instrumentation SDK sans dépendance à Kotlin ou aux noms obfusqués :

```text
python scripts/wolf-runtime/build-release-probe.py
adb -s emulator-5584 install -r build/wolf-research/release-probe/probe.apk
adb -s emulator-5584 shell am instrument -w fr.astragames.releaseprobe/fr.astragames.releaseprobe.ReleaseRuntimeProbe
```

Preuves locales sous `build/wolf-research/` : `beta5-final-build.log`, `beta5-device-tests.log`,
`beta5-controls-tests.log`, `beta5-fresh-runtime.log`, `beta5-release-runtime.log`,
`beta5-game-portrait.png`, `beta5-game-landscape.png`, `beta5-release-game.png`.
