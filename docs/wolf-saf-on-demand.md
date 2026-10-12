# Dossiers Wolf via SAF à la demande

Le mode automatique garde l’accès direct existant lorsqu’Android fournit un chemin réellement accessible à Astra. Pour un arbre `content://` sans chemin accessible, il peut désormais utiliser des descripteurs de fichiers fournis par le `DocumentsProvider`, sans importer tous les assets du jeu. Un fournisseur incompatible provoque un repli annoncé vers la copie privée ; des écritures en attente empêchent un repli silencieux qui les abandonnerait.

## Lecture et capacités

`WolfSafMount` vérifie l’exécutable par un descripteur de fichier régulier, `lseek` et `mmap`, puis teste réellement la création, la synchronisation, le renommage et la suppression d’un petit document temporaire. L’URI SAF reste dans le processus Android. `saf_bridge.c`, chargé par `LD_PRELOAD` avec le pont Wine existant, obtient les descripteurs par socket Unix et `SCM_RIGHTS`. Seuls les pairs portant l’UID d’Astra sont acceptés.

Un dossier de travail privé, une entrée exécutable vide et une copie limitée de `Game.ini` permettent de conserver le lancement Wine existant. Les appels `open` et `stat` servent le véritable exécutable depuis le fournisseur. Cette entrée privée n’accorde aucun droit SAF et ne contient aucun asset. La lecture du contenu, les accès aléatoires et les mappings passent ensuite directement par le descripteur du fournisseur, sans RPC par bloc lu.

L’index porte sur les dossiers demandés, avec un maximum de 100 000 entrées et 256 dossiers, une durée de vie de 30 secondes et une éviction des métadonnées anciennes. Les assets lus n’ont pas de cache de contenu. Les requêtes sont bornées à huit connexions, quatre workers et quinze secondes par échange. Les statistiques et la raison précise d’un repli figurent dans les diagnostics.

## Écritures et arrêt brutal

Une écriture utilise un fichier privé journalisé. Chaque fichier est limité à 256 Mio ; les payloads privés et les archives interrompues partagent un budget de 512 Mio. Le journal courant est limité à 4 096 chemins et les archives interrompues à 128 versions. Les copies d’export partagent un autre budget de 512 Mio et sont remplacées atomiquement, après synchronisation. Un dépassement conserve les données existantes et signale une erreur.

Le pont scelle le hash du payload seulement après une synchronisation réussie demandée par `fsync`, `fdatasync`, `close` ou `fclose`. L’éditeur de sauvegardes copie d’abord son export modifié dans un fichier temporaire borné, le synchronise puis le renomme avant de le sceller, en conservant la baseline de conflit. Une erreur de copie laisse le payload et le journal précédents intacts. Les limites agrégées s’appliquent aussi à cette voie et à `Game.ini`, en comptant l’espace temporaire nécessaire au remplacement atomique. La publication relit cette empreinte ; une écriture ultérieure modifiant les octets n’est donc pas automatiquement certifiée par un ancien commit.

Une publication compare l’état actuel de la source à la baseline connue. Elle écrit un document temporaire, le synchronise, vérifie son hash, vérifie à nouveau la source, conserve l’ancien document sous un nom de backup et renomme le nouveau. Les étapes sont journalisées et reprises au prochain lancement. Si la source a changé, sa version reste intacte et la copie privée demeure exportable. L’export conserve un chemin stable afin que les backups de l’éditeur restent restaurables.

Après une interruption, un payload non scellé ou dont le hash a changé reste privé et exportable. Il ne remplace jamais automatiquement la sauvegarde source et n’est pas proposé au jeu à la place de la dernière source disponible. Une nouvelle sauvegarde utilisant le même nom archive d’abord l’écriture interrompue ; elle reste accessible dans les exports `Recovered/…`. Une écriture scellée dont la publication a échoué est republiée après retour de l’accès, si la source correspond encore à sa baseline. Un retrait de permission ne supprime ni le journal ni les exports privés.

`Game.ini` utilise le mécanisme durable existant de restauration des clés temporaires de rendu. Les réglages propres au jeu sont ensuite publiés avec la même comparaison de baseline. Les alias Android `/data/data` et `/data/user/0` sont normalisés avant tout calcul de chemin relatif ; la protection interdisant de sortir du dossier reste active.

## Limites précises

- Un flux, un pipe, un descripteur non mappable, une source en lecture seule ou un fournisseur sans renommage fiable ne convient pas à cette voie. Un fournisseur distant n’est pas rendu compatible par un lien symbolique.
- Le pont cible le runtime Bionic Wine épinglé. Il intercepte ses APIs POSIX utilisées ; il ne constitue pas une implémentation universelle de tous les syscalls/FUSE. Le déplacement entre dossiers SAF retourne explicitement `EXDEV`. Les hard links, les symlinks arbitraires, les sockets et les fichiers spéciaux dans le dossier du jeu ne sont pas pris en charge.
- La suppression d’un fichier possédant encore un writer ouvert et les modifications concurrentes par d’autres applications n’offrent pas toutes les garanties d’un filesystem POSIX local. La comparaison de hash protège contre les conflits observés ; SAF ne permet pas un compare-and-swap atomique universel avec un auteur externe intervenant exactement pendant le dernier renommage.
- Un commit durable certifie des octets terminés par le writer ; il ne valide pas la structure sémantique de toute sauvegarde de tous les jeux. Les archives interrompues sont conservées pour récupération, pas annoncées comme sauvegardes valides.
- L’espace libre renvoyé par Wine porte sur le stockage privé servant au journal. Il ne prétend pas mesurer un quota distant que le fournisseur n’expose pas. Les erreurs de publication restent détectées et les payloads restent conservés.
- La compilation ARM64 et les sources [Box64 0.4.2](https://github.com/ptitSeb/box64/blob/v0.4.2/src/wrapped/wrappedlibc.c#L4508) confirment une voie de résolution globale de libc via `dlopen(NULL)`. Cela motive l’intégration du preload ; cela ne remplace pas une exécution sur un appareil ARM64 avec le binaire épinglé.

## Validation

Les compilations isolées NDK 29 du pont, en ARM64 et x86_64, passent avec `-Wall -Wextra`. Le premier essai SDK a révélé un repli causé par les alias du dossier Android dans `WolfDirectConfiguration.apply`, corrigé par normalisation canonique.

`WolfSafMountTest` couvre les FD, `SCM_RIGHTS`, `mmap`, une préparation ignorant 5 001 assets inutiles dont un illisible, la reprise d’une écriture interrompue, la publication rejetée, les conflits d’édition, le retrait d’accès du fournisseur et le repli explicite. Le retrait est simulé par une véritable `SecurityException` dans le fournisseur de debug : celui-ci partage l’UID d’Astra et une simple révocation de son auto-grant ne produirait pas un refus Android. `WolfWineSafRuntimeTest` exécute aussi un programme Win32 de sauvegarde écrit pour Astra avec le véritable Wine x86_64 sur l’émulateur SDK.

Le test Wine réel après ajout du scellement a passé sur l’émulateur SDK x86_64 : `build/mtool-wine-saf-real.log`, un test, 71,986 secondes. L’exécutable source n’a importé aucun octet ; les appels Win32 ont publié une sauvegarde, contrôlé l’espace disponible et conservé l’ancienne sauvegarde inchangée. Deux tests de publication/restauration de l’éditeur de sauvegardes passent également.

La passe finale, après les limites agrégées et la correction des chemins JNI pour utiliser exactement la racine canonique exportée à Wine, confirme les six tests SAF sans skip : `build/mtool-final-android.log`, lignes 1–85, tous terminés avec `INSTRUMENTATION_STATUS_CODE: 0`. Cette passe comprend aussi les deux tests de publication/restauration de l’éditeur. La compilation debug/release et le lint release réussissent dans `build/mtool-final-release-build.log`. Le lancement Wine SAF a été confirmé à nouveau sur ce dernier build (`build/mtool-final-wine.log`, test `sourceExecutableAndWin32SaveUseSeekableProviderDescriptors`, statut 0).

Les résultats d’une passe antérieure, une copie privée de repli ou un test JNI seul ne prouvent pas un lancement Wine SAF. La validation sur un appareil physique ARM64 et sur les fournisseurs utilisés par l’utilisateur reste distincte. Voir aussi le [rapport de validation beta.23](VALIDATION_1.12.0-beta.23.md) pour la passe finale complète.
