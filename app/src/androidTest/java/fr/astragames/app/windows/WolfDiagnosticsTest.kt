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

    @Test fun liveReportDoesNotDeleteAnInFlightMetadataWrite() {
        val store = WolfDiagnostics(context)
        val session = store.begin("Live metadata fixture", "Game.exe")
        val metadata = android.util.AtomicFile(File(session.directory, "session.json"))
        val output = metadata.startWrite()
        try {
            output.write(session.metadataSnapshot().toByteArray())
            output.fd.sync()
            assertTrue(store.report(session.directory).contains("Live metadata fixture"))
            assertTrue("Reading an active report must not remove the writer's staging file",
                File(session.directory, "session.json.new").isFile)
            metadata.finishWrite(output)
        } catch (error: Throwable) { metadata.failWrite(output); throw error }
        finally { session.finish("done") }
        assertTrue(store.report(session.directory).contains("État : done"))
    }

    @Test fun loadingTraceRemainsAvailableWhileSessionMetadataIsBusy() {
        val store = WolfDiagnostics(context)
        val session = store.begin("Stalled loading fixture", "Game.exe")
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = kotlin.concurrent.thread {
            synchronized(session) { locked.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            executor.submit { session.loadingDelay(2, "Moteur Wolf déjà installé") }
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
            val trace = File(session.directory, "loading.log").readText()
            assertTrue(trace.contains("étape 2 / 8 : Moteur Wolf déjà installé"))
            assertTrue(trace.contains("main"))
            assertTrue(trace.contains("ne prouve pas un blocage"))
        } finally { release.countDown(); holder.join(1000); executor.shutdownNow(); session.finish("done") }
        assertTrue(store.report(session.directory).contains("===== loading ====="))
    }

    @Test fun gameErrorLogDistinguishesHistoricalAndCurrentMessagesAndDecodesJapanese() {
        val store = WolfDiagnostics(context)
        val game = File(root, "game").apply { mkdirs() }
        val file = File(game, "Game_ErrorLog.txt")
        file.writeText("保存エラー", java.nio.charset.Charset.forName("windows-31j"))
        val old = store.begin("fixture", "Game.exe")
        old.gameErrorLog(game, beforeLaunch = true)
        old.gameErrorLog(game)
        old.finish("done")
        assertTrue(store.report(old.directory).contains("déjà présent, inchangé"))
        assertTrue(store.report(old.directory).contains("保存エラー"))
        val current = store.begin("fixture", "Game.exe")
        current.gameErrorLog(game, beforeLaunch = true)
        file.appendText(" new error")
        current.gameErrorLog(game)
        current.finish("done")
        assertTrue(store.report(current.directory).contains("créé ou modifié pendant cette session"))
    }

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

    @Test fun recoveryIgnoresLiveFinishedAndDismissedSessions() {
        val store = WolfDiagnostics(context)
        val live = store.begin("Live", "Game.exe")
        assertNull(store.pendingRecovery())
        live.finish("Normal close")
        assertNull(store.pendingRecovery())
        val interrupted = File(root, "wolf-diagnostics/9999999999999-interrupted").apply { mkdirs() }
        WolfDiagnosticSession(interrupted, false).stage("Installation : Box64")
        assertEquals(interrupted, store.pendingRecovery())
        store.dismissRecovery(interrupted)
        assertNull(WolfDiagnostics(context).pendingRecovery())
        assertTrue(interrupted.exists()) // Dismissing never deletes the report.
        val older = File(root, "wolf-diagnostics/9999999999998-older").apply { mkdirs() }
        WolfDiagnosticSession(older, false).stage("Older interruption")
        assertNull(store.pendingRecovery())
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
