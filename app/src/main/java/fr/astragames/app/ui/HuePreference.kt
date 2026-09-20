package fr.astragames.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun HuePreference(hue: Int, onHue: (Int) -> Unit) {
    var value by remember(hue) { mutableFloatStateOf(hue.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Teinte de l’application", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.size(24.dp).background(Color.hsv(value, .28f, 1f), CircleShape))
            TextButton(onClick = { value = 255f; onHue(255) }) { Text("Réinitialiser") }
        }
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(8.dp).background(
                Brush.horizontalGradient((0..6).map { Color.hsv((it * 60f).coerceAtMost(359f), .65f, .9f) }), CircleShape))
            Slider(value, onValueChange = { value = it }, valueRange = 0f..359f,
                onValueChangeFinished = { onHue(value.roundToInt()) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = AppLocalizer.text("Teinte de l’application") },
                colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent, thumbColor = Color.hsv(value, .28f, 1f)))
        }
    }
}
