# Mouvements autonomes : qualification beta.19

Artefact officiel Wolf3.729, x86 PE Game.exe, SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`, analyse statique REA/Ghidra12.1.4. L’original n’est ni exécuté ni modifié. Session fermée et snapshot privé `build/wolf-research/beta19-autonomous-root.rea` ; exports privés exclus de la release. Les fonctions décompilées ne sont pas le code source original.

| Contrat | Adresse | Evidence ID |
|---|---|---|
| Décision aléatoire | `009fbd50` | `ev_b8856eb9b40168a575c893dd9b1aa2e213b1d1c18285c37a1b6e27fd2593a2c4` |
| Décision approche | `009fbd90` | `ev_a00176b1b8b296468fab7fe391ea7d527ccc4b4ab855c29441bf06b8a9f010ca` |
| Déplacement aléatoire | `009ff300` | `ev_0cea990e88b875f7aed43bfbd1f28ec0f44cb61ff3f9f178732d6ce7cb1cac6c` |
| Approche et choix de direction | `009ff3c0` | `ev_9f20f2ec3bc346a60b17c7775452c517d979464ff54aece6ea1583c841cda701` |
| Pas avant | `009ff640` | `ev_1ef19d73a2bb71fd7dff059cff016169e0275d2721f5cdeed28ce673f83f93f2` |
| Collision/direction/attente | `009f56f0` | `ev_e0bf603624d2f179f6149ea56f224d859279b0c6164c613c7713683b5d400763` |
| Engagement d’un pas | `009eb070` | `ev_33600c6169abbf6c1180c331a74393101b1b264e686398f2f874f24cc5b7d839` |

Le sélecteur `009f50e0` compare l’attente à fréquence²×20. Les quatre bytes de page sont animation, vitesse, fréquence de mouvement, mode ; la fréquence est lue sans signe dans la page puis conservée comme short dans le personnage. Mode0 immobile,1 route,2 aléatoire,3 approche. Les valeurs0..255 de fréquence ne sont pas arbitrairement limitées à6 ; les fréquences d’animation et la vitesse restent0..6.

Le mode2 consomme un tirage modulo6 :0..3 déplacent aléatoirement,4 avance dans la direction courante,5 reste immobile. Le mode3 mesure la distance de Manhattan en demi-cases. En dessous de20, un tirage modulo6 choisit0..3 approche,4 aléatoire,5 avant. Au-delà, il utilise l’aléatoire sans consommer ce premier tirage. L’ordre des directions tirées est2,4,6,8 puis1,3,7,9 en huit directions. Il partage le RNG Park–Miller de la VM et les collisions/routes réellement portées.

La direction après blocage est conservée pour le pas aléatoire ; l’approche et le pas avant peuvent orienter le personnage bloqué, sauf direction fixée. Les comportements de loop, passages et extensions de contact utilisent le monde courant. Les helpers ne désactivent pas les protections des routes non portées.

La restauration recrée les paramètres de la page même si son marqueur sauvegardé correspond déjà à la page active. Le marqueur seul ne signifie pas que l’objet personnage recréé a reçu son état. Les paramètres sauvegardés qualifiés reprennent ensuite priorité.

Les tests couvrent les branches de décision, consommation RNG, seuil, ordre des directions et restauration de page avant le premier tick. Cette preuve n’est pas une partie Seris ni une validation des 159 pages signalées : les bytes de mouvement de ces pages ne figurent pas dans le rapport utilisateur.
