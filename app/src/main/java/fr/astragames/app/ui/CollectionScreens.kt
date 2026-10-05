package fr.astragames.app.ui

import androidx.compose.material3.Text as MaterialText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.LibraryFolderEntity

@Composable
internal fun CollectionsScreen(state: AstraUiState, vm: AstraViewModel, onGame: (String) -> Unit) {
    val landscape = LandscapeLayout
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var smartKey by rememberSaveable { mutableStateOf<String?>(null) }
    var customCollectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LibraryFolderEntity?>(null) }
    var deleting by remember { mutableStateOf<LibraryFolderEntity?>(null) }
    var editingCollection by remember { mutableStateOf<CollectionEntity?>(null) }
    var collectionEditor by remember { mutableStateOf(false) }
    var deletingCollection by remember { mutableStateOf<CollectionEntity?>(null) }
    val current = state.folders.firstOrNull { it.id == currentId }
    LaunchedEffect(currentId, state.folders) { if (currentId != null && current == null) currentId = null }
    val visibleFolders = state.folders.filter { it.parentId == currentId }
    val now = System.currentTimeMillis()
    val smartCollections = listOf(
        SmartCollection("favorites", "Favoris", Icons.Default.Favorite) { it.favorite },
        SmartCollection("recent", "Récemment ajoutés", Icons.Default.LibraryAdd) { now - it.dateAdded < 604_800_000L },
        SmartCollection("last_played", "Joués récemment", Icons.Default.PlayCircleOutline) { it.lastPlayedAt?.let { date -> now - date < 604_800_000L } == true },
        SmartCollection("never", "Jamais joués", Icons.Default.HourglassEmpty) { it.playCount == 0 },
        SmartCollection("missing", "Jeux manquants", Icons.Default.ErrorOutline) { it.missing },
        SmartCollection("no_cover", "Sans jaquette", Icons.Default.HideImage) { it.coverUri == null }
    )
    val selectedSmart = smartCollections.firstOrNull { it.key == smartKey }
    val selectedCustom = state.collections.firstOrNull { it.id == customCollectionId }
    BackHandler(selectedSmart != null || selectedCustom != null || current != null) {
        when {
            selectedSmart != null -> smartKey = null
            selectedCustom != null -> customCollectionId = null
            current != null -> currentId = current.parentId
        }
    }
    val displayedGames = when {
        selectedSmart != null -> state.games.filter(selectedSmart.predicate)
        selectedCustom != null -> state.customCollectionGames[selectedCustom.id].orEmpty()
        currentId != null -> state.games.filter { it.libraryFolderId == currentId }
        else -> emptyList()
    }
    ScrollingScaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactHeader(
                selectedSmart?.name ?: selectedCustom?.name ?: current?.name ?: if (LandscapeLayout) "Collections" else "Astra",
                subtitle = if (current != null) "${visibleFolders.size} sous-dossier(s)" else null,
                localizeTitle = selectedSmart != null || (selectedCustom == null && current == null),
                onBack = when {
                    selectedSmart != null -> ({ smartKey = null })
                    selectedCustom != null -> ({ customCollectionId = null })
                    current != null -> ({ currentId = current.parentId })
                    else -> null
                }
            )
        }
    ) { padding ->
        if (selectedSmart != null || selectedCustom != null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                GameCollection(displayedGames, state.settings.viewMode, state.settings.gridColumns, onGame, vm::toggleFavorite)
            }
        } else AdaptiveLists(Modifier.padding(padding).imePadding(), split = LandscapeLayout && currentId == null, first = {
            if (currentId == null) {
                if (!landscape) item { PageHeading("Collections", "Vos jeux, organisés à votre façon."); Spacer(Modifier.height(20.dp)) }
                items(smartCollections, key = { "builtin-${it.key}" }) { smart ->
                    Row(Modifier.fillMaxWidth().clickable { smartKey = smart.key }.heightIn(min = if (LandscapeLayout) 48.dp else 62.dp).padding(vertical = if (LandscapeLayout) 4.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(smart.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(smart.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(state.games.count(smart.predicate).toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }, second = {
            if (currentId == null) {
                item {
                    Spacer(Modifier.height(if (LandscapeLayout) 0.dp else 24.dp))
                    Text("Collections intelligentes", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Définissez vos règles. Les jeux correspondants sont ajoutés automatiquement.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                }
                if (state.collections.isEmpty()) item { Text(
                    "Créez une collection avec vos critères.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                ) }
                items(state.collections, key = { "collection-${it.id}" }) { collection ->
                    Surface(
                        onClick = { customCollectionId = collection.id },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        androidx.compose.material3.Text(collection.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                        Text("${state.customCollectionGames[collection.id].orEmpty().size} jeux", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    val rules = state.collectionRules.filter { it.collectionId == collection.id }
                                    androidx.compose.material3.Text(rules.joinToString(" · ") {
                                        fr.astragames.app.data.repository.CollectionRuleDraft(it.field, it.operator, it.value).localizedSummary(state)
                                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Box {
                                    var menu by remember { mutableStateOf(false) }
                                    CompactActionButton({ menu = true }, Icons.Default.MoreHoriz, "Actions rapides")
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("Modifier") }, onClick = { menu = false; editingCollection = collection; collectionEditor = true })
                                        DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; deletingCollection = collection })
                                    }
                                }
                            }
                            Text("Mise à jour automatique", Modifier.padding(start = 30.dp, top = 2.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                item {
                    Button(onClick = { editingCollection = null; collectionEditor = true }, Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 50.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Créer une collection intelligente")
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); SectionTitle("Collections Astra") }
            }
            item {
                FilledTonalButton(
                    onClick = { creating = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.CreateNewFolder, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("Nouvelle collection")
                }
            }
            items(visibleFolders, key = { it.id }) { folder ->
                RoundedListItem(
                    modifier = Modifier.clickable { currentId = folder.id },
                    headlineContent = { MaterialText(folder.name) },
                    supportingContent = { Text("${state.folders.count { it.parentId == folder.id }} sous-dossier(s)") },
                    leadingContent = { Icon(Icons.Default.Folder, null) },
                    trailingContent = {
                        Row {
                            CompactActionButton({ editing = folder }, Icons.Default.Edit, "Modifier")
                            CompactActionButton({ deleting = folder }, Icons.Default.Delete, "Supprimer")
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir", Modifier.align(Alignment.CenterVertically))
                        }
                    }
                )
            }
            if (displayedGames.isNotEmpty()) {
                item { SectionTitle("Jeux dans ce dossier") }
                items(displayedGames, key = { "game-${it.id}" }) { game -> RoundedListItem(
                    modifier = Modifier.clickable { onGame(game.id) },
                    headlineContent = { MaterialText(game.title) },
                    supportingContent = { Text(game.engine.readableEngine()) },
                    leadingContent = { GameCover(game, Modifier.width(46.dp).aspectRatio(.72f)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
                ) }
            }
            if (visibleFolders.isEmpty() && displayedGames.isEmpty()) item { CenterMessage("Aucun dossier ni jeu ici", Modifier.fillMaxWidth().height(120.dp)) }
        })
    }
    if (creating) NameDialog("Nouvelle collection", "", { name -> vm.createFolder(name, currentId); creating = false }, { creating = false })
    editing?.let { folder -> NameDialog("Modifier le dossier", folder.name, { name -> vm.renameFolder(folder, name); editing = null }, { editing = null }) }
    deleting?.let { folder -> ConfirmDialog(
        "Supprimer ${folder.name} ?",
        "Les jeux ne seront pas supprimés. Ses sous-dossiers remonteront au niveau actuel.",
        { vm.deleteFolder(folder); deleting = null },
        { deleting = null }
    ) }
    if (collectionEditor) SmartCollectionEditorDialog(
        collection = editingCollection,
        existingRules = editingCollection?.let { selected -> state.collectionRules.filter { it.collectionId == selected.id } }.orEmpty(),
        state = state,
        onSave = { id, name, mode, rules -> vm.saveCollection(id, name, mode, rules) { collectionEditor = false } },
        onDismiss = { collectionEditor = false }
    )
    deletingCollection?.let { collection -> ConfirmDialog(
        "Supprimer ${collection.name} ?", "Les jeux et leurs fichiers seront conservés.",
        { vm.deleteCollection(collection.id); deletingCollection = null }, { deletingCollection = null }
    ) }
}

internal data class SmartCollection(
    val key: String,
    val name: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val predicate: (GameEntity) -> Boolean
)
