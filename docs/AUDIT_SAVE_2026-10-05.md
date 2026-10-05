# Audit des sauvegardes — 5 octobre 2026

## Périmètre et méthode

Lecture du code courant des 16 fichiers de `data/saves` (dont le nouveau décodeur de chaînes Python), du contrôleur `GameToolsController`, des tests JVM existants et du parcours d’édition. Le graphe Graphify existant a été interrogé mais ses anciennes références `SaveEditors.kt` sont périmées : les constats ci-dessous reposent sur les sources actuelles. Les changements de l’interface sont documentés dans l’audit global.

Références techniques consultées : [JsonEx officiel RPG Maker MV](https://raw.githubusercontent.com/rpgtkoolmv/corescript/master/js/rpg_core/JsonEx.js), [pickletools CPython](https://docs.python.org/3/library/pickletools.html), [Marshal Ruby](https://docs.ruby-lang.org/en/master/Marshal.html). Les parseurs Kotlin sont purement structurels : ils ne chargent aucune classe Python/Ruby et n’exécutent aucun code contenu dans une sauvegarde.

## Défauts corrigés

| Priorité | Constat établi | Correction |
|---|---|---|
| Haute | Un entier Python dépassant 64 bits empêchait de lire la sauvegarde entière, même pour éditer simplement l’argent. Les opcodes `UNICODE`, `BINUNICODE8`, `BINBYTES8`, `BYTEARRAY8`, `NEWOBJ_EX`, `INST` étaient absents. | Préservation des grands entiers en lecture seule et prise en charge structurelle de ces opcodes ; fixtures CPython des protocoles 0, 2, 4 et 5. |
| Haute | Deux clés pickle de types différents, par exemple `1` et `"1"`, produisaient le même chemin. Les chaînes réutilisées par le memo pouvaient être modifiées en plusieurs endroits, voire modifier une clé. | Chemins typés et échappés sans collision ; distinction des alias de conteneurs et des feuilles partagées. Les feuilles partagées restent en lecture seule. Les chemins encore ambigus sont refusés avant écriture. |
| Haute | Le parseur RGSS refusait `U` (état personnalisé), les grands entiers, expressions régulières, classes/modules et attributs `I` sur les objets binaires. Les wrappers `e` étaient affichés mais non résolus à l’édition. | Structures conservées, wrappers résolus, attributs et données binaires préservés. Le format `M` est lu comme un nom de module, conformément au format Marshal. |
| Haute | Les chaînes Ruby étaient toujours interprétées et réécrites en UTF-8, même avec un autre encodage déclaré. | Encodage original conservé ; données binaires/non décodables en lecture seule ; conversion incompatible refusée avant écriture. |
| Haute | Des longueurs de collections Marshal contrôlaient directement des allocations ; JSON et pickle/Marshal n’avaient pas toutes les limites nécessaires ; la décompression LZ-String était non bornée. | Budgets de profondeur, nœuds, tailles, frames, références et dictionnaire ; contrôle avant allocation ; refus des clés dont le calcul de hash récursif n’est pas sûr. Les lectures qui retournent zéro avancent correctement. |
| Haute | Les noms de sauvegardes et identifiants importés étaient concaténés dans les chemins de backup. Les URI des backups n’étaient pas confinées avant suppression/lecture. | Noms aléatoires et dossier dérivé d’un SHA-256 ; validation canonique sous `filesDir/save-backups/` ; source du jeu vérifiée dans ses emplacements autorisés. |
| Haute | Le hash était contrôlé avant le calcul du patch et la création du backup : un jeu pouvait modifier le slot pendant cette préparation. | Nouvelle comparaison immédiatement avant remplacement ; relecture du résultat et rollback conservés ; backup synchronisé sur disque ; rotation après succès seulement. |
| Moyenne | Les métadonnées JsonEx `@`, `@c`, `@r` étaient modifiables, ce qui pouvait casser la reconstruction des objets. | Ces champs techniques ne sont plus proposés ; le contenu des tableaux `@a` reste modifiable. Les entiers au-delà de la précision JavaScript sont refusés. |
| Moyenne | L’éditeur simple pouvait prendre un libellé `experienceLabel` ou un plafond `levelCap` pour la valeur courante. | Correspondance sur le nom de champ et son type numérique, y compris les valeurs d’expérience indexées. |
| Moyenne | Chargements obsolètes, réservations tardives de l’état occupé et absence de hash pouvaient produire des résultats incohérents ou un clic sans retour. | Garde de génération sur valeurs/erreurs, réservation avant suspension, erreur explicite si l’éditeur n’est plus chargé ; restauration/suppression sérialisées et liste actualisée après restauration. |

## Couverture des sources

| Fichier | Revue / résultat |
|---|---|
| `BoundedStreams.kt` | Limites et progression des flux ; corrigé. |
| `DocumentDirs.kt` | URI SAF imbriquées, distinction arbre/document et dossier fichier ; lu, mécanisme existant conservé. |
| `GameSaves.kt` | Détection par moteur, exclusions global/config, dédoublonnage URI et recherche des dossiers ; lu. |
| `JsonSaveEditor.kt` | Chemins échappés, types, métadonnées JsonEx, classification simple ; corrigé. |
| `LzString.kt` | Compatibilité base64 MV, entrées invalides, budgets de décompression/dictionnaire ; corrigé. |
| `Marshal.kt` | Allocation, graphes, références, opcodes, encodages et écriture ; corrigé. |
| `MarshalFlattener.kt` | Chemins, types, wrappers, encodages, clés ambiguës ; corrigé. |
| `Pickle.kt` | Stack machine, opcodes, memo, plages scalaires, frames, grandes valeurs ; corrigé. |
| `PickleEntries.kt` | Graphes cycliques, références partagées, chemins typés et clés ; corrigé. |
| `RenPyArchive.kt` | ZIP, nombre/tailles des membres, log, conservation des autres membres et invalidation des anciennes signatures ; lu. |
| `RpgMakerSaveCodec.kt` | Détection JSON/MV/MZ, conservation du conteneur, contrôle JSON avant parsing récursif ; corrigé. |
| `SaveCodec.kt` | Validation avant patch, relecture après patch, taille finale, précision JavaScript ; corrigé. |
| `SaveCompression.kt` | Inflation zlib bornée, troncature, libération inflater/deflater ; limites communes ajoutées. |
| `SaveManager.kt` | Backup, restauration, confinement, concurrence, vérification/rollback, rétention ; corrigé. |
| `SaveModels.kt` | Modèles d’édition et champs simples ; lu, conservé. |
| `GameToolsController.kt` | Chargement, invalidation du hash, erreurs, réservation du busy, restauration/suppression ; corrigé. |
| `PythonStringLiterals.kt` (nouveau) | Décodage explicite des échappements pickle, sans évaluateur Python. |

## Validation

Le premier passage de `SaveSafetyTest` comprenait 16 cas JVM couvrant les défauts ci-dessus ; l’addendum décrit les trois cas ajoutés ensuite. Les anciennes régressions MV/MZ, Ruby et Ren’Py sont conservées. `scripts/generate-save-safety-fixtures.py` génère uniquement des données synthétiques connues ; `scripts/verify-renpy-fixtures.py` relit avec CPython uniquement les fixtures produites par les tests JVM, jamais des sauvegardes utilisateur.

Premier passage, avant les corrections de l’addendum : **168 tests JVM réussis, zéro échec/erreur/skip**, dont les **16 tests SaveSafetyTest**. La relecture indépendante CPython a réussi pour les fixtures protocoles 2/4/5 avec Unicode/références et 0/2/4/5 avec grands entiers, bytearrays et état personnalisé. À ce premier passage, les builds debug/release R8, APK de tests Android et modules Baseline Profile avaient compilé, mais aucun test appareil n’avait encore été exécuté. La validation native réalisée ensuite est détaillée plus bas. `git diff --check` ciblé : aucun défaut d’espacement. Voir [le rapport global](AUDIT_2026-10-05.md) pour les preuves et limites.

## Limites explicites

- Les fixtures sont synthétiques. Elles ne constituent pas une validation de chargement dans les jeux réels, JoiPlay ou un téléphone physique.
- Le fournisseur SAF ne propose pas de comparaison/remplacement atomique. Le contrôle de conflit réduit la fenêtre de concurrence sans pouvoir empêcher une écriture simultanée du jeu exactement au même instant ; fermer le jeu reste nécessaire.
- Les buffers pickle externes, références persistantes, types de clés non pris en charge, symboles Ruby non UTF-8 et payloads personnalisés opaques ne sont pas rendus éditables. Un format non reconnu échoue avant remplacement du fichier.
- Les grands entiers, feuilles pickle partagées et octets binaires restent en lecture seule. L’éditeur affiche au plus 20 000 entrées et une profondeur de 32 niveaux ; les données non affichées sont conservées.
- Les budgets de sûreté refusent les sauvegardes excessivement volumineuses/complexes au lieu de saturer le processus. Les modifications ne sont pas un benchmark sur une bibliothèque réelle.
- Les signatures Ren’Py anciennes sont invalidées après modification ; le jeu peut demander de faire confiance à la sauvegarde modifiée.

## Addendum — revalidation des formats après les 168 premiers tests

La relecture du XML `TEST-fr.astragames.app.data.saves.SaveSafetyTest.xml` a confirmé 16 cas exécutés, sans échec ni saut, au passage du 5 octobre à 06:32:18 UTC. Ce succès ne validait pas encore l’interopérabilité Ruby : les cas Marshal existants encodaient et décodaient souvent avec la même implémentation Kotlin. Trois défauts supplémentaires ont donc été reproduits et corrigés :

- **Racines RGSS multiflux.** Une sauvegarde XP/VX peut écrire plusieurs objets Marshal successifs, dont des compteurs entiers. Modifier `root[1]` échouait à la vérification, car l’éditeur remplaçait un élément d’une copie de la liste des flux. La liste réellement sérialisée est maintenant modifiée ; une racine scalaire unique fonctionne également. Reproduction avant correctif sur les classes compilées : `Verification echouee : root[1]`.
- **Références Ruby des objets `_dump`.** Ruby enregistre ces objets dans sa table de références après les attributs du bloc retourné par `_dump`. Notre lecteur et notre écrivain le faisaient avant. Le double décalage pouvait masquer le défaut lors d’un aller-retour, mais exposait un objet binaire à la place d’un texte et inversement dans l’éditeur. Le nouvel ordre suit [l’implémentation Ruby officielle](https://github.com/ruby/ruby/blob/master/marshal.c). Une fixture produite par Ruby contient un objet personnalisé, son attribut texte partagé et une deuxième référence au même objet ; les tests vérifient types, chemins et identités, pas seulement la valeur éditée.
- **Textes Ren’Py Python 2.** Les opcodes historiques `STRING`, `BINSTRING` et `SHORT_BINSTRING` étaient tous considérés comme des octets non éditables ; les clés `money` apparaissaient sous forme hexadécimale et n’étaient pas reconnues en mode simple. Ils sont distingués des véritables blocs `bytes` modernes. Le texte UTF-8 strict est modifiable, les clés restent typées, et l’écriture conserve le type Python 2 `str`. Les octets invalides restent intacts et en lecture seule. La fixture est relue avec les mêmes options UTF-8/surrogateescape que [Ren’Py officiel](https://github.com/renpy/renpy/blob/master/renpy/compat/pickle.py), puis avec `encoding="bytes"` pour vérifier que le type binaire ancien n’a pas été converti en Unicode.

Trois nouveaux tests portent `SaveSafetyTest` à **19 cas**. Les fixtures `rgss-userdef-links.rxdata`, `rgss-time.rxdata` et `rgss-streams.rxdata` sont produites par `scripts/verify-ruby-save-fixtures.rb generate`, avec JRuby 9.4.15.0 (compatibilité Ruby 3.1.7). Le JAR de vérification reste dans `build/verification-tools/`, hors sources et hors application ; SHA-256 vérifié contre Maven Central : `e8a461c48da851d839b8deb8b67b1987542ba5e23b8febfa0e60f0065e2639d2`. Le mode `verify` relit uniquement les sorties de nos tests avec Ruby et contrôle le payload personnalisé, les identités, une date `Time` avec microsecondes, le compteur modifié et les flux suivants inchangés. Cela ne remplace pas une exécution dans RGSS 1.8/1.9 ni dans un jeu réel.

Revue complémentaire du parcours d’écriture : la révision attendue du brouillon est vérifiée avant publication des champs après rechargement, puis à l’écriture ; le fichier est relu une seconde fois juste avant remplacement. Une restauration crée d’abord un backup de l’état courant et la rétention intervient après succès. Ces garanties sont établies par lecture des sources et les protections de codecs ; les tests JVM ne prouvent pas la résistance d’un fournisseur SAF réel aux coupures ni la survie d’un très gros brouillon au plafond Android du Bundle. Ces limites ne sont pas comptées comme des tests passés.

Validation finale de l’addendum : **200 tests JVM dans 39 suites, zéro échec, erreur ou test ignoré**, dont les **19 cas SaveSafetyTest** (XML du 5 octobre à 07:00:24 UTC). Les **8 relectures CPython** ont réussi, y compris les chaînes Python 2 ; les **3 relectures JRuby 9.4.15.0** ont confirmé les objets `_dump`, les dates `Time` et les flux Marshal successifs. Le second passage JVM a réussi en 7 secondes après correction des quatre avertissements de nullabilité des tests.

Les builds debug, release avec R8, APK de tests Android et Baseline Profile ont réussi. Lint : **0 erreur et 45 avertissements**. Preuves locales : `build/audit-verification-1.10.0.log`, `build/audit-unit-final-1.10.0.log` et XML sous `app/build/test-results/testDebugUnitTest/`. Ces résultats JVM et de construction sont complétés par la validation native suivante ; aucun chargement dans un jeu réel n’est revendiqué.

## Addendum — validation Android native

La suite portrait de **59 tests instrumentés** a réussi sur l’émulateur Android Studio, piloté avec les outils SDK/ADB natifs. Elle inclut les **6 tests `StorageWorkflowsTest`** et les **4 tests `SaveEditorUiTest`**. Les fichiers sont des fixtures synthétiques isolées : les sauvegardes passent par le fournisseur SAF debug et de véritables URI `content://`, sans désactiver les gardes de production.

- `StorageWorkflowsTest` vérifie notamment l’édition avec backup, le refus d’une révision périmée sans écriture, la restauration exacte avec une nouvelle copie de l’état précédent, le refus réel d’un chemin privé Astra et les URI SAF imbriquées. Les parcours de mods, de scan et d’export/restauration du catalogue complètent ses six cas.
- `SaveEditorUiTest` utilise le véritable `AstraViewModel` et son contrôleur. Ses quatre cas vérifient le brouillon avec confirmation d’abandon ou poursuite, les valeurs en lecture seule et métadonnées JsonEx protégées, le brouillon restauré puis enregistré avec backup exact, et le refus d’écraser un changement externe après restauration du brouillon.

`StateRestorationTester` détruit puis recompose le contenu en restaurant son état sauvegardé. Ces tests conservent le même ViewModel et ne tuent pas le processus Android ; ils ne constituent donc pas une preuve de survie à une mort réelle du processus, ni au dépassement du plafond du Bundle pour un très gros brouillon. Ils ne simulent pas non plus une coupure pendant l’écriture SAF. Les fournisseurs tiers, un téléphone physique et le chargement des slots dans les jeux concernés restent à vérifier.

La confirmation finale a réussi 61/61 tests en portrait, puis 32/32 tests UI en paysage à 150 % et 32/32 en portrait à 150 %. Les quatre tests d’éditeur passent dans chaque configuration. Un passage combinant paysage et police 1,5 a montré que la valeur `null`, située sous son libellé dans la liste défilante, nécessitait un défilement avant l’assertion de visibilité ; le test cible désormais cette valeur via `performScrollTo()` et conserve son assertion. Le nouveau passage confirme la visibilité après défilement et le maintien en lecture seule. Le [rapport de validation Android](ANDROID_VALIDATION_2026-10-05.md) centralise les configurations réellement utilisées, journaux et résultats des matrices.
