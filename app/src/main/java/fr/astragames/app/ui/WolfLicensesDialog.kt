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
            context.assets.open("windows/THIRD-PARTY-NOTICES.txt").bufferedReader().use { it.readText() }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Moteur Wolf · licences") },
        text = { Text(notices, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
}
