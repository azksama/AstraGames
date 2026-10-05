# Audit du cœur Astra — 5 octobre 2026

Base examinée : `2fe2ae0` (1.9.0). Revue statique et corrections du stockage, du catalogue, des imports, du réseau, du lancement et des tâches de fond, complétées par une validation native sur Android Emulator. Les codecs de sauvegarde, la traduction et l'interface sont examinés séparément par les autres volets de l'audit. Les validations de build et de tests de la session sont centralisées dans le rapport global.

## Corrections

| Priorité | Déclencheur constaté dans le code | Correction |
|---|---|---|
| P1 | La fusion de sauvegardes de doublons supprimait l'original avant même d'avoir lu la source. Une erreur SAF détruisait la sauvegarde principale. | Lecture source bornée avant écriture, conservation de l'URI cible, backup enregistré visible dans Astra, écriture `wt`, relecture complète et rollback vérifié ; suppression des nouvelles copies incomplètes. Détection des `.rmmzsave` ajoutée. |
| P1 | Pour les jeux RPG Maker, le premier handler Android de l'action JoiPlay recevait le titre, chemin et arguments, quel que soit son package. Ren'Py acceptait aussi tout préfixe ressemblant au package. | Liste exacte des packages connus par moteur et Intent vers un composant exporté/activé explicite. Les profils EXTERNAL restent un choix configuré par l'utilisateur. Cette restriction n'authentifie pas la signature d'un APK installé. |
| P1 | Une archive de catalogue legacy pouvait fournir des références de fichiers vers les préférences, le catalogue actif ou les backups privés. | Prévalidation des références mutables importées avant de toucher aux fichiers/catalogue. Les chemins privés d'Astra et leurs ancêtres sont refusés. Les références de backups sont remappées uniquement dans leur dossier géré attendu. |
| P1 | La désinstallation d'un mod concaténait un identifiant issu du catalogue dans un `deleteRecursively`, et lisait un backup sans confinement. | Identifiant d'installation validé avant l'opération ; fichier backup canonique strictement sous `mod-backups`. |
| P2 | Les ZIP de mods pouvaient décompresser le contenu d'une entrée répertoire sans le compter dans le quota ; une entrée normale allouait jusqu'à 200 Mio. | Copie par blocs et budget total incluant les répertoires, sans tableau mémoire de la taille d'une entrée. |
| P2 | L'extraction de catalogue acceptait des chemins avec `..` tant qu'ils restaient dans le staging ; des noms distincts pouvaient aliaser la même cible. | Nouveau `BackupArchive` : allowlist des dossiers, rejet traversal et doublons sans distinction de casse, extraction en flux, limite totale y compris les répertoires. |
| P2 | PIN et cookies chiffrés étaient inclus dans Auto Backup, alors que leur clé Keystore ne migre pas. | Exclusion du fichier DataStore dans les règles Android anciennes/nouvelles et le transfert entre appareils. Sur une nouvelle installation, préférences/PIN/session communautaire sont à reconfigurer ; le catalogue reste sauvegardé. |
| P2 | Fusionner deux tags présents sur le même jeu violait la clé composée `(gameId, tagId)`. | Insertion des références avec `OR IGNORE`, suppression des anciennes références et du tag dans une transaction. |
| P2 | Les opérations sur de grandes sélections/sources dépassaient les limites de paramètres SQL de certaines versions SQLite. | Lots de 900 identifiants pour suppression de source/tags, favoris, dossiers et catégories. |
| P2 | Manifestes mods, fichiers de tags et réponses VNDB/JoiPlay étaient lus sans limite. | Limites respectives 1, 4, 4 et 2 Mio avant interprétation. |
| P2 | Les chemins physiques reconstitués acceptaient des segments sortant de la source ; un scan pouvait descendre sans limite. | Confinement canonique dans le dossier sélectionné, validation du volume, limites 128 niveaux/100 000 dossiers avec échec partiel qui n'efface pas l'état de présence. Vérification lecture avant listing. |
| P2 | Une restauration sous un autre utilisateur Android ne remappait pas les jaquettes internes. | Remappage des URI internes de cover/banner/icon, avec décodage des URI et contrôle du chemin. |
| P3 | La copie d'une jaquette utilisait l'identifiant externe du jeu dans le nom de destination ; une annulation pouvait laisser une copie orpheline. | Nom UUID indépendant et nettoyage aussi sur annulation. |
| P3 | Réordonner une catégorie disparue avec une liste vide déclenchait `coerceIn(0, -1)`. | Vérification de l'existence avant calcul de l'index cible. |
| P3 | Chaque ligne du calcul de distance d'édition allouait un tableau. | Réutilisation de deux tableaux dimensionnés par la plus petite chaîne. |

## Inventaire relu

45 fichiers Kotlin de production dans ce volet, dont les deux nouveaux utilitaires. Lecture directe du code ; ce décompte ne représente pas une preuve d'exécution de chaque branche.

| Groupe | Fichiers sous `app/src/main/java/fr/astragames/app/` |
|---|---|
| Initialisation | `AstraApplication.kt`, `MainActivity.kt` |
| Fichiers/sécurité | `core/filesystem/FileAccessResolver.kt`, `core/filesystem/BoundedCopy.kt`, `core/security/KeystoreCrypto.kt`, `core/RunCatchingCancellable.kt` |
| Métadonnées | `core/metadata/CoverProvider.kt`, fournisseur communautaire, `VndbProvider.kt` |
| Recherche/modèle/collections | `core/search/DuplicateDetector.kt`, `EditDistance.kt`, `SearchParser.kt`, `TagMatcher.kt`, `TextTagParser.kt`, `core/model/Models.kt`, `core/collections/SmartCollectionEvaluator.kt` |
| Catalogue | `data/local/AstraDao.kt`, `AstraDatabase.kt`, `Entities.kt`, `data/repository/GameRepository.kt` |
| Backups/mods | `data/backup/BackupManager.kt`, `BackupArchive.kt`, `data/mods/ModArchive.kt`, `ModModels.kt`, `ModsManager.kt` |
| Scanner | `data/scanner/RecursiveSourceScanner.kt`, `LibraryReconciliation.kt`, `GameTitleNormalizer.kt`, `GameFingerprint.kt`, `EngineSignatureDetector.kt` |
| Lanceurs | `launcher/JoiPlayRuntimeManager.kt`, `JoiPlayLauncher.kt`, `JoiPlayCatalogProvider.kt`, `GameLauncher.kt`, `CompatibilityDiagnostic.kt` |
| Paramètres/outils | `settings/SettingsRepository.kt`, `SearchEngine.kt`, `CoverBlurMode.kt`, `AppLanguage.kt`, `tools/GameTools.kt` |
| Tâches | `worker/UpdateNotifications.kt`, `NetworkConstraints.kt`, `LibraryScanWorker.kt`, `JoiPlayUpdateWorker.kt`, `GameUpdatesWorker.kt` |

Également relus : manifestes main/debug, `res/xml/file_paths.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, `FixtureDocumentsProvider.kt`, Gradle racine/app/baselineprofile, `settings.gradle.kts`, `gradle.properties`, wrapper properties, ProGuard, `BaselineProfileGenerator.kt`, `scripts/verify-renpy-fixtures.py`. Les schémas Room exportés sont présents de 1 à 8 ; migrations 1→8 relues dans `AstraDatabase`. Aucune migration/destruction de base introduite. Pas de workflow GitHub Actions dans ce checkout.

## Refactorisation, duplication et volume

- `GameRepository` reste le plus grand module de ce volet (environ 812 lignes avant ajustements finaux), suivi du DAO (~550) et du scanner (~368). Ses responsabilités nombreuses (tags, imports, duplications, catalogue) justifient une extraction progressive, mais une réécriture totale aurait une surface de régression supérieure aux correctifs ciblés de cette session.
- Suppression de `parseTagNames`, fonction privée jamais appelée qui doublonnait le parseur de tags partagé. Les appels existants utilisent `parseTextTagList`.
- Extraction de la validation ZIP du catalogue dans `BackupArchive` et centralisation de la copie bornée dans `BoundedCopy` ; tests JVM directs possibles sans Android.
- `MToolLauncher` est explicitement un stub non fonctionnel. `ZipImportPreview`, certains enums/modèles et anciennes abstractions de fournisseurs n'ont pas de parcours de production complet ; ils ne sont pas présentés comme des fonctionnalités prêtes et sont conservés pour compatibilité plutôt que retirés arbitrairement.
- Les archives ne lancent aucun script de mod ; l'exécution reste celle du jeu/runtime explicitement ouvert par l'utilisateur. Les scripts de vérification CPython ne doivent recevoir que les fixtures fabriquées par les tests, jamais un pickle utilisateur.

## Vérifications et sources

- Ajout initial de 11 tests JVM couvrant copie bornée/flux non progressif, extraction backup/chemins/doublons/quota répertoire, distance d'édition, confinement de chemins et listes de packages JoiPlay : `BoundedCopyTest`, `BackupArchiveTest`, `EditDistanceTest`, `ConfinedPathTest`, `JoiPlayTrustTest`. Ils passent dans la validation finale centralisée, avec les tests des compléments ci-dessous.
- Régression SQL reproduite avec SQLite sur l'hôte : la requête UPDATE originale échoue lorsqu'un jeu possède les deux tags ; la requête corrigée conserve les références de deux jeux sans doublon. Compilation/KAPT des accès Room réussie. Cette reproduction particulière est une preuve SQLite hôte ; les scénarios Room/stockage exécutés sur Android sont décrits ci-dessous, sans leur attribuer une couverture de toutes les transactions.
- Manifestes : FileProvider privé et limité à `cache/cover-imports`, activité launcher exportée volontairement, PendingIntent notifications immutables/explicites. Le provider de fixtures existe seulement dans debug et exige `MANAGE_DOCUMENTS`.
- Revue ciblée de dépendance : `org.jsoup:jsoup:1.23.1` correspond à la version corrigée de [GHSA-pmhh-3w7g-xqp8](https://github.com/jhy/jsoup/security/advisories/GHSA-pmhh-3w7g-xqp8), source mainteneur consultée le 5 octobre 2026. Le code ne nettoie pas de HTML avec une Safelist personnalisée. Aucune mise à niveau aveugle effectuée. Cette vérification n'est pas un scan exhaustif CVE de toutes les dépendances transitives.
- Règles de sauvegarde vérifiées contre [la documentation Android Auto Backup](https://developer.android.com/identity/data/autobackup), qui définit les exclusions du domaine `file` et les règles séparées cloud/transfert.

## Limites explicites

- Les preuves natives concernent l’émulateur Android et les fixtures de test. Aucun téléphone physique, jeu ou runtime JoiPlay réel, fournisseur SAF distant ni environnement de production n'a été validé. Les restaurations Auto Backup entre installations et les comportements de fournisseurs tiers ne sont pas couverts par cette suite.
- Les `.astra` chiffrés restent liés à la clé Keystore de l'installation ; ils ne sont pas un format portable après désinstallation/changement d'appareil. Le complément AST2 ci-dessous supprime les buffers complets pour les nouveaux exports ; la lecture des anciens AST1 conserve le buffer proportionnel à l'archive du provider Android.
- Les écritures de fichiers multi-documents SAF ne peuvent pas être rendues atomiques par Room. Les backups/journaux limitent les pertes, mais un arrêt du processus pendant une opération ou un fournisseur qui refuse également le rollback nécessite une restauration explicite.
- Les téléchargements HTTPS et cookies de session ont été relus, sans connexion personnelle ni validation de l'état fournisseur. La vérification de signatures APK JoiPlay demanderait une empreinte officielle fiable ; le filtrage des packages empêche l'interception par un package tiers mais ne prouve pas l'origine d'un APK usurpant le package attendu.
- Exclusions de revue directe de ce volet : `ui/`, `translation/`, `data/saves/` et leurs nouveaux tests (autres volets), contenus binaires, images/fonts et artefacts générés/Gradle/build. Ce rapport core seul ne doit pas être décrit comme l'audit intégral de l'interface ou des codecs.

## Complément : mémoire des archives chiffrées

Après revue de l'implémentation AOSP, remplacer `readBytes` par un simple `CipherInputStream` ne suffit pas : Android Keystore conserve le résultat GCM entier avant authentification. Les nouveaux exports utilisent donc AST2 et des blocs GCM authentifiés de 1 Mio, avec identité aléatoire d'archive, taille totale et position incluses dans les AAD. Le bloc vide est authentifié ; les troncatures, permutations et données après le dernier bloc sont refusées. L'import ne commence l'extraction qu'après authentification du conteneur complet.

`BackupEnvelope.kt` est un nouveau module pur JVM, utilisé par `BackupManager`. Deux factories Cipher spécifiques sont ajoutées à `KeystoreCrypto` ; les API et le format des valeurs chiffrées des paramètres ne changent pas. Lecture AST1 et ZIP conservée. Quotas existants de 256 Mio maintenus. Les nouveaux AST2 ne sont pas lisibles par les anciennes versions d'Astra. La compatibilité AST1 retire les copies explicites du ciphertext, mais ne prétend pas supprimer la mémoire interne de son ancien GCM monobloc.

Le complément corrige également la collision de noms d'export à la seconde, vérifie chaque export fermé par relecture/authentification/longueur/SHA-256 avant succès, et applique à la création les quotas d'import sur les octets développés et le nombre d'entrées. Les fichiers temporaires sont propres à chaque opération. Les erreurs de tag cryptographique expliquent la corruption ou la différence d'installation tout en conservant leur cause.

Quatorze tests JVM supplémentaires (onze `BackupEnvelopeTest`, trois `BackupArchiveTest`) couvrent le format, une lecture indépendante AES-GCM, la compatibilité, les altérations, les flux bornés, la relecture et la symétrie des quotas. Le protocole exact, les garanties et les limites sont détaillés dans [BACKUP_FORMAT.md](BACKUP_FORMAT.md). Les tests natifs ci-dessous vérifient ensuite le provider Keystore ; aucune mesure de heap ou de performance matérielle n’est revendiquée.

Une revue indépendante a ensuite identifié que la première erreur de rollback interrompait la boucle avant suppression inconditionnelle du staging, détruisant des originaux encore utiles. `BackupRollback.kt` tente désormais toutes les opérations inverses, vérifie les fichiers restaurés, contrôle les échecs de suppression et agrège les erreurs. `BackupManager` conserve le staging dans `noBackupFilesDir` tant que le rollback est incomplet (ou qu'une erreur fatale survient après début des mutations), et affiche le chemin de récupération. Aucun nettoyage automatique de ces dossiers incomplets n'est introduit. Cinq tests JVM avec pannes injectées complètent ce correctif, portant ce complément sauvegarde à dix-neuf nouveaux tests.

## Validation finale 1.10.0

Le dernier batch global vérifié du 5 octobre 2026 confirme **200 tests JVM, 39 suites, zéro échec, erreur ou test ignoré**. Les onze tests `BackupEnvelopeTest`, cinq `BackupRollbackTest` et huit `BackupArchiveTest` passent. Les builds debug, release avec R8, APK de tests Android et variantes baseline ont réussi ; lint relève **zéro erreur et 45 avertissements**. Traces : `build/audit-verification-delivery-final-1.10.0.log` et `build/audit-unit-final-1.10.0.log`.

La suite native portrait finale a réussi **61 tests Android sur 61**, preuve dans `build/emulator-validation/instrumentation-confirmed-portrait.log`, avec le vrai modèle ML Kit activé. Les matrices paysage et portrait avec police à 150 % réussissent chacune **32 tests UI sur 32**, dans `build/emulator-validation/instrumentation-confirmed-landscape-large-text.log` et `build/emulator-validation/instrumentation-confirmed-portrait-large-text.log`. Les consignes courantes **interdisent ARTEMIS** ; cette exécution utilise uniquement l’Android Emulator du SDK et ADB.

- Les **3 tests `BackupAndroidTest`** utilisent le vrai provider Android Keystore : authentification de plusieurs blocs et d’un conteneur vide, refus d’un header ou tag altéré, puis export/restauration AST2 via le fournisseur SAF de test et une base Room réelle. Le scénario d’import corrompu vérifie que le catalogue, les copies de sauvegarde et les ressources gérées restent inchangés avant un import valide. Les clés temporaires de test sont séparées de la clé principale ; aucune clé principale n’est supprimée ou réinitialisée.
- Les **6 tests `StorageWorkflowsTest`** couvrent l’annulation du scan et la clôture de son historique, installation/conflit/restauration de mods, édition de sauvegarde SAF avec backup et refus d’une révision obsolète, refus d’écrire dans les fichiers privés Astra, documents SAF imbriqués et restauration du catalogue avec ses références de sauvegardes. Ces scénarios utilisent les classes de production, Room et des fichiers/documents de fixtures.

Ces résultats prouvent les chemins testés sur émulateur avec le fournisseur SAF de test `FixtureDocumentsProvider`. Ils ne démontrent ni le fonctionnement d’un fournisseur distant, ni une récupération après mort du processus, ni un profil mémoire, ni l’exécution sur téléphone physique ou dans un jeu réel. Les résultats finaux et les détails des trois matrices sont centralisés dans [ANDROID_VALIDATION_2026-10-05.md](ANDROID_VALIDATION_2026-10-05.md).
