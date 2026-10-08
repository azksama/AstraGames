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
