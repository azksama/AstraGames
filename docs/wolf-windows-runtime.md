# Wolf RPG Windows dans Astra

Wolf utilise **Winlator, Wine et Box64** dans Astra. Le moteur Android natif
expérimental a été retiré ; ses anciennes sauvegardes restent conservées et
exportables. **Fiche → Compatibilité → Profil de lancement** permet de corriger
le moteur détecté ou de choisir explicitement une application externe.

Ce guide décrit le fonctionnement et la reconstruction du moteur. Les
[notes beta.24](RELEASE_1.12.0-beta.24.md) présentent les changements, et le
[bilan de validation](VALIDATION_1.12.0-beta.24.md) distingue les contrôles du
nouvel APK des preuves antérieures. L’exécution ARM64 reste non démontrée.

## Installer et lancer un jeu

Ajouter le dossier contenant le jeu Windows décompressé, puis lancer son scan.
Astra détecte `Game.exe` avec `Data.wolf` ou `Data/BasicData/Game.dat`. Ouvrir la
fiche et choisir **Jouer** : le profil par défaut est **Astra · Wolf intégré**.
Les profils externes explicitement choisis restent externes.

Les composants sont téléchargés au premier usage par HTTPS et vérifiés avec un
SHA-256 épinglé. Ils sont partagés entre jeux, mais chaque jeu conserve son propre
préfixe Windows. Le premier démarrage peut demander une initialisation du préfixe,
même lorsque les ressources du jeu ne sont pas importées.

**Outils → Réglages Wolf RPG → Fichiers du jeu** utilise le dossier physique
lorsqu’Android fournit un chemin réellement accessible. Sans cet accès, Astra
essaie un [montage SAF à la demande](wolf-saf-on-demand.md) pour les fournisseurs
compatibles, puis une copie privée de secours. Seule cette dernière importe les
ressources ; la préparation et le diagnostic indiquent la raison d’un repli.

Le mode direct vérifie la lecture de l’exécutable et la création d’un fichier
temporaire dans le dossier choisi. Sur Android 11+, **Accès à tous les fichiers ·
réglages Android** permet d’accorder l’autorisation facultative nécessaire aux
lectures natives de Wine/Box64 dans le stockage partagé. Le sélecteur SAF reste
utilisé pour le catalogue. Voir la [documentation Android](https://developer.android.com/training/data-storage/manage-all-files).

Le runtime est stocké dans `noBackupFilesDir/wolf-runtime/wine9-astra-2` ; les
copies des jeux et leurs préfixes sont dans
`files/wolf-games/<SHA-256 de l’identifiant du jeu>`. Prévoir l’espace pour les
composants partagés, le préfixe et, si nécessaire, la copie privée du jeu.

Le scan ciblé d’un sous-dossier est disponible dans **Paramètres → Sources et
scan → Sous-dossier…**, sous un parent déjà autorisé. Il ne marque pas les jeux
des autres branches comme absents.

## Commandes et affichage

Les flèches, Valider, Retour et Shift sont disponibles à l’écran ; clavier et
boutons de manette sont également routés au jeu. **Menu** permet de masquer les
commandes ou de quitter. Les captures d’écran Android sont autorisées pendant
le jeu. Le verrou Astra s’applique au retour de l’arrière-plan.

**Menu → Modifier les touches → Ajouter** crée des boutons nommés avec le clavier
proposé, dont F1–F12, les modificateurs et le pavé numérique. Déplacer un bouton
change sa position ; le toucher dans l’éditeur permet de régler son action,
sa taille et son opacité. Les positions portrait/paysage et les réglages sont
mémorisés par jeu. Les combinaisons, bascules et séquences sont prises en charge.
**Presets** enregistre ou charge ces réglages, avec import/export JSON, sans
changer les sauvegardes des jeux.

Le tactile propose le clic à l’endroit touché ou le maintien d’une direction
depuis le centre de l’image ; ce dernier mode ne calcule pas un chemin vers une
destination. **Menu → Déplacement → Joystick à huit directions** offre un autre
contrôle du déplacement.

Un toucher bref de l’image avec deux doigts envoie la touche **Retour** configurée.
**Outils → Réglages Wolf RPG → Zoom à deux doigts** permet le pincement de 1× à 4×
et le déplacement de l’image agrandie. Ce réglage est désactivé par défaut,
mémorisé par jeu et immédiat ; le désactiver restaure le cadrage. Les gestes
déplacés ou annulés ne déclenchent pas Retour.

**Outils → Réglages Wolf RPG**, également accessible dans **Menu → Réglages du
jeu**, propose les profils Équilibré/Rapide/Stable, une définition de 800×600 à
1920×1080, le lissage et une limite de 30/60 images par seconde. Les profils
Box64 et la définition prennent effet au prochain lancement ; les réglages
d’affichage immédiats sont appliqués pendant la session.

Le **Cadrage** propose **Image entière** (défaut, proportions conservées),
**Remplir** (recadrage sans déformation) et **Étirer** (plein écran avec déformation).
Le bureau Windows automatique est de 1280×960 en portrait et 1280×720 en paysage ;
une définition choisie manuellement reste conservée. Cette définition ne change
pas la résolution interne du jeu.

Le compteur **Images modifiées** compte les images renouvelées, pas la cadence
interne de Wolf : une valeur faible sur une scène immobile peut être normale.
**Menu → Mesurer la fluidité** ajoute au diagnostic une mesure de rendu de
60 secondes et l’état thermique Android. Le réglage **Polices embarquées** permet
d’utiliser les polices fournies pour les jeux dont elles couvrent les caractères ;
le catalogue de polices Wine utilise un cache partagé.

**Menu → Cheats et variables** recherche, affine et modifie des valeurs pendant
la session. Les limites, l’annulation et les versions reconnues du moteur sont
décrites dans le [guide dédié](wolf-cheats.md).

## Sauvegardes, actualisation et récupération

Enregistrer la partie dans le jeu avant **Quitter**. En mode direct, le jeu écrit
immédiatement dans son dossier d’origine. En mode copie, une fermeture normale
réécrit les fichiers modifiés vers la source si celle-ci correspond encore à la
version importée. Une ancienne version est conservée sous `.astra-wolf-backup-*`.
Le [mode SAF à la demande](wolf-saf-on-demand.md) utilise son journal de publication.

Avant de passer d’une ancienne copie au mode direct, Astra récupère les écritures
privées avec comparaison de la version source. Un conflit conserve les deux
versions, maintient le lancement depuis la copie privée et propose l’export ZIP.
Une fermeture forcée conserve la copie privée pour la prochaine ouverture ; elle
ne sauvegarde pas une progression que le jeu n’a pas écrite.

Une sauvegarde Wolf restaurée depuis une archive du catalogue reste protégée au
prochain lancement et lors d’une actualisation des ressources, même lorsqu’elle
était inchangée à l’export. Si la source a évolué, le conflit conserve la copie
restaurée au lieu de l’écraser. Les contrôles restent liés au jeu et à sa source.

**Outils → Dossier des sauvegardes** repère les dossiers `Save`, `Saves`, `SaveData`,
`SaveFiles`, les fichiers `.sav`/`.save` et les écritures de la copie privée. Les
données utilisateur du préfixe Windows sont également exportables en ZIP sous
`Windows/drive_c/users/…`. Les liens symboliques ne sont pas suivis par cet
inventaire ; les sauvegardes du préfixe ne sont pas réécrites dans la source.

Les archives du catalogue `.astra` peuvent contenir les sauvegardes privées Wolf
prises en charge ; elles ne contiennent pas la copie complète du jeu, son préfixe
Windows ou les permissions SAF. Leur clé est liée à l’installation Android.
Conserver séparément les dossiers des jeux et ne pas désinstaller Astra avant
d’avoir synchronisé ou exporté les données nécessaires. Voir le
[format des archives](BACKUP_FORMAT.md).

Les EXE et DLL sont exclus de la synchronisation des fichiers de la copie.
Les fichiers supprimés dans le jeu ne sont pas supprimés automatiquement de la
source. `Game.ini` dispose d’un traitement distinct : les réglages de rendu sont
privés en mode copie et temporairement journalisés en mode direct. Une fermeture
normale ou le lancement suivant reprend la restauration en conservant les
préférences écrites par le jeu. Une modification concurrente des modes conserve
le journal au lieu d’écraser le fichier source.

Pour une mise à jour externe du jeu, un mod installé par un autre outil ou une
sauvegarde dans un emplacement personnalisé, utiliser **Fiche du jeu → Outils →
Actualiser les fichiers du jeu**, puis relancer. Les mods installés/désinstallés
dans Astra déclenchent cette actualisation automatiquement. Les sauvegardes
connues sont vérifiées à chaque lancement ; les ressources inchangées sont
réutilisées. Aucun effacement des données n’est nécessaire.

Le lecteur Windows **G:** pointe sur le dossier effectivement utilisé par le jeu.
Le répertoire de travail reste le parent réel de l’exécutable, y compris pour un
jeu imbriqué. Cette correspondance évite de mesurer l’espace libre du volume
système Android à la place du dossier de sauvegarde. Un véritable manque
d’espace ou un refus d’accès reste une erreur.

## Chargement et diagnostic

Le lancement affiche huit phases : récupération, composants du moteur, dossier
du jeu, affichage X11, audio et Box64, Windows, lancement, puis première image du
jeu. Les téléchargements indiquent leur volume et les extractions leur
progression. Lorsque Windows ou le jeu ne fournit pas de pourcentage fiable,
Astra affiche le temps écoulé et un indicateur d’activité. La préparation est
annulable ; elle respecte les animations désactivées du système.

Une nouvelle session attend la fermeture et la synchronisation de la précédente.
Après un arrêt du processus Android, Astra identifie et arrête uniquement les
anciens processus de son runtime avant de reprendre le dossier et sa configuration.
Le préfixe et les sauvegardes sont conservés. Si le marqueur Windows prêt ne
correspond plus à la révision du runtime ou aux fichiers essentiels du préfixe,
son initialisation est reprise.

Les commandes sont masquées pendant la préparation, les consoles et les images
noires ; elles apparaissent avec la première image du jeu. Si le jeu termine
avant cette image ou n’en affiche aucune dans les trois minutes après son
lancement, un message ouvre le diagnostic. La relance proposée avec le profil
Stable ne garantit pas la compatibilité du jeu.

**Diagnostic du lancement** reste accessible pendant l’attente. Les opérations
de préparation et les écritures du diagnostic s’effectuent hors du fil d’affichage.
Après 20 secondes sans changement de sous-étape, le rapport ajoute un état des
threads Astra ; ce délai ne constitue pas à lui seul un diagnostic de crash.

Pour plus de détails, activer **Paramètres → Diagnostic Wolf RPG → Mode debug
Wolf RPG**, puis relancer. Le rapport conserve les étapes, codes de sortie,
exceptions et journaux de Wine/Box64 et de l’audio. Le mode debug ajoute les
informations du chargeur, un contrôle de démarrage Box64 sur ARM64 et Logcat
limité au processus Astra. `Game_ErrorLog.txt` est distingué selon qu’il est
historique ou modifié pendant la session.

Après une session interrompue, le rapport peut être présenté à la réouverture,
après déverrouillage. Fermer l’aperçu le conserve dans les paramètres ; **Partager
le rapport** l’exporte volontairement. Les cinq dernières sessions sont conservées
localement avec rotation des journaux (512 Kio × deux fichiers et 64 Kio de début
par flux). Les rapports sont exclus de la sauvegarde système. Leur export peut
contenir des noms de jeux et des chemins ; aucun envoi automatique n’a lieu.

Android 11 ou supérieur peut fournir la raison de l’arrêt du processus. Si elle
manque, le diagnostic le précise. Le mode debug ne garantit pas la résolution
du problème.

## Architecture et adaptations

- `WolfRuntimeInstaller` sérialise l’installation, vérifie les empreintes et
  reprend les composants avec extraction confinée et téléchargements annulables.
- `WolfProcess` utilise Wine 9 WoW64, Box64 Bionic 0.4.2 sur ARM64 et Wine natif sur
  x86_64. Android 64 bits est requis ; l’application conserve minSdk 26 et targetSdk 36.
- `windows-runtime` fournit le sous-ensemble LGPL de Winlator pour X11, le rendu
  OpenGL ES, les entrées, les sockets et la mémoire partagée.
- `WolfRuntimeActivity` gère l’affichage, les commandes, le verrou et la fermeture
  des sessions. `WolfGameStorage` gère les modes d’accès et les conflits de données.
- PulseAudio Bionic fournit la sortie Android AAudio ; IPAexGothic fournit les
  caractères japonais.

Wine et les jeux s’exécutent sous l’UID d’Astra. Le moteur n’est pas un bac à sable
de sécurité pour des exécutables inconnus : le jeu peut accéder aux fichiers
privés accessibles à cet UID.

Le pont [exec_bridge.c](../scripts/wolf-runtime/exec_bridge.c), compilé pour les
deux ABI par le NDK 29, charge les ELF du runtime via `/system/bin/linker64` et
adapte les processus enfants, `/proc/self/exe` et le socket X11. Les pages PE
privées nécessitant une permission exécutable sont recopiées dans une allocation
anonyme lorsque `mprotect` est refusé sur la projection du fichier. Ce pont est
compilé à `-O0` à cause d’une récursion Wine observée avec `-O2` ; le reste de
l’application et du rendu conserve l’optimisation release.

Sur x86_64, `WolfRuntimeInstaller.patchNativeX64` adapte les bornes d’adresses du
`ntdll.so` dont l’empreinte exacte est vérifiée. Cette correction n’est pas
appliquée à ARM64. `WINE_DISABLE_FULLSCREEN_HACK=1` évite une récursion du pilote
sans RandR. Les empreintes attendues et les transformations sont dans
[WolfRuntimeInstaller.kt](../app/src/main/java/fr/astragames/app/windows/WolfRuntimeInstaller.kt).

Le rendu évite les transferts des fenêtres entièrement couvertes par une fenêtre
opaque, conserve les pixels en attente et réutilise les zones inchangées. Les
menus partiels, fenêtres transparentes et curseurs conservent leur rendu. Les
buffers partagés et GPU restent synchronisés ; la définition du jeu n’est pas
réduite. Les réglages Box64 du profil **Rapide** sont décrits dans les sources et
la [documentation Box64](https://github.com/ptitSeb/box64/blob/v0.4.2/docs/USAGE.md).
Ces optimisations ne garantissent pas une cadence minimale sur chaque appareil.

## Provenance et reconstruction

Le code Winlator est épinglé à la révision
`3981d86efa4f333b2a34a7da8b6521476cd8c8b9` du
[dépôt amont](https://github.com/brunodev85/winlator-app). Voir les
[modifications et notices](../windows-runtime/NOTICE.md), le
[texte LGPL](../windows-runtime/LICENSE) et les
[notices embarquées](../app/src/main/assets/windows/THIRD-PARTY-NOTICES.txt).
La police officielle provient du [projet IPA](https://moji.or.jp/ipafont/) ; sa
[licence](../app/src/main/assets/windows/IPA-FONT-LICENSE.txt) est fournie.

Les URL et SHA-256 des composants téléchargés sont centralisés dans
`WolfRuntimeInstaller.kt`. Wine, Box64 et l’audio proviennent des fournisseurs
GameNative et GameNative-x64 épinglés par révision. Aucun code Java/Kotlin
GameNative, jeu ou police Microsoft n’est embarqué. Les empreintes identifient
les binaires fournisseurs ; leur reconstruction depuis les sources n’a pas été
démontrée. Une redistribution autonome de ces paquets doit traiter cette
correspondance et les obligations de leurs licences.

Construire avec JDK 17+, SDK 36, NDK 29.0.14206865 et CMake 3.22.1 :

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :windows-runtime:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

Le module modifié est intégralement fourni ; le remplacer dans `windows-runtime`
puis reconstruire permet de tester une autre version. L’APK release produit par
Gradle est non signé : signer une copie avec sa propre clé ou utiliser la variante
debug. Une signature différente ne permet pas une mise à jour en place.

## Tests et portée

Les tests instrumentés sont prévus pour Android Emulator et les outils SDK, sur
un environnement dédié aux fixtures. Les tests de stockage créent leurs données
synthétiques ; les scénarios utilisant les exemples officiels Wolf demandent de
les obtenir séparément. Les sources ne redistribuent pas ces jeux.

Le test optionnel `WolfIntegratedRuntimeTest` utilise l’installateur et le moteur
réels. Les programmes synthétiques des tests Wine vérifient séparément les
sauvegardes, les accès SAF et l’audio. Le [bilan beta.24](VALIDATION_1.12.0-beta.24.md)
indique quels scénarios ont réellement été exécutés et sur quel APK ; la présence
d’un test dans les sources ne constitue pas une preuve d’exécution.

L’observation d’un flux PulseAudio actif ne mesure pas la qualité ni la latence
sonore. Un benchmark GLES ne mesure pas la cadence complète du jeu. Un test de
stockage sur une image SDK 16 Kio ne qualifie pas à lui seul Wine ou Box64 sur
un appareil à noyau 16 Kio.

- L’exécution ARM64/Box64 sur appareil physique reste non démontrée.
- Les pages applicatives de 16 Kio de l’image SDK reposent sur un noyau/MMU de 4 Kio.
- Les variantes de jeux, DLL tierces, DRM, effets GPU et médias particuliers ne
  sont pas universellement pris en charge ; le MIDI peut nécessiter une banque GM.
- Le passage en arrière-plan suspend le rendu et libère les touches ; le
  processus Windows peut continuer.
- Le téléchargement initial nécessite le réseau ; les lancements suivants
  réutilisent les composants vérifiés. Aucune validation Google Play n’est revendiquée.
