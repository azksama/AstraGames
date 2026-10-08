# Validation Astra 1.12.0-beta.7

Version Android 35. Runtime téléchargé `wine9-astra-2` inchangé. Le lancement avec cache
de la bêta 6 est conservé. Aucun jeu, sauvegarde ou rapport personnel n’est publié.

## Fluidité et affichage

Le retour utilisateur confirme le lancement rapide et le début de partie, mais signale
2–3 FPS sur Samsung ARM64. Aucun profil de cette session sur cet appareil n’est disponible.
Les changements ci-dessous visent le coût CPU et le transfert de l’image ; ils ne constituent
pas une preuve de la cause précise ni une garantie de 30 FPS dans Dragon Blood.

- Profil **Équilibré** par défaut : STRONGMEM=1, BIGBLOCK=2, NATIVEFLAGS=0.
- Profil **Rapide** optionnel : STRONGMEM=0, BIGBLOCK=3, NATIVEFLAGS=1.
- Profil **Stable (bêta 6)** : STRONGMEM=2, BIGBLOCK=0, NATIVEFLAGS=0.
- SAFEFLAGS=2 et X87DOUBLE=1 restent communs. Les compromis mémoire/blocs sont décrits
  dans la [documentation Box64](https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md).
  Revenir à Stable si un jeu devient instable. Les réglages sont conservés séparément par jeu.
- Les mises à jour de contenu X11 sont regroupées avant présentation, avec plafond 30/60.
  Pas de boucle de rendu permanent lorsque le jeu ne produit aucune image.
- Le debug garde erreurs, avertissements SEH et chargements de DLL, sans tracer chaque
  exception SEH. La latence audio demandée passe de 40 à 80 ms pour amortir les sous-alimentations.
- Compteur des **images de contenu X11 reçues et présentées**, distinct du FPS interne du moteur
  et de la fréquence de l’écran. Un écran statique peut afficher une valeur basse normalement.
  Une mesure est jointe au diagnostic toutes les dix secondes.
- Bureau Windows réglable 800×600, 1280×720, 1280×960 (défaut), 1920×1080.
  L’agrandissement conserve le ratio, y compris les fenêtres Wolf logicielles déclarées décorées
  par Wine. Les fenêtres déclarées comme dialogues ne sont pas forcées en plein écran.
- Lissage renforcé optionnel par interpolation cubique B-spline, quatre lectures bilinéaires.
  Le mode désactivé conserve l’interpolation bilinéaire antérieure. Cela adoucit les pixels,
  sans inventer de détails ou changer automatiquement la définition interne des jeux.

Le dessin Wolf reste en mode logiciel pour préserver le lancement fonctionnel. OpenGL présente
l’image X11 ; cette livraison n’ajoute pas un moteur DirectX accéléré pour tous les jeux.

## Tactile et commandes

- Clic souris à la position touchée, transformation des coordonnées et rejet des bandes
  hors du viewport. Le déplacement vers une destination dépend du support du jeu.
- Mode alternatif : maintenir un côté de l’image pour envoyer une direction, zone neutre au centre.
  Pas de connaissance de la carte, de la position du héros ou des obstacles : aucun pathfinding promis.
- Annulation/libération lors de la sortie du viewport, fin du geste, ouverture du menu,
  pause et rotation. Les sources tactile/boutons/manette partagent les touches sans relâchement prématuré.
- Déplacement des sept boutons, remappage des actions usuelles, opacité 15–100 %, taille 70–150 %.
  Positions normalisées séparées portrait/paysage. Le bouton Menu reste accessible.
- Réinitialiser les touches conserve les réglages de performance et d’image.
- Réglages accessibles avant le lancement depuis les outils de la fiche du jeu et en partie.
  Profils CPU et résolution au prochain lancement ; autres paramètres appliqués immédiatement.

## Vérifications du 8 octobre 2026

- Builds debug, instrumentation et release avec R8 réussis ; **222 tests JVM**, zéro échec.
  Lint : zéro erreur, 77 avertissements.
- Android Studio Emulator API 36.1 x86_64 : **8 tests ciblés réussis** : persistance/isolement,
  valeurs invalides, mapping tactile/annulation, direction, appuis simultanés, remappage partagé,
  glisser/revenir sur annulation, et lancement réel de l’échantillon officiel Wolf 3.729.
- Test réel : scène affichée, Valider, portrait/paysage, transformation inverse du centre de la
  fenêtre agrandie, paramètres lisibles et défilables, fermeture finalisée sans serveur orphelin.
  Captures examinées visuellement. Une course liée à l’animation d’un PopupMenu du banc de test
  a été supprimée en désactivant les animations sur cet émulateur dédié, sans changer l’application.
- Mesure avec agrandissement et lissage renforcé : **43,0 images de contenu/s sur dix secondes**
  puis **53,5** au second passage sur cet émulateur. Le plafond 30 et le passage au lissage
  standard ont été testés en paysage : **28,6 images/s**, limite respectée et images toujours reçues.
  Ces résultats x86_64 n’exécutent pas Box64 ARM64 et ne prédisent pas le FPS du Samsung.
- APK signé de release avec R8 : moteur prêt en **1 540 ms**, scène affichée et commande Valider
  testée à **16 524 ms**, attentes fixes du probe comprises. Fermeture propre vérifiée dans le
  diagnostic. Capture examinée ; signature identique à la bêta 6, version 35 et alignement ZIP validés.
  La clé de signature historique est toujours la clé Android debug locale, comme les bêtas précédentes.

Preuves locales : `build/wolf-research/beta7-build.log`, `beta7-device-tests.log`,
`beta7-integration-final.log`, `beta7-landscape.png`, `beta7-settings.png`, `beta7-fps.txt`,
`beta7-release-runtime.log`, `beta7-release-game.png`, `beta7-signature.log`.

## Limites

Pas de mesure sur le SM-S948B ni avec Dragon Blood, pas de preuve de gain relatif de Box64
sur ce téléphone. Les profils, le compteur et les diagnostics permettent cette comparaison.
Les jeux qui exigent leur propre souris, touches ou résolution peuvent nécessiter un réglage.
Une hausse de résolution ou le lissage renforcé peut coûter des performances selon le GPU.
La cible de 30 FPS reste à confirmer sur l’appareil de l’utilisateur.
