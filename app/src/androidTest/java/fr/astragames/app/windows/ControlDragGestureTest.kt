package fr.astragames.app.windows

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import fr.astragames.app.MainActivity
import org.junit.Assert.*
import org.junit.Test

class ControlDragGestureTest {
    @Test fun movingASecondFingerNeverMovesOrRecommitsTheControl() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val view = View(activity)
            parent.addView(view, FrameLayout.LayoutParams(100, 100))
            activity.setContentView(parent)
            parent.layout(0, 0, 1000, 1000); view.layout(100, 100, 200, 200)
            var saves = 0
            val drag = ControlDragGesture(view) { saves++ }
            fun send(action: Int, vararg points: Triple<Int, Float, Float>) {
                val properties = points.map { MotionEvent.PointerProperties().apply { id = it.first; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                val coords = points.map { MotionEvent.PointerCoords().apply { x = it.second; y = it.third; pressure = 1f; size = 1f } }.toTypedArray()
                val event = MotionEvent.obtain(0, 0, action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { drag.touch(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, Triple(4, 20f, 20f))
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), Triple(4, 20f, 20f), Triple(8, 500f, 500f))
            // Pointer indices can change; only ID 4 owns the gesture.
            send(MotionEvent.ACTION_MOVE, Triple(8, 800f, 800f), Triple(4, 120f, 70f))
            assertEquals(200f, view.x, .01f); assertEquals(150f, view.y, .01f)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl 8), Triple(8, 800f, 800f), Triple(4, 120f, 70f))
            assertEquals(1, saves)
            send(MotionEvent.ACTION_MOVE, Triple(8, 50f, 50f))
            send(MotionEvent.ACTION_UP, Triple(8, 50f, 50f))
            assertEquals(1, saves); assertEquals(200f, view.x, .01f); assertFalse(view.isPressed)
        } }
    }
}
