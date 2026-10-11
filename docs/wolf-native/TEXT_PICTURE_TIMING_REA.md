# Progression des pictures texte : contrat REA

Les observations portent sur les PE officiels immuables W2 SHA-256 `795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0` et W3 SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`. Les chemins d'exports ci-dessous sont relatifs à `build/wolf-rea/wolf3-native-followup`, sauf mention W2. Ils constituent une analyse statique ; l'oracle C++ exécute une transcription indépendante du calcul, pas le PE original.

## État et cycle de vie

Le sous-objet texte commence à `picture+8` : constructeur `0097b420`, instructions dans `fmt-text-picture-basebind-asm-w3.asm.txt`, `ev_6697d3fbc1fcd60ce6baec6a1df1de1c3f8442c2a601c5bf34f0cd7db5ff2df1`. Dans ce sous-objet, speed est à70, progress à78, previous à80, pauseCheckpoint àC4, waitTicks àC8 et byteCorrection àDC. Cela donne respectivement les offsets78/80/88/CC/D0/E4 dans la picture entière. **E4 n'est pas un délai ajouté au nombre de lettres** : il corrige le compteur d'octets du flux compilé. Le champ10 du sous-objet,18 de la picture, contient la longueur d'octets de ce flux.

Le constructeur `00969860` initialise speed à240 et progress à0 (`w3r-141-text-base-init.c.txt`, `ev_073236e6c087f33dc43d77c8339c62e8cc68e529768f7990533e0e090f4e3a99`). SHOW `00986570` vide le cache du texte même quand la source est identique, puis appelle le passage de mesure `00969c70` et assigne Sys11 à speed. Les instructions de SHOW sont dans `fmt-text-show-reset-asm-w3.asm.txt`, `ev_fb14570f1decc09f397305ed650ac6a72c152c5a564f6f03028e9ad5e793c395`. Sys11 est `015055EC`, distinct des vitesses de dialogue Sys9/10. Son défaut froid est **1 000 000**, établi par les constructeurs de variables W3 `00959290` (offset98C, `vm-key-map-initial.c.txt`, `ev_be9e4ab5baca3488e3aca0822d0c77e96146d90bcbd24ba8d87b4bd2055c2b9c`) et W2 `004798C0` (offset968, `vm-key-initial-w2.c.txt`, `ev_91469340d56c06e8968aee6e95e12b5f2b58856215e4c07938fc2cb23925b09b`). Le 240 du sous-objet ne remplace donc pas cette valeur lors d'un SHOW ordinaire. MOVE ne doit pas recréer l'état de progression.

Le reset `00969db0` remet progress, previous, correction, pauses et les maps d'ancres à zéro ; son passage de mesure peut calculer la correction du flux complet avant l'assignation finale de Sys11 par SHOW. `WolfTextPictureTiming.initial` conserve cette correction mesurée. Chaque passage de dessin recalcule ensuite la correction du seul préfixe visité.

`convertSpecial` est l'état de Sys150 au SHOW, et non un bit de la commande150. Le convertisseur948960 teste le global1488B5C avant le développement des variables comme des styles. Le [manuel officiel de Sys150](https://silversecond.com/WolfRPGEditor/Help/06systemvalue.html) précise son défaut0, l'activation par1 et son ajout en3.50. Les octets statiques sont00000000 (`fmt-text-sys150-static.json`, `ev_c4dd43dd44091a7649dbdc9c074f5e9339864d06eb4c4dd601ca0fb86e6469d4`). Les17 références directes ont été décodées : les seules écritures observées sont le setter9E26C0 à9E3CD9 et la restauration954CA0 à959253 ; les autres sont des lectures ou comparaisons (`fmt-text-sys150-xrefs.json`, `ev_3b587b162b84ecaef19cff21ba4047dbc38fe842e24bbe315e94d5f8b05051fa`, et `fmt-text-sys150-ref-*.json`). L'hôte doit donc préserver la source brute si1, avant d'appeler le helper en mode littéral ; développer d'abord les variables puis désactiver les styles ne reproduirait pas le contrat.

Le prétraitement W3 de948960, instructions948B77..948BD3, supprime les octetsCR(0D) avant le test de Sys150 et les substitutions. AE3D50 délègue le compactage àB1D440, dont la boucle scalaire conserve les octets différents du paramètre ; AEC000 efface la fin du buffer. Sources `fmt-text-compact-cr-core.c.txt`, `ev_9c034cf7aaea2604fea9af1863f734f15e2af507fb363b22fd323f3b66269d67`, et `fmt-text-erase-removed-cr.c.txt`, `ev_f0584269c2f96aa718eff3df76a5f75efb2b24f381b13529bfb995b2e501cb95`. Un CRLF source devientLF ; un CR source isolé disparaît. Cette étape n'est pas répétée après l'insertion des valeurs de variables ; le helper sur texte déjà développé peut donc conserver leurs CR éventuels. Le marqueur de backslash littéral consomme les deux antislashs de la source avant les substitutions : développer une variable commençant au second antislash d'une paire ne reproduirait pas le convertisseur.

## Horloge et information10

Le tick `0098f1a0` copie d'abord progress vers previous. Si aucune attente n'est active et si waitTicks<1, il fait `progress=min(50_000_000,progress+step*speed/30.0)`, sans borne inférieure. Ensuite, si waitTicks>0, il soustrait step. Le pas vaut60/FPS configurés ; les profils30 et60 utilisent donc2 et1. Source `w3r-61-picture-tick.c.txt`.

GetInfo10 `0098c140` retourne−1 pour une picture non textuelle. Pour une picture texte, il retourne1 si :

```text
compiledByteLength <= (trunc(progress) + byteCorrection) - step*speed/30.0 + 1
```

La troncature ne concerne que progress ; le seuil final demeure un Double. Une dernière lettre visible n'implique donc pas immédiatement GetInfo10=1. Export `w3r-128-picture-properties.c.txt`, `ev_7c0c6e1b3c7cae9fee92b8d75c63c7119444c7e9caa1210285b58300532b19d3`.

W2 confirme indépendamment cette horloge (`004cc6e0`, `w2r-text-tick.c.txt`) et ce getter (`00492000`, `fmt-text-getter-state-w2.asm.txt`), avec speed80/progress88/previous90, waitE8 et correctionF8. Son lecteur `004f5ef0` retourne la longueur d'octets14. Son parser a ensuite été qualifié séparément : `004cc7e0` appelle `0049d5b0`, export `fmt-text-prefix-parser-actual-w2.c.txt`, `ev_0c88ef8962ddd43bc9186e352a80f2f99edf8d20e40bdc43307ed0dfc3717def`.

## Préfixe visible et codes

Le parser `0096a240` recommence au premier octet à chaque dessin. Il lit un caractère de l'encodage configuré, avance le curseur et ajoute `max(charByteLength−2,0)` à correction. Si `trunc(progress)+correction<cursorAfterCharacter`, il arrête **avant** le dessin de ce caractère. Les CR/LF avancent le curseur sans produire un glyphe. Le mode instantané augmente progress jusqu'au curseur corrigé ; seule la séquence `01 02 20` au tout début échappe à la première garde d'affichage. Preuves `fmt-text-picture-layout-parser-w3.c.txt`, `ev_c57a42b01864be54def8da3199d2a5e2f8f9d1a0606628ad8fdf6adf3a74462f`, et instructions `fmt-text-parser-encoding-w3.asm.txt`, `ev_d910d0a95395e63bd1f327f5630a32188d92179396e0ad85f67724128883b18a`.

`0071f860` délègue à `00731730` la taille d'un caractère UTF8 ou CP932. UTF8 utilise les tailles1..6 du lecteur d'origine ; CP932 utilise1 ou2. Le helper portable refuse un Unicode non encodable plutôt que d'introduire un remplacement silencieux. Preuves `fmt-text-char-byte-size-w3.c.txt`, `ev_28b8fbe8898a3bf0023cd865f2d99158e64373bd95a6ae3c57d4a3788167cee3`, et `fmt-text-char-size-impl-w3.c.txt`, `ev_8f2fd45520fd5620c74e990dd305aba78bf7f964d87c5d2b99e5ee8f5ee9f0`.

Le lecteur de contrôles `0096c320` consomme les deux octets restants du header et ajoute3 à correction. Les clauses temporelles établies sont :

| Source | Opcode | Contrat |
| --- | --- | --- |
| `\sp[n]` |0C|Payload4 octets ; n≤0 est compilé65535, n>60000 est borné60000. Au dessin,0/65535 relit Sys11, puis le résultat est borné à1. L'ancre est le progress du premier passage, indexée par curseur après header ; correction=`trunc(correction−((correction+anchor)−cursor))`, puis+4.|
| `\.` |0E|waitTicks=16 et pauseCheckpoint=cursor+1 seulement si ce curseur dépasse le checkpoint précédent. Pendant l'attente, le préfixe s'arrête à cette borne ; rescanner ne réarme pas la pause.|
| `\>` / `\<` |20 /0B|Active/désactive le parcours instantané. Le curseur du contrôle de sortie du mode est lui-même atteint instantanément.|
| `\^` |10|Active le flag Base.C3. Sa consommation pour fermer le dialogue est établie dans972BE0 (`w3r-145-text-sys9-set.c.txt`, lignes70–88) ; aucune suppression automatique d'une picture ordinaire n'est établie.|
| Styles et alignements |2..7,11..15,1A/1B…|Les octets de marqueur/payload sont ajoutés à correction, donc ne ralentissent pas chaque lettre comme une chaîne source brute.|
| `\i` / `\iS` |16 /1F|Payload3. Pour compatibilité déclarée>339, correction du payload+2 : l'icône compte une unité. Sinon le payload n'est pas corrigé.|
| `\img` / `\imgS` |17 /22|Payload chemin encodé puis01 terminal. Pour compatibilité>339, correction du payload=`payloadBytes−1`. Les fichiers intégrés doivent néanmoins être résolus et rendus par l'hôte.|

Sources `fmt-text-picture-controls-w3.c.txt`, `ev_ac36676e5b623f78ba502fcc1a01494cb7051acded6880d1cafd1132b9cfbde7`, et instructions `fmt-text-controls-step-asm-w3.asm.txt`, `ev_761d300b96c51c22766bb44f63f764cad85d415a51dde83d9aeae20bac431cdc`. La compilation des séquences et le bornage sp viennent de `00946690`/`00948960` : `fmt-text-escape-recognizer-w3.c.txt`, `ev_2fafdc3d04f0d81989eb181c3352013e1bb971773e4b2c112be14b4c1201744a`, et `fmt-text-source-expansion-w3.c.txt`, `ev_e51d8f0dc859acf7b9b1200979e37d0d153c989c6d920b676926b6585e57e95f`.

### Distinctions du parser W2

`0049d5b0` initialise correctionF8 à0, puis avance du nombre d'octets fourni par `00590fe0`. Il **n'ajoute pas** la correction W3 `max(charByteLength−2,0)` aux caractères ordinaires. Le test de révélation reste strict `<cursor`. Il n'a pas non plus l'exemption W3 pour le premier marqueur `\>` : une vitesse initiale nulle ne peut donc pas atteindre ce marqueur à progress0. Le mode instantané fonctionne une fois le header atteint. La branche pause écrit E8=16 et E4=cursor+1, avec la même garde de rescannage.

La clause `case0xB` du switch, qui soustrait1 à l'opcode avant de dispatcher, est `\sp`0C. Elle relit Sys11 pour0/65535, borne le résultat à1 et écrit speed80. Elle avance le curseur de4 et correction de4 après les3 du header, **sans map d'ancres et sans réajustement de correction**. Le helper choisit ce parser pour la famille native<300 ; les branches330/369 restent celles du PE W3. Les tailles de caractère sont une entrée du contrat portable ; les fixtures de jeu W2 utilisent CP932 et ne prouvent pas à elles seules une configuration W2 UTF8.

## Portage et validation

`WolfTextPictureTiming` expose compile, initial, tick, layout, advance, status et validate. Program contient la source déjà développée, son encodage, sa version de compatibilité, la longueur compilée et un Set d'ancres admissibles pré-calculé une fois. State sérialise speed/progress/previous, correction, waitTicks, pauseCheckpoint, autoClose et les ancres de vitesse. La validation est indépendante de la mutation de l'état courant, permettant de refuser une restauration invalide sans perdre la session ; elle ne rescane pas le programme pour chaque ancre.

`WolfTextPictureTimingTest` passe10/10 tests portables. L'oracle `wolf-native/src/test/resources/text-picture/native-reference.cpp` lit des marqueurs bruts distincts du compilateur Kotlin et produit **4 608 étapes** :12 flux×3 profils×2 pas×64 ticks. Les flux couvrent UTF8, CP932, pauses successives, style/alignement, instantané, vitesse avec relecture de Sys11 et icônes. Les Double sont comparés bit à bit. Le profil229 reproduit les clauses qualifiées du **PE W2**, tandis que330 et369 reproduisent les branches de compatibilité du **PE W3**.

Génération x86 VS18 (`/std:c++17 /EHsc /O2 /utf-8 /fp:strict`, `_controlfp(_PC_53,_MCW_PC)`) : `build/wolf-research/coverage/generate-text-picture-vectors.cmd`. Source SHA-256 `52837bcd1ce591d8c5e820a4140b77256e21aeed4938355ab9022eee820fba5a`, TSV SHA-256 `544b7a8476ffe22dafdbc491fb96ac2aeb3232e4546fb8fab00f31c6c2cb99b1`. Le test ne saute pas si le TSV manque.

## Frontières encore explicites

Le helper refuse les variables encore non développées, le ruby `\r`, l'audio intégré `\se` et **picture `\!`**. Ce dernier écrit waitingC2 au parser ; sa sortie dépend du byte releaseC1 et de l'entrée globale. Le setter de releaseC1 pour une picture ordinaire n'est pas encore établi, donc libérer arbitrairement par Valider introduirait une sémantique non prouvée. Le dialogue a son propre protocole d'acquittement et n'est pas soumis à ce refus de picture. L'intégration VM, le rendu de chaque glyphe et la validation Android sont des étapes distinctes de ces10 tests.

La recherche de `\!` a été étendue au-delà du seul getter. Le constructeur texte 969860 met à zéro le DWORD+C0 contenant C0/C1/C2/C3 ; le constructeur picture 97b420 appelle ce constructeur sur son sous-objet+8. SHOW 986570, reset969db0, la fin d'initialisation97b6a0 et le dessin98fd20 examinés n'établissent pas une activation de C1. Le contrôle96c320, instructions96f9fe..96fa11, exige C0=0 puis écrit C2=1 et mémorise le curseur dans CC ; le tick98f1a0 libère cette attente seulement avec C1 et l'entrée globale01503cf8.

Un scan en lecture seule des encodages x86 MOV/SETcc/arithmetic avec displacement direct C0/C1/C8/C9 dans les sections exécutables du PE épinglé (fmt-text-release-scan.py, candidats conservés en JSON) a guidé deux vérifications supplémentaires : 9a25f0 et9a3620 recopient C1 depuis un autre sous-objet, sans créer de valeur1. Preuves fmt-text-release-state-copy-w3.c.txt, ev_fd4a9b16cbd5b0f3c4e6d9c27b132d0834a4d21e5b8999011c38684190498b9a, et fmt-text-release-state-clone-w3.c.txt, ev_34d11cb638160aed8853aae3fd0f6225611f7119e35c800137d5899bc3a460b3. Ce scan n'est pas un désassembleur ni une preuve d'absence de toute écriture indirecte, globale ou à un offset relatif ajusté.

Le constructeur dialogue9728c0 et son tick972be0 ont été qualifiés séparément : le dialogue possède une branche d'acquittement directe de C2, même lorsque C1 est nul. Preuves fmt-text-dialog-constructor-release-w3.c.txt, ev_1a04f2290c5a4f91eac75f42ff01cb2d3a6df805d64f453e8cf4f9455e61869c, et w3r-145-text-sys9-set.c.txt. Le [manuel officiel des caractères spéciaux](https://silversecond.com/WolfRPGEditor/Help/06specialword.html) documente l'attente jusqu'à la prochaine entrée, sans établir de setter distinct pour une picture ordinaire. L'absence de ce setter dans le périmètre examiné demeure une inconnue ; elle ne prouve pas que tous les moteurs Windows restent bloqués sur cette séquence.
