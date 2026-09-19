package fr.astragames.app.ui

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
        SmartCollection("recent", "Récemment ajoutés", Icons.Default.NewReleases) { now - it.dateAdded < 604_800_000L },
        SmartCollection("last_played", "Joués récemment", Icons.Default.History) { it.lastPlayedAt?.let { date -> now - date < 604_800_000L } == true },
        SmartCollection("never", "Jamais joués", Icons.Default.HourglassEmpty) { it.playCount == 0 },
        SmartCollection("missing", "Jeux manquants", Icons.Default.ErrorOutline) { it.missing },
        SmartCollection("no_cover", "Sans jaquette", Icons.Default.HideImage) { it.coverUri == null }
    )
    val selectedSmart = smartCollections.firstOrNull { it.key == smartKey }
    val selectedCustom = state.collections.firstOrNull { it.id == customCollectionId }
    val displayedGames = when {
        selectedSmart != null -> state.games.filter(selectedSmart.predicate)
        selectedCustom != null -> state.customCollectionGames[selectedCustom.id].orEmpty()
        currentId != null -> state.games.filter { it.libraryFolderId == currentId }
        else -> emptyList()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactHeader(
                selectedSmart?.name ?: selectedCustom?.name ?: current?.name ?: "Astra",
                subtitle = if (current != null) "${visibleFolders.size} sous-dossier(s)" else null,
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
        } else LazyColumn(Modifier.padding(padding).imePadding(), contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = PageBottomPadding)) {
            if (currentId == null) {
                item { PageHeading("Collections"); Spacer(Modifier.height(20.dp)) }
                item { SectionTitle("Collections intelligentes") }
                item {
                    Box(Modifier.fillMaxWidth()) {
                        val columns = 3
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            smartCollections.chunked(columns).forEach { row ->
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { smart ->
                                        Card(
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            onClick = { smartKey = smart.key },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                                        ) {
                                            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(smart.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                                Text(AppLocalizer.text(smart.name, LocalAppLanguage.current), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                                                Text("${state.games.count(smart.predicate)} jeux", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Mes collections intelligentes", Modifier.weight(1f))
                        TextButton(
                            onClick = { editingCollection = null; collectionEditor = true },
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)
                        ) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(3.dp)); Text("Créer") }
                    }
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
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FilterAlt, null, Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(collection.name, style = MaterialTheme.typography.titleSmall)
                                val rules = state.collectionRules.count { it.collectionId == collection.id }
                                Text("${state.customCollectionGames[collection.id].orEmpty().size} jeux • $rules règle(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CompactActionButton({ editingCollection = collection; collectionEditor = true }, Icons.Default.Edit, "Modifier")
                            CompactActionButton({ deletingCollection = collection }, Icons.Default.DeleteOutline, "Supprimer")
                        }
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
                    headlineContent = { Text(folder.name) },
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
                    headlineContent = { Text(game.title) },
                    supportingContent = { Text(game.engine.readableEngine()) },
                    leadingContent = { GameCover(game, Modifier.width(46.dp).aspectRatio(.72f)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ouvrir") }
                ) }
            }
            if (visibleFolders.isEmpty() && displayedGames.isEmpty()) item { CenterMessage("Aucun dossier ni jeu ici", Modifier.fillMaxWidth().height(120.dp)) }
        }
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
        onSave = { id, name, mode, rules -> vm.saveCollection(id, name, mode, rules); collectionEditor = false },
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
