package fr.astragames.app.ui

import androidx.compose.material3.Text as MaterialText

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    var title by rememberSaveable(game.id) { mutableStateOf(game.title) }
    var description by rememberSaveable(game.id) { mutableStateOf(game.description.orEmpty()) }
    var developer by rememberSaveable(game.id) { mutableStateOf(game.developer.orEmpty()) }
    var version by rememberSaveable(game.id) { mutableStateOf(game.version.orEmpty()) }
    var textTags by rememberSaveable(game.id) { mutableStateOf("") }
    var selectedTags by remember(game.id, assigned) { mutableStateOf(assigned.map { it.id }.toSet()) }
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
                Button(
                    onClick = {
                        vm.configureScannedGame(
                            game.id,
                            GameEdits(title, game.originalTitle, developer, version, game.productCode, game.language, description, game.f95Url, game.ryuugamesUrl),
                            selectedTags,
                            textTags
                        )
                    }, modifier = Modifier.fillMaxWidth(), enabled = title.isNotBlank()
                ) { Text(if (state.setupQueue.size == 1) "Terminer" else "Enregistrer et suivant") }
                TextButton(onClick = { vm.completeGameSetup(game.id) }, Modifier.fillMaxWidth()) { Text("Ignorer ce jeu") }
            }
        }
    }
    if (showTags) GameTagPickerSheet(state.tags, state.tagCategories, selectedTags, { selectedTags = it; showTags = false }, { showTags = false })
    if (showCover) CoverPickerSheet(game, coverState, state.settings.openSearchInExternalBrowser, { vm.chooseRemoteCover(game.id, it) }, {
        onPickCover(game.id); showCover = false
    }, { vm.removeCover(game.id); showCover = false }, { showCover = false; vm.clearCoverSearch() })
    if (showF95) F95ImportSheet(game, state.tags, f95State, state.settings.openSearchInExternalBrowser, state.settings.f95SessionUser, { vm.fetchF95Metadata(game.id, it) }, {
        vm.prepareF95Search(game.id, game.title, it)
    }, { tags, image ->
        importedF95TagNames = tags.map { it.trim().lowercase(java.util.Locale.ROOT) }.toSet()
        vm.applyF95Tags(game.id, tags)
        image?.let { vm.chooseF95Cover(game.id, it) }
        showF95 = false
    }, { showF95 = false; vm.clearF95Import() }, vm::connectF95Session, vm::disconnectF95)
}

@Composable
internal fun EditGameDialog(
    game: GameEntity,
    onSave: (GameEdits, String) -> Unit,
    onF95: () -> Unit,
    onChangeCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDeleteGame: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by rememberSaveable(game.id) { mutableStateOf(game.title) }; var original by rememberSaveable(game.id) { mutableStateOf(game.originalTitle.orEmpty()) }
    var developer by rememberSaveable(game.id) { mutableStateOf(game.developer.orEmpty()) }; var version by rememberSaveable(game.id) { mutableStateOf(game.version.orEmpty()) }
    var description by rememberSaveable(game.id) { mutableStateOf(game.description.orEmpty()) }
    var f95Url by rememberSaveable(game.id) { mutableStateOf(game.f95Url.orEmpty()) }
    var ryuugamesUrl by rememberSaveable(game.id) { mutableStateOf(game.ryuugamesUrl.orEmpty()) }
    var textTags by rememberSaveable(game.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Modifier le jeu") },
        text = { LazyColumn(Modifier.heightIn(max = 520.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Jaquette", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChangeCover, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ImageSearch, null); Spacer(Modifier.width(6.dp)); Text("Changer")
                    }
                    if (game.coverUri != null) OutlinedButton(
                        onClick = onRemoveCover,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Icon(Icons.Default.DeleteOutline, "Supprimer la jaquette") }
                }
            }
            item { HorizontalDivider(); Text("Métadonnées", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium) }
            item { OutlinedButton(onClick = onF95, Modifier.fillMaxWidth()) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Importer des métadonnées") } }
            item { EditField(title, { title = it }, "Titre") }; item { EditField(original, { original = it }, "Titre original") }
            item { EditField(developer, { developer = it }, "Développeur") }; item { EditField(version, { version = it }, "Version") }
            item { EditField(f95Url, { f95Url = it }, "Lien F95Zone") }
            item { EditField(ryuugamesUrl, { ryuugamesUrl = it }, "Lien Ryuugames") }
            item { EditField(description, { description = it }, "Description", false) }
            item { TextTagInput(textTags, { textTags = it }) }
            item { HorizontalDivider(); Text("Zone sensible", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
            item { OutlinedButton(
                onClick = onDeleteGame,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Icon(Icons.Default.DeleteForever, null); Spacer(Modifier.width(6.dp)); Text("Supprimer le jeu") } }
        } },
        confirmButton = { TextButton(onClick = { onSave(GameEdits(title, original, developer, version, game.productCode, game.language, description, f95Url, ryuugamesUrl), textTags) }, enabled = title.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
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
