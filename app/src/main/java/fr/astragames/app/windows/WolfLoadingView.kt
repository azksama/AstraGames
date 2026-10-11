package fr.astragames.app.windows

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Completed phases and elapsed time are real; neither engine provides a total percentage. */
internal class WolfLoadingView(context: Context, cancel: () -> Unit, diagnostic: () -> Unit = {}) : LinearLayout(context) {
    private val started = SystemClock.elapsedRealtime()
    private var phaseStarted = started
    private var phase = 1
    private var lastUpdate = started
    private var delayReported = false
    private val steps = WolfViewStyle.label(context, "", 13f, WolfViewStyle.accent).apply { letterSpacing = .06f }
    val status = WolfViewStyle.label(context, "", 18f).apply {
        minHeight = dp(64); setLineSpacing(dp(4).toFloat(), 1f)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val elapsed = WolfViewStyle.label(context, "", 13f, WolfViewStyle.secondary)
    private val indicator = PhaseIndicator(context)
    private val column = LinearLayout(context).apply {
        orientation = VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
    }
    init {
        orientation = VERTICAL; setBackgroundColor(WolfViewStyle.background)
        val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
        val center = FrameLayout(context)
        center.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        scroll.addView(center, FrameLayout.LayoutParams(-1, -2)); addView(scroll, LayoutParams(-1, -1))
        column.addView(WolfViewStyle.label(context, "Préparation du jeu", 28f, title = true))
        column.addView(steps, spacing(24))
        column.addView(indicator, LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
        column.addView(status, spacing(16))
        column.addView(elapsed, spacing(8))
        column.addView(WolfViewStyle.button(context, "Voir le diagnostic", true).apply {
            contentDescription = "Voir le diagnostic du lancement"; setOnClickListener { diagnostic() }
        }, spacing(32))
        column.addView(WolfViewStyle.button(context, "Annuler le lancement").apply { setOnClickListener { cancel() } }, spacing(8))
        update(1, "Récupération de la session précédente…")
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        column.layoutParams.width = minOf(dp(560), (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    fun update(step: Int, message: String) {
        val bounded = step.coerceIn(1, 8)
        if (bounded != phase || status.text != message) { lastUpdate = SystemClock.elapsedRealtime(); delayReported = false }
        if (bounded != phase) { phase = bounded; phaseStarted = SystemClock.elapsedRealtime() }
        steps.text = "ÉTAPE $phase SUR 8"
        indicator.phase = phase
        indicator.contentDescription = "${phase - 1} étapes terminées sur 8"
        status.text = message
        tick()
    }
    fun stalled(): Pair<Int, String>? {
        if (delayReported || SystemClock.elapsedRealtime() - lastUpdate < 20_000) return null
        delayReported = true
        return phase to status.text.toString()
    }
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        elapsed.text = "${(now - started) / 1000} s écoulées · ${(now - phaseStarted) / 1000} s à cette étape"
    }
    private fun spacing(top: Int) = LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = WolfViewStyle.dp(context, value)

    private class PhaseIndicator(context: Context) : View(context) {
        var phase = 1
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pulse = .65f
        private var animator: ValueAnimator? = null
        override fun onDraw(canvas: Canvas) {
            val gap = WolfViewStyle.dp(context, 6).toFloat()
            val segment = (width - 7 * gap) / 8f
            val bar = WolfViewStyle.dp(context, 6).toFloat()
            for (index in 0..7) {
                paint.color = if (index < phase) WolfViewStyle.accent else WolfViewStyle.surface
                paint.alpha = if (index == phase - 1) (pulse * 255).toInt() else 255
                val left = index * (segment + gap)
                canvas.drawRoundRect(left, (height - bar) / 2, left + segment, (height + bar) / 2, bar / 2, bar / 2, paint)
            }
        }
        private fun motion() {
            animator?.cancel(); animator = null
            if (isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
                animator = ValueAnimator.ofFloat(.45f, 1f).apply {
                    duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { pulse = it.animatedValue as Float; invalidate() }; start()
                }
            } else { pulse = 1f; invalidate() }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); motion() }
        override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); motion() }
        override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); motion() }
        override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    }
}
