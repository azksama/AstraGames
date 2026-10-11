package fr.astragames.app.windows

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import fr.astragames.app.R

/** Astra's view-based game overlays use the same roles and bundled fonts as its library. */
internal object WolfViewStyle {
    val background = 0xFF09090F.toInt()
    val surface = 0xFF191E29.toInt()
    val accent = 0xFFC9B7FF.toInt()
    val text = 0xFFF5F2FA.toInt()
    val secondary = 0xFFABA8B8.toInt()
    val action = 0xFF302147.toInt()
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun font(context: Context, title: Boolean = false): Typeface = ResourcesCompat.getFont(context, if (title) R.font.geist else R.font.inter) ?: Typeface.DEFAULT
    fun label(context: Context, value: String, size: Float = 14f, color: Int = text, title: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); typeface = font(context, title)
    }
    fun button(context: Context, value: String, primary: Boolean = false) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 14f; typeface = font(context)
        minHeight = dp(context, 48); minimumHeight = minHeight; minWidth = 0
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
        setTextColor(if (primary) WolfViewStyle.background else accent)
        background = RippleDrawable(ColorStateList.valueOf(0x447B61AA), GradientDrawable().apply {
            setColor(if (primary) accent else action); cornerRadius = dp(context, 16).toFloat()
        }, null)
        stateListAnimator = null
    }
}
