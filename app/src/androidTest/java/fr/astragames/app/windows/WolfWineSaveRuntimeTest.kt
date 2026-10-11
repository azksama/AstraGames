package fr.astragames.app.windows

import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.xconnector.UnixSocketConfig
import androidx.test.core.app.ActivityScenario
import fr.astragames.app.MainActivity
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import com.winlator.widget.XServerView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual Wine DLLs and Win32 APIs on Android, separately from native-engine fixtures. */
class WolfWineSaveRuntimeTest {
    @Test fun directGameVolumeChecksAndSaveWritesUseTheGameFilesystem() = probe(true)
    @Test fun copiedGameVolumeChecksAndSaveWritesUseTheGameFilesystem() = probe(false)
    private fun probe(direct: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("wolfWineSaveProbe") == "true")
        assumeTrue(android.os.Build.SUPPORTED_ABIS.first() == "x86_64")
        val context = instrumentation.targetContext
        val fixture = File("/data/local/tmp/astra-wine-save-probe.exe")
        check(fixture.isFile) { "Build and ADB-push the authored save probe first." }
        val id = "wine-save-probe-${UUID.randomUUID()}"
        val original = File(context.filesDir, id).apply { mkdirs() }
        val storage = WolfGameStorage(context, id, original.toURI().toString(), preferDirect = direct)
        var runner: WolfProcess? = null
        var x11: XServerComponent? = null
        var shm: SysVSharedMemoryComponent? = null
        var activity: ActivityScenario<MainActivity>? = null
        try {
            fixture.copyTo(File(original, "Game.exe"))
            val previousSave = File(original, "SaveData/Save01.sav").apply { parentFile!!.mkdirs(); writeText("existing game progress") }
            storage.prepare("Game.exe") { }
            assertEquals(direct, storage.isDirect)
            val runtime = WolfRuntimeInstaller(context).install { }
            lateinit var server: XServer
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity {
                server = XServer(it, ScreenInfo("800x600"))
                val view = XServerView(it, server)
                server.renderer = view.renderer
                it.setContentView(view)
            }
            val sockets = File(context.cacheDir, id).apply { mkdirs() }
            val display = UnixSocketConfig.create(sockets.path, "/x/X0")
            val memory = UnixSocketConfig.create(sockets.path, "/shm/SM0")
            x11 = XServerComponent(server, display).also { it.start() }
            shm = SysVSharedMemoryComponent(server, memory).also { it.start() }
            runner = WolfProcess(context, runtime, storage, display.path, memory.path, options = WolfOptions(performance = WolfPerformance.FAST))
            runner.initialize()
            // Reproduce the former launch: no dedicated game drive, Unix executable path.
            Files.delete(File(storage.prefix, "dosdevices/g:").toPath())
            @Suppress("UNCHECKED_CAST")
            val environment = WolfProcess::class.java.getDeclaredField("environment").apply { isAccessible = true }.get(runner) as Map<String, String>
            val legacy = ProcessBuilder("/system/bin/linker64", File(runtime, "proton/bin/wine").path, File(original, "Game.exe").path)
                .directory(original).redirectErrorStream(true).redirectOutput(File(storage.directory, "legacy-save-probe.log"))
                .apply { environment().putAll(environment) }.start()
            try {
                assertTrue("Legacy probe timed out", legacy.waitFor(45, TimeUnit.SECONDS))
                assertEquals("The old drive must reproduce the zero-space check", 20, legacy.exitValue())
            } finally { if (legacy.isAlive) legacy.destroyForcibly() }
            val before = File(original, "astra-wine-save-probe.txt").readText()
            val proof = File(context.getExternalFilesDir(null), "wolf-wine-save-volume-${if (direct) "direct" else "copy"}-proof.txt")
            proof.writeText("SDK emulator x86_64; real Wine/Win32 APIs; authored fixture; direct=$direct; FAST profile\nBEFORE\n$before")
            assertTrue(before, before.contains("current=Z:", ignoreCase = true)); assertTrue(before, before.contains("root_available=0"))
            val fixed = runner.launch("Game.exe")
            try {
                assertTrue("Mapped probe timed out", fixed.waitFor(45, TimeUnit.SECONDS))
                assertEquals("The real Windows save probe failed", 0, fixed.exitValue())
            } finally { if (fixed.isAlive) fixed.destroyForcibly() }
            val after = File(storage.game, "astra-wine-save-probe.txt").readText()
            proof.appendText("\nAFTER\n$after")
            assertTrue(after, after.contains("current=G:", ignoreCase = true)); assertTrue(after, after.contains("save_written=1"))
            assertEquals("Astra Wine save probe", File(storage.game, "SaveData/AstraProbe.sav").readText())
            assertEquals("existing game progress", previousSave.readText())
        } finally {
            runCatching { runner?.stop() }; runCatching { x11?.stop() }; runCatching { shm?.stop() }
            storage.restoreSourceConfiguration()
            original.deleteRecursively(); storage.directory.deleteRecursively()
            File(context.cacheDir, id).deleteRecursively()
            activity?.close()
        }
    }
}
