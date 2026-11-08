package fr.astragames.app.ui

import androidx.compose.material3.Text as MaterialText

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
internal fun ScanReportDialog(reports: List<ScanReport>, onDismiss: () -> Unit) {
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
                    Button(onClick = onDismiss, Modifier.fillMaxWidth().padding(16.dp)) { Text("Fermer") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewGamesSetupWizard(state: AstraUiState, vm: AstraViewModel, onPickCover: (String) -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    if (!accepted) {
        AlertDialog(
            onDismissRequest = vm::dismissGameSetup,
            icon = { Icon(Icons.Default.AutoAwesome, null) },
            title = { Text("${state.setupQueue.size} nouveau(x) jeu(x)") },
            text = { Text("Voulez-vous configurer chaque jeu maintenant ? Astra passera automatiquement au suivant.") },
            confirmButton = { TextButton(onClick = { accepted = true }) { Text("Configurer") } },
            dismissButton = { TextButton(onClick = vm::dismissGameSetup) { Text("Plus tard") } }
        )
        return
    }
    val game = state.setupQueue.firstOrNull() ?: return
    val assigned by remember(game.id) { vm.gameTags(game.id) }.collectAsStateWithLifecycle(emptyList())
    val coverState by vm.coverSearch.collectAsStateWithLifecycle()
    val f95State by vm.f95Import.collectAsStateWithLifecycle()
    val latestVersions by vm.latestGameVersions.collectAsStateWithLifecycle()
    var title by rememberMetadataField(game.id, game.title)
    var description by rememberMetadataField(game.id, game.description)
    var developer by rememberMetadataField(game.id, game.developer)
    var version by rememberMetadataField(game.id, game.version)
    var textTags by rememberSaveable(game.id) { mutableStateOf("") }
    var selectedTags by remember(game.id) { mutableStateOf(assigned.map { it.id }.toSet()) }
    var previousAssigned by remember(game.id) { mutableStateOf(assigned.map { it.id }.toSet()) }
    LaunchedEffect(assigned) {
        val incoming = assigned.map { it.id }.toSet()
        selectedTags = (selectedTags - (previousAssigned - incoming)) + (incoming - previousAssigned)
        previousAssigned = incoming
    }
    var saving by remember(game.id) { mutableStateOf(false) }
    var saveError by remember(game.id) { mutableStateOf<String?>(null) }
    val saveScope = rememberCoroutineScope()
    var showTags by remember { mutableStateOf(false) }
    var showCover by remember { mutableStateOf(false) }
    var showF95 by rememberSaveable(game.id) { mutableStateOf(false) }
    var importedF95TagNames by remember(game.id) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.tags, importedF95TagNames) {
        if (importedF95TagNames.isNotEmpty()) {
            selectedTags = selectedTags + state.tags.filter { it.name.trim().lowercase(java.util.Locale.ROOT) in importedF95TagNames }.map { it.id }
        }
    }

    ModalBottomSheet(onDismissRequest = vm::dismissGameSetup) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 4.dp)
        ) {
            Text("Configurer ${game.title}", style = MaterialTheme.typography.titleLarge)
            Text("${state.setupQueue.size} jeu(x) restant(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        GameCover(game, Modifier.width(88.dp).aspectRatio(.72f))
                        Column {
                            AssistChip(onClick = {}, label = { Text(game.engine.readableEngine()) })
                            TextButton(onClick = { showCover = true; vm.prepareCoverPicker(game.id) }) { Icon(Icons.Default.ImageSearch, null); Text("Jaquette") }
                        }
                    }
                }
                item { EditField(title, { title = it }, "Nom") }
                item { EditField(developer, { developer = it }, "Développeur") }
                item { EditField(version, { version = it }, "Version") }
                item { EditField(description, { description = it }, "Description", false) }
                item { TextTagInput(textTags, { textTags = it }) }
                item { OutlinedButton(onClick = { showTags = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Style, null); Spacer(Modifier.width(6.dp)); Text("Tags (${selectedTags.size})") } }
                item { OutlinedButton(onClick = { showF95 = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Importer des métadonnées") } }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        saving = true; saveError = null
                        saveScope.launch {
                            try {
                                vm.configureScannedGame(
                                    game.id,
                                    GameEdits(title, game.originalTitle, developer, version, game.productCode, game.language, description, game.f95Url, game.ryuugamesUrl),
                                    selectedTags, textTags
                                )
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { saveError = failure.message ?: "Enregistrement impossible. Réessayez." }
                            finally { saving = false }
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = title.isNotBlank() && !saving
                ) { Text(if (state.setupQueue.size == 1) "Terminer" else "Enregistrer et suivant") }
                TextButton(onClick = { vm.completeGameSetup(game.id) }, Modifier.fillMaxWidth(), enabled = !saving) { Text("Ignorer ce jeu") }
            }
        }
    }
    if (showTags) GameTagPickerSheet(state.tags, state.tagCategories, selectedTags, { selectedTags = it; showTags = false }, { showTags = false })
    if (showCover) CoverPickerSheet(game, coverState, state.settings.openSearchInExternalBrowser, { vm.chooseRemoteCover(game.id, it) }, {
        onPickCover(game.id); showCover = false
    }, { vm.removeCover(game.id); showCover = false }, { showCover = false; vm.clearCoverSearch() })
    if (showF95) F95ImportSheet(
        game, state.tags, f95State, state.settings.openSearchInExternalBrowser, state.settings.f95SessionUser,
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
private fun MetadataEditorArtwork(game: GameEntity, saving: Boolean, onImport: () -> Unit, onChangeCover: () -> Unit, onRemoveCover: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCover(game, Modifier.width(72.dp).aspectRatio(.72f))
            Column(Modifier.weight(1f)) {
                Text("Jaquette", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onChangeCover, enabled = !saving) { Text("Changer") }
                if (game.coverUri != null) TextButton(onClick = onRemoveCover, enabled = !saving) { Text("Supprimer") }
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
internal fun TextTagInput(value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    modifier = Modifier.fillMaxWidth(),
    label = { Text("Ajouter des tags") },
    placeholder = { Text("female protagonist, adventure, fantasy") },
    supportingText = { Text("Séparez-les par des virgules ou écrivez [tag] [tag]. Les tags existants seront réutilisés.") },
    minLines = 2,
    maxLines = 4,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameTagPickerSheet(
    tags: List<TagEntity>, categories: List<TagCategoryEntity>, initial: Set<String>,
    onSave: (Set<String>) -> Unit, onDismiss: () -> Unit,
    initialExcluded: Set<String> = emptySet(),
    onSaveExcluded: ((Set<String>) -> Unit)? = null,
    title: String = "Choisir les tags"
) {
    var included by remember(initial) { mutableStateOf(initial) }
    var excluded by remember(initialExcluded) { mutableStateOf(initialExcluded) }
    var category by remember { mutableStateOf("__all__") }
    var query by remember { mutableStateOf("") }
    fun cycle(tagId: String) {
        if (onSaveExcluded == null) {
            included = included.toggle(tagId)
            return
        }
        when {
            tagId in included -> { included = included - tagId; excluded = excluded + tagId }
            tagId in excluded -> excluded = excluded - tagId
            else -> included = included + tagId
        }
    }
    val visible = remember(tags, category, query) {
        tags.filter {
            (category == "__all__" || (category == "__none__" && it.groupName == null) || it.groupName == category) &&
                (query.isBlank() || it.name.contains(query.trim(), true))
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(if (onSaveExcluded != null) "${included.size} inclus · ${excluded.size} exclus" else "${included.size} sélectionné(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { onSave(included); onSaveExcluded?.invoke(excluded) }) { Icon(Icons.Default.Check, null); Text("Valider") }
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Rechercher un tag") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true
            )
            LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(category == "__all__", { category = "__all__" }, { Text("Tous") }) }
                items(categories, key = { it.id }) { item -> FilterChip(category == item.name, { category = item.name }, { MaterialText(item.name) }) }
                item { FilterChip(category == "__none__", { category = "__none__" }, { Text("Sans catégorie") }) }
            }
            if (visible.isEmpty()) CenterMessage("Aucun tag dans cette catégorie", modifier = Modifier.height(180.dp))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                items(visible, key = { it.id }) { tag ->
                    ListItem(
                        modifier = Modifier.clickable { cycle(tag.id) }, headlineContent = { MaterialText(tag.name) },
                        supportingContent = { tag.groupName?.let { MaterialText(it) } },
                        leadingContent = {
                            if (onSaveExcluded != null) {
                                TriStateCheckbox(
                                    state = when {
                                        tag.id in included -> ToggleableState.On
                                        tag.id in excluded -> ToggleableState.Indeterminate
                                        else -> ToggleableState.Off
                                    },
                                    onClick = { cycle(tag.id) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary,
                                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            } else {
                                Checkbox(tag.id in included, { cycle(tag.id) })
                            }
                        }
                    )
                }
            }
        }
    }
}
