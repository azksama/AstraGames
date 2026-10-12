package fr.astragames.app.windows

import com.winlator.xserver.XKeycode
import org.json.JSONArray
import org.json.JSONObject

internal enum class WolfControlActionMode { HOLD, TOGGLE, MACRO }
internal data class WolfMacroStep(val keys: List<XKeycode>, val durationMs: Int)
internal data class WolfControlAction(
    val keys: List<XKeycode>,
    val mode: WolfControlActionMode = WolfControlActionMode.HOLD,
    val steps: List<WolfMacroStep> = emptyList(),
) {
    companion object {
        const val MAX_KEYS = 4
        const val MAX_STEPS = 16
        const val MIN_STEP_MS = 20
        const val MAX_STEP_MS = 2000
        const val MAX_MACRO_MS = 10_000

        fun validate(action: WolfControlAction) {
            require(action.keys.isNotEmpty() && action.keys.size <= MAX_KEYS && action.keys.distinct().size == action.keys.size && action.keys.all(WolfKeyboard::valid)) { "Choisissez de 1 à 4 touches distinctes." }
            require(action.mode == WolfControlActionMode.MACRO || action.steps.isEmpty()) { "Seules les séquences peuvent contenir des étapes." }
            if (action.mode == WolfControlActionMode.MACRO) {
                require(action.steps.size in 1..MAX_STEPS) { "Une séquence contient de 1 à 16 étapes." }
                require(action.steps.all { it.keys.size <= MAX_KEYS && it.keys.distinct().size == it.keys.size && it.keys.all(WolfKeyboard::valid) && it.durationMs in MIN_STEP_MS..MAX_STEP_MS }) { "Chaque étape dure de 20 à 2000 ms et contient au plus 4 touches." }
                require(action.steps.sumOf { it.durationMs.toLong() } <= MAX_MACRO_MS) { "Une séquence dure au plus 10 secondes." }
            }
        }

        fun encode(action: WolfControlAction): JSONObject {
            validate(action)
            return JSONObject().put("keys", JSONArray(action.keys.map { it.name })).put("mode", action.mode.name).apply {
                if (action.mode == WolfControlActionMode.MACRO) put("steps", JSONArray().apply {
                    action.steps.forEach { put(JSONObject().put("keys", JSONArray(it.keys.map { key -> key.name })).put("durationMs", it.durationMs)) }
                })
            }
        }

        fun decode(row: JSONObject): WolfControlAction {
            require(row.keys().asSequence().all { it in setOf("keys", "mode", "steps") }) { "Champ d’action inconnu." }
            fun keys(array: JSONArray): List<XKeycode> {
                require(array.length() <= MAX_KEYS) { "4 touches maximum par combinaison." }
                return (0 until array.length()).map { WolfKeyboard.parseValue(array.get(it)) ?: throw IllegalArgumentException("Touche clavier inconnue.") }
            }
            val actionKeys = keys(row.getJSONArray("keys"))
            val mode = runCatching { WolfControlActionMode.valueOf(row.getString("mode")) }.getOrElse { throw IllegalArgumentException("Mode de bouton inconnu.") }
            val rows = row.optJSONArray("steps")
            require(!row.has("steps") || rows != null) { "Étapes de séquence invalides." }
            require(rows == null || rows.length() <= MAX_STEPS) { "16 étapes maximum." }
            val steps = if (rows == null) emptyList() else (0 until rows.length()).map { index ->
                val step = rows.getJSONObject(index)
                require(step.keys().asSequence().all { it in setOf("keys", "durationMs") }) { "Champ d’étape inconnu." }
                WolfMacroStep(keys(step.getJSONArray("keys")), WolfControlPresetCodec.integer(step.get("durationMs"), MIN_STEP_MS, MAX_STEP_MS, "Durée d’étape"))
            }
            return WolfControlAction(actionKeys, mode, steps).also(::validate)
        }
    }
}

/** An input remains down until its last owner releases it, including overlapping combos. */
internal class WolfHeldKeys(private val emit: (XKeycode, Boolean) -> Unit) {
    private val owners = linkedMapOf<String, List<XKeycode>>()
    fun active(owner: String) = owner in owners
    fun set(owner: String, keys: List<XKeycode>) {
        require(keys.size <= WolfControlAction.MAX_KEYS && keys.all(WolfKeyboard::valid))
        mutate { if (keys.isEmpty()) owners.remove(owner) else owners[owner] = WolfKeyboard.comboOrder(keys.distinct()) }
    }
    fun release(owner: String) = set(owner, emptyList())
    fun replaceGroup(prefix: String, values: Map<String, List<XKeycode>>) {
        require(values.keys.all { it.startsWith(prefix) } && values.values.all { it.size <= WolfControlAction.MAX_KEYS && it.all(WolfKeyboard::valid) })
        mutate {
            owners.keys.filter { it.startsWith(prefix) }.toList().forEach(owners::remove)
            values.filterValues { it.isNotEmpty() }.forEach { (id, keys) -> owners[id] = WolfKeyboard.comboOrder(keys.distinct()) }
        }
    }
    fun releaseAll() = mutate { owners.clear() }
    private fun mutate(change: () -> Unit) {
        val before = owners.values.flatten().distinct()
        change()
        val after = owners.values.flatten().distinct()
        before.asReversed().filter { it !in after }.forEach { emit(it, false) }
        after.filter { it !in before }.forEach { emit(it, true) }
    }
}
