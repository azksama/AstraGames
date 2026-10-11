package fr.astragames.app.launcher

import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.model.GameEngine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Install both official Godot APKs from joiplay.net, then pass -e godotOfficialPlugins true. */
class GodotRuntimeDiscoveryTest {
    @Test fun bothOfficialPluginsResolveToTheirOwnGenerationAndHaveOneCatalogueRowEach() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("godotOfficialPlugins") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = listOf(
            JoiPlayCatalogEntry(6, "Godot 4.3 Plugin", "1.00.60", "", "https://joiplay.net/", "", null),
            JoiPlayCatalogEntry(7, "Godot 3.6 Plugin", "1.00.50", "", "https://joiplay.net/", "", null)
        )
        val engines = setOf(GameEngine.GODOT_3, GameEngine.GODOT_4)
        val rows = JoiPlayRuntimeManager().inspect(context, engines, catalog).filter { it.engines.any(engines::contains) }
        assertEquals(2, rows.size)
        for ((generation, engine) in listOf(3 to GameEngine.GODOT_3, 4 to GameEngine.GODOT_4)) {
            val packageName = "cyou.joiplay.runtime.godot$generation"
            val intent = requireNotNull(JoiPlayLauncher().resolveJoiPlayIntent(context, engine.name))
            assertEquals("$packageName.run", intent.action)
            assertEquals(packageName, intent.component!!.packageName)
            assertEquals("$packageName.PermissionActivity", intent.component!!.className)
            val row = rows.single { it.installedPackage == packageName }
            assertTrue(row.installed && row.required && !row.updateAvailable)
            assertEquals(setOf(engine), row.engines)
            assertTrue(row.key.startsWith("catalog-"))
        }
    }
}
