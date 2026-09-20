package fr.astragames.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import fr.astragames.app.core.collections.SmartCollectionEvaluator
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.repository.CollectionRuleDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ruleFields = listOf("ENGINE", "TAG", "FOLDER", "FAVORITE", "COVER", "AVAILABLE", "DATE_ADDED", "LAST_PLAYED", "PLAY_TIME")
private val ruleSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<CollectionRuleDraft>, String>(
    save = { rules -> rules.flatMap { listOf(it.field, it.operator, it.value) } },
    restore = { values -> mutableStateListOf<CollectionRuleDraft>().apply { addAll(values.chunked(3).map { CollectionRuleDraft(it[0], it[1], it[2]) }) } }
)

internal fun CollectionRuleDraft.isValid(state: AstraUiState): Boolean {
    if (field !in ruleFields || operator !in operatorsFor(field)) return false
    return when (field) {
        "ENGINE" -> GameEngine.entries.any { it.name == value }
        "TAG" -> state.tags.any { it.id == value }
        "FOLDER" -> state.folders.any { it.id == value }
        "FAVORITE", "COVER", "AVAILABLE" -> value.toBooleanStrictOrNull() != null
        "LAST_PLAYED" -> operator == "NEVER" || value.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true
        else -> value.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true
    }
}

internal fun collectionPreviewCount(state: AstraUiState, mode: String, rules: List<CollectionRuleDraft>): Int {
    if (rules.isEmpty() || rules.any { !it.isValid(state) }) return 0
    val collection = CollectionEntity("preview", "", matchMode = mode)
    val entities = rules.mapIndexed { i, r -> CollectionRuleEntity(i.toString(), collection.id, r.field, r.operator, r.value) }
    val refs = state.gameTagRefs.groupBy { it.gameId }
    val now = System.currentTimeMillis()
    return state.games.count { game -> !game.hidden && SmartCollectionEvaluator.matches(collection, entities, game,
        refs[game.id].orEmpty(), state.folders, state.playStats[game.id], now) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartCollectionEditorDialog(
    collection: CollectionEntity?, existingRules: List<CollectionRuleEntity>, state: AstraUiState,
    onSave: (String?, String, String, List<CollectionRuleDraft>) -> Unit, onDismiss: () -> Unit
) {
    var name by rememberSaveable(collection?.id) { mutableStateOf(collection?.name.orEmpty()) }
    var mode by rememberSaveable(collection?.id) { mutableStateOf(collection?.matchMode ?: "ALL") }
    val rules = rememberSaveable(collection?.id, saver = ruleSaver) {
        mutableStateListOf<CollectionRuleDraft>().apply { addAll(existingRules.map { CollectionRuleDraft(it.field, it.operator, it.value) }) }
    }
    val snapshot = rules.toList()
    val valid = snapshot.isNotEmpty() && snapshot.all { it.isValid(state) }
    val preview by produceState<Int?>(null, snapshot, mode, state) {
        value = null
        value = withContext(Dispatchers.Default) { collectionPreviewCount(state, mode, snapshot) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ScrollingScaffold(topBar = { CompactHeader("Collections", onBack = onDismiss) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        item { PageHeading(if (collection == null) "Nouvelle collection" else "Modifier la collection", "Une sélection qui évolue avec votre bibliothèque.") }
                        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nom de la collection") }, singleLine = true, shape = RoundedCornerShape(12.dp)) }
                        item {
                            Text("Règles", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("Inclure les jeux qui respectent :", style = MaterialTheme.typography.bodyMedium)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                listOf("ALL" to "Toutes les règles", "ANY" to "Au moins une").forEachIndexed { i, (key, label) ->
                                    SegmentedButton(selected = mode == key, onClick = { mode = key }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                                }
                            }
                        }
                        itemsIndexed(rules, key = { index, _ -> index }) { index, rule ->
                            RuleCard(index, rule, state, onChange = { rules[index] = it }, onRemove = { rules.removeAt(index) })
                        }
                        item { OutlinedButton(onClick = { rules += CollectionRuleDraft("ENGINE", "IS", defaultRuleValue("ENGINE", state)) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Ajouter une règle")
                        } }
                        item {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Aperçu des résultats", style = MaterialTheme.typography.titleMedium)
                                    if (preview == null) Text("Calcul en cours…") else Text("${preview} jeux", style = MaterialTheme.typography.titleLarge)
                                    Text("La collection se mettra à jour automatiquement.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item { Button(onClick = { onSave(collection?.id, name.trim(), mode, snapshot) }, enabled = name.isNotBlank() && valid,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if (collection == null) "Créer la collection" else "Enregistrer") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleCard(index: Int, rule: CollectionRuleDraft, state: AstraUiState, onChange: (CollectionRuleDraft) -> Unit, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.background, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Règle", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, AppLocalizer.text("Retirer"), Modifier.size(20.dp)) }
            }
            DropdownSelector("Champ", ruleFields, rule.field, { it.fieldLabel() }) { field ->
                onChange(CollectionRuleDraft(field, defaultOperator(field), defaultRuleValue(field, state)))
            }
            DropdownSelector("Condition", operatorsFor(rule.field), rule.operator, { it.operatorLabelRaw() }) { onChange(rule.copy(operator = it)) }
            when (rule.field) {
                "ENGINE" -> DropdownSelector("Moteur", GameEngine.entries.map { it.name }, rule.value, { it.readableEngine() }) { onChange(rule.copy(value = it)) }
                "TAG" -> SearchableTagSelector(state.tags, rule.value) { onChange(rule.copy(value = it)) }
                "FOLDER" -> DropdownSelector("Dossier", state.folders.map { it.id }, rule.value, { id -> state.folders.firstOrNull { it.id == id }?.name ?: "Dossier" }) { onChange(rule.copy(value = it)) }
                "FAVORITE", "COVER", "AVAILABLE" -> DropdownSelector("Valeur", listOf("true", "false"), rule.value, { if (it == "true") "Oui" else "Non" }) { onChange(rule.copy(value = it)) }
                else -> if (rule.operator != "NEVER") OutlinedTextField(rule.value, { onChange(rule.copy(value = it.replace(',', '.'))) }, Modifier.fillMaxWidth(),
                    label = { Text(if (rule.field == "PLAY_TIME") "Durée en heures" else "Nombre de jours") }, singleLine = true,
                    isError = !rule.isValid(state), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            }
            if (!rule.isValid(state)) Text("Complétez cette règle.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
