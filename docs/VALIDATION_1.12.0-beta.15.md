# Validation Wolf natif — 1.12.0-beta.15

Cette version développe le moteur Android intégré et conserve le parcours Winlator. Les preuves ci-dessous distinguent les formats décodés, la sémantique portable, les pixels et médias Android, et les parcours de jeux originaux. Elles ne certifient ni tous les jeux Wolf, ni un débit constant de 30 ou 60 FPS sur un téléphone.

## Environnement et intégrité

Les tests Android utilisent exclusivement Android Emulator d'Android Studio, ADB, Logcat et l'instrumentation du SDK. L'émulateur de validation est API 36.1, x86_64, image Google officielle, rendu SwiftShader. Les bibliothèques sont compilées pour arm64-v8a et x86_64 ; leur compilation et leur alignement ne constituent pas une exécution sur un appareil ARM64.

Les 34 fichiers du manifeste local de corpus sont relus et vérifiés par taille et SHA-256. Les jeux de référence restent inchangés. Les originaux et les binaires Windows de recherche ne sont inclus ni dans l'APK ni dans les sources publiques. Les fixtures distribuées sont des données et médias synthétiques, des snapshots Astra et des vecteurs de référence accompagnés de leur provenance.

## Tests du moteur et de son hôte Android

Les résultats XML du build final comptent 323 tests JVM du module `wolf-native` et 239 tests JVM de l'application : zéro échec, erreur ou test ignoré. Le build compile aussi les APK debug et R8. Lint rapporte zéro erreur, zéro diagnostic fatal et 106 avertissements.

Le lot Android final `native-beta15-final-qualified-sdk.log` termine **156 tests en 179,572 s**, sans échec ni test ignoré. Il exerce archives, polices, rendu, texte progressif, saisie Android, clavier/manette, fichiers et sauvegardes, audio, synthèse vocale avec les voix installées, vidéo MP4 et Theora/Vorbis, téléchargements, continuations de VM et parcours des jeux originaux. Il inclut 117 vérifications médias : 88 de rendu, 18 de vidéo, 7 d'audio et 4 du lecteur partagé. Les neuf tests WOLFX dédiés font partie de ces nombres.

Le précédent lot de 135 tests, qui précède les extensions WOLFX et fonds/brouillards, est conservé comme historique ; il n'est pas additionné au lot final.

Les vérifications du rendu lisent les pixels des bitmaps. Celles des vidéos contrôlent dimensions, images, progression, recherche temporelle, PCM décodé, pause et conversion de vitesse. Les tests audio distinguent volume, arrêt, reprise et déduplication ; ils ne constituent pas une écoute sur haut-parleur physique. Les tests de saisie utilisent les services d'accessibilité du SDK et les vrais boutons du dialogue, avec des attentes bornées.

Un test de 64 consultations successives d'une sauvegarde absente termine en 299 ms entre les observations de l'interface, sans attendre une frame pour chaque consultation. Il ne mesure pas 64 lectures de gros fichiers existants. Un autre test utilise un vrai fichier validé, contrôle sa réutilisation, les copies défensives, son remplacement atomique, sa récupération `.bak` et le rejet d'une corruption ayant conservé sa taille et sa date de modification.

Le cache Android conserve au plus une enveloppe de 4 Mio, identifiée par inode, périphérique, taille et horodatages nanoseconde quand l'API les fournit. L'API 26 relit le fichier. Le cache portable du snapshot possède sa propre copie et compare le contenu avant réutilisation ; modifier le tableau fourni ne contourne pas le CRC. Une restauration conserve sa validation indépendante.

Le snapshot Astra v22 conserve séparément les fonds et brouillards, leur profil Wolf 2/3, leurs phases signées et les rechargements différés. La restauration valide les ressources et dimensions avant mutation. La migration v21 utilise un fichier réellement produit par l'ancien writer, avec provenance et SHA-256 ; elle ne se contente pas de modifier un numéro d'en-tête.

Les écritures répétées d'un volume identique ne produisent plus un message, une tâche de handler et un appel au lecteur audio à chaque frame. Les modifications réelles restent appliquées dans l'ordre et concernent aussi les voix lancées ensuite.

## Jeux originaux

### Exemples officiels Wolf2.29.6.1 et Wolf3.72.9

Les tests Android utilisent les données et les commandes originales : titre, nouvelle partie, tutoriel, déplacement, menu de jeu, sauvegarde, fermeture de l'Activity, nouveau moteur, Continuer et chargement. Aucun appel d'événement, téléportation, remplacement de base ou injection de variable ne prépare le scénario. Les deux parcours de sauvegarde depuis le menu passent dans le lot final de 156 tests.

Le parcours portable couvre également un combat des exemples officiels. Il est distinct des parcours Android : la réussite d'un interpréteur JVM ou de l'admission ne prouve pas le rendu d'un combat sur le téléphone.

### The Crooked Man v4

L'archive originale possède le SHA-256 `c081098247a3efc2cc8b40aff33ef776a853570814b5836fb7eee99ea11aca53`. Le parcours Android final passe : choix de l'anglais, titre, avertissement, introduction, dialogues, début de partie, déplacement, sauvegarde/restauration depuis le menu Astra, **Save depuis le menu original du jeu, fermeture de l'Activity, nouveau moteur, Load au titre et restauration du héros**. La sauvegarde du jeu contient un snapshot complet dans une enveloppe de 228 431 octets. Les polices originales sont réellement chargées, dont Aozora Mincho pour l'anglais.

Le parcours portable supplémentaire utilise le menu original pour Save slot1, puis crée un moteur neuf, lit la langue persistée dans slot99, sélectionne Load au titre et charge slot1. Le héros et les bases du jeu sont restaurés, puis le déplacement fonctionne. Le fichier est lu comme snapshot complet et valeurs partielles ; Common220 écrit explicitement variable271=0 avec222 avant Load. Une comparaison correcte tient compte de cette écriture de l'auteur au lieu d'exiger que son fichier reste inchangé.

Le fichier de preuve Android termine avec `result=PASS` et `originalGameMenuSaveRestartLoad=true`. Les attentes du scénario, confirmations et captures d'écran font partie de sa durée ; celle-ci n'est pas une mesure du démarrage du moteur. Le début du jeu et ces opérations ne certifient pas toutes les scènes, langues ou branches de son scénario. Dragon Blood n'est pas disponible dans le corpus local ; les anciens diagnostics Wine ne démontrent pas son fonctionnement dans ce moteur.

## Qualification des contrats et admission

Les contrats portés sont reliés aux exports REA des exécutables immuables et aux sources primaires épinglées. Les tableaux de mouvement, effets et filtres ainsi que les horloges du texte possèdent des vecteurs C++ indépendants. Le parser temporel est confronté à4608 étapes des profils Wolf2.29 et Wolf3.30/3.69.

L'audit des commandes examine 96 853 commandes dans trois corpus au checkpoint documenté. Il est séparé de `WolfRuntime.load(source).inspectCapabilities(includeAllMaps=true)`, qui inspecte aussi les références système, modes, routes, options et ressources. Cette API publique, exécutée sur le JAR Gradle final, inspecte les 4, 4 et 125 cartes : zéro diagnostic et `inspectedAllMaps=true` dans les trois cas. Cela reste une admission statique, pas 133 cartes jouées. Le [registre des corpus](wolf-native/CORPUS_LEDGER_2026-10-10.md) conserve les checkpoints et leur périmètre.

Les fonds et brouillards sont confrontés à 740 vecteurs natifs indépendants : 394 Wolf 2 et 346 Wolf 3, avec phases négatives, zooms entiers, découpe et différences d'opacité/composition. Les ressources WOLFX disposent de 192 vecteurs de format et de 16 comparaisons de médias avec l'upstream C++ épinglé. La lecture Android des images, masques, polices, sons et vidéos à clé vide est vérifiée séparément. Les clés Pro personnalisées ou dépendantes de variables et les cartes/textes/CSV WOLFX ne sont pas admis ; un alias protégé ne laisse pas reprendre silencieusement le fichier clair.

La [couverture native](wolf-native/RUNTIME_COVERAGE.md) énumère les contrats exécutés et les extensions non qualifiées. Les sauvegardes Windows, replay2202, certains profils de protection, certaines transformations d'images et certains formats vidéo conservent leurs limites explicites. Winlator reste disponible pour ces jeux et leurs anciennes sauvegardes.

## Historique des corrections de validation

Les journaux précédents ne sont pas effacés ni additionnés aux résultats finaux. Une ancienne fixture demandait une image visible avec pattern0, alors que cette valeur cache réellement l'image ; elle a été corrigée. Un test de déplacement après sauvegarde insistait contre un mur et a été remplacé par le retour sur le chemin déjà parcouru.

Une ancienne exécution SDK a été arrêtée volontairement avec ADB après une attente globale d'Espresso pendant la fermeture du clavier. La capture montrait le texte réellement saisi et aucune preuve de crash applicatif. Ce batch interrompu est marqué incomplet avec une note d'intervention ; le nouveau test utilise des actions SDK bornées et passe. Un code de sortie du processus de commande ne suffit pas à certifier un batch : chaque résultat d'instrumentation et son code final sont vérifiés.

Deux lots plus récents ont également été interrompus pendant une attente d'ActivityScenario/Espresso. Le harnais final observe le cycle de vie et clique les vrais dialogues via le SDK, sans attendre une file UI globalement inactive. Les références pixels déclarent explicitement le filtrage nearest ; l'inversion utilise le code natif 24 ; les tests vidéo commencent leur mesure après le démarrage de l'horloge et attendent une session AudioTrack réelle. Ces corrections concernent les tests et ne modifient pas le moteur pour faire passer les assertions. Les libellés de boutons transformés en majuscules sont comparés sans dépendre de leur casse.

Une tentative externe de `uiautomator dump` pendant l'instrumentation a échoué parce que celle-ci possédait déjà UiAutomation. L'erreur du processus CLI n'est pas attribuée à Astra. Les journaux des lots interrompus ou échoués sont conservés et ne sont pas additionnés aux tests du lot final.

Le contrôle externe de la sauvegarde du tiers a également été corrigé : une nouvelle source doit charger ses métadonnées avant de calculer l'identité attendue par le store, comme le fait l'hôte. La vérification d'identité n'a pas été désactivée. Après correction du harnais, les 156 tests passent dans une même exécution complète.

## Artifacts de release

L'APK de distribution est minifié par R8, signé avec le certificat historique d'Astra et accompagné de ses sommes SHA-256 et des sources. Une sonde SDK indépendante contrôle la version non debuggable, l'ABI JNI des codecs et des parcours natifs à partir des données officielles ; ses résultats et ceux du canal de mise à jour public sont consignés dans le résumé final. Le contrôle de chargement R8 conserve une copie exacte d'un checkpoint créé par l'application, déplace le héros et observe sa position différente par une nouvelle sauvegarde, remet le checkpoint privé comme un backup, puis exige que le vrai menu de chargement restaure la première position. Il n'injecte aucune variable ni aucun état directement dans la VM et ne modifie aucun fichier original du jeu.

Les conditions de licence des codecs et des lecteurs sont embarquées dans l'application. Les mesures sur cet émulateur ne remplacent pas un test du Samsung ARM64 de l'utilisateur ou une mesure de fluidité sur son jeu.

Le graphe local est actualisé sur 143 fichiers de code natif et conserve les autres nœuds historiques : 10 501 nœuds, 30 588 arêtes et 491 communautés. Cette extraction AST reste partielle pour 20 fichiers Kotlin ; les builds Kotlin passent indépendamment. L'export HTML n'est pas régénéré et aucune extraction sémantique complète n'est revendiquée. Les 16 TSV de référence conservent leurs octets et leurs SHA-256 dans Git, sans normalisation des fins de ligne.
