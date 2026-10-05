package fr.astragames.app.ui

import androidx.compose.material3.Text as MaterialText
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.DeletedGameEntity
import fr.astragames.app.data.repository.DuplicateMergePreview
import fr.astragames.app.data.repository.SaveConflictStrategy
import fr.astragames.app.core.search.DuplicateDetector.DuplicateGroup
import fr.astragames.app.launcher.JoiPlayRuntimeInfo

@Composable
internal fun DuplicatesDialog(state: AstraUiState, vm: AstraViewModel, onDismiss: () -> Unit) {
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selectedGroup = state.duplicateGroups.firstOrNull { it.key == selectedKey }
    var primaryId by remember(selectedKey) { mutableStateOf(selectedGroup?.games?.firstOrNull()?.id) }
    val secondary = selectedGroup?.games?.firstOrNull { it.id != primaryId }
    val preview by vm.duplicatePreview.collectAsStateWithLifecycle()
    var migrateSaves by remember(selectedKey) { mutableStateOf(true) }
    var strategy by remember(selectedKey) { mutableStateOf(SaveConflictStrategy.KEEP_BOTH) }
    var deleteFiles by remember(selectedKey) { mutableStateOf(false) }
    var confirmPhysical by remember { mutableStateOf(false) }
    LaunchedEffect(primaryId, secondary?.id) {
        if (primaryId != null && secondary != null) vm.previewDuplicateMerge(primaryId!!, secondary.id)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            ScrollingColumn(topBar = { CompactHeader(
                    selectedGroup?.let { "Comparer les doublons" } ?: "Doublons détectés",
                    selectedGroup?.let { "Choisissez l’exemplaire principal" } ?: "${state.duplicateGroups.size} groupe(s)",
                    onBack = if (selectedGroup != null) ({ selectedKey = null; vm.clearDuplicatePreview() }) else onDismiss
                ) }) {
                if (selectedGroup != null && primaryId != null && secondary != null) {
                    DuplicateComparisonContent(
                        group = selectedGroup, state = state, primaryId = primaryId!!, preview = preview,
                        migrateSaves = migrateSaves, onMigrateSaves = { migrateSaves = it },
                        strategy = strategy, onStrategy = { strategy = it }, deleteFiles = deleteFiles,
                        onDeleteFiles = { deleteFiles = it }, onPrimary = { primaryId = it },
                        onIgnore = { vm.ignoreDuplicateGroup(selectedGroup.key); selectedKey = null },
                        onMerge = {
                            if (deleteFiles) confirmPhysical = true else vm.mergeDuplicate(primaryId!!, secondary.id, migrateSaves, strategy, false) { selectedKey = null }
                        }
                    )
                } else if (state.duplicateGroups.isEmpty()) CenterMessage("Aucun doublon détecté", Modifier.fillMaxSize())
                else LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    itemsIndexed(state.duplicateGroups, key = { _, group -> group.key }) { index, group ->
                        RoundedListItem(
                            modifier = Modifier.clickable { selectedKey = group.key; primaryId = group.games.first().id },
                            headlineContent = { MaterialText("${group.games.first().title} • ${AppLocalizer.text("${group.games.size} exemplaires", LocalAppLanguage.current)}") },
                            supportingContent = { MaterialText(group.games.joinToString("\n") { it.physicalPath ?: it.documentUri }, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.ContentCopy, null) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Comparer") }
                        )
                    }
                }
            }
        }
    }
    if (confirmPhysical && primaryId != null && secondary != null) ConfirmDialog(
        "Supprimer le dossier secondaire ?",
        "Les sauvegardes sont traitées avant la suppression. Le dossier ${secondary.title} sera ensuite supprimé définitivement du téléphone.",
        {
            confirmPhysical = false
            vm.mergeDuplicate(primaryId!!, secondary.id, migrateSaves, strategy, true) { selectedKey = null }
        },
        { confirmPhysical = false }
    )
}

@Composable
internal fun DuplicateComparisonContent(
    group: DuplicateGroup,
    state: AstraUiState,
    primaryId: String,
    preview: DuplicateMergePreview?,
    migrateSaves: Boolean,
    onMigrateSaves: (Boolean) -> Unit,
    strategy: SaveConflictStrategy,
    onStrategy: (SaveConflictStrategy) -> Unit,
    deleteFiles: Boolean,
    onDeleteFiles: (Boolean) -> Unit,
    onPrimary: (String) -> Unit,
    onIgnore: () -> Unit,
    onMerge: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BoxWithConstraints {
                if (maxWidth >= 700.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    group.games.forEach { game -> DuplicateGameCard(game, state, game.id == primaryId, { onPrimary(game.id) }, Modifier.weight(1f)) }
                } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(group.games, key = { it.id }) { game -> DuplicateGameCard(game, state, game.id == primaryId, { onPrimary(game.id) }, Modifier.width(290.dp)) }
                }
            }
        }
        item { Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sauvegardes", style = MaterialTheme.typography.titleMedium)
            Text(
                if (preview == null) "Recherche des sauvegardes…"
                else "${preview.primarySaves.size} dans le jeu principal • ${preview.secondarySaves.size} à récupérer • ${preview.conflictingSaves.size} conflit(s)"
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(migrateSaves, onMigrateSaves); Text("Récupérer les sauvegardes du doublon")
            }
            if (migrateSaves && (preview?.conflictingSaves?.isNotEmpty() == true)) {
                Text("En cas de même nom", style = MaterialTheme.typography.labelLarge)
                SaveConflictStrategy.entries.forEach { choice -> FilterChip(
                    strategy == choice, { onStrategy(choice) }, { Text(choice.label()) }, Modifier.padding(end = 6.dp)
                ) }
            }
        } } }
        item { Card { Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Après la fusion", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().clickable { onDeleteFiles(!deleteFiles) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(deleteFiles, onDeleteFiles)
                Column { Text("Supprimer le dossier secondaire"); Text(
                    if (deleteFiles) "Suppression physique irréversible après confirmation" else "Conserver les fichiers et ignorer définitivement ce doublon",
                    style = MaterialTheme.typography.bodySmall, color = if (deleteFiles) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                ) }
            }
        } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onIgnore, Modifier.weight(1f)) { Text("Ignorer ce groupe") }
            Button(onClick = onMerge, Modifier.weight(1f), enabled = preview != null) { Text("Fusionner") }
        } }
        if (group.games.size > 2) item { Text("Après cette fusion, les exemplaires restants seront reproposés un par un.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun DuplicateGameCard(game: GameEntity, state: AstraUiState, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    Card(
        modifier.clickable(onClick = onSelect),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, { onSelect() }); Text(if (selected) "Exemplaire principal" else "Choisir comme principal", fontWeight = FontWeight.SemiBold) }
        GameCover(game, Modifier.fillMaxWidth().height(150.dp))
        MaterialText(game.title, style = MaterialTheme.typography.titleMedium)
        Text(game.engine.readableEngine())
        MaterialText(game.physicalPath ?: game.documentUri, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        Text("${game.playCount} lancements • ${state.playStats[game.id]?.totalDurationMs.asDuration()}", style = MaterialTheme.typography.bodySmall)
        if (game.favorite) Text("Favori", color = Color.Red)
    } }
}

internal fun SaveConflictStrategy.label() = when (this) {
    SaveConflictStrategy.KEEP_PRIMARY -> "Garder le principal"
    SaveConflictStrategy.REPLACE_WITH_SECONDARY -> "Remplacer"
    SaveConflictStrategy.KEEP_BOTH -> "Conserver les deux"
}

@Composable
internal fun DeletedGamesDialog(games: List<DeletedGameEntity>, onRestore: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            ScrollingColumn(topBar = { CompactHeader("Jeux supprimés", "${games.size} jeu(x)", onBack = onDismiss) }) {
                if (games.isEmpty()) CenterMessage("Aucun jeu supprimé", Modifier.fillMaxSize())
                else LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(games, key = { it.id }) { game -> RoundedListItem(
                        headlineContent = { MaterialText(game.title) },
                        supportingContent = { Text("Supprimé le ${game.deletedAt.asDateTime()}\n${game.physicalPath ?: game.documentUri}", maxLines = 3) },
                        leadingContent = { Icon(Icons.Default.DeleteOutline, null) },
                        trailingContent = { TextButton(onClick = { onRestore(game.id) }) { Text("Réautoriser") } }
                    ) }
                }
            }
        }
    }
}

@Composable
internal fun RuntimeManagerDialog(runtimes: List<JoiPlayRuntimeInfo>, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            ScrollingColumn(topBar = { CompactHeader("Runtimes JoiPlay", "Détection locale des composants", onBack = onDismiss) }) {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(runtimes, key = { it.key }) { runtime -> RoundedListItem(
                        headlineContent = { MaterialText(runtime.name) },
                        supportingContent = {
                            Text(when {
                                runtime.updateAvailable -> "Installé${runtime.versionName?.let { " • version $it" }.orEmpty()} • mise à jour ${runtime.latestVersion} disponible"
                                runtime.installed -> "Installé${runtime.versionName?.let { " • version $it" }.orEmpty()}"
                                runtime.required -> "Requis par votre bibliothèque • non installé"
                                else -> "Version ${runtime.latestVersion.orEmpty()} • non installé"
                            })
                        },
                        leadingContent = { Icon(
                            if (runtime.updateAvailable) Icons.Default.Update else if (runtime.installed) Icons.Default.CheckCircle else Icons.Default.Download,
                            null, tint = if (runtime.updateAvailable) MaterialTheme.colorScheme.tertiary else if (runtime.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        ) },
                        trailingContent = { if (!runtime.installed || runtime.updateAvailable) TextButton(onClick = { uriHandler.openUri(runtime.downloadUrl) }) { Text(if (runtime.updateAvailable) "Mettre à jour" else "Télécharger") } }
                    ) }
                    item { Text(
                        "Après installation ou mise à jour d’un plugin, fermez puis rouvrez ce gestionnaire pour relancer la détection.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    ) }
                }
            }
        }
    }
}

@Composable
internal fun CenterMessage(text: String, modifier: Modifier = Modifier, loading: Boolean = false) = Box(modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { if (loading) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)) }; Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
