package fr.astragames.app.windows

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.PopupMenu
import com.winlator.xserver.XKeycode

/** Two thumb zones; the transparent middle passes pointer events through to the game. */
internal class WolfTouchControls(context: Context, private val key: (XKeycode, Boolean) -> Unit,
                                 private val quit: () -> Unit) : FrameLayout(context) {
    private val held = mutableSetOf<XKeycode>()
    private val pad = FrameLayout(context)
    private val actions = FrameLayout(context)
    private var hidden = false
    init {
        isMotionEventSplittingEnabled = true
        val directions = listOf(
            Triple("Haut", XKeycode.KEY_UP, 1 to 0), Triple("Gauche", XKeycode.KEY_LEFT, 0 to 1),
            Triple("Droite", XKeycode.KEY_RIGHT, 2 to 1), Triple("Bas", XKeycode.KEY_DOWN, 1 to 2))
        directions.forEachIndexed { index, (label, code, position) ->
            pad.addView(control("", label, code).apply {
                foreground = ArrowDrawable(floatArrayOf(0f, -90f, 90f, 180f)[index])
            }, LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(position.first * 52); topMargin = dp(position.second * 52) })
        }
        addView(pad, LayoutParams(dp(156), dp(156), Gravity.BOTTOM or Gravity.LEFT).apply {
            leftMargin = dp(12); bottomMargin = dp(12)
        })
        actions.addView(control("Valider", "Valider", XKeycode.KEY_ENTER, true),
            LayoutParams(dp(76), dp(76), Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        actions.addView(control("Retour", "Retour", XKeycode.KEY_ESC),
            LayoutParams(dp(64), dp(64), Gravity.LEFT or Gravity.BOTTOM))
        actions.addView(control("Shift", "Maintenir Shift", XKeycode.KEY_SHIFT_L),
            LayoutParams(dp(64), dp(48), Gravity.LEFT or Gravity.TOP))
        addView(actions, LayoutParams(dp(148), dp(148), Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = dp(12); bottomMargin = dp(16)
        })
        addView(button("Menu", false).apply {
            contentDescription = "Menu du jeu"
            setOnClickListener { anchor ->
                releaseAll()
                PopupMenu(context, anchor).apply {
                    menu.add(if (hidden) "Afficher les commandes" else "Masquer les commandes").setOnMenuItemClickListener {
                        hidden = !hidden
                        pad.visibility = if (hidden) View.GONE else View.VISIBLE
                        actions.visibility = if (hidden) View.GONE else View.VISIBLE
                        true
                    }
                    menu.add("Quitter").setOnMenuItemClickListener { quit(); true }
                    show()
                }
            }
        }, LayoutParams(dp(76), dp(48), Gravity.TOP or Gravity.RIGHT).apply { topMargin = dp(8); rightMargin = dp(12) })
    }
    private fun control(label: String, description: String, code: XKeycode, primary: Boolean = false) = button(label, primary).apply {
        contentDescription = description
        var touchClick = false
        setOnClickListener { if (!touchClick) { press(code); postDelayed({ release(code) }, 150) } }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { press(code); view.isPressed = true }
                MotionEvent.ACTION_MOVE -> if (event.x !in 0f..view.width.toFloat() || event.y !in 0f..view.height.toFloat()) {
                    release(code); view.isPressed = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    release(code); view.isPressed = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) { touchClick = true; view.performClick(); touchClick = false }
                }
            }
            true
        }
    }
    private fun button(label: String, primary: Boolean) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 13f
        setPadding(0, 0, 0, 0); minWidth = 0; minHeight = 0
        setTextColor(if (primary) Color.rgb(26, 17, 42) else Color.rgb(239, 231, 255))
        background = RippleDrawable(ColorStateList.valueOf(0x557B61AA), GradientDrawable().apply {
            setColor(if (primary) Color.rgb(201, 183, 255) else Color.argb(235, 42, 34, 56))
            cornerRadius = dp(if (primary) 38 else 16).toFloat()
        }, null)
        stateListAnimator = null
    }
    private fun press(code: XKeycode) { if (held.add(code)) key(code, true) }
    private fun release(code: XKeycode) { if (held.remove(code)) key(code, false) }
    fun releaseAll() { held.toList().forEach(::release); clearPressed(this) }
    private fun clearPressed(group: android.view.ViewGroup) {
        for (i in 0 until group.childCount) { val child = group.getChildAt(i); child.isPressed = false; if (child is android.view.ViewGroup) clearPressed(child) }
    }
    override fun onDetachedFromWindow() { releaseAll(); super.onDetachedFromWindow() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class ArrowDrawable(private val angle: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 231, 255); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun draw(canvas: Canvas) {
            val unit = bounds.width() / 52f
            paint.strokeWidth = 2.5f * unit
            canvas.save(); canvas.translate(bounds.exactCenterX(), bounds.exactCenterY()); canvas.rotate(angle)
            canvas.drawPath(Path().apply { moveTo(-7 * unit, 3 * unit); lineTo(0f, -4 * unit); lineTo(7 * unit, 3 * unit) }, paint)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
