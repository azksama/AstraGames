# Construire et vérifier Astra beta.25

## Sources distribuées

La préversion fournit le code Android, les ponts natifs, les bibliothèques embarquées,
les schémas Room, les tests synthétiques et les scripts de construction nécessaires.
Les journaux locaux de revue, sondes de qualification séparées et contenus de jeux
ne font pas partie des assets publics.

- `Astra-1.12.0-beta.25-sources.zip` : arbre public du projet.
- `Astra-1.12.0-beta.25-native-sources.tar.gz` : sources natives MKXP-Z correspondantes,
  avec leurs dépendances, patches et recettes sous `astra-build/`.
- `Astra-1.12.0-beta.25-notices.zip` : notices et licences également embarquées.
- `SOURCE_MANIFEST.json` et `SHA256SUMS.txt` : inventaire et empreintes des assets.

L'archive native est conservée sans modification depuis la compilation qualifiée.
Son SHA-256 est `f4518f0cf52c874773b0d74ac116bfe930eb534660ce842a8db1613b5ddb17d1`.
Les certificats et clés d'exemple des suites de tests amont OpenSSL/Ruby ne sont pas
des secrets Astra. Aucune clé de signature de l'application n'est distribuée.

## Application Android

Utiliser Android Studio et son SDK : plateforme Android 36, NDK `29.0.14206865`,
CMake `3.22.1`, et un JDK compatible avec Gradle 8.14.5 et AGP 8.11.1
(JDK 17 minimum ; le JBR d'Android Studio convient). Kotlin est fixé à 2.2.21.
L'application conserve minSdk 26 et targetSdk 36 ; les ABI sont ARM64 et x86_64.
AGP sélectionne les Build Tools nécessaires à la compilation. Les vérifications
de signature et d'alignement décrites ici utilisent les outils SDK `36.1.0`.

Depuis la racine du projet, sous Windows :

```powershell
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:lintRelease
```

Sous un système POSIX, utiliser `bash ./gradlew` à la place. Gradle compile aussi les
ponts du module `windows-runtime`. Le module `mkxpz-runtime` emploie les bibliothèques
fournies ; leur reconstruction séparée est décrite dans
[scripts/mkxpz/README.md](../scripts/mkxpz/README.md).

Le résultat release non signé se trouve dans
`app/build/outputs/apk/release/app-release-unsigned.apk`. Le build active R8 et la
réduction des ressources. Pour un usage local, signer avec sa propre clé existante
et les outils SDK. Une signature différente ne peut pas remplacer l'installation
publique en place. La clé historique de distribution reste hors du dépôt et des
archives.

Pour inspecter un APK signé avec les outils du SDK :

```text
apksigner verify --verbose --print-certs Astra-1.12.0-beta.25.apk
zipalign -c -P 16 -v 4 Astra-1.12.0-beta.25.apk
python scripts/mkxpz/check-apk-pages.py Astra-1.12.0-beta.25.apk --output pages.json
```

Ces contrôles de signature et d'alignement ne remplacent pas une exécution sur
Android. Le [bilan de validation](VALIDATION_1.12.0-beta.25.md) sépare les essais
effectifs, les inspections statiques et les preuves héritées.

## Tests et corpus optionnel

Les tests synthétiques sont inclus. Les tests réels Wolf nécessitent un corpus
externe non distribué. `ASTRA_WOLF_CORPUS` désigne sa racine en lecture seule ;
sans corpus disponible, les tests concernés sont ignorés. Une variable définie
vers un corpus incomplet provoque un échec explicite.

Le corpus comprend les échantillons officiels `sample22961` et `sample3729`,
les sauvegardes référencées par `WolfRealSaveCodecTest` sous `wolf-data-proof`,
et le fichier `wolf-save-reference-real-w2-corrected.json` dans le dossier frère
`mtool-implementation`. Les chemins et SHA attendus figurent dans les tests.
Les tests JVM avec corpus appartiennent aux qualifications historiques de beta.24.
Ils n’ont pas été réexécutés pour beta.25 ; les validations de cette passe utilisent
Android Emulator et le SDK.

Les tests instrumentés nécessitent un AVD SDK dédié aux fixtures :

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Certains tests réseau ou de runtime externe demandent des ressources et options
supplémentaires. Cette commande générale n'est pas la matrice sélective attestée
par le bilan public. Les APK debug et de tests servent au développement et ne sont
pas les binaires utilisateur distribués.

## Portée de la reproductibilité

L'inventaire permet de vérifier le contenu exact de l'archive source et sa relation
avec le commit public. L'identité des sources et des payloads natifs est vérifiée ;
une reconstruction universellement identique de l'APK sur toute machine n'est pas
revendiquée. Les outils, caches et signatures peuvent changer les octets produits.

## Sonde de mise à jour release

`scripts/release-probe/build.ps1` construit une instrumentation Android indépendante,
signée avec le certificat de développement historique local. Elle est réservée à
un AVD de qualification contenant un catalogue témoin et ne modifie ni le catalogue
ni les préférences. Ses résultats sont des empreintes, sans contenu des fiches.

Installer la sonde avec `adb install -t`, puis exécuter :

```text
adb shell am instrument -w -e mode before fr.astragames.upgradeprobe/fr.astragames.upgradeprobe.UpgradeProbe
```

Ce premier passage attend beta.24. Installer l’APK beta.25 avec `adb install -r`,
puis répéter avec `-e mode after`. Après ouverture de l’interface, répéter avec
`-e mode after-launch`. La sonde compare les tables, les préférences, les backups
et l’identité de l’installation. Elle n’est pas une application à distribuer.
