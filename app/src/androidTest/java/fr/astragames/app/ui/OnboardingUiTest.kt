package fr.astragames.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.AstraSettings
import fr.astragames.app.ui.theme.AstraTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnboardingUiTest {
    @get:Rule val compose = createComposeRule()
    private val previousLanguage = AppLocalizer.language

    @After fun cleanup() { AppLocalizer.language = previousLanguage }

    @Test fun welcomeIsReadableAndOptionalSetupCanBeSkipped() {
        AppLocalizer.language = AppLanguage.FRENCH
        var finished = false
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                AstraTheme {
                    OnboardingScreen(
                        state = AstraUiState(settingsLoaded = true, settings = AstraSettings(language = AppLanguage.FRENCH)),
                        onPickSource = {}, onPickTags = {}, onSetLanguage = {}, onFinish = { finished = true },
                        onConnectSession = { _, _ -> }, onDisconnectSession = {}
                    )
                }
            }
        }
        val headline = compose.onNodeWithText("Toute votre bibliothèque. Un seul ciel.")
        headline.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        headline.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bitmap = headline.captureToImage().asAndroidBitmap()
        val foreground = layouts.single().layoutInput.style.color.toArgb()
        // The first pixel is above the glyphs, on the real rendered gradient.
        assertTrue("The welcome headline must meet the large-text contrast threshold",
            ColorUtils.calculateContrast(foreground, bitmap.getPixel(0, 0)) >= 3.0)
        val app = ApplicationProvider.getApplicationContext<AstraApplication>()
        val output = File(app.cacheDir, "ui-review/onboarding.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }

        compose.onNodeWithText("Continuer").performScrollTo().performClick()
        compose.onNodeWithText("Choisissez votre dossier de jeux").assertExists()
        compose.onNodeWithText("Configurer plus tard").performScrollTo().performClick()
        compose.onNodeWithText("Configurer plus tard").performScrollTo().performClick()
        compose.onNodeWithText("Vérifier les runtimes").assertExists()
        compose.onNodeWithText("Continuer").performScrollTo().performClick()
        compose.onNodeWithText("Compte F95Zone (optionnel)").assertExists()
        compose.onNodeWithText("Terminer").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(finished) }
    }
}
