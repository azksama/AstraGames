package fr.astragames.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.astragames.app.data.local.GameSourceEntity
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.material3.Text as MaterialText

@Composable
internal fun SourceSubfolderDialog(
    source: GameSourceEntity,
    loadFolders: suspend (List<String>) -> List<String>,
    onScan: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var path by rememberSaveable(source.id) { mutableStateOf<List<String>>(emptyList()) }
    var retry by remember { mutableIntStateOf(0) }
    val selectedPath = path
    var folders by remember(source.id, selectedPath, retry) { mutableStateOf<List<String>?>(null) }
    var error by remember(source.id, selectedPath, retry) { mutableStateOf<String?>(null) }
    LaunchedEffect(source.id, selectedPath, retry) {
        try {
            folders = loadFolders(selectedPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Impossible de lire ce dossier"
        }
    }
    val entries = folders
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scanner un sous-dossier") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MaterialText((listOf(source.displayName) + path).joinToString(" / "), Modifier.weight(1f))
                        if (path.isNotEmpty()) TextButton(onClick = { path = path.dropLast(1) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            Text("Dossier parent")
                        }
                    }
                }
                when {
                    error != null -> item {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retry++ }) { Text("Réessayer") }
                    }
                    entries == null -> item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    entries.isEmpty() -> item { Text("Aucun sous-dossier", Modifier.padding(vertical = 16.dp)) }
                    else -> items(entries) { name ->
                        ListItem(
                            modifier = Modifier.clickable { path = selectedPath + name },
                            headlineContent = { MaterialText(name) },
                            leadingContent = { Icon(Icons.Default.Folder, null) }
                        )
                    }
                }
                if (path.isEmpty()) item { Text("Seul le dossier choisi sera analysé.", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onScan(selectedPath) }, enabled = selectedPath.isNotEmpty() && entries != null) {
                Text("Scanner ce dossier")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
