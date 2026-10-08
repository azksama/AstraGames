package fr.astragames.app.windows

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WindowsDisplayProbeTest {
    @Test fun windowsMapsAGraphicalWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("windowsDisplayProbe") == "true")
        val context = instrumentation.targetContext
        ActivityScenario.launch<WindowsDisplayProbeActivity>(Intent(context, WindowsDisplayProbeActivity::class.java)
            .putExtra("command", args.getString("command") ?: "notepad")).use { scenario ->
            var mapped = false
            repeat(90) {
                Thread.sleep(1000)
                scenario.onActivity { mapped = it.server.windowManager.rootWindow.children.any { window -> window.attributes.isMapped } }
                if (mapped) return@repeat
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "wolf-probe/display.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue(File(context.filesDir, "wolf-probe/display.log").readText().takeLast(16000), mapped)
        }
    }
}
