package fr.astragames.app.windows

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real downloaded Wine plus an original, separately prepared official Wolf sample. */
class WolfIntegratedRuntimeTest {
    @Test fun gameRunsInsideAstraAndAcceptsControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("wolfIntegration") == "true")
        val context = instrumentation.targetContext
        val sample = InstrumentationRegistry.getArguments().getString("wolfSample") ?: "game"
        require(sample in setOf("game", "game3729"))
        val source = File(context.filesDir, "wolf-probe/$sample")
        assertTrue(File(source, "Game.exe").isFile)
        val intent = android.content.Intent().setClassName(context, "fr.astragames.app.windows.WolfRuntimeActivity")
            .putExtra("id", "official-wolf-test-$sample").putExtra("source", source.toURI().toString())
            .putExtra("executable", "Game.exe").putExtra("title", "Wolf officiel")
        val diagnostics = WolfDiagnostics(context)
        val previousDebug = diagnostics.enabled
        if (InstrumentationRegistry.getArguments().getString("wolfDebug") == "true") diagnostics.enabled = true
        try { ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
            scenario.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            var ready = false
            for (attempt in 0 until 240) {
                Thread.sleep(1000)
                scenario.onActivity { activity -> ready = buttons(activity.window.decorView).any { it.text == "Valider" && it.isShown } }
                if (ready) break
            }
            assertTrue("The actual runtime never became playable", ready)
            Thread.sleep(10_000)
            screenshot("integrated-title.png")
            scenario.onActivity { activity -> buttons(activity.window.decorView).first { it.text == "Valider" }.performClick() }
            Thread.sleep(4000)
            screenshot("integrated-game.png")
            scenario.onActivity { activity -> buttons(activity.window.decorView).first { it.text == "Quitter" }.performClick() }
            Thread.sleep(5000)
        }
            if (diagnostics.enabled) {
                val report = diagnostics.report(diagnostics.reports().first())
                assertTrue(report.contains("debug=true"))
                assertTrue(report.contains("WINEDEBUG=-all,err+all,warn+all"))
                assertTrue(report.contains("Lancement du jeu via"))
                assertTrue(report.contains("===== runtime ====="))
                assertTrue("Normal close must finalize the diagnostic session", report.contains("État : Fermeture demandée"))
            }
        } finally { diagnostics.enabled = previousDebug }
    }
    private fun buttons(view: View): List<Button> = if (view is Button) listOf(view) else if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, "wolf-probe/$name").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
