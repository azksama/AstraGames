# Analyse REA de Wolf RPG pour un moteur Android

Recherche du 10 octobre 2026. **La structure d'un interpréteur natif est identifiée; le moteur Android jouable reste à construire.** REA a analysé statiquement deux exécutables officiels avec Ghidra. La recherche retrouve le chargement des événements, leur préparation, une VM de commandes et des dépendances graphiques Windows. Une sonde indépendante lit les données des deux versions, sans exécuter les commandes.

La voie retenue est de reconstruire les formats et les comportements dans un cœur portable, puis de fournir rendu, audio, fichiers et entrées Android. Les fonctions Windows ne se transforment pas en moteur ARM64 par recompilation du pseudo-code. [L'architecture et les critères de validation](RECONSTRUCTION.md) décrivent le passage de cette recherche à un premier jeu natif.

## Périmètre et identité des artefacts

| Référence | Échantillon officiel | Format observé | Taille | SHA-256 |
|---|---|---|---:|---|
| W2 | Wolf RPG Editor 2.2961 / `Game.exe` | PE32, x86, 4 sections | 6 963 200 octets | `795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0` |
| W3 | Wolf RPG Editor 3.729 / `Game.exe` | PE32, x86, 7 sections | 12 511 744 octets | `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e` |

Les adresses ci-dessous appartiennent uniquement à ces empreintes. Elles ne doivent pas être appliquées à un autre `Game.exe`, à un `GamePro.exe` ni à un EXE personnalisé. Les jeux commerciaux des anciens rapports Astra n'ont pas été fournis comme cibles binaires de cette analyse. Les exécutables officiels n'ont pas été lancés pendant ce travail.

Les données témoins comprennent 34 fichiers identifiés dans [fixtures.json](fixtures.json), dont huit cartes et deux `CommonEvent.dat`. Les originaux, les archives et les dossiers complets REA sont conservés localement dans `build/wolf-research/` et `build/wolf-rea/`, ignorés par Git. Les 34 empreintes ont été recontrôlées sans changement.

## Méthode et niveau de preuve

REA **6.3.0**, provider **Ghidra 12.1.4**, analyse automatique `ghidra-default`, Node **24.21.0**, JBR/JDK **21**. Empreinte du profil natif : `147197ae9bfae42dc35826e4b23e5989fee5d5139aab74b13dcab0515e145825`. Le profil complet et les limites figurent dans [l'index des preuves](evidence-index.json).

Les conclusions ci-dessous distinguent :

- **Observation statique** : octets, adresses, branches, appels ou tables renvoyés par REA sur un artefact identifié.
- **Inférence** : nom métier attribué à une fonction en rapprochant ces appels, opcodes et formats. `FUN_*`, signatures et types Ghidra ne sont pas les sources originales.
- **Inconnue** : comportement qui demande encore une exécution de référence, un autre format ou une validation Android.

Les nombres de procédures Ghidra — **11 104 pour W2 et 18 194 pour W3** — décrivent l'inventaire d'analyse, pas le nombre de commandes Wolf ni une couverture fonctionnelle. Les noms issus de Function ID et les xrefs vers des messages doivent être vérifiés : une grande fonction W2 référencée par plusieurs erreurs s'est révélée être une table de localisation, pas le démarrage du jeu. Une absence de xref retrouvée vers `Game.dat` W3 ne prouve pas l'absence d'utilisation de ce fichier.

Overviews : `ev_2df118baf2b4ebf4aa1819cc50aec690b1629c64e6d127bcd2ce88fe57b417af` (W2), `ev_e71d1df32d2044de573cc47f7d5def45e7853978ef840c386f8c8f3e15206b2a` (W3).

## W2 : chaîne de chargement et VM

Les références à `BasicData/CommonEvent.dat` conduisent à `0x44fed0`, qui appelle le lecteur `0x487c30`, puis la préparation `0x43a5c0`. Le lecteur vérifie des caractères du header, le marqueur `0x8f`, le compteur d'événements et le footer `0x8f`; chaque événement est confié à `0x488460`. Cette chaîne corrobore le dialecte CP932 non compressé lu par la sonde.

| Adresse W2 | Rôle déduit des appels et états | Preuve REA principale |
|---|---|---|
| `0x44fed0` | Chargement CommonEvent/tileset et initialisation du contexte | `ev_57004174835289523cc5d5b0666cada184bed5b0d54a1b04adcd05f0791a4330` |
| `0x487c30` | Lecture du conteneur CommonEvent | `ev_81e13cc2a54579417fcdaf80e2f70649b23fb52e1310f26a04ed9407a68c556f` |
| `0x488460` | Lecture d'un événement commun et de ses commandes | `ev_f91bb739c350969fa6e57e961660de31e61ce70c6af5fc7102d155ef4ce99e64` |
| `0x43a5c0` | Préparation des branches et opérations internes | `ev_b141a833fb1005e55888a79bfcd0c3fe094d0363c20cb35b4abd0ab5d27d76c5` |
| `0x4cad00` | Boucle principale, mise à jour/rendu et cadence | `ev_f1d62321a306153ad4dc37c1df1e2427071b827dce0d4fb93b9daa73fbaa0acb` |
| `0x4cbde0` | Mise à jour d'un événement et appel du runner | `ev_1b606a8ce672a4909d5f87508d6c4812c4b81cae762b4b6c9724a53c244454e1` |
| `0x453690` | Initialisation du contexte d'événement | `ev_709e9df4836fd2eda632e81e4ee9df717c876e04a78229965316db71d59934a8` |
| `0x453930` | Progression du programme, suspensions et dispatch | `ev_1e26b5737a582c3b5c770f01eeee76ddbb1eeb5c29bc5fc838f0f1c60cc828e5` |
| `0x455650` | Répartiteur des commandes | `ev_a0a719318db3fed1fb7ac92c197862e89ae0a1a9f2bf2159eceff4e8ecf62f3e` |

### Commandes préparées avant exécution

`0x43a5c0` parcourt les commandes, utilise leur profondeur, résout des destinations et transforme certaines opérations vers des variantes internes. Le chemin de l'opcode 498 recherche une cible de boucle; des variantes de conditions et DB sont aussi préparées. Cela justifie un **IR distinct des fichiers d'origine**, conservant l'opcode et l'offset source avec les destinations résolues. Copier les seuls numéros d'opcode dans un grand `switch` ne suffirait pas.

Corroboration assembler : `ev_94a6598cb596d2ec7891d9dcb9239b6791e02db868bfea463c74bb4e670d26c7`. La correspondance exhaustive entre toutes les opérations préparées, leurs flags et le fichier reste à valider.

### Répartiteur et fonctions à spécifier

REA délimite `0x455650–0x457333` inclus : **7 396 octets de corps, 226 blocs de base, 76 fonctions appelées et six tables de saut récupérées**. Certaines tables concernent des sous-modes ou opcodes internes. Les six tables ne représentent donc pas six familles de commandes, et les 89 labels `case` du pseudo-code ne sont pas 89 opcodes de fichier. Les labels inconnus restent `null` dans l'index.

La table suivante rapproche un opcode documenté dans les formats de l'appel direct observé dans sa branche. Elle donne des points d'analyse, pas des handlers natifs déjà implémentés.

| Opcode | Nom de repérage | Handler W2 observé |
|---:|---|---|
| 101 | Message | `0x4579a0` |
| 102 | Choices | `0x457c60` |
| 111 | VariableCondition | `0x458150` |
| 121 | SetVariable | `0x458ae0` |
| 122 | SetString | `0x45b670` |
| 123 | InputKey | `0x45dd40` |
| 140 | Sound | `0x460610` |
| 150 | Picture | `0x460c60` |
| 180 | Wait | `0x463080` |
| 201 / 202 | Move / WaitMove | `0x463100` / `0x4631d0` |
| 210 / 211 | CommonEvent / CommonEventReserve | `0x4631f0` / `0x463ef0` |
| 220 / 221 / 222 | SaveLoad / LoadGame / SaveGame | `0x465720` / `0x465a30` / `0x465d20` |
| 250 | Database | `0x4669f0` |
| 300 | CommonEventByName | `0x464080` |

Pseudo-code : `ev_bebd35494eeaa62be0970c08eba355f916e53812b4c3858f7d2c5edbace35b13`; instructions : `ev_6a39908dfd06f405e2f2ed774a5a38b5f8a34dfa813713304e622a80f7a83488`. Le dossier typé complet dépassait la limite MCP de 10 MiB; il a été récupéré intégralement par `export_evidence_bundle`, avec son Evidence ID. Il n'a pas été tronqué pour produire cette table.

### Attente, variables, images et saves

**Attente.** `0x463080` résout l'argument, ramène une valeur négative à zéro, renseigne le champ d'attente du contexte à l'offset `+0x20`, puis rend la main. Le runner `0x453930` décrémente ce compteur et peut suspendre l'événement avant le dispatch suivant. Le futur moteur doit représenter cette suspension dans l'ordonnanceur. La durée exacte, les modes et les effets d'un profil 30/60 ticks demandent un replay de référence.

Preuves : `ev_25e43b5e698f359124e685302b7f56faf3b595ff26472f8889c7baf1bbf5834c` et `ev_16abd8cd995a77aabd940374d3e6441ccc6478c33231c4947b882781362bc9e5`.

**Variables.** `0x458ae0` interprète plusieurs champs de flags, avec chemins arithmétiques, bitwise et aléatoires. Dans les branches examinées, le diviseur nul est remplacé par 1. `0x490bc0` distingue valeurs immédiates et plusieurs plages d'identifiants dépendant du contexte. Cela interdit d'interpréter chaque argument comme un entier immédiat; cela ne spécifie pas encore toutes les plages de variables ni tous les débordements.

Preuves : `ev_0a908f087378b336dbb6b15944c688229a82a5535803d99f4aed98bd841d340a`, `ev_55e61dd693e617805d1fe394ef28a357636e8e4645944265c59fd5be2eb4e725`.

**Images et input.** `0x460c60` traite de nombreuses variantes Picture; `0x45dd40` configure des flags et états d'attente d'entrée. Ces handlers donnent les frontières entre état du jeu, ressources et backend. Leurs coordonnées, transformations, transitions et règles de press/release devront être comparées avec Windows.

Preuves : `ev_3ef8d47987957ed3ca8bd218ffb97c03be0ab0363b652a6d5da688738f622ba4`, `ev_2f56c12e588c386677e177707166ed9a429669b0b928fa21f9ddbfde05580247`.

**Common par nom et sauvegarde.** Les chemins `0x464080` et `0x465d20` ont été retrouvés et décompilés. Le premier manipule des contextes d'appel; le second sélectionne des chemins de save et appelle d'autres fonctions. Le codec final des sauvegardes n'est pas établi par cette étape. La compatibilité d'une migration des sauvegardes Astra reste à démontrer.

Preuves : `ev_dc3ce8caba04c592216c8db2bf780d92984749abdc810cdc2727e9611b2c47f0`, `ev_cc6f440a7c9b46e95113c68c5bcccf8c17d66905ce21af0d747ed721e765ff3d`.

## W3 : comparaison indépendante

Les xrefs de `CommonEvent.dat` conduisent notamment à `0x9fae40`. Cette fonction construit le chemin, appelle `0x5e2810`, puis la préparation `0x9d93d0`. La préparation parcourt les records de commandes, traite branches/boucles et variantes internes. Elle examine aussi des directives `PerformanceMonitor` dans le texte de certaines commandes : tous les commentaires ne peuvent donc pas être présumés sans effet de préparation.

Preuves : `ev_a5fee8d67ae717ef8e600d693b04608737cd99d6211e3065b8f94777a5b2b804` (wrapper), `ev_84e1969f6c5fb031f40d965ef97553ac94e5809b9a794dc650c2b3072d3c5dcb` (préparation).

Le chemin des requêtes Common par nom mène à un handler étendu, puis au handler de chaînes `0xa0fe10`. **Ce dernier n'est pas le répartiteur.** Remonter son appel à `0xa09588` conduit à `0xa082c0`, qui lit l'opcode du record courant, fait progresser la position du programme et appelle les handlers. Son propre caller à `0xa0690e` appartient à `0xa05990`.

REA délimite le répartiteur W3 à `0xa082c0–0xa09714` inclus : **5 205 octets, 266 blocs de base, 87 fonctions appelées et neuf tables de saut récupérées**. Le pseudo-code comprend 126 labels, avec sous-modes et variantes internes; ce n'est pas un inventaire de 126 commandes de fichier. Son dossier complet est conservé dans le bundle.

Preuves : `ev_03096a032ee84b9f3d5c91861037cd0cf3d5d4eceb2faae72ad89456a627ce98` (résolution du caller), `ev_05aa41899532def30436fcf2e0909b3b427295fc49ef7aea15cfa102894cb525` (pseudo-code), `ev_2d6e38fdb63c5e0882bf174bba14cfe8e56bccc458c7d3edb5178567555b6391` (dossier typé), `ev_907993e988f2fbcf10056aedd1f5058b16726249a4a245cd92a575a3a86b7a01` (runner).

| Opcode | Point d'analyse | Handler W2 | Handler W3 |
|---:|---|---|---|
| 101 | Message | `0x4579a0` | `0xa0a1a0` |
| 121 | SetVariable | `0x458ae0` | `0xa0d670` |
| 122 | SetString | `0x45b670` | `0xa0fe10` |
| 123 | InputKey | `0x45dd40` | `0xa14280` |
| 150 | Picture | `0x460c60` | `0xa189c0` |
| 180 | Wait | `0x463080` | `0xa1b900` |
| 210 | CommonEvent | `0x4631f0` | `0xa1bee0` |
| 222 | SaveGame | `0x465d20` | `0xa1f2c0` |
| 250 | Database | `0x4669f0` | `0xa1fe30` |

Les correspondances W3 ci-dessus rapprochent branches du pseudo-code et appels assembler. La facette de tables typées n'a pas établi tous les labels d'opcode, notamment ceux de certaines branches qui rendent immédiatement la main. L'index conserve ses `null` et ses limites; il ne remplit pas ces labels à partir du pseudo-code. La sémantique des handlers demeure à vérifier par fixtures.

Le handler d'attente W3 résout aussi l'argument, ramène le négatif à zéro et écrit le champ `+0x20`. Le runner décrémente ce champ puis rend la main quand l'attente est active, avant d'appeler `0xa082c0`. Cela confirme une frontière utile à l'ordonnanceur, sans établir une équivalence de toutes les conditions de reprise. Preuves : `ev_43b02be197f663c08c5923d39f352c398baf9849c30daf1ec9e0f84704c6c105` (handler), `ev_2d12af78117ea7bb8d059a7b70908be8685d92d79b7ce90dcb9c44f605974c96` (runner).

### Limite du lecteur CommonEvent W3

Ghidra attribue à `0x5e2810` un corps non contigu : 7 842 octets dans un span de 46 784 octets. L'espace entre les ranges ne doit pas être traité comme le code de cette fonction. La décompilation a rapporté des instructions p-code non résolues, puis est restée bloquée. Après environ 13 minutes, seul le processus de décompilation appartenant à cette session a été arrêté; la session REA a continué à servir les autres fonctions et l'export.

Il n'existe donc **aucun pseudo-code complet vérifié du lecteur W3** dans ce résultat. Le retour `decompile_failed` est un diagnostic de l'analyse interrompue, pas un crash observé du moteur Wolf. Le log et la réponse d'erreur sont gardés sous `build/wolf-rea/wolf3/`. La consommation exacte du fichier LZ4 est prouvée séparément par la sonde et les références de formats.

Preuve de bornes : `ev_5c4c4d12fcb857d8ba7547e43ae80e1b11d208ee40fcf88cae8aabf931e40ff4`. Inconnue enregistrée : `unk_822272e1875c8824a98bb6f5d13b3df036a7aa0dea85ac254f74e8ab4b84a60d`.

## Couche Windows à remplacer

REA retrouve DxLib, le chargement de `Direct3DCreate9Ex` avec repli vers `Direct3DCreate9`, et des API Windows de fichiers, clavier et horloge. Les recherches W3 incluent aussi des chemins DxLib D3D11, fonts, son et chargement asynchrone. La présence d'un chemin dans une chaîne n'établit pas son activation pendant un jeu.

W3 contient `DxArchive_WOLF_MOD.cpp` et `DxArchive_WOLF_MOD_security.cpp`. C'est un indice concret de variantes Wolf : le lecteur d'archives standard DxLib ne peut pas être annoncé compatible sans essais. `GuruGuruSMF4.dll` marque également une dépendance audio Windows à remplacer. Le [backend Android disponible chez DxLib](https://dxlib.xsrv.jp/dxdload.html) peut servir de référence ou de candidat, avec [ses notices](https://dxlib.xsrv.jp/dxlicense.html); il ne fournit pas la VM Wolf.

Preuves : `ev_68215c53ed2c65d27e041868557cd3f86638da93686f8353b6287811a1cc6938` (chaînes W2), `ev_511ffe7dda2fdfe4796dc5290ffc9b1c7e663e077a67cc1fe557353ea2b943f1` (API W2), `ev_2b573e3327f8405e377bec0e2489d8bce709089c5b747db96ca5d68907afebbe` (chaînes W3), `ev_dc97ad5f238a8da3b16a9061dc2b04438544bbc6de0b1abdee84654f8f6db19c` (loader D3D W3, `0x8a99f0`).

## Lecture des données et performance mesurée

La [sonde de formats](../../tools/wolf_native_probe.py) lit huit cartes et 450 common events : **53 723 commandes, 48 types distincts**, avec consommation exacte des dix payloads et aucune erreur. W2 est CP932 non compressé; W3 utilise UTF-8, LZ4 et un champ d'extension par commande. Le footer CommonEvent W3 `0x92` diffère de son header `0x93`.

Ces faits sont détaillés avec les sources primaires, offsets, bornes et comptes dans [FORMATS_RESEARCH.md](FORMATS_RESEARCH.md). La sonde conserve paramètres, routes et zones opaques; elle ne fournit ni exécution, ni DB/tileset complet, ni rendu, ni sauvegarde compatible.

Le benchmark hôte sur ces dix fichiers donne une médiane de **414 ms** sur cinq passes. C'est une lecture Python sur Windows avec cache fichiers chaud. **Ce n'est pas une mesure Android de lancement ou de FPS.** L'accès direct, le chargement à la demande et le pipeline d'images proposés dans [RECONSTRUCTION.md](RECONSTRUCTION.md) devront être mesurés sur le moteur natif.

## Inconnues prioritaires

REA conserve quatre inconnues ouvertes dans le ledger W2, et celle du lecteur W3 décrite ci-dessus :

| Sujet | Preuve nécessaire | Unknown ID |
|---|---|---|
| Sémantique VM, scheduling, arithmétique, indirections et profils | Fixtures et replays différentiels avec état attendu | `unk_725a270efde0b2b4fc1237e2b2553fc30d3f26d7e37855c6f3295c6f2529fbf9` |
| Codec des sauvegardes et DB conservées | Saves témoins et allers-retours Windows/natif | `unk_7393af8f03f95ceaba8841c201d28be5efbd3785f69dc554048176e129c1ef6a` |
| Versions, Pro, archives et EXE personnalisés | Corpus représentatif identifié et tests par dialecte | `unk_39fb6cd9015c1cd31e50bccdeaecafa3ebad27e13efc05c11b93d87fb9aff7ac` |
| Objectifs 30/60 FPS sur Android ARM64 | Frames et latences mesurées sur prototype puis appareils | `unk_f702e18d90bfbf010369ad1fba3239f26cc96d035a1ce544b633d8a5679c70b4` |

Les fonctions 3.729 doivent être comparées avec leur propre jeu de preuves. Les résultats W2 n'établissent pas à eux seuls une sémantique identique pour W3. Les commandes absentes du corpus, Live2D, plugins et comportements d'un EXE modifié restent hors de la preuve obtenue ici.

| Question de recherche | État au terme de cette analyse |
|---|---|
| Quels exécutables et profils ont été étudiés ? | Répondu : deux PE32 officiels identifiés par hash. |
| Peut-on lire cartes et CommonEvent des deux corpus ? | Répondu pour les dix fichiers examinés, sans exécution. |
| Où sont la préparation, la progression VM et les handlers ? | Répondu pour les chaînes ciblées W2/W3; sémantique exhaustive partielle. |
| Quelles dépendances faut-il remplacer sur Android ? | Répondu pour les API et backends retrouvés; inventaire des extensions partiel. |
| Peut-on garantir saves compatibles, tous les jeux et 30/60 FPS ? | Non résolu : codec, corpus étendu et prototype Android nécessaires. |

## Livrables et passage à l'implémentation

Le dossier livre une recherche binaire REA, les formats examinés, les empreintes et index, un lecteur de recherche reproductible et **21 tests passés, aucun ignoré localement**. Les détails de reprise, export, snapshots et contraintes MCP sont dans [README.md](README.md).

L'étape suivante est M1/M2 : lecteurs C++ Game/DB/tilesets, IR et VM minimaux, première carte Android, puis titre → partie → déplacement → dialogue → changement de carte. Les sauvegardes, effets et versions supplémentaires viennent avec leurs preuves de compatibilité. Les tests Android suivront exclusivement Android Studio Emulator, ADB, instrumentation et Logcat.

L'APK Astra actuel n'a pas été modifié par ce dossier. Une release prétendant contenir un moteur Wolf natif jouable serait prématurée.
