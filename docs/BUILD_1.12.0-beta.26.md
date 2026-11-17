# Construire Astra 1.12.0-beta.26

Utiliser Android Studio et son SDK : Android 36, NDK 29.0.14206865, CMake 3.22.1, JDK 17 minimum. La qualification utilise le JBR d’Android Studio, Gradle 8.14.5, AGP 8.11.1 et les outils de signature SDK 36.1.0.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

L’APK release non signé est `app/build/outputs/apk/release/app-release-unsigned.apk`. R8 et la réduction des ressources sont actifs. La signature de distribution reste hors des sources ; une autre signature ne peut pas remplacer l’installation existante.

La version conserve minSdk 26, targetSdk 36, les ABI ARM64 et x86_64 et le schéma Room 9. Aucun runtime natif n’est modifié par cette version.

Les tests Android se trouvent dans `app/src/androidTest`. Installer les APK debug et androidTest sur un AVD dédié, puis exécuter par exemple :

```text
adb shell am instrument -w -e class fr.astragames.app.data.local.LibraryManagementTest,fr.astragames.app.ui.LibraryManagementUiTest fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
```

Les fixtures de stockage sont fournies seulement par la variante debug. Elles modélisent également les refus Android et les dossiers accessibles par plusieurs autorisations. Les essais réseau de `MetadataLiveTest` sont optionnels (`-e metadataLive true`).

La sonde indépendante de mise à jour se construit avec `scripts/release-probe/build.ps1`. Installer son APK avec `adb install -t` sur l’AVD de qualification, déjà peuplé et équipé de beta.25. Exécuter la sonde avant et après `adb install -r Astra-1.12.0-beta.26.apk` :

```text
adb shell am instrument -w -e mode before -e beforeVersion 1.12.0-beta.25 -e afterVersion 1.12.0-beta.26 fr.astragames.upgradeprobe/fr.astragames.upgradeprobe.UpgradeProbe
adb shell am instrument -w -e mode after -e beforeVersion 1.12.0-beta.25 -e afterVersion 1.12.0-beta.26 fr.astragames.upgradeprobe/fr.astragames.upgradeprobe.UpgradeProbe
```

Après navigation sans modification du catalogue, répéter avec `-e mode after-launch`. La sonde est en lecture seule et compare les tables, les préférences, les sauvegardes internes et l’identité d’installation. Elle n’est pas distribuée comme application utilisateur.

L’archive source correspond au commit de la version. Les sources natives MKXP-Z et leurs notices sont reprises sans modification depuis beta.25 ; leur recette figure dans `scripts/mkxpz/README.md`. L’archive native conserve le SHA-256 `f4518f0cf52c874773b0d74ac116bfe930eb534660ce842a8db1613b5ddb17d1`. Le manifeste source et `SHA256SUMS.txt` accompagnent les fichiers. Une reconstruction bit à bit sur toutes les machines n’est pas revendiquée.
