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
    private val present: (AlertDialog) -> Unit, private val changed: () -> Unit,
    private val exportPreset: ((String, String) -> Unit)? = null, private val importPreset: (() -> Unit)? = null) {
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
    private fun rows(values: List<String>, singleChoice: Boolean = false, multipleChoice: Boolean = false) = object : ArrayAdapter<String>(
        theme, if (multipleChoice) android.R.layout.simple_list_item_multiple_choice else if (singleChoice) android.R.layout.simple_list_item_single_choice else android.R.layout.simple_list_item_1, values
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                textSize = 16f; typeface = WolfViewStyle.font(context); setTextColor(WolfViewStyle.text)
                minHeight = dp(48)
            }
    }
    private fun message(value: String?) = android.widget.Toast.makeText(context, value ?: "Réglage invalide.", android.widget.Toast.LENGTH_LONG).show()
    private fun keyNames(keys: List<XKeycode>) = keys.joinToString(" + ", transform = WolfKeyboard::label).ifBlank { "Pause sans touche" }

    /** Existing single-key selection remains quick; combinations use an independent staged picker. */
    private fun keys(selected: List<XKeycode>, allowEmpty: Boolean = false, picked: (List<XKeycode>) -> Unit) {
        val root = column()
        val search = field("Rechercher une touche")
        val chosen = linkedSetOf<XKeycode>().apply { addAll(selected) }
        val summary = WolfViewStyle.label(theme, keyNames(chosen.toList()), 13f, color = WolfViewStyle.secondary)
        val list = ListView(theme).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        var choices = WolfKeyboard.keys
        fun refresh() {
            val query = search.text.toString()
            choices = WolfKeyboard.keys.filter { WolfKeyboard.label(it).contains(query, true) || it.name.removePrefix("KEY_").contains(query, true) || (it.id.toInt() and 255).toString() == query }
            list.adapter = rows(choices.map(WolfKeyboard::label), multipleChoice = true)
            choices.forEachIndexed { index, code -> list.setItemChecked(index, code in chosen) }
            summary.text = "${chosen.size}/4 · ${keyNames(chosen.toList())}"
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(list, LinearLayout.LayoutParams(-1, dp((context.resources.configuration.screenHeightDp - 280).coerceIn(120, 300))).apply { topMargin = dp(8) })
        val dialog = builder("Combinaison de touches").setView(root).setPositiveButton("Valider la combinaison", null).setNegativeButton("Annuler", null).create()
        list.setOnItemClickListener { _, _, index, _ ->
            val code = choices[index]
            if (code in chosen) chosen.remove(code)
            else if (chosen.size < WolfControlAction.MAX_KEYS) chosen.add(code)
            else { list.setItemChecked(index, false); message("4 touches maximum dans une combinaison.") }
            summary.text = "${chosen.size}/4 · ${keyNames(chosen.toList())}"
        }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!allowEmpty && chosen.isEmpty()) { message("Choisissez au moins une touche."); return@setOnClickListener }
            picked(WolfKeyboard.comboOrder(chosen.toList())); dialog.dismiss()
        } }
        refresh(); present(dialog)
    }

    private fun actionEditor(root: LinearLayout, initial: WolfControlAction): () -> WolfControlAction {
        var chosen = initial.keys
        var mode = initial.mode
        val steps = initial.steps.toMutableList()
        val keyButton = WolfViewStyle.button(theme, "Touche : ${keyNames(chosen)}")
        fun updateKeys(value: List<XKeycode>) { chosen = value; keyButton.text = "Touche : ${keyNames(chosen)}" }
        keyButton.setOnClickListener {
            if (chosen.size == 1) key(chosen.first()) { updateKeys(listOf(it)) }
            else keys(chosen) { updateKeys(it) }
        }
        root.addView(keyButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(WolfViewStyle.button(theme, "Configurer une combinaison").apply {
            setOnClickListener { keys(chosen) { updateKeys(it) } }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val modeButton = WolfViewStyle.button(theme, "")
        val macro = LinearLayout(theme).apply { orientation = LinearLayout.VERTICAL }
        fun modeName() = when (mode) { WolfControlActionMode.HOLD -> "Maintenir"; WolfControlActionMode.TOGGLE -> "Bascule"; WolfControlActionMode.MACRO -> "Séquence" }
        fun redrawSteps() {
            macro.removeAllViews()
            macro.visibility = if (mode == WolfControlActionMode.MACRO) View.VISIBLE else View.GONE
            val total = steps.sumOf { it.durationMs }
            macro.addView(WolfViewStyle.label(theme, "${steps.size}/16 étapes · $total/10000 ms. Touchez le bouton une seconde fois pour arrêter la séquence.", 13f, color = WolfViewStyle.secondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            steps.forEachIndexed { index, step ->
                macro.addView(WolfViewStyle.button(theme, "Étape ${index + 1} : ${keyNames(step.keys)} · ${step.durationMs} ms").apply {
                    contentDescription = "Modifier l’étape ${index + 1}"
                    setOnClickListener { editStep(step) { steps[index] = it; redrawSteps() } }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                macro.addView(WolfViewStyle.button(theme, "Supprimer l’étape ${index + 1}").apply {
                    setOnClickListener { steps.removeAt(index); redrawSteps() }
                }, LinearLayout.LayoutParams(-1, -2))
            }
            macro.addView(WolfViewStyle.button(theme, "Ajouter une étape").apply {
                isEnabled = steps.size < WolfControlAction.MAX_STEPS
                setOnClickListener { editStep(WolfMacroStep(chosen, 120)) { steps += it; redrawSteps() } }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        fun redrawMode() { modeButton.text = "Action : ${modeName()}"; redrawSteps() }
        modeButton.setOnClickListener {
            present(builder("Action du bouton").setSingleChoiceItems(rows(listOf("Maintenir pendant l’appui", "Bascule : activer / désactiver", "Séquence de touches"), true), mode.ordinal) { dialog, index ->
                mode = WolfControlActionMode.entries[index]
                if (mode == WolfControlActionMode.MACRO && steps.isEmpty()) steps += WolfMacroStep(chosen, 120)
                redrawMode(); dialog.dismiss()
            }.setNegativeButton("Annuler", null).create())
        }
        root.addView(modeButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(WolfViewStyle.label(theme, "Une bascule maintient les touches jusqu’au prochain appui. Pause, verrouillage, menus et changement de preset relâchent toutes les touches.", 13f, color = WolfViewStyle.secondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(macro, LinearLayout.LayoutParams(-1, -2))
        redrawMode()
        return { WolfControlAction(chosen, mode, if (mode == WolfControlActionMode.MACRO) steps.toList() else emptyList()).also(WolfControlAction::validate) }
    }

    private fun editStep(initial: WolfMacroStep, picked: (WolfMacroStep) -> Unit) {
        val root = column()
        var chosen = initial.keys
        root.addView(WolfViewStyle.button(theme, "Touches : ${keyNames(chosen)}").apply {
            setOnClickListener { keys(chosen, allowEmpty = true) { chosen = it; text = "Touches : ${keyNames(chosen)}" } }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(WolfViewStyle.label(theme, "Aucune touche sélectionnée ajoute une pause. Durée de 20 à 2000 ms ; séquence totale de 10 secondes maximum.", 13f, color = WolfViewStyle.secondary))
        val duration = field("Durée (ms)", initial.durationMs.toString(), 4).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        root.addView(duration, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val dialog = builder("Étape de séquence").setView(root).setPositiveButton("Enregistrer l’étape", null).setNegativeButton("Annuler", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = duration.text.toString().toIntOrNull()
            if (value == null || value !in WolfControlAction.MIN_STEP_MS..WolfControlAction.MAX_STEP_MS) {
                duration.error = "Durée entre 20 et 2000 ms."; return@setOnClickListener
            }
            picked(WolfMacroStep(chosen, value)); dialog.dismiss()
        } }
        present(dialog)
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
        val action = actionEditor(root, store.action(id, default))
        val appearance = appearance(root, id)
        val dialog = builder(label).setView(scroll(root))
            .setPositiveButton("Enregistrer", null)
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Par défaut") { _, _ ->
                val data = store.controlLayout(); data.remove("size_$id"); data.remove("opacity_$id"); data.remove("key_$id"); data.remove("action_$id")
                store.loadControlLayout(data); changed()
            }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { val selected = action(); store.setAction(id, selected); appearance.save(store, id); changed() }
                .onSuccess { dialog.dismiss() }.onFailure { message(it.message) }
        } }
        present(dialog)
    }
    fun joystick() {
        val root = column()
        root.addView(WolfViewStyle.label(theme, "Huit directions. Les touches de la croix sont utilisées ; un second doigt peut maintenir une action en même temps.", color = WolfViewStyle.secondary))
        val appearance = appearance(root, "joystick")
        val bindings = mutableMapOf<String, WolfControlAction>()
        // The four bindings remain editable even while the cross itself is hidden.
        listOf("up" to XKeycode.KEY_UP, "left" to XKeycode.KEY_LEFT, "right" to XKeycode.KEY_RIGHT, "down" to XKeycode.KEY_DOWN).forEach { (id, default) ->
            val label = mapOf("up" to "Haut", "left" to "Gauche", "right" to "Droite", "down" to "Bas").getValue(id)
            bindings[id] = store.action(id, default)
            val keyButton = WolfViewStyle.button(theme, "$label : ${keyNames(bindings.getValue(id).keys)}").apply {
                setOnClickListener { key(bindings.getValue(id).keys.first()) { bindings[id] = WolfControlAction(listOf(it)); text = "$label : ${WolfKeyboard.label(it)}" } }
            }
            root.addView(keyButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            root.addView(WolfViewStyle.button(theme, "Combinaison $label").apply {
                setOnClickListener { keys(bindings.getValue(id).keys) { selected ->
                    bindings[id] = WolfControlAction(selected); keyButton.text = "$label : ${keyNames(selected)}"
                } }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        present(builder("Joystick").setView(scroll(root))
            .setPositiveButton("Enregistrer") { _, _ -> bindings.forEach { (id, action) -> store.setAction(id, action) }; appearance.save(store, "joystick"); changed() }
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
        val action = actionEditor(root, control?.let { store.action(it.id, it.code) } ?: WolfControlAction(listOf(XKeycode.KEY_ENTER)))
        val appearance = appearance(root, control?.id)
        root.addView(WolfViewStyle.label(context, "Déplacez ensuite le bouton en le faisant glisser sur le jeu.", color = WolfViewStyle.secondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val dialogBuilder = builder(if (control == null) "Ajouter un bouton" else "Modifier le bouton")
            .setView(scroll(root)).setPositiveButton(if (control == null) "Ajouter" else "Enregistrer", null).setNegativeButton("Annuler", null)
        if (control != null) dialogBuilder.setNeutralButton("Supprimer") { _, _ -> store.removeControl(control.id); changed() }
        val dialog = dialogBuilder.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val selected = action()
                val clean = WolfControlLayout.label(name.text.toString()).ifBlank { WolfKeyboard.label(selected.keys.first()).take(16) }
                val id = if (control == null) store.addControl(clean, selected.keys.first()).id else control.id.also { store.editControl(it, clean, selected.keys.first()) }
                store.setAction(id, selected); appearance.save(store, id); changed()
            }.onSuccess { dialog.dismiss() }.onFailure { message(it.message) }
        } }
        present(dialog)
    }
    fun presets() {
        val names = presets.names()
        val root = column()
        root.addView(WolfViewStyle.label(context, "Joystick ou croix, combinaisons, bascules, séquences, positions, tailles et opacités. Disponibles pour tous vos jeux Wolf, et partageables en JSON.", color = WolfViewStyle.secondary))
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
        if (exportPreset != null) action("Exporter la disposition") { exportCurrent() }
        importPreset?.let { callback -> action("Importer un preset") { callback() } }
        action("Disposition par défaut") { store.resetControls(); changed() }
        action("Fermer") {}
        list.setOnItemClickListener { _, _, index, _ ->
            val name = names[index]
            val choices = listOf("Charger ce preset", "Remplacer par la disposition actuelle", "Supprimer ce preset") + if (exportPreset != null) listOf("Exporter ce preset") else emptyList()
            present(builder(name).setAdapter(rows(choices)) { _, action ->
                when (action) {
                    0 -> { presets.read(name)?.let(store::loadControlLayout); changed(); dialog.dismiss() }
                    1 -> { presets.save(name, store.controlLayout()); dialog.dismiss() }
                    2 -> { presets.delete(name); dialog.dismiss(); presets() }
                    3 -> { exportPreset?.invoke(filename(name), presets.export(name)); dialog.dismiss() }
                }
            }.setNegativeButton("Annuler", null).create())
        }
        present(dialog)
    }
    private fun exportCurrent() {
        val root = column()
        val name = field("Nom du preset", "Contrôles Wolf")
        root.addView(name, LinearLayout.LayoutParams(-1, -2))
        root.addView(WolfViewStyle.label(theme, "Le fichier contient uniquement les commandes. Il ne contient aucune sauvegarde ni configuration du moteur.", 13f, color = WolfViewStyle.secondary))
        val dialog = builder("Exporter un preset").setView(root).setPositiveButton("Exporter", null).setNegativeButton("Annuler", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val clean = WolfControlLayout.presetName(name.text.toString())
            runCatching { WolfControlPresetCodec.encode(clean, store.controlLayout()) }
                .onSuccess { json -> dialog.dismiss(); exportPreset?.invoke(filename(clean), json) }
                .onFailure { name.error = it.message }
        } }
        present(dialog)
    }
    private fun filename(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').take(64).ifBlank { "wolf-controls" } + ".json"
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
