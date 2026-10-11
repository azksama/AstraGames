package fr.astragames.app.windows

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Owns only the first pointer. Lifting it never transfers movement to another finger. */
internal class WolfJoystickView(context: Context, private val moved: (Set<String>) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var thumbX = 0f
    private var thumbY = 0f
    private var directions = emptySet<String>()
    var editing = false
        set(value) { reset(); field = value; invalidate() }

    init { contentDescription = "Joystick de déplacement"; isFocusable = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val radius = min(cx, cy) - 2f
        paint.style = Paint.Style.FILL; paint.color = 0xED2A2238.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
        paint.color = if (editing) 0xFFC9B7FF.toInt() else 0x665F5374
        canvas.drawCircle(cx, cy, radius - paint.strokeWidth, paint)
        paint.strokeWidth = resources.displayMetrics.density * 2f; paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0x99EFE7FF.toInt()
        for (rotation in listOf(0f, 90f, 180f, 270f)) {
            canvas.save(); canvas.rotate(rotation, cx, cy)
            val y = cy - radius * .75f; val step = radius * .055f
            canvas.drawLine(cx - step, y + step, cx, y, paint)
            canvas.drawLine(cx, y, cx + step, y + step, paint); canvas.restore()
        }
        paint.style = Paint.Style.FILL
        paint.color = if (activePointer != MotionEvent.INVALID_POINTER_ID) 0xFFC9B7FF.toInt() else 0xFF9280B6.toInt()
        canvas.drawCircle(cx + thumbX * radius * .5f, cy + thumbY * radius * .5f, radius * .32f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset(); activePointer = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true); update(event, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index < 0) reset() else update(event, index)
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == activePointer) reset()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                reset(); parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun update(event: MotionEvent, index: Int) {
        val radius = (min(width, height) / 2f).coerceAtLeast(1f)
        val x = (event.getX(index) - width / 2f) / radius
        val y = (event.getY(index) - height / 2f) / radius
        val distance = hypot(x, y).coerceAtLeast(1f)
        thumbX = x / distance; thumbY = y / distance
        val next = WolfJoystickDirection.directions(thumbX, thumbY)
        if (next != directions) { directions = next; moved(next) }
        invalidate()
    }

    fun reset() {
        activePointer = MotionEvent.INVALID_POINTER_ID; thumbX = 0f; thumbY = 0f
        if (directions.isNotEmpty()) { directions = emptySet(); moved(emptySet()) }
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { if (!hasWindowFocus) reset(); super.onWindowFocusChanged(hasWindowFocus) }
    override fun onDetachedFromWindow() { reset(); super.onDetachedFromWindow() }
}
