# Variables Wolf dans le moteur Wine

L’onglet **Variables Wolf** de l’éditeur de mémoire lit les données du processus du jeu. Il expose leurs identifiants Wolf, leur catégorie, leur groupe et leur valeur typée. Il permet de rechercher un identifiant ou une valeur, de modifier une valeur après comparaison avec la lecture attendue, de confirmer cette modification par relecture et d’annuler la dernière modification confirmée. Le scanner mémoire reste accessible séparément.

Ce lot n’intègre aucun code, DLL ou table propriétaire de MTool. L’analyse de MTool a montré l’intérêt d’un adaptateur par moteur avec une garde de version ; les layouts ci-dessous viennent des exécutables **officiels Wolf** analysés avec REA, des call sites vérifiés en assembleur et des essais sur leurs copies. Le code du helper et du protocole est original Astra. Il n’injecte pas de DLL et n’appelle pas de fonction interne du moteur.

## Surface et limites

| Surface | État |
| --- | --- |
| Variables numériques globales, banques 0 à 9 | Lecture, recherche et écriture `int32` signé |
| Variables texte globales | Layout statique reconnu ; les deux jeux échantillons ont zéro variable texte globale. Pas de preuve positive de modification de cette catégorie sur ce corpus |
| Variables numériques communes | Lecture et recherche, écriture par le même trajet vérifié que les numériques globales |
| Variables texte communes, slots 5 à 9 | Lecture, recherche, édition et annulation vérifiées sur les deux échantillons officiels |
| Base de données, variables système, variables locales/self d’événement | Pas exposées par cet adaptateur |
| Exécutable d’une autre version ou dont les ressources ont changé | Refus explicite de l’adaptateur typé ; scanner générique disponible |
| Texte plus grand que sa capacité mémoire existante | Écriture refusée ; aucune allocation distante ni modification de capacité |
| Texte source de plus de 4 096 octets | Affiché comme lecture seule ; corps non transporté |
| Concurrence avec les scripts du jeu | Comparaison, vérification des pointeurs et relecture ; ce n’est pas une transaction atomique avec les threads du jeu |

Les noms métier (« or », « PV », etc.) ne sont pas devinés. Les lignes présentent la catégorie et l’ID réel. La recherche couvre l’ID et le contenu affiché ; les lettres ASCII sont comparées sans casse, les autres caractères par leur représentation UTF-8 exacte. Les résultats sont paginés par 128 lignes avec un nombre total de correspondances. Le groupe peut sélectionner une banque globale ou un événement commun.

Les variables se trouvent dans le processus de jeu courant, et non dans une table de démonstration. Le helper reconnaît le chemin complet de cet exécutable et s’arrête lorsque ce processus se termine. Les tokens et l’annulation sont propres à cette session et ne sont pas persistés.

## Identité et layout

La garde compare le SHA-256 du fichier de l’exécutable avant de choisir un layout. Le nom `Game.exe`, un numéro de version supposé ou un pointeur ressemblant à un vecteur ne suffisent pas. Les adresses ci-dessous sont des RVA ajoutés à la base réelle du module, récupérée avec `Module32FirstW` / `Module32NextW` ; l’ASLR n’est donc pas ignoré.

| Fait | Wolf 2.2961 | Wolf 3.729 |
| --- | --- | --- |
| Échantillon officiel | `sample22961/WOLF_RPG_Editor2/Game.exe` | `sample3729/WOLF_RPG_Editor3/Game.exe` |
| SHA-256 | `795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0` | `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e` |
| PE | x86 / PE32, base préférée `0x400000` | x86 / PE32, base préférée `0x400000` |
| Contexte des données, RVA | `0x006A8EE8` | `0x0110D370` |
| Adresse préférée du contexte | `0x00AA8EE8` | `0x0150D370` |
| Vecteur externe des numériques | contexte + `0x18` | contexte + `0x14` |
| Vecteur des textes globaux | contexte + `0x28` | contexte + `0x20` |
| Vecteur externe des numériques communs | contexte + `0x38` | contexte + `0x2C` |
| Vecteur externe des textes communs | contexte + `0x48` | contexte + `0x38` |
| Taille d’un vecteur | 16 octets ; proxy à 0, begin/end/cap à 4/8/12 | 12 octets ; begin/end/cap à 0/4/8 |
| Taille d’un objet texte | 28 octets ; proxy à 0, union data à 4, longueur à 20, capacité à 24 | 24 octets ; union data à 0, longueur à 16, capacité à 20 |
| Petit texte intégré à l’objet | capacité 15 ; buffer dans l’union data | capacité 15 ; buffer dans l’union data |
| Texte alloué | capacité > 15 ; pointeur 32 bits dans l’union data | capacité > 15 ; pointeur 32 bits dans l’union data |
| Encodage interne | CP932 | UTF-8 / CP65001 |

Les owners de la fenêtre/rendu `0x00AA5EE8` et `0x01504C60` ne sont **pas** ces contextes. Les vrais arguments `this` des getters ont été vérifiés dans les call sites. La première tentative de sonde utilisant un owner de rendu a été rejetée par les contrôles de layout et n’a pas été activée dans le produit.

Chaque lecture de vecteur contrôle begin ≤ end ≤ capacité, les multiples de stride, les bornes de compte et l’accessibilité mémoire. Une chaîne doit avoir une longueur valide, un buffer lisible et un terminateur nul. L’écriture requiert des pages déjà accessibles en écriture. Les pointeurs et allocations entrent dans le token de chaque variable. Le contexte est resondé immédiatement avant et après une écriture ; le helper refuse un token qui ne correspond plus.

### Identifiants logiques

| Catégorie | ID Wolf | Index/groupe |
| --- | --- | --- |
| Numérique globale | `2_000_000 + banque * 100_000 + index` | banque 0 à 9 ; index présent dans cette banque |
| Texte globale | `3_000_000 + index` | index présent dans le vecteur texte global |
| Numérique commune | `15_000_000 + commun * 100 + slot` | slots 0 à 4 et 10 à 99 présents dans le vecteur |
| Texte commune | `15_000_000 + commun * 100 + slot` | slots 5 à 9, index texte `slot - 5` |

Un ID hors de ces catégories ou absent des données courantes est refusé. Les commandes typées transmettent un ID, jamais une adresse mémoire arbitraire.

## Écriture et transport

Les numériques sont des entiers signés 32 bits, sans changement de type. Les textes reçus en UTF-8 sont convertis dans l’encodage du jeu ; une conversion CP932 qui utiliserait une substitution ou un caractère approchant est refusée. Les NUL incorporés et les chaînes UTF-8 mal formées sont refusés.

L’écriture compare l’ancienne valeur, vérifie que le layout/token n’a pas changé, écrit et relit. Pour le texte, le helper écrit dans le buffer existant et actualise la longueur, en conservant capacité, pointeur et propriété de l’allocation. Il refuse une écriture qui chevauche une valeur gelée par le scanner. L’annulation utilise la dernière valeur confirmée comme ancienne valeur attendue ; si le jeu l’a changée depuis, l’annulation est refusée.

Une erreur de relecture signale que la modification n’est pas confirmée. Comme les threads du jeu continuent de fonctionner, la valeur peut changer immédiatement après la vérification ou pendant l’écriture. Le helper n’annonce pas une atomicité ou une stabilité permanente.

Le protocole garde l’en-tête de 56 octets du scanner et ajoute les opérations 11 à 14 : sonde, recherche, lecture et écriture. Une extension de requête de 40 octets contient la version, le token, la catégorie, le groupe et les tailles de query/before/after ; chacun des trois textes est borné à 4 096 octets. Une réponse a 104 octets d’en-tête, jusqu’à 128 entrées de 40 octets et leur valeur UTF-8. La taille maximale acceptée est 529 512 octets. Le décodeur contrôle version, ID de requête, framing, doublons, cohérence ID/catégorie/groupe, types et bornes. Le mutex de `WolfCheatSession` sérialise les commandes typées et celles du scanner. La boucle d’attente lit d’abord les huit octets contenant l’ID de réponse ; une réponse de la requête précédente ne provoque pas une allocation de 529 Ko à chaque poll. Le corps courant est lu par blocs bornés de 8 Ko.

## Preuves conservées

L’index [rea-index.json](../../build/wolf-research/wolf-data-proof/layout-evidence/rea-index.json) conserve pour chaque preuve l’ID REA, le fichier source et le hash du sujet. Les noms historiques de certains fichiers parlent de « système », mais le code ciblé et les routes d’ID documentés ci-dessus sont ceux des fonctions indiquées ici.

| Sujet | Adresse/fonction | Preuve REA |
| --- | --- | --- |
| Wolf 2, getter numérique et ID/banques | `FUN_0043F0B0` | [response](../../build/wolf-rea/wolf2-native-render-followup/responses/fmt-save221-numeric-query-w2.json), `ev_1bb5fa2b4db7b4ae33c9b938c243724a331bbc9b1c4007c6887e54f6bc306942` |
| Wolf 2, getter texte global/commun | `FUN_0043FC40` | [response](../../build/wolf-rea/wolf2-native-render-followup/responses/formatcoverage-22-system-string-route.json), `ev_1d69d6c542c850d3ade6cfc6b3ebbf41f9bae96014c9f56458c23f951d32c54c` |
| Wolf 3, getter numérique | `FUN_009DEAC0` | [response](../../build/wolf-rea/wolf3-native-followup/responses/12-system-value.json), `ev_95f3709984ea630b23f7d6f3940fba2e7894bf547106bdbc9a58cb526de82033` |
| Wolf 3, setter texte et layout des objets | `FUN_009DD400` | [response](../../build/wolf-rea/wolf3-native-followup/responses/vm-font04-system-string-setter.json), `ev_72d266f926d02e132192dbb22511d38ee38141f91daa649dc4c9395cbedab74c` |
| Wolf 3, commande texte | `FUN_00A0FE10` | [response](../../build/wolf-rea/wolf3-native-followup/responses/fmt-macro-string122.json), `ev_48b6d9b9bd8ecfe495fc6fc487abeb489eab79ef99fb228ae020e3b3d8fc0596` |
| Wolf 3, conversion d’encodage | `FUN_00429750` | [response](../../build/wolf-rea/wolf3-native-followup/responses/fmt-ext260-encode-cp932-w3.json), `ev_d812d709da68e3aa7d92cb48a1bd13732d59a752b10ee9a55496cfa2dfccfe4b` |

Des extraits produits avec le `llvm-objdump` du NDK sur les mêmes exécutables corroborent les call sites et tailles de vecteurs : [Wolf 2 this → getter](../../build/wolf-research/wolf-data-proof/layout-evidence/w2-global-number-caller.asm.txt), [Wolf 3 this → getter](../../build/wolf-research/wolf-data-proof/layout-evidence/w3-global-number-caller.asm.txt), [Wolf 2 numériques](../../build/wolf-research/wolf-data-proof/layout-evidence/w2-number-getter.asm.txt), [Wolf 2 textes](../../build/wolf-research/wolf-data-proof/layout-evidence/w2-string-getter.asm.txt), [vecteurs numériques](../../build/wolf-research/wolf-data-proof/layout-evidence/w2-vector-rows.asm.txt), [vecteurs texte](../../build/wolf-research/wolf-data-proof/layout-evidence/w2-vector-strings.asm.txt). Ce sont des observations statiques, pas des mesures de FPS.

La nouvelle session REA `wolf-adapter` a ouvert Wolf 2 mais `binary_overview` et `search_strings` ont terminé avec `provider_timeout` ; elle n’a apporté aucune nouvelle décompilation. Les erreurs, l’export vide et la fermeture sont conservés dans [evidence/wolf-adapter](../../build/mtool-research/2026-10-12/evidence/wolf-adapter). Les preuves statiques utilisables sont les anciennes sessions exactes indexées ci-dessus, complétées par les essais runtime ci-dessous. La session a été exportée et fermée.

## Validation reproductible

### Windows Win32, exécutables officiels

`python scripts/wolf-runtime/check-wolf-data.py` copie les échantillons dans des dossiers uniques sous `build/wolf-research/wolf-data-proof`, lance les exécutables officiels inchangés et le helper livré, puis utilise le même transport et les mêmes opérations que l’App. L’entrée clavier requise pour commencer la partie vise uniquement la fenêtre du processus de test lorsqu’elle possède effectivement le focus ; le focus précédent est restauré. Les données et sauvegardes originales ne sont pas modifiées.

Le dernier run du 12 octobre 2026 est passé avec exit 0, sur les deux exécutables et une fixture originale non-Wolf. [proof.json](../../build/wolf-research/wolf-data-proof/proof.json) décrit les répertoires utilisés et les contrôles réalisés.

| Observation runtime | Wolf 2.2961 | Wolf 3.729 |
| --- | --- | --- |
| Banques numériques présentes | 10 : `[18,4,5,4,1,1,1,1,1,10]` | identiques |
| Numériques globales | 46 | 46 |
| Textes globaux | 0 | 0 |
| Groupes communs | 225 | 225 |
| Numériques communes listées | 21 375 | 21 375 |
| Textes communs listés | 1 125 | 1 125 |
| Numérique modifiée/relu/restaurée | ID `2000017` | ID `2000017` |
| Texte commun modifié/relu/restauré | ID `15000005` | ID `15000005` |
| Texte japonais `星` | aller-retour CP932 vérifié | aller-retour UTF-8 vérifié |
| Ancienne valeur/token périmés | refusés | refusés |
| Croissance au-delà de la capacité | refusée | refusée |
| Recherche par ID | vérifiée | vérifiée |

La fixture non-Wolf démontre le refus de l’identité inconnue, puis une lecture/écriture/relecture générique, le refus d’une ancienne valeur incorrecte, une annulation, la recherche du scanner et l’arrêt du helper après la fin du jeu. Cela ne démontre pas la compatibilité typée avec tous les jeux Wolf.

### Sauvegarde éditée puis rechargée par le vrai moteur

Deux nouvelles copies des données officielles Wolf 2 ajoutent uniquement `SaveLoad[1,1]` à l’init du commun 48, sans commande d’écriture de variable. Elles conservent le `Game.exe` officiel inchangé. La première reçoit la sauvegarde originale corrigée ; la seconde sa copie modifiée par le codec Kotlin. Le helper n’effectue que la lecture de l’ID `2000017` pendant cette preuve.

| Entrée | SHA-256 de la sauvegarde | Variable 17 observée |
| --- | --- | --- |
| Original de contrôle | `841ae6b9bdbfcea168c91260c0fa445123f5eb336f2b44c58f6329923e11846d` | `0` avant chargement, puis `271828` |
| Copie éditée par Kotlin | `26a67b594055a573e32c1d647f0585d8d123c7a6cd66855f2cee647ac921878c` | `0` avant chargement, puis `314159` |

Les preuves [contrôle original](../../build/wolf-research/wolf-data-proof/offline-reload-control-proof.json), [copie éditée](../../build/wolf-research/wolf-data-proof/offline-reload-proof.json) et le [runner](../../build/wolf-research/wolf-data-proof/check-offline-reload.py) sont conservés. Cela confirme le passage codec offline → format accepté par le moteur original → donnée réelle lue en mémoire sur Windows Win32. Le test ne prétend pas une validation Wine/Android. Deux essais préalables cachés sont restés au statut « variables non initialisées » ou ont atteint le timeout ; les deux contrôles finaux sont exécutés séquentiellement et présentent brièvement uniquement leur fenêtre de test pour passer l’initialisation, puis restaurent le focus précédent. Leurs copies de sauvegardes et les originaux conservent les hash ci-dessus.

### JVM et Android SDK

Le test JVM `WolfEngineDataProtocolTest` couvre le framing, les bornes UTF-8, les entiers signés, les ID/catégories/groupes, les erreurs/layouts inconnus et les réponses invalides. `WolfCheatProtocolTest` couvre le protocole existant du scanner. Les XML du run central du 12 octobre 2026 présentent respectivement 5 et 4 tests, sans erreur ni échec. Les builds Gradle et runs d’émulateur sont centralisés pour ce lot ; consulter le rapport d’implémentation pour le résultat du build final, incluant l’optimisation du poll.

Le test SDK opt-in `WolfWineEngineDataTest` utilise les vrais échantillons officiels via le Wine livré, dans un dossier de jeu jetable. Il exige `wolfEngineDataProbe=true`, `wolfFixtureVersion=22961` ou `3729` et la copie préalable du jeu dans `files/wolf-probe/save-fixtures/game<version>`. Il vérifie un numérique commun, un texte commun japonais, la relecture, l’annulation, les anciens token/valeur, la capacité et la recherche. Son fichier de preuve est `externalFiles/wolf-engine-data-<version>-proof.txt`. Aucun exécutable synthétique n’usurpe un hash de moteur officiel.

Les runs Android SDK/Wine centralisés passent pour Wolf 2 (82,403 s, [log](../../build/mtool-wine-data-w2.log)) et Wolf 3 (83,529 s, [log](../../build/mtool-wine-data-w3.log)), chacun avec `OK (1 test)`. Ces durées incluent préparation et initialisation du runtime de test ; elles ne sont pas des mesures de temps de sonde ou de FPS. Le numérique commun et le texte commun ont donc une preuve positive Wine/Android distincte de la preuve Win32. Aucun résultat sur appareil physique ou jeu commercial d’une autre identité n’est inféré de ces essais.

### Création des fixtures officielles

Le script [create-official-save-fixture.py](../../scripts/wolf-runtime/create-official-save-fixture.py) persiste la procédure de préparation du corpus. Il attend `--source` (dossier officiel déjà obtenu séparément) et `--dest` (nouveau dossier), reconnaît les deux SHA de `Game.exe`, parse le commun 48, insère les commandes avant son blank terminal et reparcourt l’ensemble. Il accepte les fichiers Wolf 2 non compressés et Wolf 3 compressés, avec une réémission LZ4 valide composée de littéraux. Aucun contenu de jeu n’est embarqué dans le script.

```powershell
python scripts/wolf-runtime/create-official-save-fixture.py --source build/wolf-research/sample22961/WOLF_RPG_Editor2 --dest build/wolf-research/generated-w2-save --mode save
python scripts/wolf-runtime/create-official-save-fixture.py --source build/wolf-research/sample3729/WOLF_RPG_Editor3 --dest build/wolf-research/generated-w3-save --mode save
```

Le mode `save` ajoute variable normale 17 = `271828`, `SaveLoad[0,1]` puis `Wait[10]`. Le mode `load` ajoute uniquement `SaveLoad[1,1]` ; la sauvegarde à tester doit ensuite être copiée dans `Save/SaveData01.sav` du nouveau dossier. Le marqueur et le slot sont configurables, mais les preuves ci-dessus concernent le marqueur par défaut et le slot 1. Toute destination existante ou située sous l’original est refusée avant copie. Les sorties de ces exemples sont ignorées sous `build/`.

La [preuve du générateur](../../build/wolf-research/wolf-data-proof/fixture-creator-proof.json) couvre les quatre combinaisons version/mode, le refus d’une destination existante sans modification et la conservation des EXE/données sources. Les payloads des deux variantes `save` générées correspondent exactement aux fixtures déjà exécutées dans Wine, après décompression du Wolf 3. Le mode `load` Wolf 2 correspond à la procédure de reload Win32 ci-dessus ; le mode `load` Wolf 3 a une vérification structurelle, sans nouveau test de reload de sauvegarde revendiqué.

### Rebuild du helper

```powershell
python scripts/wolf-runtime/build-memory-agent.py
python scripts/wolf-runtime/build-memory-agent.py --check
python scripts/wolf-runtime/check-wolf-data.py
```

Le build est CRT-free, avec le LLVM du NDK et `-Wall -Wextra -Werror`. `--check` compare le résultat généré à l’asset sans écraser cette dernière. Le SHA-256 du helper validé est `f6c874a9ed273dd2f3c6383b399910a1ceb6e2f59eca1d673432c6c044bb76d7`.

## Fichiers d’implémentation

Le layout/probe/read/write est dans [wolf_data.h](../../scripts/wolf-runtime/wolf_data.h), intégré à [memory_agent.c](../../scripts/wolf-runtime/memory_agent.c). [EngineDataAdapter.kt](../../app/src/main/java/fr/astragames/app/windows/EngineDataAdapter.kt) décrit la surface typée ; [WolfEngineDataProtocol.kt](../../app/src/main/java/fr/astragames/app/windows/WolfEngineDataProtocol.kt) valide le transport ; [WolfEngineDataAdapter.kt](../../app/src/main/java/fr/astragames/app/windows/WolfEngineDataAdapter.kt) gère écriture et annulation. [WolfCheatSession.kt](../../app/src/main/java/fr/astragames/app/windows/WolfCheatSession.kt) relie les deux modes au helper courant, et [WolfCheatDialog.kt](../../app/src/main/java/fr/astragames/app/windows/WolfCheatDialog.kt) expose l’onglet et les erreurs explicites.

Les prochaines extensions nécessitent chacune un layout identifié et une preuve runtime : nouvelle version officielle, textes globaux non vides, chaînes à allocation externe, base de données, système ou variables d’événement. Les limites ne doivent pas être masquées par une table de valeurs fictives ou par une conclusion de support universel.
