package fr.astragames.app.windows

import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.astragames.app.R

/** Shared Astra chrome for host dialogs. The game's own drawn windows keep their artwork. */
internal object WolfDialogs {
    fun context(host: Context) = ContextThemeWrapper(host, R.style.Theme_AstraGames_WolfControls)

    fun builder(host: Context, title: String): AlertDialog.Builder {
        val theme = context(host)
        return AlertDialog.Builder(theme, R.style.Theme_AstraGames_WolfControls).setCustomTitle(
            WolfViewStyle.label(theme, title, 22f, title = true).apply {
                setPadding(dp(theme, 24), dp(theme, 24), dp(theme, 24), dp(theme, 8))
            }
        )
    }

    fun column(host: Context) = LinearLayout(context(host)).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(host, 24), dp(host, 16), dp(host, 24), dp(host, 8))
    }

    fun rows(host: Context, values: List<String>, singleChoice: Boolean = false) = object : ArrayAdapter<String>(
        context(host), if (singleChoice) android.R.layout.simple_list_item_single_choice else android.R.layout.simple_list_item_1, values
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                typeface = WolfViewStyle.font(context); textSize = 16f
                setTextColor(WolfViewStyle.text); minHeight = dp(context, 48)
                setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12))
            }
    }

    fun field(host: Context, hint: String = "") = EditText(context(host)).apply {
        this.hint = hint; contentDescription = hint; textSize = 16f
        typeface = WolfViewStyle.font(context); setTextColor(WolfViewStyle.text)
        setHintTextColor(WolfViewStyle.accent); minHeight = dp(context, 48)
    }

    /** Called after show; preserves each dialog's validation, keyboard and dismissal listeners. */
    fun style(dialog: AlertDialog) {
        val context = dialog.context
        dialog.window?.apply {
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.wolf_control_dialog_background))
            val width = (context.resources.configuration.screenWidthDp - 32).coerceIn(240, 560)
            setLayout(dp(context, width), WindowManager.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .65f }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            typeface = WolfViewStyle.font(context); textSize = 15f; setTextColor(WolfViewStyle.text)
            setTextIsSelectable(true)
        }
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                isAllCaps = false; typeface = WolfViewStyle.font(context); textSize = 14f
                setTextColor(WolfViewStyle.accent); minHeight = dp(context, 48)
            }
        }
    }

    fun show(dialog: AlertDialog) { dialog.show(); style(dialog) }
    private fun dp(context: Context, value: Int) = WolfViewStyle.dp(context, value)
}
