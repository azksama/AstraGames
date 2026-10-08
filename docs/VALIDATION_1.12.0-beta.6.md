# Validation Astra 1.12.0-beta.6

Version Android 34. Runtime et composants téléchargés `wine9-astra-2` inchangés.

## Constat et corrections

Le rapport beta.5 fourni montre 604 secondes de préparation, suivies du lancement
du jeu puis d'un arrêt avec le code 139 après environ 28 secondes. Le serveur Wine
est cette fois démarré et arrêté correctement. La synchronisation prend ensuite
environ 47 secondes. L'extrait runtime commence après une rotation du journal ;
aucune pile précise de l'accès mémoire invalide n'est conservée.

La copie précédente relisait, hachait et écrivait les fichiers source, même identiques.
La bêta 6 conserve un état d'import terminé associé au dossier source :

- Un lancement normal ne parcourt plus les ressources du jeu. Il vérifie les
  sauvegardes connues (.sav/.save et dossiers Save/Saves), sans écraser une modification
  locale non synchronisée. Les ajouts dans les dossiers de sauvegarde suivis sont repris.
- Les anciennes copies avec manifeste et préfixe Windows initialisé sont réutilisées.
  Les fichiers sans index de taille/date seront hachés au premier arrêt, pour ne pas
  oublier des données écrites avant un ancien crash.
- L'import initial et l'actualisation explicite gardent une progression et des points
  de reprise. Le hash est calculé pendant la copie, avec une conversion hexadécimale
  sans création d'un Formatter pour chacun des 32 octets.
- À la fermeture, les ressources de même taille/date ne sont plus relues. Les
  sauvegardes restent hachées ; les nouveaux fichiers et modifications hors Save
  sont également synchronisés. Une modification externe de ressource qui conserve
  artificiellement sa taille/date nécessite une actualisation explicite.
- Outils → Actualiser les fichiers du jeu demande une relecture au prochain lancement,
  préserve les conflits et retire les anciennes ressources supprimées de la source
  uniquement si leur contenu local est intact. L'installation/désinstallation de mods
  dans Astra invalide le cache avant mutation. Les sources et sauvegardes ne sont pas effacées.

## Rendu et arrêt 139

Le réglage logiciel est désormais ajouté quand `Game.ini` existe sans la clé correspondante.
Les valeurs avec espaces/casse différente sont aussi prises en charge. Seule la copie
privée est modifiée, dans le dossier de l'exécutable sélectionné. Le mode logiciel est
le mode de sécurité [documenté par Wolf](https://silversecond.com/WolfRPGEditor/Help/02gamesetting.html).

Box64 reçoit des paramètres prudents pour les flags, l'ordre mémoire SIMD et la taille
des blocs (SAFEFLAGS=2, STRONGMEM=2, BIGBLOCK=0, NATIVEFLAGS=0, X87DOUBLE=1), issus des
options [documentées par Box64](https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md)
et utilisés dans le profil stabilité Winlator. Cela vise les erreurs possibles de
recompilation ARM64 ; ce n'est pas une preuve de la cause du code 139 signalé.

Le mode debug conserve les erreurs, exceptions et chargements DLL, mais n'active plus
`warn+all`, qui noyait le rapport sous les sondages de fichiers et surchargeait les entrées/sorties.
Chaque flux conserve également ses 64 premiers Kio, en plus des deux fichiers tournants
de 512 Kio. `Game_ErrorLog.txt` est intégré avec sa date et son état avant/après lancement,
avec décodage UTF-8 ou japonais Windows-31J. Huit flux, cinq sessions, environ 42,5 Mio
maximum pour les logs hors métadonnées ; aucun envoi automatique.

Le journal personnel joint mentionne une capacité disque insuffisante et une sauvegarde
nécessitant un Game.exe plus récent. Sans date, ces messages ne prouvent pas la cause de
la dernière session. Aucun remplacement de l'exécutable, suppression de sauvegarde ou
effacement de l'application n'a été effectué. Les rapports personnels ne sont pas publiés.

## Vérifications du 8 octobre 2026

- 222 tests JVM, zéro échec. Builds debug, instrumentation et release R8 réussis.
  Lint : zéro erreur, 70 avertissements (dont mesure informative de l'espace disponible).
- Android Emulator API 36.1 x86_64 : huit tests de stockage, cinq de diagnostic,
  un de lancement intégré réussis. Deux tests conditionnels de mort de processus
  non exécutés dans ce passage (16 tests affichés par le runner).
- Cas de régression : cache beta.5, sauvegarde locale non synchronisée, source mise à jour,
  import interrompu, fichier illisible, suppression d'un mod, conflit, export,
  import SAF imbriqué, journal japonais historique et journal modifié.
- Benchmark de 70 002 fichiers synthétiques : import initial **51 264 ms**, préparation
  suivante avec une nouvelle instance du stockage **888 ms**, synchronisation avec un
  nouveau fichier hors Save **5 268 ms**. Une ressource source est volontairement rendue
  illisible au second lancement pour vérifier qu'elle n'est pas ouverte. Les fichiers
  sont petits : cela ne prédit pas la copie initiale d'un jeu réel de plusieurs Go via SAF.
- Échantillon officiel Wolf 3.729 : récupération du cache beta.5 en **26 ms**, lancement
  Wine, scène du jeu affichée, Valider et rotation portrait/paysage, fermeture propre.
  Captures contrôlées visuellement. Cette exécution utilise Wine x86_64, pas Box64 ARM64.
- APK de release signé, R8 activé, non débogable : moteur prêt en **2 324 ms**,
  scène jouable contrôlée à **17 226 ms**, y compris les attentes fixes du probe
  et l'appui Valider. Fermeture finalisée avec résultat « Fermeture demandée ».
  Signature identique aux bêtas précédentes, version 34 et alignement ZIP vérifiés.

Preuves locales : `build/wolf-research/beta6-final-build.log`, `beta6-device-tests.log`,
`beta6-storage-benchmark.txt`, `beta6-game-landscape.png`, `beta6-release-runtime.log`.

## Limites

La disparition du crash de Dragon Blood sur le Samsung SM-S948B n'est pas validée :
ni cet appareil ni les fichiers du jeu ne sont disponibles pour une reproduction.
La préparation récurrente inférieure à une minute est mesurée sur le jeu de test,
mais ne constitue pas une garantie sur toute configuration, premier téléchargement,
premier préfixe Wine, stockage lent ou initialisation propre au jeu.
