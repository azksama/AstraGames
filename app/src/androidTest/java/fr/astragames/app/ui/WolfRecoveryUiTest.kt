package fr.astragames.app.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import fr.astragames.app.MainActivity
import fr.astragames.app.windows.WolfDiagnosticSession
import fr.astragames.app.windows.WolfDiagnostics
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class WolfRecoveryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mainActivityAutomaticallyOpensInterruptedReportAndRemembersDismissal() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val store = WolfDiagnostics(app)
        val directory = File(app.noBackupFilesDir, "wolf-diagnostics/${System.currentTimeMillis()}-${UUID.randomUUID()}").apply { mkdirs() }
        // Same durable state as a dead process: metadata/logs exist, no active in-memory session.
        WolfDiagnosticSession(directory, false).apply { stage("AUTORECOVERY_UI_FIXTURE"); event("Survived process death") }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithText("AUTORECOVERY_UI_FIXTURE", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("AUTORECOVERY_UI_FIXTURE", substring = true).assertIsDisplayed()
                Espresso.pressBack()
                compose.waitUntil(5000) { store.pendingRecovery() == null }
                scenario.recreate()
                compose.waitForIdle()
                compose.onNodeWithText("AUTORECOVERY_UI_FIXTURE", substring = true).assertDoesNotExist()
                assertTrue(directory.isDirectory)
            }
        } finally { directory.deleteRecursively() }
    }
}
