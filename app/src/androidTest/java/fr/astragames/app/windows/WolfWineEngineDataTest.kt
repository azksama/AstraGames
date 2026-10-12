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
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in proof against real official Wolf EXEs through the shipped Wine. No layout fixture impersonates a game. */
class WolfWineEngineDataTest {
    @Test fun officialWolfNumbersTextExpectedWritesUndoAndSearch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("wolfEngineDataProbe") == "true")
        val version = args.getString("wolfFixtureVersion", "22961")
        require(version in setOf("22961", "3729"))
        val context = instrumentation.targetContext
        val prepared = File(context.filesDir, "wolf-probe/save-fixtures/game$version")
        check(File(prepared, "Game.exe").isFile) { "Push the copied official sample before testing." }
        val id = "wolf-data-probe-${UUID.randomUUID()}"
        val original = File(context.filesDir, id)
        prepared.copyRecursively(original)
        val storage = WolfGameStorage(context, id, original.toURI().toString())
        var runner: WolfProcess? = null; var editor: WolfCheatSession? = null
        var x11: XServerComponent? = null; var shm: SysVSharedMemoryComponent? = null
        var activity: ActivityScenario<MainActivity>? = null
        try {
            storage.prepare("Game.exe") { }
            val runtime = WolfRuntimeInstaller(context).install { }
            lateinit var server: XServer
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { server = XServer(it, ScreenInfo("800x600")); val view = XServerView(it, server); server.renderer = view.renderer; it.setContentView(view) }
            val sockets = File(context.cacheDir, id).apply { mkdirs() }
            val display = UnixSocketConfig.create(sockets.path, "/x/X0"); val memory = UnixSocketConfig.create(sockets.path, "/shm/SM0")
            x11 = XServerComponent(server, display).also { it.start() }; shm = SysVSharedMemoryComponent(server, memory).also { it.start() }
            runner = WolfProcess(context, runtime, storage, display.path, memory.path)
            runner.initialize(); val game = runner.launch("Game.exe")
            editor = WolfCheatSession(context, storage.directory, "Game.exe", runner)
            val adapter = editor.engineData
            val probe = withTimeout(60_000) {
                var supported: EngineDataProbe.Supported? = null
                while (supported == null) {
                    delay(500)
                    supported = adapter.probe() as? EngineDataProbe.Supported
                    check(game.isAlive) { "Official game stopped before its data initialized." }
                }
                supported
            }
            assertEquals(if (version == "22961") "Wolf 2.2961" else "Wolf 3.729", probe.build)
            val common = (probe.commonCount - 1).coerceAtLeast(0)
            val number = adapter.read(15_000_000 + common * 100 + 99)
            val after = EngineDataValue.Number((number.value as EngineDataValue.Number).value xor 1)
            val written = adapter.write(number, after)
            assertEquals(after, adapter.read(number.id).value)
            assertTrue(adapter.canUndo)
            assertTrue(runCatching { adapter.write(number, EngineDataValue.Number(42)) }.isFailure)
            assertEquals(after, adapter.read(number.id).value)
            assertEquals(number.value, adapter.undo().value)
            assertFalse(adapter.canUndo)
            assertTrue(runCatching { adapter.write(number.copy(token = number.token xor 1), after) }.isFailure)
            val text = adapter.read(15_000_000 + common * 100 + 5)
            val textAfter = EngineDataValue.Text("星")
            val textWritten = adapter.write(text, textAfter)
            assertEquals(textAfter, adapter.read(text.id).value)
            assertEquals(text.value, adapter.undo().value)
            assertTrue(runCatching { adapter.write(text, EngineDataValue.Text("x".repeat(text.capacityBytes + 1))) }.isFailure)
            val matches = adapter.search(EngineDataCategory.COMMON_NUMBERS, common, number.id.toString())
            assertTrue(matches.variables.any { it.id == number.id })
            File(context.getExternalFilesDir(null), "wolf-engine-data-$version-proof.txt").writeText(
                "Real Wine Win32 APIs / ${android.os.Build.SUPPORTED_ABIS.first()}; ${probe.build}; logical numeric ${written.id} and text ${textWritten.id}; read/write/reread/undo, stale expected/token rejection, capacity refusal and category/ID search passed. Official EXE unchanged, copied sample data only.\n")
        } finally {
            editor?.close(); runCatching { runner?.stop() }; runCatching { x11?.stop() }; runCatching { shm?.stop() }
            storage.restoreSourceConfiguration(); original.deleteRecursively(); storage.directory.deleteRecursively(); File(context.cacheDir, id).deleteRecursively(); activity?.close()
        }
    }
}
