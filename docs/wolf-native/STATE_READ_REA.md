# Lectures d’état Wolf : contrat REA de la commande124

Analyse statique du Game.exe officiel Wolf3.729, SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`, x86 PE, REA6.3.0/Ghidra12.1.4. Profil d’analyse `147197ae9bfae42dc35826e4b23e5989fee5d5139aab74b13dcab0515e145825`. La source est conservée intacte dans `build/wolf-research/sample3729/WOLF_RPG_Editor3/Game.exe`. Aucun EXE original n’a été exécuté ou modifié dans cette analyse. Les exports privés sont exclus des sources distribuées.

Ce document recense les champs **observés**, sans rendre automatiquement admissibles les commandes dont le runtime ou l’hôte ne sait pas fournir les données. Les pages/commandes originales de Seris ne sont pas disponibles : le rapport beta.18 indique des offsets et catégories de refus, mais pas les flags ni les operands. On ne peut donc pas transformer les 375 incompatibilités de son audit en 375 corrections prouvées.

## Provenance

| Objet | Adresse | Evidence ID |
|---|---|---|
| Handler124, pseudo-code conservé | `00a14330` | `ev_c6f2b84efdf158453e5030a4789e3c5cf2a6b6e8efa13f1964f4dc3e54b07f54` |
| Handler124, instructions complètes | `00a14330` | `ev_a2bfc6bf6da063c88314f9eb4ea89196eaefecafd24a0bab142494a30a7729ce` |
| Métadonnées du handler, relecture actuelle | `00a14330` | `ev_bacf9a3418d045aa1fe93d591acdfc57b22d2621cf262360c9107cb5dded3fe1` |
| Référence depuis le dispatcher | `00a083e5 → 00a14330` | `ev_184fdc547c6f37a39eccd3e191697e7b4a3eb7bffa1675de9d45d1f4b1443146` |
| Table des catégories | `00a17ad4`,16 octets | `ev_2ccbf9fdc53261d052dd56e6245958d65cc730d552d96cf8365e1e1a946664bd` |
| Table des31 champs de personnage | `00a17ae4`,124 octets | `ev_de4e54d032ddb0d9142ec4d3ea6aafd8b8028f648828501c6218ba1528ba19b3` |
| Cibles compressées des champs de position | `00a17b70` | `ev_68ec5baf11a2044449194b848b73f0926eb3cd914ac6d027f68e5c43cd4e1740` |
| Remap complet163 octets de position | `00a17bd8` | `ev_b015456ee1766c10e537a042c146253b1112fbeb74e3c46df0443bd697f6dd99` |
| Table des36 champs généraux | `00a17cc8`,144 octets | `ev_24248166695fa1237a11b13411d60c20b274e5dd5de3277c4f13d6811acffac4` |
| Getter des propriétés de picture, pseudo-code | `0098c140` | `ev_7c0c6e1b3c7cae9fee92b8d75c63c7119444c7e9caa1210285b58300532b19d3` |
| Getter des propriétés, instructions complètes | `0098c140` | `ev_1aa53b11f65f0dd350a6382ddd026568a6045bd594be980c247e96a1c930fae3` |
| Binding des bytes Game.dat | `0045e1a0` | `ev_301d6ff6d5ea90c1b5b1d81a1f81be2abea2983fa1bf5abb589785be3f5e7cf2` |
| Appel du binding avec objet`00f511c8` | `0045e607` | `ev_dc6c2bcda22547cc323df46d2e429a561df256ace870037d31e518af7aa07aea` |
| Table de langue | `00cd8580`,28 octets | `ev_3d3120353135c07f5e6331d127a6e65ab3efd385a085918b2261619d1273db9c` |
| Tick réel d’une picture | `0098f1a0` | `ev_34c4158ec3066d4077966584e25de75d5ec1f823bedf11203e4879a1627b005c` |
| Setter de durée, instructions`0098f03d/43` | `0098e920` | `ev_536f0dbbf1ceaf325ca90530eb8d3cee5f46a19d8ac793c27c1fce9c477be587` |
| Setter de paramètres, fin`009886cd` | `00987630` | `ev_87d1689cddc081593a710de91a0a73f5d3ce367d4c42642d98c58b2c5e73fd56` |
| Préparation des metadata de personnage | `009c8be0` | `ev_fa51e603958f95b5a96fe8f043e0fc00f44450ad00b908988910581f87ae72a3` |
| Handler de lecture d’entrée123 | `00a14280` | `ev_d3cf42a3f82dab8443ee1c7b0098d02a65da27a291660c6131bfeed497623911` |
| Lecteur d’entrée, dont catégorie souris | `00a06cf0` | `ev_27f578c64bea9816fcc3fb8317417651743a7e5c1852093926584bc4c8611823` |
| Mise à jour des entrées et compteurs d’appui | `009b4c90`, pseudo-code | `ev_e380885b63311b4452cc3e3a1a55f27ecdd0ebd23aca7275f87387a4bf5afcb7` |
| Mise à jour des compteurs, instructions | `009b6c37..009b6c76` | `ev_1193c7b6534f8eccfbdf21dc9247fac4df48bf9771ebd036f0b7dd2a70c884ab` |

Le corps du handler contient749 blocs et14238 octets, avec deux plages inclusives : `a14330..a159ec` et `a159f0..a17ad0`. Son pseudo-code contient des `break` vides et des pseudo-types non stabilisés : ceux-ci ne constituent pas une preuve de retour zéro. Les tables et instructions permettent de rétablir les chemins que le décompilateur masque.

Exports historiques : `build/wolf-rea/wolf3-native-followup/vm-01-value-state-w3.c.txt`, `vm-05-value-state.asm.txt`, `w3r-128-picture-properties.c.txt`. Relectures actuelles : `build/wolf-research/beta19-state-rea-*.json`, `beta19-state-rea-extra-*.json`, `beta19-state-getter-rea.json` et `beta19-state-getter.asm.txt`. Certains anciens noms de fichier sont trompeurs : `w3r-50-picture-getter.asm.txt` appartient au hit-test`0099da70`, pas au getter`0098c140` ; `w3r-10-picture-tick.asm.txt` contient le setter, pas le tick`0098f1a0`.

## En-tête et flags

`args[0]` désigne la destination. `args[1]` contient les flags. Les bits12..15 sélectionnent1=personnage,2=position,3=général,4=picture. Les bits8..11 portent l’opération0..8 : affectation, addition, soustraction, multiplication, division, modulo, maximum, minimum, valeur absolue. Les autres catégories/opérations ne produisent pas une affectation dans ce PE.

| Bit | Contrat observé |
|---|---|
| `0x01` | Limiter le résultat à±999999. |
| `0x10` | Destination indirecte : résoudre la destination et ajouter2000000 si elle est inférieure à1000000. |
| `0x20` | Coordonnées précises, catégorie position seulement. |
| `0x40` | **Inclure les effets de picture**, catégorie picture seulement. |
| `16..23` | Numéro du champ de position. |

À`a168d3`, le caller passe directement `(flags >> 6) & 1` au getter`98c140`. Ce getter ajoute les effets uniquement quand le paramètre est non nul. Le libellé de la case UI « sans correction » ne doit donc pas être utilisé comme nom du bit sérialisé : son sens est inversé. Aucun seuil de version n’est vérifié dans ce caller ; ne pas introduire un seuil fictif3704 dans le mot Game.dat, dont les valeurs sont372/369/etc.

Les masques qualifiés sont`00ffff31` pour position,`0000ff51` pour picture,`0000ff11` pour personnage/général, puis validation séparée de la catégorie et de l’opération. La reconnaissance du format des flags ne vaut pas prise en charge du champ. Les bits réservés restent refusés.

## Personnage : args[2]=cible, args[3]=champ

Une cible inexistante renvoie−1. Pour les profils≥321, le filtre de suppression temporaire situé à`a14443` renvoie−1 pour les champs12/13/16/20 ; ID et coordonnées restent lisibles. Les numéros ci-dessous viennent des31 entrées de`a17ae4`.

| Champ | Cible du branchement | Valeur |
|---|---|---|
| 0/1 | `a144bb/d7` | Position logique en cases entières, division signée par2 puis wrap de la carte. |
| 2/3 | `a144f3/a1450a` | Position logique en demi-cases, wrap sur2×dimension. |
| 4 | `a14521` | Élévation en pixels, actor+`c0`. |
| 5 | `a1452c` | Direction, short signé actor+`4c`. |
| 6/7 | `a14535/4b` | Position écran non zoomée via`9ea4e0/9ea590`, respect du bouclage. |
| 8 | `a14561` | Premier byte du vecteur de numéro d’ombre, ou−1 si vide. |
| 9 | `a1457d` | Tag du point logique, via`9fa7c0`. |
| 10 | `a14595` | ID réel, y compris après suppression temporaire. |
| 11 | `a1459d` | Présence dans les limites de l’écran avec marge de tuile, sans zoom. |
| 12 | `a14632` | Index de page active+1 ;0 sans page ;−1 si supprimé selon profil. |
| 13 | `a14667` | Trigger de page0..4 ; page absente/supprimée : branches de version. |
| 14/15 | `a146a4/f6` | Extensions de contact X/Y, bytes1/2 des features ;0 si features absentes. |
| 16 | `a14748` | **Pattern d’animation basé sur0**, calculé avec les shorts`54/4e`, pas le pattern picture basé sur1. |
| 17 | `a147ec` | Mouvement logique ou pixel effectivement produit. |
| 18 | `a148b2` | Divisions horizontales de sprite ; graphique de tuile=1. |
| 19 | `a148e5` | Divisions verticales ; tuile=1 ; sprite complet avec suffixe`$`=1 dans profil≥372, sinon4. |
| 20 | `a14939` | Pattern de la picture du personnage, basé sur1 ; clamp selon metadata et profil. |
| 21/22 | `a149c9/e1` | Correction pixel X/Y, actor+`158/15c` ;0 si actor-picture absente. |
| 23 | `a149f9` | Vitesse, short actor+`5e`. |
| 24 | `a14a02` | Fréquence de mouvement, short actor+`62`. |
| 25 | `a14a0b` | Fréquence d’animation, short actor+`60`. |
| 26 | `a14a14` | Opacité, short actor+`48`. |
| 27 | `a14a1d` | Traversée des obstacles, byte actor+`57`. |
| 28/29 | `a14a2a/6d` | Position écran après shake et zoom : `v+(v−centre)*(zoom−100)/100`, multiplication int32 et troncature signée. |
| 30 | `a14a8e` | Directions franchissables :1 haut,2 gauche,4 droite,8 bas ;16/32/64/128 diagonales si la configuration et les passages le permettent. |

Les metadata du sprite, sa cadence et ses collisions doivent être les données réelles du runtime. Ne pas déclarer les champs18..20 ou30 compatibles en remplaçant les informations absentes par une constante. Le helper de zoom prend une coordonnée sans shake ; un caller dont la caméra inclut déjà le shake doit passer0 pour éviter sa double application.

Pour 18/19, `009c8be0` combine le nombre de patterns, les directions de l’image et son type de ressource. Avec `P` patterns, la grille horizontale vaut `P×D` normalement, `(P+1)×D` pour le suffixe `T`, `2×P×D` pour `TX`, où `D=2` pour 8 directions d’image et `D=1` pour 4. Le suffixe `$` est une image complète à une division. Les 8 directions ne signifient donc jamais 8 lignes : les directions supplémentaires se trouvent dans les colonnes. Ces règles sont distinctes des directions de déplacement.

Pour 21/22, le getter teste d’abord la présence de l’actor-picture `actor+154`, puis lit directement les corrections entières `actor+158/15c`. Les corrections d’effets graphiques, le shake ou les pixels d’une texture ne remplacent pas ces offsets de déplacement. Leur portage exige que les valeurs du déplacement pixel du runtime soient bien les mêmes résidus que ces champs ; si ce lien n’est pas établi, le champ reste refusé. Pour 20, la présence d’un pattern logique ne prouve pas le pattern effectivement choisi dans l’actor-picture.

## Général : args[2]=champ

| Champ | Cible du branchement | Valeur |
|---|---|---|
| 0 | `a16a18` | ID courant de carte, avec la branche native pendant transfert. |
| 1/2 | `a16a31/47` | BGM/BGS choisi dans la base système ;−1 si fichier direct ou absent. BGS sélectionné par Sys99. |
| 3/4 | `a16ab6/a16b0d` | Position BGM/BGS en ms ; MIDI en ticks dans Windows ;0 si aucune voix correspondante. |
| 5/6 | `a16b89/be6` | Durée BGM/BGS, mêmes unités ;0 si aucune voix correspondante. |
| 7/8/9 | `a16c62/84/a6` | Souris gauche/milieu/droite :2 nouveau down,1 maintenu,0 sinon. |
| 10 | `a16cc8` | Delta de molette de la frame. |
| 11/12 | `a16cd3/de` | Delta X/Y du pointeur de la frame. |
| 13 | `a16ce9` | Événement sous le pointeur, priorité au plus petit ID,−1 sinon ; ni joueur ni compagnon. |
| 14 | `a17049` | ID du map-event appelant cette commande. |
| 15/16 | `a17057/62` | Dimensions de carte. |
| 17 | `a1706d` | ID du common courant,−1 sinon. |
| 18 | `a1707b` | Événement foreground actuel ; common encodé+500000. |
| 19 | `a170e9` | Ligne de l’événement foreground actuel. |
| 20/21 | `a1715a/65` | Coordonnées X/Y du pointeur. |
| 22 | `a17170` | Mémoire du processus, en Mo. L’équivalent Android doit provenir d’une mesure de l’hôte. |
| 23 | `a171bb` | Mémoire disponible via`42da30`, unité Mo, contrat actuel plafonné à4096. |
| 24 | `a171ca` | Langue issue de **byteSettings[17]**, signé modulo100, table`[1,1,6,5,4,2,3]` pour indices0..6. |
| 25 | `a171e6` | Tileset courant. |
| 26 | `a171f1` | Nombre d’acteurs map dans le vecteur natif, stride`378` ; ne pas utiliser seulement les acteurs visibles. |
| 27 | `a17215` | Type de pad, DirectInput0/XInput1 ; dépend du véritable périphérique de l’hôte. |
| 28 | `a17226` | ID de map-event **déclenché cette frame** par décision/contact,−1 sinon. Ce n’est pas l’historique du foreground. |
| 29 | `a17231` | Pointeur dans l’écran logique, limites inclusives dans ce PE. |
| 30 | `a172a7` | Événement sous le pointeur, incluant l’extension de contact ; priorité au plus petit ID. |
| 31 | `a177c1` | Zoom de carte en pourcentage. |
| 32/33 | `a177cc/d7` | Centre X/Y du zoom. |
| 34/35 | `a177e2/fc` | Temps de la frame précédente des événements/du rendu, en0,1ms : diviser la mesure ns par100000. |

La langue provient du binder`45e1a0` : `object+98+i*4=byteBuffer+i`. L’objet est`f511c8`, donc`f512a4` pointe vers byte17. Les codes retournés sont1 japonais,2 anglais,3 occidental,4 chinois simplifié,5 chinois traditionnel,6 coréen. Une valeur inconnue de byte17 n’est pas qualifiée ; le type d’encodage UTF8 n’identifie pas la langue.

Les champs d’événements sous le pointeur appliquent la caméra, l’inverse du zoom, la forme du hitbox et le bouclage. Le champ30 ajoute les extensions X/Y de contact. Les timings doivent lire une mesure de la **frame précédente**, pour que la commande en cours ne mesure pas son propre coût ni un compteur initial fictif. Une mesure réelle de0 peut être valide ; retourner0 faute de backend ne l’est pas.

Les positions et durées audio ne reprennent pas le sentinel −1 des ID de base de données. Pour BGM absent, `a16ab6/a16b89` conserve le registre résultat initialisé à 0 ; pour BGS absent, les chemins passent par `a16297` qui remet explicitement ce registre à 0. Le canal BGS est cherché avec la clé `DAT015059fc` (Sys99) dans la table `DAT01505540` : ce n’est pas nécessairement la voix 0. Général 27 peut exposer un profil Android adapté à DirectInput=0, mais ce choix doit être explicite ; il ne constitue pas une observation d’un périphérique Windows XInput.

### Zone sous le pointeur : champs 13 et 30

Le contrat observé utilise des rectangles **en pixels**, pas une distance absolue en demi-cases. Soit `T` la taille de tuile, `Z` le zoom en pourcentage, `C` son centre, et `p` le pointeur. Le point non zoomé est `trunc((p−C)/(Z/100)+C)`, puis reçoit la caméra tronquée et le modulo de la dimension de carte en pixels. La base d’acteur vient de `actor+3c/+40`, divisée par 4 avec troncature signée. Il faut conserver cette précision avant de calculer le delta.

Le test X est `−reachX ≤ deltaX < T+reachX`. Le test Y est `yMin−reachY ≤ deltaY < T+reachY`, avec `yMin=0` pour collision carrée et `T/2` sinon. Pour 13, les extensions sont nulles. Pour 30, `reachX/Y=features[1/2]×T/2`, ou 0 si le bloc de features n’existe pas. Les bornes supérieures sont exclusives. Le champ 13 teste quatre représentations autour du modulo ; 30 choisit les représentations supplémentaires en fonction des flags de boucle X/Y. Le filtre de profil ≥340 ignore `actor+ba` non nul. Le résultat est le plus petit ID des événements map du vecteur, ou −1 si aucune correspondance. Joueur et compagnons ne sont pas dans ce vecteur.

Le champ 26 compte tous les éléments de ce même vecteur map, `((end−begin)/0x378)`, y compris ceux marqués effacés s’ils n’en ont pas été retirés. Une collection réunissant joueur, compagnons et événements aurait un décompte incorrect.

## Picture : args[2]=ID, args[3]=champ

Le handler traite36/37 avant sa recherche de la picture. Pour les autres champs, une picture absente renvoie0 pour9,−1 sinon. Une picture marquée effacée renvoie−1 pour les autres champs dans les profils>339. Son opacité0 n’est pas synonyme de disparition.

| Champ | Contrat du getter98c140 |
|---|---|
| 0/1 | X/Y interpolés ; correction caméra si lié au scroll ; effets si bit40. |
| 2/3 | Taille intrinsèque ; chemin easy-window distinct, ne pas appliquer tous les effets de zoom au bitmap source. |
| 4 | Pattern courant ; animation d’effets uniquement si bit40. |
| 5 | Opacité interpolée avec correction d’effets optionnelle. |
| 6 | Angle en dixièmes de degré, correction optionnelle. |
| 7 | Échelle combinée ; privilégier Y si X=100, sinon X. |
| 8 | Hit-test du pointeur via99da70 ; utilise réellement la géométrie. |
| 9 | Utilisation/affichage, indépendant de l’opacité ; l’effacement programmé tient compte du compteur restant. |
| 10 | Horloge de révélation du texte, voir TEXT_PICTURE_TIMING_REA.md. |
| 11/12 | Échelle X/Y en pourcentage, correction optionnelle. |
| 13..20 | Coins du quadrilatère, intercalés X/Y. **Ce PE retourne−1 pour une picture non quad**, contrairement au texte actuel du manuel. Un renderer sans quad doit continuer à refuser ces champs. |
| 21/22 | Position/durée vidéo ;0 pour une picture présente non vidéo. |
| 23 | Z-order brut, offset58c. |
| 24 | Conversion d’ancre enum0..6 en`[0,11,2,20,22,10,12]`. |
| 25/26 | Divisions X/Y ; easy-window=1 pour profil>367. |
| 27/28/29 | Pourcentage RGB à partir de brightness signée, et des effets si bit40. |
| 30 | Blend :0 normal,1 additif,2 soustractif,3 multiplicatif. |
| 31 | Lien au scroll, inverse de NativePicture.screenRelative. |
| 32 | Durée totale configurée, stockée à20c ; **conservée après la fin du traitement**. |
| 33 | Compteur restant brut, offset208 ;0 puis−1 après traitement ordinaire. |
| 34 | Minimum des délais strictement positifs, sentinelle initiale2milliards ; sentinelle finale→−1. |
| 35 | Maximum des délais positifs,−1 si aucun. |
| 36/37 | ID immédiatement précédent/suivant, tri parID ; aucun voisin→ID demandé, même si celui-ci est absent. |

À`98e525`, la conversion de brightness utilise`CVTSI2SS`, `DIVSS` par le float exact`40233333` (2,55f), promotion en double, additions0,5 puis100, puis troncature. Preuves des constantes : `cd8998=33332340`, `ev_6b7bac95e4ea9ea4582bbddf44afc680683a7dbc5e5da0aa5856ea942208e3f9` ; `cd8830=000000000000e03f`, `ev_129f5fd2bc02dfcc226c187fd6e36f232b8f2a59aa6ec68798e3281100c9d7f2` ; `cd8c48=0000000000005940`, `ev_c01cf47de900c3fd56cd7ec0363fcc58680722040dbf85874ffb5ac3b684d359`. Ne pas lire les octets ARGB d’un tint comme s’ils étaient les pourcentages de la commande150.

Avec effets, `98e7d7..98e831` ajoute la brightness interpolée en double aux corrections d’effets entières, puis tronque **leur somme** à `98e826`. Le helper `colorPercentage` reçoit cet entier final. Tronquer séparément la base change le résultat quand la somme traverse zéro : base −0,9 et correction +1 produisent 0, pas 1. Un multiplicateur RGB flottant reconstruit avec `(color−1)×255` a moins de précision que les entiers source/cible du PE ; le contrat exact conserve ces entiers et l’interpolation en double.

Les setters écrivent208 et20c depuis la même durée à`98f03d/98f043` et`9886cd`. Le tick98f1a0 décrémente uniquement208 par le pas logique et limite à−1 hors erase. Un modèle limité à l’existence d’un tween perd32, et transforme33 en0 permanent après completion. Il faut conserver le total et le compteur dans la picture et sa sauvegarde. Les délais viennent des opérations réellement en attente, pas d’un délai arbitraire.

## Position et entrées complémentaires

La table de position est compressée :26 cibles à`a17b70`, remap des numéros0..162 à`a17bd8`. Les numéros de champ sont les bits16..23, et non args[3] qui reste Y. Les familles établies sont :0 tag,1 map-event ID,2/3 passage tile/semi-tile et événement,4 chip supérieur,5..7 chips des couches1..3,8..10 tags des couches1..3,11..16 autotile,17..19 attributs couches1..3,20 masque des passages cardinaux,21 counter,22/23 picture sous un point,24/25 chips couches4/5,26/27 tags couches4/5,28/29 attributs couches4/5,150/151 map-event sous un point écran normal/étendu,152/153 conversion écran vers cases,154/155 conversion vers demi-cases,156..158 RGB du pixel,161/162 conversion vers pixels de carte. La cible du champ30 construit un vecteur de chips et des affectations multiples ; son contrat public reste non qualifié. Les numéros31..149 utilisent la cible par défaut. Les alias159/160 suivent la branche bleue dans le PE mais ne doivent pas être déclarés publiquement sans qualification.

Les champs11..16 d’autotile et30 demandent une reconstruction supplémentaire de9ca940 et du chemin d’affectation. Les lectures RGB de156..158 nécessitent la lecture du pixel réellement rendu. Les lectures de collision et des champs150/151 doivent employer les acteurs valides, l’extension de contact et les règles de wrap exactes : pas de0 substitutif.

### Commande 123 : souris physique

La catégorie souris est `(flags & 0xf00)==0x300`. Le sous-mode est `flags & 15`, le mode d’edge/durée `(flags >> 16) & 15`. Les boutons sélectionnés sont `0x10` gauche, `0x20` milieu, `0x40` droite.

| Sous-mode | Valeur observée |
|---|---|
| 0, modes 0/1 | État maintenu ; priorité gauche, puis milieu, puis droite. |
| 0, mode 2 | Nouvel appui : état courant non nul, état précédent nul. |
| 0, mode 3 | Relâchement : état courant nul, état précédent non nul. |
| 0, mode 4 | Maximum des compteurs d’appui des boutons sélectionnés, 0 si aucun. |
| 1 | Coordonnée X, `DAT01503ca8`. |
| 2 | Coordonnée Y, `DAT01503cac`. |
| 3 | Delta de molette de la frame, `DAT01503cc8`. |

Pour les modes 0..3 du sous-mode 0, les valeurs de bouton sont **20/21/22**, et non 1/2/3 ; 0 signifie aucune correspondance. Les globals courants sont `1503c9f/1503ca0/1503ca1`, les précédents `1504330/1504331/1504332`, les nouveaux appuis `1503ca2/1503ca3/1503ca4`, les compteurs `150475c/1504760/1504764`. Ce chemin ne mélange pas la souris avec les boutons logiques de validation/retour.

`FUN009b4c90`, instructions `9b6c37..9b6c76`, incrémente chaque compteur de **1 par mise à jour d’entrée**, et le remet à 0 quand le bouton est relâché. Le pas moteur `f4ead0` divise séparément les délais de répétition ; il ne multiplie pas ces compteurs. Les compteurs démarrent à 1 lors du premier update qui voit l’appui, puis 2, etc.

Le bit `0x80` du handler `a14280` démarre l’attente. Pour une attente de boutons, le reader utilise toujours les nouveaux appuis `1503ca2/3/4`, indépendamment du mode 0..4 demandé pour une lecture instantanée. Il termine l’attente quand le résultat est non nul, écrit la destination et libère l’invocation. Pour X/Y/molette, le sous-mode continue à fournir sa valeur ; une valeur 0 maintient donc l’attente. Le filtre physique `0x1000` ne doit pas être transféré automatiquement à la souris : son champ préparé dans l’invocation n’est pas consulté par ce bloc.

Les autres catégories observées sont `0x100` clavier, `0x200` boutons de pad, `0x400` axes analogiques, `0x500` POV et `0x600` tactile natif. Elles demandent des données physiques et leurs propres contrats ; décoder leur nibble ne suffit pas à les porter. Les exports de la relecture du poll sont `build/wolf-research/beta19-input-update-rea.json` et `beta19-input-update.asm.txt`.

Routes : le dispatcher autonome/route9fbf40 est couvert par`ev_9e8d1b4f26b64ad1f22dbffb0f08ee34fdccc2d8fa0ce8084c0cbb0053c1b1b6`. Les modes de décision2/3 utilisent9fbd50/9fbd90, déjà reconstruits dans WolfAutonomousMotion. Le diagnostic « Mode autonome de page » peut aussi venir de la longueur du bloc movement ou de ses valeurs de vitesse/animation ; sans bytes de page, il ne prouve pas159 modes autonomes nouveaux. Les modes non établis restent refusés et les prochains diagnostics doivent inclure les operands.

## Validation et limites

WolfStateReadSemantics et ses tests isolent les contrats mathématiques ci-dessus : flags réservés, anchors, recherche de voisins absents, sentinelle2milliards, troncature/overflow int32 du zoom, compteur de durée, langue et conversion float des couleurs. Ces fixtures sont des preuves locales de reconstruction ; ce ne sont pas des replays du jeu Seris, une exécution comparative de Windows, ni une validation de toutes les variantes Wolf2.

Le [manuel officiel de la commande](https://silversecond.com/WolfRPGEditor/Help/04ev_valuenext.html) corrobore le rôle des familles et les unités d’état. Les détails de sérialisation et les cas où le PE diffère du texte actuel sont établis par les instructions et tables citées ici. Toute admission nouvelle doit associer ce contrat à un état réellement fourni par le runtime/hôte. Winlator demeure le secours pour un contrat absent.
