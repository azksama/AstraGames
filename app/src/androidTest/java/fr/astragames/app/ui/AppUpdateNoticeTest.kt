package fr.astragames.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import fr.astragames.app.updates.GitHubRelease
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppUpdateNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val release = GitHubRelease("99.0.0-beta.2", 42, "Astra.apk", 100, "a".repeat(64), "", true)

    @Test fun availableOfferOpensTheUpdateScreenAndCanBeDeferred() {
        var opened = 0; var dismissed = 0
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
            AstraTheme { AppUpdateNotice(release, false, { opened++ }, { dismissed++ }) }
        } }
        compose.onNodeWithText("Une mise à jour est disponible").assertIsDisplayed()
        compose.onNodeWithText("Astra 99.0.0-beta.2").assertIsDisplayed()
        compose.onNodeWithText("Version bêta").assertIsDisplayed()
        compose.onNodeWithText("Une nouvelle version d’Astra est disponible sur GitHub.").performScrollTo().assertIsDisplayed()
        capture()
        compose.onNodeWithText("Voir la mise à jour").performClick(); assertEquals(1, opened)
        compose.onNodeWithText("Plus tard").performClick(); assertEquals(1, dismissed)
    }

    @Test fun readyOfferShowsInstallationInsteadOfRequiringAnotherDownload() {
        var opened = false
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
            AstraTheme { AppUpdateNotice(release.copy(prerelease = false), true, { opened = true }, {}) }
        } }
        compose.onNodeWithText("La mise à jour est prête à installer.").assertIsDisplayed()
        compose.onNodeWithText("Version bêta").assertDoesNotExist()
        compose.onNodeWithText("Installer").performClick(); assertTrue(opened)
    }

    private fun capture() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("wolfUiCapture") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = arguments.getString("captureLabel") ?: "phone"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "astra-update-notice-$label.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
