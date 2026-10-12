package fr.astragames.app.windows

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real engine-authored save, from a copied official sample with an authored SaveLoad command. */
class WolfSaveFixtureRuntimeTest {
    @Test fun officialEngineWritesFixtureForCodecValidation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("wolfSaveFixture") == "true")
        val version = arguments.getString("wolfFixtureVersion") ?: "22961"
        require(version in setOf("22961", "3729"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "wolf-probe/save-fixtures/game$version")
        check(File(source, "Game.exe").isFile) { "Push the separately prepared sample before testing." }
        val id = "wolf-save-fixture-$version"
        val store = WolfGameOptions(context, id)
        val before = store.read()
        store.save(before.copy(resolution = "800x600", performance = WolfPerformance.STABLE, storageMode = WolfStorageMode.AUTO))
        try {
            ActivityScenario.launch<WolfRuntimeActivity>(WolfRuntimeActivity.intent(context, id, source.toURI().toString(), "Game.exe", "Wolf save fixture $version")).use { scenario ->
                val save = File(source, "Save/SaveData01.sav")
                var found = false
                repeat(180) {
                    if (!found) {
                        Thread.sleep(1000)
                        if (save.isFile && save.length() > 20) found = true
                        // Enter the official new-game entry if the sample is at its title.
                        if (it % 3 == 0) scenario.onActivity { activity ->
                            val field = WolfRuntimeActivity::class.java.getDeclaredField("server").apply { isAccessible = true }
                            (field.get(activity) as? com.winlator.xserver.XServer)?.let { server ->
                                server.injectKeyPress(com.winlator.xserver.XKeycode.KEY_ENTER)
                                activity.window.decorView.postDelayed({ server.injectKeyRelease(com.winlator.xserver.XKeycode.KEY_ENTER) }, 100)
                            }
                        }
                    }
                }
                assertTrue("The actual Wolf engine never wrote its save", found)
                save.copyTo(File(context.getExternalFilesDir(null), "wolf-engine-save-$version.sav"), overwrite = true)
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                Thread.sleep(3000)
            }
        } finally { store.save(before) }
    }
}
