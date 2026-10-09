# Validation Astra 1.12.0-beta.8

Version Android 36, runtime `wine9-astra-2` conservé. Cette livraison cible le coût du
rendu pendant les textes et les transitions d’image. Le retour utilisateur sur la bêta 7
confirme près d’une heure de jeu sans crash, mais des baisses à 14–15 FPS. Aucun profil
de ces ralentissements sur le Samsung ni jeu personnel n’est disponible dans ces tests.

## Changements

- Les opérations de dessin conservent la zone réellement modifiée. Les petites modifications
  ne rechargent plus toute la texture ; les zones sont réunies avant la présentation.
- Pour les fenêtres logicielles, les copies d’images comparent les lignes aux pixels existants.
  Les lignes identiques ne sont ni recopiées ni transférées ; une image inchangée ne demande
  pas un nouveau dessin. Les pixmaps temporaires gardent leur copie rapide.
- Les transferts partiels GLES 3 lisent le buffer existant avec sa longueur de ligne,
  sans allouer/copier un second buffer. Les états de lecture sont restaurés après transfert.
- Le premier chargement ne transfère plus deux fois la même texture.
- Les mises à jour sont regroupées via `Choreographer` sur le rafraîchissement Android,
  dans la limite 30/60 images/s. Aucun rendu permanent sur une scène immobile.
  Pause/reprise suspendent/réamorcent les callbacks.
- Le calcul des poids du lissage cubique utilise des multiplications plutôt que `pow`.
  L’interpolation, la résolution et les profils CPU restent les mêmes.
- Toutes les dix secondes, le diagnostic mesure les notifications, dessins, temps de
  soumission CPU/pilote, intervalle p95 des images modifiées, Mio/s et transferts complets/partiels.
  Cette durée n’inclut pas l’exécution GPU asynchrone. Aucune attente `glFinish` en production.
- Le compteur est explicitement **Images modifiées**. Il exclut les images identiques,
  contrairement au compteur antérieur de notifications de contenu présentées. Une image
  statique ou une animation limitée peut produire une valeur basse même si le moteur tourne
  à 60 Hz. Il ne constitue pas une mesure des FPS internes du jeu.

Les buffers partagés et les opérations dont la région est inconnue gardent un transfert complet.
La synchronisation existante entre X11 et le rendu est conservée. Pas de modification des fichiers
du jeu, du démarrage mis en cache, des sauvegardes ou des contrôles.

## Mesures du 9 octobre 2026

Android Studio Emulator API 36.1 x86_64, textures BGRA 1280×960, 200 opérations par cas.
Les temps comprennent la soumission GLES et sa terminaison (`glFinish` uniquement dans le test).
La référence utilise la copie native et le transfert complet antérieurs ; chaque cas part de
buffers identiques. Aucun gain Box64 ARM64 n’est déduit de ces résultats.

| Copie d’une image logicielle complète | Médiane | p95 | Octets transférés, 200 images |
| --- | ---: | ---: | ---: |
| Référence : texte modifié 256×32, transfert complet | 1,632 ms | 1,791 ms | 983 040 000 |
| Même texte, copie/transfert des changements | 0,828 ms | 1,031 ms | 6 553 600 |
| Image entièrement identique | 0,138 ms | 0,238 ms | 0 |
| Image entièrement modifiée | 1,523 ms | 1,682 ms | 983 040 000 |

Pour ce texte : **environ 49 % de temps en moins et 150 fois moins d’octets transférés**.
Une image entièrement modifiée garde un coût comparable. Le test séparé de transfert GLES
sans copie d’image mesure 1,425 ms contre 0,731 ms de médiane pour la même zone de texte.
Ce sont des coûts d’opérations, pas une promesse de doublement du FPS d’un jeu.

Sur les séquences de la carte officielle Wolf 3.729 sans déplacement, les anciennes mesures
de cette session transféraient environ 45–52 Mio/s, contre environ 2–5 Mio/s avec détection
des changements. Les scènes/durées ne constituent pas un benchmark de gameplay identique.
Les images modifiées sont moins nombreuses qu’avec l’ancien compteur : ce changement de
définition interdit de comparer directement leurs valeurs comme des FPS.

## Vérifications

- Builds debug, instrumentation et release R8 : réussis, ARM64 et x86_64 compilés.
- **222 tests JVM**, aucun échec. Lint application : aucune erreur, 77 avertissements.
  Lint du module Windows : aucune erreur, 6 avertissements.
- Quatre tests GLES/JNI sur émulateur : lecture de tous les pixels, clipping, union de régions,
  offsets de copie, lignes, remplissage complet, recréation de texture, copies d’images entières,
  image identique, modification d’un pixel, 35 modifications pseudo-aléatoires, deux benchmarks.
- Jeu officiel 3.729 avec le moteur réel : séquence de deux minutes, contrôles, portrait/paysage,
  lissage standard/renforcé, plafond 30, réglages et fermeture finalisée réussis.
- Les huit tests d’intégration/commandes/réglages passent. Après les pages d’introduction,
  les déplacements par appui tactile dans quatre directions présentent **38,7 puis 42,0
  images modifiées/s en moyenne** sur deux passages. Les captures confirment le déplacement
  et le défilement de la carte. Ce n’est pas une garantie de cadence minimale à chaque instant.
- APK signé de release, non debuggable et optimisé avec R8 : jeu prêt en **1 504 ms**,
  scène/commande Valider contrôlées à **16 499 ms**, attentes fixes de la sonde comprises.
  Fermeture et serveur Wine finalisés ; capture examinée. Version 36, alignement ZIP et
  certificat identique aux bêtas précédentes validés. La clé historique reste la clé Android
  debug locale, comme pour les livraisons précédentes.

Preuves locales : `build/wolf-research/beta8-build-final.log`, `beta8-transfer-tests.log`,
`beta8-software-benchmark.txt`, `beta8-transfer-final2.txt`, `beta8-final-device-tests.log`,
`beta8-changedframes-soak.txt`, `beta8-motion-tests.log`, `beta8-signature.log`.
Le passage final est dans `beta8-controls-final.log`, `beta8-walking-final.txt` et
`beta8-release-runtime.log` ; captures `beta8-walking-right.png`, `beta8-walking-left.png`,
`beta8-release-game.png` et `beta8-settings.png`.

## Limites

Le dessin Wolf reste logiciel sous Wine ; OpenGL présente son image. Cette optimisation
élimine des copies/transferts inutiles, mais ne transforme pas le moteur en rendu DirectX
accéléré. Les calculs du jeu, Box64, les effets, les accès aux ressources et la température
du téléphone peuvent encore limiter la cadence.

Aucun minimum permanent de 30–60 FPS n’est validé sur le SM-S948B ni dans Dragon Blood.
Les prochaines comparaisons sur appareil peuvent exploiter le diagnostic de rendu ajouté.
L’éditeur de variables/cheats demandé puis reporté n’est pas inclus.
