package fr.astragames.app.windows

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import fr.astragames.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Wine loader/Win32 I/O through the source DocumentProvider, not just a JNI mock. */
class WolfWineSafRuntimeTest {
    @Test fun sourceExecutableAndWin32SaveUseSeekableProviderDescriptors() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wolfWineSaveProbe") == "true")
        assumeTrue(android.os.Build.SUPPORTED_ABIS.first() == "x86_64")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File("/data/local/tmp/astra-wine-save-probe.exe")
        check(fixture.isFile) { "Build scripts/wolf-runtime/create-save-fixture.py and ADB-push its authored EXE." }
        val id = "wine-saf-${UUID.randomUUID()}"
        val provider = Uri.parse("content://fr.astragames.app.test.storage")
        val uri = context.contentResolver.call(provider, "fixture", id, null)!!.getString("uri")!!
        val original = File(context.filesDir, "fixtures/$id")
        val storage = WolfGameStorage(context, id, uri)
        var runner: WolfProcess? = null
        var x11: XServerComponent? = null
        var shm: SysVSharedMemoryComponent? = null
        var activity: ActivityScenario<MainActivity>? = null
        try {
            fixture.copyTo(File(original, "Game.exe"))
            File(original, "SaveData").mkdirs()
            val oldSave = File(original, "SaveData/Save01.sav").apply { writeText("existing game progress") }
            storage.prepare("Game.exe") { }
            assertTrue(storage.accessDescription, storage.isSaf)
            assertEquals("Only the physical working directory stub exists", 0L, File(storage.game, "Game.exe").length())
            val runtime = WolfRuntimeInstaller(context).install { }
            lateinit var server: XServer
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity {
                server = XServer(it, ScreenInfo("800x600"))
                val view = XServerView(it, server); server.renderer = view.renderer
                it.setContentView(view)
            }
            val sockets = File(context.cacheDir, id).apply { mkdirs() }
            val display = UnixSocketConfig.create(sockets.path, "/x/X0")
            val memory = UnixSocketConfig.create(sockets.path, "/shm/SM0")
            x11 = XServerComponent(server, display).also { it.start() }
            shm = SysVSharedMemoryComponent(server, memory).also { it.start() }
            runner = WolfProcess(context, runtime, storage, display.path, memory.path, options = WolfOptions(performance = WolfPerformance.FAST))
            runner.initialize()
            val process = runner.launch("Game.exe")
            try {
                assertTrue("SAF Wine probe timed out", process.waitFor(90, TimeUnit.SECONDS))
                assertEquals("Wine/Win32 SAF probe failed; see runtime.log", 0, process.exitValue())
            } finally { if (process.isAlive) process.destroyForcibly() }
            runner.stop(); runner = null
            assertEquals(0, storage.synchronize())
            val proof = File(original, "astra-wine-save-probe.txt").readText()
            assertTrue(proof, proof.contains("current=G:", ignoreCase = true))
            assertTrue(proof, proof.contains("save_written=1"))
            assertEquals("Astra Wine save probe", File(original, "SaveData/AstraProbe.sav").readText())
            assertEquals("existing game progress", oldSave.readText())
            assertEquals("No executable bytes imported", 0L, File(storage.game, "Game.exe").length())
            File(context.getExternalFilesDir(null), "wolf-wine-saf-proof.txt").writeText(
                "SDK x86_64 emulator; actual Wine9/Win32 loader and file APIs; original authored fixture; imported executable bytes=0\n$proof\n${storage.storageStatistics()}")
        } finally {
            runCatching { runner?.stop() }; runCatching { x11?.stop() }; runCatching { shm?.stop() }
            runCatching { storage.restoreSourceConfiguration() }; storage.closeMount()
            context.contentResolver.call(provider, "removeFixture", id, null)
            storage.directory.deleteRecursively(); File(context.cacheDir, id).deleteRecursively()
            activity?.close()
        }
    }
}
