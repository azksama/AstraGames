package fr.astragames.app.windows

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.os.SystemClock

/** Phase counts and elapsed time are real; Wine does not provide a meaningful percentage. */
internal class WolfLoadingView(context: Context, cancel: () -> Unit) : LinearLayout(context) {
    private val started = SystemClock.elapsedRealtime()
    private var phaseStarted = started
    private var phase = 1
    private val steps = TextView(context).apply { setTextColor(0xFFC9B7FF.toInt()); textSize = 14f; gravity = Gravity.CENTER }
    val status = TextView(context).apply { setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER }
    private val elapsed = TextView(context).apply { setTextColor(0xFFCCC5D7.toInt()); textSize = 14f; gravity = Gravity.CENTER }
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK)
        addView(TextView(context).apply { text = "Lancement de Wolf RPG"; setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER })
        addView(steps, LayoutParams(-1, -2).apply { topMargin = 24 })
        addView(ProgressBar(context), LayoutParams(-2, -2).apply { gravity = Gravity.CENTER; topMargin = 24; bottomMargin = 24 })
        addView(status, LayoutParams(-1, -2))
        addView(elapsed, LayoutParams(-1, -2).apply { topMargin = 20; bottomMargin = 20 })
        addView(Button(context).apply { text = "Annuler le lancement"; setOnClickListener { cancel() } })
        update(1, "Récupération de la session précédente…")
    }
    fun update(step: Int, message: String) {
        if (step != phase) { phase = step; phaseStarted = SystemClock.elapsedRealtime() }
        steps.text = "Étape $phase / 8"
        status.text = message
        tick()
    }
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        elapsed.text = "${(now - started) / 1000} s écoulées · ${(now - phaseStarted) / 1000} s dans cette étape"
    }
}
