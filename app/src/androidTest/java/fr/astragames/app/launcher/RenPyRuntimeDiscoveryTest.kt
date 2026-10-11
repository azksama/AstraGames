package fr.astragames.app.launcher

import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.model.GameEngine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run with -e renpyOfficialPlugin true after installing the APK linked from joiplay.net. */
class RenPyRuntimeDiscoveryTest {
    @Test fun installedOfficialVersionedPluginCanBeResolvedFromAstraPackageVisibility() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("renpyOfficialPlugin") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(JoiPlayLauncher().resolveJoiPlayIntent(context, GameEngine.RENPY.name))
        assertEquals("cyou.joiplay.runtime.renpy8.run", intent.action)
        assertEquals("cyou.joiplay.runtime.renpy.v8d4d1", intent.component!!.packageName)
        assertEquals("cyou.joiplay.runtime.renpy.v8d4d1.PermissionActivity", intent.component!!.className)
        val runtime = JoiPlayRuntimeManager().inspect(context, setOf(GameEngine.RENPY))
            .first { it.installedPackage == intent.component!!.packageName }
        assertTrue(runtime.installed)
        assertEquals(setOf(GameEngine.RENPY), runtime.engines)
    }
}
