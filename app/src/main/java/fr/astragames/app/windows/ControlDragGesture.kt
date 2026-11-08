package fr.astragames.app.windows

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.hypot

/** Reposition one control with its original pointer; cancellation restores the last saved position. */
internal class ControlDragGesture(private val view: View, private val save: () -> Unit) {
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var originX = 0f
    private var originY = 0f
    private var dragged = false
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop

    fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                pointer = event.getPointerId(0)
                downX = event.rawX; downY = event.rawY
                originX = view.x; originY = view.y; dragged = false
                view.isPressed = true
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0) return true
                // getRawX(index) requires API 29. The common window offset also works on API 26.
                val dx = event.getX(index) + event.rawX - event.x - downX
                val dy = event.getY(index) + event.rawY - event.y - downY
                if (hypot(dx, dy) > slop) dragged = true
                val parent = view.parent as? ViewGroup
                if (dragged && parent != null) {
                    view.x = (originX + dx).coerceIn(parent.paddingLeft.toFloat(),
                        (parent.width - parent.paddingRight - view.width).coerceAtLeast(parent.paddingLeft).toFloat())
                    view.y = (originY + dy).coerceIn(parent.paddingTop.toFloat(),
                        (parent.height - parent.paddingBottom - view.height).coerceAtLeast(parent.paddingTop).toFloat())
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) finish()
            MotionEvent.ACTION_UP -> if (pointer != MotionEvent.INVALID_POINTER_ID) finish()
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun finish() {
        pointer = MotionEvent.INVALID_POINTER_ID
        view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
        if (dragged) save() else view.performClick()
    }

    fun cancel() {
        if (pointer == MotionEvent.INVALID_POINTER_ID) return
        pointer = MotionEvent.INVALID_POINTER_ID
        view.x = originX; view.y = originY; view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }
}
