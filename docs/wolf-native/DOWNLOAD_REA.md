# Commande260 : contrat de téléchargement W2/W3

Les PE étudiés sont les deux binaires officiels du ledger : W2 `795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0`, W3 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`. L'analyse REA est statique ; aucun `Game.exe` n'a été exécuté pour ces observations. La documentation primaire décrit le [téléchargement ordinaire](https://silversecond.com/WolfRPGEditor/Help/04ev_download.html) et les [variantes Pro](https://silversecond.com/WolfRPGEditor/Help/04ev_download_pro.html).

## Opérandes et résultat

`args[1]` contient deux bits : bit0 réception uniquement en chaîne, bit1 attente de la fin. `args[2]` désigne la variable texte recevant le résultat, ou0 pour aucune variable. `strings[0]` est l'URL et `strings[1]` le chemin relatif au jeu. Les chaînes sont déjà développées dans le contexte du commun par la VM. Une réception texte seule vérifie encore le chemin fourni avant de lancer le worker natif.

Une cible CommonSelf `1600000..1699999` est fixée au commun d'origine avant le travail asynchrone. W3 calcule `15000000 + commonId*100 + args[2]%100` ; W2 utilise l'adresse correspondante dans son tableau de chaînes. La réception dans un fichier et celle dans une variable sont indépendantes lorsque le bit0 est absent. La destination se terminant par un séparateur utilise le dernier composant de l'URL demandée, et `index.htm` si ce composant est vide.

Le worker utilise un état téléchargement en cours, un pointeur d'attente d'événement, un fichier temporaire puis une copie finale. L'état système48 est −1 en erreur,0 en cours,1 terminé. La VM ne doit pas présenter le retour immédiat du dispatcher comme une réussite réseau. La variable précédente doit rester conservée en cas d'échec. Le host est responsable de l'attente, de la résolution des variables, des erreurs HTTP, des écritures et de l'annulation ; `WolfDownloadRequest` n'effectue aucun accès réseau ou fichier.

W2 : dispatcher `0046bd10`, worker `004bfe40`. Export instructions `fmt-ext260-handler-asm.asm.txt`, preuve `ev_6810c85ff753e0a6ba3aa68a7d9857312151f27b1d875ccd8f5328e5cff6820d`, et worker `fmt-ext260-worker-w2-asm.asm.txt`, `ev_cf3227d717f4608638f6750b259d352acb270b114587b9753ec9997bd17dfd0b`.

W3 : dispatcher `00a2a300`, worker `009a3bd0`, thread `009a4f90`. Exports `fmt-ext260-handler-w3.c.txt`, `fmt-ext260-worker-w3.c.txt`, `fmt-ext260-network-thread-w3.c.txt`, preuves respectives `ev_387887e710ca9f0e7044f794d68fe70c4720445eabe1469f3615d1e4c5d2c015`, `ev_03bb3cb2c2b133020554144434818bfe6918e2959fe935b19bc7ff0ee16f440a`, `ev_683553d5e1f1952778a696b4a254480ffcf141b2d0952c46d816aa4c90bb6563`. Les fichiers de preuve sont sous `build/wolf-rea/wolf{2,3}-native-*-followup` ; les JSON de réponse conservent les identités et limites du provider.

## Extensions W3

| Bit `args[3]` | Effet établi |
|---|---|
|2|URL UTF8 au lieu du codepage legacy.|
|4|Encodage formulaire de la partie suivant le premier `?` seulement.|
|16|POST, corps dans `strings[2]`.|
|32|Corps POST UTF8 au lieu du codepage legacy.|
|64|Encodage formulaire de tout le corps POST.|
|256|Réception texte UTF8 au lieu du codepage legacy.|

Les trois choix UTF8 sont désactivés si le DWORD `f51360` vaut0. Son initializer donne−1 ; le binder Game.dat analysé n'établit aucune liaison à ce DWORD. Il ne faut donc pas l'assimiler à un byte de configuration, ni l'inférer seulement de la version déclarée. Le profil W2 portable admet le chemin ordinaire en CP932 et refuse explicitement ces extensions sans preuve W2.

`00426e20` conserve les octets ASCII alphanumériques et `*.-_&=`, remplace l'espace par`+`, encode le reste en `%HH` majuscules. L'encodage intervient avant l'analyse du marqueur de type de contenu POST. Si `<<CONTENT>>type<<CONTENT_END>>` existe alors, le worker utilise le type indiqué et garde uniquement le suffixe après le marqueur ; il abandonne aussi le préfixe avant celui-ci. Sans marqueur terminé, le corps reste entier et le type est `application/x-www-form-urlencoded`. Une fois échappé par le bit64, le marqueur n'est plus reconnu.

Le préfixe URL W3 `<<VERIFY_TLS>>` est retiré et active le drapeau natif de validation TLS. Le port Android doit toujours vérifier TLS ; l'absence de ce marqueur ne justifie pas une connexion permissive. Preuves de l'encodeur : `fmt-ext260-percent-encode-w3.c.txt`, `ev_b42111c3e7ffbc240b83c4a9cc03604d4a05d0bf42d3ee3d4294e04a74e73d9f` et instructions `ev_4e57dd992a0d77b8a550b97d701885e0a7462a179925422abcca62eb884436f1`.

## Helper portable et limites déclarées

API : `WolfDownloadRequest.parse(args, expandedStrings, profile, commonId=-1, encodingMode=-1)`, `filePath()`, `urlBytes`, `postBytes`, `decodeResponse(bytes)`. Les tableaux exposés sont copiés. Le host doit respecter les octets encodés de l'URL : réencoder silencieusement une query CP932 en UTF8 changerait la requête. Les erreurs de décodage ne doivent pas écraser une variable existante.

Le helper admet HTTP/HTTPS, chemins relatifs, variables texte globales/CommonSelf, CP932 japonais et UTF8. La réponse s'arrête au premier NUL ; après la conversion vers la chaîne UTF8 interne, le publisher `009a6cd0` retire un seul BOM initial `EF BB BF`. Le helper reproduit cette suppression d'un `U+FEFF` après décodage, y compris un corps constitué du seul BOM et deux BOM successifs. Le native utilise des conversions Win32 avec remplacement et un codepage sélectionnable ; le port ne prétend pas reproduire tous les ACP. Il refuse explicitement les caractères non représentables et les réponses malformées pour éviter une réception corrompue silencieuse.

Les segments interdits natifs `//`, double antislash,`..`,`./`,`.\\` et les caractères `%:*?"<>|` sont rejetés dans une destination. Le port refuse aussi les chemins absolus, caractères de contrôle et segment`.`. Les noms dérivés de l'URL ne sont jamais décodés en séparateurs ; les escapes restent des caractères de nom. URL/chemin sont bornés à4096 caractères/octet encodés, le texte reçu à16MiB et un million de caractères. Ces plafonds sont des limites du port, pas des capacités Win32 affirmées.

Les aliases négatifs sont refusés avec `WolfDownloadUnsupported`, y compris−1/−2/−4/−5. Dans W2,−1 ouvre l'URL par ShellExecuteA seulement si le short système est dans15000..15499 ; hors plage il finit sans téléchargement. Dans le binaire W3 gratuit,−4 retourne sans worker, et−1 est également supprimé dans la plage22760..22839. Les détails Pro de ZIP, navigateur, mises à jour automatiques et suppression de l'attente ne sont pas portés par ce helper. Les attentes automatiques de cadence et exemptions localhost du worker original restent à traiter par le host ; cette preuve portable ne constitue pas leur validation.

## Cycle de vie et cadence du worker W3

`009a3bd0` refuse une deuxième requête quand son octet busy est actif, avant de modifier le statut, la cible ou le pointeur d'attente. Le dispatcher installe l'attente uniquement lorsque le worker retourne un pointeur non nul. Le constructeur `009a38c0` initialise le statut privé à−1 et busy/wait à0. Ce statut privé est distinct du DWORD Sys48 `0150b7a0` publié par la boucle ; la valeur privée ne prouve pas à elle seule l'état système visible avant le premier tick.

Le publisher `009a6cd0` copie le statut ; après succès1 et fin du thread, il affecte la cible texte, supprime un BOM, libère busy/wait et ferme le handle. L'annulation `009a6e70`, lorsqu'un thread actif existe, positionne l'indicateur d'annulation, attend brièvement la fin, ferme/nettoie puis remet Sys48 à−1. Sans thread actif, elle conserve le statut déjà publié. Nettoyeur `009a4a90` : handles/fichier temporaire libérés, statut privé−1, busy/wait0. Preuves du publisher et de l'annulation : `ev_78703f08fe09a050b24088f5fb6b1bd684cfa8fb98eb692f8eec537923e3d421` et `ev_4ee77caf329cf09389f8041fae1956a2992125f37340e5337555605d94b33c80` ; les noms historiques des fichiers exportés `fmt-ext260-cancel-reset-w3` et `fmt-ext260-update-publish-w3` sont inversés par rapport aux fonctions qu'ils contiennent.

Le thread n'admet comme réussite HTTP que200. Les timeouts natifs sont30s connexion,60s envoi et300s réception. Son exemption locale reconnaît strictement `http://localhost` ou `https://localhost` suivis de`:` ou`/`. Le profil gratuit ajoute500ms initiales et900ms à la fin d'une requête non locale, ainsi que des délais selon son historique. Les slots et comparaisons de cet historique sont irréguliers dans l'original ; aucune file concurrente ou politique simplifiée n'est affirmée par le helper portable. Ces délais Win32 ne constituent pas une obligation de bloquer l'interface Android.

## Validation reproductible

`wolf-native/src/test/resources/wolf-download-native-reference.cpp` est un oracle indépendant de l'implémentation Kotlin. Il reproduit les branches du dispatcher et l'encodeur à partir de la preuve, et appelle `WideCharToMultiByte(932/65001)` sur Windows pour les octets. Seul cet exécutable écrit pour le test est lancé. VS18 BuildTools x86, `cl /std:c++17 /EHsc /O2`, génère `wolf-download-native.tsv` : **384 vecteurs**, tous les256 octets d'entrée de l'encodeur, puis128 combinaisons des six flags avec mode−1/0.

SHA source C++ : `cafc61fc0fc167d21900797c773aed60ce0a674e46922e4a3da8e8dd821bad2d`. SHA TSV : `4cd8c78c9d226078705b3f76f6939fc3e0856992162bd79d6e9b3c8aec8ebd6a`. **10/10 tests JVM passent**, y compris les384 vecteurs, cibles fichier/chaîne, contexte asynchrone CommonSelf, query, POST/marqueurs, BOM/NUL, refus Pro, traversées, limites et entrées malformées. Runner sans Gradle : `build/wolf-research/coverage/run-download-tests.ps1`, compilé contre le JAR core immuable du checkpoint20261010-223700. Cette validation ne prouve ni des téléchargements Android réels ni des opérations Pro.
