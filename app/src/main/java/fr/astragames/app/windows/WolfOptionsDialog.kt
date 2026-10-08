package fr.astragames.app.windows

import android.app.AlertDialog
import android.content.Context
import android.widget.*

/** Compact native controls shared by the game sheet and the in-session menu. */
internal fun showWolfOptions(host: Context, store: WolfGameOptions, changed: () -> Unit = {}) {
    val context = android.view.ContextThemeWrapper(host, android.R.style.Theme_Material_Dialog_Alert)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val accent = android.content.res.ColorStateList.valueOf(0xFFC9B7FF.toInt())
    val original = store.read()
    val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
    fun label(text: String) { layout.addView(TextView(context).apply { this.text = text; setPadding(0, 14, 0, 4) }) }
    fun choice(title: String, values: List<String>, selected: Int): Spinner {
        label(title)
        return Spinner(context).apply {
            contentDescription = title
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(selected); layout.addView(this)
        }
    }
    fun toggle(title: String, selected: Boolean) = Switch(context).apply {
        text = title; isChecked = selected; thumbTintList = accent; minHeight = dp(48); layout.addView(this)
    }
    fun slider(title: String, minimum: Int, maximum: Int, value: Int): SeekBar {
        val caption = TextView(context).apply { text = "$title : $value %"; setPadding(0, 14, 0, 4); layout.addView(this) }
        return SeekBar(context).apply {
            contentDescription = title; max = maximum - minimum; progress = value - minimum; progressTintList = accent; thumbTintList = accent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) { caption.text = "$title : ${progress + minimum} %" }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            }); layout.addView(this)
        }
    }
    val performance = choice("Fluidité · au prochain lancement", WolfPerformance.entries.map { it.label }, original.performance.ordinal)
    label("Rapide favorise la vitesse ; revenez à Stable si le jeu devient instable.")
    val resolution = choice("Affichage Windows · au prochain lancement", WolfOptions.resolutions, WolfOptions.resolutions.indexOf(original.resolution))
    label("La définition interne dépend du jeu. Le lissage adoucit les pixels lors de l’agrandissement.")
    val smooth = toggle("Lissage renforcé", original.smooth)
    val fps = choice("Limite d’affichage", listOf("30 images/s", "60 images/s"), if (original.maxFps == 30) 0 else 1)
    val counter = toggle("Compteur FPS · images reçues du jeu", original.showFps)
    val touch = choice("Tactile", listOf("Clic à la position touchée", "Direction au toucher (maintenir)"), if (original.directionalTouch) 1 else 0)
    label("Le clic déplace le personnage si le jeu le prévoit. Sinon, maintenez un côté de l’image pour avancer dans cette direction.")
    val opacity = slider("Opacité des touches", 15, 100, original.opacity)
    val size = slider("Taille des touches", 70, 150, original.size)
    label("Menu → Modifier les touches : faites glisser chaque touche ; touchez-la pour changer son action. Positions séparées en portrait et paysage.")
    val dialog = AlertDialog.Builder(context).setTitle("Réglages Wolf RPG")
        .setView(ScrollView(context).apply { addView(layout) })
        .setNegativeButton("Annuler", null)
        .setPositiveButton("Enregistrer") { _, _ ->
            store.save(WolfOptions(WolfPerformance.entries[performance.selectedItemPosition], WolfOptions.resolutions[resolution.selectedItemPosition],
                smooth.isChecked, if (fps.selectedItemPosition == 0) 30 else 60, counter.isChecked, touch.selectedItemPosition == 1,
                opacity.progress + 15, size.progress + 70)); changed()
        }.create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent)
    }
    dialog.show()
}
