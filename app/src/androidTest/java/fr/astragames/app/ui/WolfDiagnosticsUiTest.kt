package fr.astragames.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.windows.WolfDiagnostics
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class WolfDiagnosticsUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val id = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "wolf-ui-$id").apply { mkdirs() }
    private val context = object : ContextWrapper(app) {
        override fun getNoBackupFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("wolf-ui-$id", mode)
    }
    @After fun cleanup() { root.deleteRecursively(); app.deleteSharedPreferences("wolf-ui-$id") }

    @Test fun debugSwitchPersistsAndCrashReportCanBeReadAndShared() {
        val store = WolfDiagnostics(context)
        store.begin("Wolf test", "Game.exe").apply { stage("BOX64_FAILURE_FIXTURE"); event("code de sortie 139"); finish("Échec") }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalAppLanguage provides AppLanguage.FRENCH) {
                MaterialTheme { WolfDiagnosticsDialog {} }
            }
        }
        compose.onNodeWithText("Mode debug Wolf RPG").assertIsOff().performClick()
        compose.waitUntil { WolfDiagnostics(context).enabled }
        compose.onNodeWithText("Mode debug Wolf RPG").assertIsOn()
        compose.waitUntil { compose.onAllNodesWithText("BOX64_FAILURE_FIXTURE", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Partager le rapport").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("BOX64_FAILURE_FIXTURE", substring = true).assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(app.getExternalFilesDir(null), "wolf-diagnostics-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
