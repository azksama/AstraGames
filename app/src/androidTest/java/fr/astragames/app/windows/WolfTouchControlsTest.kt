package fr.astragames.app.windows

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import fr.astragames.app.MainActivity
import com.winlator.xserver.XKeycode
import org.junit.Assert.*
import org.junit.Test

class WolfTouchControlsTest {
    @Test fun draggingPersistsAndCancelledDragRestoresPosition() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val store = WolfGameOptions(activity, "drag-controls-${System.nanoTime()}")
            val controls = WolfTouchControls(activity, { _, _ -> }, {}, store)
            controls.setPadding(45, 80, 35, 110)
            activity.setContentView(controls)
            controls.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
            controls.layout(0, 0, 1000, 1800)
            controls.setEditing(true)
            val left = buttons(controls).first { it.contentDescription == "Gauche" }
            val beforeX = left.x
            assertTrue(left.x >= 45 && left.y >= 80)
            fun drag(action: Int, x: Float, y: Float) {
                MotionEvent.obtain(0, 0, action, x, y, 0).let { left.dispatchTouchEvent(it); it.recycle() }
            }
            drag(MotionEvent.ACTION_DOWN, 10f, 10f)
            drag(MotionEvent.ACTION_MOVE, 200f, -150f)
            assertTrue(left.x > beforeX)
            drag(MotionEvent.ACTION_CANCEL, 200f, -150f)
            assertEquals(beforeX, left.x, .01f)
            assertNull(store.position("left", false))
            drag(MotionEvent.ACTION_DOWN, 10f, 10f)
            drag(MotionEvent.ACTION_MOVE, 200f, -150f)
            drag(MotionEvent.ACTION_UP, 200f, -150f)
            val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val saved = requireNotNull(store.position("left", landscape))
            controls.reload()
            controls.layout(0, 0, 1000, 1800)
            assertEquals(45 + saved.first * (1000 - 45 - 35 - left.width), left.x, .1f)
            assertTrue(left.y + left.height <= 1800 - 110)
        } }
    }
    @Test fun sharedRemappingDoesNotReleaseAnotherHeldButton() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val store = WolfGameOptions(activity, "shared-controls-${System.nanoTime()}")
            store.bind("left", XKeycode.KEY_Z)
            store.bind("accept", XKeycode.KEY_Z)
            val held = mutableSetOf<XKeycode>()
            val controls = WolfTouchControls(activity, { code, down -> if (down) held.add(code) else held.remove(code) }, {}, store)
            activity.setContentView(controls)
            val left = buttons(controls).first { it.contentDescription.toString().startsWith("Gauche") }
            val accept = buttons(controls).first { it.contentDescription.toString().startsWith("Valider") }
            touch(left, MotionEvent.ACTION_DOWN)
            touch(accept, MotionEvent.ACTION_DOWN)
            touch(left, MotionEvent.ACTION_UP)
            assertEquals(setOf(XKeycode.KEY_Z), held)
            controls.setEditing(true)
            assertTrue(held.isEmpty())
            assertTrue(controls.editing)
            buttons(controls).first { it.text == "Terminer" }.performClick()
            assertFalse(controls.editing)
        } }
    }
    @Test fun movementAndActionCanBeHeldTogetherAndCancelReleasesThem() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val held = mutableSetOf<XKeycode>()
                val controls = WolfTouchControls(activity, { code, down -> if (down) held.add(code) else held.remove(code) }, {})
                activity.setContentView(controls)
                val left = buttons(controls).first { it.contentDescription == "Gauche" }
                val accept = buttons(controls).first { it.contentDescription == "Valider" }
                touch(left, MotionEvent.ACTION_DOWN)
                touch(accept, MotionEvent.ACTION_DOWN)
                assertEquals(setOf(XKeycode.KEY_LEFT, XKeycode.KEY_ENTER), held)
                touch(left, MotionEvent.ACTION_CANCEL)
                assertEquals(setOf(XKeycode.KEY_ENTER), held)
                controls.releaseAll()
                assertTrue(held.isEmpty())
                assertFalse(accept.isPressed)
                assertTrue(buttons(controls).none { it.text == "Quitter" })
                assertTrue(buttons(controls).any { it.text == "Menu" })
            }
        }
    }
    private fun touch(view: View, action: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, 10f, 10f, 0).let { view.dispatchTouchEvent(it); it.recycle() }
    }
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
}
