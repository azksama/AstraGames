# Lecteur d’entrées Wolf 3 — bêta 17

Le rapport Seris de la bêta 16 s’arrête dans le common 0, commande 123, offset `0x3b`, avec « Mode de bord d’entrée non pris en charge ». Il ne contient pas les paramètres complets de cette commande ni les DAT/PROJECT du jeu. La correction ci-dessous traite des modes refusés par le lecteur Astra ; elle ne constitue pas une partie validée de Seris.

## Référence et observations

REA analyse l’exécutable officiel local Wolf 3.729, `sample3729/WOLF_RPG_Editor3/Game.exe`, préservé à l’identique : SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`. Le handler 123 à `0xa14280` transmet les flags au lecteur `0xa06cf0`.

| Preuve REA | Sujet |
| --- | --- |
| `ev_a1d27f0393f5a975c095efd3636301e9e4ef44af1fcc13c3bf5707a1d1704757` | handler 123, `0xa14280` |
| `ev_c2f7c12ef1509f4151c58b4a48415e5816853d771293ae77ebd29d3de57a251f` | lecteur, `0xa06cf0` |

Les exports complets restent dans le dossier local de recherche `build/wolf-research/beta17-input-rea.json`. La réponse du lecteur a dépassé la limite de transport ; le résultat de l’opération terminée a été récupéré et exporté, plutôt que de s’appuyer sur un aperçu tronqué. Il s’agit d’une observation statique de décompilation, pas d’une trace de Seris sur Android.

Le lecteur de référence sépare les périphériques et les modes du nibble `flags >> 16`. Les branches clavier et manette utilisent le même traitement pour les modes 0 et 1. Le mode 2 consulte les appuis ; le mode 3 consulte les relâchements. Pour les directions logiques combinées, il compare la direction résolue : passer de haut à haut-droite produit une nouvelle direction ; retirer haut tout en maintenant droite ne produit pas un relâchement à zéro. Les actions confirmer/retour/Shift conservent leur priorité.

## Implémentation et limites

Astra conserve une image des entrées courantes et précédentes par tick d’exécution. Les reprises asynchrones ne consomment pas une seconde fois les changements du même tick. Les lecteurs physiques mode 1 conservent le comportement de maintien du mode 0 ; les modes 2/3 des lecteurs logiques et physiques utilisent leurs propres changements. Les historiques sont éphémères et sont remis à zéro avec `releaseInput`, notamment lors d’un menu, d’un verrouillage ou d’une restauration. Les formats de sauvegarde ne changent pas.

Les tests JVM vérifient maintien clavier/manette/souris en modes 0/1, appuis et relâchements logiques/clavier/manette lus une seule fois, changement de direction combinée et annulation des historiques. Un test SDK vérifie aussi qu’un bouton personnalisé F8 du vrai overlay alimente le lecteur physique 123 du moteur natif.

Les modes récents gardent le contrôle de profil Wolf. Les variantes d’attente non qualifiées et le mode 4, qui fait intervenir des compteurs de durée dans la référence, restent refusés. Cette correction ne qualifie ni tous les paramètres de la commande 123, ni tous les jeux Wolf. Winlator reste disponible.
