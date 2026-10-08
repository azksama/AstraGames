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
    var path by rememberSaveable(source.id) { mutableStateOf(arrayListOf<String>()) }
    var retry by remember { mutableIntStateOf(0) }
    var loadedPath by remember { mutableStateOf<List<String>?>(null) }
    var folders by remember { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source.id, path.toList(), retry) {
        loadedPath = null
        error = null
        try {
            folders = loadFolders(path.toList())
            loadedPath = path.toList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Impossible de lire ce dossier"
        }
    }
    val ready = loadedPath == path.toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scanner un sous-dossier") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MaterialText((listOf(source.displayName) + path).joinToString(" / "))
                Text("Seul le dossier choisi sera analysé.", style = MaterialTheme.typography.bodySmall)
                if (path.isNotEmpty()) TextButton(onClick = { path = ArrayList(path.dropLast(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Dossier parent")
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp)) {
                    when {
                        error != null -> Column {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { retry++ }) { Text("Réessayer") }
                        }
                        !ready -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        folders.isEmpty() -> Text("Aucun sous-dossier", Modifier.padding(vertical = 16.dp))
                        else -> LazyColumn {
                            items(folders, key = { it }) { name ->
                                ListItem(
                                    modifier = Modifier.clickable { path = ArrayList(path + name) },
                                    headlineContent = { MaterialText(name) },
                                    leadingContent = { Icon(Icons.Default.Folder, null) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onScan(path.toList()) }, enabled = path.isNotEmpty() && ready && error == null) {
                Text("Scanner ce dossier")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
