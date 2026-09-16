package fr.astragames.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import fr.astragames.app.core.model.*
import fr.astragames.app.settings.CoverBlurMode
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(text, modifier.padding(start = 16.dp, top = 14.dp, bottom = 5.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
internal fun SettingsSectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
    }
}

@Composable
internal fun CompactActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String
) = IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
    Icon(icon, AppLocalizer.text(description), Modifier.size(18.dp))
}

@Composable
internal fun RoundedListItem(
    modifier: Modifier = Modifier,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        ListItem(
            headlineContent = headlineContent,
            supportingContent = supportingContent,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
internal fun SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) = RoundedListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked, onChange) })

internal fun updateIntervalLabel(code: String) = when (code) {
    "LAUNCH" -> "Chaque lancement"
    "DAY_1" -> "1 jour"
    "DAY_3" -> "3 jours"
    "DAY_15" -> "15 jours"
    "DAY_30" -> "30 jours"
    else -> "7 jours"
}

internal fun LibrarySort.label() = when (this) {
    LibrarySort.TITLE -> "Titre"
    LibrarySort.RECENTLY_ADDED -> "Ajouts récents"
    LibrarySort.LAST_PLAYED -> "Dernier lancement"
    LibrarySort.MOST_PLAYED -> "Plus joués"
    LibrarySort.NEVER_PLAYED -> "Jamais joué"
    LibrarySort.UPDATE_AVAILABLE -> "Mises à jour disponibles"
}

internal fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "Système"
    ThemeMode.LIGHT -> "Clair"
    ThemeMode.DARK -> "Sombre"
}

internal fun CoverBlurMode.label() = when (this) {
    CoverBlurMode.OFF -> "Désactivé"
    CoverBlurMode.STARTUP -> "Automatique au démarrage"
    CoverBlurMode.MANUAL -> "Manuel"
}

@Composable
internal fun InfoLine(label: String, value: String) { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(value); Spacer(Modifier.height(9.dp)) }

internal fun Set<String>.toggle(id: String): Set<String> = toMutableSet().apply { if (!add(id)) remove(id) }
internal fun String.normalizeTagName(): String = Normalizer.normalize(trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase(java.util.Locale.ROOT)
internal fun String.readableEngine() = lowercase(java.util.Locale.ROOT).replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
internal fun Long.asDateTime(): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(this))

internal fun Long?.asDuration(): String {
    val totalMinutes = ((this ?: 0L) / 60_000L).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours} h ${minutes} min"
        minutes > 0 -> "$minutes min"
        else -> "0 min"
    }
}
