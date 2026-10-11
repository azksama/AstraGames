# Codes spéciaux W3 : contrats de recherche natifs

Analyse en lecture seule du `Game.exe` officiel W3, SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`. Cette preuve concerne ce binaire précis. Elle n'est pas une affirmation de compatibilité de toutes les extensions des versions suivantes.

Les six recherches du commun 40 du corpus utilisent l'opération 122 `flags=0x40200`. Son handler `00a0fe10` délègue au dispatcher `00a2e2d0`. Les exports publics REA de travail se trouvent dans `build/wolf-rea/wolf3-native-followup/fmt-macro-*.c.txt`, avec leurs attestations dans `responses/*.json`. Aucun original n'est modifié ou exécuté par ces fixtures.

## Recherche de commun par nom

`<<GET_COMMONEVENT_ID_FROM_NAME>>Nom` fait une recherche exacte dans l'index des noms non vides. L'index est construit en ordre croissant d'ID et chaque insertion remplace l'entrée précédente : un nom dupliqué retourne donc le plus grand ID. Un nom absent retourne `-1`, et le résultat est un entier décimal en chaîne.

Preuves : dispatcher `00a2e2d0` (`ev_19834e5d465fe253fd19992965b16c3b504f2e9b55526c168233506529097bc9`), lookup `00ae9a80` (`ev_32ffb142934fb583d2180188eeab6a7b967f2f69cdb42520bbe31ee07d549eed`), reconstruction d'index `009fae40` (`ev_66118dbb6f21cb7bd44511e3b724ff224d2df06ebb6ed8fb85449301340224b0`). Ni changement de casse ni normalisation Unicode ne sont établis.

## Recherche de chemin

Syntaxe atteinte par le jeu : `<<FIND_PATH_DIR8/v2/SX=…/SY=…/EX=…/EY=…/LEN=1/EXE=20000>>`.

| Paramètre | Contrat du binaire |
|---|---|
| `DIR4` / `DIR8` | Quatre / huit voisins, ordre fixe : haut, puis rotation horaire. |
| `SX/SY/EX/EY` | Origine et destination obligatoires. Les coordonnées de la macro sont normalisées modulo les dimensions, même si elles sont négatives. |
| `/v2/` | Permet une destination bloquée en modifiant temporairement sa case et quatre arêtes voisines. Sans v2, une destination non praticable retourne immédiatement `<<NoPath>>`. |
| `LEN` | Défaut 0, sortie entière. Ne limite pas la recherche ; limite seulement le nombre de pas sérialisés. Les valeurs négatives produisent une sortie vide pour un chemin non nul. |
| `EXE` | Défaut natif **10000**, et `-1` conserve le défaut. Limite le nombre d'extractions du tas. Le 20000 du jeu est explicite. |
| `MOVE_EV` | Défaut `-999` : aucune collision avec les personnages. `-1` désigne l'événement courant et `-2` le héros. Les valeurs `>=-2` activent le contrôle des personnages ; les diagonales vérifient aussi les deux cases latérales. |
| `POS` | Remplace les directions par les coordonnées des cases parcourues, sans l'origine. |
| `STEP` | Préfixe `nombreTotal(=STEP)\n`. Le nombre total n'est pas tronqué par LEN. |
| `TIME` | Suffixe `\nTIME=…ms/EXE=…/STEP=…`, avec le nombre réel d'extractions. |
| `XY/XYN` | Export dans une collection nommée/numérotée présent dans le dispatcher. Admission à garder explicite tant que cette destination de données n'est pas portée. |
| `NO_DIAG` | Aucun traitement observé dans cette macro du binaire ni dans ses constantes. Ne pas confondre avec DIR4 ou un flag d'itinéraire. |

L'option v2 est reconnue lorsque la comparaison du fragment `/v2/` retourne zéro. Elle force le byte d'arêtes de la destination à `0xff`, puis ajoute les quatre entrées correspondantes dans les voisins, **uniquement si le byte du voisin n'est pas nul**. Les voisins entièrement bloqués restent donc bloqués. Les bytes modifiés sont conservés puis restaurés après recherche. Helpers `0042b4a0/0042b4f0/0042b5c0`, attestations `ev_79f0c6a7782e13f28a33a405bf1908ce349ea90b9cb6cb3cacc11d17a1b61726`, `ev_83b422b0cbb2ba5714573704fbfd783d1a31e88da6504cedb075240bfef47468`, `ev_e3ab6c2dbedebcd586c773a59429b570f9546fa2eac97055690b662b573113e8`.

## Grille et recherche

Le constructeur de grille `009fb530` prend les permissions cardinales de chaque case fournies par `009fbb30`. Les directions utilisent les bits `01/04/10/40` pour haut/droite/bas/gauche ; les diagonales ajoutent `02/08/20/80`. Une arête cardinale exige la sortie de la source **et** l'entrée réciproque de la destination. Une diagonale exige les quatre arêtes qui encadrent le carré : les deux sorties cardinales du départ et les deux passages vers la diagonale. Elle ne coupe donc pas un coin fermé.

`009fbb30` interroge `009fa060(2*x,2*y,direction,…,mode=2,…)`. Le mode 2 teste le passage de la case entière. Les attributs de quart de case (`0x20`) ayant un nibble de blocage non nul refusent ce mode ; `0x200` traverse la couche supérieure pour poursuivre sur les couches suivantes. Le tile lookup `009f97e0` traite un tile brut supérieur à 99999 comme `raw/100000-1`, et sinon `raw+32` ; l'index 0 est vide.

Les cartes et grilles temporaires sont en colonnes puis lignes ; la copie vers le moteur de recherche utilise `index=colonne*nombreDeLignes+ligne`. Les champs de bouclage `map+0x1e60/+0x1e61` correspondent respectivement à X/Y. Le loader `009f8860` lit SysDB type0, ligne de l'ID de carte, champ3 : 1 active X, 2 active Y, 3 active les deux, les autres valeurs les désactivent. Le loader W2 `0044dcf0` applique le même contrat. `WolfParser.loadMap` renseigne donc `WolfMap.loopX/loopY` ; la lecture du MPS seul garde les deux à false. Le caller de la macro transmet les deux bytes au bind `0042b030`, dont les champs internes d'axe ne doivent pas être renommés par intuition : le code d'origine utilise un ordre différent entre bind et recherche. Les flags de la carte et l'ordre d'axes du helper de recherche sont deux contrats distincts.

Preuve du binding W3 : `fmt-macro-map-defaults-loader.c.txt`, `ev_21fd29f70d8ec5712cebd0ccdab4bf545a51bc2e7ab8fbe5169dcc251a682467`, et instructions `009f930b..009f9352`, `ev_dfc0d255cbd796761fb2987b335efb6a35b4b3cd4b1c6bacffeb74699888c4e6`. Le lecteur numérique `0093e6b0` confirme l'ordre `(type,data,field)` : `ev_435b5661be212575a587c8266da4e9ca8a563525bfac1a2872ad1dca4d42c39b`.

Preuves grille : `ev_65402ebdc6880b805878ed442e794612e591090a36e3fac71d7c3bb51880c017` (`009fb530`), `ev_bdf752988b27ad24875c472ced35c086b9c77cfd8b4ced60c655adb7aa47ebd1` (`009fbb30`), `ev_ea34d2c8af4d8ea305e8a588132357ad8aab351397ebe5608daca323eb3d47a2` (`009fa060`).

La recherche `0042ba10` coûte 100 par pas cardinal et 101 par diagonale. L'heuristique `0042b7d0` est `dx+dy` en DIR4 et `dx²+dy²` en DIR8, avec réduction aux distances bouclées ; remplacer ceci par une BFS changerait les résultats. Le tas compare les priorités signées sur 64 bits : le push ne fait pas remonter les égalités, le pop choisit le fils droit en cas d'égalité. Une meilleure distance n'est enregistrée que si elle est strictement inférieure. Les entrées devenues anciennes restent dans le tas, et l'extraction lit la meilleure distance actuelle de la case.

Le compteur EXE augmente pour chaque extraction, y compris celle de la destination. Si `pops >= budget`, le dispatcher retourne `<<NoPath/TooFar>>` **même si la destination a été trouvée lors de cette dernière extraction**. Une file épuisée sans destination produit `<<NoPath>>`. Aucun résultat vide ne doit être converti silencieusement en déplacement autorisé.

Preuves recherche : `ev_b7b7b89a93e6e853b595a2d00b56ec4ba7324200d1cd67b5871280fd6d43d338` (`0042ba10`), `ev_0e89756241e52d5babb2b89baaa9e751d7a13caa36cd218fe997af33521e5859` (heuristique), `ev_d82d219502065fdcfc8c6bcc6271eabb1a6c35ba3ffbea87812018d70e447d16` (push), `fmt-macro-path-priority-pop.c.txt` (pop).

## Occupation des personnages : MOVE_EV

`0095a9b0` prépare les marqueurs du héros et des événements, puis `0095acb0` les copie et retire l'occupation du personnage qui se déplace. Les membres du groupe ne sont pas ajoutés par ce builder. La grille utilise les demi-coordonnées logiques ; elle ne se déduit pas de la boîte du bitmap.

```text
pour chaque personnage, héros puis événements :
    si through : ignorer
    si le déplacement vise le héros et l'événement a word20=0 et graphic44=-1 : ignorer
    x0 = halfX / 2, largeur = (halfX % 2 == 1) ? 2 : 1
    si halfY % 2 == 1 :
        si foot : hauteur = 2
        sinon : halfY++, hauteur = 1
    sinon : hauteur = 1
    y0 = halfY / 2
    chaque case du rectangle reçoit indexPersonnage+10 si elle est vide,
        sinon INT_MAX (occupation superposée)
```

Les divisions et modulos signés suivent la troncature C vers zéro. `word20` est la longueur du fichier graphique courant dans la std::string à+10 ; `graphic44` est le tile graphique. Le filtre ignore donc les événements qui n'ont ni fichier graphique ni tile, seulement en mode héros. `009ee890` assigne le fichier à+10 (`LEA ECX,[EDI+10]` à009ee943, puis `CALL00437c40`) ; le changement de page `009f41a0` écrit+44 depuis son graphicTile. Preuves : `ev_0208922bb99345f85c91c8d618a5e4ee6d3f744644274a1743deebc7094ce572`, `ev_c850d993e827ab8fcd8498186f15ecf2a65725df91223187e3e4c5c4e0bf14de`. Ce test ne porte pas sur le nom de l'événement ni sur la présence d'une page.

Un personnage qui se déplace avec `through` vide toute la grille. Sinon, le builder retire uniquement son marqueur `index+10` dans les quatre cases à partir de `(halfX/2,halfY/2)`. Un marqueur `INT_MAX` reste présent à cette étape. Le caller lit ensuite le marqueur de la destination : s'il est non nul, il retire ce même marqueur dans les quatre cases à partir de la destination. Cette dernière règle s'applique aussi au sentinelle `INT_MAX` ; elle n'efface pas les marqueurs différents. Les déplacements diagonaux contrôlent destination et les deux cases latérales.

Preuves : `0095a9b0`, `ev_3d6b6e583fc2e019ae4d0ef1cd0f70a909b184f7dbb72eadb51f9c2c8ef190d4` ; `0095acb0`, `ev_08f7b705ea89487c63efbfb895d50529a7cf829ef3d02eb780d49e3eb1c68b61` ; instructions du caller à partir de `00a337da` dans `fmt-macro-dispatch.asm.txt`. `w3-native-occupancy.cpp` et ses 206 vecteurs TSV couvrent les parités X/Y, foot, événements sans graphique, personnages traversables, superpositions et nettoyage de la destination.

## Sérialisation exacte

`0042c6a0` calcule `total=nodes.size-1` avant troncature. Pseudocode :

```text
pour chaque pas i, sans la case initiale :
    si LEN != 0 et LEN <= i : retourner le préfixe, sans END
    si POS : ajouter "x,y/"
    sinon : ajouter une direction 1..9
ajouter POS ? "END" : "/END"
```

Conséquences : un LEN1 sur un chemin de deux pas peut retourner `6`, sans `/END` ; POS peut retourner `x,y/`, avec le slash final. Un chemin nul retourne `/END` en directions et `END` en POS, même si LEN est négatif. LEN supérieur ou égal au nombre total garde le marqueur de fin. Les déplacements bouclés utilisent le delta le plus court ; une égalité à la moitié de la dimension n'est pas inversée.

Preuves : `ev_8974c373a473cb8bff83317ac728feee82f9063028f893bee4570191ff0b04f5` (`0042c6a0`), `ev_e5ebb7da54e038e9111583a950218301fc8f52eda9da07b0a754d287a87e9502` (instructions), `ev_0a0da664fd95dccd1ec8cd102c09d28e93e292016269f031bada09d4b8b6bb14` (`0042ca30`).

Les 96 vecteurs de `wolf-native/src/test/resources/path-macros/w3-native-format.tsv` sont générés par la transcription C++ indépendante `w3-native-format.cpp` : directions, POS, bornes LEN, chemin nul, bouclages et demi-dimension. `w3-native-grid.cpp` produit séparément 300 grilles d'arêtes à partir des permissions cardinales ; `w3-native-occupancy.cpp` fournit 206 cas d'occupation des personnages. Sources et TSV sont au même emplacement. Ces oracles vérifient la sérialisation et la construction de grille ; ils ne constituent pas à eux seuls une exécution du jeu Windows ni une preuve de vitesse Android.
