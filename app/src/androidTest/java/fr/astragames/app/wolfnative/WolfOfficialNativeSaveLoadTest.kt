package fr.astragames.app.wolfnative

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fr.astragames.app.windows.WolfGameOptions
import fr.astragames.app.windows.WolfOptions
import fr.astragames.app.windows.WolfRuntimeMode
import fr.astragames.wolf.NativeFrame
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses the original game's menu and event commands. No teleports, common calls or save injection. */
class WolfOfficialNativeSaveLoadTest {
    @Test fun official22961GameMenuSaveRestartAndContinue() = journey("22961")
    @Test fun official3729GameMenuSaveRestartAndContinue() = journey("3729")

    private fun journey(version: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val supplied = InstrumentationRegistry.getArguments().getString("nativeOfficialRoot")
        assumeTrue("Provision official data via SDK ADB", !supplied.isNullOrBlank())
        val root = File(requireNotNull(supplied), version)
        assertTrue(File(root, "Data/BasicData/Game.dat").isFile)
        val id = "official-menu-$version-${UUID.randomUUID()}"
        val saveDirectory = WolfNativeSaveStore.directoryFor(context, id)
        val save = File(saveDirectory, if (version == "22961") "slot-1.astrawolf" else
            "named-${WolfNativeSaveStore.digest("save/savedata01.sav".toByteArray())}.astrawolf")
        val original = File(root, "Save").walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).invariantSeparatorsPath to WolfNativeSaveStore.digest(it.readBytes())
        }
        val output = File(context.getExternalFilesDir(null), "native-official-menu-$version.json")
        val checkpoints = JSONArray()
        val evidence = JSONObject().put("sample", version).put("checkpoints", checkpoints)
        val begin = SystemClock.elapsedRealtime()
        val field = WolfNativeView::class.java.getDeclaredField("frame").apply { isAccessible = true }
        fun activity() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<WolfNativeActivity>().firstOrNull()
        fun frame(): NativeFrame? {
            var current: NativeFrame? = null
            instrumentation.runOnMainSync { activity()?.let { a -> descendants(a.window.decorView)
                .filterIsInstance<WolfNativeView>().firstOrNull()?.let { current = field.get(it) as NativeFrame? } } }
            return current
        }
        fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                assertNull("Engine failed at $label", frame()?.diagnostic)
                SystemClock.sleep(40)
            }
            val current = frame()
            fail("$version: $label; tick=${current?.tick}; map=${current?.map?.map?.name}; " +
                "hero=${current?.map?.characters?.firstOrNull { it.id == -2 }}; " +
                "dialog=${current?.dialog?.text?.take(120)}; pictures=${current?.pictures?.map { it.id }}")
        }
        fun waitTicks(count: Int) {
            val tick = requireNotNull(frame()).tick
            await("$count logical ticks") { (frame()?.tick ?: 0) >= tick + count }
        }
        fun press(label: String) = instrumentation.runOnMainSync {
            val a = requireNotNull(activity())
            val button = descendants(a.window.decorView).filterIsInstance<Button>().first { it.contentDescription == label }
            val now = SystemClock.uptimeMillis()
            // Actual touch dispatch avoids the accessibility click's intentional120ms key hold.
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, button.width / 2f, button.height / 2f, 0)
            val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, button.width / 2f, button.height / 2f, 0)
            try { button.dispatchTouchEvent(down); button.dispatchTouchEvent(up) }
            finally { down.recycle(); up.recycle() }
        }
        fun selectTitleChoice(index: Int) {
            repeat(3) {
                val selected = requireNotNull(frame()?.dialog).selectedChoice
                if (selected == index) return
                press("Bas")
                await("title selection moved") { frame()?.dialog?.selectedChoice != selected }
                waitTicks(2)
            }
            assertEquals("Title choice after actual touch input", index, frame()?.dialog?.selectedChoice)
        }
        fun checkpoint(label: String) {
            val f = requireNotNull(frame())
            val hero = f.map?.characters?.firstOrNull { it.id == -2 }
            checkpoints.put(JSONObject().put("label", label).put("elapsedMs", SystemClock.elapsedRealtime() - begin)
                .put("tick", f.tick).put("map", f.map?.map?.name).put("x", hero?.x).put("y", hero?.y)
                .put("dialog", f.dialog?.let { d -> JSONObject().put("text", d.text)
                    .put("selected", d.selectedChoice).put("choices", JSONArray(d.choices)) })
                .put("pictures", JSONArray().apply { f.pictures.forEach { p -> put(JSONObject()
                    .put("id", p.id).put("text", p.text).put("path", p.path).put("x", p.x).put("y", p.y)
                    .put("fontSize", p.fontSize).put("width", p.width).put("height", p.height)
                    .put("anchor", p.anchor).put("scaleX", p.scaleX).put("scaleY", p.scaleY)) } }))
            instrumentation.runOnMainSync { activity()?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            SystemClock.sleep(100)
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(context.getExternalFilesDir(null), "native-official-menu-$version-$label.png")
                    .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            output.writeText(evidence.toString(2))
        }
        fun closeActivity() {
            instrumentation.runOnMainSync { activity()?.finish() }
            await("activity closed", 5000) {
                var closed = false
                instrumentation.runOnMainSync { closed = activity() == null }
                closed
            }
        }
        fun launch() {
            context.startActivity(WolfNativeActivity.intent(context, id, Uri.fromFile(root).toString(), "Game.exe", "Official menu $version")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("title", 90_000) { frame()?.pictures?.any { it.path?.contains("Title", true) == true } == true }
            await("title choices") { frame()?.dialog?.choices?.size == 3 }
            waitTicks(3)
        }
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE))
        try {
            launch(); checkpoint("title")
            val titleMap = requireNotNull(frame()).map?.map?.name
            selectTitleChoice(0)
            press("Valider")
            await("new game") { frame()?.map?.map?.name?.let { it != titleMap } == true }
            await("tutorial") { frame()?.dialog != null }
            var lastDialog = SystemClock.elapsedRealtime()
            val tutorialDeadline = lastDialog + 60_000
            var count = 0
            while (SystemClock.elapsedRealtime() < tutorialDeadline) {
                if (frame()?.dialog != null) {
                    lastDialog = SystemClock.elapsedRealtime(); press("Valider"); count++; SystemClock.sleep(600)
                } else if (SystemClock.elapsedRealtime() - lastDialog > 1500) break
                else SystemClock.sleep(40)
            }
            assertTrue("Original tutorial was not exercised", count >= 5)
            assertNull(frame()?.dialog)
            checkpoint("new-game")
            val before = requireNotNull(frame()).map!!.characters.first { it.id == -2 }.let { it.x to it.y }
            press("Droite")
            await("movement") { frame()?.map?.characters?.firstOrNull { it.id == -2 }?.let { it.x != before.first || it.y != before.second } == true }
            await("movement settled") { frame()?.map?.characters?.firstOrNull { it.id == -2 }?.moving == false }
            val savedMap = frame()!!.map!!.map.name
            val savedPosition = frame()!!.map!!.characters.first { it.id == -2 }.let { it.x to it.y }
            press("Retour"); waitTicks(120); checkpoint("game-menu")
            repeat(5) { press("Bas"); waitTicks(45) }
            press("Valider"); waitTicks(45); checkpoint("save-slots")
            press("Valider")
            await("save from original events") { save.isFile && save.length() > 100 }
            checkpoint("saved")
            val digest = WolfNativeSaveStore.digest(save.readBytes())
            closeActivity(); launch(); checkpoint("restart-title")
            // The game's own events preselect Continue when a save exists (Sys7).
            selectTitleChoice(1); checkpoint("restart-title-continue")
            press("Valider"); waitTicks(45); checkpoint("continue-slots")
            press("Valider")
            await("load from original title") { frame()?.map?.let { scene -> scene.map.name == savedMap &&
                scene.characters.firstOrNull { it.id == -2 }?.let {
                    kotlin.math.abs(it.x - savedPosition.first) < .001f && kotlin.math.abs(it.y - savedPosition.second) < .001f
                } == true } == true }
            waitTicks(90); checkpoint("loaded")
            assertEquals(digest, WolfNativeSaveStore.digest(save.readBytes()))
            original.forEach { (path, checksum) -> assertEquals("Original Windows save modified", checksum,
                WolfNativeSaveStore.digest(File(root, path).readBytes())) }
            assertNull(frame()?.diagnostic)
            evidence.put("saveBytes", save.length()).put("result", "PASS")
        } catch (error: Throwable) {
            evidence.put("result", "FAIL").put("error", error.toString()); throw error
        } finally {
            closeActivity(); output.writeText(evidence.toString(2))
            context.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit()
                .remove(WolfNativeSaveStore.digest(id.toByteArray())).commit()
            if (saveDirectory.exists()) {
                check(saveDirectory.canonicalFile.parentFile!!.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
                saveDirectory.parentFile!!.deleteRecursively()
            }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
