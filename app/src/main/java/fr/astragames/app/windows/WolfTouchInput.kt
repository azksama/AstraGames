package fr.astragames.app.windows

import android.view.MotionEvent
import com.winlator.xserver.XKeycode
import kotlin.math.abs

internal data class WolfViewport(val left: Int, val top: Int, val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int) {
    fun point(x: Float, y: Float): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || screenWidth <= 0 || screenHeight <= 0 || !x.isFinite() || !y.isFinite() ||
            x < left || y < top || x >= left + width || y >= top + height) return null
        return ((x - left) * screenWidth / width).toInt().coerceIn(0, screenWidth - 1) to
            ((y - top) * screenHeight / height).toInt().coerceIn(0, screenHeight - 1)
    }
}

/** Directional fallback has no knowledge of the game's map or pathfinding. */
internal fun wolfTouchDirection(x: Int, y: Int, width: Int, height: Int): Set<XKeycode> {
    val dx = (x - width / 2f) / (width / 2f)
    val dy = (y - height / 2f) / (height / 2f)
    if (maxOf(abs(dx), abs(dy)) < .12f) return emptySet()
    return setOf(if (abs(dx) > abs(dy)) { if (dx < 0) XKeycode.KEY_LEFT else XKeycode.KEY_RIGHT }
        else if (dy < 0) XKeycode.KEY_UP else XKeycode.KEY_DOWN)
}

internal class WolfTouchInput(private val move: (Int, Int) -> Unit, private val button: (Boolean) -> Unit,
    private val directions: (Set<XKeycode>) -> Unit, private val focus: () -> Unit) {
    private var pointerId = -1
    private var pointerDown = false
    fun cancel() { if (pointerDown) button(false); pointerDown = false; pointerId = -1; directions(emptySet()) }
    fun touch(event: MotionEvent, viewport: WolfViewport, directional: Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                if (viewport.point(event.x, event.y) == null) return true
                pointerId = event.getPointerId(0); focus()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { cancel(); return true }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) { cancel(); return true }
            MotionEvent.ACTION_POINTER_DOWN -> return true
        }
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return true
        val point = viewport.point(event.getX(index), event.getY(index)) ?: run { cancel(); return true }
        if (directional) directions(wolfTouchDirection(point.first, point.second, viewport.screenWidth, viewport.screenHeight))
        else {
            move(point.first, point.second)
            if (!pointerDown) { pointerDown = true; button(true) }
        }
        return true
    }
}
