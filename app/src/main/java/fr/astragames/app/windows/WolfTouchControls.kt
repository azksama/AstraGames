package fr.astragames.app.windows

import android.app.AlertDialog
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
import kotlin.math.abs

/** Independently movable controls. Empty space passes touch through to the game. */
internal class WolfTouchControls(context: Context, private val key: (XKeycode, Boolean) -> Unit,
    private val quit: () -> Unit, private val store: WolfGameOptions? = null,
    private val settings: () -> Unit = {}, private val clearInput: () -> Unit = {}) : FrameLayout(context) {
    private data class Control(val id: String, val label: String, val code: XKeycode, val size: Int, val arrow: Float? = null)
    private val definitions = listOf(
        Control("up", "Haut", XKeycode.KEY_UP, 52, 0f), Control("left", "Gauche", XKeycode.KEY_LEFT, 52, -90f),
        Control("right", "Droite", XKeycode.KEY_RIGHT, 52, 90f), Control("down", "Bas", XKeycode.KEY_DOWN, 52, 180f),
        Control("accept", "Valider", XKeycode.KEY_ENTER, 76), Control("back", "Retour", XKeycode.KEY_ESC, 64),
        Control("shift", "Shift", XKeycode.KEY_SHIFT_L, 56))
    private val buttons = mutableMapOf<String, Button>()
    private val held = mutableMapOf<String, XKeycode>()
    private var hidden = false
    var editing = false
        private set
    private var options = store?.read() ?: WolfOptions()
    private val menuButton: Button
    init {
        isMotionEventSplittingEnabled = true
        definitions.forEach { def ->
            val button = button(if (def.arrow == null) def.label else "", def.id == "accept").apply {
                contentDescription = def.label
                if (def.arrow != null) foreground = ArrowDrawable(def.arrow)
            }
            buttons[def.id] = button
            var touchClick = false
            var downX = 0f; var downY = 0f; var originX = 0f; var originY = 0f; var dragged = false
            button.setOnClickListener {
                if (editing) remap(def)
                else if (!touchClick) { press(def); button.postDelayed({ release(def.id) }, 120) }
            }
            button.setOnTouchListener { view, event ->
                if (editing) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = view.x; originY = view.y; dragged = false }
                        MotionEvent.ACTION_MOVE -> {
                            if (abs(event.rawX - downX) + abs(event.rawY - downY) > dp(8)) dragged = true
                            if (dragged) {
                                view.x = (originX + event.rawX - downX).coerceIn(paddingLeft.toFloat(), (width - paddingRight - view.width).coerceAtLeast(paddingLeft).toFloat())
                                view.y = (originY + event.rawY - downY).coerceIn(paddingTop.toFloat(), (height - paddingBottom - view.height).coerceAtLeast(paddingTop).toFloat())
                            }
                        }
                        MotionEvent.ACTION_UP -> if (dragged) {
                            store?.position(def.id, landscape(), (view.x - paddingLeft) / (width - paddingLeft - paddingRight - view.width).coerceAtLeast(1),
                                (view.y - paddingTop) / (height - paddingTop - paddingBottom - view.height).coerceAtLeast(1))
                        } else view.performClick()
                        MotionEvent.ACTION_CANCEL -> { view.x = originX; view.y = originY }
                    }
                } else when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { press(def); view.isPressed = true }
                    MotionEvent.ACTION_MOVE -> if (event.x !in 0f..view.width.toFloat() || event.y !in 0f..view.height.toFloat()) {
                        release(def.id); view.isPressed = false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        release(def.id); view.isPressed = false
                        if (event.actionMasked == MotionEvent.ACTION_UP) { touchClick = true; view.performClick(); touchClick = false }
                    }
                }
                true
            }
            addView(button, LayoutParams(dp(def.size), dp(def.size)))
        }
        menuButton = button("Menu", false).apply {
            contentDescription = "Menu du jeu"
            setOnClickListener { anchor ->
                releaseAll(); clearInput()
                if (editing) { setEditing(false); return@setOnClickListener }
                PopupMenu(context, anchor).apply {
                    menu.add("Réglages du jeu").setOnMenuItemClickListener { settings(); true }
                    menu.add(if (editing) "Terminer la disposition" else "Modifier les touches").setOnMenuItemClickListener { setEditing(!editing); true }
                    menu.add("Réinitialiser les touches").setOnMenuItemClickListener { store?.resetControls(); reload(); true }
                    menu.add(if (hidden) "Afficher les commandes" else "Masquer les commandes").setOnMenuItemClickListener {
                        hidden = !hidden; if (hidden) setEditing(false); reload(); true
                    }
                    menu.add("Quitter").setOnMenuItemClickListener { quit(); true }
                    show()
                }
            }
        }
        addView(menuButton, LayoutParams(dp(76), dp(48), Gravity.TOP or Gravity.RIGHT).apply { topMargin = dp(8); rightMargin = dp(12) })
        reload()
    }
    fun setEditing(value: Boolean) {
        releaseAll(); editing = value
        if (value) hidden = false
        menuButton.text = if (value) "Terminer" else "Menu"
        if (value) android.widget.Toast.makeText(context, "Glissez pour déplacer · touchez pour changer l’action", android.widget.Toast.LENGTH_LONG).show()
        reload()
    }
    fun reload() {
        releaseAll(); options = store?.read() ?: WolfOptions()
        definitions.forEach { def -> buttons.getValue(def.id).apply {
            val mapped = store?.binding(def.id, def.code) ?: def.code
            text = if (mapped == def.code) (if (def.arrow == null) def.label else "") else keyLabel(mapped)
            foreground = if (mapped == def.code && def.arrow != null) ArrowDrawable(def.arrow) else null
            contentDescription = if (mapped == def.code) def.label else "${def.label} : ${keyLabel(mapped)}"
            alpha = if (editing) 1f else options.opacity / 100f
            visibility = if (hidden) View.GONE else View.VISIBLE
            val size = dp(def.size * options.size / 100).coerceAtLeast(dp(48))
            layoutParams = LayoutParams(size, size)
        } }
        requestLayout()
    }
    private fun landscape() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val step = dp(52 * options.size / 100).coerceAtLeast(dp(48))
        val areaWidth = width - paddingLeft - paddingRight
        val areaHeight = height - paddingTop - paddingBottom
        for (def in definitions) {
            val view = buttons.getValue(def.id)
            val saved = store?.position(def.id, landscape())
            val default = when (def.id) {
                "up" -> (dp(12) + step).toFloat() to (areaHeight - dp(12) - step * 3).toFloat()
                "left" -> dp(12).toFloat() to (areaHeight - dp(12) - step * 2).toFloat()
                "right" -> (dp(12) + step * 2).toFloat() to (areaHeight - dp(12) - step * 2).toFloat()
                "down" -> (dp(12) + step).toFloat() to (areaHeight - dp(12) - step).toFloat()
                "accept" -> (areaWidth - dp(12) - view.width).toFloat() to (areaHeight - dp(22) - step * 2).toFloat()
                "back" -> (areaWidth - dp(20) - view.width - dp(76 * options.size / 100)).toFloat() to (areaHeight - dp(12) - view.height).toFloat()
                else -> (areaWidth - dp(20) - view.width - dp(76 * options.size / 100)).toFloat() to (areaHeight - dp(12) - step * 3).toFloat()
            }
            view.x = paddingLeft + (saved?.first?.times(areaWidth - view.width) ?: default.first).coerceIn(0f, (areaWidth - view.width).coerceAtLeast(0).toFloat())
            view.y = paddingTop + (saved?.second?.times(areaHeight - view.height) ?: default.second).coerceIn(0f, (areaHeight - view.height).coerceAtLeast(0).toFloat())
        }
        if (indexOfChild(menuButton) != childCount - 1) menuButton.bringToFront()
    }
    private fun remap(def: Control) {
        val choices = listOf(XKeycode.KEY_ENTER, XKeycode.KEY_ESC, XKeycode.KEY_SPACE, XKeycode.KEY_SHIFT_L, XKeycode.KEY_CTRL_L,
            XKeycode.KEY_UP, XKeycode.KEY_DOWN, XKeycode.KEY_LEFT, XKeycode.KEY_RIGHT, XKeycode.KEY_Z, XKeycode.KEY_X, XKeycode.KEY_C,
            XKeycode.KEY_A, XKeycode.KEY_S, XKeycode.KEY_D, XKeycode.KEY_W, XKeycode.KEY_F1, XKeycode.KEY_F5, XKeycode.KEY_F12)
        AlertDialog.Builder(context).setTitle("Action de ${def.label}")
            .setSingleChoiceItems(choices.map(::keyLabel).toTypedArray(), choices.indexOf(store?.binding(def.id, def.code) ?: def.code)) { dialog, index ->
                store?.bind(def.id, choices[index]); reload(); dialog.dismiss()
            }.setNegativeButton("Annuler", null).show()
    }
    private fun keyLabel(code: XKeycode) = when (code) {
        XKeycode.KEY_ENTER -> "Entrée"; XKeycode.KEY_ESC -> "Échap"; XKeycode.KEY_SPACE -> "Espace"
        XKeycode.KEY_SHIFT_L -> "Shift"; XKeycode.KEY_CTRL_L -> "Ctrl"
        else -> code.name.removePrefix("KEY_")
    }
    private fun press(def: Control) {
        if (def.id in held) return
        val code = store?.binding(def.id, def.code) ?: def.code
        val alreadyHeld = code in held.values
        held[def.id] = code
        if (!alreadyHeld) key(code, true)
    }
    private fun release(id: String) { val code = held.remove(id) ?: return; if (code !in held.values) key(code, false) }
    fun releaseAll() { held.keys.toList().forEach(::release); buttons.values.forEach { it.isPressed = false } }
    override fun onDetachedFromWindow() { releaseAll(); super.onDetachedFromWindow() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun button(label: String, primary: Boolean) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 13f; setPadding(0, 0, 0, 0); minWidth = 0; minHeight = 0
        setTextColor(if (primary) Color.rgb(26, 17, 42) else Color.rgb(239, 231, 255))
        background = RippleDrawable(ColorStateList.valueOf(0x557B61AA), GradientDrawable().apply {
            setColor(if (primary) Color.rgb(201, 183, 255) else Color.argb(235, 42, 34, 56)); cornerRadius = dp(if (primary) 38 else 16).toFloat()
        }, null); stateListAnimator = null
    }
    private class ArrowDrawable(private val angle: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 231, 255); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun draw(canvas: Canvas) {
            val unit = bounds.width() / 52f; paint.strokeWidth = 2.5f * unit
            canvas.save(); canvas.translate(bounds.exactCenterX(), bounds.exactCenterY()); canvas.rotate(angle)
            canvas.drawPath(Path().apply { moveTo(-7 * unit, 3 * unit); lineTo(0f, -4 * unit); lineTo(7 * unit, 3 * unit) }, paint); canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
