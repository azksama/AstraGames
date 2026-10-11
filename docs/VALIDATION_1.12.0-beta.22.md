# Validation — Astra 1.12.0-beta.22

Cette préversion ajoute Godot 3 et Godot 4 à la détection des jeux, aux profils de lancement, aux filtres et aux collections. Le lancement passe par le plugin JoiPlay correspondant. Le gestionnaire reconnaît les plugins installés derrière les entrées « Godot 3.6 » et « Godot 4.3 », sans ajouter les mêmes composants comme runtimes distincts. La comparaison des versions moteur Ren’Py utilise aussi des composantes numériques pour distinguer notamment 8.1 de 8.10.

## Vérifications avant publication

- **272 tests JVM** réussis, aucun échec, erreur ou test ignoré, après passage à beta.22/code 50.
- **21 tests Android ciblés** réussis sur Android 16/API 36 dans Android Studio Emulator, avec les plugins Godot officiels et Ren’Py installés : découverte des paquets, catalogue sans doublons, profils, scanner local/SAF, diagnostics et scènes natives Godot 3/4.
- Les deux scènes synthétiques affichent du texte, reçoivent un toucher et affichent « Touch OK ». L’instrumentation contrôle les marqueurs Logcat et des pixels de contenu ; les captures ont aussi été examinées. Le GPU hôte est utilisé pour éviter la limite GLES3 de SwiftShader constatée avec Godot 4. [Méthode, captures et limites](VALIDATION_GODOT_2026-10-11.md).
- Builds debug, instrumentation et release R8 réussis. Lint **release : 0 erreur, 99 avertissements**.
- APK release **non debuggable**, R8, signé avec le certificat historique SHA-256 `7425b524d8daac7759c58362e5f97a3bc5aa99d0645c2d8f0cd680490a4ad793`. Signature v2/v3 vérifiée avec `apksigner` ; alignement ZIP 16 KiB vérifié avec `zipalign`. Les segments LOAD des **14 bibliothèques ELF ARM64/x86_64** sont alignés au moins à 16 KiB, vérifiés avec `llvm-readelf` du NDK Android.
- Installation de l’APK beta.21/code 49, puis mise à jour vers l’APK beta.22/code 50 avec `adb install -r`, sans désinstallation. PackageManager confirme la nouvelle version et l’absence du flag debuggable. Démarrage de `MainActivity` réussi ; aucun crash AndroidRuntime dans le journal du contrôle.

Le lancement Godot validé nécessite un seul pack `.pck` séparé dans le dossier du jeu. Les projets sources et packs intégrés aux exécutables sont reconnus, avec un diagnostic demandant l’export ou l’extraction quand le dossier peut être inspecté. La validation utilise des fixtures synthétiques ; elle ne certifie pas tous les jeux ni le téléphone de l’utilisateur.

## Livraison

La [préversion GitHub v1.12.0-beta.22](https://github.com/azksama/AstraGames/releases/tag/v1.12.0-beta.22) contient l’APK signé `Astra-1.12.0-beta.22.apk`, les sources du commit tagué, `VALIDATION_SUMMARY.json` et `SHA256SUMS.txt`. La publication conserve le canal bêta ; le canal stable reste séparé.

Les preuves locales sont dans `build/release-1.12.0-beta.22/` : builds, lint, résultats d’instrumentation, certificat, alignements, versions installées et journal de démarrage. Le résumé de validation joint à la release consigne le commit source et le résultat du contrôle public des artefacts et du client de mise à jour Android.
