package fr.astragames.app.ui

import androidx.compose.material3.Text as MaterialText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.LibraryFolderEntity
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.data.repository.CollectionRuleDraft
import java.util.Date
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

@Composable
internal fun TagEditDialog(tag: TagEntity?, categories: List<TagCategoryEntity>, onSave: (Pair<String, String?>) -> Unit, onDismiss: () -> Unit) {
    var name by remember(tag) { mutableStateOf(tag?.name.orEmpty()) }; var category by remember(tag) { mutableStateOf(tag?.groupName) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (tag == null) "Nouveau tag" else "Modifier le tag") },
        text = { Column {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true)
            Spacer(Modifier.height(12.dp)); Text("Catégorie", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(category == null, { category = null }, { Text("Aucune") }) }
                items(categories, key = { it.id }) { item -> FilterChip(category == item.name, { category = item.name }, { MaterialText(item.name) }) }
            }
        } },
        confirmButton = { TextButton(onClick = { onSave(name to category) }, enabled = name.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
internal fun CategoryChoiceDialog(categories: List<TagCategoryEntity>, onChoice: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Classer les tags") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { ListItem(modifier = Modifier.clickable { onChoice(null) }, headlineContent = { Text("Sans catégorie") }, leadingContent = { Icon(Icons.Default.Style, null) }) }
            items(categories, key = { it.id }) { category -> ListItem(modifier = Modifier.clickable { onChoice(category.name) }, headlineContent = { MaterialText(category.name) }, leadingContent = { Icon(Icons.Default.Folder, null) }) }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
internal fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit, confirmLabel: String = "Supprimer") = AlertDialog(
    onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
)

@Composable
internal fun GameFolderDialog(
    folders: List<LibraryFolderEntity>,
    selectedId: String?,
    onChoice: (String?) -> Unit,
    onDismiss: () -> Unit
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Classer le jeu") },
    text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { ListItem(
                modifier = Modifier.selectable(selected = selectedId == null, role = Role.RadioButton, onClick = { onChoice(null) }), headlineContent = { Text("Sans dossier") },
                leadingContent = { RadioButton(selectedId == null, null) }
            ) }
            items(folders, key = { it.id }) { folder -> ListItem(
                modifier = Modifier.selectable(selected = selectedId == folder.id, role = Role.RadioButton, onClick = { onChoice(folder.id) }), headlineContent = { MaterialText(folder.name) },
                leadingContent = { RadioButton(selectedId == folder.id, null) }
            ) }
        }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
)

@Composable
internal fun DeleteGameDialog(game: GameEntity, onConfirm: suspend (Boolean) -> Unit, onDismiss: () -> Unit) =
    RemoveGamesDialog(listOf(game), true, onConfirm, onDismiss)

@Composable
internal fun RemoveGamesDialog(games: List<GameEntity>, initiallyDeleteFiles: Boolean, onConfirm: suspend (Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteFiles by remember { mutableStateOf(initiallyDeleteFiles) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.DeleteForever, null) },
        title = { Text(if (games.size == 1) games.first().title else "${games.size} jeux sélectionnés") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(true, false).forEach { files ->
                    Row(Modifier.fillMaxWidth().selectable(deleteFiles == files, enabled = !busy, role = Role.RadioButton,
                        onClick = { deleteFiles = files; error = null }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(deleteFiles == files, null, enabled = !busy)
                        Text(if (files) "Supprimer le jeu et les fichiers" else "Ignorer le dossier", Modifier.padding(start = 8.dp))
                    }
                }
                Text(if (deleteFiles) "Les dossiers ci-dessous et tout leur contenu, y compris les sauvegardes qu’ils contiennent, seront supprimés définitivement du téléphone. Les jeux seront retirés d’Astra."
                    else "Les fichiers sont conservés. Ces dossiers et leurs sous-dossiers ne seront plus analysés et leurs jeux seront retirés d’Astra. Réautorisation dans Paramètres > Dossiers ignorés.")
                games.forEach { game ->
                    MaterialText(game.physicalPath ?: game.documentUri, style = MaterialTheme.typography.bodySmall)
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Opération en cours…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            busy = true; error = null
            scope.launch {
                try { onConfirm(deleteFiles) }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "Opération impossible" }
                finally { busy = false }
            }
        }, enabled = !busy && games.isNotEmpty(), colors = ButtonDefaults.textButtonColors(contentColor = if (deleteFiles) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
            Text(if (deleteFiles) "Supprimer définitivement" else "Ignorer")
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Annuler") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickGameActionsSheet(
    game: GameEntity,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onIgnore: (() -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { MaterialText(game.title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge) }
        item { ListItem(modifier = Modifier.clickable(enabled = !game.missing, role = Role.Button, onClick = onPlay),
            headlineContent = { Text("Jouer", color = if (game.missing) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f) else MaterialTheme.colorScheme.onSurface) },
            supportingContent = if (game.missing) ({ Text("Ce jeu est introuvable. Rescannez sa source.") }) else null,
            leadingContent = { Icon(Icons.Default.PlayArrow, null) }) }
        item { ListItem(modifier = Modifier.clickable(role = Role.Button, onClick = onOpen), headlineContent = { Text("Ouvrir la fiche") }, leadingContent = { Icon(Icons.Default.Info, null) }) }
        item { ListItem(
            modifier = Modifier.clickable(onClick = onFavorite),
            headlineContent = { Text(if (game.favorite) "Retirer des favoris" else "Ajouter aux favoris") },
            leadingContent = { Icon(if (game.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (game.favorite) Color.Red else LocalContentColor.current) }
        ) }
        item { ListItem(modifier = Modifier.clickable(role = Role.Button, onClick = onSelect), headlineContent = { Text("Démarrer une sélection multiple") }, leadingContent = { Icon(Icons.Default.Checklist, null) }) }
        onIgnore?.let { action -> item { ListItem(modifier = Modifier.clickable(onClick = action), headlineContent = { Text("Ignorer le dossier") }, leadingContent = { Icon(Icons.Default.FolderOff, null) }) } }
        onDelete?.let { action -> item { ListItem(modifier = Modifier.clickable(onClick = action), headlineContent = { Text("Supprimer le jeu et les fichiers", color = MaterialTheme.colorScheme.error) }, leadingContent = { Icon(Icons.Default.DeleteForever, null) }) } }
        }
    }
}

@Composable
internal fun SearchableTagSelector(tags: List<TagEntity>, selectedId: String, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val language = LocalAppLanguage.current
    val sortedTags = remember(tags, query, language) {
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag(language.code))
        tags.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedWith { first, second -> collator.compare(first.name, second.name) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            label = { Text("Rechercher un tag") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true
        )
        if (sortedTags.isEmpty()) Text("Aucun tag trouvé", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sortedTags, key = { it.id }) { tag ->
                ListItem(
                    modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).selectable(selected = tag.id == selectedId, role = Role.RadioButton, onClick = { onSelect(tag.id) }),
                    headlineContent = { MaterialText(tag.name, style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = { RadioButton(selected = tag.id == selectedId, onClick = null) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (tag.id == selectedId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }
        }
    }
}

@Composable
internal fun <T> DropdownSelector(label: String, values: List<T>, selected: T, localizeValues: Boolean = true, text: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
                androidx.compose.material3.Text(if (localizeValues) AppLocalizer.text(text(selected)) else text(selected), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 360.dp)) {
                values.forEach { value -> DropdownMenuItem(
                    text = { androidx.compose.material3.Text(if (localizeValues) AppLocalizer.text(text(value)) else text(value)) },
                    leadingIcon = { if (value == selected) Icon(Icons.Default.Check, null) },
                    onClick = { onSelect(value); expanded = false }
                ) }
            }
        }
    }
}

internal fun defaultOperator(field: String) = when (field) {
    "DATE_ADDED", "LAST_PLAYED" -> "WITHIN_DAYS"
    "PLAY_TIME" -> "GREATER_THAN"
    else -> "IS"
}

internal fun defaultRuleValue(field: String, state: AstraUiState) = when (field) {
    "ENGINE" -> GameEngine.entries.first().name
    "TAG" -> state.tags.minWithOrNull { first, second ->
        java.text.Collator.getInstance(java.util.Locale.FRENCH).compare(first.name, second.name)
    }?.id.orEmpty()
    "FOLDER" -> state.folders.firstOrNull()?.id.orEmpty()
    "FAVORITE", "COVER", "AVAILABLE" -> "true"
    else -> "7"
}

internal fun operatorsFor(field: String) = when (field) {
    "DATE_ADDED" -> listOf("WITHIN_DAYS", "OLDER_THAN_DAYS")
    "LAST_PLAYED" -> listOf("WITHIN_DAYS", "OLDER_THAN_DAYS", "NEVER")
    "PLAY_TIME" -> listOf("GREATER_THAN", "LESS_THAN")
    else -> listOf("IS", "NOT")
}

internal fun String.fieldLabel() = when (this) {
    "ENGINE" -> "Moteur"; "TAG" -> "Tag"; "FOLDER" -> "Dossier"; "FAVORITE" -> "Favori"
    "AVAILABLE" -> "Disponible"; "COVER" -> "Jaquette"; "DATE_ADDED" -> "Date d’ajout"; "LAST_PLAYED" -> "Dernier lancement"; "PLAY_TIME" -> "Temps de jeu"
    else -> this
}

internal fun String.operatorLabelRaw() = when (this) {
    "IS" -> "Est"; "NOT" -> "N’est pas"; "WITHIN_DAYS" -> "Dans les derniers jours"
    "OLDER_THAN_DAYS" -> "Il y a plus de jours"; "NEVER" -> "Jamais lancé"
    "GREATER_THAN" -> "Plus de"; "LESS_THAN" -> "Moins de"; else -> this
}

internal fun CollectionRuleDraft.operatorLabel() = operator.operatorLabelRaw()

internal fun CollectionRuleDraft.summary(state: AstraUiState): String {
    val shownValue = when (field) {
        "ENGINE" -> value.readableEngine()
        "TAG" -> state.tags.firstOrNull { it.id == value }?.name ?: "Tag supprimé"
        "FOLDER" -> state.folders.firstOrNull { it.id == value }?.name ?: "Dossier supprimé"
        "FAVORITE", "COVER", "AVAILABLE" -> if (value == "true") "Oui" else "Non"
        "PLAY_TIME" -> "$value h"
        "LAST_PLAYED" -> if (operator == "NEVER") "Jamais" else "$value jours"
        else -> "$value jours"
    }
    return "${field.fieldLabel()} • $shownValue"
}

internal fun CollectionRuleDraft.localizedSummary(state: AstraUiState): String {
    val shownValue = when (field) {
        "ENGINE" -> value.readableEngine()
        "TAG" -> state.tags.firstOrNull { it.id == value }?.name ?: AppLocalizer.text("Tag supprimé")
        "FOLDER" -> state.folders.firstOrNull { it.id == value }?.name ?: AppLocalizer.text("Dossier supprimé")
        "FAVORITE", "COVER", "AVAILABLE" -> AppLocalizer.text(if (value == "true") "Oui" else "Non")
        else -> value
    }
    return listOf(AppLocalizer.text(field.fieldLabel()), AppLocalizer.text(operatorLabel()), shownValue.takeUnless { operator == "NEVER" }).filterNotNull().joinToString(" ")
}
