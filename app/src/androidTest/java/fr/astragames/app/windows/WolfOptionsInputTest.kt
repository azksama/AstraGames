package fr.astragames.app.windows

import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.xserver.XKeycode
import org.junit.Assert.*
import org.junit.Test

class WolfOptionsInputTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun preferencesRemainPerGameAndResetOnlyControls() {
        val id = "options-test-${System.nanoTime()}"
        val store = WolfGameOptions(context, id)
        store.save(WolfOptions(performance = WolfPerformance.FAST, resolution = "1920x1080", opacity = 31, size = 140))
        store.bind("accept", XKeycode.KEY_Z)
        store.position("accept", false, .25f, .8f)
        store.position("accept", true, .6f, .3f)
        val restored = WolfGameOptions(context, id)
        assertEquals(31, restored.read().opacity)
        assertEquals(XKeycode.KEY_Z, restored.binding("accept", XKeycode.KEY_ENTER))
        assertEquals(.25f to .8f, restored.position("accept", false))
        assertEquals(.6f to .3f, restored.position("accept", true))
        assertEquals(WolfOptions(), WolfGameOptions(context, "$id-other").read())
        restored.save(restored.read().copy(smooth = false))
        assertEquals(.6f to .3f, restored.position("accept", true))
        restored.resetControls()
        assertNull(restored.position("accept", false))
        assertEquals(XKeycode.KEY_ENTER, restored.binding("accept", XKeycode.KEY_ENTER))
        assertEquals(75, restored.read().opacity)
        assertEquals(WolfPerformance.FAST, restored.read().performance)
        assertEquals("1920x1080", restored.read().resolution)
        assertFalse(restored.read().smooth)
    }

    @Test fun invalidPreferencesAreBounded() {
        val store = WolfGameOptions(context, "invalid-options-${System.nanoTime()}")
        store.save(WolfOptions(resolution = "broken", opacity = -20, size = 500, maxFps = 999))
        assertEquals("1280x960", store.read().resolution)
        assertEquals(15, store.read().opacity)
        assertEquals(150, store.read().size)
        assertEquals(60, store.read().maxFps)
        store.position("left", false, -10f, 10f)
        assertEquals(0f to 1f, store.position("left", false))
        store.position("left", false, Float.NaN, 0f)
        assertEquals(0f to 1f, store.position("left", false))
    }

    @Test fun clicksMapToGameAndCancelOutsideViewport() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val clicks = mutableListOf<Boolean>()
        var focusCount = 0
        val input = WolfTouchInput({ x, y -> moves += x to y }, { clicks += it }, {}, { focusCount++ })
        val viewport = WolfViewport(100, 200, 800, 600, 1280, 960)
        fun event(action: Int, x: Float, y: Float) {
            MotionEvent.obtain(0, 0, action, x, y, 0).let { input.touch(it, viewport, false); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 500f, 500f)
        assertEquals(640 to 480, moves.last())
        event(MotionEvent.ACTION_MOVE, 700f, 600f)
        assertEquals(960 to 640, moves.last())
        event(MotionEvent.ACTION_MOVE, 50f, 500f)
        event(MotionEvent.ACTION_UP, 50f, 500f)
        assertEquals(listOf(true, false), clicks)
        event(MotionEvent.ACTION_DOWN, 50f, 500f)
        assertEquals(1, focusCount)
        assertNull(viewport.point(Float.NaN, 500f))
        assertNull(viewport.point(900f, 500f))
    }

    @Test fun directionHoldReleasesOnCancelAndCenter() {
        var keys = emptySet<XKeycode>()
        val input = WolfTouchInput({ _, _ -> fail("Directional touch must not click") }, { fail("Unexpected mouse button") }, { keys = it }, {})
        val viewport = WolfViewport(0, 0, 800, 600, 1280, 960)
        fun event(action: Int, x: Float, y: Float) {
            MotionEvent.obtain(0, 0, action, x, y, 0).let { input.touch(it, viewport, true); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 790f, 300f)
        assertEquals(setOf(XKeycode.KEY_RIGHT), keys)
        event(MotionEvent.ACTION_MOVE, 400f, 5f)
        assertEquals(setOf(XKeycode.KEY_UP), keys)
        event(MotionEvent.ACTION_MOVE, 400f, 300f)
        assertTrue(keys.isEmpty())
        event(MotionEvent.ACTION_MOVE, 5f, 300f)
        assertEquals(setOf(XKeycode.KEY_LEFT), keys)
        event(MotionEvent.ACTION_CANCEL, 5f, 300f)
        assertTrue(keys.isEmpty())
    }
}
