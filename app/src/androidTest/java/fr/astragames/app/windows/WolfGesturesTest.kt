package fr.astragames.app.windows

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class WolfGesturesTest {
    private val viewport = WolfViewport(0, 0, 800, 600, 1280, 960, 800, 600)
    private fun send(input: WolfTouchInput, action: Int, time: Long, vararg positions: Pair<Float, Float>, zoom: Boolean = false) {
        val properties = positions.indices.map { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = positions.map { (px, py) -> MotionEvent.PointerCoords().apply { x = px; y = py; pressure = 1f; size = 1f } }.toTypedArray()
        MotionEvent.obtain(100, time, action, positions.size, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).let {
            input.touch(it, viewport, false, zoom); it.recycle()
        }
    }
    @Test fun quickTwoFingerTapSendsReturnOnceWithoutMouseClick() {
        var returns = 0; val clicks = mutableListOf<Boolean>()
        val input = WolfTouchInput({ _, _ -> }, { clicks += it }, {}, {}, { returns++ })
        send(input, MotionEvent.ACTION_DOWN, 100, 300f to 300f)
        send(input, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 120, 300f to 300f, 500f to 300f)
        send(input, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 170, 300f to 300f, 500f to 300f)
        send(input, MotionEvent.ACTION_UP, 180, 300f to 300f)
        assertEquals(1, returns); assertTrue(clicks.isEmpty())
    }
    @Test fun pinchAndPanRequireTheOptionAndNeverSendReturn() {
        for (enabled in listOf(false, true)) {
            var returns = 0; val scales = mutableListOf<Float>(); val clicks = mutableListOf<Boolean>()
            val input = WolfTouchInput({ _, _ -> }, { clicks += it }, {}, {}, { returns++ }, { scale, _, _, _, _ -> scales += scale })
            send(input, MotionEvent.ACTION_DOWN, 100, 300f to 300f, zoom = enabled)
            send(input, MotionEvent.ACTION_POINTER_DOWN or 256, 120, 300f to 300f, 500f to 300f, zoom = enabled)
            send(input, MotionEvent.ACTION_MOVE, 150, 250f to 280f, 550f to 280f, zoom = enabled)
            send(input, MotionEvent.ACTION_POINTER_UP or 256, 200, 250f to 280f, 550f to 280f, zoom = enabled)
            send(input, MotionEvent.ACTION_UP, 220, 250f to 280f, zoom = enabled)
            assertEquals(0, returns); assertTrue(clicks.isEmpty())
            if (enabled) assertEquals(1.5f, scales.single(), .001f) else assertTrue(scales.isEmpty())
        }
    }
    @Test fun cancelledLongOrThreeFingerTouchesDoNotTriggerReturn() {
        var returns = 0
        val input = WolfTouchInput({ _, _ -> }, {}, {}, {}, { returns++ })
        for (third in listOf(false, true)) {
            send(input, MotionEvent.ACTION_DOWN, 100, 300f to 300f)
            send(input, MotionEvent.ACTION_POINTER_DOWN or 256, 120, 300f to 300f, 500f to 300f)
            if (third) send(input, MotionEvent.ACTION_POINTER_DOWN or 512, 130, 300f to 300f, 500f to 300f, 600f to 300f)
            send(input, MotionEvent.ACTION_UP, 900, 300f to 300f)
        }
        send(input, MotionEvent.ACTION_DOWN, 100, 300f to 300f)
        send(input, MotionEvent.ACTION_POINTER_DOWN or 256, 120, 300f to 300f, 500f to 300f)
        send(input, MotionEvent.ACTION_CANCEL, 150, 300f to 300f, 500f to 300f)
        assertEquals(0, returns)
    }
    @Test fun zoomPreferencePersistsAndDoesNotChangeOtherGames() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val id = "zoom-${System.nanoTime()}"; val store = WolfGameOptions(context, id)
        assertFalse(store.read().pinchZoom)
        store.save(store.read().copy(pinchZoom = true))
        assertTrue(WolfGameOptions(context, id).read().pinchZoom)
        assertFalse(WolfGameOptions(context, "$id-other").read().pinchZoom)
        store.resetControls(); assertTrue(store.read().pinchZoom)
    }
}
