package fr.astragames.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.updates.AppUpdateManager
import fr.astragames.app.updates.GitHubRelease
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class AppUpdateChannelTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val id = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "channel-ui-$id").apply { mkdirs() }
    private val context = object : ContextWrapper(app) {
        override fun getCacheDir() = root
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("channel-ui-$id", mode)
    }
    @After fun cleanup() { root.deleteRecursively(); app.deleteSharedPreferences("channel-ui-$id") }

    @Test fun switchingChannelRefreshesOfferAndPersistsTheChoice() {
        val manager = AppUpdateManager(context) { _, previews ->
            GitHubRelease(if (previews) "99.0.0-beta.2" else "98.0.0", 42, "Astra.apk", 100, "a".repeat(64), "", previews)
        }
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                MaterialTheme { AppUpdateScreen(manager, {}, {}) }
            }
        }
        compose.onNodeWithText("Recevoir les versions bêta").performScrollTo().assertIsOff().performClick()
        compose.waitUntil { manager.state.value.release?.version == "99.0.0-beta.2" && !manager.state.value.busy }
        compose.onNodeWithText("Recevoir les versions bêta").assertIsOn()
        compose.onNodeWithText("Astra 99.0.0-beta.2").performScrollTo().assertIsDisplayed()
        assertTrue(AppUpdateManager(context).state.value.includePrereleases)
        compose.onNodeWithText("Recevoir les versions bêta").performScrollTo().performClick()
        compose.waitUntil { manager.state.value.release?.version == "98.0.0" && !manager.state.value.busy }
        compose.onNodeWithText("Recevoir les versions bêta").assertIsOff()
        compose.onNodeWithText("Astra 99.0.0-beta.2").assertDoesNotExist()
        assertFalse(AppUpdateManager(context).state.value.includePrereleases)
    }
}
