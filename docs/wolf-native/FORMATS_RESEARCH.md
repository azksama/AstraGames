# Wolf RPG : formats nécessaires au moteur Android

Analyse du 10 octobre 2026. Cette recherche porte sur les données officielles **2.2961 et 3.729** présentes dans `build/wolf-research/`. Elle complète l'analyse REA des exécutables. Les identifiants `FMT-*` ci-dessous sont des repères locaux de cette recherche, pas des Evidence IDs renvoyés par REA.

**Résultat vérifié :** nous savons lire entièrement les huit cartes et les deux fichiers de common events de ces échantillons, soit **53 723 commandes**. Cela prouve la lecture du format de ces fichiers. Aucune commande n'est exécutée par la sonde, et aucune compatibilité de jeu Android n'est démontrée par ce résultat.

## Sources primaires et licences

| Repère | Source figée / fichiers examinés | Utilité et limites |
|---|---|---|
| FMT-S1 | [djytw/wolf-rpg-formats, `5c70e643693e08f99bf299a2ca598f7eaa02c542`](https://github.com/djytw/wolf-rpg-formats/tree/5c70e643693e08f99bf299a2ca598f7eaa02c542) : `common.ksy`, `mps.ksy`, `event_command.ksy`, `commonevent_dat.ksy`, `game_dat.ksy`, `database_dat.ksy`, `database_project.ksy`, `tilesetdata_dat.ksy` | MIT, copyright 2024 djytw. Le README vise 2.2x / 3.0x / 3.3x. Il n'établit pas le format 3.729 : compression et extensions récentes doivent être ajoutées. Plusieurs paramètres/opcodes sont laissés à documenter. |
| FMT-S2 | [Sinflower/WolfTL, `bfc38fc2735ab4f7f7ddbec8d57b7fef65f0712f`](https://github.com/Sinflower/WolfTL/tree/bfc38fc2735ab4f7f7ddbec8d57b7fef65f0712f) : `WolfTL/WolfRPG/{FileCoder,Map,Command,RouteCommand,CommonEvents,GameDat,Database}.hpp` | MIT, copyright 2024 Sinflower. Parseurs et outils de traduction, pas interpréteur de jeu. Le code conserve les paramètres des commandes et inclut des chemins de lecture 3.50+. Il contient des variables statiques globales de version/encodage : une réutilisation dans Astra doit les remplacer par un profil par jeu. |
| FMT-S3 | [Sinflower/UberWolf, `f4191375d289171b1f29dbc5ca320133b2b4b96a`](https://github.com/Sinflower/UberWolf/tree/f4191375d289171b1f29dbc5ca320133b2b4b96a) : `UberWolfLib/WolfDec.cpp`, `WolfPro.cpp`, `WolfCrypt/`, `3rdParty/DXLib/DXArchive*` | MIT pour le projet Sinflower, copyright 2023. Le code sélectionne plusieurs générations d'archives et de protection; les composants DxLib ont leur propre notice. L'extraction n'est pas un moteur, et le code utilise des API Windows. Aucun déchiffrement n'a été exécuté ici. |
| FMT-S4 | [SmokingWOLF : changelog officiel 3.50–3.649](https://smokingwolf.github.io/tool_wolf_rpg_editor/old_releaselog/ReleaseLog07.html), également [source figée `fe4425e0891e666ac9a577a5fc54035613f65900`](https://github.com/SmokingWOLF/tool_wolf_rpg_editor/blob/fe4425e0891e666ac9a577a5fc54035613f65900/old_releaselog/ReleaseLog07.html#L2021) | L'auteur décrit l'enveloppe LZ4 de CommonEvent/DB depuis 3.500 et le champ d'extension après la route de chaque commande. Copie locale : `build/wolf-rea/upstream/official/ReleaseLog07.html`, lignes 2021–2049. |
| FMT-S5 | [LZ4 : spécification des blocs](https://github.com/lz4/lz4/blob/v1.10.0/doc/lz4_Block_format.md) | Description primaire du format. La sonde Python implémente directement le décodeur borné avec gestion des références qui se chevauchent. Il s'agit de blocs bruts, pas de fichiers LZ4 avec framing. |

Les dépôts téléchargés restent sous `build/wolf-rea/upstream/` et ne sont pas intégrés dans l'APK. La sonde comporte les notices MIT des références dont sa structure est dérivée.

## Corpus et résultats de lecture

Commande reproductible, Python 3.12+ avec bibliothèque standard seulement :

```powershell
python tools/wolf_native_probe.py `
  build/wolf-research/sample22961/WOLF_RPG_Editor2 `
  build/wolf-research/sample3729/WOLF_RPG_Editor3 `
  --common-events --output build/wolf-rea/maps-and-common-probe.json
python -m unittest discover -s tools/tests -p test_wolf_native_probe.py -v
```

Résultats locaux `FMT-P1` : **21 tests passent, aucun ignoré dans cet environnement**. Les tests couvrent les fichiers officiels disponibles, toutes les troncatures d'une petite carte témoin, les compteurs incohérents, marqueurs/terminateurs invalides, chaîne non terminée, mauvais encodage, extension manquante, décompression hors bornes, offsets LZ4 impossibles, sortie visant un fichier d'entrée et passage par lien symbolique/jonction. Les fixtures officielles sont ignorées par Git; le test de corpus est explicitement ignoré si elles ne sont pas présentes.

`FMT-P2` : le rapport JSON contient `errors=[]` et `payloadFullyConsumed=true` pour les dix fichiers. SHA-256 du rapport brut local `build/wolf-rea/maps-and-common-probe.json` : `024f683087680d5102825b634d0308c2adf4c5cf76d7aba25c17f364cedff8bb`. Sa [projection sans les événements détaillés](corpus-summary.json) conserve cette empreinte sous `sourceReportSha256`. Les SHA-256 des entrées figurent aussi dans [fixtures.json](fixtures.json). Les **34 fichiers** du manifeste REA ont été recontrôlés après lecture : **aucun fichier d'origine modifié**.

| Version | Carte | Dimensions / couches | Événements / pages | Commandes | Taille fichier / payload décompressé |
|---|---|---|---|---:|---:|
| 2.2961 | Dungeon | 40×30 / 3 | 5 / 6 | 79 | 17 315 / 17 290 |
| 2.2961 | SampleMapA | 40×30 / 3 | 19 / 20 | 324 | 31 990 / 31 965 |
| 2.2961 | SampleMapB | 30×30 / 3 | 6 / 6 | 36 | 13 155 / 13 130 |
| 2.2961 | TitleMap | 20×15 / 3 | 1 / 1 | 87 | 7 806 / 7 781 |
| 3.729 | Dungeon | 40×30 / 3 | 5 / 6 | 79 | 2 790 / 17 777 |
| 3.729 | SampleMapA | 40×30 / 3 | 25 / 26 | 683 | 25 076 / 59 111 |
| 3.729 | SampleMapB | 30×30 / 3 | 6 / 6 | 36 | 3 054 / 13 919 |
| 3.729 | TitleMap | 20×15 / 3 | 1 / 1 | 89 | 3 223 / 9 264 |

| Version | Common events | Commandes | Taille fichier / payload décompressé | Dialecte observé |
|---|---:|---:|---:|---|
| 2.2961 | 225 | 25 611 | 1 055 820 / 1 055 809 | CP932, header `0x8f`, footer `0x8f`, non compressé |
| 3.729 | 225 | 26 699 | 385 224 / 1 309 081 | UTF-8, header `0x93`, footer `0x92`, LZ4 |

Les marqueurs header/footer de CommonEvent 3.729 **ne sont pas identiques**. Une validation qui compare les deux rejette à tort les données officielles. La sonde accepte ici seulement les deux dialectes CommonEvent réellement examinés.

Les cartes 2.2961 ont un wire version `100`, marqueur d'encodage `0` et layout marker `0x65`. Les cartes 3.729 ont un wire version `103`, marqueur d'encodage `0x55` et layout marker `0x69`. Ce ne sont pas les numéros commerciaux du moteur. Toutes les extensions des **27 586 commandes v3** lues ici ont une longueur nulle. Le support des extensions non vides est testé sur une fixture synthétique, pas observé dans ces jeux officiels.

## Structure utile pour un parseur Android

### `.mps` : conteneur, couches et événements

Observations `FMT-P3`, corroborées par [Map.hpp](https://github.com/Sinflower/WolfTL/blob/bfc38fc2735ab4f7f7ddbec8d57b7fef65f0712f/WolfTL/WolfRPG/Map.hpp) : un header de 20 octets contient `WOLFM` et un marqueur d'encodage. Puis viennent un entier little endian de version de format et un marqueur sur un octet. Pour le format compressé examiné, les tailles décompressée/compressée sont à l'offset 25, puis le bloc LZ4. Le payload contient titre, tileset, dimensions, compteur d'événements et, depuis le format 103, deux entiers supplémentaires dont le nombre de couches. Les tiles sont des mots 32 bits. Le dialecte UTF-8 peut signaler une matrice absente par `0xffffffff`.

Une carte contient des événements `0x6f`, leurs coordonnées et pages `0x79`. Chaque page conserve conditions, graphisme, route autonome et liste de commandes. Les terminators sont `0x7a` pour page, `0x70` pour événement et `0x66` pour carte. La sonde conserve les zones non interprétées en hexadécimal : consommation exacte des octets n'établit pas la sémantique de chaque drapeau.

### Commandes : conserver l'information avant d'interpréter

Observations `FMT-P4`, corroborées par [Command.hpp](https://github.com/Sinflower/WolfTL/blob/bfc38fc2735ab4f7f7ddbec8d57b7fef65f0712f/WolfTL/WolfRPG/Command.hpp#L706) : compteur de mots `u8`, opcode `u32`, arguments `i32`, profondeur de branche `u8`, compteur de chaînes `u8`, chaînes à longueur 32 bits incluant le NUL, puis drapeau de route `0/1`. Une route ajoute cinq octets d'en-tête, des flags, son compteur et des commandes de mouvement. Chaque commande route a opcode `u8`, compteur d'arguments `u8`, arguments 32 bits et terminator `01 00`. Le format 3.50+ ajoute ensuite une longueur `u8` et les octets d'extension.

Le futur IR doit conserver opcode, arguments **signés**, profondeur, chaînes, route, extension, offset et profil de version. Transformer les arguments en simples nombres immédiats serait incorrect : les valeurs peuvent référencer des variables. Les tableaux ne doivent pas perdre les commandes vides ou les fins de branche, même si un outil de traduction les filtre.

### Fréquences : orienter le travail, pas mesurer la compatibilité

`FMT-P5` : **48 opcodes distincts** apparaissent dans les données 2.2961, **46** dans les données 3.729; aucun n'est inconnu dans la table de noms de la sonde. Cela n'implique pas que leurs arguments ou leur comportement soient implémentés.

| Opcode | Nom de repérage | Occurrences dans le corpus complet |
|---:|---|---:|
| 103 | Comment | 11 654 |
| 121 | SetVariable | 8 185 |
| 0 | Blank | 6 839 |
| 401 | ChoiceCase / branche | 5 076 |
| 250 | Database | 5 050 |
| 499 | BranchEnd | 3 643 |
| 111 | VariableCondition | 3 550 |
| 300 | CommonEventByName | 2 311 |
| 150 | Picture | 1 564 |
| 498 | LoopEnd | 927 |

**Inférence de priorité :** un premier interpréteur utile devra d'abord traiter variables, DB, branches/boucles, appels de common events, puis images/messages/input/mouvements. Les commentaires et commandes vides gonflent les fréquences; «90 % des commandes reconnues» ne serait pas une preuve de jeu jouable.

## Autres formats : source disponible, validation restant à faire

| Format | Ce que les sources montrent | Preuve encore nécessaire |
|---|---|---|
| `Game.dat` | [game_dat.ksy](https://github.com/djytw/wolf-rpg-formats/blob/5c70e643693e08f99bf299a2ca598f7eaa02c542/game_dat.ksy) décrit options octets, chaînes, paramètres 16 bits et bloc de données supplémentaires. [GameDat.hpp](https://github.com/Sinflower/WolfTL/blob/bfc38fc2735ab4f7f7ddbec8d57b7fef65f0712f/WolfTL/WolfRPG/GameDat.hpp) conserve titre, fonts, key blob et données non interprétées. | Tester les offsets et variantes 3.729. Lire le profil de comportement choisi par le jeu, pas seulement la version de son EXE. |
| DB `.dat` + `.project` | [database_dat.ksy](https://github.com/djytw/wolf-rpg-formats/blob/5c70e643693e08f99bf299a2ca598f7eaa02c542/database_dat.ksy) décrit types, mapping des colonnes numériques/chaînes et valeurs. [database_project.ksy](https://github.com/djytw/wolf-rpg-formats/blob/5c70e643693e08f99bf299a2ca598f7eaa02c542/database_project.ksy) décrit noms, schéma et defaults; WolfTL recoupe leurs compteurs. | Consommation exacte des six paires officielles; distinguer DB fixe, mutable et système; vérifier appels par ID et par nom, conversions et états par sauvegarde. |
| `TileSetData.dat` | [tilesetdata_dat.ksy](https://github.com/djytw/wolf-rpg-formats/blob/5c70e643693e08f99bf299a2ca598f7eaa02c542/tilesetdata_dat.ksy) décrit textures de base/autotiles, tags et passage directionnel. | Tester variantes/counters puis prouver le découpage des autotiles et collisions par comparaison au moteur Windows. |
| `MapTree.dat` | Présent dans les deux échantillons; les sources de format retenues n'en donnent pas un parseur dédié. | Relier ID, chemins et carte initiale via REA ou sorties officielles; ne pas choisir arbitrairement la première `.mps`. |
| Archives `.wolf`, protection Pro, `.wolfx` | UberWolf contient DXArchive v5/v6/v8, clés/profils versionnés et formats protégés. | Corpus archivé contrôlé; index, priorité archive/fichiers libres, lecture aléatoire, chemins et déchiffrement. La sonde actuelle les refuse. |
| Sauvegardes | La documentation officielle permet sauvegarde complète et écritures partielles avec noms personnalisés. | Format exact, variantes de chiffrement, sérialisation de chaque état et round-trip avec Windows. Lire les cartes ne permet pas de migrer les saves. |

La [documentation officielle des sauvegardes](https://smokingwolf.github.io/tool_wolf_rpg_editor/help/04ev_file.html) décrit aussi la reprise d'un save au milieu d'un événement : le chargement repart sans événement en cours. Le futur moteur doit reproduire ce contrat plutôt que sérialiser naïvement toute sa pile VM.

## Compatibilité comportementale à conserver

La [configuration officielle](https://smokingwolf.github.io/tool_wolf_rpg_editor/help/02gamesetting.html) prévoit un tick de jeu 30/60 FPS, plusieurs résolutions dont 16:9, et un mode de comportement ancien de `Game.exe`. Les différences concernent notamment les couches et transitions d'images, le traitement du texte et certaines conversions DB. **Choix de conception :** séparer cadence logique du jeu, rendu Android et zoom/letterboxing; tenir un profil de compatibilité par jeu. Augmenter le rendu à 60 FPS ne doit pas doubler les attentes ni le déplacement.

La [documentation des common events](https://smokingwolf.github.io/tool_wolf_rpg_editor/help/02commonev.html) distingue appel seul, auto-exécution et deux modes parallèles. Les entrées alimentent des variables locales de common event et un retour est possible. Une VM limitée à «liste de commandes séquentielle» ne suffit donc pas; il faut contexte local, appels/réservations et ordonnanceur.

Le [manuel des références de variables](https://smokingwolf.github.io/tool_wolf_rpg_editor/help/04ev_valuenext.html) explique que les grands identifiants représentent des variables et permettent une indirection. **À établir avec REA et essais différentiels :** encodage complet des espaces Self/Common/Map/Sys, limites, signed overflow, division, hasard et résolutions récursives. Les commentaires `SetVariable` de WolfTL constituent des indices, pas une spécification exhaustive.

## Pistes existantes et décision technique

Le [manuel officiel consacré à BrowserWoditor](https://smokingwolf.github.io/tool_wolf_rpg_editor/help/06tools.html) décrit un processus de conversion : ajouter `BrowserWoditor.dat`, recréer `Data.wolf`, puis utiliser des ressources JS/Wasm dans un navigateur. Il signale que les fonctionnalités ne suivent pas forcément la dernière version du moteur. Ce produit ne prouve pas qu'une bibliothèque Android ARM64 réutilisable existe, ni que les jeux Windows existants s'importent sans conversion. Aucune licence de sources permettant son intégration n'a été établie ici.

DxLib possède déjà un [package Android officiel et ses sources](https://dxlib.xsrv.jp/dxdload.html), avec [conditions de redistribution et notices de composants](https://dxlib.xsrv.jp/dxlicense.html). **Inférence :** il pourrait servir de backend graphique/audio C++ si ses comportements utiles au Wolf runtime sont vérifiés. Il n'apporte pas l'interpréteur Wolf fermé et ne convertit pas `Game.exe` en ARM64. Aucun téléchargement/build de DxLib Android n'a été fait dans cette recherche.

**Premier jalon testable recommandé :** parseurs bornés indépendants d'Android → IR stable → carte et collision natives → variables/branches/common events → dialogue + choix + image + audio → save/load réversible. Chaque comportement doit être comparé à une fixture officielle et au moteur Windows. Garder le moteur Wine actuel pour les jeux dont l'inventaire de fonctions n'est pas couvert, puis basculer uniquement après preuve d'exécution complète.

Le prochain verrou n'est plus «peut-on lire les événements des deux échantillons ?», auquel la sonde répond oui. C'est **reproduire leur sémantique, leur ordonnancement et leur état**, puis élargir les formats/versions sans annoncer une couverture universelle à partir de ces deux seuls jeux.
