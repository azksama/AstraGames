package fr.astragames.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.astragames.app.updates.GitHubRelease

/** In-app notice remains available when Android notifications are disabled. */
@Composable
internal fun AppUpdateNotice(release: GitHubRelease, ready: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        title = { Text("Une mise à jour est disponible", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MaterialText("Astra ${release.version}", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                if (release.prerelease) Text("Version bêta", style = MaterialTheme.typography.bodySmall)
                Text(if (ready) "La mise à jour est prête à installer." else "Une nouvelle version d’Astra est disponible sur GitHub.")
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(if (ready) "Installer" else "Voir la mise à jour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Plus tard") } }
    )
}
