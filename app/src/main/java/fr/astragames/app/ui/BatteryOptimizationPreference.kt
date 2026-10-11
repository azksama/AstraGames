package fr.astragames.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.astragames.app.settings.BatteryOptimization

@Composable
internal fun BatteryOptimizationPreference(beforeExternal: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var exempt by remember(context) { mutableStateOf(BatteryOptimization.isExempt(context)) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = BatteryOptimization.isExempt(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    RoundedListItem(
        modifier = Modifier.testTag("battery-optimization").clickable {
            beforeExternal()
            if (!BatteryOptimization.open(context)) Toast.makeText(context,
                "Ouvrez Réglages Android → Applications → Astra → Batterie.", Toast.LENGTH_LONG).show()
        },
        headlineContent = { Text("Optimisation de la batterie") },
        supportingContent = { Text(if (exempt)
            "Désactivée pour Astra · modifier dans Android"
        else "Autorisée par Android · toucher pour la désactiver pour Astra. Ce réglage concerne l’arrière-plan, pas la limite d’images par seconde.") },
        leadingContent = { Icon(Icons.Default.BatteryChargingFull, null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Réglages Android") }
    )
}
