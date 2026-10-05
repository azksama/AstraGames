# Format des sauvegardes du catalogue Astra

Les nouveaux exports utilisent le conteneur **AST2**. La lecture des anciens conteneurs **AST1** et ZIP non chiffrés reste prise en charge. AST2 nécessite cette version d'Astra ou une version ultérieure ; les anciennes applications ne savent pas le lire.

La clé AES-256 demeure `astra_master_key` dans Android Keystore. Ce format ne rend pas la clé exportable : une sauvegarde chiffrée reste liée à l'installation qui l'a créée. Les méthodes historiques utilisées pour les petites valeurs des paramètres (PIN/cookies) sont inchangées.

## Pourquoi un nouveau conteneur

Le [provider AES-GCM d'Android Keystore2](https://android.googlesource.com/platform/frameworks/base/+/0ad9a225d43d8998a6f9aa0906a977c2ae32a78a/keystore/java/android/security/keystore2/AndroidKeyStoreAuthenticatedAESCipherSpi.java) conserve le résultat du déchiffrement jusqu'à la vérification finale. Faire passer un ancien AST1 dans un simple flux n'élimine donc pas sa mémoire proportionnelle à la taille totale. AST2 limite chaque opération GCM à 1 Mio de données claires. Les données sont ensuite traitées dans des fichiers temporaires privés avant extraction.

## AST2, encodage binaire

Tous les entiers sont big endian.

| Offset | Taille | Valeur |
|---|---:|---|
| 0 | 4 octets | ASCII `AST2` (`41 53 54 32`) |
| 4 | 4 octets | Taille fixe des blocs : `1 048 576` |
| 8 | 8 octets | Taille totale du ZIP clair, de 0 à `268 435 456` inclus |
| 16 | 16 octets | Identifiant aléatoire de cette archive, généré par `SecureRandom` |

Le header fait 32 octets. Il est suivi de `max(1, ceil(tailleTotale / 1 048 576))` enregistrements. Pour chaque enregistrement, dans l'ordre :

1. IV de 12 octets, généré par le provider Keystore lors de l'initialisation en chiffrement. Aucun IV n'est imposé par l'application.
2. Texte chiffré puis tag GCM de 16 octets. La taille claire est `min(1 048 576, octetsRestants)` ; il n'y a pas de longueur fournie par l'enregistrement.

Les données associées authentifiées (AAD) de chaque enregistrement sont exactement :

`header[32] || indexEnregistrementInt32[4] || tailleClaireInt32[4]`

L'index commence à zéro. Le header complet authentifie le format, la taille de bloc, la taille totale et l'identité de l'archive. L'index et la taille claire lient chaque bloc à sa position. La permutation, la répétition ou l'insertion d'un bloc provenant d'une autre archive ne produit pas un flux valide. Une archive vide possède quand même un enregistrement avec IV et tag, authentifiant son header. Le lecteur exige EOF après le dernier tag et refuse également les tailles et formats inconnus avant d'allouer les blocs.

Le budget de 256 Mio du ZIP clair est conservé. Le surcoût chiffré est de 32 octets par archive puis 28 octets par bloc (7 200 octets au maximum). L'écriture et l'extraction partagent également les mêmes limites : 256 Mio de fichiers développés et 10 000 entrées. La copie compte les octets réellement lus plutôt que les métadonnées des fichiers. Un contenu très compressible ou un trop grand nombre de fichiers ne peut ainsi plus produire un export annoncé réussi, mais que l'import refuserait.

## Validation avant utilisation

Le lecteur écrit dans un fichier ZIP de staging appartenant à Astra. Aucune extraction, modification de fichiers gérés ou transaction d'import du catalogue n'a lieu avant le succès du déchiffrement de tous les blocs et du contrôle EOF. Un échec préalable à l'import supprime le staging dans le `finally` de `BackupManager`. Le fichier exporté incomplet est supprimé si la création échoue.

Les noms d'export comportent un suffixe UUID court en plus de l'horodatage ; une collision détectée avant création est refusée. Chaque opération possède son propre dossier temporaire. Après fermeture du flux de sortie, Astra rouvre le document exporté, impose AST2, authentifie tous ses blocs et compare la longueur et le SHA-256 du ZIP déchiffré avec celui lu lors de l'export. La vérification utilise un sink de hachage, sans extraction et sans tableau de la taille de l'archive. Une troncature silencieuse ou un fichier différent ne peut donc plus être annoncé comme sauvegarde réussie. Si la vérification échoue, seule la cible créée pour cet export est supprimée.

Les échecs de tag AES-GCM conservent leur cause technique et produisent un message indiquant que l'archive est endommagée ou appartient à une autre installation. Les erreurs d'entrée/sortie et de format restent distinctes.

Le staging d'import est placé dans `noBackupFilesDir`, pour empêcher l'éviction par le cache Android des seules copies originales en cas de problème. Si l'import des fichiers ou la transaction du catalogue échoue, `BackupRollback` tente chaque fichier dans l'ordre inverse, même lorsqu'une tentative précédente échoue. Les copies restaurées sont comparées par taille et SHA-256 ; une suppression retournant `false` n'est pas considérée réussie tant que le nouveau fichier existe. Toutes les erreurs sont conservées comme exceptions supprimées du diagnostic principal, avec la cause initiale de l'import.

Le staging est effacé après succès ou rollback entièrement réussi. Un rollback incomplet ou une erreur fatale pendant les mutations conserve le dossier et ses originaux. Le message d'échec indique son chemin et demande de ne pas désinstaller Astra. Les originaux sont dans `rollback/<dossier-géré>/<chemin-relatif>` et correspondent au même chemin sous `filesDir`. La récupération après une telle erreur reste une intervention explicite ; ce mécanisme ne prétend pas rendre atomique un ensemble de fichiers et une transaction SQLite.

Chaque appel AES-GCM d'AST2 reçoit au plus 1 Mio de données claires, plus son tag au déchiffrement. L'implémentation n'alloue aucun tableau représentant l'archive complète. Il s'agit d'une borne algorithmique des buffers de l'application et de la taille par opération provider, pas d'une mesure de heap Android ou de performance sur téléphone.

## Compatibilité AST1 et ZIP

AST1 conserve son encodage historique `AST1 || IV[12] || AES-GCM(ZIP)[N + 16]`, sans AAD. Le lecteur lit le texte chiffré par blocs de 64 Kio et appelle `doFinal` explicitement pour propager les erreurs d'authentification. Cela retire les copies intégrales du texte chiffré faites par Astra, mais **le déchiffrement d'une grande sauvegarde AST1 conserve le buffer complet du provider Android**. Une fois restaurée, un nouvel export produit AST2.

Les ZIP legacy reconnus commencent par une signature ZIP locale (`50 4B 03 04`) ou de fin de répertoire vide (`50 4B 05 06`). Ils passent ensuite dans les mêmes validations d'extraction, schéma SQLite et références de fichiers. Ils n'ont pas d'authentification cryptographique, comme auparavant.

## Preuves locales et limites

`BackupEnvelopeTest` utilise réellement AES-GCM du JDK avec une clé de test : contenu vide, frontières exactes et partielles de bloc, lecteur indépendant du format/AAD, compatibilité AST1 et ZIP, altérations de header/identité/IV/corps/tag, troncatures y compris après un bloc entier, permutations/répétitions/blocs d'autres archives, données ajoutées après le dernier tag, tailles invalides, lectures partielles/non progressives et flux généré de plus de 3 Mio traité via un fichier avec comparaison SHA-256. Des tests vérifient aussi la comparaison après export (contenu, taille, hash, format), l'erreur d'authentification compréhensible et la conservation des erreurs d'entrée/sortie. `BackupArchiveTest` vérifie les quotas symétriques à l'écriture/lecture, les contenus très compressibles et les noms invalides/alias.

Les cinq tests `BackupRollbackTest` utilisent le filesystem local et des pannes injectées : ordre inverse et préservation des originaux, erreur de copie sans abandon des autres fichiers, suppression refusée, copie corrompue silencieusement et original absent, fichier nouveau déjà supprimé.

Dernier batch global vérifié 1.10.0 du 5 octobre 2026 : les onze tests `BackupEnvelopeTest`, cinq `BackupRollbackTest` et huit `BackupArchiveTest` passent dans le batch de **200 tests JVM/39 suites**, sans échec, erreur ni test ignoré. Les builds debug/release/R8 et APK de tests réussissent ; lint donne **zéro erreur et 45 avertissements**. Traces : `build/audit-verification-delivery-final-1.10.0.log` et `build/audit-unit-final-1.10.0.log`.

La suite native portrait finale a réussi **61 tests Android sur 61**, dans `build/emulator-validation/instrumentation-confirmed-portrait.log`, avec le vrai modèle ML Kit activé. Les matrices paysage et portrait avec police à 150 % réussissent chacune **32 tests UI sur 32**, dans `build/emulator-validation/instrumentation-confirmed-landscape-large-text.log` et `build/emulator-validation/instrumentation-confirmed-portrait-large-text.log`. Les consignes courantes interdisent explicitement ARTEMIS : la validation utilise uniquement Android Emulator, le SDK et ADB.

Les **3 tests `BackupAndroidTest`** exécutent réellement AES-GCM avec le provider Android Keystore. Ils vérifient plusieurs blocs et le conteneur vide, le refus de modifications du header/tag, ainsi que `BackupManager` sur une base Room réelle et des flux `content://` du fournisseur de documents de test. Le scénario complet exporte AST2 avec une ressource dépassant un bloc, rejette une archive corrompue avant toute mutation, puis restaure catalogue, références de sauvegardes, contenu des copies et ressource exacte. Les clés de test temporaires ne remplacent ni ne suppriment `astra_master_key`.

Les **6 tests `StorageWorkflowsTest`** ajoutent des parcours natifs de stockage, dont édition/restauration SAF de sauvegarde, refus d’un éditeur obsolète, documents imbriqués avec mods et restauration du catalogue incluant les backups. Leur réussite confirme ces scénarios sur fixtures ; elle ne démontre pas l’interopérabilité avec tous les fournisseurs SAF.

Le rapport global détaille les autres résultats. Aucun profil mémoire mesuré, performance matérielle, téléphone physique, fournisseur distant, jeu réel ou récupération après mort du processus n’est revendiqué. Les résultats finaux et les détails des trois matrices sont centralisés dans [ANDROID_VALIDATION_2026-10-05.md](ANDROID_VALIDATION_2026-10-05.md).
