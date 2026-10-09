package fr.astragames.app.windows

import android.view.MotionEvent
import com.winlator.xserver.XKeycode
import kotlin.math.abs
import kotlin.math.hypot

internal data class WolfViewport(val left: Int, val top: Int, val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int,
    val visibleWidth: Int = Int.MAX_VALUE, val visibleHeight: Int = Int.MAX_VALUE) {
    fun point(x: Float, y: Float): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || screenWidth <= 0 || screenHeight <= 0 || !x.isFinite() || !y.isFinite() ||
            x < maxOf(0, left) || y < maxOf(0, top) || x >= minOf(visibleWidth, left + width) || y >= minOf(visibleHeight, top + height)) return null
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
    private val directions: (Set<XKeycode>) -> Unit, private val focus: () -> Unit,
    private val back: () -> Unit = {}, private val zoom: (Float, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    private val slop: Float = 12f) {
    private var pointerId = -1
    private var pointerDown = false
    private var multi = false
    private var tapCandidate = false
    private var started = 0L
    private var ids = intArrayOf()
    private var origins = floatArrayOf()
    private var span = 0f
    private var centerX = 0f
    private var centerY = 0f
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hold = Runnable { if (pointerId >= 0 && !multi && !pointerDown) { pointerDown = true; button(true) } }

    private fun releasePointer() {
        handler.removeCallbacks(hold)
        if (pointerDown) button(false)
        pointerDown = false; pointerId = -1; directions(emptySet())
    }
    fun cancel() { releasePointer(); multi = false; tapCandidate = false; ids = intArrayOf() }
    fun touch(event: MotionEvent, viewport: WolfViewport, directional: Boolean, zoomEnabled: Boolean = false): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { cancel(); return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                if (viewport.point(event.x, event.y) == null) return true
                started = event.eventTime
                pointerId = event.getPointerId(0); focus()
                if (!directional) handler.postDelayed(hold, 150)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val active = pointerId >= 0
                releasePointer(); multi = true
                if (event.pointerCount == 2 && active) {
                    ids = intArrayOf(event.getPointerId(0), event.getPointerId(1))
                    origins = floatArrayOf(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                    tapCandidate = (0..1).all { viewport.point(event.getX(it), event.getY(it)) != null }
                    span = hypot(origins[0] - origins[2], origins[1] - origins[3])
                    centerX = (origins[0] + origins[2]) / 2; centerY = (origins[1] + origins[3]) / 2
                } else tapCandidate = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (multi) {
                    checkMovement(event)
                    val tapped = tapCandidate && event.eventTime - started in 0..350
                    cancel()
                    if (tapped) { focus(); back() }
                    return true
                }
                if (!directional && pointerId >= 0) viewport.point(event.x, event.y)?.let {
                    move(it.first, it.second)
                    if (!pointerDown) { pointerDown = true; button(true) }
                }
                cancel(); return true
            }
            MotionEvent.ACTION_POINTER_UP -> { if (multi) { checkMovement(event); return true }; cancel(); return true }
        }
        if (multi) {
            checkMovement(event)
            if (ids.size == 2 && event.pointerCount == 2) {
                val first = event.findPointerIndex(ids[0]); val second = event.findPointerIndex(ids[1])
                if (first >= 0 && second >= 0) {
                    val nextSpan = hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))
                    val x = (event.getX(first) + event.getX(second)) / 2; val y = (event.getY(first) + event.getY(second)) / 2
                    if (zoomEnabled && !tapCandidate && span > 1 && nextSpan > 1) zoom(nextSpan / span, x, y, x - centerX, y - centerY)
                    span = nextSpan; centerX = x; centerY = y
                }
            }
            return true
        }
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return true
        val point = viewport.point(event.getX(index), event.getY(index)) ?: run { cancel(); return true }
        if (directional) directions(wolfTouchDirection(point.first, point.second, viewport.screenWidth, viewport.screenHeight))
        else {
            move(point.first, point.second)
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !pointerDown) {
                handler.removeCallbacks(hold); pointerDown = true; button(true)
            }
        }
        return true
    }

    private fun checkMovement(event: MotionEvent) {
        for (i in ids.indices) {
            val index = event.findPointerIndex(ids[i]); if (index < 0) continue
            if (hypot(event.getX(index) - origins[i * 2], event.getY(index) - origins[i * 2 + 1]) > slop) tapCandidate = false
        }
    }
}
