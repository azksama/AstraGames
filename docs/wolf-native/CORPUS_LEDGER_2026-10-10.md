# Inventaire sémantique indépendant des corpus Wolf

## Mesure et portée

Cet inventaire parcourt toutes les commandes, les pages et les routes encodées des deux jeux officiels locaux et du corpus tiers décrit plus bas. Il ne déduit pas la couverture du seul premier événement lancé. Les comptes proviennent des données parsées ; les fonctions Windows ci-dessous ont été inspectées statiquement avec les outils publics REA, sans modifier les binaires. Une commande admise par la VM n'est pas une preuve de jouabilité, ni de cadence Android.

| Corpus | Communes | Cartes | Pages | Commandes | Opcodes d'événement | Opcodes de route |
|---|---:|---:|---:|---:|---:|---:|
| Wolf 2.29.6.1 | 225 | 4 | 33 | 26 137 | 48 | 5 |
| Wolf 3.72.9 | 225 | 4 | 39 | 27 586 | 46 | 8 |
| The Crooked Man v4 | 315 | 125 | 1 683 | 43 130 | 58 | 34 |

Les artefacts détaillés sont conservés par checkpoint dans `build/wolf-research/coverage`. Chaque copie contient les sources Kotlin immuables, leur manifeste SHA-256, le JAR compilé et les résultats. Les miroirs à la racine de ce dossier ne remplacent pas ces copies. L'audit initial comptait 7 refus W2 et 109 W3. Les checkpoints du 10 octobre 2026 à 22:08, 22:37 et 23:27 sont historiques : le dernier constatait 0 refus W2, 14 W3 et 5 tiers dans le seul contrôleur de commandes. JAR `checkpoint-20261010-232700/inventory.jar`, SHA-256 `aa242d32371b24b333534d68f2af2a1c08b0517c18c0cff85ee14a337bdd7027`.

Le checkpoint du 11 octobre à 00:48:28 examine **96 853 commandes** et constate **0 refus de `WolfRuntime.capabilityProblem`** sur chacun des trois corpus. JAR `checkpoint-20261011-004828/inventory.jar`, SHA-256 `9fb5c308516dd492dec9565a2d50b01722f3b6e94ef4a69df3509822e78c2dad`. Le [résumé structuré](CORPUS_CHECKPOINT_2026-10-11.json) lie les comptes aux sources et aux runners exacts. Le compteur d'opcodes ne vérifie pas tous les namespaces, les conditions de page, les données introduites par CSV ou les variantes de flags qui n'ont pas de garde précise.

Un second runner inspecte donc **chaque carte indépendamment avec `WolfCapabilityInspector`**, pour qu'un refus sur une carte ne masque pas les suivantes. Sur le même JAR, il relève **96 diagnostics W2, 99 W3 et 132 tiers**, dans `checkpoint-20261011-004828/full-admission/*-admission-audit.json`. Certains proviennent de gardes lexicales périmées : références imbriquées dans les styles, champs112 empaquetés, cibles210 dynamiques et paramètres de déplacement maintenant portés. D'autres correspondent à des branches réellement absentes, notamment les noms de touches SysS1..6 et le fog Sys61/65. Ces résultats ne doivent pas être assimilés à un « moteur complètement admis ». Le parcours réel de sauvegarde du tiers a également révélé le flag18 de comparaison111, accepté par le contrôle d'opcode mais mal interprété à l'exécution. Le prochain checkpoint après qualification et correction de ces variantes doit rester distinct de cet instantané.

Les 29 refus W3 du checkpoint 22:08 concernaient 24 effets290 (déplacement pixel de personnage, effets d'image avancés et masques), quatre macros122 (deux recherches FIND_PATH4/8 et deux synthèses vocales MS_VOICE_PLAY) et une téléportation130 `[-1,15,1,1,16,0]` sur la carte2/événement2. Ils ne sont plus refusés par le contrôle d'opcode dans l'instantané 00:48:28. Les fichiers historiques conservent emplacement, offset et arguments exacts ; leur admission positive ne remplace pas les tests sémantiques des variantes.

**Checkpoint final du 11 octobre à01:43:28** : les **96 853 commandes** restent sans refus de `capabilityProblem`, et les deux mesures d'admission complète ne constatent plus de diagnostic sur ces corpus. L'appel public exact `WolfRuntime.load(source).inspectCapabilities(includeAllMaps=true)` donne `supported=true`, `inspectedAllMaps=true`, `issues=0`, avec4/4 cartes W2,4/4 W3 et125/125 du tiers. Le runner indépendant inspecte aussi les133 cartes séparément et confirme0 raison distincte. JAR source immuable `checkpoint-20261011-014328/inventory.jar`, SHA-256 `c5e95c894f51655949175d31992bded1ea73b61118c6289b649926f5490421c1` ; aucun fichier de production n'a changé depuis le manifeste au moment de cette mesure.

La même admission publique et exhaustive a été rejouée sur une copie immuable du **JAR Gradle réellement construit**, `gradle-core.jar`, SHA-256 `f5f6c7497c772ee572ddef2186dc15b149e6f67c0ef30a17d0d47dc8a0c26821`, avec les mêmes résultats. Les rapports et leurs hashes se trouvent dans `full-admission` et `gradle-public-admission`, liés au [résumé public](CORPUS_CHECKPOINT_2026-10-11.json). L'appel public est autorisé à s'arrêter au premier refus : `includeAllMaps=true` seul ne garantit pas l'énumération complète ; ici `inspectedAllMaps=true` est également vérifié. Les nombres96/99/132 ci-dessus restent l'historique00:48:28, pas les résultats finaux.

Une admission statique positive de ces133 cartes n'établit ni une partie complète, ni le rendu de tous les textes introduits par variables/CSV, ni les variantes Pro absentes de ces données. Les parcours réels de menus, sauvegarde/restart/chargement et les tests SDK se consignent séparément. Les sauvegardes Windows `.sav`, le replay220/action2, les clés WOLFX personnalisées et les cartes/textes individuels WOLFX restent des frontières explicites. La lecture des alias protégés et leur existence sous un nom clair par `GET_FILE_EXIST` sont deux contrats distincts ; la seconde règle reste non qualifiée, comme précisé dans [WOLFX_RESOURCES.md](WOLFX_RESOURCES.md).

Binaires de référence :

- W2 `Game.exe` SHA-256 `795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0`.
- W3 `Game.exe` SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`.

## Toutes les commandes rencontrées

Les commentaires et marqueurs structurels restent comptés. Zéro signifie absent du corpus, sans préjuger du support du moteur.

| Code | Contrat examiné | W2 | W3 |
|---:|---|---:|---:|
| 0 | Instruction vide | 3340 | 3499 |
| 99 | Point de contrôle | 49 | 49 |
| 101 | Message et codes de texte | 172 | 254 |
| 102 | Choix et branche résultante | 11 | 16 |
| 103 | Commentaire | 5640 | 6014 |
| 106 | Texte de diagnostic | 46 | 44 |
| 107 | Arrêt de diagnostic | 1 | 1 |
| 111 | Branches numériques, masques et indirects | 1746 | 1804 |
| 112 | Branches de chaînes | 32 | 34 |
| 121 | Calcul numérique, namespaces et indirects | 3994 | 4191 |
| 122 | Chaînes, conversion, saisie et codes spéciaux | 287 | 309 |
| 123 | Lecture des entrées, clavier/souris/joystick | 55 | 59 |
| 124 | Lecture de l'état de carte/personnage/image | 32 | 54 |
| 126 | Masque de condition | 4 | 6 |
| 130 | Transfert, repositionnement, échange de personnages | 5 | 5 |
| 140 | Audio : play/stop/fade/volume | 21 | 30 |
| 150 | Création/modification/effacement d'image | 756 | 808 |
| 151 | Teinte et flash d'écran | 10 | 10 |
| 160 | Transition préparée | 1 | 1 |
| 161 | Capture | 1 | 0 |
| 162 | Présentation de capture | 1 | 0 |
| 170 | Début de boucle | 37 | 38 |
| 171 | Sortie de boucle | 67 | 70 |
| 172 | Retour d'événement | 296 | 305 |
| 174 | Retour au titre | 1 | 1 |
| 175 | Quitter | 1 | 1 |
| 176 | Boucle | 50 | 50 |
| 179 | Boucle comptée | 412 | 440 |
| 180 | Attente logique | 92 | 126 |
| 201 | Affectation de route | 3 | 4 |
| 202 | Attente de fin de route | 1 | 1 |
| 210 | Appel d'événement commun | 55 | 58 |
| 211 | Appel différé | 4 | 4 |
| 212 | Définition de label | 66 | 68 |
| 213 | Saut vers label | 131 | 134 |
| 220 | Sauvegarde/chargement | 2 | 2 |
| 221 | Lecture d'une variable dans une sauvegarde | 8 | 9 |
| 222 | Écriture d'une variable dans une sauvegarde | 1 | 2 |
| 250 | Accès DB numérique/texte | 2506 | 2544 |
| 270 | Gestion du groupe | 2 | 4 |
| 280 | Secousse d'écran | 1 | 1 |
| 290 | Effets d'image/personnage/carte | 179 | 283 |
| 300 | Appel commun nommé et arguments | 1147 | 1164 |
| 401 | Marqueur de branche | 2481 | 2595 |
| 420 | Branche alternative | 147 | 153 |
| 421 | Branche alternative | 5 | 9 |
| 498 | Fin de boucle | 449 | 478 |
| 499 | Fin de branche | 1789 | 1854 |

Les codes 104, 105, 125, 141, 240, 241, 242, 251, 260 et 281 ne sont pas rencontrés ici. Leur absence ne permet pas de les ignorer dans la compatibilité générale.

## Routes, pages et références

| Route | Sens | W2 | W3 |
|---:|---|---:|---:|
| 9 | Orientation gauche | 0 | 1 |
| 21 | Saut | 2 | 2 |
| 22 | Rotation horaire | 8 | 8 |
| 26 | Vers le héros | 3 | 4 |
| 29 | Vitesse | 1 | 1 |
| 47 | Attente | 8 | 10 |
| 60 | Navigation automatique | 0 | 2 |
| 160 | Navigation intelligente | 0 | 1 |

Les en-têtes de route forcée font cinq octets. Les routes autonomes de pages de ces corpus ont des tableaux de commandes vides ; leurs paramètres de mouvement restent significatifs. Les déclencheurs rencontrés sont action (24/30 pages), autorun (1/1) et contact du joueur (8/8). Opacité de page : 255 ; blend : 0. Les flags, conditions de page, conditions des communs et variables des paramètres doivent être audités en plus des opcodes.

Les références rencontrées couvrent variables normales et réserves, self de carte, self de commun, variables système, chaînes normales/système/self, DB utilisateur/mutable/système et références indirectes. Les flags d'une commande ne sont pas des références de variable : les confondre créait de faux namespaces inconnus lors de l'ancien inventaire. Le rôle de chaque argument est maintenant pris en compte.

Le wire des tuiles réserve le slot transparent : autotile N lit le slot N−1 ; tuile normale N lit le slot N+nombre d'autotiles+1. Le portage de cette propriété ne peut pas être validé avec une fixture qui reproduit l'ancienne erreur d'index.

Les [preuves cartes, animation et audio](MAP_ANIMATION_REA.md) détaillent la transposition couche/X/Y vers le contrat portable row-major, le binding SysDB0/champ3 des boucles, la configuration de cycle W3 byte34, les motifs de repos/base lors d'un changement de page et l'audio des transferts de carte. Les 24 tests de formats passent avec les corpus officiels activés ; cela reste une preuve de lecture portable, distincte des tests de parcours et de pixels sur Android.

Le dernier run Formats passe27/27, dont la garde d'alias `.mps.wolfx` qui refuse avant lecture même si la carte claire existe. Le [contrat des fonds et brouillards](MAP_TEXTURES_REA.md) ajoute les bindings Sys61..68/SysS30..31, LF/CR, les divisions, les phases signées W2, les quatre rectangles et les différences MUL4/MULA11. Huit tests comparent740 phases/géométries C++ indépendantes. La qualification d'alias WOLFX est distinguée du lecteur des médias dans [WOLFX_RESOURCES.md](WOLFX_RESOURCES.md).

## Rejets qui bloquaient des parcours réels

L'instantané avant intégration localise chaque occurrence, arguments et offset dans `*-rejections.ndjson` :

- W2 : quatre modes 140, deux effets de personnage 290 (flash/secousse de l'événement 14 de la carte 1), une secousse 280.
- W3 : 96 effets 290, six macros 122 du commun 40 de déplacement au clic, quatre modes 140, une lecture souris 123, une secousse 280, une opération de transfert 130.

Les variations 122 de conversion de nombre, concaténation, remplacement et retrait de sous-chaîne, ainsi que 123 clavier/mouse et lectures système, avaient auparavant interrompu menus/combats. Elles ne doivent pas être confondues avec les seules variantes de base acceptées. Les menus, saisie, sauvegarde et combat nécessitent de vérifier les namespaces utilisés par les arguments, pas uniquement de cocher l'opcode.

## Effets portés et preuve différentielle

`PictureEffects.kt` transpose les opérations 290 catégorie image 0..10 : flash, couleur, translation, secousse, zoom autour d'un point, deux clignotements, oscillation de taille avec pause et trois parcours des motifs. **L'opération 2 est une translation**, pas un changement de taille. Un show150 sur un ID existant conserve les effets ; l'effacement les supprime. Référence W2 : setter `004c1dd0`, updater `004ce890`, draw `004920a0` ; source et 3 152 phases C++/x87 dans `wolf-native/src/test/resources/picture-effects`. Huit tests JVM comparent tous les mots et le rendu dérivé, puis restaurent chaque phase. Ils comprennent 648 phases de secousses longues W3 : le tick `0098f1a0` alterne la parité au-delà de 99999 répétitions, sans épuiser le compte. Les points de composition pour les extensions W3 sont explicitement placés après flash0, puis après l'onde7 et avant zoom4/translation2 ; ils fonctionnent également en l'absence d'état d'effet de base.

`CharacterEffects.kt` transpose les opérations catégorie personnage 0..3 : flash, secousse, clignotement triangulaire et en dents de scie. La couleur personnage est directement clampée à −100..100 ; elle n'utilise pas la division par deux des images. Les rapports sont quantifiés en millièmes avant la conversion x87. La secousse calcule son décalage avant de décrémenter la phase, et conserve un dernier décalage jusqu'au tick suivant. Le décalage bouge aussi le point d'ombre ; la teinte ne colore pas l'ombre.

Référence W2 : setters `004c3020/004c3060/004c30f0`, updater `004475b0`, draw `004c6e80`. W3 updater `009ea7f0`, preuve `ev_416d0b61364637b266577082a52a35ffcd7f6415e6ae7d87921737294808e295`, et draw personnage `009c4a70`, preuve `ev_73dd64e690a3bee58f890b7ba06a216f4d3a7aed94132c60253e58dd16a1c834`. Les deux profils ont un état portable de 14 mots. En W3, un nombre de secousses supérieur à 99999 alterne indéfiniment la parité ; W2 le décrémente. Quatre tests JVM passent : 2 958 phases W2 et 2 982 phases W3 comparant état, décalage et couleur dérivée. Les équations couleur W3 sont confirmées par son propre draw, qui tronque le pourcentage interpolé avant multiplication par 2,55.

Les snapshots d'effets valident tous leurs états avant remplacement : comptes, longueurs, durées, périodes, couleurs et divisions. Une restauration invalide conserve l'état précédent. Les longues répétitions 999999 utilisées par le menu officiel sont admises.

W3 personnage op8 écrit la valeur brute clampée0..1 dans le champ `0x148` : l'updater l'utilise pour maintenir l'animation à l'arrêt. Ce n'est pas un flag de collision. L'op12 écrit le verrou de direction à `0xaa` ; le route updater `009fd7a0` le consulte avant de changer la direction. L'op7 appelle `009eeec0` pour un déplacement en pixels avec repositionnement logique et collisions ; une translation purement visuelle serait incorrecte.

## Sauvegardes, fichiers, système et vidéo

Ces contrats ont été retrouvés même lorsque l'opcode n'est pas exercé par le corpus. Ils distinguent les futures implémentations de simples no-op :

| Surface | Contrat Windows établi | Conséquence pour Android |
|---|---|---|
| 220, `00465720` | Slot numérique >0 : `Save/SaveData%.2i.sav` ; slot0 : clé texte. Action0 sauvegarde, action1 chargement complet et fin des événements. Action2 charge le replay des huit touches et du RNG (`004b3fd0(slot,0,1)`), pas les variables de jeu. | Le codec privé `AWNS` ne prouve pas une lecture des fichiers `.sav` Windows. Action2 reste refusée tant que l'objet replay n'est pas porté ; aucune restauration de variables ou chargement complet ne remplace ce contrat. |
| 221, `00465a30` | Le type de destination choisi par `0043c9b0` sélectionne le lecteur numérique/texte. | Ne pas choisir le lecteur d'après le namespace de la variable stockée. |
| 222 | Le moteur conserve un backup, charge/modifie/réécrit la sauvegarde puis restaure sa session. | Écriture atomique et preservation des valeurs étrangères ; les namespaces acceptés doivent correspondre au codec disponible. |
| 122 mode0x500 | Lecture de fichier et codes `GET_FILE_EXIST`/`GET_SOUNDTIME`. | Une absence, un refus d'accès et un codec audio manquant ne doivent pas tous devenir zéro. |
| 122 mode0x600 | Écriture avec `fopen("w")`/`fwrite`. | Chemins confinés au jeu ; écriture atomique compatible SAF/local. |
| 122 mode0x700 | Énumération `FindFirst("*")`, éléments joints par des sauts de ligne, hors `.`/`..`, restauration du répertoire courant. | Aucun changement de cwd global Android ; définir l'ordre et les noms rendus. |
| 122 modes0x800..B00 | Retrait/remplacement de toutes les occurrences ; partie avant/après le premier séparateur. | Préserver la sémantique des séparateurs absents et des chaînes vides. |
| 260, `0046bd10` / `00a2a300` | Téléchargement HTTP vers fichier et/ou variable texte ; cible−1 W2 particulière ouvre l'URL via ShellExecuteA seulement dans la plage système15000..15499. | Helper portable validé, contrat détaillé dans [DOWNLOAD_REA.md](DOWNLOAD_REA.md) ; le réseau Android et les aliases Pro restent distincts de cette preuve. |
| 240/241/242 | Propriété de passage, tileset, rectangle et remodelage des autotiles. | Ce ne sont pas des opérations fichier. |
| 290 image18..20 | Contrôle vidéo DxLib (`0052d020/0052cf90/0052d0b0/0052d280`). | Un bitmap ou une texture noire ne constitue pas un lecteur vidéo. Décodage, contrôle de lecture et audio restent à prouver. |

Sources de travail pour ces contrats : `build/wolf-rea/wolf2-native-render-followup/formatcoverage-{01..26}-*.c.txt`. Les identifiants de preuve complets restent dans les réponses JSON correspondantes.

Correction du contrat220/action2 : `004b3fd0` désérialise le fichier dans un objet temporaire, puis copie uniquement ses0x34 octets à+0x2710 vers `00aa8f40`, active `aa370c=1` et remet `aa370d=0`. `00478e30` encode les huit touches dans un octet et accumule les répétitions ; `00478f50` relit ces séquences et vérifie le RNG. `00417f90` est le générateur Park–Miller (16807/mod2147483647), dont mode2 fixe le seed. Les vecteurs de cet objet ne sont donc ni les variables normales, ni Self/CommonSelf, ni les DB mutables. Preuves : `fmt-save220-object-{write,read,seed}-w2.c.txt`, respectivement `ev_17f3a272018d6159d38f951cfac0a8a12643cc0efefa29048d3b54135e8a80af`, `ev_7ea8a7d5fc4bceaa1b26e05da8066d08b1ece785de0799c4eb692ffcfd75ce17`, `ev_b6c3f9ed40de28340d187341ae98c5a4104d105a9a4ba4e8b39e379812d07583`. L'ancienne description « chargement des seules variables » était erronée et ne sert plus à l'admission.

Pour221, le type de destination sélectionne aussi les getters de cellules DB. Une cellule texte lue par `004772c0` numérique renvoie0 ; une cellule numérique lue par `00477a40` texte renvoie la chaîne vide. Aucun `atoi` ni formatage décimal n'est établi dans ces branches. Pour les globales de type opposé, les lecteurs renvoient−1/`<<ERROR>>`. Sources `fmt-save221-db-{number,string}-w2.c.txt` et `fmt-save221-numeric-query-w2.c.txt` ; preuves `ev_56ea735f97f4d039817b0d5e40b70112955d00ca20f35ac31b854c83544e9fa1`, `ev_3203df6cc7ad195bd8f255a9400564da5d113bcc7a1c49c501ee9f85aa75c19a`.

## Codes spéciaux W3 et limites

Le [contrat détaillé des macros](SPECIAL_CODES_REA.md) couvre noms de communs, construction de grille, passage des tuiles, occupation des personnages, options de recherche et sérialisation. Trois transcriptions C++ indépendantes produisent 96 vecteurs de sortie, 300 grilles et 206 occupations ; les TSV restent reproductibles à partir des sources, sans appeler le code Kotlin de production.

Les six appels atteignables de déplacement au clic utilisent `122 flags0x40200`, commun40, dont `<<FIND_PATH_DIR8/v2/SX=.../SY=.../EX=.../EY=.../LEN=1/EXE=20000>>`. Le handler W3 `00a0fe10` appelle `00a2e2d0` pour les macros valides ; REA `ev_48b6d9b9bd8ecfe495fc6fc487abeb489eab79ef99fb228ae020e3b3d8fc0596`. Les contrats détaillés des options doivent être confrontés à ce helper avant d'admettre les options non exercées.

Le [manuel officiel des codes spéciaux](https://silversecond.com/WolfRPGEditor/Help/04ev_string_specialcode.html) décrit les sorties directionnelles ou par coordonnées, la limitation de sortie distincte de l'exploration, les contrôles d'acteurs et les résultats d'échec. L'historique officiel local documente `/v2` : une destination bloquée par les tuiles peut être atteinte dans la recherche. `GET_COMMONEVENT_ID_FROM_NAME` renvoie une chaîne décimale, et choisit l'ID le plus grand en cas de noms identiques. Les coûts TIME, la limite EXE, les sorties XY/XYN et les options non documentées restent des contrats distincts ; ils ne doivent pas être supprimés silencieusement.

Cet inventaire ne contient pas tous les jeux, toutes les commandes possibles, les extensions Pro ou les profils historiques. Les preuves de menus/save/load, Android SDK, pixels et performances réelles doivent être consignées dans les résultats d'intégration, séparément des comptes statiques ci-dessus.

## Corpus tiers : The Crooked Man v4

La [page du traducteur autorisé](https://vgperson.com/games/crookedman.htm) distribue la version gratuite WOLF du jeu d'Uri. Il s'agit d'un jeu freeware, pas d'un corpus sous licence open source. L'archive, les ressources et l'exécutable restent dans `build/wolf-research/third-crookedman-v4`, ignoré par Git ; aucun asset du jeu n'est ajouté aux tests distribués ni aux releases. Le ZIP SHA-256 est `dab0a00de0e2b01ef1a304d924902d27cf16ee07404e5c5624dc1a38e6d06547`, et le `GamePro.exe` SHA-256 est `cf07ae76af74b36d8c2d107b8fe968c93bbb7cf439ed07b04a1fed9275ee3259`. Cet exécutable n'a pas été exécuté. Sa session d'analyse publique REA est distincte des deux sessions officielles, dans `build/wolf-rea/crooked369-formats`.

Le lecteur portable ouvre `Data.wolf` directement en DX8 / crypt350 et décode les seules ressources demandées. La première sonde identifie deux variantes absentes des corpus précédents : certaines métadonnées portent le préfixe ASCII exact `Extracting data from encrypted files violates the guidelines.` suivi de NUL, soit 62 octets ; `TileSetData.dat` déclare la version211 (D3). Les images PNG, les fichiers `.project`, le SysDatabase et les tilesets examinés ne portent pas ce préfixe.

`MetadataCrypt` reconnaît maintenant uniquement ce préfixe exact devant le magic clair du type demandé. Il ne recherche pas une signature à un offset arbitraire et ne traite pas les PNG, `.project` ou archives comme des métadonnées. La copie du corps est comptabilisée dans le budget et laisse l'entrée intacte ; le champ de taille de Game.dat est validé contre le corps clair. Le parser admet D3 uniquement avec les 31 chaînes et les deux blocs de propriétés déjà retrouvés jusqu'au footer ; D4 reste refusé. Deux nouveaux tests multi-encodage vérifient les quatre types, la préservation des chaînes/propriétés, les quotas, les headers incorrects, les enveloppes imbriquées/chiffrées et chaque troncature de D3. La suite Formats+MetadataCrypt passe **35/35** avec les corpus officiels activés.

La lecture intégrale portable du jeu tiers est positive : Game.dat profil369 ; 315 communs, 125 cartes, 1 683 pages, 43 130 commandes, 58 opcodes d'événement et34 de route. Les six tilesets conservent toutes leurs propriétés. Le run lit 134 ressources demandées, 828 402 octets positionnels du Data.wolf de 37 846 705 octets ; son temps local JVM est342ms, ce qui ne mesure pas un lancement ou des FPS Android. JAR compilé examiné SHA-256 `ae1a9e457da91f9b1dda272dc6405b5778ab5f599a992ae1f3c5925f6476312c`. Rapport : `third-crookedman-v4/coverage/{summary.json,rejections.ndjson}` ; les routes des commandes refusées sont conservées en entier.

Le premier contrôle statique de ce tiers constatait **678 refus**, conservés ici comme historique des variantes découvertes. Au checkpoint 23:27 il en restait 5 (quatre104 et une route201/header2=3), indépendamment des14 lectures124 W3 précisées plus haut. Au checkpoint 00:48:28 le contrôle de commandes n'en trouve plus ; l'inspection complète relève encore 132 diagnostics distincts. Le tableau suivant décrit les variantes du premier inventaire, pas une liste de refus actuelle ni des résultats de jouabilité Android :

| Surface | Occurrences initiales | Variante concrète découverte |
|---|---:|---|
| 242 | 560 | Remplacement de rectangles de tuiles, dont commun256 `[3,1600016,1600017,1,1,1600014]`. |
| 201 | 74 | Routes flags2, avec commandes ordinaires et header, dont commun245 `[3,3,3,0,3]`. |
| 240 | 13 | Propriété de passage, dont carte16/événement20 `[451,15]`. |
| 140 | 13 | Encodages négatifs de réglage audio `-224/-240/-256`, volume0/100. |
| 123 | 4 | Flags65752 (`0x100d8`), commun213. |
| 104 | 4 | Contrôle de l'interface message sans arguments, commun224. |
| 251 | 4 | Lecture CSV d'une DB, commun312 `[32,0,0,512,1600041]`. |
| 290 | 2 | Flash image opération0 RGB50, durée30, argument7 de délai0, commun312. |
| 150 | 2 | Flag `0x02000000` sur images écran320×240. |
| 4 / 1001 | 1 chacun | Codes historiques dans les communs196/213 ; contrat à retrouver avant admission. |

Le littéral natif du préfixe est aussi présent dans le PE369 : `00c2cde8`, REA `ev_d9371031c6357aaba77daa69d5698c3dc5d935d06f16148803b1d5398b6b006d`. Les xrefs directes ne donnent aucun caller, et les loaders sont obfusqués ; une équivalence native généralisée n'est donc pas établie. Le port est fondé sur les corps filaires complets effectivement lus, pas sur une supposition de compatibilité de toutes les versions Pro. La session369 a été fermée proprement par `close_binary`. Cette qualification filaire ne constitue pas à elle seule un parcours Android ; les résultats d'exécution restent consignés séparément.

Le dump privé `coverage/structure.json` donne un parcours vérifiable sans modifier les variables : carte0/event0 Title→commun213→sélection de langue312. English est la deuxième entrée, vers le bas depuis le Japonais initial, et demande `Aozora Mincho Regular`. Après confirmation et acquittement, le titre sélectionne Load à gauche ; un pas vers la droite choisit New au centre. L'introduction transfère ensuite vers carte1(12,5), event3 Opening. Les CSV anglais sont `Data/TCM_Other texts_EN.csv` et `Data/TCM_Dialogue_EN.csv`. Les langues Français/Polski/Русский demandent `Spectral ExtraLight`.

Les cinq polices individuelles de la racine portent `WOLFX`, pas une archive DX. Le [lecteur individuel WOLFX](WOLFX_RESOURCES.md) les déchiffre avec la clé vide et aucun lookup de variable : les cinq payloads TTF sont identiques octet par octet à la référence upstream épinglée. Six tests JVM et192 vecteurs C++ couvrent les clés explicites, les références de variables, les limites de blocs et les refus ; ces fichiers de test ne contiennent aucun asset du tiers.

## Variantes de texte et traductions importées

L'inventaire des échappements est séparé du contrôle d'opcodes. Il examine les sources brutes des commandes101/102/122/150, les noms et cellules des trois DB, puis les 24 CSV UTF8 présents dans `Data.wolf` pour le tiers. Aucune valeur de variable n'est inventée pour rendre un template dynamique artificiellement compilable ; Sys150=1 n'est pas déduit de l'ordre statique des commandes.

| Sources examinées | W2 (profil225) | W3 (profil372) | Tiers (profil369) |
|---|---:|---:|---:|
| Templates de pictures texte | 86 | 94 | 154 |
| Templates littéraux compilés | 2 | 9 | 0 |
| Templates dynamiques, sans expansion fictive | 84 | 85 | 154 |
| Refus sur templates littéraux | 0 | 0 | 0 |
| Dialogues sources | 172 | 254 | 59 |
| Chaînes de choix sources | 31 | 44 | 53 |
| Cellules DB texte | 2 701 | 2 710 | 5 704 |
| Noms de lignes DB | 1 453 | 1 522 | 4 747 |
| Ressources CSV lues | 0 | 0 | 24 |

Les templates de pictures officiels utilisent notamment `\f`, `\c`, `\font`, `\space`, `\ax`, `\ay`, `\A-`, `\E` et des références `\cself`. Aucun `\!`, `\r` ou `\se` n'y est trouvé directement. Les dialogues officiels comportent en revanche 11 occurrences ruby chacun, ainsi qu'une attente `\!` W2 et trois W3 ; le protocole de dialogue et le rendu ruby se vérifient séparément du helper de progression des pictures.

Le tiers contient 24 occurrences `\r` dans les CSV japonais, coréen et polonais ; ses DB initiales contiennent 8 textes avec ruby dans USER/type24, dupliqués entre noms et cellules. Ni `\!` ni `\se` brut n'est présent dans ces CSV. Ces chaînes peuvent être introduites par les variables : réussir le parcours anglais ne prouve pas le rendu de toutes les langues. Les JSON privés `*-text-escapes.json` conservent contexte, emplacement et digest, sans publier le texte du jeu.

Le [contrat REA de progression](TEXT_PICTURE_TIMING_REA.md) distingue les parsers W2/W3, le compteur d'octets, Sys11 froid à1 000 000, les pauses16 ticks, la conversion Sys150 capturée au SHOW, les paires de backslash et les CR supprimés avant substitutions. Dix tests portables comparent **4 608 étapes C++** indépendantes ; MOVE conserve l'horloge et GetInfo10 peut rester0 au SHOW avant le premier tick. Le helper refuse précisément ruby/audio/input-release non qualifiés pour une picture ordinaire ; l'opcode150 accepté à lui seul ne garantit pas ces variantes dynamiques.

Le run final de lecture brute inclut maintenant les 24 CSV : 158 ressources distinctes demandées et 1 775 574 octets positionnels lus. Il ne copie ni les ressources de jeu ni l'archive complète vers des assets distribués. Les temps JVM et ces compteurs ne sont pas une mesure de lancement Android ou de FPS.
