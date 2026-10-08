package fr.astragames.app.windows

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class WolfDiagnosticsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val id = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "wolf-diagnostics-test-$id").apply { mkdirs() }
    private val context = object : ContextWrapper(app) {
        override fun getNoBackupFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("wolf-test-$id", mode)
    }
    @After fun cleanup() { root.deleteRecursively(); app.deleteSharedPreferences("wolf-test-$id") }

    @Test fun failingSubprocessHasStdoutStderrAndExitCodeInShareableReport() {
        val store = WolfDiagnostics(context)
        assertFalse(store.enabled)
        store.enabled = true
        assertTrue(WolfDiagnostics(context).enabled)
        val session = store.begin("Diagnostic fixture", "Game.exe")
        session.stage("Initialisation Box64 / Wine")
        val child = ProcessBuilder("/system/bin/sh", "-c", "echo 'startup banner'; echo 'fatal fixture' >&2; exit 139").redirectErrorStream(true).start()
        session.capture(child, "runtime")
        val exit = child.waitFor()
        session.exited(child, "wineboot", exit)
        session.failure("Launch failed", IllegalStateException("fixture failure"))
        session.finish("Échec fixture")
        val report = WolfDiagnostics(context).report(session.directory)
        assertTrue(report.contains("startup banner"))
        assertTrue(report.contains("fatal fixture"))
        assertTrue(report.contains("code de sortie 139"))
        assertTrue(report.contains("IllegalStateException: fixture failure"))
        assertTrue(report.contains("Initialisation Box64 / Wine"))
        val intent = store.shareIntent(session.directory)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = intent.clipData!!.getItemAt(0).uri
        assertEquals("content", uri.scheme)
        val exported = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(report, exported)
        File(app.cacheDir, "wolf-reports/Astra-Wolf-${session.directory.name}.txt").delete()
    }

    @Test fun retentionKeepsFiveSeparateSessionsAndTheirLastSteps() {
        val store = WolfDiagnostics(context)
        repeat(7) { index -> store.begin("game$index", "Game.exe").apply { stage("stage$index"); finish("done$index") } }
        val reports = store.reports()
        assertEquals(5, reports.size)
        assertTrue(reports.any { store.report(it).contains("stage6") })
        assertTrue(reports.none { store.report(it).contains("stage0") })
    }

    @Test fun incompleteSessionIsRecoveredWithoutInventingACrashCause() {
        val store = WolfDiagnostics(context)
        val directory = File(root, "wolf-diagnostics/1-recovery").apply { mkdirs() }
        WolfDiagnosticSession(directory, false).apply { stage("Box64 probe"); event("last surviving line") }
        val report = store.report(directory)
        assertTrue(report.contains("Session interrompue sans résultat final"))
        assertTrue(report.contains("last surviving line"))
        assertFalse(report.contains("État : Crash"))
    }

    @Test fun prepareProcessDeathRecovery() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("wolfRecovery") == "prepare")
        WolfDiagnostics(app).begin("PROCESS_DEATH_PROBE", "Game.exe").apply { stage("Box64 interrupted probe"); event("PERSIST_BEFORE_DEATH") }
    }

    @Test fun verifyProcessDeathRecovery() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("wolfRecovery") == "verify")
        val store = WolfDiagnostics(app)
        val report = store.report(store.reports().first())
        assertTrue(report.contains("PROCESS_DEATH_PROBE"))
        assertTrue(report.contains("PERSIST_BEFORE_DEATH"))
        assertTrue(report.contains("Box64 interrupted probe"))
        assertTrue(report.contains("Session interrompue sans résultat final"))
    }
}
