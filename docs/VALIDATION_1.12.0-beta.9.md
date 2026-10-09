# Validation Astra 1.12.0-beta.9

Version Android 37, runtime `wine9-astra-2`. Cette livraison ajoute le cadrage en plein écran
et réduit les blocages entre Wine et le pilote graphique. Le retour utilisateur confirme une
amélioration avec la bêta 8 ; les ralentissements de Dragon Blood sur Samsung ne sont pas
reproduits sur un appareil physique dans cette validation.

## Affichage et commandes

- Le jeu utilise toute la surface Android, avec barres système masquées et réapparition par
  balayage. La bande auparavant réservée aux commandes en portrait est supprimée.
- Le cadrage utilise la taille de la fenêtre du jeu, indépendamment du bureau Windows.
  Cela évite deux ajustements successifs et leur marge supplémentaire.
- **Image entière** reste le défaut : agrandissement maximal, proportions conservées, tout
  le contenu visible. Les bandes nécessaires restent lorsque les formats sont différents.
- **Remplir** agrandit sans déformer et coupe les bords qui dépassent ; **Étirer** occupe
  toute la surface en modifiant les proportions. Les réglages sont immédiats et conservés
  par jeu, depuis Outils → Réglages Wolf RPG ou Menu → Réglages du jeu.
- Les clics utilisent le cadrage courant, y compris les offsets négatifs du recadrage.
  Les zones masquées hors de l'écran sont rejetées et relâchent les commandes tactiles.
- Les commandes et leurs positions enregistrées respectent les encoches et les barres
  visibles. Les écrans de préparation et de résultat respectent également ces zones.
  L'overlay de verrouillage Compose conserve ses insets et ceux du clavier.
- Le bureau automatique est 1280×960 pour un lancement en portrait et 1280×720 en paysage.
  Une résolution manuelle existante est conservée. La rotation en cours de partie recadre
  l'image ; elle ne redémarre pas Wine et ne change pas le bureau virtuel.

Wolf propose plusieurs définitions natives, dont du 16:9, dans sa
[configuration officielle](https://silversecond.com/WolfRPGEditor/Help/02gamesetting.html).
Changer le bureau Windows ne crée pas de décor supplémentaire dans un jeu fixé en 4:3.
Le mode Image entière respecte aussi une image native 16:9. Aucun fichier de jeu original
n'est modifié pour forcer un format.

## Synchronisation du rendu

Pour les fenêtres logicielles privées, le rendu copie la zone modifiée dans un buffer direct
réutilisé sous le verrou X11, puis relâche ce verrou avant la soumission au pilote graphique.
Wine peut traiter les requêtes suivantes pendant le transfert et le dessin de l'image précédente.
Les changements arrivant entre la capture et le transfert restent en attente pour l'image suivante.
L'instantané assure une image cohérente ; aucune lecture GLES directe d'un buffer en cours d'écriture.

Les buffers partagés, les images GPU et les textures alimentées directement par GPU conservent
le chemin complètement synchronisé et l'ordre des fenêtres. Un buffer temporaire est conservé
par texture visible ; sa capacité croît seulement lorsque la région à capturer l'exige.
Cela ajoute jusqu'à une image de mémoire pour chaque fenêtre logicielle présentée, sans charger
les ressources du jeu en mémoire. Les buffers sont libérés avec leur texture.

Le diagnostic mesure aussi la durée du verrou des images, distincte de la soumission CPU/pilote.
Aucune attente `glFinish` n'est ajoutée au rendu de production.

## Mesures du 9 octobre 2026

Android Studio Emulator API 36.1 x86_64, GLES/JNI réels, texture BGRA 1280×960,
200 mises à jour par cas. La référence transfère directement depuis le buffer du drawable,
comme en bêta 8. La section critique exclut l'acquisition du verrou ; le coût total inclut
`glFinish` uniquement dans le benchmark. Ces mesures ne sont pas des FPS de gameplay.

| Zone modifiée et chemin | Verrou médian | Verrou p95 | Total médian | Total p95 |
| --- | ---: | ---: | ---: | ---: |
| Texte 256×32, référence directe | 0,746 ms | 0,963 ms | 0,813 ms | 1,080 ms |
| Texte 256×32, instantané réutilisé | 0,013 ms | 0,021 ms | 0,135 ms | 0,363 ms |
| Image 1280×960, référence directe | 0,624 ms | 0,784 ms | 1,479 ms | 1,718 ms |
| Image 1280×960, instantané réutilisé | 0,175 ms | 0,266 ms | 1,734 ms | 1,960 ms |

Le verrou diminue d'environ 98 % pour le texte et 72 % pour l'image complète. La copie de
l'image complète ajoute environ 0,25 ms au coût total médian ; les requêtes Wine peuvent
avancer pendant la partie pilote de ce travail. Aucun gain équivalent de FPS sur ARM64
n'est déduit de ces résultats.

Le parcours de la carte officielle 3.729 présente environ **37,2 images modifiées/s** pendant
les déplacements tactiles. La soumission moyenne est de 1,58 ms et le verrou de 0,10 ms sur
la fenêtre de mesure enregistrée. Sur une carte immobile, seules les animations changent,
avec environ 5 images modifiées/s. Ce compteur exclut les images identiques et ne mesure
pas la cadence interne du moteur. Les passages ne constituent pas une comparaison de
FPS contrôlée avec la bêta 8 ; aucun plancher permanent n'est validé.

## Vérifications

- Builds debug, instrumentation et release R8 : réussis, ARM64 et x86_64 compilés.
- **228 tests JVM**, aucun échec. Lint application : aucune erreur, 77 avertissements ;
  module Windows : aucune erreur, 6 avertissements.
- Seize tests Android de rendu, options, commandes et reprise après erreur passent : pixels GLES/JNI,
  transfert partiel, image identique, recréation, nouveaux changements entre capture et
  transfert, benchmarks, préférences, clipping tactile, appuis et disposition avec insets.
- Jeu officiel 3.729 avec Wine réel : déplacements, rotation, limites d'affichage, six
  combinaisons cadrage/orientation, menus de sélection et fermeture finalisée passent.
  L'encoche simulée est activée ; les captures sont examinées.
- Le cadrage physique vérifié sur l'écran 1080×2400 est : portrait entier 1080×810,
  rempli 3200×2400 avec offset X −1060, étiré 1080×2400 ; paysage entier 1440×1080,
  rempli 2400×1800 avec offset Y −360, étiré 2400×1080.
- Le lancement interne incomplet passe maintenant par le gestionnaire de diagnostic,
  au lieu d'échouer avant l'ouverture d'une session.
- L'APK signé optimisé avec R8 lance le jeu officiel en paysage avec bureau automatique,
  affiche sa scène, accepte Valider et ferme Wine proprement : jeu prêt en **1 466 ms**,
  scène contrôlée à **16 821 ms**, dont 14,5 secondes d'attentes fixes de la sonde.
  APK non debuggable, version 37, alignement ZIP et certificat historique vérifiés.
  La signature utilise la clé Android debug locale historique, comme les précédentes bêtas.

Les essais Android utilisent exclusivement l'émulateur Android Studio, ADB et l'instrumentation
du SDK. Le panneau d'information Android du premier passage en plein écran a été confirmé
dans l'AVD ; le test sélectionne explicitement la racine du menu déroulant natif.
L'écran du jeu reste protégé par `FLAG_SECURE` dans l'application de production.

Preuves locales : `build/wolf-research/beta9-build-final.log`, `beta9-render-controls-final.log`,
`beta9-lock-benchmark.txt`, `beta9-integrated-modes.log`, `beta9-fps-modes.txt`,
`beta9-signature.log`, `beta9-release-runtime.log`, `beta9-release-game.png`,
captures `beta9-integrated-landscape-FIT.png`,
`beta9-integrated-landscape-FILL.png`, `beta9-integrated-landscape-STRETCH.png`
et les trois équivalents portrait.

## Limites

Les calculs du jeu, le décodage des ressources, le dessin logiciel Wolf et Box64 peuvent
encore limiter la fluidité. Cette livraison réduit la contention du rendu ; elle ne constitue
pas une preuve de 30–60 FPS permanents sur le SM-S948B ou dans Dragon Blood.
Les modes Remplir et Étirer ont les compromis affichés dans les réglages. Aucune extension
universelle des cartes 4:3 ni augmentation des détails natifs d'un jeu n'est annoncée.
