package fr.astragames.app.windows

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.winlator.xserver.XKeycode

/** Focused native dialogs; the overlay owns modal lifetime and releases every held input. */
internal class WolfControlDialogs(private val context: Context, private val store: WolfGameOptions,
    private val present: (AlertDialog) -> Unit, private val changed: () -> Unit) {
    private val theme = WolfDialogs.context(context)
    private val presets = WolfControlPresets(context)
    private fun builder(title: String) = WolfDialogs.builder(theme, title)
    private fun column() = LinearLayout(theme).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8))
    }
    private fun field(hint: String, value: String = "", limit: Int = 48) = EditText(theme).apply {
        this.hint = hint; setText(value); isSingleLine = true; filters = arrayOf(InputFilter.LengthFilter(limit))
        textSize = 16f; typeface = WolfViewStyle.font(context); minHeight = dp(48)
        setTextColor(WolfViewStyle.text); setHintTextColor(WolfViewStyle.secondary)
        contentDescription = hint
    }
    private fun rows(values: List<String>, singleChoice: Boolean = false) = object : ArrayAdapter<String>(
        theme, if (singleChoice) android.R.layout.simple_list_item_single_choice else android.R.layout.simple_list_item_1, values
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                textSize = 16f; typeface = WolfViewStyle.font(context); setTextColor(WolfViewStyle.text)
                minHeight = dp(48)
            }
    }
    fun movement() {
        val modes = WolfMovementMode.entries
        present(builder("Déplacement")
            .setSingleChoiceItems(rows(listOf("Croix directionnelle", "Joystick à huit directions"), true), modes.indexOf(WolfControlLayout.movement(store.controlLayout()))) { dialog, index ->
                store.loadControlLayout(store.controlLayout().put("movementMode", modes[index].name)); changed(); dialog.dismiss()
            }.setNegativeButton("Fermer", null).create())
    }
    private fun scroll(root: LinearLayout) = ScrollView(theme).apply { addView(root) }
    private class Appearance(var size: Int, var opacity: Int) {
        fun save(store: WolfGameOptions, id: String) {
            store.loadControlLayout(store.controlLayout().put("size_$id", size).put("opacity_$id", opacity))
        }
    }
    private fun appearance(root: LinearLayout, id: String?): Appearance {
        val layout = store.controlLayout()
        val state = Appearance(id?.let { WolfControlLayout.size(layout, it) } ?: 100,
            id?.let { WolfControlLayout.opacity(layout, it, store.read().opacity) } ?: store.read().opacity)
        fun slider(label: String, minimum: Int, maximum: Int, initial: Int, picked: (Int) -> Unit) {
            val value = WolfViewStyle.label(theme, "$label : $initial %", color = WolfViewStyle.secondary)
            root.addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            root.addView(SeekBar(theme).apply {
                max = maximum - minimum; progress = initial - minimum; contentDescription = label; minimumHeight = dp(48)
                progressTintList = android.content.res.ColorStateList.valueOf(WolfViewStyle.accent); thumbTintList = progressTintList
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val number = progress + minimum; value.text = "$label : $number %"; picked(number)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        slider("Taille individuelle", 50, 200, state.size) { state.size = it }
        slider("Opacité individuelle", 15, 100, state.opacity) { state.opacity = it }
        root.addView(WolfViewStyle.label(theme, "La taille se combine avec celle des commandes dans les réglages du jeu.", 13f, color = WolfViewStyle.secondary))
        return state
    }
    fun control(id: String, label: String, default: XKeycode) {
        val root = column()
        var selected = store.binding(id, default)
        val keyButton = WolfViewStyle.button(theme, "Touche : ${WolfKeyboard.label(selected)}")
        keyButton.setOnClickListener { key(selected) { selected = it; keyButton.text = "Touche : ${WolfKeyboard.label(it)}" } }
        root.addView(keyButton, LinearLayout.LayoutParams(-1, -2))
        val appearance = appearance(root, id)
        present(builder(label).setView(scroll(root))
            .setPositiveButton("Enregistrer") { _, _ -> store.bind(id, selected); appearance.save(store, id); changed() }
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Par défaut") { _, _ ->
                val data = store.controlLayout(); data.remove("size_$id"); data.remove("opacity_$id"); data.remove("key_$id")
                store.loadControlLayout(data); changed()
            }.create())
    }
    fun joystick() {
        val root = column()
        root.addView(WolfViewStyle.label(theme, "Huit directions. Les touches de la croix sont utilisées ; un second doigt peut maintenir une action en même temps.", color = WolfViewStyle.secondary))
        val appearance = appearance(root, "joystick")
        val bindings = mutableMapOf<String, XKeycode>()
        // The four bindings remain editable even while the cross itself is hidden.
        listOf("up" to XKeycode.KEY_UP, "left" to XKeycode.KEY_LEFT, "right" to XKeycode.KEY_RIGHT, "down" to XKeycode.KEY_DOWN).forEach { (id, default) ->
            val label = mapOf("up" to "Haut", "left" to "Gauche", "right" to "Droite", "down" to "Bas").getValue(id)
            bindings[id] = store.binding(id, default)
            root.addView(WolfViewStyle.button(theme, "$label : ${WolfKeyboard.label(bindings.getValue(id))}").apply {
                setOnClickListener { key(bindings.getValue(id)) { bindings[id] = it; text = "$label : ${WolfKeyboard.label(it)}" } }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        present(builder("Joystick").setView(scroll(root))
            .setPositiveButton("Enregistrer") { _, _ -> bindings.forEach { (id, code) -> store.bind(id, code) }; appearance.save(store, "joystick"); changed() }
            .setNegativeButton("Annuler", null).create())
    }
    fun key(selected: XKeycode, picked: (XKeycode) -> Unit) {
        val root = column()
        val search = field("Rechercher une touche")
        val list = ListView(theme).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        var choices = WolfKeyboard.keys
        fun refresh(query: String) {
            choices = WolfKeyboard.keys.filter { WolfKeyboard.label(it).contains(query, true) || it.name.removePrefix("KEY_").contains(query, true) }
            list.adapter = rows(choices.map(WolfKeyboard::label), singleChoice = true)
            choices.indexOf(selected).takeIf { it >= 0 }?.let { list.setItemChecked(it, true) }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        val available = (context.resources.configuration.screenHeightDp - 220).coerceIn(120, 320)
        root.addView(list, LinearLayout.LayoutParams(-1, dp(available)).apply { topMargin = dp(8) })
        val dialog = builder("Choisir une touche").setView(root).setNegativeButton("Annuler", null).create()
        list.setOnItemClickListener { _, _, index, _ -> picked(choices[index]); dialog.dismiss() }
        refresh(""); present(dialog)
    }
    fun custom(control: WolfCustomControl? = null) {
        if (control == null && store.customControls().size >= WolfControlLayout.MAX_CUSTOM) {
            present(builder("Limite de boutons").setMessage("24 boutons personnalisés maximum. Supprimez un bouton pour en ajouter un autre.").setPositiveButton("Fermer", null).create()); return
        }
        val root = column()
        root.addView(WolfViewStyle.label(context, "Nom affiché sur le bouton", color = WolfViewStyle.secondary))
        val name = field("Nom du bouton", control?.label.orEmpty(), 16)
        root.addView(name, LinearLayout.LayoutParams(-1, -2))
        var selected = control?.let { store.binding(it.id, it.code) } ?: XKeycode.KEY_ENTER
        val keyButton = WolfViewStyle.button(theme, "Touche : ${WolfKeyboard.label(selected)}")
        keyButton.setOnClickListener { key(selected) { selected = it; keyButton.text = "Touche : ${WolfKeyboard.label(it)}" } }
        root.addView(keyButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val appearance = appearance(root, control?.id)
        root.addView(WolfViewStyle.label(context, "Déplacez ensuite le bouton en le faisant glisser sur le jeu.", color = WolfViewStyle.secondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val dialogBuilder = builder(if (control == null) "Ajouter un bouton" else "Modifier le bouton")
            .setView(scroll(root)).setPositiveButton(if (control == null) "Ajouter" else "Enregistrer", null).setNegativeButton("Annuler", null)
        if (control != null) dialogBuilder.setNeutralButton("Supprimer") { _, _ -> store.removeControl(control.id); changed() }
        val dialog = dialogBuilder.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val clean = WolfControlLayout.label(name.text.toString()).ifBlank { WolfKeyboard.label(selected).take(16) }
            val id = if (control == null) store.addControl(clean, selected).id else control.id.also { store.editControl(it, clean, selected) }
            appearance.save(store, id)
            changed(); dialog.dismiss()
        } }
        present(dialog)
    }
    fun presets() {
        val names = presets.names()
        val root = column()
        root.addView(WolfViewStyle.label(context, "Joystick ou croix, touches, positions, tailles et opacités individuelles. Disponibles pour tous vos jeux Wolf.", color = WolfViewStyle.secondary))
        val list = ListView(theme)
        list.adapter = rows(names)
        if (names.isEmpty()) root.addView(WolfViewStyle.label(context, "Aucun preset enregistré.", 16f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(16) })
        else root.addView(list, LinearLayout.LayoutParams(-1, dp((names.size * 52).coerceAtMost(224))).apply { topMargin = dp(8) })
        val dialog = builder("Presets de contrôles").setView(ScrollView(theme).apply {
            isFillViewport = false; addView(root)
        }).create()
        fun action(label: String, primary: Boolean = false, click: () -> Unit) {
            root.addView(WolfViewStyle.button(theme, label, primary).apply {
                setOnClickListener { dialog.dismiss(); click() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        action("Enregistrer la disposition", primary = true) { savePreset() }
        action("Disposition par défaut") { store.resetControls(); changed() }
        action("Fermer") {}
        list.setOnItemClickListener { _, _, index, _ ->
            val name = names[index]
            present(builder(name).setAdapter(rows(listOf("Charger ce preset", "Remplacer par la disposition actuelle", "Supprimer ce preset"))) { _, action ->
                when (action) {
                    0 -> { presets.read(name)?.let(store::loadControlLayout); changed(); dialog.dismiss() }
                    1 -> { presets.save(name, store.controlLayout()); dialog.dismiss() }
                    2 -> { presets.delete(name); dialog.dismiss(); presets() }
                }
            }.setNegativeButton("Annuler", null).create())
        }
        present(dialog)
    }
    private fun savePreset() {
        val root = column()
        val name = field("Nom du preset")
        root.addView(name, LinearLayout.LayoutParams(-1, -2))
        val dialog = builder("Enregistrer un preset").setView(root).setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val clean = WolfControlLayout.presetName(name.text.toString())
            if (clean.isBlank()) { name.error = "Donnez un nom au preset."; return@setOnClickListener }
            fun save() {
                runCatching { presets.save(clean, store.controlLayout()) }.onSuccess { dialog.dismiss() }
                    .onFailure { name.error = it.message }
            }
            if (clean in presets.names()) present(builder("Remplacer « $clean » ?").setMessage("La disposition enregistrée sera remplacée par celle de ce jeu.")
                .setPositiveButton("Remplacer") { _, _ -> save() }.setNegativeButton("Annuler", null).create()) else save()
        } }
        present(dialog)
    }
    private fun dp(value: Int) = WolfViewStyle.dp(context, value)
}
