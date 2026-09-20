package fr.astragames.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.astragames.app.R

@Composable
internal fun LockContent(biometric: Boolean, pinEnabled: Boolean, onBiometric: () -> Unit, onPin: (String, (Boolean) -> Unit) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    val verify = {
        if (pin.length >= 4 && !verifying) {
            verifying = true
            onPin(pin) { accepted -> verifying = false; invalid = !accepted; if (!accepted) pin = "" }
        }
    }
    val identity: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.mipmap.astra_icon), "Astra", Modifier.size(if (LandscapeLayout) 80.dp else 104.dp))
            androidx.compose.material3.Text("Astra", style = MaterialTheme.typography.displayMedium)
            androidx.compose.material3.Text("GAMES", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 5.sp), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Astra est verrouillé", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    val authentication: @Composable () -> Unit = {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(if (LandscapeLayout) 16.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(if (LandscapeLayout) 12.dp else 16.dp)) {
                Text("Déverrouiller Astra", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                if (biometric) Button(onClick = onBiometric, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Default.Fingerprint, null, Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)); Text("Déverrouiller par biométrie")
                }
                if (pinEnabled) {
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8); invalid = false },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Code") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                        enabled = !verifying, isError = invalid, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { verify() }))
                    if (invalid) Text("Code incorrect", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { verify() }, enabled = pin.length >= 4 && !verifying, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Déverrouiller") }
                }
            }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), contentAlignment = Alignment.Center) {
            if (LandscapeLayout) Row(Modifier.widthIn(max = 1000.dp).fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Box(Modifier.weight(.4f).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) { identity() }
                Box(Modifier.weight(.6f).verticalScroll(rememberScrollState())) { authentication() }
            } else Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) { identity(); authentication() }
        }
    }
}
