package fr.astragames.app.windows

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class WolfProcessRecoveryTest {
    @Test fun interruptionRecoveryStopsOnlyTheAppsOwnedWineChildren() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = File(context.filesDir, "wolf-games/${"a".repeat(64)}/prefix")
        val runtime = File(context.noBackupFilesDir, "wolf-runtime/wine9-astra-2")
        val orphan = ProcessBuilder("/system/bin/sleep", "60").apply {
            environment()["WINEPREFIX"] = prefix.path
            environment()["ASTRA_RUNTIME_ROOT"] = runtime.path
        }.start()
        val unrelated = ProcessBuilder("/system/bin/sleep", "60").start()
        try {
            assertTrue(orphan.isAlive); assertTrue(unrelated.isAlive)
            assertTrue(WolfProcessRecovery(context).recover() >= 1)
            assertTrue("Owned orphan remained alive", orphan.waitFor(3, TimeUnit.SECONDS))
            assertTrue("Unrelated app subprocess was killed", unrelated.isAlive)
        } finally { orphan.destroyForcibly(); unrelated.destroyForcibly() }
    }
}
