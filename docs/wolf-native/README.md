# Recherche du moteur Wolf natif Android

Travail du 10 octobre 2026 pour Astra. **État : analyse des exécutables et sonde de formats livrées; interpréteur Android à construire.** Le moteur jouable de l’application reste celui décrit dans [Wolf Windows intégré](../wolf-windows-runtime.md).

- [Analyse REA et fonctions identifiées](REA_ANALYSIS.md) : preuves, adresses, limites et différences entre les EXE officiels.
- [Formats et corpus](FORMATS_RESEARCH.md) : cartes/CommonEvent, 53 723 commandes, encodages et compression.
- [Architecture et jalons Android](RECONSTRUCTION.md) : cœur C++, VM, rendu, accès direct, sauvegardes et validation.
- [Manifeste des 34 fichiers](fixtures.json), [synthèse du corpus](corpus-summary.json), [mesure hôte](parse-benchmark.json) et [index REA dérivé](evidence-index.json) : 91 preuves et cinq inconnues ouvertes.

Les exécutables, archives téléchargées, textes complets des jeux, pseudo-code et dossiers REA complets restent sous `build/`, ignoré par Git. Les fichiers suivis contiennent la recherche et ses références. Les EXE n’ont pas été exécutés pendant cette analyse statique ni modifiés.

## Réexécuter la sonde

Python 3.12+ suffit; aucune dépendance PyPI. Obtenir les échantillons officiels depuis [le site Wolf RPG](https://silversecond.com/WolfRPGEditor/) et vérifier leurs empreintes avec `fixtures.json`. Les lecteurs et profils établis ici concernent **2.2961 / 3.729**; ne pas remplacer une version par une autre en conservant les mêmes conclusions.

```powershell
python tools/wolf_native_probe.py `
  build/wolf-research/sample22961/WOLF_RPG_Editor2 `
  build/wolf-research/sample3729/WOLF_RPG_Editor3 `
  --common-events --output build/wolf-rea/maps-and-common-probe-recheck.json
python -m unittest discover -s tools/tests -p test_wolf_native_probe.py -v
```

La sonde refuse les liens/jonctions et une sortie dans le dossier source. Elle est en lecture seule et n’exécute pas les commandes. `--commands` conserve aussi les paramètres et chaînes des commandes dans le rapport; réserver ces sorties au dossier local `build/`. Le test de corpus officiel est ignoré explicitement lorsque les échantillons ne sont pas disponibles; les tests synthétiques restent exécutés.

## Reprendre REA

Versions utilisées : REA **6.3.0**, Ghidra **12.1.4**, Node **24.21.0**, JBR/JDK **21**. Le provider est `ghidra`; le preset est `ghidra-default`. Les chemins ci-dessous correspondent à cette machine et sont à adapter à une autre installation. Le client n’installe rien et ne modifie aucune inscription MCP.

Le MCP de l’application a atteint deux fois son délai de démarrage de 330 secondes sur le premier EXE. `tools/research/rea_session.mjs` est un client stdio de recherche pour le même serveur REA public : il applique `REA_GHIDRA_STARTUP_TIMEOUT_MS=1200000` uniquement à son processus enfant. Les opérations suivantes réutilisent la session analysée. Utiliser un **dossier de session neuf** : les réponses sont écrites exclusivement, sans remplacement.

```powershell
$reaNode = 'C:/Users/AZK/AppData/Local/Programs/REA/runtime/node-v24.21.0-win-x64/node.exe'
$reaPackage = 'C:/Users/AZK/AppData/Local/Programs/REA/cli/node_modules/rea-agents'
$reaGhidra = 'C:/Users/AZK/AppData/Local/Programs/REA/tools/ghidra_12.1.4_PUBLIC'
$reaJava = 'C:/Program Files/Android/Android Studio/jbr'
& $reaNode tools/research/rea_session.mjs $reaPackage $reaGhidra $reaJava `
  build/wolf-rea/replay2 build/wolf-research/sample22961/WOLF_RPG_Editor2/Game.exe
```

Le client ouvre l’EXE, demande `binary_overview`, puis consomme les requêtes JSON de `replay2/requests/` en ordre de nom. Il conserve le `CallToolResult` complet dans `responses/`. Dans un deuxième terminal, après l’ouverture :

```powershell
$reaRequests = 'build/wolf-rea/replay2/requests'
@{name='search_strings';arguments=@{pattern='CommonEvent'}} |
  ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 "$reaRequests/10-common.json"
@{name='procedure_pseudo_code';arguments=@{procedure='0x463080'}} |
  ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 "$reaRequests/20-wait.json"
```

`0x463080` appartient uniquement au hash 2.2961 étudié. Les noms de fonctions `FUN_*` et les pseudo-types sont produits par Ghidra; ils ne sont pas le code source original. Pour 3.729, utiliser une nouvelle session et les adresses de son propre dossier.

Exporter avant de fermer :

```powershell
$reaExport = [IO.Path]::GetFullPath('build/wolf-rea/replay2/evidence.json')
$reaSnapshot = [IO.Path]::GetFullPath('build/wolf-rea/replay2/snapshot.json')
@{name='export_evidence_bundle';arguments=@{path=$reaExport}} |
  ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 "$reaRequests/90-export.json"
@{name='close_binary';arguments=@{snapshot_path=$reaSnapshot}} |
  ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 "$reaRequests/91-close.json"
```

Attendre la **réponse de fermeture réussie** dans `responses/91-close.json`, puis créer `replay2/stop` pour terminer le client. Si l’export ou la fermeture retourne une erreur, la session reste à traiter; la seule présence du fichier de réponse ne prouve pas la réussite. Le client ferme aussi une session encore ouverte lors d’un arrêt normal par le fichier `stop`.

Pour relire le snapshot conservé, passer son chemin comme dernier argument optionnel après l’EXE dans un nouveau dossier de session. REA contrôle la correspondance avec l’artefact. Le snapshot exporté ici conserve seulement les requêtes déclarées éligibles : il ne remplace pas toutes les opérations Ghidra.

Le dossier du répartiteur 2.2961 dépassait les 10 MiB de réponse MCP. REA l’a conservé intégralement avec son Evidence ID; `export_evidence_bundle` a permis de le lire sans troncature. Réexporter la session suffit pour récupérer ce cas; ne pas le présenter comme une analyse échouée ou une source récupérée.

## Reconstruire l’index des preuves

```powershell
python tools/wolf_rea_index.py `
  build/wolf-rea/wolf2/evidence.json build/wolf-rea/wolf3/evidence.json `
  --output build/wolf-rea/evidence-index-recheck.json
```

L’index est une projection dérivée, sans pseudo-code ni assembler complets. Il conserve hash du bundle, sujets, profils d’analyse, Evidence IDs, relations, limites, inconnues et tables de saut. `dispatchCases` relève les appels directs avant la première branche depuis l’adresse exacte de chaque case. Un label peut être un sous-mode ou une opération interne : il ne devient pas automatiquement un opcode du format de fichier. Les valeurs de case inconnues et les limitations de récupération restent explicites.

Les bundles complets et snapshots locaux constituent la preuve détaillée. Les conserver avec leurs empreintes si la recherche est déplacée : le dossier temporaire Ghidra est supprimé lors de `close_binary`. Les 34 fichiers d’origine ont été recontrôlés inchangés après la recherche.
