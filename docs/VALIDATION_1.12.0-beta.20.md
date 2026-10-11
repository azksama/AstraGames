# Validation — Astra 1.12.0-beta.20

Cette version ajoute l’analyse de compatibilité Wolf depuis Astra et le choix du moteur dans la fiche du jeu. Elle corrige le volume utilisé par les contrôles d’espace des sauvegardes Wine, évite le travail de rendu sur les fenêtres cachées et renforce le profil Rapide. Le moteur natif reste expérimental ; Winlator et les sauvegardes existantes sont conservés.

## Analyse et choix du moteur

Un appui long sur **Moteur / Wolf RPG** dans le résumé de la fiche ouvre l’analyse. **Analyser le jeu** parcourt métadonnées, communs et toutes les pages des cartes déclarées, sans exécuter de VM, importer le jeu ou écrire ses sauvegardes. Le rapport contient occurrences de commandes/références, routes, macros, blocages et emplacements disponibles. Il reste sur l’appareil et s’exporte explicitement en texte ou JSON. Les compteurs continuent au-delà du quota de 20 000 détails ; un quota atteint ou une carte illisible rend l’analyse incomplète.

**Fiche → Compatibilité → Moteur de lancement** propose Automatique, Natif Android et Winlator. Changer ce champ conserve touches, positions, presets et préférences graphiques. Automatique respecte les sessions Windows existantes et le secours ; Natif forcé conserve les refus explicites. Les sauvegardes des deux moteurs restent séparées.

Un inventaire statique ne prouve pas toutes les branches du jeu, les ressources calculées, le décodage Android ou les FPS. Les cartes non déclarées sont hors du périmètre. Les détails sont dans [COMPATIBILITY_ANALYSIS.md](wolf-native/COMPATIBILITY_ANALYSIS.md).

## Sauvegardes Wine : reproduction et correction

Un lancement par chemin Unix utilisait Z:, raccordé à `/`. Les API Windows `GetDiskFreeSpaceExA(NULL)`, `GetDiskFreeSpaceExA(racine)` et `GetDiskFreeSpaceA(racine)` renvoyaient **0 octet disponible**, tandis que le dossier du jeu disposait d’environ 9,4 milliards d’octets et qu’une écriture réussissait. Cette différence reproduit un contrôle qui refuse la sauvegarde pour manque d’espace.

L’exécutable est maintenant lancé depuis **G:**, raccordé au dossier réellement utilisé : original accessible ou copie privée. Les mêmes API renvoient l’espace du bon volume. Wine crée d’abord les lecteurs standards d’un nouveau préfixe ; Astra ajoute ensuite le lien G:. Un ancien lien est remplacé atomiquement ; un vrai dossier existant sur G: est conservé et provoque un refus explicite. Aucun préfixe ou fichier de progression utilisateur n’est effacé.

Deux tests SDK exécutent un EXE Win32 écrit pour cette vérification avec le vrai runtime Wine épinglé, sous le profil Rapide. Ils reproduisent l’ancien contrôle, vérifient le nouveau et écrivent un fichier dans SaveData, en mode direct et copie privée. Le fichier de progression préexistant garde son contenu. Ils utilisent des fixtures jetables ; l’erreur du jeu utilisateur demande encore confirmation sur son téléphone. Un vrai volume plein ou un dossier non inscriptible n’est pas masqué.

## Rendu et profil Rapide

Le renderer ignore une fenêtre entièrement couverte par une fenêtre opaque au-dessus : pas de capture, transfert de texture ou dessin GLES pour cette fenêtre. Les pixels restent en attente jusqu’à sa réapparition. Les tests montrent le retour des pixels modifiés derrière le jeu, les menus partiels et le mélange transparent, sans perte d’image. La définition et le filtre d’image sont conservés.

Le benchmark SDK utilise un vrai affichage GLES/JNI sur Android 36.1/x86_64, 1280×960, filtre cubique, 400 frames par scénario, 160 échantillons par variante après chauffe, ordre ABBA et `glFinish`. Il compare les mêmes pixels avec le même contenu visible. Seul le traitement du bureau caché varie.

| Scénario | Médiane avant | Médiane après | P95 avant | P95 après |
| --- | ---: | ---: | ---: | ---: |
| Bureau statique derrière le jeu | 10,7645 ms | 7,5152 ms | 12,5969 ms | 8,2813 ms |
| Bureau modifié à chaque frame | 12,4151 ms | 7,4209 ms | 13,8126 ms | 8,3120 ms |

Cela représente environ **30 % et 40 % de réduction du temps de rendu mesuré**. Dans le scénario où le bureau change à chaque frame, les transferts de cette fenêtre passent de 491 520 000 octets à 0 sur chaque bloc de 100 frames. Les pixels visibles restent identiques. Ces scénarios sont synthétiques : ils excluent l’exécution du jeu, Box64, le décodage des images et la présentation à l’écran. Ils ne démontrent pas un gain de 30/40 % des FPS d’un jeu ni 30/60 FPS sur Samsung.

Le profil **Rapide**, appliqué après relance, utilise `SAFEFLAGS=1`, `CALLRET=1`, `FORWARD=1024` et eSync Wine, avec les réglages rapides existants. fSync reste désactivé. Équilibré et Stable gardent la synchronisation habituelle. Les paramètres sont documentés pour le Box64 épinglé ; leur comportement sur un téléphone ARM64 doit être confirmé en jeu. La réduction de barrières du profil Rapide peut être inadaptée à certains jeux : Stable reste disponible.

Le test réel de l’exemple officiel **Wolf 3.729** utilise ce profil et vérifie lancement, commandes, déplacement, modes Image entière/Remplir/Étirer, rotation, réglages et fermeture normale. Les captures et mesures d’images renouvelées restent dans les preuves locales. Ce parcours x86_64 ne constitue pas une partie complète, une mesure physique ARM64 ou une garantie de cadence du jeu.

## Vérifications de cette livraison

Seuls Android Studio Emulator, ADB, Logcat et instrumentation SDK sont utilisés pour Android. ARTEMIS n’est pas employé.

- **410 tests JVM moteur + 251 application = 661 réussites**, aucun échec/erreur/ignore. Le lot couvre notamment toutes les pages/communs sans VM, quotas, carte initiale illisible, annulation, schéma du rapport et conservation des données.
- SDK fonctionnel téléphone 1080×2400/420 dpi : **234 cas, 222 réussites, 12 ignorés**. Sept cas demandent des corpus natifs optionnels non provisionnés et cinq sont d’anciennes sondes Wine/utilitaires de reprise après arrêt de processus, activés uniquement par arguments dédiés. Les exports texte/JSON, FileProvider, URI content, appui long, choix du moteur, options préservées et workflows de sauvegardes sont vérifiés. Deux lots supplémentaires vérifient le rendu (2 cas) et les sauvegardes Windows (2 cas) ; le parcours Wine officiel ajoute 1 cas. Total **239 cas distincts, 227 réussites, 12 ignorés**, sans additionner les premières tentatives. Le vrai Wine est qualifié dans ces lots séparés malgré les anciennes sondes désactivées.
- Les essais initiaux restent séparés : fixture cache de 70 000 fichiers évincée sur un ancien AVD presque plein, puis tutoriel Android de plein écran prenant le focus sur le nouvel AVD. Le lot final utilise un AVD neuf avec espace disponible, confirmation système préparée et animations système désactivées pour les clics d’instrumentation. Les animations actives provoquaient des clics de menu hors de la zone momentanément visible. Le driver d’accessibilité SDK natif est connecté avant l’ouverture des activités : sa connexion tardive dans un popup ne récupérait pas ses actions de clic, alors que le parcours avec captures initiales réussissait. Les parcours concernés ont été rejoués sans dépendre du mode capture. Les fixtures GLES ont été corrigées pour représenter le bureau Wine comme une fenêtre enfant et attendre une surface EGL active. Une première version de montage G: avant wineboot empêchait la création des lecteurs standards ; l’ordre corrigé est testé sur deux nouveaux préfixes.
- APK **R8 signé, non debuggable**, mise à jour de beta.19/code 47 vers beta.20/code 48 avec le même certificat. Le probe de cet APK vérifie les codecs JNI, titre, nouvelle partie, déplacement, sauvegarde native v23 et restitution de position. Première image 856 ms pour la petite fixture créée pour le test avec 701 noms de cartes ; ce temps ne décrit pas le jeu utilisateur. Les parcours Wine/interface sont qualifiés sur le debug du même source, séparément de ce probe natif R8.
- Signature v2/v3, alignement ZIP 16 KiB et 16 bibliothèques ARM64/x86_64 avec segments ELF LOAD alignés ≥ 16 KiB. Notices présentes ; aucun Game.exe/GamePro.exe embarqué. Lint : 0 erreur, 105 avertissements, dont deux nouvelles suggestions de style/espace allouable. Le diagnostic d’espace utilise volontairement l’espace libre réel du volume, pas une estimation incluant des caches potentiellement libérables.
- Les 34 originaux du manifeste sont inchangés, tailles et SHA-256 vérifiés. Les catalogues existants passent leurs contrôles : état (commande 124), 149 slots/113 lectures admises ; système, 183 identifiants/159 lectures admises. Cette livraison ne prétend pas ajouter la prise en charge de toutes les commandes manquantes du dernier jeu signalé.
- Graphe de navigation actualisé par extraction AST de 26 fichiers : 11 209 nœuds, 31 964 liens, 515 communautés. Trois limites partielles du parseur Kotlin sont consignées ; Gradle compile ces fichiers. Les éléments historiques restent conservés ; les documents et l’ancienne visualisation HTML ne sont pas réextraits. Le graphe ne certifie pas la compatibilité des jeux.

Les assets publics `VALIDATION_SUMMARY.json` et `SHA256SUMS.txt` contiennent le commit source, les contrôles, mesures et empreintes. Les quatre téléchargements publics sont vérifiés après publication. Le client de mises à jour est testé contre le flux GitHub publié : le canal bêta doit sélectionner cette version avec son digest, tandis que le canal stable exclut les préversions.

## Limites

Pas de téléphone ARM64 connecté ni de corpus complet du jeu utilisateur dans cette livraison. Le support Wolf natif reste partiel et l’analyse statique n’est pas une certification de partie complète. Les défauts réels de stockage ou d’accès restent signalés. Aucune cadence minimale permanente de 30/60 FPS n’est annoncée ; le profil, les mesures de rendu et les prochains diagnostics permettent de distinguer le coût d’affichage de l’exécution Wine/Box64.
