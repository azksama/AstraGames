package fr.astragames.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.astragames.app.core.collections.FolderHierarchy
import fr.astragames.app.core.collections.SmartCollectionEvaluator
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.repository.CollectionRuleDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ruleSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<CollectionRuleDraft>, String>(
    save = { rules -> rules.flatMap { listOf(it.field, it.operator, it.value) } },
    restore = { values -> mutableStateListOf<CollectionRuleDraft>().apply { addAll(values.chunked(3).map { CollectionRuleDraft(it[0], it[1], it[2]) }) } }
)

internal fun CollectionRuleDraft.isValid(state: AstraUiState) = SmartCollectionEvaluator.isValidRule(field, operator, value,
    tagExists = { id -> state.tags.any { it.id == id } }, folderExists = { id -> state.folders.any { it.id == id } })

internal fun collectionPreviewCount(state: AstraUiState, mode: String, rules: List<CollectionRuleDraft>): Int {
    if (rules.isEmpty() || rules.any { !it.isValid(state) }) return 0
    val collection = CollectionEntity("preview", "", matchMode = mode)
    val entities = rules.mapIndexed { i, r -> CollectionRuleEntity(i.toString(), collection.id, r.field, r.operator, r.value) }
    val refs = if (rules.any { it.field == "TAG" }) state.gameTagRefs.groupBy { it.gameId } else emptyMap()
    val folderIds = if (rules.any { it.field == "FOLDER" }) FolderHierarchy(state.folders).ruleMemberships(entities) else emptyMap()
    val now = System.currentTimeMillis()
    return state.games.count { game -> SmartCollectionEvaluator.matches(collection, entities, game,
        refs[game.id].orEmpty(), state.folders, state.playStats[game.id], now, folderIds) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartCollectionEditorDialog(
    collection: CollectionEntity?, existingRules: List<CollectionRuleEntity>, state: AstraUiState,
    onSave: suspend (String?, String, String, List<CollectionRuleDraft>) -> Unit, onDismiss: () -> Unit
) {
    var name by rememberSaveable(collection?.id) { mutableStateOf(collection?.name.orEmpty()) }
    var mode by rememberSaveable(collection?.id) { mutableStateOf(collection?.matchMode ?: "ALL") }
    val rules = rememberSaveable(collection?.id, saver = ruleSaver) {
        mutableStateListOf<CollectionRuleDraft>().apply { addAll(existingRules.map { CollectionRuleDraft(it.field, it.operator, it.value) }) }
    }
    val snapshot = rules.toList()
    val valid = snapshot.isNotEmpty() && snapshot.all { it.isValid(state) }
    val hierarchy = remember(state.folders) { FolderHierarchy(state.folders) }
    val submission = rememberDialogSubmission()
    val dismiss = { if (!submission.pending) onDismiss() }
    val preview by produceState<Int?>(null, snapshot, mode, state.games, state.tags, state.folders, state.gameTagRefs, state.playStats) {
        value = null
        value = withContext(Dispatchers.Default) { collectionPreviewCount(state, mode, snapshot) }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AstraDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ScrollingScaffold(topBar = { CompactHeader("Collections", onBack = dismiss) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                    val intro: LazyListScope.() -> Unit = {
                        item { PageHeading(if (collection == null) "Nouvelle collection" else "Modifier la collection", "Une sélection qui évolue avec votre bibliothèque.") }
                        item { OutlinedTextField(name, { name = it; submission.clearError() }, Modifier.fillMaxWidth(), enabled = !submission.pending, label = { Text("Nom de la collection") }, singleLine = true, shape = RoundedCornerShape(12.dp)) }
                    }
                    val ruleContent: LazyListScope.() -> Unit = {
                        item {
                            Text("Règles", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("Inclure les jeux qui respectent :", style = MaterialTheme.typography.bodyMedium)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                listOf("ALL" to "Toutes les règles", "ANY" to "Au moins une").forEachIndexed { i, (key, label) ->
                                    SegmentedButton(selected = mode == key, onClick = { mode = key; submission.clearError() }, enabled = !submission.pending, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                                }
                            }
                        }
                        itemsIndexed(rules, key = { index, _ -> index }) { index, rule ->
                            RuleCard(index, rule, state, hierarchy, enabled = !submission.pending,
                                onChange = { rules[index] = it; submission.clearError() },
                                onRemove = { rules.removeAt(index); submission.clearError() })
                        }
                        item { OutlinedButton(onClick = { rules += CollectionRuleDraft("ENGINE", "IS", defaultRuleValue("ENGINE", state)); submission.clearError() }, Modifier.fillMaxWidth(), enabled = !submission.pending) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Ajouter une règle")
                        } }
                    }
                    val actions: LazyListScope.() -> Unit = {
                        item {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Aperçu des résultats", style = MaterialTheme.typography.titleMedium)
                                    if (preview == null) Text("Calcul en cours…") else Text("${preview} jeux", style = MaterialTheme.typography.titleLarge)
                                    Text("La collection se mettra à jour automatiquement.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        submission.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                        item { Button(onClick = { submission.submit { onSave(collection?.id, name.trim(), mode, snapshot) } }, enabled = !submission.pending && name.isNotBlank() && valid,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if (collection == null) "Créer la collection" else "Enregistrer") } }
                    }
                    if (LandscapeLayout) Row(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(.42f).fillMaxHeight(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { intro(); actions() }
                        LazyColumn(Modifier.weight(.58f).fillMaxHeight(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = ruleContent)
                    } else LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { intro(); ruleContent(); actions() }

                }
            }
        }
    }
}

@Composable
private fun RuleCard(index: Int, rule: CollectionRuleDraft, state: AstraUiState, hierarchy: FolderHierarchy, enabled: Boolean, onChange: (CollectionRuleDraft) -> Unit, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.background, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(if (LandscapeLayout) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (LandscapeLayout) 8.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Règle", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Text((index + 1).toString(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Default.DeleteOutline, AppLocalizer.text("Retirer", LocalAppLanguage.current), Modifier.size(20.dp)) }
            }
            val fieldPicker: @Composable () -> Unit = {
                DropdownSelector("Champ", SmartCollectionEvaluator.fields, rule.field, enabled, text = { it.fieldLabel() }) { field ->
                    onChange(CollectionRuleDraft(field, defaultOperator(field), defaultRuleValue(field, state)))
                }
            }
            val operatorPicker: @Composable () -> Unit = {
                DropdownSelector("Condition", SmartCollectionEvaluator.operatorsFor(rule.field), rule.operator, enabled, text = { it.operatorLabelRaw() }) { onChange(rule.copy(operator = it)) }
            }
            if (LandscapeLayout && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.2f) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { fieldPicker() }
                Box(Modifier.weight(1f)) { operatorPicker() }
            } else { fieldPicker(); operatorPicker() }
            when (rule.field) {
                "ENGINE" -> DropdownSelector("Moteur", GameEngine.entries.map { it.name }, rule.value, enabled, text = { it.readableEngine() }) { onChange(rule.copy(value = it)) }
                "TAG" -> SearchableTagSelector(state.tags, rule.value, enabled) { onChange(rule.copy(value = it)) }
                "FOLDER" -> {
                    val fallback = AppLocalizer.text(if (rule.value.isEmpty()) "Dossier" else "Dossier supprimé", LocalAppLanguage.current)
                    DropdownSelector("Dossier", state.folders.map { it.id }, rule.value, enabled, localizeValues = false, text = { id -> hierarchy.pathOf(id) ?: fallback }) { onChange(rule.copy(value = it)) }
                }
                "FAVORITE", "COVER", "AVAILABLE" -> DropdownSelector("Valeur", listOf("true", "false"), rule.value, enabled, text = { if (it == "true") "Oui" else "Non" }) { onChange(rule.copy(value = it)) }
                else -> if (rule.operator != "NEVER") OutlinedTextField(rule.value, { onChange(rule.copy(value = it.replace(',', '.'))) }, Modifier.fillMaxWidth(),
                    label = { Text(if (rule.field == "PLAY_TIME") "Durée en heures" else "Nombre de jours") }, singleLine = true,
                    enabled = enabled, isError = !rule.isValid(state), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            }
            if (!rule.isValid(state)) Text("Complétez cette règle.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
