# Astra

Astra est une application Android native qui indexe plusieurs dossiers de jeux, détecte leurs moteurs et les lance avec JoiPlay, avec MKXP-Z intégré pour RPG Maker XP/VX/VX Ace, ou avec Winlator, Wine et Box64 intégrés pour Wolf RPG. Le projet est écrit en Kotlin, Jetpack Compose et Material 3.

La préversion **1.12.0-beta.24 (code 52)** réunit MKXP-Z intégré, les corrections
UI/UX et de stockage, ainsi que les protections de restauration Wolf et d’intégrité
des archives de mods. Les [notes de version](docs/RELEASE_1.12.0-beta.24.md)
présentent les changements ; le [bilan de validation](docs/VALIDATION_1.12.0-beta.24.md)
identifie l’APK concerné, les contrôles effectués et leurs limites.

Le moteur Wolf natif expérimental et son analyseur de compatibilité ont été retirés.
Les anciens choix Automatique/Natif utilisent Winlator. Les sauvegardes natives déjà
créées restent conservées et exportables depuis les outils du jeu ; elles ne sont pas
converties en sauvegardes Windows.

## Fonctions incluses

- onboarding et sélection de dossiers avec `ACTION_OPEN_DOCUMENT_TREE` ;
- onboarding guidé avec choix de langue, import de tags, dossier de jeux et vérification des runtimes JoiPlay ;
- interface disponible en français, anglais (par défaut), espagnol, russe, allemand, chinois et japonais, avec conservation des titres, tags et recherches Unicode ;
- permissions SAF persistantes ; accès à tous les fichiers facultatif pour jouer directement depuis un dossier local avec Winlator ;
- sources multiples, activation, suppression, scan individuel ou global ;
- scan ciblé depuis **Paramètres → Sources et scan → Sous-dossier…** : navigation dans une source déjà autorisée, puis analyse du dossier choisi avec les options de récursivité de la source. Ce scan ajoute ou retrouve les jeux sans marquer les autres absents ; le scan complet reste nécessaire pour vérifier les suppressions ;
- scan récursif hors thread principal (garde-fous de 128 niveaux et 100 000 dossiers), avec arrêt et remontée dès qu'un dossier contient un exécutable, et écran de progression indiquant source, chemin, profondeur, dossiers et jeux ;
- détection RPG Maker XP/VX/VX Ace/MV/MZ, Ren'Py, HTML5, Tyrano, Construct, Twine, Electron, Wolf RPG et Godot 3/4 (packs PCK, projets et packs intégrés aux exécutables) ;
- exécution RGSS 1/2/3 avec MKXP-Z intégré, commandes tactiles et accessibles, copie de travail privée et provenance source/privée dans les outils de sauvegarde ; qualification par fixtures, sans garantie pour tous les jeux ;
- moteur Wolf intégré : Wine, Box64 sur ARM64, affichage et commandes dans Astra, accès SAF à la demande ou copie de secours et synchronisation prudente des sauvegardes ;
- Wolf RPG exécuté uniquement avec Winlator ; anciennes sauvegardes natives conservées en lecture seule et exportables ;
- fingerprint, reconnaissance de déplacement, doublons et jeux manquants ;
- rapport détaillé et persistant après chaque scan : ajoutés, actualisés, déjà connus, déplacés, manquants, ignorés et erreurs avec chemin et raison ;
- Room avec index FTS4, historique des scans et sessions de lancement ;
- bibliothèque en grille réglable sur 2, 3 ou 4 colonnes ou en liste, favoris, sélection multiple et actions rapides ;
- recherche accessible depuis les champs de l’accueil et de Jeux ou par glissement vers le bas en haut de liste, avec moteur en liste et multi-tags regroupés par catégorie ;
- création, édition, suppression, tri et classement multiple des tags par catégories ;
- sélection rapide et groupée des tags pour chaque jeu, sans affectation globale implicite ;
- dossiers et sous-dossiers Astra virtuels navigables, renommables et supprimables sans supprimer les jeux ;
- panneau de filtres compact par moteur, source, dossier Astra, dossier parent système réel, tags, état et tri, sans exposer les sous-dossiers comme entrées séparées ;
- collections intelligentes intégrées et personnalisables avec règles moteur, tag recherchable et trié alphabétiquement, dossier, favori, jaquette, disponibilité, dates et durée de jeu ; éditeur avec aperçu réel des résultats et choix de toutes les règles ou au moins une ;
- résolution guidée des doublons : comparaison côte à côte, choix du principal, fusion des métadonnées et de l’historique, récupération des sauvegardes et stratégie de conflits ;
- suivi du temps de jeu par sessions et affichage de la durée cumulée ;
- gestionnaire de runtimes JoiPlay avec détection dynamique des variantes installées, catalogue officiel mis en cache sept jours et notification des mises à jour ;
- détection des plugins Ren’Py versionnés avec vérification du certificat officiel ; moteur choisi dans un profil reflété dans la bibliothèque et la fiche ;
- moteurs Godot 3 et Godot 4 disponibles dans les profils, filtres et collections ; lancement via le plugin JoiPlay de la même génération, avec association des plugins installés aux entrées du catalogue sans doublons ;
- lancement Godot depuis un dossier contenant un seul pack `.pck` séparé ; les projets sources et les packs intégrés aux exécutables sont identifiés, avec un diagnostic demandant l’export ou l’extraction du pack. La compatibilité des jeux reste celle des plugins JoiPlay installés ;
- suppression d'un jeu avec exclusion persistante des scans, restauration depuis les paramètres et suppression physique optionnelle explicitement confirmée ;
- sauvegarde et restauration chiffrée du catalogue, des profils, des jaquettes, des journaux de sauvegardes/mods et des sauvegardes privées prises en charge pour MKXP-Z/Wolf, avec contrôles de propriété ; la clé reste liée à l’installation Android ;
- choix automatique et manuel de jaquettes dans le moteur sélectionné (Yandex, Google, Qwant, Bing, DuckDuckGo ou Ecosia), sans filtre de contenu ajouté par Astra, sélection de l’image affichée, ouverture navigateur, choix local et recadrage libre ;
- enrichissement silencieux des nouveaux jeux via VNDB (sans importer ses tags), puis recherche de la page de métadonnées avec le moteur sélectionné et URL canonique conservée dans la fiche ;
- choix entre plusieurs sources de métadonnées, avec leurs liens conservés séparément ;
- import de métadonnées pendant l'ajout ou l'édition : recherche automatique ou lien, aperçu des valeurs actuelles et proposées, sélection des champs, tags et jaquette avant validation ;
- assistant séquentiel de configuration des nouveaux jeux après chaque scan, avec actions fixes protégées du clavier et des barres système ;
- édition complète des fiches et date du dernier lancement ;
- ajout textuel de tags par virgules ou crochets pendant la configuration et l’édition, avec réutilisation automatique des tags existants ;
- interface Astra : polices Geist/Inter embarquées, affiches panoramiques, listes compactes et réglages en lignes ;
- palette Astra par défaut, personnalisable avec le curseur de teinte dans Apparence : fond `#09090f`, cartes et menu `#191e29`, bordures `#2b3242`, actions `#302147`, textes secondaires `#c9b7ff` et titres blancs ;
- flou des jaquettes désactivable, automatique au démarrage ou manuel, avec bascule rapide depuis Jeux ;
- navigation flottante élargie à quatre entrées (Accueil, Jeux, Collections, Paramètres), sans bande opaque derrière la pilule et maintenue en place sous le clavier Android ;
- en-têtes qui défilent avec les pages, avec recherche ancrée en haut de Jeux après leur disparition ;
- accueil avec icône Astra/GAMES, dernière partie en affiche panoramique, bouton de reprise et dix derniers jeux lancés dans une grille à défilement vertical ; bibliothèque complète accessible par Jeux ou Tout voir ;
- accès Historique et Mises à jour dans la section Activité de l’accueil ;
- navigation latérale sur grand écran et en paysage, panneaux côte à côte adaptés à la maquette, largeur de lecture limitée et commandes accessibles avec texte agrandi ;
- volets des paramètres fermés par défaut et verrouillage aux couleurs d’Astra avec le vrai logo et saisie du code masquée ;
- Baseline Profile embarqué et module de génération Macrobenchmark pour accélérer le démarrage et les parcours principaux ;
- copie rapide du nom d’une jaquette en touchant son libellé et raccourci vers le dossier de sauvegarde d’un jeu ;
- verrouillage biométrique et code chiffré, historique des sessions, bouton Outils, éditeur de sauvegardes et gestionnaire de mods ;
- retour à la page et au brouillon précédents après déverrouillage, sans afficher leur contenu pendant le verrouillage ;
- option **Paramètres → Optimisation de la batterie** ouvrant la demande Android pour Astra, avec état actualisé au retour ;
- traduction locale des textes reconnus RPG Maker MV/MZ/XP/VX/VX Ace, Wolf et Ren’Py avec Google Translate (ML Kit), sans compte ni clé API, cache réutilisable et restauration des originaux ;
- analyse des textes déclenchée uniquement par l’utilisateur, avec résultat persistant et réutilisation des extractions lorsque les fichiers restent identiques ;
- mises à jour d’Astra depuis les releases GitHub publiques, vérification et téléchargement périodiques, contrôle SHA-256 et signature APK avant confirmation Android ;
- mode manuel de traduction : export JSON pour l’IA de votre choix, commandes protégées par marqueurs, import validé et restauration des originaux ;
- payload et Intent JoiPlay sans appel à `ShortcutActivity` ;
- diagnostic de compatibilité par jeu et profils de lancement personnalisables (moteur, dossier, fichier d'entrée, arguments ou application externe) avec test direct ;
- scan au lancement et worker périodique prêt à être planifié ;
- abstraction `CoverProvider` et `MToolLauncher` pour les évolutions.

## Jouer à RPG Maker XP, VX et VX Ace avec MKXP-Z

Dans la fiche du jeu, ouvrir **Compatibilité → Profil de lancement**, choisir
**Astra · MKXP-Z (prototype)**, enregistrer puis toucher **Jouer**. JoiPlay reste
disponible comme autre choix de lancement.

Le premier lancement crée une copie privée du jeu : prévoir l’espace nécessaire.
MKXP-Z utilise ensuite cette copie pour jouer et sauvegarder. Les outils distinguent
la **Copie Astra active (MKXP-Z)** du **Dossier source** ; éditer une sauvegarde
source ne modifie pas celle utilisée par MKXP-Z. Il n’y a pas de synchronisation
automatique ni de mise à jour automatique de cette copie depuis le dossier source.
Enregistrer la partie dans le jeu avant de quitter.

Ce prototype a été exercé avec des jeux de test RGSS 1/2/3. Aucun RTP n’est fourni ;
les scripts ou extensions Windows, Win32API, MIDI, vidéos et archives chiffrées
ne sont pas qualifiés. La compatibilité avec tous les jeux commerciaux n’est pas
établie. Les sources natives sont référencées dans
[sources-lock.json](scripts/mkxpz/sources-lock.json), avec les
[notices MKXP-Z](mkxpz-runtime/src/main/assets/licenses/mkxpz/).

## Jouer à Wolf RPG dans Astra

Ajouter le dossier du jeu Windows décompressé puis lancer son scan. Astra détecte
`Game.exe` avec `Data.wolf` ou `Data/BasicData/Game.dat`. Dans la fiche, **Jouer**
utilise le profil **Astra · Wolf intégré** ; un profil externe explicitement choisi
reste externe. Le premier lancement télécharge et vérifie les composants Wine et
prépare un préfixe Windows propre au jeu.

**Outils → Réglages Wolf RPG → Fichiers du jeu** choisit le dossier d’origine
lorsqu’il est accessible, sinon un montage SAF à la demande pour les fournisseurs
compatibles, puis une copie privée de secours. L’accès à tous les fichiers est
facultatif sur Android 11+. Une copie privée nécessite de l’espace supplémentaire.
Les écritures SAF passent par un journal avant publication vérifiée.

Les commandes à l’écran, le clavier et les manettes permettent de jouer.
**Menu → Modifier les touches** règle touches, combinaisons, séquences, positions,
taille et opacité ; les presets sont importables et exportables en JSON.
**Menu → Réglages du jeu** propose les profils, la définition, le cadrage et les
options tactiles. **Menu → Cheats et variables** ouvre le [guide des valeurs](docs/wolf-cheats.md).

Enregistrer dans le jeu avant **Quitter**. Une fermeture normale synchronise les
fichiers modifiés lorsque la source correspond encore à sa version de référence.
Un conflit conserve la copie privée et propose son export ZIP. **Outils → Dossier
des sauvegardes** permet également d’exporter les données utilisateur du préfixe
Windows. Les archives du catalogue ne contiennent pas les copies complètes des
jeux ni leurs préfixes : conserver séparément ces dossiers.

Pour une mise à jour externe du jeu ou un mod installé par un autre outil, utiliser
**Outils → Actualiser les fichiers du jeu**, puis relancer. Les mods gérés dans
Astra déclenchent cette actualisation. **Diagnostic du lancement**, puis
**Paramètres → Diagnostic Wolf RPG**, donnent accès aux étapes et erreurs du moteur.
Le partage d’un diagnostic est volontaire et peut inclure des noms et chemins.

Le [guide Wolf](docs/wolf-windows-runtime.md) détaille les commandes, les sauvegardes,
les diagnostics et les sources ; le [guide SAF](docs/wolf-saf-on-demand.md) décrit
les garanties et limites des fournisseurs de documents.

## Jaquettes et métadonnées

Les recherches d’images et de métadonnées utilisent le moteur choisi dans les paramètres (Yandex, Google, Qwant, Bing, DuckDuckGo ou Ecosia). Aucun paramètre de filtrage de contenu n’est ajouté par Astra. Les résultats d’images sont recherchés automatiquement à l’ouverture, et un bouton ouvre la même recherche dans le navigateur système. L’utilisateur peut aussi afficher les résultats dans le WebView intégré. Les liens de métadonnées sont validés et normalisés en HTTPS avant leur utilisation. VNDB complète silencieusement jaquette, description et développeur lors de l’ajout, sans importer ses tags ; les fiches F95Zone ou Ryuugames liées complètent les champs manquants et proposent des images de repli. Un résultat F95Zone trouvé par recherche doit correspondre au titre avant tout enrichissement automatique. Le bouton des paramètres relance ce traitement sur les fiches incomplètes, en conservant les valeurs et jaquettes déjà choisies.

L’import propose **Trouver automatiquement**, **Lire cette fiche** ou **Choisir dans le navigateur**, puis un aperçu unique. Seuls les champs cochés remplacent les informations actuelles ; fermer l’aperçu ne modifie pas la fiche. Les tags actuels sont conservés et la jaquette reste facultative. Le téléchargement précède l’enregistrement ; un échec laisse la sélection ouverte. Le recadrage suit l’import des informations.

L’éditeur plein écran regroupe informations, description, tags et liens, avec deux panneaux sur écran large. Il conserve les saisies lorsqu’un import ou le choix d’une jaquette est ouvert. Les liens invalides sont signalés avant enregistrement et fermer un brouillon demande de confirmer son abandon.

F95Zone peut masquer ses pièces jointes aux visiteurs. Astra reconnaît cette restriction et les sessions enregistrées que le serveur ne reconnaît plus. **Se connecter à F95Zone**, puis **Utiliser cette session**, relit la fiche ; **Relire la fiche** permet aussi de réessayer. Les cookies sont partagés avec les aperçus et téléchargements uniquement sur les hôtes HTTPS F95Zone, y compris après redirection. Une session peut expirer même si son nom est encore enregistré.

Les [vérifications du parcours métadonnées](docs/VALIDATION_METADATA_2026-11-08.md) précisent les tests et leurs limites.


## Architecture

```text
core/        modèles, accès fichiers, recherche, métadonnées
data/local  Room, DAO et entités
data/scanner détection, normalisation, fingerprint et scan SAF
data/repository orchestration des données
data/saves   codecs, découverte SAF, écriture vérifiée et backups
data/mods    import ZIP, installation et restauration journalisées
data/backup  archives chiffrées du catalogue
launcher/    JoiPlay, profils et sélection du moteur effectif
mkxpz/       exécution RGSS, copie privée, verrou et pont SDL
windows/     moteur intégré Winlator / Wine / Box64 et sauvegardes Windows
translation/ ML Kit, lecteurs de textes par moteur, cache et restauration journalisée
updates/     releases GitHub, téléchargement vérifié et installation Android
settings/    DataStore
worker/      WorkManager
ui/          navigation, écrans par domaine, état et contrôleur des outils
```

L'injection est volontairement explicite via `AppContainer` : elle garde le démarrage lisible et permet de remplacer les dépendances dans les tests sans framework supplémentaire.

Les modules `windows-runtime` et `mkxpz-runtime` fournissent les ponts natifs,
bibliothèques et notices ; `baselineprofile` contient le générateur de profil.

## Compiler

Prérequis : JDK 17 ou supérieur compatible avec Gradle et Android SDK 36.
Le NDK 29.0.14206865 et CMake 3.22.1 sont fixés dans les fichiers Gradle ;
AGP sélectionne ses Build Tools. Les contrôles de signature et d'alignement de
cette préversion utilisent les outils SDK 36.1.0. Voir les
[instructions de construction](docs/BUILD_1.12.0-beta.24.md).

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

Les tests locaux couvrent la normalisation, les signatures moteur imbriquées, le fingerprint, les doublons/déplacements, le parseur FTS, les modes multi-tags, les runtimes Ren'Py, les parseurs de métadonnées communautaires, de VNDB et du catalogue JoiPlay, ainsi que le payload JoiPlay.

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Les tests Wolf utilisant le corpus officiel externe sont optionnels dans un clone simple.
Pour les exécuter depuis un worktree, définir `ASTRA_WOLF_CORPUS` sur le dossier
`build/wolf-research` du corpus existant, utilisé uniquement en lecture.
L’exemple suppose que ce corpus existe dans le clone courant ; depuis un autre
worktree, adapter le chemin vers le même corpus :

```powershell
$env:ASTRA_WOLF_CORPUS = (Resolve-Path '.\build\wolf-research').Path
.\gradlew.bat :app:testDebugUnitTest
```

Ce dossier doit contenir `sample22961`, `sample3729` et `wolf-data-proof`.
La référence indépendante `wolf-save-reference-real-w2-corrected.json` doit rester
dans le dossier frère `mtool-implementation`. Les fixtures ne sont ni copiées dans
le dépôt ni embarquées dans l’APK. Sans corpus configuré/disponible, les tests
concernés sont ignorés ; avec `ASTRA_WOLF_CORPUS` défini, une fixture manquante fait
échouer le test pour éviter une qualification incomplète silencieuse.

Les tests instrumentés vérifient Room/FTS, les archives du catalogue, les sauvegardes et les mods, sur fichiers locaux et URI SAF imbriquées. Les exécuter sur un émulateur Android SDK dédié aux fixtures :

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Le module Baseline Profile se compile sans lancer de test sur l’appareil :

```powershell
.\gradlew.bat :baselineprofile:assembleBenchmarkRelease :baselineprofile:assembleNonMinifiedRelease
```

La génération réelle reste volontairement explicite avec `:app:generateBaselineProfile`, car elle nécessite un appareil Android 13+ compatible ou un appareil géré dédié.

## Sauvegardes de jeux et mods

L’éditeur reconnaît les sauvegardes Ren’Py ZIP contenant `log` (pickle), les anciens conteneurs bruts/zlib, RPG Maker MV (LZ-String), MZ (base64/zlib), RGSS XP/VX/VX Ace (Marshal, y compris plusieurs flux) et certaines sauvegardes Wolf. Pour Wolf, les cellules numériques des structures reconnues sont modifiables ; les textes et banques inconnues restent en lecture seule. Le format récent de sauvegarde Wolf 3.729 est actuellement refusé. Il conserve le conteneur d’origine, crée une copie de sécurité avant écriture et relit le résultat. Une sauvegarde modifiée depuis l’ouverture de l’éditeur doit être rechargée. Les structures non prises en charge sont refusées avant écriture ; la compatibilité n’est pas universelle pour les jeux utilisant un format personnalisé. La [validation de cette préversion](docs/VALIDATION_1.12.0-beta.24.md) précise les formats et scénarios effectivement exercés.

Après modification, Ren’Py peut demander d’autoriser le chargement de la sauvegarde. Les métadonnées et la capture restent dans l’archive ; la signature du contenu original n’est pas réutilisée. Les tests utilisent des fixtures synthétiques générées par CPython, avec des références partagées, des cycles et des caractères Unicode. Vérification indépendante après les tests Gradle :

```powershell
python -X utf8 scripts/verify-renpy-fixtures.py
```

Depuis **Outils → Mods**, sélectionner un dépôt, importer le ZIP puis installer le mod du moteur concerné. Le dépôt contient des sous-dossiers `RenPy`, `RPGMakerMV`, `RPGMakerMZ`, etc. Une archive peut fournir ce manifeste à sa racine :

```json
{
  "formatVersion": 1,
  "id": "example.mod",
  "name": "Example mod",
  "engines": ["RenPy"],
  "installMode": "OVERLAY",
  "target": "game",
  "filesRoot": "files"
}
```

`files/` contient les fichiers à installer, relativement à `target`. `COPY` accepte uniquement les nouveaux fichiers ; `OVERLAY` ajoute ou remplace avec backup (`REPLACE` reste un alias historique). Sans manifeste, le moteur du jeu sélectionné et l’arborescence du ZIP déterminent la destination. Un dossier d’emballage est retiré sans écraser les niveaux significatifs `game`, `www` ou `js`.

L’import ZIP est limité à 4 000 entrées et 200 Mio décompressés. La désinstallation vérifie les fichiers avant de restaurer les originaux. Un conflit avec une modification ultérieure est signalé ; une installation interrompue conserve son journal et propose **Restaurer**. Les mods RPG Maker doivent inclure leur configuration d’activation lorsque nécessaire : copier un plugin JavaScript isolé ne le déclare pas automatiquement dans `js/plugins.js`.

Les archives du catalogue `.astra` utilisent la clé Android Keystore de l’installation actuelle. Elles ne constituent pas une migration vers un autre téléphone ou après désinstallation de l’app. Les anciens ZIP non chiffrés restent importables (schémas 5 à 9). Elles peuvent contenir le catalogue, les profils, les jaquettes, les sauvegardes privées MKXP-Z/Wolf prises en charge et les originaux nécessaires à la désinstallation des mods. Elles ne sauvegardent pas les copies complètes des jeux, leurs préfixes Windows ou les permissions SAF : conserver séparément les dossiers des jeux.

La beta.24 vérifie le propriétaire de chaque sauvegarde et l’intégrité des originaux
de mods référencés. Une archive dont un original de mod manque ou a changé est
refusée avant de remplacer les données existantes ; l’export refuse aussi les
originaux absents ou endommagés. Les restes locaux non référencés ne sont pas exportés.
Une sauvegarde Wolf restaurée reste protégée au prochain lancement ou à
l’actualisation des ressources, même si elle était inchangée lors de l’export.
Si la source a évolué, le conflit conserve la copie restaurée au lieu de l’écraser.

Le [format des archives](docs/BACKUP_FORMAT.md) décrit le chiffrement, les limites
et la récupération après erreur. Les [notes de version](docs/RELEASE_1.12.0-beta.24.md)
et le [bilan public de validation](docs/VALIDATION_1.12.0-beta.24.md) précisent
les corrections et les contrôles associés à cette préversion.

## Traduction des jeux

Le mode **Fichier pour IA** permet d’exporter tous les textes standards pris en charge, avec leur contexte, puis d’importer le fichier traduit. Il ne nécessite ni modèle ML Kit ni compte connecté dans Astra. Seuls les champs `translation` doivent changer ; les marqueurs `⟦ASTRA_…⟧` protègent les commandes RPGM. Le jeu doit rester dans la même version entre export et import. Voir le [guide complet et le prompt prêt à utiliser](docs/MANUAL_TRANSLATION.md).

Depuis **Fiche du jeu → Outils → Traduire le jeu**, toucher **Analyser les textes**, choisir la langue source et la langue cible, fermer le jeu puis toucher **Traduire avec Google**. Google Translate (ML Kit) télécharge les modèles nécessaires au premier usage, puis traduit sur l’appareil sans compte ni clé API. Le téléchargement utilise uniquement le Wi-Fi par défaut ; cette option peut être désactivée. Une fois les modèles disponibles, la traduction fonctionne hors connexion. Garder Astra ouvert pendant le traitement ; après un arrêt, les fragments déjà traduits sont réutilisés au prochain essai.

L’ouverture de l’écran ne lance aucune extraction. Une analyse terminée est conservée dans le stockage privé de l’application et réaffichée à la prochaine ouverture. **Actualiser l’analyse** permet de la relancer explicitement. Avant traduction, export ou import, les empreintes des fichiers sont contrôlées et les fichiers modifiés sont relus ; une analyse interrompue ou invalide ne remplace pas le dernier résultat complet.

Le module lit les JSON standards MV/MZ dans `data` ou `www/data`, les données Marshal standards XP/VX/VX Ace, les dialogues et choix des cartes et événements communs Wolf décompressés, et les dialogues/choix Ren’Py dans les sources `.rpy`. Les commandes de mise en forme et variables comme `\N[1]`, `\V[2]`, `\C[3]`, `%1`, ainsi que les interpolations et balises Ren’Py, sont conservées. Les textes identiques sont traduits une seule fois, avec un cache distinct pour chaque paire de langues. Les scripts exécutables, notes, noms de ressources, images, contenus propres aux plugins et sauvegardes du joueur ne sont pas traduits. Les archives Wolf protégées, les scripts Ren’Py compilés `.rpyc` et les textes produits dynamiquement ne sont pas extraits.

Le temps restant est estimé après quelques fragments, à partir des caractères effectivement traités pendant cette tentative ; le téléchargement des modèles et les traductions déjà en cache sont exclus de ce calcul. Les retours à la ligne ajoutés par le modèle dans un fragment sont normalisés en espaces, sans toucher aux retours à la ligne du jeu. Une sortie vide ou contenant des commandes inattendues laisse le fragment original en place au lieu de bloquer toute la traduction. Le bilan indique le nombre de fragments uniques conservés et reste disponible après réouverture de l’écran. Ces fragments ne sont pas mis en cache comme des traductions réussies ; ils peuvent être retentés après restauration des originaux.

Tous les originaux sont sauvegardés et relus avant application, dans `data/.astra-translation` (ou `www/data/.astra-translation`). **Conserver ce dossier**, qui reste auprès du jeu et n’est pas inclus dans l’archive du catalogue. **Restaurer les originaux** annule la traduction à l’octet près. La restauration refuse d’écraser un fichier modifié depuis par un autre outil. Les écritures interrompues sont journalisées ; si une reprise automatique échoue, ce même écran permet de relancer la restauration. Une interruption pendant la création initiale du journal, ou un stockage endommagé, peut nécessiter de récupérer manuellement les fichiers depuis le sous-dossier `original`.

Les mods installés via Astra doivent être désinstallés avant traduction ou export manuel ; restaurer ensuite les originaux avant d’installer des mods ou une mise à jour du jeu. Limites de traitement : 2 000 fichiers, 16 Mio par fichier et 64 Mio de JSON cumulés. Les textes automatiques longs sont découpés en fragments de 3 500 caractères maximum, sans couper les commandes. Le fichier manuel est limité à 64 Mio et 200 000 entrées. Il n’y a pas de remise en page automatique : une traduction plus longue peut déborder des fenêtres du jeu, et certaines polices ne contiennent pas les caractères de la langue cible. La qualité et la durée dépendent du texte, de la paire de langues et du téléphone ; traduire un jeu complet en quelques secondes n’est pas garanti.

La traduction automatique est fournie par Google Translate, sans garantie d’exactitude. [Fonctionnement de ML Kit](https://developers.google.com/ml-kit/language/translation) et [conditions d’utilisation](https://developers.google.com/ml-kit/language/translation/translation-terms). Les paires sans anglais utilisent l’anglais comme langue intermédiaire, ce qui peut affecter la qualité.

Le test réel du modèle est explicite et utilise uniquement des textes synthétiques :

```powershell
adb shell am instrument -w -r -e class fr.astragames.app.translation.GameTranslationTest -e liveTranslation true fr.astragames.app.test/androidx.test.runner.AndroidJUnitRunner
```

Sans l’argument `liveTranslation`, le test nécessitant les modèles est ignoré ; les tests d’extraction, de stockage local/SAF, d’interface et de restauration restent exécutables sans téléchargement.

## Mises à jour d’Astra

Ouvrir **Paramètres → Mises à jour d’Astra**. Astra interroge par défaut les releases stables du [dépôt public](https://github.com/azksama/AstraGames/releases), sans compte ni jeton. L’option **Recevoir les versions bêta**, désactivée par défaut, inclut aussi les préversions. Son changement relance la vérification et retire toute mise à jour en attente du canal précédent. La version la plus récente est choisie en respectant l’ordre des versions (bêta 2, bêta 10, puis stable), sans retour automatique vers une ancienne version. Le choix est conservé après fermeture et utilisé aussi par les vérifications automatiques.

La vérification périodique et le téléchargement automatique sont activés par défaut, avec un réseau non facturé (Wi-Fi) requis pour le téléchargement. La cadence demandée est de 12 heures ; Android peut la décaler selon la batterie et le réseau.

Une notification ouvre l’écran lorsqu’un APK est prêt. **Télécharger et installer** enchaîne le téléchargement manuel et l’ouverture de l’installateur ; **Installer la mise à jour** utilise le fichier déjà prêt. Android demande l’autorisation d’installer depuis Astra au premier usage, puis la confirmation de chaque installation. Astra ne peut pas effectuer une installation silencieuse sur un appareil Android ordinaire.

Le fichier doit correspondre à la taille et au SHA-256 annoncés par GitHub, au package Astra, à une version supérieure et à la signature de l’installation actuelle. Ces contrôles sont répétés avant de donner accès au fichier à l’installateur. Les APK non signés sont exclus. Les versions distribuées avec la clé de développement locale peuvent se mettre à jour entre elles ; une installation signée avec une autre clé doit disposer d’un APK compatible.

Le certificat historique de distribution est conservé pour la préversion beta.24.
La portée du contrôle de mise à jour depuis beta.23 et les empreintes de l’APK sont
précisées dans le [bilan public](docs/VALIDATION_1.12.0-beta.24.md). Conserver
l’installation et ses données pour garder l’accès aux archives chiffrées.

## Portée de validation

Les jeux délégués à JoiPlay nécessitent son runtime et un chemin physique accessible.
Wolf utilise Wine dans Astra. Le [guide Wolf](docs/wolf-windows-runtime.md) décrit
les modes d’accès, les sauvegardes et les limites du moteur.

Les validations décrites dans le [bilan beta.24](docs/VALIDATION_1.12.0-beta.24.md)
s’appuient sur des fixtures et Android Emulator x86_64. Le bilan distingue les
résultats du nouvel APK des preuves antérieures réutilisées. **L’exécution ARM64
reste non démontrée.** L’image SDK 16 Kio expose des pages applicatives de 16 Kio
sur un noyau/MMU de 4 Kio ; elle ne qualifie pas un appareil ARM64 à noyau 16 Kio.
Aucune compatibilité universelle avec les jeux commerciaux, extensions Windows,
RTP, MIDI, vidéos ou archives chiffrées n’est établie.

## Sources et licences

Les modules `windows-runtime` et `mkxpz-runtime`, leurs adaptations et les scripts
de compilation font partie des sources. Voir les [notices Winlator](windows-runtime/NOTICE.md),
les [notices MKXP-Z](mkxpz-runtime/src/main/assets/licenses/mkxpz/) et les
[licences des polices de l’interface](docs/licenses/).
Les sources natives correspondantes et les notices sont distribuées avec la
préversion ; le [guide de compilation MKXP-Z](scripts/mkxpz/README.md) décrit
les versions et les adaptations. Aucun jeu ni RTP commercial n’est fourni.
