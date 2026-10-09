package fr.astragames.app.windows

import android.app.Activity
import android.os.StrictMode
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SdkSuppress(minSdkVersion = 28)
class WolfStartupUiTest {
    @Test fun cachedEngineContinuesToSourceCheckWithoutProgressDiskIoOnMainThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = "startup-${UUID.randomUUID()}"
        val missingSource = File(context.cacheDir, id)
        val storage = WolfGameStorage(context, id, missingSource.toURI().toString())
        val root = WolfRuntimeInstaller(context).root.apply { mkdirs() }
        val created = mutableListOf<File>()
        for ((name, value) in listOf("ready" to WolfRuntimeInstaller.REVISION, "audio-ready" to "1")) {
            val marker = File(root, name)
            if (!marker.exists()) { marker.writeText(value); created.add(marker) }
        }
        var oldPolicy: StrictMode.ThreadPolicy? = null
        val violations = CopyOnWriteArrayList<String>()
        instrumentation.runOnMainSync {
            oldPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(oldPolicy!!).detectDiskReads().detectDiskWrites()
                .penaltyListener({ it.run() }) { violation ->
                    if (violation.stackTrace.any { it.className.startsWith("fr.astragames.app.windows.WolfDiagnostic") ||
                            it.className.startsWith("fr.astragames.app.windows.WolfGameStorage") }) violations.add(violation.toString())
                }.build())
        }
        try {
            val intent = WolfRuntimeActivity.intent(context, id, missingSource.toURI().toString(), "Game.exe", "Cached startup test")
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                var failed = false
                for (attempt in 0 until 100) {
                    scenario.onActivity { failed = buttons(it.window.decorView).any { button -> button.text == "Voir le rapport de diagnostic" } }
                    if (failed) break
                    Thread.sleep(50)
                }
                assertTrue("Cached startup must leave engine step and report the missing source", failed)
                val report = WolfDiagnostics(context).report(WolfDiagnostics(context).reports().first())
                assertTrue(report.contains("Moteur Wolf déjà installé"))
                assertTrue(report.contains("Vérification de l’autorisation du dossier source"))
                assertTrue("Startup diagnostics performed disk IO on Main: $violations", violations.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { oldPolicy?.let(StrictMode::setThreadPolicy) }
            created.forEach { it.delete() }
            storage.directory.deleteRecursively()
        }
    }
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
}
