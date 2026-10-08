# Validation d’Astra 1.12.0-beta.1

Préversion du 8 octobre 2026, code de version Android 29, minSdk 26, targetSdk 36.

## Contrôles de livraison

- Reconstruction release R8, tests JVM et lint application/module réussis après changement de version.
- 210 tests JVM sans échec. Lint : aucune erreur ; avertissements conservés.
- Signature SHA-256 du certificat identique à l’APK installable 1.11.0 :
  `7425b524d8daac7759c58362e5f97a3bc5aa99d0645c2d8f0cd680490a4ad793`.
- APK release signé avec la clé de développement habituelle, signatures v2/v3 et alignement vérifiés.
- Installation en place et lancement de Wolf 3.729 par instrumentation SDK dans ce même APK versionné.
  La sonde vérifie le démarrage, envoie Valider, rejette une image vide et ferme la session.

## Preuves de l’intégration

La validation fonctionnelle précédant le changement de version comprend 14 tests Android
(stockage SAF, scan ciblé, sélection de dossier, sauvegardes/conflits/export ZIP et audio),
ainsi que les parcours de jeux officiels Wolf 2.2961 et 3.729 avec captures revues.
Le code fonctionnel est inchangé pour cette publication : seuls le numéro de version et les
informations de publication ont été ajustés.

L’exécution est vérifiée sur Android Emulator officiel API 36.1 x86_64 avec pages de 4 Ko.
Le test sonore observe le flux d’un programme Windows PlaySound vers PulseAudio/AAudio ;
il ne mesure pas la qualité auditive et ne valide pas tous les MIDI/codecs.
ARM64/Box64 est compilé, mais pas validé sur téléphone. Les pages de 16 Ko et la compatibilité
universelle ne sont pas établies. Voir [le guide du moteur](wolf-windows-runtime.md).

Les sources de la release sont produites par `git archive` depuis son commit et les assets
sont accompagnés de SHA256SUMS.txt. Les composants Wine/Box64 téléchargés à l’exécution et
les jeux ne sont pas redistribués dans cette archive de sources ni dans l’APK.
