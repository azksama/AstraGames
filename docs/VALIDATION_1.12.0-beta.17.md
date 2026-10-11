# Validation Wolf — 1.12.0-beta.17

Cette version autorise les captures Android pendant les sessions natives et Winlator, renouvelle l’écran de chargement partagé, et ajoute des boutons personnalisés et des presets de contrôles. Elle traite aussi les deux diagnostics de la bêta 16 transmis pendant ce travail. Les fichiers originaux des jeux Seris et Dragon Blood ne sont pas disponibles localement ; une partie de ces jeux sur le Samsung de l’utilisateur n’est pas revendiquée.

## Fonctionnement livré

Les deux activités Wolf ne positionnent plus `FLAG_SECURE`. Le test SDK de lancement natif exige ce résultat avant de prendre sa capture, sans retirer le flag pour contourner la vérification. Le test du résultat d’erreur Windows vérifie également la fenêtre réelle. Ces contrôles concernent les hôtes de jeu Wolf.

Le chargement affiche la vraie étape 1..8, son libellé et les durées écoulées, avec une action diagnostic et une action annulation de 48 dp minimum. La colonne défile sur les fenêtres courtes. La phase courante pulse ; l’animation est arrêtée quand la vue disparaît et désactivée avec les animations Android. Il n’y a pas de pourcentage inventé. Les commandes restent masquées jusqu’à la première image du jeu.

L’overlay partagé ajoute création, modification et suppression de boutons nommés, recherche dans le catalogue de touches physiques et gestion de presets nommés. Les dialogues reprennent les surfaces, arrondis et polices d’Astra ; les actions des presets sont des boutons séparés, avec défilement sur les fenêtres courtes. Les touches proposées ont chacune un scan DirectInput valide et unique ; F8 et les touches étendues font l’objet d’assertions distinctes. Les boutons personnalisés conservent les positions normalisées dans chaque orientation. Un preset contient uniquement les contrôles, leurs affectations, positions, taille et opacité : moteur, fichiers, résolution et sauvegardes du jeu destinataire restent inchangés. Les limites sont de 24 boutons personnalisés et 32 presets locaux. Les entrées maintenues sont libérées à l’édition, au chargement, à la suppression et au détachement ; deux boutons sur une même touche conservent le comptage des appuis.

Le rapport Seris bloque la commande 123 sur un mode d’entrée. L’analyse statique [REA du lecteur Wolf 3](wolf-native/INPUT_EDGES_REA.md) étaye la correction des modes 0/1 physiques et des changements 2/3 logiques/clavier/manette. Les variantes non qualifiées restent refusées ; les snapshots de sauvegarde ne changent pas. Les paramètres exacts du common Seris ne sont pas fournis par le rapport.

Dragon Blood attend sa première image dans Wine avec le profil Rapide. Le rapport contient `rtl_rb_tree_put failed` lors de l’enregistrement de modules. Le diagnostic identifie désormais ce message et propose le profil Stable comme piste ; l’écran d’échec permet cette relance. C’est une hypothèse circonscrite, pas une causalité démontrée ni un résultat de relance mesuré sur ce jeu.

## Preuves exécutées

Les tests emploient uniquement Android Studio Emulator, ADB, Logcat et l’instrumentation Android SDK. Aucun outil ARTEMIS n’est utilisé. Les fixtures synthétiques sont identifiées comme telles et ne réécrivent pas les événements des exemples officiels.

- **330 tests JVM moteur + 246 tests JVM application**, sans échec, erreur ou test ignoré. Les nouveaux contrats couvrent les modes d’entrée, les presets limités aux contrôles, les touches proposées et le diagnostic Wine.
- **25 tests Android ciblés**, tous réussis : hôte natif, commandes physiques, paramètres et overlay, éditeur complet et résultat d’erreur Windows. Le parcours réel d’accessibilité crée un bouton Potion/F8, l’enregistre dans un preset, le supprime et charge la disposition dans un autre jeu. Le lecteur natif 123 reçoit séparément F8 depuis le bouton réel.
- Matrice visuelle du chargement, de la recherche de touche, de l’état vide des presets et de l’éditeur sur téléphone portrait, paysage avec texte 130 %, et tablette. Les trois tests de l’éditeur sont rejoués dans chaque format ; ces répétitions ne sont pas comptées comme de nouvelles méthodes. Le centre vide des captures de l’éditeur est un hôte de test de contrôles, pas une image de jeu échouée. La revue indépendante a demandé deux corrections visuelles ; son verdict final `ship` les score toutes deux résolues, sans prétendre réévaluer toute l’application. `DESIGN.md` est préservé.
- APK R8 signé, non debuggable : jeu synthétique à table de 701 noms, titre, nouvelle partie, déplacement et restauration de la position par sauvegarde Astra v22. Les codecs JNI produisent des images Theora et du PCM Vorbis non silencieux. Les mesures exactes de ce parcours figurent dans `VALIDATION_SUMMARY.json` ; elles ne mesurent pas le démarrage de Seris.
- Lint : aucune erreur ni fatal. Signature historique v2/v3, alignement ZIP 16 Kio, 16 bibliothèques ARM64/x86_64 avec segments ELF alignés à 16 Kio minimum, notices embarquées et absence des exécutables de jeu originaux vérifiés.
- Les 34 originaux du manifeste conservent leurs tailles et SHA-256. Le graphe est actualisé par extraction AST des fichiers de code concernés ; les limites d’extraction Kotlin partielles sont enregistrées. Il ne constitue pas une qualification sémantique supplémentaire du moteur.

Les compteurs, les SHA-256, le commit des sources et la qualification du canal public de mise à jour sont consignés avec l’APK dans les assets de release `VALIDATION_SUMMARY.json` et `SHA256SUMS.txt`. Un essai SDK ayant visé un nom de classe inexistant est conservé séparément dans la recherche ; il n’est pas présenté comme une réussite. Les lots finaux sélectionnent uniquement les classes réelles et n’ignorent aucun test.

## Limites

La qualification Android utilise API 36.1/x86_64 en émulation. Elle ne mesure ni les FPS du téléphone ARM64 ni une session des jeux signalés. Le secours Winlator reste disponible pour les profils et fonctions natifs non qualifiés. Les sauvegardes natives et Windows restent séparées. La [couverture générale](wolf-native/RUNTIME_COVERAGE.md) et les résultats historiques des bêta 15/16 restent distincts de cette livraison.
