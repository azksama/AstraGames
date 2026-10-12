package fr.astragames.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WolfLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices by produceState("Chargement…") {
        value = withContext(Dispatchers.IO) {
            val windows = context.assets.open("windows/THIRD-PARTY-NOTICES.txt").bufferedReader().use { it.readText() }
            val formats = listOf("WolfTL-MIT.txt", "wolf-rpg-formats-MIT.txt", "WolfSave-MIT.txt").joinToString("\n\n") { name ->
                "$name\n${context.assets.open("licenses/$name").bufferedReader().use { it.readText() }}"
            }
            "WINLATOR / WINE\n\n$windows\n\nLECTEURS DE FORMATS WOLF\n\n$formats"
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Moteur Wolf · licences") },
        text = { Text(notices, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
}
