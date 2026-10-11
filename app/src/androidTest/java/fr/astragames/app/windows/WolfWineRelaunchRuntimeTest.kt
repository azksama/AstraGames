package fr.astragames.app.windows

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import fr.astragames.app.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class WolfWineRelaunchRuntimeTest {
    @Test fun exitedLauncherDoesNotKillItsWindowsChildAndToolsDoNotKeepGameAlive() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wolfWineRelaunchProbe") == "true")
        assumeTrue(android.os.Build.SUPPORTED_ABIS.first() == "x86_64")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File("/data/local/tmp/astra-wine-relaunch-probe.exe")
        check(fixture.isFile)
        val id = "wine-relaunch-${UUID.randomUUID()}"
        val original = File(context.filesDir,id).apply { mkdirs() }
        val storage = WolfGameStorage(context,id,original.toURI().toString(),preferDirect=true)
        var runner: WolfProcess? = null
        var x11: XServerComponent? = null
        var shm: SysVSharedMemoryComponent? = null
        var activity: ActivityScenario<MainActivity>? = null
        try {
            fixture.copyTo(File(original,"Game.exe"))
            storage.prepare("Game.exe") {}
            val runtime = WolfRuntimeInstaller(context).install {}
            lateinit var server: XServer
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity {
                server = XServer(it,ScreenInfo("800x600"))
                val view = XServerView(it,server); server.renderer = view.renderer; it.setContentView(view)
            }
            val sockets = File(context.cacheDir,id).apply { mkdirs() }
            val display = UnixSocketConfig.create(sockets.path,"/x/X0")
            val memory = UnixSocketConfig.create(sockets.path,"/shm/SM0")
            x11 = XServerComponent(server,display).also { it.start() }
            shm = SysVSharedMemoryComponent(server,memory).also { it.start() }
            runner = WolfProcess(context,runtime,storage,display.path,memory.path)
            runner.initialize()
            val process = runner.launch("Game.exe")
            assertTrue(process.waitFor(45,TimeUnit.SECONDS)); assertEquals(0,process.exitValue())
            val started = android.os.SystemClock.elapsedRealtime()
            while (!File(original,"child.ready").exists() && android.os.SystemClock.elapsedRealtime()-started<15_000) delay(100)
            assertTrue("Child must reach its Win32 loop",File(original,"child.ready").exists())
            repeat(24) { assertTrue("Child must survive longer than relaunch grace",runner.isGameAlive(process)); delay(250) }
            val toolDirectory = File(storage.directory,"auxiliary").apply { mkdirs() }
            val tool = runner.startAuxiliary(File(original,"Game.exe"),listOf("child"),toolDirectory)
            val toolStart = android.os.SystemClock.elapsedRealtime()
            while (!File(toolDirectory,"child.ready").exists() && android.os.SystemClock.elapsedRealtime()-toolStart<15_000) delay(100)
            assertTrue(File(toolDirectory,"child.ready").exists())
            File(original,"finish").writeText("1")
            val end = android.os.SystemClock.elapsedRealtime()+15_000
            while (runner.isGameAlive(process) && android.os.SystemClock.elapsedRealtime()<end) delay(250)
            assertFalse("An untagged auxiliary process must not keep the game alive",runner.isGameAlive(process))
            assertTrue("Auxiliary must still be running for the exclusion to be proven",tool.isAlive)
            File(toolDirectory,"finish").writeText("1")
            File(context.getExternalFilesDir(null),"wolf-wine-relaunch-proof.txt").writeText("Actual Wine on Android SDK x86_64: launcher exit=0; Windows child supervised >6s; untagged tool excluded; game ended after child exit.\n")
        } finally {
            runCatching { runner?.stop() }; runCatching { x11?.stop() }; runCatching { shm?.stop() }
            storage.restoreSourceConfiguration(); original.deleteRecursively(); storage.directory.deleteRecursively()
            File(context.cacheDir,id).deleteRecursively(); activity?.close()
        }
    }
}
