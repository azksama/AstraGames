package fr.astragames.app.mkxpz

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import fr.astragames.app.AstraApplication
import fr.astragames.app.ui.LocalAppLanguage
import fr.astragames.app.ui.LockContent
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Main-process authentication only: the SDL process never reads credentials or DataStore. */
class MkxpzUnlockActivity : FragmentActivity() {
    private val settingsRepository get() = (application as AstraApplication).container.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back/cancel, including process recreation, must never release the native lock.
        setResult(RESULT_CANCELED)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            // The owner may have deliberately disabled the lock in Astra during play.
            if (!settings.lockPinEnabled && !settings.lockBiometricEnabled) {
                authenticated()
                return@launch
            }
            setContent {
                AstraTheme(hue = settings.accentHue) {
                    CompositionLocalProvider(LocalAppLanguage provides settings.language) {
                        LockContent(settings.lockBiometricEnabled, settings.lockPinEnabled, {
                            val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            if (BiometricManager.from(this@MkxpzUnlockActivity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
                                val prompt = BiometricPrompt(this@MkxpzUnlockActivity,
                                ContextCompat.getMainExecutor(this@MkxpzUnlockActivity),
                                object : BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = authenticated()
                                })
                                prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Astra")
                                    .setAllowedAuthenticators(authenticators).build())
                            } else {
                                android.widget.Toast.makeText(this@MkxpzUnlockActivity,
                                    "Authentification Android indisponible. Vérifiez le verrouillage de l’appareil ou utilisez le code Astra.",
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                        }) { pin, result -> lifecycleScope.launch {
                            val accepted = settingsRepository.verifyPin(pin)
                            result(accepted)
                            if (accepted) authenticated()
                        } }
                    }
                }
            }
        }
    }

    private fun authenticated() {
        setResult(RESULT_OK)
        finish()
    }
}
