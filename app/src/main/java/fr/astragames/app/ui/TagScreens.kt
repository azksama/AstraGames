package fr.astragames.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var categoriesMode by remember { mutableStateOf(false) }
    var mergesMode by remember { mutableStateOf(false) }
    var tagQuery by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var editingTag by remember { mutableStateOf<TagEntity?>(null) }
    var tagDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<TagCategoryEntity?>(null) }
    var categoryDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var deleteCategory by remember { mutableStateOf<TagCategoryEntity?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactHeader(if (mergesMode) "Fusions de tags" else if (categoriesMode) "Catégories de tags" else "Tags", "${state.tags.size} tags", onBack = onBack) {
                if (!mergesMode) IconButton(onClick = {
                    if (categoriesMode) { editingCategory = null; categoryDialog = true } else { editingTag = null; tagDialog = true }
                }) { Icon(Icons.Default.Add, "Ajouter") }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!categoriesMode && !mergesMode, { categoriesMode = false; mergesMode = false }, { Text("Tags") })
                FilterChip(categoriesMode && !mergesMode, { categoriesMode = true; mergesMode = false; selected = emptySet() }, { Text("Catégories") })
                FilterChip(mergesMode, { mergesMode = true; categoriesMode = false; selected = emptySet() }, { Text("Fusions") })
            }
            if (!categoriesMode && !mergesMode) FilledTonalButton(
                onClick = onPickTags,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Importer des tags") }
            if (selected.isNotEmpty() && !categoriesMode && !mergesMode) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size} sélectionné(s)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { moveDialog = true }) { Text("Classer") }
                    IconButton(onClick = { vm.deleteTags(selected); selected = emptySet() }) { Icon(Icons.Default.DeleteOutline, "Supprimer") }
                }
            }
            if (mergesMode) TagMergesManager(state, vm)
            else if (categoriesMode) CategoryManager(state, vm, { editingCategory = it; categoryDialog = true }, { deleteCategory = it })
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
                TagManager(state, selected, tagQuery, { selected = selected.toggle(it) }, { editingTag = it; tagDialog = true })
            }
        }
    }
    if (tagDialog) TagEditDialog(editingTag, state.tagCategories, {
        if (editingTag == null) vm.createTag(it.first, it.second) else vm.editTag(editingTag!!, it.first, it.second); tagDialog = false
    }, { tagDialog = false })
    if (categoryDialog) NameDialog(if (editingCategory == null) "Nouvelle catégorie" else "Renommer la catégorie", editingCategory?.name.orEmpty(), {
        if (editingCategory == null) vm.createTagCategory(it) else vm.renameTagCategory(editingCategory!!, it); categoryDialog = false
    }, { categoryDialog = false })
    if (moveDialog) CategoryChoiceDialog(state.tagCategories, { vm.moveTagsToCategory(selected, it); selected = emptySet(); moveDialog = false }, { moveDialog = false })
    deleteCategory?.let { category ->
        ConfirmDialog("Supprimer « ${category.name} » ?", "Les tags seront conservés sans catégorie.", {
            vm.deleteTagCategory(category); deleteCategory = null
        }, { deleteCategory = null })
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
                        .clickable { keepFirst = isFirst }
                        .background(if (keepFirst == isFirst) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(keepFirst == isFirst, { keepFirst = isFirst })
                    Spacer(Modifier.width(6.dp))
                    Text(tag.name, Modifier.weight(1f), fontWeight = if (keepFirst == isFirst) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
@Composable
internal fun TagManager(state: AstraUiState, selected: Set<String>, query: String, onToggle: (String) -> Unit, onEdit: (TagEntity) -> Unit) {
    val filteredTags = state.tags.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val ordered = state.tagCategories.map { it.name }
    val other = filteredTags.mapNotNull { it.groupName }.filterNot(ordered.toSet()::contains).distinct().sorted()
    val groups = (ordered + other).map { it to filteredTags.filter { tag -> tag.groupName == it } } +
        ("Sans catégorie" to filteredTags.filter { it.groupName == null })
    if (state.tags.isEmpty()) CenterMessage("Aucun tag. Utilisez + pour en créer un directement.", Modifier.fillMaxSize())
    else LazyColumn(contentPadding = PaddingValues(bottom = PageBottomPadding)) {
        groups.filter { it.second.isNotEmpty() }.forEach { (category, tags) ->
            item(key = "h-$category") { SectionTitle(category) }
            items(tags, key = { it.id }) { tag ->
                RoundedListItem(
                    modifier = Modifier.clickable { onToggle(tag.id) }, headlineContent = { Text(tag.name) },
                    leadingContent = { Checkbox(tag.id in selected, { onToggle(tag.id) }) },
                    trailingContent = { IconButton(onClick = { onEdit(tag) }) { Icon(Icons.Default.Edit, "Modifier") } }
                )
            }
        }
    }
}

@Composable
internal fun CategoryManager(state: AstraUiState, vm: AstraViewModel, onEdit: (TagCategoryEntity) -> Unit, onDelete: (TagCategoryEntity) -> Unit) {
    if (state.tagCategories.isEmpty()) CenterMessage("Créez des catégories puis classez plusieurs tags en une fois.", Modifier.fillMaxSize())
    else LazyColumn(contentPadding = PaddingValues(bottom = PageBottomPadding)) {
        items(state.tagCategories, key = { it.id }) { category ->
            RoundedListItem(
                headlineContent = { Text(category.name) }, supportingContent = { Text("${state.tags.count { it.groupName == category.name }} tag(s)") },
                leadingContent = { Icon(Icons.Default.Folder, null) }, trailingContent = {
                    Row {
                        IconButton(onClick = { vm.moveTagCategory(category.id, -1) }) { Icon(Icons.Default.ArrowUpward, "Monter") }
                        IconButton(onClick = { vm.moveTagCategory(category.id, 1) }) { Icon(Icons.Default.ArrowDownward, "Descendre") }
                        IconButton(onClick = { onEdit(category) }) { Icon(Icons.Default.Edit, "Renommer") }
                        IconButton(onClick = { onDelete(category) }) { Icon(Icons.Default.DeleteOutline, "Supprimer") }
                    }
                }
            )
        }
    }
}
