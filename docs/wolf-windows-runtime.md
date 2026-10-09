# Wolf RPG Windows dans Astra

État du 9 octobre 2026 : moteur intégré expérimental fonctionnel sur l'émulateur Android
API 36.1 x86_64. Les exemples officiels Wolf **2.2961 et 3.729** atteignent la partie,
affichent personnages et dialogues japonais, et répondent au bouton Valider d'Astra.
Le test ne couvre pas une partie complète, tous les jeux, ni un téléphone ARM64.

## Utilisation

Scanner un jeu Windows décompressé, ouvrir sa fiche et choisir Jouer. Le profil Wolf par défaut
est Astra · Wolf intégré. Les profils externes explicitement choisis restent externes.
Les composants sont téléchargés au premier usage par HTTPS et vérifiés avec un SHA-256 épinglé.
Le runtime reste dans `noBackupFilesDir/wolf-runtime/wine9-astra-2` ; les jeux importés et leurs
préfixes restent dans `files/wolf-games/<SHA-256 de l'identifiant du jeu>`.
La taille nécessaire comprend le runtime partagé et un préfixe Windows par jeu. Une copie du jeu
est ajoutée uniquement lorsque le mode copie est choisi ou que l’accès direct est impossible.

Le scan d'un sous-dossier s'effectue depuis Paramètres → Sources et scan → Sous-dossier…,
en naviguant dans un parent déjà autorisé. Il ne marque pas les jeux des autres branches absents.

## Architecture livrée

- `WolfRuntimeInstaller` : installation sérialisée, téléchargements annulables, empreintes,
  extraction confinée, reprises par composant, bibliothèques Bionic adaptées à l'ABI.
- `WolfProcess` : Wine 9 WoW64 ; Box64 Bionic 0.4.2 pour ARM64, Wine natif pour x86_64.
  L'application conserve minSdk 26 et targetSdk 36. Android 64 bits est requis.
- `windows-runtime` : sous-ensemble LGPL de Winlator pour X11, rendu OpenGL ES, entrées,
  sockets et mémoire partagée. Aucune installation externe Winlator/GameNative/JoiPlay.
- `WolfRuntimeActivity` : affichage dans Astra, pavé tactile, clavier, D-pad et boutons A/B,
  fermeture sérialisée des sessions, verrouillage Astra au retour de l'arrière-plan et protection des captures.
- `WolfProcessRecovery` : arrêt des anciens enfants Windows/audio identifiés par l’UID, leurs
  chemins privés et leur environnement ; contrôle de l’identité du processus avant le signal.
- `WolfLoadingView` : étapes réelles et durée, commandes masquées jusqu’à la première image
  non noire du jeu dans le renderer. Les fenêtres de préparation et les consoles sont exclues.
- `WolfTouchInput` et `ViewTransformation` : Retour à deux doigts, zoom optionnel de 1× à 4×,
  déplacement à deux doigts et conversion des coordonnées tactiles dans le même cadrage.
- PulseAudio Bionic et sortie Android AAudio ; IPAexGothic pour les caractères japonais.
- `WolfGameStorage` : dossier d’origine accessible ou copie SAF/file, détection des écritures locales non synchronisées,
  synchronisation avec comparaison de l'original, sauvegarde de l'ancienne version et export ZIP.

Wine et les jeux s'exécutent sous l'UID d'Astra. Le moteur **n'est pas un bac à sable de sécurité**
pour exécutables inconnus. Le jeu peut accéder aux fichiers privés accessibles à cet UID.

## Adaptations techniques

Le pont `scripts/wolf-runtime/exec_bridge.c`, compilé pour les deux ABI par le NDK 29,
charge les ELF dynamiques du runtime via `/system/bin/linker64` et adapte les processus enfants,
la résolution de `/proc/self/exe` et le socket X11. Les pages PE privées modifiées nécessitant
une permission exécutable sont recopiées dans une allocation anonyme lorsque Android refuse
`mprotect` sur la projection du fichier. Ce petit pont est compilé à `-O0` dans les deux
variantes : à `-O2`, le sample Wolf 3.729 provoquait un débordement de pile Wine sur
l'émulateur x86_64. Le remplacement du seul pont corrige le défaut ; le reste de
l'application, le serveur X11 et le renderer gardent leur optimisation release.

La distribution de Wine possède une réservation d'adresses adaptée à Box64. Sur x86_64 natif,
`WolfRuntimeInstaller.patchNativeX64` corrige quatre bornes 39 bits en bornes 47 bits dans le
`ntdll.so` dont l'empreinte exacte est vérifiée. Cette correction n'est pas appliquée à ARM64.
Le hash attendu après correction est
`d927858a6db35029e13f943067098fafe247e2f955de074719d69b48ce1a0d22`.
`WINE_DISABLE_FULLSCREEN_HACK=1` évite une récursion du pilote sans RandR.

En mode copie, le mode logiciel Wolf et le mode fenêtré sont imposés dans la **copie privée** de `Game.ini`,
créée si elle n'existe pas. Les sources utilisateur ne sont pas modifiées pour cela.
Les événements FocusIn/FocusOut et SetInputFocus du serveur X11 ont été corrigés pour que
les commandes tactiles atteignent réellement le jeu.

## Sauvegardes et récupération

Après une fermeture normale, les fichiers changés sont réécrits vers la source si leur version
source correspond à celle importée. Une ancienne version est gardée sous `.astra-wolf-backup-*`.
En mode direct, le jeu écrit immédiatement dans son dossier d’origine. Avant de passer d’une
ancienne copie au mode direct, les modifications privées sont synchronisées avec comparaison
de la version source. Un conflit maintient le lancement depuis la copie privée.
En cas de conflit, les deux versions restent disponibles ; le bilan propose Exporter les sauvegardes.
Une fermeture forcée conserve la copie privée pour la prochaine ouverture. Ne pas effacer les
données d'Astra ou désinstaller avant d'avoir synchronisé/exporté les sauvegardes.

`Game.ini`, les EXE et les DLL sont exclus de la synchronisation. Les fichiers supprimés dans le
jeu ne sont pas supprimés automatiquement de la source. Les exports du catalogue `.astra`
ne contiennent pas les copies privées Wolf ni les préfixes Windows.

Depuis la bêta 10, la fiche du jeu propose **Outils → Dossier des sauvegardes** : repérage de
`Save`, `Saves`, `SaveData`, `SaveFiles`, `.sav`, `.save`, et des écritures de la copie privée.
Les données utilisateur Windows du préfixe sont aussi repérées et incluses dans le ZIP sous
`Windows/drive_c/users/…`, y compris les fichiers d’une version ancienne. Les liens symboliques
ne sont pas suivis par cet inventaire. Les sauvegardes Windows ne sont pas réécrites dans la source.

Le mode direct résout uniquement les chemins locaux pris en charge par `FileAccessResolver`,
vérifie la lecture de l’exécutable et la création d’un fichier temporaire dans le dossier choisi.
Sur Android 11+, l’autorisation facultative `MANAGE_EXTERNAL_STORAGE` permet les lectures natives
de Wine/Box64 dans le stockage partagé. Le sélecteur SAF reste utilisé pour le catalogue et le
mode copie ; les fournisseurs sans chemin local restent compatibles avec cette copie.
Voir la [documentation Android](https://developer.android.com/training/data-storage/manage-all-files).

En mode direct, `WolfDirectConfiguration` conserve l’original de `Game.ini` dans un journal privé
avant d’activer temporairement les deux modes. Une fermeture normale restaure l’original ; après
un arrêt du processus, le lancement suivant reprend la restauration. Les autres préférences
écrites par le jeu sont conservées. Une modification concurrente des modes bloque la restauration
et conserve le journal original, au lieu d’écraser le fichier source.

## Chargement et fermeture interrompue

Depuis la bêta 11, le lancement affiche huit phases : récupération, composants du moteur,
dossier du jeu, affichage X11, audio et Box64, Windows, lancement, puis première image du jeu.
Le téléchargement affiche son volume ; l’extraction affiche le nombre de fichiers et la part
de l’archive compressée lue. Windows et le jeu ne fournissent pas de pourcentage fiable :
ces phases affichent le temps réellement écoulé et un indicateur d’activité. Le lancement peut
être annulé pendant la préparation. L’extraction vérifie aussi l’interruption dans les fichiers.

Une nouvelle session attend la fermeture et la synchronisation de l’ancienne avant de réutiliser
les sockets. Après la disparition du processus Android, Astra repère et arrête uniquement les
anciens processus de son runtime avant de reprendre le dossier et sa configuration journalisée.
Le préfixe et les sauvegardes ne sont pas supprimés. Le marqueur Windows prêt doit correspondre
à la révision du runtime et aux fichiers essentiels du préfixe, sinon son initialisation est reprise.
Une fermeture brutale ne sauvegarde pas la progression qui n’a pas été écrite par le jeu.

La première image du jeu active les commandes ; ni la présence d’une fenêtre ni un framebuffer
noir ne suffisent. Si le jeu termine avant d’afficher une image, ou n’en affiche aucune en trois
minutes après son lancement, un message renvoie vers le diagnostic. Le contrôle des pixels est
limité au démarrage et cesse après cette première image.

Le toucher bref à deux doigts utilise la touche **Retour** configurée pour le jeu. Activer
**Outils → Réglages Wolf RPG → Zoom à deux doigts** permet de pincer et de déplacer l’image
agrandie. Les gestes déplacés ou annulés ne déclenchent pas Retour. Voir la
[validation de la bêta 11](VALIDATION_1.12.0-beta.11.md).

## Provenance et reconstruction

Le code Winlator est épinglé à `3981d86efa4f333b2a34a7da8b6521476cd8c8b9` :
https://github.com/brunodev85/winlator-app
Voir `windows-runtime/NOTICE.md`, `windows-runtime/LICENSE` et les notices embarquées dans
`app/src/main/assets/windows/THIRD-PARTY-NOTICES.txt`.
La police officielle provient de https://moji.or.jp/ipafont/ et sa licence IPA est embarquée.

Les URLs exactes et SHA-256 des paquets téléchargés sont centralisés dans
`WolfRuntimeInstaller.kt`. Wine provient de https://downloads.gamenative.app/proton-9.0-x86_64.txz ;
les bibliothèques ARM de https://downloads.gamenative.app/imagefs_bionic.txz ; Box64 et l'audio
sont épinglés aux dépôts GameNative et GameNative-x64 par révision. Aucun code Java/Kotlin
GameNative n'est embarqué. Aucun jeu ni police Microsoft n'est distribué dans l'APK.
Les empreintes identifient les binaires fournisseurs ; la reproductibilité de ces binaires depuis
leurs sources correspondantes n'a pas été démontrée. Une distribution publique autonome des
paquets doit traiter cette correspondance et les obligations de toutes leurs licences.

Construire avec JDK 17+, SDK 36, NDK 29.0.14206865, CMake 3.22.1 :

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :windows-runtime:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

Le module modifié est intégralement fourni ; le remplacer dans `windows-runtime` puis reconstruire
permet de tester une autre version. L'APK release Gradle est non signé ; signer une copie avec
sa propre clé ou utiliser le debug. Une signature différente ne permet pas une mise à jour en place.

## Reproduire les tests Android

Uniquement Android Emulator officiel et ADB. Les échantillons sont téléchargés séparément :
https://silversecond.com/WolfRPGEditor/Data/WolfRPGEditor_22961.zip et
https://silversecond.com/WolfRPGEditor/Data/WolfRPGEditor_3.729.zip.
Décompresser les noms ZIP avec CP932, conserver les données, Game.exe, Game.ini éventuel et DLL.
Préparer les fixtures dans `files/wolf-probe/game` et `files/wolf-probe/game3729` du debug.
L'instrumentation importe ensuite le jeu et utilise l'installateur et le moteur de production.

```text
adb -s emulator-5584 shell am instrument -w -e wolfIntegration true -e wolfSample game3729 -e class fr.astragames.app.windows.WolfIntegratedRuntimeTest fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5584 shell am instrument -w -e class fr.astragames.app.data.local.StorageWorkflowsTest,fr.astragames.app.ui.SourceSubfolderDialogTest,fr.astragames.app.windows.WolfGameStorageTest fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
```

Le test de jeu vérifie le parcours de lancement et envoie une commande ; les captures titre/partie
nécessitent une revue visuelle. Il désactive FLAG_SECURE uniquement dans l'instrumentation.
Les anciens tests `WindowsRuntimeProbeTest` et `WindowsDisplayProbeTest` sont des sondes de
diagnostic opt-in ; ils ne remplacent pas le test du parcours complet.

## Limites constatées

- Exécution ARM64/Box64 non validée sur matériel physique ; 16 Ko de pages non validés.
- Rendu logiciel : pas de promesse de performances ou de prise en charge des effets GPU particuliers.
- Deux versions officielles testées, pas toutes les variantes, DLL tierces, DRM, médias ou jeux commerciaux.
- Le MIDI nécessitant une banque GM et les codecs particuliers peuvent manquer.
- Une mise en arrière-plan suspend le rendu et libère les touches ; le processus Windows peut continuer.
- Le téléchargement initial nécessite le réseau ; aucune preuve Google Play ou de redistribution
  autonome du runtime. L'installation suivante réutilise les composants vérifiés.


## Bilan de validation initiale du moteur

- 210 tests JVM : réussis ; lint application et module : aucune erreur (avertissements conservés).
- 14 tests Android : stockage SAF, scan de sous-dossier, interface de sélection,
  sauvegardes Wolf avec conflits/export ZIP et sortie sonore Windows vers PulseAudio : réussis.
- Wolf 2.2961 et 3.729 : lancement du jeu original et entrée dans la partie confirmés par
  instrumentation et revue des captures sur Android Emulator API 36.1 x86_64, pages de 4 Ko.
- La fixture audio Windows appelle PlaySound sur une onde synthétique. Le test observe un
  flux PulseAudio actif ; il ne constitue pas une appréciation auditive de latence/qualité ni
  une validation des musiques MIDI de tous les jeux.
- La sonde release est indépendante d'AndroidX/Kotlin pour fonctionner avec les classes
  obfusquées. Elle lance le moteur, envoie Valider, rejette une image de jeu vide et ferme
  la session. Les captures restent soumises à revue visuelle.

```powershell
python scripts/wolf-runtime/create-audio-fixture.py
python scripts/wolf-runtime/build-release-probe.py
adb -s emulator-5584 install -r build/wolf-research/release-probe/probe.apk
adb -s emulator-5584 shell am instrument -w fr.astragames.releaseprobe/.ReleaseRuntimeProbe
```

La sonde et l'APK cible doivent être signés avec la même clé. Les scripts de test utilisent
la clé debug Android locale. Le fichier de livraison `Astra-1.12.0-beta.1.apk` est une
construction release optimisée signée avec cette clé, distincte d'une publication stable.
Les données et preuves de test ne sont pas intégrées aux sources ni à l'APK.
