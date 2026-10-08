# Validation Astra 1.12.0-beta.4 — extraction Zstd et récupération des rapports

Code Android 32, minSdk 26, targetSdk 36. Runtime `wine9-astra-2` inchangé.

## Cause et correction

Le rapport utilisateur de la bêta 3 montre un `NoSuchFieldError` sur `srcPos` puis
`dstPos` dans `ZstdInputStreamNoFinalizer.initDStream`, suivi de `SIGABRT`. Le crash
survient pendant la décompression de l’archive Box64, avant son exécution.

Le code natif de [zstd-jni 1.5.7-6](https://github.com/luben/zstd-jni/blob/v1.5.7-6/src/main/native/jni_inputstream_zstd.c)
cherche ces deux champs `long` par leur nom JNI. Dans la release bêta 3, R8 supprime
`srcPos` et renomme `dstPos` en `e`. Une règle ciblée du module windows-runtime
conserve maintenant ces deux champs. L’optimisation R8 reste active ; le fichier
`seeds.txt` du nouveau build confirme leur conservation.

Un second défaut, rencontré lors du test de réinstallation, est corrigé : un lien
symbolique existant dont la cible manque ne doit pas être recréé (`EEXIST`). La
vérification utilise désormais `NOFOLLOW_LINKS`. Le cache de téléchargement et les
données utilisateur sont conservés ; aucune réinitialisation manuelle n’est requise.

## Reprise de l’interface

- Une session incomplète, y compris issue de la bêta 3, ouvre automatiquement son
  rapport au retour dans Astra, après chargement des réglages et déverrouillage.
- Les sessions actives, terminées ou déjà acquittées ne déclenchent pas cet aperçu.
  Sa fermeture conserve les journaux et ne fait pas remonter les anciens rapports.
- Une activité Wolf restaurée retourne à Astra sans relancer automatiquement le jeu.
  Le rapport du lancement interrompu est ainsi préservé.

## Preuves du 8 octobre 2026

- **APK publié bêta 3 reproduit** sur Android Emulator API 36.1 x86_64 : même
  `NoSuchFieldError srcPos/dstPos`, suivi du même abort natif.
- **APK signé bêta 4, R8 actif, `debuggable=false`** : le véritable installateur
  termine l’extraction Zstd du composant x86_64. Le lancement atteint ensuite
  l’erreur de dossier de jeu absent, volontaire dans cette fixture. Ce composant
  utilise le même décodeur JNI que l’archive Box64 ARM64.
- Réouverture de MainActivity dans cette même release : le rapport de l’ancien
  crash apparaît automatiquement, vérifié par un dump UI Automator.
- 216 tests JVM réussis. Instrumentation debug : 12 tests exécutés sans échec,
  deux tests conditionnels de mort de processus ignorés dans ce passage (JUnit
  affiche `OK (14 tests)`). Couverture : récupération, exclusion des sessions
  actives/finies, fermeture persistante de l’aperçu, recréation d’activité sans
  relancement, export et sécurité des mises à jour.
- Builds debug, androidTest et release réussis ; lint : zéro erreur, 62 avertissements.
- APK code 32 signé avec le même certificat que les versions précédentes ; signature
  et alignement ZIP vérifiés avec les outils Android SDK.

Les preuves concernent l’émulateur x86_64 et le crash JNI exact fourni par l’utilisateur.
Elles ne prouvent pas encore le fonctionnement du jeu de l’utilisateur sur son téléphone
ARM64, ni la compatibilité universelle Wolf RPG. Les tests runtime des versions antérieures
portaient sur le debug ; cette régression impose désormais une vérification de la release.

## Reproduction

Le probe est une instrumentation Android sans dépendance AndroidX/Kotlin, pour cibler
l’APK réellement minifié et non débogable. Il n’ajoute aucun point d’entrée à l’application.
Utiliser exclusivement un émulateur x86_64 dédié avec le runtime `wine9-astra-2` déjà
installé, le cache de l’archive des bibliothèques Android disponible et le même certificat
pour l’application et l’instrumentation. Il force la réextraction en sauvegardant les
marqueurs d’installation ; les marqueurs sont restaurés à la fin (un abort natif empêche
le bloc finally, le prochain passage récupère la sauvegarde).

```powershell
./scripts/wolf-runtime/release-probe/build.ps1
adb -s emulator-5584 install -r build/wolf-research/release-probe/probe.apk
adb -s emulator-5584 shell am instrument -w fr.astragames.probe/fr.astragames.probe.RuntimeProbe
```

Résultat attendu : `debuggable=false`, `PASS: production Zstd extraction completed in
the installed APK`, session terminée avec « Le dossier du jeu est inaccessible. ».

Preuves locales non distribuées : `build/wolf-research/beta3-release-zstd-native-logcat.txt`,
`beta4-release-zstd-probe.log`, `beta4-release-recovery.xml`, `beta4-device-tests.log`
et `beta4-final-build.log` dans le même dossier.
