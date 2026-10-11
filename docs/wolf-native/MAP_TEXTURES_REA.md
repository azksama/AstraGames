# Fonds et brouillards Wolf 2 / Wolf 3

`WolfMapTextures.kt` porte les phases et les quatre rectangles de dessin. Le contrôleur VM résout les fichiers et les variables ; le renderer consomme la géométrie sans avancer l'horloge. Les profils2 et3 restent distincts.

## Ressources et variables

| Variable | Contrat |
|---|---|
| Sys61 | Brouillard : −1 désactivé ; −2 chemin SysS31 ; ≥0 rangée SysDB13 |
| Sys62 / 63 | Vitesse X / Y du brouillard |
| Sys64 / 65 | Mélange / opacité du brouillard |
| Sys66 | Fond : −1 désactivé ; −2 chemin SysS30 ; ≥0 rangée SysDB13 |
| Sys67 / 68 | Vitesse X / Y du fond |

SysDB13 fournit le chemin au champ0, les vitesses aux champs1/2 et le diviseur de parallaxe au champ3 si ce champ existe. Les états froids sont ID−1, vitesses0, mélange0, opacité255. Le constructeur W2 initialise aussi les diviseurs à0.

SysS30/31 utilisent **LF** : `chemin\nvitesseX\nvitesseY\ndiviseur`. Les tokens absents conservent les anciennes vitesses et le diviseur. Le diviseur fourni est borné à−999..999. Le fond retire les CR seulement lorsque la description originale contient un LF ; le brouillard ne les retire pas dans son parser. La conversion W2 `_atol` conserve le débordement Int32 ; le CRT W3 sature à INT_MIN/INT_MAX. Le setter de chaîne signale un rechargement différé indépendamment de l'ID ; une réussite place ensuite l'ID à−2.

Le chargement par ID remet les phases à0 et effectue immédiatement une avance. Le rechargement par chaîne remet les phases à0 sans cette avance. Un fond absent est désactivé avec diagnostic. Le fallback de handle d'un brouillard DB manquant reste non qualifié ; le port conserve un refus explicite.

## Phases et dessin

La phase est en huitièmes de pixel. Additions et multiplications débordent en Int32 ; la division tronque vers zéro. Le fond utilise `vitesse × (60/FPS)` ; le brouillard `vitesse / (60/FPS)`. La caméra contribue `−2 × caméraEnQuarts / diviseur`. Le moteur ajoute la secousse brute en pixels directement à la phase en huitièmes : aucune conversion ×8 n'est établie.

W2 ajoute l'étendue du bitmap une seconde fois avant modulo et conserve un reste négatif. W3 ramène les négatifs par additions de l'étendue. La validation de sauvegarde prend donc `behaviorVersion=2/3`. Pour une dimension positive N, W2 admet `1−8N..8N`, W3 `0..8N`. Accumulateurs et diviseurs restent signés. Les dimensions0..INT_MAX/8 et le refus IDIV(INT_MIN,−1) sont des protections du port, pas des clamps attribués au moteur Windows.

Les quatre parties remplissent une seule étendue de bitmap, sans répétition jusqu'au viewport. Hors échelle unité, les phases4..7 sont portées à8. Zoom et origine sont déjà intégrés aux destinations.

W2 `DrawRectGraph` unité pose un clip puis dessine le bitmap complet translaté par `destination−source` : aucune remise à l'échelle des pixels restants si la source déborde. En zoom, `DerivationGraph` refuse une origine négative ou ≥dimension ; avec origine valide, une taille dépassante est bornée à la fin du bitmap. Le sous-bitmap est ensuite dessiné aux destinations originales, ce qui peut l'étirer.

W2 mélange≥3 appelle DxLib MUL4 ; W3 appelle MULA11. Le fond désactive la transparence, le brouillard l'active. Le fond W3 se situe après les pictures≤−400000 ; le brouillard suit la passe positive151 et se dessine hors masque de carte.

Le draw W3 soustrait le global `0150BB44` à255 pour borner l'opacité. Ses24 références directes inspectées sont des lectures et ses octets statiques valent0. Son rôle et d'éventuelles écritures indirectes restent inconnus : il n'est assimilé ni au fade151 ni à `screenOpacity`. Le mode normal froid utilisé est `min(Sys65,255)`.

## Preuves

- PE W2 SHA-256 `795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0` : fogID4d3d90, backID4d2530, parsers4d4330/4d2930, ticks4d4160/4d32b0, coordinator4d5850, draw4d3480/4d1e20, rect540530, derive540400→53e490.
- PE W3 SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e` : fogID9ceca0, backID9cd510, parsers9cf320/9cdad0, ticks9cf200/9ce6e0, coordinatora3ea50, draw9cfe90/9ce7f0, rect75a0a0, derive753d60.

Réponses publiques REA : `build/wolf-rea/wolf2-final-keys-followup` et `wolf3-final-save-followup`. Preuves ciblées :

- LF/CR W2 : `ev_1fd0b59e1c560042ead9f973c987dc2f0b1fdb3625d95891e1dc89ad337deb9d` ; LF W3 : `ev_9a31488413b9d82d4e206b156298b70bd7c738386a38bfaf34faff1937827eb9`.
- Coordinator W2 : `ev_fd7484e52fda6cacefc8cc4171dbf1ea6a13a1d015776d73e45c377009ded885`.
- Clip W2 : `ev_bdf002a90aa5718711506b7772a3b6773168c9b9bc48d3e92afda4c6ef4c7f17` ; derive : `ev_81ae9cc1b8cdae4e4845b3e5fb85da361d9164b96befd22ff3d900da106c3aba`.
- Décimal W2 : `ev_886e3354dacd027facfe34639c3566cae79b136ce3ec051aa2d2e62498f0c7ab` ; W3 : `ev_dba68ce3f49f422fc12e59ca9351024141699d05fd75be624ee8027b6f096a65`, borne signée `ev_192bd5dc0cfc7088ed34a18ccfc473a267d0cdbb9eb8fae47b4c4d5e0e27b1d3`.

Huit tests portables comparent **740 vecteurs C++ indépendants** :346 W3 et394 W2, incluant phases négatives, zoom, caméra, parallaxe, débordements et reprise. Sources et commandes : `wolf-native/src/test/resources/map-textures/README.md`. Cette preuve arithmétique est distincte des pixels Android et ne démontre pas une compatibilité de tous les jeux.
