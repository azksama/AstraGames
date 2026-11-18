package fr.astragames.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text as MaterialText

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import fr.astragames.app.core.metadata.MetadataSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.data.repository.GameEdits

@Composable
internal fun ScanReportDialog(reports: List<ScanReport>, onDismiss: () -> Unit, onConfigure: (() -> Unit)? = null) {
    var selectedIndex by remember(reports) { mutableIntStateOf(0) }
    var filter by remember(reports) { mutableStateOf<ScanReportItemStatus?>(null) }
    val report = reports.getOrNull(selectedIndex.coerceAtMost(reports.lastIndex)) ?: return
    val visibleItems = report.items.filter { filter == null || it.status == filter }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AstraDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.fillMaxHeight().widthIn(max = 1040.dp).align(Alignment.Center)) {
                    CompactHeader("Rapport de scan", report.sourceName, onBack = onDismiss, localizeSubtitle = false)
                    if (reports.size > 1) LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(reports) { index, item ->
                            FilterChip(selectedIndex == index, { selectedIndex = index; filter = null }, { MaterialText(item.sourceName) })
                        }
                    }
                    LazyColumn(
                        Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Text("Terminé le ${report.finishedAt.asDateTime()} • ${report.visitedFolders} dossiers visités", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { ReportMetric("Trouvés", report.found, MaterialTheme.colorScheme.primaryContainer) }
                                item { ReportMetric("Ajoutés", report.added, MaterialTheme.colorScheme.secondaryContainer) }
                                item { ReportMetric("Actualisés", report.updated, MaterialTheme.colorScheme.tertiaryContainer) }
                                item { ReportMetric("Déjà connus", report.unchanged, MaterialTheme.colorScheme.surfaceContainerHigh) }
                                item { ReportMetric("Déplacés", report.moved, MaterialTheme.colorScheme.surfaceContainerHigh) }
                                item { ReportMetric("Manquants", report.missing, MaterialTheme.colorScheme.errorContainer) }
                                item { ReportMetric("Ignorés", report.ignored, MaterialTheme.colorScheme.surfaceContainerHigh) }
                                item { ReportMetric("Erreurs", report.errors.size, MaterialTheme.colorScheme.errorContainer) }
                            }
                        }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { FilterChip(filter == null, { filter = null }, { Text("Tout (${report.items.size})") }) }
                                items(ScanReportItemStatus.entries) { status ->
                                    val count = report.items.count { it.status == status }
                                    if (count > 0) FilterChip(filter == status, { filter = status }, { Text("${status.label()} ($count)") })
                                }
                            }
                        }
                        if (visibleItems.isEmpty()) item { CenterMessage("Aucun détail disponible pour ce rapport.", Modifier.fillMaxWidth().height(180.dp)) }
                        else items(visibleItems) { item ->
                            ListItem(
                                headlineContent = { MaterialText(item.title ?: item.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Column {
                                        if (item.title != null) MaterialText(item.path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(item.reason)
                                    }
                                },
                                leadingContent = { Icon(item.status.icon(), item.status.label(), tint = item.status.tint()) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (onConfigure != null) TextButton(onClick = onDismiss) { Text("Plus tard") }
                        Button(onClick = onConfigure ?: onDismiss, Modifier.weight(1f)) {
                            Text(if (onConfigure != null) "Configurer" else "Fermer")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReportMetric(label: String, value: Int, color: Color) {
    Surface(shape = RoundedCornerShape(16.dp), color = color) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun ScanReportItemStatus.label() = when (this) {
    ScanReportItemStatus.ADDED -> "Ajoutés"
    ScanReportItemStatus.UPDATED -> "Actualisés"
    ScanReportItemStatus.UNCHANGED -> "Déjà connus"
    ScanReportItemStatus.MOVED -> "Déplacés"
    ScanReportItemStatus.MISSING -> "Manquants"
    ScanReportItemStatus.IGNORED -> "Ignorés"
    ScanReportItemStatus.ERROR -> "Erreurs"
}

internal fun ScanReportItemStatus.icon() = when (this) {
    ScanReportItemStatus.ADDED -> Icons.Default.AddCircle
    ScanReportItemStatus.UPDATED -> Icons.Default.Update
    ScanReportItemStatus.UNCHANGED -> Icons.Default.CheckCircle
    ScanReportItemStatus.MOVED -> Icons.AutoMirrored.Filled.DriveFileMove
    ScanReportItemStatus.MISSING -> Icons.Default.LinkOff
    ScanReportItemStatus.IGNORED -> Icons.Default.HideSource
    ScanReportItemStatus.ERROR -> Icons.Default.Error
}

@Composable
internal fun ScanReportItemStatus.tint() = when (this) {
    ScanReportItemStatus.ADDED, ScanReportItemStatus.UNCHANGED -> MaterialTheme.colorScheme.primary
    ScanReportItemStatus.UPDATED, ScanReportItemStatus.MOVED -> MaterialTheme.colorScheme.tertiary
    ScanReportItemStatus.MISSING, ScanReportItemStatus.ERROR -> MaterialTheme.colorScheme.error
    ScanReportItemStatus.IGNORED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun NewGamesSetupInvitation(count: Int, onConfigure: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AutoAwesome, null) },
        title = {
            if (count == 1) Text("Nouveau jeu")
            else MaterialText("${AppLocalizer.text("Nouveaux jeux", LocalAppLanguage.current)} · $count")
        },
        text = { Text(if (count == 1) "Configurer ce jeu maintenant ?" else "Voulez-vous configurer chaque jeu maintenant ? Astra passera automatiquement au suivant.") },
        confirmButton = { TextButton(onClick = onConfigure) { Text("Configurer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Plus tard") } }
    )
}

private enum class SetupExit { SkipGame, LeaveSetup }

@Composable
internal fun NewGameSetupScreen(game: GameEntity, state: AstraUiState, vm: AstraViewModel, onPickCover: (String) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    val assigned by remember(game.id) { vm.gameTags(game.id) }.collectAsStateWithLifecycle<List<TagEntity>?>(null)
    var title by rememberMetadataField(game.id, game.title)
    var description by rememberMetadataField(game.id, game.description)
    var developer by rememberMetadataField(game.id, game.developer)
    var version by rememberMetadataField(game.id, game.version)
    var textTags by rememberSaveable(game.id) { mutableStateOf("") }
    var selectedTags by rememberSaveable(game.id, stateSaver = idSetSaver) { mutableStateOf(emptySet<String>()) }
    var previousAssigned by rememberSaveable(game.id, stateSaver = idSetSaver) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(assigned) {
        val incoming = assigned?.mapTo(hashSetOf()) { it.id } ?: return@LaunchedEffect
        selectedTags = (selectedTags - (previousAssigned - incoming)) + (incoming - previousAssigned)
        previousAssigned = incoming
    }
    val submission = rememberDialogSubmission()
    var showTags by rememberSaveable(game.id) { mutableStateOf(false) }
    var showCover by rememberSaveable(game.id) { mutableStateOf(false) }
    var showF95 by rememberSaveable(game.id) { mutableStateOf(false) }
    var discard by rememberSaveable(game.id) { mutableStateOf<SetupExit?>(null) }
    var importedF95TagNames by remember(game.id) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.tags, importedF95TagNames) {
        if (importedF95TagNames.isNotEmpty()) {
            selectedTags = selectedTags + state.tags.filter { it.name.trim().lowercase(java.util.Locale.ROOT) in importedF95TagNames }.map { it.id }
        }
    }

    val dirty = title != game.title || developer != game.developer.orEmpty() || version != game.version.orEmpty() ||
        description != game.description.orEmpty() || textTags.isNotBlank() || selectedTags != previousAssigned
    fun leave(action: SetupExit) {
        when (action) {
            SetupExit.SkipGame -> vm.completeGameSetup(game.id)
            SetupExit.LeaveSetup -> vm.dismissGameSetup()
        }
    }
    fun requestExit(action: SetupExit) {
        if (!submission.pending) {
            if (dirty) discard = action else leave(action)
        }
    }
    BackHandler { requestExit(SetupExit.LeaveSetup) }
    val count = state.setupQueue.size
    val header: @Composable () -> Unit = {
        val remaining = if (count > 1) "${AppLocalizer.text("Jeux à configurer", LocalAppLanguage.current)} · $count" else null
        CompactHeader("Configurer ${game.title}", remaining, onBack = { requestExit(SetupExit.LeaveSetup) }, localizeSubtitle = false)
    }
    val artwork: @Composable () -> Unit = {
        MetadataEditorArtwork(game, submission.pending,
            onImport = { vm.clearF95Import(); showF95 = true },
            onChangeCover = { vm.prepareCoverPicker(game.id); showCover = true },
            engineLabel = game.engine.readableEngine())
    }
    val actions: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            submission.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (submission.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { requestExit(SetupExit.SkipGame) }, enabled = !submission.pending) { Text("Passer") }
                Button(onClick = {
                    submission.submit {
                        vm.configureScannedGame(game.id,
                            GameEdits(title, game.originalTitle, developer, version, game.productCode, game.language, description, game.f95Url, game.ryuugamesUrl),
                            selectedTags, textTags, previousAssigned)
                    }
                }, modifier = Modifier.weight(1f), enabled = title.isNotBlank() && !submission.pending) {
                    Text(if (count == 1) "Terminer" else "Enregistrer et suivant")
                }
            }
        }
    }
    Surface(Modifier.fillMaxSize().testTag("new-game-setup"), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), contentAlignment = Alignment.TopCenter) {
            val short = maxHeight < 240.dp
            val sideBySide = maxWidth >= 600.dp
            val artworkWidth = (maxWidth * .42f).coerceAtMost(300.dp)
            Column(Modifier.widthIn(max = 920.dp).fillMaxSize()) {
                if (!short) { header(); HorizontalDivider() }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (sideBySide) Column(
                        Modifier.width(artworkWidth).fillMaxHeight()
                            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
                    ) { artwork() }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("new-game-setup-fields"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (short) item("header") { header() }
                        if (!sideBySide) item("artwork") { artwork() }
                        item("title") { MetadataEditField(title, { title = it }, "Nom", enabled = !submission.pending, error = if (title.isBlank()) "Saisissez un titre pour le jeu." else null) }
                        item("developer") { MetadataEditField(developer, { developer = it }, "Développeur", enabled = !submission.pending) }
                        item("version") { MetadataEditField(version, { version = it }, "Version", enabled = !submission.pending) }
                        item("description") { MetadataEditField(description, { description = it }, "Description", multiline = true, enabled = !submission.pending) }
                        item("textTags") { TextTagInput(textTags, { textTags = it }, enabled = !submission.pending) }
                        item("tagPicker") { OutlinedButton(onClick = { showTags = true }, modifier = Modifier.fillMaxWidth(), enabled = !submission.pending) {
                            Icon(Icons.Default.Style, null); Spacer(Modifier.width(6.dp)); Text("Tags (${selectedTags.size})")
                        } }
                        if (short) item("actions") { actions() }
                    }
                }
                SnackbarHost(snackbar)
                if (!short) { HorizontalDivider(); actions() }
            }
        }
    }
    discard?.let { action ->
        ConfirmDialog("Quitter sans enregistrer ?", "Vos modifications en cours seront perdues.",
            onConfirm = { discard = null; leave(action) }, onDismiss = { discard = null },
            confirmLabel = "Abandonner les modifications", dismissLabel = "Continuer à modifier")
    }
    if (showTags) GameTagPickerSheet(state.tags, state.tagCategories, selectedTags, { selected, _ -> selectedTags = selected; showTags = false }, { showTags = false })
    if (showCover) CoverPickerSheet(game, vm.coverSearch.collectAsStateWithLifecycle().value, state.settings.openSearchInExternalBrowser, { vm.chooseRemoteCover(game.id, it) }, {
        onPickCover(game.id); showCover = false
    }, { vm.removeCover(game.id); showCover = false }, { showCover = false; vm.clearCoverSearch() })
    if (showF95) F95ImportSheet(
        game, state.tags, vm.f95Import.collectAsStateWithLifecycle().value, state.settings.openSearchInExternalBrowser, state.settings.f95SessionUser,
        onFetch = { vm.fetchF95Metadata(game.id, it) },
        onPrepareSearch = { vm.prepareF95Search(game.id, game.title, it) },
        onFind = { vm.findMetadata(game.id, it) },
        onComplete = { selection ->
            vm.applyMetadataImport(game.id, selection) {
                importedF95TagNames = selection.tags.map { it.trim().lowercase(java.util.Locale.ROOT) }.toSet()
                showF95 = false
            }
        },
        onDismiss = { showF95 = false; vm.clearF95Import() },
        onConnectSession = vm::connectF95Session, onDisconnectSession = vm::disconnectF95
    )
}

/** Refresh untouched fields after an import while retaining the user's unfinished edits. */
@Composable
internal fun rememberMetadataField(gameId: String, stored: String?): MutableState<String> {
    val value = stored.orEmpty()
    val draft = rememberSaveable(gameId) { mutableStateOf(value) }
    var previous by rememberSaveable(gameId) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (draft.value == previous) draft.value = value
        previous = value
    }
    return draft
}

@Composable
internal fun EditGameDialog(
    game: GameEntity,
    onSave: suspend (GameEdits, String) -> Unit,
    onF95: () -> Unit,
    onChangeCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDeleteGame: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by rememberMetadataField(game.id, game.title)
    var original by rememberMetadataField(game.id, game.originalTitle)
    var developer by rememberMetadataField(game.id, game.developer)
    var version by rememberMetadataField(game.id, game.version)
    var language by rememberMetadataField(game.id, game.language)
    var productCode by rememberMetadataField(game.id, game.productCode)
    var description by rememberMetadataField(game.id, game.description)
    var f95Url by rememberMetadataField(game.id, game.f95Url)
    var ryuugamesUrl by rememberMetadataField(game.id, game.ryuugamesUrl)
    var textTags by rememberSaveable(game.id) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showSources by rememberSaveable(game.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val edits = GameEdits(title, original, developer, version, productCode, language, description, f95Url, ryuugamesUrl)
    val dirty = listOf(title to game.title, original to game.originalTitle, developer to game.developer, version to game.version,
        language to game.language, productCode to game.productCode, description to game.description, f95Url to game.f95Url, ryuugamesUrl to game.ryuugamesUrl)
        .any { (draft, stored) -> draft != stored.orEmpty() } || textTags.isNotBlank()
    val invalidF95 = f95Url.isNotBlank() && MetadataSource.F95ZONE.pageUrl(f95Url) == null
    val invalidRyuu = ryuugamesUrl.isNotBlank() && MetadataSource.RYUUGAMES.pageUrl(ryuugamesUrl) == null
    fun close() { if (!saving) { if (dirty) confirmDiscard = true else onDismiss() } }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AstraDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Column(Modifier.widthIn(max = 920.dp).fillMaxSize().align(Alignment.TopCenter)) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::close, enabled = !saving) { Icon(Icons.Default.Close, "Fermer") }
                        Text("Modifier le jeu", style = MaterialTheme.typography.titleLarge)
                    }
                    HorizontalDivider()
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val wide = maxWidth >= 600.dp
                        Row(Modifier.fillMaxSize()) {
                            if (wide) Column(Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                MetadataEditorArtwork(game, saving, onF95, onChangeCover, onRemoveCover)
                            }
                            LazyColumn(Modifier.weight(1f).testTag("metadata-editor-fields"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (!wide) item { MetadataEditorArtwork(game, saving, onF95, onChangeCover, onRemoveCover) }
                                item { Text("Informations du jeu", style = MaterialTheme.typography.titleMedium) }
                                item { MetadataEditField(title, { title = it }, "Titre", enabled = !saving, error = if (title.isBlank()) "Saisissez un titre pour le jeu." else null) }
                                item { MetadataEditField(original, { original = it }, "Titre original", enabled = !saving) }
                                item { MetadataEditField(developer, { developer = it }, "Développeur", enabled = !saving) }
                                item {
                                    BoxWithConstraints {
                                        if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            Box(Modifier.weight(1f)) { MetadataEditField(version, { version = it }, "Version", enabled = !saving) }
                                            Box(Modifier.weight(1f)) { MetadataEditField(language, { language = it }, "Langue", enabled = !saving) }
                                        } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            MetadataEditField(version, { version = it }, "Version", enabled = !saving)
                                            MetadataEditField(language, { language = it }, "Langue", enabled = !saving)
                                        }
                                    }
                                }
                                item { MetadataEditField(description, { description = it }, "Description", multiline = true, enabled = !saving) }
                                item { HorizontalDivider(); Text("Tags", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium) }
                                item { TextTagInput(textTags, { textTags = it }) }
                                item {
                                    HorizontalDivider()
                                    TextButton(onClick = { showSources = !showSources }, Modifier.fillMaxWidth()) {
                                        Text("Liens et identifiant"); Spacer(Modifier.weight(1f)); Icon(if (showSources) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                                    }
                                }
                                if (showSources || invalidF95 || invalidRyuu) {
                                    item { MetadataEditField(f95Url, { f95Url = it }, "Lien F95Zone", uri = true, enabled = !saving, error = if (invalidF95) "Collez un lien F95Zone commençant par https://f95zone.to/threads/." else null) }
                                    item { MetadataEditField(ryuugamesUrl, { ryuugamesUrl = it }, "Lien Ryuugames", uri = true, enabled = !saving, error = if (invalidRyuu) "Collez le lien HTTPS d’une fiche Ryuugames." else null) }
                                    item { MetadataEditField(productCode, { productCode = it }, "Code produit", enabled = !saving) }
                                }
                                item {
                                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                    TextButton(onClick = onDeleteGame, enabled = !saving, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                        Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Supprimer le jeu")
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick = {
                            saving = true; error = null
                            scope.launch {
                                try { onSave(edits, textTags); onDismiss() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: "Enregistrement impossible. Réessayez." }
                                finally { saving = false }
                            }
                        }, enabled = !saving && title.isNotBlank() && !invalidF95 && !invalidRyuu, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
                    }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, title = { Text("Quitter sans enregistrer ?") },
        text = { Text("Vos modifications en cours seront perdues.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("Abandonner les modifications") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Continuer à modifier") } }
    )
}

@Composable
private fun MetadataEditorArtwork(game: GameEntity, saving: Boolean, onImport: () -> Unit, onChangeCover: () -> Unit,
    onRemoveCover: (() -> Unit)? = null, engineLabel: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCover(game, Modifier.width(72.dp).aspectRatio(.72f))
            Column(Modifier.weight(1f)) {
                Text("Jaquette", style = MaterialTheme.typography.titleMedium)
                if (engineLabel != null) Text(engineLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onChangeCover, enabled = !saving) { Text("Changer") }
                if (game.coverUri != null && onRemoveCover != null) TextButton(onClick = onRemoveCover, enabled = !saving) { Text("Supprimer") }
            }
        }
        OutlinedButton(onClick = onImport, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Importer des métadonnées")
        }
    }
}

@Composable
private fun MetadataEditField(value: String, onChange: (String) -> Unit, label: String, multiline: Boolean = false, uri: Boolean = false, enabled: Boolean = true, error: String? = null) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = !multiline,
        minLines = if (multiline) 4 else 1, maxLines = if (multiline) 10 else 1, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text),
        isError = error != null, supportingText = if (error != null) ({ Text(error) }) else null)
}

@Composable
internal fun EditField(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = true) = OutlinedTextField(
    value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
)

@Composable
internal fun TextTagInput(value: String, onChange: (String) -> Unit, enabled: Boolean = true) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    modifier = Modifier.fillMaxWidth(),
    enabled = enabled,
    label = { Text("Ajouter des tags") },
    placeholder = { Text("female protagonist, adventure, fantasy") },
    supportingText = { Text("Séparez-les par des virgules ou écrivez [tag] [tag]. Les tags existants seront réutilisés.") },
    minLines = 2,
    maxLines = 4,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
)

private val tagChoiceSaver = mapSaver<SnapshotStateMap<String, ToggleableState>>(
    save = { choices -> choices.mapValues { it.value.name } },
    restore = { saved -> mutableStateMapOf<String, ToggleableState>().apply {
        saved.forEach { (id, value) -> put(id, ToggleableState.valueOf(value as String)) }
    } }
)

private fun tagChoice(id: String, included: Set<String>, excluded: Set<String>?) = when {
    id in included -> ToggleableState.On
    excluded != null && id in excluded -> ToggleableState.Indeterminate
    else -> ToggleableState.Off
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameTagPickerSheet(
    tags: List<TagEntity>, categories: List<TagCategoryEntity>, initial: Set<String>,
    onSave: suspend (Set<String>, Set<String>) -> Unit, onDismiss: () -> Unit,
    initialExcluded: Set<String>? = null,
    title: String = "Choisir les tags", allowEmptySelection: Boolean = true
) {
    val choices = rememberSaveable(saver = tagChoiceSaver) { mutableStateMapOf<String, ToggleableState>() }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var uncategorizedOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedCategory = categoryId?.let { id -> categories.firstOrNull { it.id == id } }
    fun pickCategory(id: String?, withoutCategory: Boolean = false) {
        categoryId = id
        uncategorizedOnly = withoutCategory
    }
    val available = remember(tags) { tags.mapTo(hashSetOf()) { it.id } }
    val selection by remember(initial, initialExcluded, available) { derivedStateOf {
        val included = initial.filterTo(mutableSetOf()) { it in available }
        val excluded = initialExcluded.orEmpty().filterTo(mutableSetOf()) { it in available }
        for ((id, choice) in choices) {
            if (id !in available) continue
            if (choice == ToggleableState.On) included.add(id) else included.remove(id)
            if (choice == ToggleableState.Indeterminate) excluded.add(id) else excluded.remove(id)
        }
        included to excluded
    } }
    val (included, excluded) = selection
    val submission = rememberDialogSubmission()
    fun cycle(tagId: String) {
        val next = when (tagChoice(tagId, included, excluded)) {
            ToggleableState.Off -> ToggleableState.On
            ToggleableState.On -> if (initialExcluded == null) ToggleableState.Off else ToggleableState.Indeterminate
            ToggleableState.Indeterminate -> ToggleableState.Off
        }
        if (next == tagChoice(tagId, initial, initialExcluded)) choices.remove(tagId) else choices[tagId] = next
        submission.clearError()
    }
    val categoryName = selectedCategory?.name
    val visible = remember(tags, categoryName, uncategorizedOnly, query) {
        val term = query.trim()
        tags.filter { tag ->
            val matchesCategory = when {
                uncategorizedOnly -> tag.groupName == null
                categoryName != null -> tag.groupName == categoryName
                else -> true
            }
            matchesCategory && (term.isEmpty() || tag.name.contains(term, ignoreCase = true))
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !submission.pending })
    ModalBottomSheet(onDismissRequest = { if (!submission.pending) onDismiss() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(if (initialExcluded != null) "${included.size} inclus · ${excluded.size} exclus" else "${included.size} sélectionné(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { submission.submit { onSave(included, excluded) } }, enabled = !submission.pending && (allowEmptySelection || included.isNotEmpty())) {
                    Icon(Icons.Default.Check, null); Text("Valider")
                }
            }
            submission.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("game-tag-choices")) {
                item {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = !submission.pending,
                        placeholder = { Text("Rechercher un tag") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true
                    )
                }
                item {
                    LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selectedCategory == null && !uncategorizedOnly, { pickCategory(null) }, { Text("Tous") }, enabled = !submission.pending) }
                        items(categories, key = { it.id }) { item -> FilterChip(item.id == categoryId, { pickCategory(item.id) }, { MaterialText(item.name) }, enabled = !submission.pending) }
                        item { FilterChip(uncategorizedOnly, { pickCategory(null, withoutCategory = true) }, { Text("Sans catégorie") }, enabled = !submission.pending) }
                    }
                }
                if (visible.isEmpty()) item { CenterMessage("Aucun tag dans cette catégorie", modifier = Modifier.height(180.dp)) }
                items(visible, key = { it.id }) { tag ->
                    val choice = tagChoice(tag.id, included, excluded)
                    ListItem(
                        modifier = Modifier.triStateToggleable(choice, enabled = !submission.pending, role = Role.Checkbox) { cycle(tag.id) },
                        headlineContent = { MaterialText(tag.name) },
                        supportingContent = { tag.groupName?.let { MaterialText(it) } },
                        leadingContent = {
                            TriStateCheckbox(state = choice, onClick = null, enabled = !submission.pending)
                        }
                    )
                }
            }
        }
    }
}
