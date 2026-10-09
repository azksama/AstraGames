package fr.astragames.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.windows.WolfGameStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WolfSavesSheet(game: GameEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember(game.id, game.documentUri) { WolfGameStorage(context, game.id, game.documentUri) }
    var files by remember { mutableStateOf<List<WolfGameStorage.SaveFile>>(emptyList()) }
    var location by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            exporting = true; error = null; message = null
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { storage.exportSaves(it) }
                }
                message = "Sauvegardes exportées."
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (exception: Exception) { error = exception.message ?: "Export impossible." }
            finally { exporting = false }
        }
    }
    LaunchedEffect(storage, revision) {
        loading = true; error = null
        try {
            val result = withContext(Dispatchers.IO) {
                storage.restoreLastLocation()
                storage.saveFiles() to "${if (storage.isDirect) "Dossier d’origine" else "Copie privée dans Astra"} :\n${storage.game.path}"
            }
            files = result.first; location = result.second
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (exception: Exception) { files = emptyList(); error = exception.message ?: "Lecture impossible." }
        finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Sauvegardes Wolf RPG", style = MaterialTheme.typography.titleLarge) }
            item { Text("Le nom du dossier dépend du jeu : Save, SaveData, fichiers .sav… Faites une sauvegarde dans le jeu puis fermez la partie avant d’exporter.") }
            if (location.isNotEmpty()) item { SelectionContainer { Text(location, style = MaterialTheme.typography.bodySmall) } }
            item { Text("Les sauvegardes écrites dans les dossiers utilisateur Windows restent dans le préfixe du jeu. L’export les inclut sous Windows/.", style = MaterialTheme.typography.bodySmall) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch("Astra-Wolf-sauvegardes.zip") }, enabled = !loading && !exporting && files.isNotEmpty()) {
                        Text(if (exporting) "Export…" else "Exporter les sauvegardes")
                    }
                    TextButton(onClick = { revision++ }, enabled = !loading && !exporting) { Text("Actualiser") }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            message?.let { item { Text(it) } }
            if (loading) item { CircularProgressIndicator(Modifier.size(28.dp)) }
            else if (files.isEmpty() && error == null) item { Text("Aucune sauvegarde repérée. Le jeu peut utiliser un emplacement ou un format particulier.") }
            items(files, key = { it.path }) { file ->
                SelectionContainer { Text("${file.path}\n${file.file.path}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
