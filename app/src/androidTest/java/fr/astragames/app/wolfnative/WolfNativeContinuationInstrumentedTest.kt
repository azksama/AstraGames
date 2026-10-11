package fr.astragames.app.wolfnative

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fr.astragames.app.windows.WolfGameOptions
import fr.astragames.app.windows.WolfOptions
import fr.astragames.app.windows.WolfRuntimeMode
import fr.astragames.app.windows.WolfDiagnostics
import fr.astragames.wolf.NativeFrame
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class WolfNativeContinuationInstrumentedTest {
    @Test fun sixtyFourSaveReadsResumeWithinOneLogicalTickWithoutOneFramePerRead() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "native-continuations-${UUID.randomUUID()}").apply { mkdirs() }
        val id = root.name
        val output = File(context.getExternalFilesDir(null), "native-continuation-burst.json")
        val evidence = JSONObject().put("reads", 64).put("gameId", id)
        val field = WolfNativeView::class.java.getDeclaredField("frame").apply { isAccessible = true }
        fun activity() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<WolfNativeActivity>().firstOrNull()
        fun frame(): NativeFrame? {
            var result: NativeFrame? = null
            instrumentation.runOnMainSync { activity()?.let { a -> descendants(a.window.decorView)
                .filterIsInstance<WolfNativeView>().firstOrNull()?.let { result = field.get(it) as NativeFrame? } } }
            return result
        }
        fun await(timeout: Long, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < deadline) {
                assertNull("Native continuation failed", frame()?.diagnostic)
                SystemClock.sleep(10)
            }
            if (!condition()) {
                val current = frame()
                var windowState = "No resumed native activity"
                instrumentation.runOnMainSync { activity()?.let { a ->
                    windowState = "focus=${a.hasWindowFocus()}; views=" + descendants(a.window.decorView)
                        .filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.joinToString(" | ")
                } }
                val store = WolfDiagnostics(context)
                val diagnostic = store.reports().firstOrNull { store.report(it).contains(id) }?.let(store::report).orEmpty()
                evidence.put("lastTick", current?.tick).put("frameDiagnostic", current?.diagnostic)
                    .put("windowState", windowState).put("diagnostic", diagnostic)
                fail("Native host stalled: tick=${current?.tick}; diagnostic=${current?.diagnostic}; $windowState\n${diagnostic.take(6_000)}")
            }
        }
        NativeGameFixture.writeSaveReadBurst(root)
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE))
        context.startActivity(WolfNativeActivity.intent(context, id, Uri.fromFile(root).toString(), "Game.exe", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await(15_000) { (frame()?.tick ?: 0) >= 80 }
            val started = SystemClock.elapsedRealtime()
            val tickBefore = requireNotNull(frame()).tick
            await(900) { frame()?.dialog?.text == "read burst finished" }
            val elapsed = SystemClock.elapsedRealtime() - started
            val tickAfter = requireNotNull(frame()).tick
            assertTrue("Save reads must resume the same logical tick, rather than advancing time", tickAfter - tickBefore <= 18)
            evidence.put("result", "PASS").put("elapsedMs", elapsed)
                .put("tickBefore", tickBefore).put("tickAfter", tickAfter)
        } catch (failure: Throwable) {
            evidence.put("result", "FAIL").put("error", failure.toString())
            throw failure
        } finally {
            output.writeText(evidence.toString(2))
            instrumentation.runOnMainSync { activity()?.finish() }
            context.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit()
                .remove(WolfNativeSaveStore.digest(id.toByteArray())).commit()
            val directory = WolfNativeSaveStore.directoryFor(context, id).parentFile!!
            check(directory.canonicalFile.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            if (directory.exists()) directory.deleteRecursively()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
