# Ressources individuelles WOLFX

`WolfXResource.kt` décode les ressources individuelles portant les cinq octets ASCII `WOLFX`. Ce format est distinct d'une archive DX et n'utilise pas la clé d'archive. Le port est adapté des fichiers MIT `UberWolfLib/WolfX/detail/{GeneratorDetail,CrackDetail,DataManipDetail,UtilsDetail,ValidateDetail}.hpp` de [l'upstream UberWolf](https://github.com/Sinflower/UberWolf/tree/f4191375d289171b1f29dbc5ca320133b2b4b96a/UberWolfLib/WolfX), Copyright (c)2025 Sinflower. Les notices sont dans `wolf-native/THIRD_PARTY_NOTICES.md`.

## Contrat de lecture

API : `WolfXResource.isEncrypted(bytes)` puis `decode(bytes, key=byteArrayOf(), stringVariable=null, numberVariable=null)`. Le résultat est le payload exact, sans les octets d'enveloppe. La clé et les éventuelles chaînes sont des octets fournis dans l'encodage du jeu ; le helper n'impose pas UTF8 ni CP932. Il n'effectue ni IO, ni recherche de clé, ni remplacement de valeur absente, ni décompression.

La clé produit un bloc statique de64 octets par mélange/rotations modulo256. Son hash FNV1a32 et le DWORD big-endian aux offsets5..8 déterminent le LCG1664525/1013904223 et son bloc256. Les cinq octets10..14 déchiffrés donnent l'index de chaîne et la partie basse24bits de l'index numérique. Un index de chaîne inférieur à10000 nécessite la valeur explicite ; un index numérique inférieur à1000000 nécessite sa valeur explicite. Les valeurs supérieures désactivent ces lookups. Toutes les combinaisons/extractions de bytes `UtilsDetail` sont **big-endian**. Une interprétation little-endian de ces helpers ne décode pas les ressources établies.

Les modifications de clé par les deux valeurs précèdent le XOR des positions15..fin avec le bloc indexé `(position-10)%256`. Le payload commence à `512+static[0]+static[1]`. Cinq octets natifs aux positions15..19 sont comparés aux bytes du payload aux index `floor((size-1)*i/4)`, i=0..4. Le helper refuse un échec de ces contrôles, une clé invalide ou une référence de variable indisponible ; il ne rend jamais un succès vide de remplacement. Ce contrôle à cinq points n'authentifie pas l'ensemble de la ressource ; le consommateur doit encore vérifier son propre format (sfnt, image, etc.).

Limites explicites du port : ressource64MiB, clé65536bytes, chaîne de clé4MiB ; payload non vide et en-tête entièrement présent. Les données d'entrée ne sont pas modifiées. Les clés personnalisées ne sont pas découvertes automatiquement dans le VM ; l'API permet leur intégration après preuve du setter et du dossier concerné.

## Priorité des noms et périmètre

Le moteur demande normalement le nom clair, par exemple `Picture/a.png`. Si `Picture/a.png.wolfx` existe, il le choisit **avant** `Picture/a.png`, même lorsque les deux existent. Une mauvaise clé ne permet pas de reprendre silencieusement le fichier clair. Un chemin avec suffixe explicite `.wolfx` fonctionne aussi, sans ajouter un second suffixe. Ces règles sont établies par le [manuel primaire des fichiers chiffrés](https://silversecond.com/WolfRPGEditor/Help/02_file_crypt_pro.html) et les [commandes Pro](https://silversecond.com/WolfRPGEditor/Help/04ev_pro_command.html), pas par une assimilation à une archive DX.

Le helper portable décode des payloads binaires sans connaître leur usage. Son existence seule ne câble pas tous les lecteurs de l'application. Le port host concerne les images, fonds, brouillards, masques, audio, vidéo et polices avec **clé vide**. Une ressource qui réclame une clé/variable non fournie reste refusée. Le setter Pro de clé fichier/dossier et sa persistance ne sont pas admis par cette preuve. Les polices `ttf/ttc/otf` n'acceptent pas de clé personnalisée dans le [contrat officiel](https://silversecond.com/WolfRPGEditor/Help/02_file_crypt_pro.html).

Les cartes `.mps.wolfx` et les contenus `.txt/.csv.wolfx` n'ont pas encore de décodeur câblé dans leurs lecteurs. `WolfParser.loadMap` détecte l'alias prioritaire ou l'extension explicite et refuse avant lecture ; la version claire présente ne masque pas ce refus. Les chemins texte/CSV doivent également diagnostiquer l'alias protégé, sans le traiter comme un fichier absent ou une chaîne vide. Aucun décorateur générique de `WolfAssetSource` n'est revendiqué ici. Les métadonnées `.dat/.project` ont leur propre chiffrement et ne sont pas des ressources individuelles WOLFX dans ce [contrat](https://silversecond.com/WolfRPGEditor/Help/02_file_crypt_pro.html).

Le code spécial `GET_FILE_EXIST` est un contrat distinct de la lecture : la branche W3 `a2f218..a2f295` normalise le nom via `94e760`, appelle l'énumérateur `73da00`, puis retourne le texte `1` pour un handle différent de−1 ou `0` sinon ; le handle réussi est fermé via `73da20`. Aucun appel direct au lecteur WOLFX n'apparaît dans cette branche. La recherche éventuelle d'un alias `.wolfx` à l'intérieur du normaliseur ou de l'énumérateur n'est pas qualifiée : un fichier protégé seul ne bénéficie donc d'aucune garantie d'existence sous son nom clair par cette preuve. Source privée `wolf3-native-followup/fmt-macro-dispatch.asm.txt`, SHA-256 `bfae8ecfcf22eaa87986b80bfd3803aa138613b786f071de9c7f573c0aea9219`, évidence `ev_e25384d4903250e8e579e4065f488a4907191e4e98b7f24ace8a5d07781c4a43`.

`WolfNativeResourceBytes` est le lecteur host commun : il exige un worker hors UI, priorise l'alias protégé et partage la même future entre requêtes simultanées du nom clair et du nom explicite. Il conserve uniquement les payloads déchiffrés dans un cache de16Mio/64 entrées, avec éviction LRU ; il ne modifie pas les octets d'origine. Images, bitmaps opaques, masques, polices, audio, MP4/OGV et mesure `soundTime` consomment ce lecteur. Ce cache ne devient pas un décorateur global du parser.

## Preuves reproductibles

`wolf-native/src/test/resources/wolfx/native-reference.cpp` génère192 fixtures déterministes et indépendantes du Kotlin. Avec `WOLFX_UPSTREAM` défini, chaque fixture est également déchiffrée et comparée par `decryptFull` du commit épinglé. VS18 BuildTools AMD64, `cl /std:c++20 /EHsc /O2 /D WOLFX_UPSTREAM /I <WolfX/detail>`. Aucun Game.exe n'est exécuté.

SHA-256 source C++ : `a7658e867a15e7829c2d332ef9b492710d852e0c2325cf6502ad71bea09b7dd6`. SHA-256 TSV : `3b4d9fbcb109902b1a89191e923c41fed3459fd0f6cb969d402555d1345e7c15`. Six tests JVM passent :192 cas, paramètres explicites et références absentes, signedInt/fnv overflow, tailles1..1537 et bords256, dommages/contrôles, quotas et préservation des entrées. Runner local sans Gradle : `build/wolf-research/coverage/run-wolfx-tests.ps1`.

Le corpus privé The Crooked Man v4 contient cinq `*.ttf.wolfx` à la racine. Clé vide, aucun lookup de variable, offset852 pour chaque fichier. Le Kotlin et le `decryptFull` upstream produisent exactement les mêmes payloads, tous débutant par `00 01 00 00` (TrueType). Preuve privée : `third-crookedman-v4/coverage/wolfx-font-proof.tsv` ; les assets restent ignorés par Git.

| Police | Taille TTF | SHA-256 TTF |
|---|---:|---|
|Aozora Mincho Regular|7924152|27c0eaab87e78b66afc85a8308bfe27d1c29a9a23d324dbc659d7093daacf694|
|NanumMyeongjo|3839464|a21faae83d2d16d11c639f04f25fbe410eda905969ad75eff50d63ca31569f25|
|Noto Naskh Arabic Regular|300668|8cba766b19c98c66cba88c39cedb310a6713c493d08e510d35ae0064f22eac8c|
|Source Han Serif TW ExtraLight|7450888|860c41ed033cc89f1f51878cfeca4eb1f38eb592a671a4b87363de069c33a348|
|Spectral ExtraLight|238512|ba6dd643c52c354c0cf7c14308924588414472d2c5487c671f80a8cacac7c5e9|

Cette comparaison prouve la lecture du format sur ces ressources ; elle ne prouve pas à elle seule leur rendu Android, le layout de toutes les langues ni tous les jeux Pro.

Une référence native indépendante génère aussi16 cas sur quatre médias authored : PNG16×16, WAV PCM3s, MP4 et OGV, avec clé vide/chaîne/nombre/dossier. Toutes les sorties sont comparées octet par octet au `decryptFull` upstream épinglé. Rapport privé `build/wolf-research/wolfx-media-native-proof.tsv`, SHA-256 `e26c3cc165c307d6fecbd207527945416cf327e99361a6f5c6df8652f1f248b1`. Ce run utilise MSVC AMD64 : la compilation x86 des sources upstream échoue sur un type `uintmax_t`/`size_t` et n'a pas été corrigée localement. Les tests Android de pixels/lecture média restent une étape distincte de cette comparaison de payloads ; les médias authored ne sont pas des assets du jeu tiers.

Les générateurs suivis sont `wolf-native/src/test/resources/wolfx/media-payloads.cjs` et `media-reference.cpp`. Le [README des fixtures SDK](../../app/src/androidTest/assets/wolf-native/wolfx/README.md) décrit les 9 fichiers synthétiques et leurs SHA. Les neuf tests Android dédiés passent sur l'émulateur du SDK : quatre du lecteur, deux de rendu, un d'audio et deux de vidéo. Ils vérifient les pixels, les alias sans fichier clair, les filtres et deux canaux de masque, la durée sonore et les vrais décodeurs/session AudioTrack. Ces résultats font partie des 117 vérifications médias ; le résultat du lot complet est consigné dans le [rapport de validation](../VALIDATION_1.12.0-beta.15.md). La lecture et le rendu des polices privées possèdent également leur test Android distinct.
