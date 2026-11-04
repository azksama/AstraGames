package fr.astragames.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.AstraApplication
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.AstraSettings
import fr.astragames.app.ui.theme.AstraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real application navigation and snackbar collector, without catalogue or source writes. */
@RunWith(AndroidJUnit4::class)
class GlobalSnackbarUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<AstraApplication>()
    private val store = ViewModelStore()
    private val previousLanguage = AppLocalizer.language
    private lateinit var previousSettings: AstraSettings
    private lateinit var vm: AstraViewModel

    @Before fun prepare() {
        previousSettings = runBlocking { app.container.settings.settings.first() }
        check(!previousSettings.lockPinEnabled && !previousSettings.lockBiometricEnabled) {
            "This test requires the unlocked SDK fixture installation."
        }
        runBlocking { app.container.settings.setScanOnLaunch(false) }
        compose.runOnUiThread { vm = AstraViewModel(app); store.put("global-snackbar", vm) }
    }

    @After fun restore() {
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        if (::previousSettings.isInitialized) runBlocking {
            app.container.settings.setScanOnLaunch(previousSettings.scanOnLaunch)
        }
        AppLocalizer.language = previousLanguage
    }

    @Test fun messageStaysAboveNavigationAndTheCoveredPartOfSettingsRemainsClickable() = verify(1f)

    @Test fun largeTextMessageStaysAboveNavigationAndTheCoveredPartOfSettingsRemainsClickable() = verify(2f)

    private fun verify(fontScale: Float) {
        val state = AstraUiState(settingsLoaded = true, settings = previousSettings.copy(
            onboardingCompleted = true, language = AppLanguage.FRENCH, scanOnLaunch = false
        ))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AstraTheme {
                    // Exercise the bottom navigation even when the SDK AVD is a large window.
                    Box(Modifier.widthIn(max = 360.dp).fillMaxHeight()) {
                        AstraApp(state, vm, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { !vm.isLocked.value }
        compose.onNodeWithContentDescription("Paramètres").assertIsDisplayed()
        val message = "Navigation disponible pendant le message"
        runBlocking { vm.events.emit(UiEvent.Message(message)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        val snackbar = compose.onNodeWithText(message).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithContentDescription("Paramètres").assertIsDisplayed()
        val navigation = settings.fetchSemanticsNode().boundsInRoot
        capture("snackbar-navigation-font-${fontScale.toInt()}")
        assertTrue("Message $snackbar must remain above the navigation action $navigation", snackbar.bottom <= navigation.top)
        // This part of the actual touch target was obscured by the old bottom-aligned message.
        settings.performTouchInput { click(Offset(width / 2f, height - 2f)) }
        settings.assertIsSelected()
        // In a short window the section can be outside the LazyColumn's composed viewport.
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Apparence"))
        compose.onNodeWithText("Apparence").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val output = File(app.cacheDir, "global-review-ui").apply { mkdirs() }.resolve("$name.png")
        output.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
