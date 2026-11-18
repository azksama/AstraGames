package fr.astragames.app.ui

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.data.local.TagEntity

@Composable
internal fun TagsScreen(state: AstraUiState, vm: AstraViewModel, onPickTags: () -> Unit, onBack: () -> Unit) {
    var categoriesMode by rememberSaveable { mutableStateOf(false) }
    var mergesMode by rememberSaveable { mutableStateOf(false) }
    var tagQuery by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable(stateSaver = idSetSaver) { mutableStateOf(emptySet<String>()) }
    var editingTagId by rememberSaveable { mutableStateOf<String?>(null) }
    var tagDialog by rememberSaveable { mutableStateOf(false) }
    var editingCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryDialog by rememberSaveable { mutableStateOf(false) }
    var moveDialog by rememberSaveable { mutableStateOf(false) }
    var deletingCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteTags by rememberSaveable { mutableStateOf(false) }
    val editingTag = editingTagId?.let { id -> state.tags.firstOrNull { it.id == id } }
    val editingCategory = editingCategoryId?.let { id -> state.tagCategories.firstOrNull { it.id == id } }
    BackHandler(selected.isNotEmpty() && !moveDialog && !confirmDeleteTags && !tagDialog) { selected = emptySet() }
    LaunchedEffect(state.tags, state.settingsLoaded, moveDialog, confirmDeleteTags) {
        if (state.settingsLoaded && !moveDialog && !confirmDeleteTags && selected.isNotEmpty()) {
            selected = selected.intersect(state.tags.mapTo(hashSetOf()) { it.id })
        }
    }

    ScrollingScaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactHeader(if (mergesMode) "Fusions de tags" else if (categoriesMode) "Catégories de tags" else "Tags", "${state.tags.size} tags", onBack = onBack) {
                if (!mergesMode) IconButton(onClick = {
                    if (categoriesMode) { editingCategoryId = null; categoryDialog = true } else { editingTagId = null; tagDialog = true }
                }) { Icon(Icons.Default.Add, AppLocalizer.text("Ajouter", LocalAppLanguage.current)) }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            androidx.compose.foundation.lazy.LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(!categoriesMode && !mergesMode, { categoriesMode = false; mergesMode = false }, { Text("Tags") }) }
                item { FilterChip(categoriesMode && !mergesMode, { categoriesMode = true; mergesMode = false; selected = emptySet() }, { Text("Catégories") }) }
                item { FilterChip(mergesMode, { mergesMode = true; categoriesMode = false; selected = emptySet() }, { Text("Fusions") }) }
            }
            if (!categoriesMode && !mergesMode) FilledTonalButton(
                onClick = onPickTags,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Importer des tags") }
            if (selected.isNotEmpty() && !categoriesMode && !mergesMode) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size} sélectionné(s)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { moveDialog = true }) { Text("Classer") }
                    IconButton(onClick = { confirmDeleteTags = true }) { Icon(Icons.Default.DeleteOutline, AppLocalizer.text("Supprimer")) }
                }
            }
            if (mergesMode) TagMergesManager(state, vm)
            else if (categoriesMode) CategoryManager(state, vm, { editingCategoryId = it.id; categoryDialog = true }, { deletingCategoryId = it.id })
            else {
                OutlinedTextField(
                    value = tagQuery,
                    onValueChange = { tagQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Rechercher un tag") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true
                )
                TagManager(state, selected, tagQuery, { selected = selected.toggle(it) }, { editingTagId = it.id; tagDialog = true })
            }
        }
    }
    if (tagDialog && (editingTagId == null || editingTag != null)) {
        TagEditDialog(editingTag, state.tagCategories, onSave = { name, categoryId, changed ->
            if (editingTag == null) vm.createTag(name, categoryId)
            else vm.editTag(editingTag, name, categoryId, changed)
            tagDialog = false
        }, onDismiss = { tagDialog = false })
    }
    if (categoryDialog && (editingCategoryId == null || editingCategory != null)) {
        NameDialog(
            title = if (editingCategory == null) "Nouvelle catégorie" else "Renommer la catégorie",
            initial = editingCategory?.name.orEmpty(),
            onSave = { name ->
                if (editingCategory == null) vm.createTagCategory(name)
                else vm.renameTagCategory(editingCategory, name)
                categoryDialog = false
            },
            onDismiss = { categoryDialog = false },
            draftKey = editingCategoryId.orEmpty()
        )
    }
    if (moveDialog) CategoryChoiceDialog(state.tagCategories, onChoice = { categoryId ->
        vm.moveTagsToCategory(selected, categoryId)
        selected = emptySet()
        moveDialog = false
    }, onDismiss = { moveDialog = false })
    if (confirmDeleteTags) ConfirmDialog("Supprimer les tags sélectionnés ?", "Les tags seront retirés de tous les jeux associés.", {
        vm.deleteTags(selected)
        selected = emptySet()
        confirmDeleteTags = false
    }, { confirmDeleteTags = false })
    deletingCategoryId?.let { id -> state.tagCategories.firstOrNull { it.id == id } }?.let { category ->
        ConfirmDialog("Supprimer « ${category.name} » ?", "Les tags seront conservés sans catégorie.", {
            vm.deleteTagCategory(category)
            deletingCategoryId = null
        }, { deletingCategoryId = null })
    }
}


@Composable
internal fun TagMergesManager(state: AstraUiState, vm: AstraViewModel) {
    val merges by vm.tagMerges.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshTagMerges() }
    if (merges.isEmpty()) {
        CenterMessage("Aucune fusion à vérifier", Modifier.fillMaxSize())
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = PageBottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(merges, key = { "${it.first.id}|${it.second.id}" }) { suggestion ->
                MergeSuggestionCard(suggestion, onMerge = { keep, removed -> vm.mergeTagPair(keep, removed) }, onIgnore = { vm.ignoreTagMerge(suggestion.first, suggestion.second) })
            }
        }
    }
}

@Composable
internal fun MergeSuggestionCard(suggestion: TagMergeSuggestion, onMerge: (TagEntity, TagEntity) -> Unit, onIgnore: () -> Unit) {
    var keepFirst by remember(suggestion.first.id, suggestion.second.id) { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ressemblance : ${(suggestion.similarity * 100).toInt()} %", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onIgnore) { Text("Ignorer") }
                TextButton(onClick = { onMerge(if (keepFirst) suggestion.first else suggestion.second, if (keepFirst) suggestion.second else suggestion.first) }) { Text("Fusionner") }
            }
            listOf(suggestion.first to true, suggestion.second to false).forEach { (tag, isFirst) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .selectable(keepFirst == isFirst, role = Role.RadioButton) { keepFirst = isFirst }
                        .background(if (keepFirst == isFirst) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(keepFirst == isFirst, null)
                    Spacer(Modifier.width(6.dp))
                    MaterialText(tag.name, Modifier.weight(1f), fontWeight = if (keepFirst == isFirst) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
@Composable
internal fun TagManager(state: AstraUiState, selected: Set<String>, query: String, onToggle: (String) -> Unit, onEdit: (TagEntity) -> Unit) {
    val groups = remember(state.tags, state.tagCategories, query) {
        val term = query.trim()
        val byCategory = state.tags.asSequence().filter { term.isEmpty() || it.name.contains(term, ignoreCase = true) }
            .groupBy { it.groupName }
        val known = state.tagCategories.mapTo(linkedSetOf()) { it.name }
        val legacy = byCategory.keys.filterNotNull().filterNot(known::contains).sorted()
        buildList<Pair<String?, List<TagEntity>>> {
            for (name in known + legacy) byCategory[name]?.let { add(name to it) }
            byCategory[null]?.let { add(null to it) }
        }
    }
    if (state.tags.isEmpty()) CenterMessage("Aucun tag. Utilisez + pour en créer un directement.", Modifier.fillMaxSize())
    else if (groups.isEmpty()) CenterMessage("Aucun résultat pour cette recherche.", Modifier.fillMaxSize())
    else LazyColumn(contentPadding = PaddingValues(bottom = PageBottomPadding)) {
        groups.forEach { (category, tags) ->
            item(key = "h:" + (category?.let { "named:$it" } ?: "uncategorized")) { MaterialText(category ?: AppLocalizer.text("Sans catégorie"), Modifier.padding(start = 16.dp, top = 14.dp, bottom = 5.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
            items(tags, key = { "tag:" + it.id }) { tag ->
                RoundedListItem(
                    modifier = Modifier.toggleable(tag.id in selected, role = Role.Checkbox) { onToggle(tag.id) }, headlineContent = { MaterialText(tag.name) },
                    leadingContent = { Checkbox(tag.id in selected, null) },
                    trailingContent = { IconButton(onClick = { onEdit(tag) }) { Icon(Icons.Default.Edit, AppLocalizer.text("Modifier", LocalAppLanguage.current)) } }
                )
            }
        }
    }
}

@Composable
internal fun CategoryManager(state: AstraUiState, vm: AstraViewModel, onEdit: (TagCategoryEntity) -> Unit, onDelete: (TagCategoryEntity) -> Unit) {
    if (state.tagCategories.isEmpty()) CenterMessage("Créez des catégories puis classez plusieurs tags en une fois.", Modifier.fillMaxSize())
    else {
        val counts = remember(state.tags) { state.tags.groupingBy { it.groupName }.eachCount() }
        LazyColumn(contentPadding = PaddingValues(bottom = PageBottomPadding)) {
            itemsIndexed(state.tagCategories, key = { _, category -> category.id }) { index, category ->
                TagCategoryCard(category, counts[category.name] ?: 0,
                    onMoveUp = { vm.moveTagCategory(category.id, -1) },
                    onMoveDown = { vm.moveTagCategory(category.id, 1) },
                    onEdit = { onEdit(category) }, onDelete = { onDelete(category) },
                    canMoveUp = index > 0, canMoveDown = index < state.tagCategories.lastIndex)
            }
        }
    }
}

@Composable
internal fun TagCategoryCard(
    category: TagCategoryEntity, tagCount: Int,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit,
    canMoveUp: Boolean = true, canMoveDown: Boolean = true
) {
    // Four 48 dp actions alongside a leading icon leave no readable title on a compact phone.
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Folder, null, Modifier.padding(top = 4.dp))
            Column(Modifier.weight(1f)) {
                MaterialText(category.name, style = MaterialTheme.typography.bodyLarge)
                Text("$tagCount tag(s)", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ArrowUpward, AppLocalizer.text("Monter")) }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ArrowDownward, AppLocalizer.text("Descendre")) }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Edit, AppLocalizer.text("Renommer")) }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.DeleteOutline, AppLocalizer.text("Supprimer")) }
        }
    }
}
