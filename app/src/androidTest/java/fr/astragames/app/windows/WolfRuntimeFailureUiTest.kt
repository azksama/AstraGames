package fr.astragames.app.windows

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class WolfRuntimeFailureUiTest {
    @Test fun failureBeforeDownloadOffersADurableDiagnosticReport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Invalid internal launch arguments fail before installation or any network access.
        val intent = Intent(context, WolfRuntimeActivity::class.java).putExtra("title", "EARLY_FAILURE_FIXTURE")
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            var reportButton: Button? = null
            for (attempt in 0 until 50) {
                scenario.onActivity { activity -> reportButton = buttons(activity.window.decorView).firstOrNull { it.text == "Voir le rapport de diagnostic" } }
                if (reportButton != null) break
                Thread.sleep(100)
            }
            assertNotNull("Early failure must offer the report, not close silently", reportButton)
            scenario.onActivity { reportButton!!.performClick() }
            val store = WolfDiagnostics(context)
            val report = store.report(store.reports().first())
            assertTrue(report.contains("EARLY_FAILURE_FIXTURE"))
            assertTrue(report.contains("IllegalArgumentException"))
            assertTrue(report.contains("Préparation du lancement"))
        }
    }
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
}
