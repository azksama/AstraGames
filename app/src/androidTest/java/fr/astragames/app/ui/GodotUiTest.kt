package fr.astragames.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.launcher.JoiPlayCatalogEntry
import fr.astragames.app.launcher.JoiPlayRuntimeManager
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File

class GodotUiTest {
    @get:Rule val compose = createComposeRule()
    private var previousLanguage = AppLanguage.ENGLISH

    @Before fun useFrench() {
        previousLanguage = AppLocalizer.language
        AppLocalizer.language = AppLanguage.FRENCH
    }

    @After fun restoreLanguage() { AppLocalizer.language = previousLanguage }

    @Test fun launchProfileLetsTheUserSelectEitherGodotGeneration() {
        val game = GameEntity(
            id = "godot-profile", title = "Godot", documentUri = "file:///storage/emulated/0/Games/Godot",
            physicalPath = "/storage/emulated/0/Games/Godot", executableName = "Astra.pck",
            engine = "UNKNOWN", launcher = "JOIPLAY", sourceId = "godot-test",
            dateAdded = 1, lastModified = 1, fingerprint = "godot-test"
        )
        var saved: LaunchProfileEntity? = null
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                AstraTheme { LaunchProfileDialog(game, null, { saved = it }, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("Automatique", substring = true).performClick()
        compose.onNodeWithText("Godot 3").performScrollTo().performClick()
        compose.onNodeWithText("Enregistrer").performClick()
        compose.runOnIdle { assertEquals("GODOT_3", saved?.engineOverride) }
        compose.onNodeWithText("Godot 3").performClick()
        compose.onNodeWithText("Godot 4").performScrollTo().performClick()
        compose.onNodeWithText("Enregistrer").performClick()
        compose.runOnIdle {
            assertEquals("GODOT_4", saved?.engineOverride)
            assertEquals("JOIPLAY", saved?.launcherType)
        }
    }

    @Test fun installedOfficialGodotPluginsShowAsInstalledWithoutDownloadDuplicates() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("godotOfficialPlugins") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engines = setOf(GameEngine.GODOT_3, GameEngine.GODOT_4)
        val catalog = listOf(
            JoiPlayCatalogEntry(6, "Godot 4.3 Plugin", "1.00.60", "", "https://joiplay.net/", "", null),
            JoiPlayCatalogEntry(7, "Godot 3.6 Plugin", "1.00.50", "", "https://joiplay.net/", "", null)
        )
        val rows = JoiPlayRuntimeManager().inspect(context, engines, catalog).filter { it.engines.any(engines::contains) }
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                AstraTheme { RuntimeManagerDialog(rows) {} }
            }
        }
        compose.onAllNodesWithText("Godot 4.3 Plugin").assertCountEquals(1)
        compose.onAllNodesWithText("Godot 3.6 Plugin").assertCountEquals(1)
        compose.onAllNodesWithText("Installé", substring = true).assertCountEquals(2)
        compose.onAllNodesWithText("non installé", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Télécharger").assertCountEquals(0)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(context.cacheDir, "godot-validation").apply { mkdirs() }
        File(directory, "runtimes-installed.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
