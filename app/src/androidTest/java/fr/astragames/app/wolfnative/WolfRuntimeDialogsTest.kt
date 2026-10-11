package fr.astragames.app.wolfnative

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fr.astragames.app.windows.WolfGameOptions
import fr.astragames.app.windows.WolfOptions
import fr.astragames.app.windows.WolfRuntimeMode
import fr.astragames.wolf.NativeFrame
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises the actual runtime menu, modal pause lifetime, save picker and diagnostic. */
class WolfRuntimeDialogsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun activity() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .filterIsInstance<WolfNativeActivity>().firstOrNull()
    private fun main(action: (WolfNativeActivity) -> Unit) {
        val block = { activity()?.let(action); Unit }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else instrumentation.runOnMainSync(block)
    }
    private fun dialog(): AlertDialog? {
        var result: AlertDialog? = null
        main { activity ->
            @Suppress("UNCHECKED_CAST")
            val dialogs = WolfNativeActivity::class.java.getDeclaredField("dialogs").apply { isAccessible = true }.get(activity) as Set<AlertDialog>
            result = dialogs.lastOrNull { it.isShowing }
        }
        return result
    }
    private fun frame(): NativeFrame? {
        var result: NativeFrame? = null
        main { activity -> descendants(activity.window.decorView).filterIsInstance<WolfNativeView>().firstOrNull()?.let {
            result = WolfNativeView::class.java.getDeclaredField("frame").apply { isAccessible = true }.get(it) as NativeFrame?
        } }
        return result
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(40) }
        fail("Runtime dialog did not reach $label; diagnostic=${frame()?.diagnostic}")
    }
    private fun menu() {
        main { WolfNativeActivity::class.java.getDeclaredMethod("showMenu").apply { isAccessible = true }.invoke(it) }
        await("menu") { dialog()?.listView?.adapter?.count == 6 }
    }
    private fun row(index: Int) = instrumentation.runOnMainSync {
        val list = requireNotNull(dialog()).listView
        list.performItemClick(null, index, list.adapter.getItemId(index))
    }
    private fun button(which: Int) = instrumentation.runOnMainSync { requireNotNull(dialog()).getButton(which).performClick() }

    @Test fun realNativeMenusPauseThroughNestedOptionsAndKeepSaveAndDiagnosticActionsWorking() {
        val root = File(context.cacheDir, "runtime-dialog-${UUID.randomUUID()}").apply { mkdirs() }
        val id = root.name
        NativeGameFixture.writePlayerLaunchFlag(root)
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE))
        try {
            context.startActivity(WolfNativeActivity.intent(context, id, Uri.fromFile(root).toString(), "Game.exe", "Authored native modal fixture")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("normal player launch") { frame()?.dialog?.text == "Player launch: 0" }
            assertNull(frame()?.diagnostic)
            menu(); capture("wolf-native-menu")
            row(0); await("options") { dialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.text?.toString() == "Enregistrer" }
            val before = frame()!!.tick; SystemClock.sleep(200); assertEquals(before, frame()!!.tick)
            capture("wolf-runtime-options")
            instrumentation.runOnMainSync { descendants(requireNotNull(dialog()).window!!.decorView)
                .filterIsInstance<ScrollView>().forEach { it.fullScroll(View.FOCUS_DOWN) } }
            capture("wolf-runtime-options-actions")
            button(AlertDialog.BUTTON_POSITIVE)
            await("resume after nested options") { dialog() == null && frame()!!.tick > before }
            menu(); row(1); await("saved notice") { dialog()?.findViewById<android.widget.TextView>(android.R.id.message)?.text?.contains("Emplacement 0 enregistré") == true }
            button(AlertDialog.BUTTON_POSITIVE)
            menu(); row(2); await("save picker") { dialog()?.listView?.adapter?.count == 1 }
            capture("wolf-native-save-slots"); row(0)
            await("loaded save") { dialog() == null && frame()?.dialog?.text == "Player launch: 0" }
            menu(); row(4); await("diagnostic") { dialog()?.getButton(AlertDialog.BUTTON_NEUTRAL)?.text?.contains("Partager") == true }
            capture("wolf-native-diagnostic")
            assertTrue(requireNotNull(dialog()).getButton(AlertDialog.BUTTON_NEUTRAL).isShown)
            button(AlertDialog.BUTTON_POSITIVE)
            assertNull(frame()?.diagnostic)
        } finally {
            main { it.finish() }
            context.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit()
                .remove(WolfNativeSaveStore.digest(id.toByteArray())).commit()
            File(context.filesDir, "wolf-native/${WolfNativeSaveStore.digest(id.toByteArray())}").takeIf { it.exists() }?.deleteRecursively()
            assertEquals(context.cacheDir.canonicalFile, root.canonicalFile.parentFile); root.deleteRecursively()
        }
    }

    private fun capture(prefix: String) {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("wolfUiCapture") != "true") return
        SystemClock.sleep(350)
        val label = args.getString("captureLabel") ?: "phone"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$prefix-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
