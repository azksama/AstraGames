package fr.astragames.app.launcher

import fr.astragames.app.core.model.GameEngine
import org.junit.Assert.*
import org.junit.Test

class JoiPlayRuntimeManagerTest {
    private val manager = JoiPlayRuntimeManager()
    private val godotCatalog = listOf(entry(6, "Godot 4.3 Plugin", "1.00.60"), entry(7, "Godot 3.6 Plugin", "1.00.50"))

    @Test fun associatesBothGodotGenerationsWithoutInstalledDuplicateRows() {
        val result = manager.reconcile(setOf(GameEngine.GODOT_3, GameEngine.GODOT_4), godotCatalog, listOf(godot(3), godot(4)))
        assertEquals(2, result.size)
        assertEquals("cyou.joiplay.runtime.godot4", result[0].installedPackage)
        assertEquals("cyou.joiplay.runtime.godot3", result[1].installedPackage)
        assertEquals(setOf(GameEngine.GODOT_4), result[0].engines)
        assertEquals(setOf(GameEngine.GODOT_3), result[1].engines)
        assertTrue(result.all { it.installed && it.required && !it.updateAvailable })
    }

    @Test fun installingOneGenerationDoesNotMarkTheOtherAsInstalled() {
        val result = manager.reconcile(setOf(GameEngine.GODOT_3), godotCatalog, listOf(godot(3)))
        assertEquals(2, result.size)
        assertFalse(result[0].installed)
        assertFalse(result[0].required)
        assertTrue(result[1].installed)
        assertTrue(result[1].required)
    }

    @Test fun godotPackageGenerationWorksWithALocalizedLabel() {
        val result = manager.reconcile(emptySet(), godotCatalog, listOf(godot(4).copy(label = "Extension JoiPlay")))
        assertTrue(result.first().installed)
        assertEquals(2, result.size)
    }

    @Test fun checksGodotPluginUpdatesUsingApplicationVersionRatherThanEngineVersion() {
        val result = manager.reconcile(emptySet(), godotCatalog, listOf(godot(4).copy(version = "1.00.50-patreon")))
        assertTrue(result.first().updateAvailable)
        assertEquals("1.00.60", result.first().latestVersion)
    }

    @Test fun fallbackCatalogStillRecognizesGodotWhenOffline() {
        val result = manager.reconcile(setOf(GameEngine.GODOT_3), emptyList(), listOf(godot(3)))
        assertEquals(1, result.count { it.installedPackage == "cyou.joiplay.runtime.godot3" })
        assertTrue(result.single { it.installed }.required)
        assertTrue(result.single { it.installed }.key.startsWith("catalog-"))
    }

    @Test fun renpyVersionsMatchOnNumericComponentsAndDoNotConsumeOtherVersions() {
        val catalog = listOf(entry(1, "Ren'Py 8.1 Plugin"), entry(2, "Ren'Py 8.10 Plugin"), entry(3, "Ren'Py 7.7.1 Plugin"))
        val result = manager.reconcile(emptySet(), catalog, listOf(
            component("cyou.joiplay.runtime.renpy.v8d10d0", "Ren'Py 8.10 Plugin"),
            component("cyou.joiplay.runtime.renpy.v7d7d1", "Ren'Py 7.7.1 Plugin")
        ))
        assertEquals(3, result.size)
        assertFalse(result[0].installed)
        assertEquals("cyou.joiplay.runtime.renpy.v8d10d0", result[1].installedPackage)
        assertEquals("cyou.joiplay.runtime.renpy.v7d7d1", result[2].installedPackage)
    }

    @Test fun recognizesVersionedRenpyPackageWhenItsLabelOmitsTheVersion() {
        val result = manager.reconcile(emptySet(), listOf(entry(1, "Ren'Py 8.4.1 Plugin")), listOf(
            component("cyou.joiplay.runtime.renpy.v8d4d1", "Ren'Py Plugin")
        ))
        assertTrue(result.single().installed)
    }

    private fun entry(id: Int, title: String, version: String = "1.00.50") =
        JoiPlayCatalogEntry(id, title, version, "", "https://joiplay.net/", "", null)

    private fun godot(major: Int) = JoiPlayRuntimeManager.InstalledComponent(
        "cyou.joiplay.runtime.godot$major", "Godot $major Plugin for JoiPlay", if (major == 3) "1.00.50" else "1.00.60"
    )

    private fun component(packageName: String, label: String) = JoiPlayRuntimeManager.InstalledComponent(packageName, label, "1.00.50")
}
