package fr.astragames.app.wolfnative

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fr.astragames.app.windows.WolfGameOptions
import fr.astragames.app.windows.WolfOptions
import fr.astragames.app.windows.WolfRuntimeMode
import fr.astragames.app.windows.WolfTouchControls
import fr.astragames.wolf.NativeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class WolfNativeInputInstrumentedTest {
    @Test fun customF8ButtonUsesTheNativePhysicalInputReader() = journey(0x1181, 166, KeyEvent.KEYCODE_F8, "166", control = com.winlator.xserver.XKeycode.KEY_F8)
    @Test fun hardwareKeyboardCUsesDxLibScanPlusOneHundred() = journey(0x181, null, KeyEvent.KEYCODE_C, "146")
    @Test fun enterFilterDoesNotTreatTheZConfirmAliasAsPhysicalEnter() = journey(0x1181, 128, KeyEvent.KEYCODE_ENTER, "128", rejectKey = KeyEvent.KEYCODE_Z)
    @Test fun controllerBIsReadableByTheGamesPhysicalButtonCommand() = journey(0x281, null, KeyEvent.KEYCODE_BUTTON_B, "302")
    @Test fun aGameKeyboardRemapHonorsCAndDoesNotKeepZAsAFixedConfirmAlias() = journey(0x90, null,
        KeyEvent.KEYCODE_C, "10", rejectKey = KeyEvent.KEYCODE_Z, mappings = mapOf(52 to 146))
    @Test fun aGameControllerRemapHonorsBAndDoesNotKeepAAsAFixedConfirmAlias() = journey(0x90, null,
        KeyEvent.KEYCODE_BUTTON_B, "10", rejectKey = KeyEvent.KEYCODE_BUTTON_A, mappings = mapOf(55 to 302))

    private fun journey(flags: Int, specific: Int?, code: Int, expected: String,
        rejectKey: Int? = null, mappings: Map<Int, Int> = emptyMap(), control: com.winlator.xserver.XKeycode? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "native-key-${UUID.randomUUID()}").apply { mkdirs() }
        val id = root.name
        NativeGameFixture.writeKeyInput(root, flags, specific, mappings)
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE))
        if (control != null) WolfGameOptions(context, id).addControl("Probe F8", control)
        context.startActivity(WolfNativeActivity.intent(context, id, Uri.fromFile(root).toString(), "Game.exe", "Physical input test")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
                val field = WolfNativeView::class.java.getDeclaredField("frame").apply { isAccessible = true }
                fun read(): Pair<Boolean, NativeFrame?> {
                    var result: Pair<Boolean, NativeFrame?> = false to null
                    instrumentation.runOnMainSync {
                        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<WolfNativeActivity>().firstOrNull() ?: return@runOnMainSync
                        val views = descendants(activity.window.decorView)
                        result = views.filterIsInstance<WolfTouchControls>().any { controls -> controls.isShown } to
                            views.filterIsInstance<WolfNativeView>().firstOrNull()?.let { view -> field.get(view) as NativeFrame? }
                    }
                    return result
                }
                fun await(condition: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40)
                    assertTrue("Native input event did not resume", condition())
                }
                fun sendKey(keyCode: Int) = instrumentation.runOnMainSync {
                    val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<WolfNativeActivity>().single()
                    // sendKeyDownUpSync waits for an idle UI queue, which a running 60 FPS
                    // renderer does not provide. Deliver through the activity's real SDK path.
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                }
                await { read().first }
                if (rejectKey != null) {
                    sendKey(rejectKey)
                    SystemClock.sleep(200)
                    assertNull("A prior/fixed alias must not bypass the game's input reader", read().second?.dialog)
                }
                if (control == null) sendKey(code) else instrumentation.runOnMainSync {
                    val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<WolfNativeActivity>().single()
                    descendants(activity.window.decorView).filterIsInstance<android.widget.Button>().single { it.contentDescription == "Probe F8" }.performClick()
                }
                await { read().second?.dialog != null }
                assertEquals(expected, read().second?.dialog?.text)
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<WolfNativeActivity>().forEach { it.finish() }
            }
            val closedDeadline = SystemClock.elapsedRealtime() + 5000
            var resumed = true
            while (resumed && SystemClock.elapsedRealtime() < closedDeadline) {
                instrumentation.runOnMainSync { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .any { it is WolfNativeActivity } }
                if (resumed) SystemClock.sleep(40)
            }
            context.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit()
                .remove(WolfNativeSaveStore.digest(id.toByteArray())).commit()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
