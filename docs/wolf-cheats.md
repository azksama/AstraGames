# Cheats et variables dans Wine

Ouvrir **Menu → Cheats et variables** pendant le jeu. Aucune recherche ne tourne tant que vous ne la demandez pas.

1. Choisir le type de valeur et saisir la valeur visible dans le jeu. Commencer par Entier 32 bits pour un compteur classique. Entiers 8/16/64 bits et nombres décimaux 32/64 bits sont également disponibles.
2. Lancer une recherche exacte, inconnue ou par intervalle. Revenir au jeu et faire varier le compteur.
3. Affiner : égale à, a changé, inchangée, augmentée, diminuée ou intervalle. Un affinage peut être annulé.
4. Ouvrir un résultat pour lire sa valeur actuelle et la modifier. **Annuler la dernière modification** rétablit la valeur précédente si la relecture le permet.
5. **Épingler** donne un nom personnel à une valeur. **Valeurs suivies** permet de retrouver ces noms/adresses, relire, modifier, renommer, figer, libérer ou retirer une valeur.

Le gel continue lorsque le panneau est fermé et s’arrête avec le jeu. Adresses, noms et gels sont limités à cette session ; ils ne sont pas appliqués automatiquement au prochain lancement. Une valeur figée doit être libérée avant modification manuelle. La recherche garde au maximum 2 097 152 candidats et signale explicitement toute troncature ; un affinage ne réintroduit pas les adresses écartées par cette limite.

## Variables du moteur Wolf

L’onglet **Variables Wolf** interroge directement les données reconnues du moteur. Il reconnaît les exécutables officiels Wolf **2.2961 et 3.729** par leur SHA-256 exact ; une autre identité affiche un refus et laisse disponible le scanner générique.

Choisir numérique ou texte commun, rechercher par ID/catégorie/groupe ou valeur, puis ouvrir une variable. La modification exige que la valeur et la session correspondent encore à la lecture. Le helper relit le résultat ; **Annuler** exige également que la valeur modifiée n’ait pas changé depuis. Le remplacement des chaînes est limité à la capacité existante : aucun agrandissement d’allocation du jeu n’est tenté.

Les variables DB, système et self, les exécutables personnalisés et toutes les versions du moteur ne sont pas couverts. Les IDs sont présentés avec leur groupe ; aucun nom de variable n’est inventé. Les identités reconnues et les accès aux données sont décrits dans les sources du [helper Wolf](../scripts/wolf-runtime/wolf_data.h) et de son [adaptateur Android](../app/src/main/java/fr/astragames/app/windows/WolfEngineDataAdapter.kt).

## Couverture et limites

L’outil recherche des valeurs numériques dans les pages accessibles en lecture/écriture du processus. Il ne connaît pas automatiquement les noms internes des variables. Les valeurs encodées, chiffrées, recalculées immédiatement, non alignées ou placées dans un autre processus peuvent ne pas être trouvées ou ne pas rester modifiées. Il ne s’agit pas d’une garantie pour tous les jeux. Une modification d’un compteur peut être enregistrée par le jeu dans sa sauvegarde : son annulation en mémoire ne restaure pas une sauvegarde déjà écrite.

Le binaire original d’Astra utilise les API Windows [VirtualQueryEx](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-virtualqueryex), [ReadProcessMemory](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-readprocessmemory) et WriteProcessMemory dans le préfixe Wine du jeu. Pas d’injection de DLL ni de service réseau. Le chemin retourné par [QueryFullProcessImageNameW](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-queryfullprocessimagenamew) doit correspondre exactement à l’exécutable lancé sur G:. Plusieurs correspondances sont refusées. Le handle de processus est conservé jusqu’à sa fin ; un PID recyclé n’est pas repris.

Avant une écriture, la valeur est relue et comparée à celle affichée. Cette précaution **n’est pas une opération atomique** avec l’exécution du jeu. Les gels sont rafraîchis toutes les 250 ms ; une allocation inaccessible ou différente désactive son écriture. Seules les pages engagées PAGE_READWRITE/WRITECOPY sont parcourues ; pages exécutables, protégées et de garde sont exclues. Les six types sont alignés naturellement, les résultats paginés par 128, le suivi limité à 128 valeurs et les gels à 64. L’annulation conserve la recherche précédente.

Les fichiers de communication sont privés et propres à chaque lancement. La fermeture de la session termine l’assistant et ne supprime que son répertoire temporaire. Elle ne supprime ni dossier source ni sauvegarde. Le helper reste actif tant que le jeu auquel il est attaché vit, y compris après 30 minutes sans nouvelle recherche.

## Sources et tests

Le helper est généré depuis [memory_agent.c](../scripts/wolf-runtime/memory_agent.c)
avec [build-memory-agent.py](../scripts/wolf-runtime/build-memory-agent.py).
L’option `--check` vérifie que le binaire embarqué correspond au résultat du build.
Les programmes de test synthétiques permettent d’exercer les types numériques,
l’affinage, l’écriture, l’annulation, le gel et les refus d’une valeur ou d’un
processus obsolète sans redistribuer un jeu privé.

Le [bilan public beta.24](VALIDATION_1.12.0-beta.24.md) précise les contrôles exécutés
et leur lien avec l’APK distribué. Les résultats d’une fixture ne garantissent
pas tous les jeux, et l’exécution ARM64 reste non démontrée.
