# Texte et mesures de chaînes — qualification beta.19

## Cas signalé et portée

Le diagnostic Seris `1791717976334-c09222ee-611a-48e4-b8f3-2ce7b56bd8c2` indique une inspection complète de 241 cartes et 375 incompatibilités. Il contient notamment des codes de base de données encore visibles après l'expansion statique : `\cdb[16:16:16]`, `\cdb[13:16:3]` et `\udb[1:16:0]` dans des commandes image 150. Le résolveur d'exécution connaît déjà ces trois familles ; le précontrôle ne faisait qu'une passe et refusait donc une couche extérieure encore intacte.

Les nouveaux helpers ne lisent aucune valeur fictive de base de données. L'expansion statique substitue uniquement une forme syntaxique, jusqu'à disparition des références imbriquées. Les types, lignes et colonnes réels restent vérifiés par le résolveur d'exécution.

## Artefact REA

Analyse statique en lecture seule de l'exécutable officiel **WOLF RPG Editor 3.729**, `build/wolf-research/sample3729/WOLF_RPG_Editor3/Game.exe`, SHA-256 `4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e`, avec **Ghidra 12.1.4**. Les résultats du décompilateur sont des observations de ce binaire, pas ses sources originales. Aucun jeu utilisateur n'a été exécuté dans cette analyse.

Bundle local : `build/wolf-research/beta19-text-codes-rea-evidence.json`. Le répartiteur de macros et le convertisseur de texte sont également exportés dans `build/wolf-rea/wolf3-native-followup/`.

| Contrat | Procédure ou branche | Preuve REA |
| --- | --- | --- |
| Conversion des références, répétée jusqu'à 100 passes ; protection des antislashs cités | `00948960` | `ev_18f0077cd4780cb9a090b2b8d9987cc0c1a27709ace0c5f0d3948219abbbad2d` |
| Dispatch des macros WIDTH, BYTE et CUT_LINE | `00a2e2d0` | `ev_19834e5d465fe253fd19992965b16c3b504f2e9b55526c168233506529097bc9` |
| Projection visible : base du ruby, antislash littéral, suppression des arguments image et des contrôles | `00426470` | `ev_5615d3e2fa175ecfdb8e801eb84e3c67caa494e6fe65db646f7e063a81d51c94` |
| Largeur UTF-8 : 1 pour un octet, 2 sinon, exception U+FF66..U+FFDF à 1 | `0042a760` | `ev_9465e8023f5735e651824a95ab0a755c0f0d2ff07ae15455c6095aa62aa74124`, `ev_82806a1cca4dcbfbf4df6712a5a44e56579988f7a5388de958d65b3e93e793eb` |
| Reconstitution des codes compilés avant comptage des octets | `0094e760`, branche `00a2e64c` | `ev_2e8cc5ac0fc8ea4204711244de423cce8cc14dbcd56d2b9fc6e8bd74fa8ec79a` |
| Formatage décimal du résultat, distinct du compteur | `00427510` | `ev_56f220e42e3ae31c5b030fd9d4e6c35ef1229d6bd7c559980983dfcdf843fd71` |
| Découpe inclusive sur LF ; début négatif ramené à 0, fin au moins égale au début | `0042ad20` | `ev_1ad9b51a4e9f0ce457c349cbcb6b43880aef2067eafda6d0811ac7b8bb0eeeeb` |

Le [manuel officiel des codes spéciaux](https://silversecond.com/WolfRPGEditor/Help/04ev_string_specialcode.html) décrit une largeur logique ignorant les retours à la ligne, un comptage d'octets UTF-8, ainsi qu'une découpe de lignes inclusive dont les indices commencent à zéro. Il indique aussi que `GET_MAPEV_FILENAME` renvoie le chemin graphique relatif à `Data`, `<<NotFound>>` en l'absence d'événement et `<TILE>15` pour un exemple de graphique de tuile. Les détails Unicode et les bornes extrêmes ci-dessous proviennent du binaire.

## API et intégration

`WolfTextExpansion.staticShape(input)` développe les références les plus internes d'abord, conserve les antislashs cités et laisse les codes de présentation inconnus au validateur existant. Le remplacer dans `WolfTextCodes.staticExpansion` évite le faux refus des références DB imbriquées. `hasReference(input)` permet au contrôle des ressources de différer un chemin contenant une vraie référence plutôt qu'un antislash cité. `expand(input, resolve)` permet de partager exactement cette récursion avec la VM ; le callback doit conserver les lectures et les erreurs DB réelles.

`WolfStringMeasurements.widthCount(input, convertSpecial)` mesure la largeur logique Wolf, pas les pixels d'une police Android. En mode conversion, les variables doivent déjà être développées ; le helper vérifie les codes de présentation, conserve le texte de base du ruby, omet contrôles et arguments d'images et retire CR/LF du comptage. Le classificateur observé compte notamment `é`, un accent combinant et un emoji à 2 chacun. U+FF61..U+FF65 restent à 2 ; seule la plage exacte U+FF66..U+FFDF vaut 1. Ce comportement n'est pas un algorithme générique de largeur Unicode.

`byteCount(input, convertSpecial)` qualifie le texte UTF-8 ordinaire et les antislashs cités. **Les chaînes formatées restent refusées en mode conversion** : le moteur original reconstitue et normalise certains codes compilés avant de compter, ce qui interdit de retourner arbitrairement la longueur du texte source. Avec conversion désactivée, la chaîne est comptée telle quelle. BYTE et WIDTH s'arrêtent au premier NUL, conformément aux chemins de chaînes C observés. Leur appel doit transmettre l'état effectif de la variable système 150 ; la valeur 1 désactive la conversion des codes spéciaux.

`cutLines(input, start, end)` sélectionne les lignes LF inclusives. Une ligne de début absente retourne une chaîne vide ; une fin dépassant le texte sélectionne jusqu'à la fin. CR, Unicode et codes de présentation sont préservés par le helper ; l'étape normale de texte de la VM effectue déjà sa normalisation CR.

Les helpers refusent un texte supérieur à 1 000 000 caractères et une référence non résolue après 100 passes, sans tronquer silencieusement le contenu. Cette borne mémoire est une protection Astra ; la limite des 100 passes est issue du binaire.

`GET_MAPEV_FILENAME` doit lire le graphique **courant** de l'acteur, du joueur ou du compagnon, après les modifications de page ou de mouvement ; le premier graphique de la carte n'est pas une substitution correcte. Cette intégration appartient au résolveur des chaînes et à la VM. `MAKE_EVENT_*` et `SET_EVENT_NAME_ID` nécessitent encore leurs effets réels sur les acteurs, noms et identifiants ; cette analyse ne les admet pas avec un résultat vide ou un événement fictif.

## Validation et limites

**11 tests JVM ciblés réussis** : 5 pour les références imbriquées, l'ordre de résolution, les citations, les cycles et les bornes ; 6 pour les mesures et découpes, Unicode, NUL, mise en forme et refus explicites. Commande : `:wolf-native:test --tests fr.astragames.wolf.WolfTextExpansionTest --tests fr.astragames.wolf.WolfStringMeasurementsTest`.

Log : `build/wolf-research/beta19-text-codes-test.log`. XML conservés dans `build/wolf-research/beta19-text-tests/`, indépendamment des autres lots Gradle.

Les tests utilisent des chaînes synthétiques. Ils ne prouvent ni le passage du précontrôle de Seris, ni son exécution sur téléphone, ni la prise en charge de toutes les chaînes formatées pour BYTE. Les macros non qualifiées et les erreurs de valeurs DB doivent rester visibles ; aucune incompatibilité n'est masquée pour permettre le lancement.
