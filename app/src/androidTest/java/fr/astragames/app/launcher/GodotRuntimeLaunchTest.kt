package fr.astragames.app.launcher

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.model.LaunchResult
import fr.astragames.app.data.local.GameEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt in with godotRuntimeFixtures=true after pushing scripts/godot-runtime/prepare-fixtures.cjs outputs. */
class GodotRuntimeLaunchTest {
    @Test fun godot3PackRendersAndAcceptsTouch() = play(3, "Astra.pck")
    @Test fun godot4PackRendersAndAcceptsTouch() = play(4, "Astra.pck")
    @Test fun godot3SourceProjectRequiresAnExportedPack() = sourceRequiresPack(3)
    @Test fun godot4SourceProjectRequiresAnExportedPack() = sourceRequiresPack(4)

    private fun sourceRequiresPack(generation: Int): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("godotRuntimeFixtures") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "godot-source-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(folder, "project.godot").writeText("config_version=${if (generation == 3) 4 else 5}\n")
            val game = fixture(generation, folder.path, "project.godot")
            val report = CompatibilityDiagnostic(JoiPlayLauncher()).inspect(context, game, null)
            assertFalse(report.canLaunch)
            assertTrue(report.checks.any { it.label == "Pack Godot" && ".pck" in it.detail })
            val result = JoiPlayLauncher().launch(context, game)
            assertTrue(result is LaunchResult.Failure && ".pck" in result.message)
        } finally { folder.deleteRecursively() }
    }

    @Test fun ambiguousAndUppercasePackFoldersHaveAnActionableDiagnostic(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "godot-packs-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val pack = File(folder, "Main.pck").apply { writeText("fixture") }
            val patch = File(folder, "Patch.pck").apply { writeText("fixture") }
            val game = fixture(3, folder.path, pack.name)
            val ambiguous = JoiPlayLauncher().launch(context, game)
            assertTrue(ambiguous is LaunchResult.Failure && "plusieurs" in ambiguous.message)
            assertTrue(patch.delete())
            assertTrue(pack.renameTo(File(folder, "Main.PCK")))
            val uppercase = JoiPlayLauncher().launch(context, game.copy(executableName = "Main.PCK"))
            assertTrue(uppercase is LaunchResult.Failure && "minuscules" in uppercase.message)
        } finally { folder.deleteRecursively() }
    }

    private fun fixture(generation: Int, folder: String, entry: String) = GameEntity(
        id = "godot-$generation-fixture", title = "Astra Godot $generation validation",
        documentUri = "file://$folder", physicalPath = folder, executableName = entry,
        engine = "GODOT_$generation", launcher = "JOIPLAY", sourceId = "godot-fixtures",
        dateAdded = 1, lastModified = 1, fingerprint = "godot-$generation-fixture"
    )

    private fun play(generation: Int, entry: String): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("godotRuntimeFixtures") == "true")
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        shell("am force-stop cyou.joiplay.runtime.godot$generation")
        shell("logcat -c")
        val kind = if (entry.endsWith(".pck")) "pack" else "project"
        val folder = "/storage/emulated/0/Download/AstraGodotValidation/godot$generation/$kind"
        val game = fixture(generation, folder, entry)
        assertEquals(LaunchResult.Success, JoiPlayLauncher().launch(context, game))
        fun awaitMarker(marker: String) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            var logs: String
            do {
                logs = shell("logcat -d -t 1000 -s godot:V AndroidRuntime:E")
                if (marker in logs) return
                SystemClock.sleep(250)
            } while (SystemClock.uptimeMillis() < deadline)
            fail("Godot fixture did not reach $marker. Logcat: ${logs.takeLast(6000)}")
        }
        awaitMarker("ASTRA_GODOT_${generation}_READY")
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 200f, 300f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        awaitMarker("ASTRA_GODOT_${generation}_TOUCH")
        SystemClock.sleep(500) // Capture a rendered frame after the label reacts to touch.
        val screenshots = File(context.cacheDir, "godot-validation").apply { mkdirs() }
        val screenshot = requireNotNull(automation.takeScreenshot())
        var brightPixels = 0
        for (y in 80 until screenshot.height * 9 / 10 step 2) {
            for (x in 8 until screenshot.width / 2 step 2) {
                val pixel = screenshot.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 128 && android.graphics.Color.green(pixel) > 128 &&
                    android.graphics.Color.blue(pixel) > 128) brightPixels++
            }
        }
        File(screenshots, "godot-$generation-${entry.substringAfterLast('.')}.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertTrue("Fixture text must actually render (not only the plugin overlay): $brightPixels foreground pixels", brightPixels > 40)
        shell("am force-stop cyou.joiplay.runtime.godot$generation")
    }
}
