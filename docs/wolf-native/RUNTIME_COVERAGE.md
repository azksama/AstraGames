# Couverture du moteur Wolf natif — beta.19

Le moteur exécute les données du jeu sur Android, sans lancer Game.exe, Wine ou Box64. Le parcours Winlator reste disponible. L'admission examine les modes et paramètres des commandes, les références système, les pages, les routes et les cartes ; un opcode connu ne suffit pas à établir la compatibilité.

Les preuves de formats, de sémantique portable, de rendu Android et de parcours de vrais jeux sont distinctes. Le [rapport beta.15](../VALIDATION_1.12.0-beta.15.md) conserve les parcours historiques ; la [validation beta.19](../VALIDATION_1.12.0-beta.19.md) décrit les ajouts et tests actuels. L'[inventaire des corpus](CORPUS_LEDGER_2026-10-10.md) conserve aussi les anciens checkpoints, y compris leurs refus ; une admission statique positive ne démontre pas l'exécution de toutes les branches.

Le [catalogue124](STATE_READ_COVERAGE.md) classe149 emplacements en113 lectures admises et36 refus/réservés, avec les profils et flags contrôlés séparément. Les temps audio/CPU/rendu et mémoire viennent de l'hôte mesuré ; mouvements autonomes2/3, macros de mesures/types/fichiers et expansion DB imbriquée sont portés dans les contrats qualifiés. La sauvegarde native v23 conserve les durées des images. Les ressources réellement absentes, quadrilatères et autres modes non qualifiés continuent de déclencher le secours Winlator.

## Fonctions implémentées

| Surface | Comportement exécuté |
|---|---|
| Métadonnées et ressources | Game.dat, événements communs, bases de données, tilesets et cartes CP932/UTF-8 ; enveloppes chiffrées qualifiées, LZ4 et archives Wolf/DX documentées. Images, masques, polices, audio et vidéo WOLFX avec la clé vide qualifiée. Lecture directe du dossier ou de l'archive, sans import général du jeu. |
| Interpréteur | Événements de carte et communs, pages et conditions, branches numériques/texte, AND complet, boucles, labels, appels nommés ou numérotés avec arguments/retours, variables et DB mutables, erreurs localisées et budget partagé par tick. |
| Entrées | Lecture Wolf des touches, souris/tactile et manette, fronts conservés pendant les opérations asynchrones, attente de saisie, répétition, affectations clavier/manette Sys52..57 et blocage des périphériques. Les boutons de l'interface constituent des commandes sémantiques ; les périphériques physiques passent par les affectations du jeu. |
| Personnages et cartes | Collision, demi-grille, comptoirs, boucle de carte, navigation et recherche de chemin, déplacements autonomes et routes, vitesse/fréquence lues dans les tables du jeu, sauts, animation, ombres facultatives, hauteur et effets de personnage. Transferts, changement de tileset et modification de rectangles de tuiles. |
| Caméra et composition | Défilement, secousses, zoom, tonalité, flash, capture et transitions. Fonds et brouillards avec phases, parallaxe et mélanges distincts Wolf 2/3. Ordre des groupes d'images, découpe des sprites, sources divisées, ancrage, couleur, rotation, interpolation, effets retardés et masques de visibilité. |
| Texte | Dialogues, choix, couleurs, tailles, familles initiales et polices modifiées par le jeu, captures de police par image texte, ruby de rendu et images/icônes intégrées. Horloge des images texte selon les octets du profil Wolf, changements de vitesse, pauses, affichage instantané et état sauvegardable. |
| Fichiers et DB | Lectures de texte dans les codecs qualifiés, accès confiné, écritures privées atomiques sans modifier les originaux, lecture/écriture CSV avec type des cellules et comportement explicite quand le fichier manque. |
| Audio | BGM/BGS/SE, lecture, arrêt, volume/fades, MIDI natif, durée mesurée et voix Android pour les macros de synthèse vocale qualifiées. Une voix doit être installée sur Android. |
| Vidéo | MP4 par les codecs Android ; Ogg Theora/Vorbis par les bibliothèques Xiph embarquées, image ARGB et PCM, horloge A/V, pause, boucle, déplacement temporel, volume et conversion de vitesse PCM. Aucun exécutable FFmpeg n'est lancé par l'application. |
| Téléchargement | Commande260, progression/statut Wolf, transfert HTTP contrôlé, TLS, annulation et fin asynchrone ; les réponses tardives d'une session remplacée ne modifient pas la nouvelle session. |
| Sauvegardes | Sauvegardes privées complètes et opérations de valeurs221/222, CRC et empreinte des métadonnées, remplacement atomique avec copie précédente, conservation des modifications de DB/cartes et des états visuels. Lecture des anciens snapshots publiés beta.14 et des versions intermédiaires. |

Les fonctions nouvelles sont reliées aux contrats Windows relevés avec REA et, lorsque possible, à des vecteurs de référence indépendants. Les sources et les rapports spécialisés indiquent les adresses, empreintes et limites de ces observations statiques. La taille de la whitelist ne remplace pas cette validation.

## Parcours de jeux

Les données officielles Wolf 2.29.6.1 et Wolf 3.72.9 sont utilisées sans réécrire les événements ni injecter les variables du scénario. Les tests portables parcourent le titre, la nouvelle partie, le tutoriel, le déplacement, un combat et la reprise d'une sauvegarde avec les propres commandes de ces jeux. Les tests Android passent par les contrôles de l'application et vérifient séparément le rendu, les fichiers de sauvegarde et les originaux.

Un troisième corpus, The Crooked Man, exerce une archive de jeu, les polices protégées, le choix de langue et les introductions. Les checkpoints Android disponibles et leurs résultats figurent dans le rapport de validation ; le fait de parser son archive ne constitue pas à lui seul une preuve de jouabilité.

Les jeux et leurs fichiers originaux ne sont pas distribués dans l'APK, les fixtures publiques ou les sources de release. Dragon Blood n'est pas présent dans le corpus local : les anciens rapports Wine transmis par l'utilisateur ne prouvent pas sa compatibilité native.

## Admission et secours

Le mode Automatique conserve les jeux ayant déjà un état Windows dans leur parcours existant. Pour un nouveau jeu, l'analyse des métadonnées et de toutes les cartes précède l'admission native. Les cartes sont lues séquentiellement ; les matrices ne sont pas toutes conservées en mémoire. Depuis beta.18, un refus localisé n'interrompt plus l'examen des autres cartes : le rapport agrège jusqu'à 512 incompatibilités distinctes, indique le nombre de cartes examinées et signale un scan incomplet.

Une commande, une référence ou une ressource obligatoire non qualifiée provoque un refus localisé. Le moteur ne transforme pas silencieusement une référence inconnue en zéro et ne saute pas arbitrairement une commande de scénario. Le même préflight complet s'applique au mode Natif explicite : une incompatibilité connue arrête la préparation avec le rapport, avant le premier tick. Les références calculées sont contrôlées lorsqu'elles se résolvent. Automatique conserve le secours Winlator ; aucune sauvegarde native n'est présentée comme une sauvegarde Windows.

Le [catalogue système beta.18](SYSTEM_VARIABLE_COVERAGE.md) rapproche tous les identifiants de l'aide normale et Pro du contrat partagé par VM et inspecteur : 159 lectures admises, 24 fonctions refusées. Les variables transitoires Android ne sont pas admises comme des champs d'une sauvegarde isolée. Les autres namespaces (variables ordinaires, self, common, DB et caractères) conservent leurs contrats et limites, notamment les propriétés de caractère non portées ; ce catalogue système n'est pas une certification de tous les opcodes ni de tous les jeux.

Les extensions non qualifiées incluent notamment l'import des sauvegardes Windows, les fichiers de replay, le profil d'archive Pro1030, les clés WOLFX personnalisées ou dépendant des variables, les lecteurs de cartes/texte/CSV WOLFX, certaines transformations quadrilatérales d'images, certains modes de texte et les vidéos AVI/MPG. Les lecteurs protégés non câblés refusent la ressource avant une version claire coexistante. La recherche d'alias protégés par GET_FILE_EXIST reste non qualifiée. Les familles de commandes déjà portées peuvent également contenir des options non couvertes ; celles-ci restent signalées par l'inspection ou au moment de leur exécution.

## Limites des mesures

Les tests utilisent l'Android Emulator d'Android Studio, ADB, Logcat et l'instrumentation SDK. Le rendu logiciel de cet émulateur, un vecteur JVM ou un export REA ne garantissent pas 30/60 FPS sur un téléphone, ni la compatibilité de tous les jeux commerciaux et de toutes les versions de Wolf. Le profil, le jeu, le système Android et le matériel doivent accompagner toute mesure de performance.
