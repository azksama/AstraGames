package fr.astragames.app.windows

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Explicit, session-bound memory editing. Closing this panel never reuses addresses in another game. */
internal fun showWolfCheats(host: FragmentActivity, session: WolfCheatSession): AlertDialog = WolfCheatDialog(host, session).show()

private class WolfCheatDialog(private val host: FragmentActivity, private val session: WolfCheatSession) {
    private val theme = WolfDialogs.context(host)
    private val scope = CoroutineScope(host.lifecycleScope.coroutineContext + SupervisorJob(host.lifecycleScope.coroutineContext[Job]))
    private val root = WolfDialogs.column(host)
    private val body = LinearLayout(theme).apply { orientation = LinearLayout.VERTICAL }
    private val status = label("Aucune recherche en cours.")
    private val progress = ProgressBar(theme, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
    private val cancel = button("Annuler la recherche") { cancelled = true; session.cancel(); status.text = "Annulation demandée…" }.apply { visibility = View.GONE }
    private val children = mutableSetOf<AlertDialog>()
    private val actions = mutableListOf<View>()
    private val dataTab = button("Variables Wolf") { dataMode = true; tracking = false; render(); if (dataProbe == null) loadData(0, probe = true) }
    private val searchTab = button("Mémoire") { dataMode = false; tracking = false; render() }
        .apply { contentDescription = "Recherche en mémoire" }
    private val pinsTab = button("Valeurs suivies") { dataMode = false; tracking = true; render() }
    private lateinit var dialog: AlertDialog
    private var busy = false
    private var tracking = false
    private var dataMode = false
    private var dataProbe: EngineDataProbe? = null
    private var dataPage: EngineDataPage? = null
    private var dataCategory: EngineDataCategory? = null
    private var dataBank = ""
    private var dataQuery = ""
    private var page = 0
    private var query = ""
    private var valueText = ""
    private var otherText = ""
    private var initialMode = 0
    private var pinsList: LinearLayout? = null
    private var cancelled = false

    fun show(): AlertDialog {
        root.addView(label("Recherchez une valeur, jouez pour la faire varier, puis affinez. Les noms internes du jeu ne sont pas automatiquement connus : vous pouvez nommer vos valeurs suivies."))
        root.addView(row(dataTab, searchTab, pinsTab), params(12))
        root.addView(status, params(12)); root.addView(progress, params(4)); root.addView(cancel, params(4))
        root.addView(body, params(12))
        dialog = WolfDialogs.builder(host, "Cheats et variables")
            .setView(ScrollView(theme).apply { addView(root) })
            .setNegativeButton("Fermer", null).create()
        dialog.setOnDismissListener {
            if (busy) session.cancel()
            scope.cancel(); children.toList().forEach(AlertDialog::dismiss)
        }
        render(); WolfDialogs.show(dialog)
        session.result?.let { loadPage(0, it.type) }
        return dialog
    }

    private fun label(text: String, title: Boolean = false): TextView = WolfViewStyle.label(theme, text, if (title) 16f else 13f,
        color = if (title) WolfViewStyle.text else WolfViewStyle.secondary, title = title)
    private fun params(margin: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(margin) }
    private fun dp(value: Int) = WolfViewStyle.dp(host, value)
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = WolfViewStyle.button(theme, text, primary).apply { setOnClickListener { action() } }
    private fun row(vararg views: View) = LinearLayout(theme).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { index, view -> addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(8) }) }
    }
    private fun field(hint: String, value: String, changed: (String) -> Unit): EditText = WolfDialogs.field(theme, hint).apply {
        isSingleLine = true; setText(value); filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private fun choice(values: List<String>, selected: Int, picked: (Int) -> Unit) = Spinner(theme).apply {
        adapter = ArrayAdapter(theme, android.R.layout.simple_spinner_dropdown_item, values)
        minimumHeight = dp(48); setSelection(selected)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { picked(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }
    private fun enable(view: View): View { actions += view; view.isEnabled = !busy; return view }
    private fun present(child: AlertDialog) {
        children += child; child.setOnDismissListener { children -= child }; WolfDialogs.show(child)
    }
    private fun setBusy(value: Boolean) {
        busy = value; progress.visibility = if (value) View.VISIBLE else View.GONE; cancel.visibility = progress.visibility
        actions.forEach { it.isEnabled = !value }; dataTab.isEnabled = !value; searchTab.isEnabled = !value; pinsTab.isEnabled = !value
        children.forEach { child -> listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL).forEach { child.getButton(it)?.isEnabled = !value } }
    }
    private fun work(message: String, action: suspend () -> Unit) {
        if (busy || !dialog.isShowing) return
        cancelled = false; status.text = message; setBusy(true)
        val start = SystemClock.elapsedRealtime()
        scope.launch {
            try {
                action()
                status.text = if (cancelled) "Annulation demandée ; les résultats disponibles sont conservés." else "Terminé en ${(SystemClock.elapsedRealtime() - start) / 1000.0} s."
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = error.message ?: "L’opération n’a pas abouti." }
            finally { if (dialog.isShowing) { setBusy(false); render() } }
        }
    }
    private fun render() {
        body.removeAllViews(); actions.clear(); pinsList = null
        dataTab.alpha = if (dataMode) 1f else .65f
        searchTab.alpha = if (tracking || dataMode) .65f else 1f; pinsTab.alpha = if (tracking) 1f else .65f
        pinsTab.text = "Valeurs suivies (${session.pins.size})"
        if (dataMode) renderData() else if (tracking) renderPins() else renderSearch()
        setBusy(busy)
    }
    private fun renderSearch() {
        body.addView(label("Type de valeur", true))
        body.addView(enable(choice(WolfCheatType.entries.map { it.label }, session.type.ordinal) { session.type = WolfCheatType.entries[it] }), params(4))
        val value = field("Valeur à rechercher", valueText) { valueText = it }
        val other = field("Valeur maximale", otherText) { otherText = it }
        body.addView(enable(choice(listOf("Valeur exacte", "Valeur inconnue", "Intervalle inclusif"), initialMode) {
            initialMode = it; value.visibility = if (it == 1) View.GONE else View.VISIBLE; other.visibility = if (it == 2) View.VISIBLE else View.GONE
        }), params(4))
        value.visibility = if (initialMode == 1) View.GONE else View.VISIBLE; other.visibility = if (initialMode == 2) View.VISIBLE else View.GONE
        body.addView(enable(value), params(4)); body.addView(enable(other), params(4))
        body.addView(enable(button("Nouvelle recherche", true) {
            search(op = 1, mode = listOf(0, 1, 6)[initialMode], type = session.type)
        }), params(8))
        val result = session.result ?: return
        body.addView(label("${result.count} résultat(s) · ${result.type.label}", true), params(16))
        body.addView(label("${result.scanned / (1024 * 1024)} Mio parcourus. Les valeurs ci-dessous correspondent à la dernière recherche ; ouvrir un résultat relit sa valeur actuelle."), params(4))
        if (result.truncated) body.addView(label("Limite atteinte : seuls les 2 097 152 premiers candidats ont été conservés. Affinez-les ou commencez une recherche plus précise ; les autres adresses ne sont pas incluses."), params(8))
        val refine = button("Affiner les résultats") { refine(result.type) }
        val undo = button("Annuler l’affinage") { work("Restauration de la recherche…") {
            session.command(10, valueType = result.type); session.command(3, valueType = result.type); page = 0
        } }
        body.addView(row(enable(refine), enable(undo)), params(8))
        val list = LinearLayout(theme).apply { orientation = LinearLayout.VERTICAL }
        result.rows.forEach { found ->
            list.addView(enable(button("${hex(found.address)}  ·  ${result.type.format(found.bits)}") { edit(found.address, result.type) }), params(4))
        }
        if (result.count == 0L) list.addView(label("Aucun résultat. Essayez une autre valeur ou un autre type."), params(8))
        val height = (host.resources.configuration.screenHeightDp / 3).coerceIn(144, 320)
        body.addView(ScrollView(theme).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(8) })
        val previous = button("Précédent") { loadPage((page - 128).coerceAtLeast(0), result.type) }
        val next = button("Suivant") { loadPage(page + 128, result.type) }
        if (page > 0) enable(previous) else previous.isEnabled = false
        if (page + result.rows.size < result.count) enable(next) else next.isEnabled = false
        body.addView(label(if (result.count == 0L) "0 résultat" else "${page + 1}–${page + result.rows.size} sur ${result.count}"), params(8))
        body.addView(row(previous, next), params(4))
        body.addView(enable(button("Annuler la dernière modification") { work("Annulation de la modification…") { session.undo() } }), params(8))
    }
    private fun renderData() {
        body.addView(label("Variables du moteur", true))
        body.addView(label("Les identifiants viennent des tableaux du jeu. Les noms métier (argent, vie…) ne sont pas déduits. Seuls les formats mémoire vérifiés sont éditables."), params(4))
        body.addView(enable(button("Actualiser la sonde Wolf") { loadData(0, probe = true) }), params(8))
        when (val probe = dataProbe) {
            null -> { body.addView(label("Lancez la sonde après être entré dans une partie."), params(8)); return }
            is EngineDataProbe.Unavailable -> {
                body.addView(label(probe.reason), params(8))
                body.addView(enable(button("Ouvrir le scanner mémoire") { dataMode = false; tracking = false; render() }), params(8))
                return
            }
            is EngineDataProbe.Supported -> body.addView(label("${probe.build} · ${probe.banks.sum()} nombres · ${probe.textCount} textes · ${probe.commonCount} événements communs", true), params(8))
        }
        body.addView(enable(choice(listOf("Toutes les catégories") + EngineDataCategory.entries.map { it.label }, dataCategory?.wire ?: 0) {
            dataCategory = EngineDataCategory.entries.firstOrNull { category -> category.wire == it }
        }), params(8))
        body.addView(enable(field("Groupe / événement commun (vide : tous)", dataBank) { dataBank = it }), params(4))
        body.addView(enable(field("Rechercher un ID, une valeur ou du texte", dataQuery) { dataQuery = it }), params(4))
        body.addView(enable(button("Rechercher les variables", true) { loadData(0) }), params(8))
        val page = dataPage ?: return
        body.addView(label("${page.count} variable(s). Ouvrir une ligne relit sa valeur actuelle."), params(12))
        val list = LinearLayout(theme).apply { orientation = LinearLayout.VERTICAL }
        page.variables.forEach { variable ->
            val value = variable.value.display.replace('\n', ' ').take(120).ifEmpty { "(texte vide)" }
            list.addView(enable(button("${variable.id} · ${variable.category.label}\n$value${if (variable.readOnly) " · Lecture seule" else ""}") { editData(variable.id) }), params(4))
        }
        if (page.count == 0L) list.addView(label("Aucune variable ne correspond à cette recherche."))
        body.addView(ScrollView(theme).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp((host.resources.configuration.screenHeightDp / 3).coerceIn(144, 320))).apply { topMargin = dp(8) })
        val previous = button("Précédent") { loadData((page.offset - 128).coerceAtLeast(0)) }
        val next = button("Suivant") { loadData(page.offset + 128) }
        if (page.offset > 0) enable(previous) else previous.isEnabled = false
        if (page.offset + page.variables.size < page.count) enable(next) else next.isEnabled = false
        body.addView(label(if (page.count == 0L) "0 variable" else "${page.offset + 1}–${page.offset + page.variables.size} sur ${page.count}"), params(8))
        body.addView(row(previous, next), params(4))
        if (session.engineData.canUndo) body.addView(enable(button("Annuler la dernière modification Wolf") {
            work("Annulation et relecture de la variable…") { session.engineData.undo(); refreshDataPage() }
        }), params(8))
    }
    private fun loadData(offset: Int, probe: Boolean = false) {
        val bank = if (dataBank.isBlank()) null else dataBank.trim().toIntOrNull()
        if (dataBank.isNotBlank() && (bank == null || bank !in 0..9999)) { status.text = "Le groupe doit être un entier entre 0 et 9999."; return }
        work("Lecture des tableaux Wolf…") {
            if (probe) { dataPage = null; dataProbe = session.engineData.probe() }
            if (dataProbe is EngineDataProbe.Supported) {
                val result = session.engineData.search(dataCategory, bank, dataQuery, offset)
                dataProbe = result.probe; dataPage = result
            }
        }
    }
    private suspend fun refreshDataPage() {
        val bank = if (dataBank.isBlank()) null else dataBank.trim().toIntOrNull()
        val result = session.engineData.search(dataCategory, bank, dataQuery, dataPage?.offset ?: 0)
        dataProbe = result.probe; dataPage = result
    }
    private fun editData(id: Int) {
        work("Relecture de la variable $id…") {
            val before = session.engineData.read(id)
            val column = WolfDialogs.column(host)
            column.addView(label("${before.id} · ${before.category.label}\nGroupe ${before.bank} · Index ${before.index}"))
            val value = field("Nouvelle valeur Wolf", before.value.display) {}.apply {
                filters = arrayOf(android.text.InputFilter.LengthFilter(WolfEngineDataProtocol.MAX_TEXT_BYTES))
                if (before.value is EngineDataValue.Text) { isSingleLine = false; maxLines = 5 }
                isEnabled = !before.readOnly
            }
            column.addView(value, params(8))
            column.addView(label(if (before.value is EngineDataValue.Text) "Capacité du jeu : ${before.capacityBytes} octets. Le texte doit tenir dans cette capacité et son encodage. La valeur et le tableau sont vérifiés avant et après l’écriture."
                else "Entier signé 32 bits. La valeur et le tableau sont vérifiés avant et après l’écriture."), params(8))
            val child = WolfDialogs.builder(host, "Variable ${before.id}").setView(column)
                .setPositiveButton(if (before.readOnly) "Fermer" else "Modifier", null).setNegativeButton("Annuler", null).create()
            child.setOnShowListener {
                child.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (before.readOnly) { child.dismiss(); return@setOnClickListener }
                    val after = if (before.value is EngineDataValue.Number) {
                        val number = value.text.toString().trim().toIntOrNull()
                        if (number == null) { value.error = "Entier requis entre ${Int.MIN_VALUE} et ${Int.MAX_VALUE}."; return@setOnClickListener }
                        EngineDataValue.Number(number)
                    } else EngineDataValue.Text(value.text.toString())
                    work("Écriture et relecture de la variable…") { session.engineData.write(before, after); child.dismiss(); refreshDataPage() }
                }
            }
            present(child)
        }
    }
    private fun search(op: Int, mode: Int, type: WolfCheatType) {
        val values = try {
            val first = if (mode in listOf(0, 6)) type.parse(valueText) else 0L
            val second = if (mode == 6) type.parse(otherText) else 0L
            if (mode == 6) require(type.format(first).toBigDecimal() <= type.format(second).toBigDecimal()) { "La valeur minimale doit être inférieure ou égale à la valeur maximale." }
            first to second
        } catch (error: IllegalArgumentException) { status.text = error.message ?: "Entrez une valeur valide."; return }
        work(if (op == 1) "Recherche en mémoire…" else "Affinage des résultats…") {
            session.command(op, mode = mode, value = values.first, other = values.second, valueType = type)
            session.type = type; session.command(3, valueType = type); page = 0
        }
    }
    private fun refine(type: WolfCheatType) {
        val modes = listOf(0, 2, 3, 4, 5, 6)
        present(WolfDialogs.builder(host, "Affiner · ${type.label}").setAdapter(WolfDialogs.rows(host, listOf(
            "Égale à la valeur saisie", "A changé", "N’a pas changé", "A augmenté", "A diminué", "Dans l’intervalle saisi"
        ))) { _, position ->
            val mode = modes[position]
            if (mode == 0 || mode == 6) {
                val column = WolfDialogs.column(host)
                column.addView(field(if (mode == 6) "Valeur minimale" else "Valeur à rechercher", valueText) { valueText = it })
                if (mode == 6) column.addView(field("Valeur maximale", otherText) { otherText = it }, params(8))
                present(WolfDialogs.builder(host, if (mode == 6) "Affiner par intervalle" else "Affiner par valeur")
                    .setView(column).setPositiveButton("Affiner") { _, _ -> search(2, mode, type) }.setNegativeButton("Annuler", null).create())
            } else search(2, mode, type)
        }.setNegativeButton("Annuler", null).create())
    }
    private fun loadPage(offset: Int, type: WolfCheatType) { work("Lecture des résultats…") { session.command(3, offset = offset, valueType = type); page = offset } }
    private fun edit(address: Long, type: WolfCheatType) {
        work("Lecture de la valeur actuelle…") {
            val before = session.read(address, type)
            val pin = session.pins.firstOrNull { it.address == address && it.type == type }
            pin?.bits = before
            val column = WolfDialogs.column(host)
            column.addView(label("${hex(address)} · ${type.label}"))
            val value = field("Nouvelle valeur", type.format(before)) {}
            column.addView(value, params(8))
            column.addView(label(if (pin?.frozen == true) "Libérez cette valeur dans Valeurs suivies avant de la modifier." else "La valeur est relue avant l’écriture. Si elle diffère de celle affichée, la modification est refusée. Ouvrez alors de nouveau ce résultat."), params(8))
            val child = WolfDialogs.builder(host, pin?.name ?: "Modifier une valeur").setView(column)
                .setPositiveButton("Modifier", null).setNeutralButton(if (pin == null) "Épingler" else "Renommer", null).setNegativeButton("Fermer", null).create()
            child.setOnShowListener {
                child.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (session.pins.any { it.address == address && it.type == type && it.frozen }) { value.error = "Libérez d’abord cette valeur."; return@setOnClickListener }
                    val after = try { type.parse(value.text.toString()) } catch (error: IllegalArgumentException) { value.error = error.message; return@setOnClickListener }
                    work("Modification de la valeur…") { session.write(address, type, before, after); child.dismiss() }
                }
                child.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { namePin(address, type, before, pin) }
            }
            present(child)
        }
    }
    private fun namePin(address: Long, type: WolfCheatType, bits: Long, existing: WolfCheatSession.Pin?) {
        val column = WolfDialogs.column(host)
        val name = field("Nom de la variable suivie", existing?.name.orEmpty()) {}
        column.addView(name); column.addView(label("Ce nom est votre repère personnel. L’adresse et le gel sont limités à cette session de jeu."), params(8))
        val child = WolfDialogs.builder(host, if (existing == null) "Suivre cette valeur" else "Renommer la valeur").setView(column)
            .setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create()
        child.setOnShowListener { child.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val clean = name.text.toString().filterNot(Char::isISOControl).trim().take(48)
            if (clean.isBlank()) { name.error = "Donnez un nom à cette valeur."; return@setOnClickListener }
            val pin = session.pins.firstOrNull { it.address == address && it.type == type }
            if (pin == null) {
                if (session.pins.size >= 128) { name.error = "128 valeurs suivies maximum. Retirez une valeur avant d’en ajouter une autre."; return@setOnClickListener }
                session.pins += WolfCheatSession.Pin(address, type, clean, bits)
            } else pin.name = clean
            child.dismiss(); render()
        } }
        present(child)
    }
    private fun renderPins() {
        body.addView(label("Les gels continuent quand ce panneau est fermé et s’arrêtent à la fermeture du jeu. Rien n’est réactivé automatiquement à la prochaine session."))
        body.addView(field("Rechercher un nom ou une adresse", query) { query = it; fillPins() }, params(8))
        body.addView(enable(button("Actualiser les valeurs") {
            work("Lecture des valeurs suivies…") {
                for (pin in session.pins.toList()) {
                    if (cancelled) break
                    pin.bits = session.read(pin.address, pin.type)
                }
            }
        }), params(8))
        pinsList = LinearLayout(theme).apply { orientation = LinearLayout.VERTICAL; body.addView(this, params(8)) }
        fillPins()
        body.addView(enable(button("Annuler la dernière modification") { work("Annulation de la modification…") { session.undo() } }), params(8))
    }
    private fun fillPins() {
        val list = pinsList ?: return
        val old = (0 until list.childCount).map(list::getChildAt).toSet(); actions.removeAll(old)
        list.removeAllViews()
        val matches = session.pins.filter { it.name.contains(query, true) || hex(it.address).contains(query, true) }
        if (matches.isEmpty()) list.addView(label(if (session.pins.isEmpty()) "Aucune valeur suivie. Ouvrez un résultat puis choisissez Épingler." else "Aucune valeur ne correspond à ce nom."))
        matches.forEach { pin -> list.addView(enable(button("${pin.name}${if (pin.frozen) " · Figée" else ""}\n${pin.type.format(pin.bits)} · ${hex(pin.address)}") { pinActions(pin) }), params(8)) }
    }
    private fun pinActions(pin: WolfCheatSession.Pin) {
        val labels = listOf("Lire et modifier", "Renommer", if (pin.frozen) "Libérer la valeur" else "Figer la valeur", "Copier l’adresse", "Retirer du suivi")
        present(WolfDialogs.builder(host, pin.name).setAdapter(WolfDialogs.rows(host, labels)) { _, action ->
            when (action) {
                0 -> edit(pin.address, pin.type)
                1 -> namePin(pin.address, pin.type, pin.bits, pin)
                2 -> if (pin.frozen) work("Libération de la valeur…") { session.freeze(pin, false) }
                    else present(WolfDialogs.builder(host, "Figer « ${pin.name} » ?")
                        .setMessage("Sa valeur actuelle sera maintenue pendant cette session, même si vous fermez ce panneau. Vous pourrez la libérer ici.")
                        .setPositiveButton("Figer") { _, _ -> work("Activation du gel…") { pin.bits = session.read(pin.address, pin.type); session.freeze(pin, true) } }
                        .setNegativeButton("Annuler", null).create())
                3 -> (host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(pin.name, hex(pin.address)))
                4 -> work("Retrait du suivi…") { if (pin.frozen) session.freeze(pin, false); session.pins.remove(pin) }
            }
        }.setNegativeButton("Fermer", null).create())
    }
    private fun hex(address: Long) = "0x${address.toString(16).uppercase()}"
}
