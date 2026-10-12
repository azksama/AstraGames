package fr.astragames.app.windows

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import fr.astragames.app.MainActivity
import com.winlator.xserver.XKeycode
import org.junit.Assert.*
import org.junit.Test

class WolfControlActionTest {
    @Test fun toggleComboAndAnotherFingerShareKeysAndFocusLossClearsEveryOwner() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val store = WolfGameOptions(activity, "combo-toggle-${System.nanoTime()}")
            store.setAction("shift", WolfControlAction(listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_Z), WolfControlActionMode.TOGGLE))
            store.setAction("accept", WolfControlAction(listOf(XKeycode.KEY_Z, XKeycode.KEY_ENTER)))
            val held = mutableSetOf<XKeycode>()
            val controls = WolfTouchControls(activity, { key, down -> if (down) held.add(key) else held.remove(key) }, {}, store)
            install(activity, controls)
            val toggle = button(controls, "Shift"); val accept = button(controls, "Valider")
            tap(toggle); assertEquals(setOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_Z), held); assertTrue(toggle.isSelected)
            touch(accept, MotionEvent.ACTION_DOWN); tap(toggle)
            assertEquals(setOf(XKeycode.KEY_Z, XKeycode.KEY_ENTER), held); assertFalse(toggle.isSelected)
            touch(accept, MotionEvent.ACTION_UP); assertTrue(held.isEmpty())
            tap(toggle); touch(accept, MotionEvent.ACTION_DOWN); controls.onWindowFocusChanged(false)
            assertTrue(held.isEmpty()); assertFalse(toggle.isSelected); assertFalse(accept.isPressed)
            // An UP remaining from a pre-lock gesture must not turn the toggle on again.
            touch(toggle, MotionEvent.ACTION_DOWN); controls.releaseAll(); touch(toggle, MotionEvent.ACTION_UP)
            assertTrue(held.isEmpty())
        } }
    }
    @Test fun cancelledToggleGestureNeverLatchesAndVisibilityChangeReleasesAnExistingLatch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val store = WolfGameOptions(activity, "cancel-toggle-${System.nanoTime()}")
            store.setAction("shift", WolfControlAction(listOf(XKeycode.KEY_SHIFT_L), WolfControlActionMode.TOGGLE))
            val held = mutableSetOf<XKeycode>()
            val controls = WolfTouchControls(activity, { key, down -> if (down) held.add(key) else held.remove(key) }, {}, store)
            install(activity, controls)
            val toggle = button(controls, "Shift")
            touch(toggle, MotionEvent.ACTION_DOWN); touch(toggle, MotionEvent.ACTION_CANCEL)
            assertTrue(held.isEmpty()); assertFalse(toggle.isSelected)
            tap(toggle); assertTrue(held.isNotEmpty())
            controls.visibility = View.GONE
            assertTrue(held.isEmpty()); assertFalse(toggle.isSelected)
        } }
    }
    @Test fun sequenceProducesRealKeyTransitionsAndFinishesReleased() {
        val events = mutableListOf<Pair<XKeycode, Boolean>>()
        lateinit var controls: WolfTouchControls
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val store = WolfGameOptions(activity, "sequence-${System.nanoTime()}")
                store.setAction("accept", WolfControlAction(listOf(XKeycode.KEY_ENTER), WolfControlActionMode.MACRO,
                    listOf(WolfMacroStep(listOf(XKeycode.KEY_ENTER, XKeycode.KEY_CTRL_L), 40), WolfMacroStep(emptyList(), 30), WolfMacroStep(listOf(XKeycode.KEY_Z), 40))))
                controls = WolfTouchControls(activity, { key, down -> events += key to down }, {}, store)
                install(activity, controls); tap(button(controls, "Valider"))
                assertEquals(listOf(XKeycode.KEY_CTRL_L to true, XKeycode.KEY_ENTER to true), events)
            }
            SystemClock.sleep(300)
            scenario.onActivity {
                assertEquals(listOf(XKeycode.KEY_CTRL_L to true, XKeycode.KEY_ENTER to true, XKeycode.KEY_ENTER to false,
                    XKeycode.KEY_CTRL_L to false, XKeycode.KEY_Z to true, XKeycode.KEY_Z to false), events)
                assertFalse(button(controls, "Valider").isSelected)
            }
        }
    }
    @Test fun pauseDuringSequenceCancelsQueuedStepsRatherThanRepressingKeysLater() {
        val events = mutableListOf<Pair<XKeycode, Boolean>>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val store = WolfGameOptions(activity, "sequence-cancel-${System.nanoTime()}")
                store.setAction("accept", WolfControlAction(listOf(XKeycode.KEY_ENTER), WolfControlActionMode.MACRO,
                    listOf(WolfMacroStep(listOf(XKeycode.KEY_ENTER), 60), WolfMacroStep(listOf(XKeycode.KEY_Z), 60))))
                val controls = WolfTouchControls(activity, { key, down -> events += key to down }, {}, store)
                install(activity, controls); tap(button(controls, "Valider")); controls.releaseAll()
                assertEquals(listOf(XKeycode.KEY_ENTER to true, XKeycode.KEY_ENTER to false), events)
            }
            SystemClock.sleep(250)
            scenario.onActivity { assertEquals(listOf(XKeycode.KEY_ENTER to true, XKeycode.KEY_ENTER to false), events) }
        }
    }
    @Test fun importedPresetReleasesHeldInputAndInvalidImportPreservesCurrentState() {
        val name = "Portable ${System.nanoTime()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val store = WolfGameOptions(activity, "import-controls-${System.nanoTime()}")
            val held = mutableSetOf<XKeycode>()
            val controls = WolfTouchControls(activity, { key, down -> if (down) held.add(key) else held.remove(key) }, {}, store)
            install(activity, controls)
            touch(button(controls, "Valider"), MotionEvent.ACTION_DOWN)
            val before = store.controlLayout().toString()
            try { controls.importPreset("{\"format\":\"other\"}"); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(before, store.controlLayout().toString()); assertEquals(setOf(XKeycode.KEY_ENTER), held)
            try {
                val layout = store.controlLayout().put("action_accept", WolfControlAction.encode(WolfControlAction(listOf(XKeycode.KEY_Z), WolfControlActionMode.TOGGLE)))
                assertEquals(name, controls.importPreset(WolfControlPresetCodec.encode(name, layout)))
                assertTrue(held.isEmpty()); assertEquals(WolfControlActionMode.TOGGLE, store.action("accept", XKeycode.KEY_ENTER).mode)
                tap(button(controls, "Valider")); assertEquals(setOf(XKeycode.KEY_Z), held)
                controls.setEditing(true); assertTrue(held.isEmpty())
            } finally { WolfControlPresets(activity).delete(name) }
        } }
    }
    private fun install(activity: MainActivity, controls: WolfTouchControls) {
        activity.setContentView(controls)
        controls.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        controls.layout(0, 0, 1200, 1800)
    }
    private fun button(root: View, name: String): Button = descendants(root).filterIsInstance<Button>().first { it.contentDescription.toString().startsWith(name) }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun touch(view: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, 10f, 10f, 0).let { view.dispatchTouchEvent(it); it.recycle() }
    }
    private fun tap(view: View) { touch(view, MotionEvent.ACTION_DOWN); touch(view, MotionEvent.ACTION_UP) }
}
