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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Real Win32 memory APIs in the shipped Wine runtime; original, disposable fixture only. */
class WolfWineCheatTest {
    @Test fun realWineSearchRefineWriteUndoFreezeAndProcessIsolation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("wolfWineCheatProbe") == "true")
        instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val fixture = File("/data/local/tmp/" + InstrumentationRegistry.getArguments().getString("memoryFixture", "astra-memory-fixture.exe"))
        check(fixture.isFile)
        val id = "wine-memory-probe-${UUID.randomUUID()}"
        val original = File(context.filesDir, id).apply { mkdirs() }
        val storage = WolfGameStorage(context, id, original.toURI().toString())
        var runner: WolfProcess? = null; var editor: WolfCheatSession? = null; var wrong: WolfCheatSession? = null
        var x11: XServerComponent? = null; var shm: SysVSharedMemoryComponent? = null
        var activity: ActivityScenario<MainActivity>? = null
        try {
            fixture.copyTo(File(original, "Game.exe"))
            storage.prepare("Game.exe") { }
            val runtime = WolfRuntimeInstaller(context).install { }
            lateinit var server: XServer
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { server = XServer(it, ScreenInfo("800x600"));val view=XServerView(it,server);server.renderer=view.renderer;it.setContentView(view) }
            val sockets=File(context.cacheDir,id).apply{mkdirs()}
            val display=UnixSocketConfig.create(sockets.path,"/x/X0");val memory=UnixSocketConfig.create(sockets.path,"/shm/SM0")
            x11=XServerComponent(server,display).also{it.start()};shm=SysVSharedMemoryComponent(server,memory).also{it.start()}
            runner=WolfProcess(context,runtime,storage,display.path,memory.path)
            runner.initialize();val game=runner.launch("Game.exe")
            withTimeout(30_000) { while(!File(storage.game,"fixture.bin").isFile)delay(100) }
            val b=ByteBuffer.wrap(File(storage.game,"fixture.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val addresses=List(6){b.long}
            editor=WolfCheatSession(context,storage.directory,"Game.exe",runner)
            editor.command(0)
            val types=listOf(WolfCheatType.INT32,WolfCheatType.INT16,WolfCheatType.INT8,WolfCheatType.INT64,WolfCheatType.FLOAT,WolfCheatType.DOUBLE)
            val expected=listOf("123456789","-1731","73","1234567891234567","123.5","456.25")
            addresses.indices.forEach { i -> assertEquals(expected[i],types[i].format(editor.read(addresses[i],types[i]))) }
            editor.write(addresses[0],types[0],123456789,99999999)
            assertEquals(99999999L,editor.read(addresses[0],types[0]))
            assertTrue(runCatching { editor.write(addresses[0],types[0],1,2) }.isFailure)
            assertEquals(99999999L,editor.read(addresses[0],types[0]));editor.undo()
            assertEquals(123456789L,editor.read(addresses[0],types[0]))
            val found=editor.command(1,value=123456789)
            assertTrue(found.count>0);assertFalse(found.truncated)
            val page=editor.command(3);assertTrue(page.rows.any{it.address==addresses[0]})
            File(storage.game,"change").writeText("1");delay(150)
            val refined=editor.command(2,mode=2);assertTrue(refined.count>0)
            assertTrue(editor.command(3).rows.any{it.address==addresses[0] && it.bits==77777777L})
            editor.command(10);assertEquals(found.count,editor.result!!.count)
            File(storage.game,"change").delete();delay(100)
            val pin=WolfCheatSession.Pin(addresses[0],types[0],"Or",88888888)
            editor.freeze(pin,true);delay(400);assertEquals(88888888L,editor.read(addresses[0],types[0]))
            editor.freeze(pin,false);editor.write(addresses[0],types[0],88888888,42424242);delay(350)
            assertEquals(42424242L,editor.read(addresses[0],types[0]))
            if (InstrumentationRegistry.getArguments().getString("wolfCheatUi") == "true") {
                val session = editor
                activity.onActivity {
                    it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    showWolfCheats(it, session)
                }
                setText("Valeur à rechercher", "42424242")
                click("Nouvelle recherche")
                withTimeout(30_000) { while(session.result?.rows?.none { it.bits == 42424242L } != false) delay(100) }
                val row = "0x${addresses[0].toString(16).uppercase()}  ·  42424242"
                click(row)
                setText("Nouvelle valeur", "51515151")
                capture("wolf-cheat-edit.png")
                click("Modifier")
                withTimeout(10_000) { while(session.read(addresses[0],types[0]) != 51515151L) delay(100) }
                click("Valeurs suivies (0)")
                capture("wolf-cheat-panel.png")
                click("Annuler la dernière modification")
                withTimeout(10_000) { while(session.read(addresses[0],types[0]) != 42424242L) delay(100) }
                click("Fermer")
            }
            // A helper with another image path must not attach to this game, even in the same prefix.
            wrong=WolfCheatSession(context,storage.directory,"NotThisGame.exe",runner)
            assertTrue(runCatching{wrong.command(0)}.exceptionOrNull()?.message?.contains("introuvable")==true)
            assertEquals(42424242L,editor.read(addresses[0],types[0]))
            File(storage.game,"finish").writeText("1")
            withTimeout(10_000){while(game.isAlive)delay(100)}
            assertTrue(runCatching{editor.read(addresses[0],types[0])}.isFailure)
            File(context.getExternalFilesDir(null),"wolf-wine-cheat-proof.txt").writeText("Real Wine Win32 APIs on ${android.os.Build.SUPPORTED_ABIS.first()}; fixture=${fixture.name}; 6 numeric types read; exact search (${found.count} results); changed refinement; writes, stale-write rejection, undo, freeze/unfreeze, wrong-executable isolation and terminated-session rejection passed. Authored fixture only.\n")
        } finally {
            editor?.close();wrong?.close();runCatching{runner?.stop()};runCatching{x11?.stop()};runCatching{shm?.stop()}
            storage.restoreSourceConfiguration();original.deleteRecursively();storage.directory.deleteRecursively();File(context.cacheDir,id).deleteRecursively();activity?.close()
        }
    }

    private fun click(label: String) = interact(label) { it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }
    private fun setText(label: String, value: String) = interact(label) {
        it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })
    }
    private fun interact(label: String, action: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        fun nodes(n: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
            listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::nodes).orEmpty() }
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val all = ui.rootInActiveWindow?.let(::nodes).orEmpty()
            if (all.any { (it.text?.toString()?.equals(label, true) == true || it.contentDescription == label) && action(it) }) return
            all.filter { it.isScrollable }.forEach { it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            android.os.SystemClock.sleep(150)
        }
        error("Cheat UI interaction failed: $label")
    }
    private fun capture(name: String) {
        android.os.SystemClock.sleep(600)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
