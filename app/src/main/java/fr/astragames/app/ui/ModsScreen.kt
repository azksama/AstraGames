package fr.astragames.app.ui

import androidx.compose.material3.Text as MaterialText

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.mods.ModStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ModsSheet(game: GameEntity, vm: AstraViewModel, onDismiss: () -> Unit) {
    val mods by vm.tools.modsCatalog.collectAsStateWithLifecycle()
    val busy by vm.tools.toolsBusy.collectAsStateWithLifecycle()
    val loading by vm.tools.modsLoading.collectAsStateWithLifecycle()
    val error by vm.tools.modsError.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val root = uiState.settings.modsRootUri
    LaunchedEffect(game.id) { vm.tools.loadMods(game.id) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (busy || loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { Text("Mods", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }
            error?.let { message -> item { Text(message, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) } }
            if (root == null) {
                item {
                Text("Choisissez le dossier Astra/Mods pour scanner et importer des mods.", Modifier.padding(horizontal = 20.dp))
                Button(onClick = vm.tools::requestModsRoot, enabled = !busy, modifier = Modifier.padding(20.dp)) {
                    Text("Choisir le dépôt de mods")
                }
                }
            } else {
                item {
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.tools.loadMods(game.id) }, enabled = !busy && !loading) { Text("Rescanner") }
                    OutlinedButton(onClick = { vm.tools.requestModZipImport(game.id) }, enabled = !busy && !loading) { Text("Importer un ZIP") }
                }
                }
                if (!loading && error == null && mods.isEmpty()) item { Text("Aucun mod dans ce dépôt.", Modifier.padding(20.dp)) }
                items(mods, key = { it.entity.id }) { item ->
                    val status = when (item.status) {
                        ModStatus.AVAILABLE -> "Disponible"
                        ModStatus.INSTALLED -> "Installé"
                        ModStatus.INCOMPATIBLE -> "Incompatible"
                        ModStatus.ERROR -> "Restauration nécessaire"
                    }
                    ListItem(
                        headlineContent = { MaterialText(item.entity.name) },
                        supportingContent = {
                            Column {
                                MaterialText(listOfNotNull(AppLocalizer.text(status), item.entity.version, item.entity.author).joinToString(" · "))
                                item.entity.description?.let { MaterialText(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Build, null) },
                        trailingContent = {
                            when (item.status) {
                                ModStatus.AVAILABLE -> TextButton(enabled = !busy, onClick = { vm.tools.installMod(game.id, item.entity.id) }) { Text("Installer") }
                                ModStatus.INSTALLED -> TextButton(enabled = !busy, onClick = { item.installation?.let { vm.tools.uninstallMod(game.id, it.id) } }) { Text("Désinstaller") }
                                ModStatus.ERROR -> TextButton(enabled = !busy, onClick = { item.installation?.let { vm.tools.uninstallMod(game.id, it.id, true) } }) { Text("Restaurer") }
                                ModStatus.INCOMPATIBLE -> Unit
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
