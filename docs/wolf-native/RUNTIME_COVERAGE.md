# Portée du cœur natif au 10 octobre 2026

Le module `wolf-native` contient un interpréteur Kotlin/JVM portable, exécuté directement par Android. Il ne lance ni `Game.exe`, ni Wine, ni Box64. C'est une implémentation partielle en cours de validation, pas un moteur compatible avec tous les jeux Wolf. Le parcours Winlator doit rester le secours automatique pour les jeux refusés et disponible après une erreur du mode natif explicite.

## Exécuté et vérifié sur l'hôte

`WolfRuntime.load` lit les données avec `WolfParser` et les cartes à la demande. La session conserve ses propres variables, DB mutables, RNG, pages, piles d'appels, attentes logiques, entrées, personnages, caméra, dialogues et pics. La VM dispose d'un budget partagé de 50 000 instructions par tick et conserve toute erreur avec carte, événement, commun, opcode et offset. Une commande ou une extension inconnue produit un refus explicite.

Les 21 tests de VM couvrent le parcours construit titre → choix → pic → nouvelle carte → dialogue → déplacement, les branches/loops/appels/retours, namespaces numériques et texte, calculs entiers/flottants/indirects, collision pleine tuile et contact, attente sans blocage, masque AND complet, coordonnées tactiles avec appui/relâchement, pulse de touche, isolement de session et snapshot. Ce sont des fixtures indépendantes ; elles ne prouvent pas la compatibilité d'un jeu existant. Les 13 tests de formats sont documentés séparément et lisent aussi les deux corpus officiels locaux.

Le snapshot privé `AWNS` V2 conserve variables/DB, héros, événements, positions, targets de déplacement, direction/motif/opacité/collision, événements effacés, pics et contrôles. Il vérifie CRC, taille, comptes et chemins avant mutation. Les piles d'événement, attentes et dialogue ne sont pas restaurés, conformément au [contrat officiel de chargement sans événement en cours](https://smokingwolf.github.io/tool_wolf_rpg_editor/help/04ev_file.html). Une sauvegarde pendant une route, un tween de pic ou un son continu encore non sérialisé échoue explicitement. Le codec ne remplace ni ne migre les sauvegardes Windows.

## Refus réels observés

Les deux corpus s'ouvrent et se parsèment, mais leur partie n'est pas jouable dans ce cœur à ce stade. Les tests de boot verrouillent les premiers refus suivants, pas une liste arbitraire d'opcodes possibles :

| Corpus local | Première erreur native |
|---|---|
| Wolf 2.29.6.1 | Carte 0, commun 48, commande 111, offset `0x14d8c`, variable système `9000115` non portée. |
| Wolf 3.72.9 | Carte 0, commun 48, commande 221, offset `0x1c1d4`, commande non portée. |

Le jeu Dragon Blood de l'utilisateur n'est pas disponible dans le corpus local. Les anciens diagnostics de sa session Wine ne constituent aucune preuve de compatibilité native.

## Admission automatique conservatrice

L'admission positive nécessite un profil pris en charge, les métadonnées des communs, toutes les pages/commandes/routes et toutes les cartes. Une incompatibilité définitive arrête la lecture des cartes restantes ; `scannedMapCount` et `inspectedAllMaps` décrivent cette limite. Les cartes sont examinées séquentiellement, sans conserver une liste de matrices ; au plus 512 incompatibilités distinctes sont stockées. Il n'y a aucun parcours de toutes les images ou copie du dossier du jeu.

Sont exclus en automatique tant qu'ils ne sont pas validés : demi/quarter collisions, eau/comptoir/triangle, physique et cadence exacte du héros, mouvements autonomes et routes, ombres/zones/variantes de page, lecteurs d'entrée Wolf, téléportations autres que transfert pleine tuile du héros `-2`, masques DB expérimentaux, audio/fades/MIDI équivalents, sauvegardes Windows et variantes avancées des pics (texte/ancrage/couleur/rotation/motif/tween). Les variables système et références non certifiées sont examinées aussi dans les conditions de pages et de communs. Les namespaces inconnus ne deviennent jamais arbitrairement zéro.

Les codes de texte pris en charge par le renderer sont préservés après substitution de variables ; aucune suppression de couleur, police ou ruby ne modifie une chaîne logique. Les codes de timing et les options inline non portées sont refusés. Le masque de condition bitwise suit `(a AND b) == b`, y compris un masque vide, conformément au [manuel](https://smokingwolf.github.io/tool_wolf_rpg_editor/help/04ev_ifvalue.html).

Le rendu mesure les choix multilignes et fait défiler les longues listes en conservant leurs indices. La pagination des messages n'est pas encore portée : un message ou un libellé individuel trop haut pour la fenêtre est refusé explicitement. Les fenêtres utilisent une peau Android générique, sans reproduction de la peau Wolf personnalisée.

## Limites de preuve

Les masques de plusieurs handlers sont reconstructibles dans les exports REA existants, mais certains handlers DB/pics/input et l'ordonnancement n'ont pas encore de comparaison différentielle complète avec le moteur Windows. Le mode natif explicite sert à tester ces limites et peut s'arrêter avec un diagnostic. Un résultat JVM, un fichier de format correctement lu ou un frame Canvas ne garantit pas 30/60 FPS, la jouabilité des corpus ou la compatibilité d'un jeu commercial. Toute preuve Android doit utiliser l'Android Emulator et les outils SDK autorisés, et être consignée séparément.
