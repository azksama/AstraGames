# Validation Wolf natif — 1.12.0-beta.16

Cette version corrige le refus de tables de base de données qui contiennent des noms de lignes sans colonnes. Le diagnostic de la bêta 15 pour The Clumsy Witch Seris s'arrête dans le lecteur natif avec `SysDatabase.dat:payload@0x6c9d: invalid database row count 701`, avant le rendu. Le rapport joint ne contient pas les fichiers DAT/PROJECT du jeu : la correction et les tests ci-dessous reproduisent ce message avec des données valides indépendantes ; ils ne certifient pas une partie de ce jeu sur le Samsung de l'utilisateur.

## Cause et correction

Les noms de lignes sont stockés dans le PROJECT. Un type sans champs numériques ni texte n'a aucune valeur à lire dans le DAT, même s'il possède 701 lignes. `Data::ReadDat` du lecteur [WolfTL épinglé](https://github.com/Sinflower/WolfTL/blob/bfc38fc2735ab4f7f7ddbec8d57b7fef65f0712f/WolfTL/WolfRPG/Database.hpp) lit uniquement les deux blocs de valeurs, dont la taille est nulle dans ce cas.

Le lecteur Astra imposait à tort au moins un octet disponible par ligne. L'acceptation dépendait donc du nombre d'octets restant dans les types suivants ; une table valide placée en fin de fichier était refusée. La nouvelle règle utilise la taille réelle d'une ligne, y compris zéro, et valide le nombre de noms du PROJECT avant de construire les lignes. Les quotas cumulatifs d'entrée et d'allocations, le maximum de records, les tailles des vraies valeurs et le footer exact restent contrôlés. Les fichiers originaux ne sont pas réécrits.

## Régression et périmètre

Le test avant correction reproduit `invalid database row count 701`. Quatre nouvelles méthodes JVM vérifient ensuite les tables de 701 noms en CP932 et UTF-8, dans les quatre enveloppes C1/C2/C3/C4, en dernière position ou avant un type contenant un nombre et un texte. Elles vérifient tous les noms et identifiants, les valeurs du type suivant et l'intégrité des octets d'entrée. Les cas invalides couvrent noms PROJECT insuffisants, compte négatif ou excessif, quota d'allocations, valeur tronquée et footer manquant, incorrect ou suivi de données superflues.

Un nouveau test SDK lance le vrai `WolfNativeActivity` sur un jeu synthétique complet dont le registre système type 6 contient 701 noms sans colonnes. Il exige une première image native, les commandes devenues visibles, le diagnostic d'accès direct et l'absence d'import général. Le fichier de sauvegarde Windows synthétique reste inchangé. Les tests utilisent uniquement Android Emulator, ADB, Logcat et l'instrumentation du SDK.

Une sonde indépendante du SDK peut rejouer ce même jeu dans l'APK minifié R8 avec `nativeNameOnlyRoot`. Elle pilote les vraies commandes pour quitter le titre, déplacer le héros et enregistrer/restaurer sa position par le menu Astra. Elle ne dépend pas des noms de classes que R8 peut renommer. Elle vérifie les SHA-256 du DAT et du PROJECT avant/après et conserve séparément la mesure de la première image et la durée de l'ensemble du parcours.

Les résultats finaux, les versions des artifacts et les sommes SHA-256 sont publiés dans `VALIDATION_SUMMARY.json` et `SHA256SUMS.txt` avec la release. La qualification étendue de la [bêta 15](VALIDATION_1.12.0-beta.15.md) reste un résultat historique distinct : ses 156 tests Android ne sont pas additionnés aux tests exécutés pour cette correction.

Le build final compte **327 tests JVM du moteur et 239 tests JVM de l'application**, sans échec, erreur ni test ignoré. Les exemples officiels locaux sont présents et leurs lecteurs font partie de cette exécution. Le lot Android des six tests de lancement termine en **7,324 s**, sans échec ni test ignoré. Lint rapporte zéro erreur, zéro fatal et 106 avertissements.

La sonde de l'APK signé, non debuggable, passe sur Android Emulator API 36.1/x86_64 : table de 701 noms, titre carte 0, nouvelle partie carte 1, déplacement réel puis restauration de la position par une sauvegarde Astra v22. La première image du petit jeu synthétique est observée en 713 ms ; ce n'est pas une mesure de démarrage de Seris. Les codecs JNI décodent aussi les frames et le PCM de la fixture Theora/Vorbis. Les 16 bibliothèques ARM64/x86_64 ont des segments ELF alignés au moins à 16 Kio ; la signature historique v2/v3 et l'alignement ZIP de l'APK sont vérifiés.

Les 34 originaux du manifeste local gardent leurs tailles et SHA-256. Le graphe local est actualisé sur les six fichiers de code modifiés : 10 521 nœuds, 30 614 arêtes et 472 communautés. Cette mise à jour reste une extraction AST partielle ; elle ne constitue pas une nouvelle qualification sémantique de tout le moteur.

## Limites

Ce correctif traite le lecteur de bases, pas une incompatibilité graphique ou une mesure de fluidité. L'émulateur x86_64 ne remplace pas l'exécution ARM64 physique. Les protections et commandes non qualifiées conservent le secours Winlator et les limites de la [couverture native](wolf-native/RUNTIME_COVERAGE.md).
