# Godot — validation du 11 octobre 2026

Godot 3 et Godot 4 sont disponibles dans les profils de lancement, filtres et collections. Le scanner reconnaît les packs PCK, les fichiers `project.godot` et les packs intégrés aux exécutables, puis conserve le bon fichier d’entrée. JoiPlay reçoit le type `godot3` ou `godot4` et le lancement cible le plugin de la génération correspondante.

Le gestionnaire associe désormais « Godot 3.6 Plugin » au paquet `cyou.joiplay.runtime.godot3` et « Godot 4.3 Plugin » au paquet `cyou.joiplay.runtime.godot4`. Il affiche une seule entrée installée par génération et compare la version de l’application du plugin pour les mises à jour. Les libellés Android « Godot 3/4 Plugin for JoiPlay » ne créent plus les doublons observés sur la capture utilisateur.

## Périmètre du lancement

Les plugins officiels testés recherchent eux-mêmes les fichiers portant l’extension exacte `.pck` dans le dossier du jeu. Le lancement validé utilise un seul pack séparé par dossier. Un projet source ou un pack intégré à un exécutable nécessite donc un export ou une extraction ; Astra affiche un diagnostic explicite lorsqu’il peut inspecter le dossier. Il signale aussi les dossiers contenant plusieurs packs et les extensions `.PCK` en majuscules. L’accès SAF est utilisé lorsqu’il est disponible. Une liste masquée par le stockage cloisonné d’Android ne provoque pas un faux diagnostic « pack absent » : JoiPlay peut disposer de ses propres permissions.

Le catalogue JoiPlay nomme son plugin Godot 4 « 4.3 », mais l’APK téléchargé indique un moteur 4.4.1. L’association repose sur la génération stable du paquet. La compatibilité des jeux dépend toujours du moteur, des fonctionnalités et du plugin. Sources : [JoiPlay](https://joiplay.net/), [catalogue officiel](https://joiplay.net/assets/json/downloads.json), [export de packs Godot](https://docs.godotengine.org/en/stable/tutorials/export/exporting_pcks.html). Les manifestes et le code du plugin ont été inspectés avec `aapt` et `dexdump` du SDK Android.

## Résultats

| Vérification | Résultat |
| --- | --- |
| Suite JVM debug | 272 tests, 0 échec, 0 erreur |
| Instrumentation Android ciblée | 21 tests, 0 échec |
| Compilation debug et APK de tests | Réussite |
| Compilation release avec R8 | Réussite ; APK release non signé |
| Android Lint debug | 0 erreur, 101 avertissements |
| Signature de l’APK debug livré | Vérifiée avec `apksigner`, schéma v2 |
| Contrôle visuel | Deux scènes Godot et écran des runtimes examinés |

L’instrumentation a été exécutée sur Android 16/API 36, dans l’émulateur Android Studio `Astra_Wine_Beta20_20261011` (`emulator-5582`), avec les deux plugins officiels installés. Elle couvre la détection des paquets, leur réconciliation avec le catalogue, les profils, le scanner local/SAF, les diagnostics et des régressions Ren’Py. Chaque scène native est lancée depuis Astra, émet un marqueur Logcat, reçoit un toucher et affiche « Touch OK ». Le test contrôle également des pixels de contenu dans la capture pour détecter un écran vide.

La série finale de 21 tests utilise `-gpu host`, avec le traducteur OpenGL de l’émulateur sur NVIDIA RTX 4070 SUPER. Une exécution initiale sous SwiftShader avait chargé Godot 4 et reçu les entrées, mais son rendu était vide à cause d’une limite d’uniformes GLES3. Cette exécution n’est pas retenue comme preuve de rendu ; les captures et résultats finaux proviennent du GPU hôte.

Les fixtures sont synthétiques et redistribuables. Cette validation ne couvre pas le téléphone de l’utilisateur ni tous les jeux Godot.

## Reproduction

Utiliser le JBR d’Android Studio, les outils du SDK Android et un émulateur avec les plugins officiels et leurs permissions de stockage. Générer les scènes avec :

```powershell
node scripts/godot-runtime/prepare-fixtures.cjs
adb -s emulator-5582 push build/godot-validation/fixtures/. /storage/emulated/0/Download/AstraGodotValidation/
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5582 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w -r -e class 'fr.astragames.app.launcher.GodotRuntimeDiscoveryTest,fr.astragames.app.launcher.GodotRuntimeLaunchTest,fr.astragames.app.launcher.RenPyRuntimeDiscoveryTest,fr.astragames.app.data.local.StorageWorkflowsTest,fr.astragames.app.ui.GodotUiTest' -e godotOfficialPlugins true -e godotRuntimeFixtures true -e renpyOfficialPlugin true fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:lintDebug --no-daemon --max-workers=1
```

Les tests natifs sont optionnels et nécessitent les fixtures et plugins. Le test Ren’Py de cette commande nécessite aussi son plugin officiel. Les tests ordinaires JVM restent indépendants des plugins Android.

## Artifacts locaux

APK de test : `build/godot-validation/Astra-Godot-test.apk`, version inchangée `1.12.0-beta.21` / code 49, certificat Android Debug. SHA-256 :

```text
7d16981925158e6df8964f07e8017111972ce5bc932f526f4b340f17476c1347
```

Captures : [runtimes installés](evidence/godot/runtimes-installed.png), [Godot 3](evidence/godot/godot-3-pck.png), [Godot 4](evidence/godot/godot-4-pck.png). Les journaux locaux sont dans `build/godot-validation/` : `android-final.log`, `debug-final.log`, `release-final.log` et `lint-final.log`.

Les APK JoiPlay téléchargés du catalogue officiel, non inclus dans les sources, ont les empreintes suivantes :

| Plugin | Version application | SHA-256 APK |
| --- | --- | --- |
| Godot 3 | 1.00.50 | `994f811cce1ab021bdacbc2b6687e10bf9337a5acbef4e0c92532c07245010a3` |
| Godot 4 | 1.00.60 | `e3c0d384a5f74a2a1b899531c1bfb3cbaa7b36a6117e79d114d68ca5515b5729` |

Leur certificat de signature SHA-256 commun, vérifié avec `apksigner`, est `17ae227147e8ac4c3daab4a5b7baa63af16f9608b4ae5048cb018c7b4bbfd1f7`.

La livraison finale utilise la version **1.12.0-beta.22/code 50**, avec un APK R8 signé ; voir la [validation de la release](VALIDATION_1.12.0-beta.22.md). L’APK debug beta.21 mentionné ci-dessus reste la preuve de la première série de tests.
