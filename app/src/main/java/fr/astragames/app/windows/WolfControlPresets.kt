package fr.astragames.app.windows

import android.content.Context
import org.json.JSONObject

/** Shared across games, private to Astra, independent of game save data. */
internal class WolfControlPresets(context: Context) {
    private val prefs = context.getSharedPreferences("wolf_control_presets", Context.MODE_PRIVATE)
    private fun data() = runCatching { JSONObject(prefs.getString("presets", "{}")!!) }.getOrDefault(JSONObject())
    fun names() = data().keys().asSequence().sortedWith(String.CASE_INSENSITIVE_ORDER).take(WolfControlLayout.MAX_PRESETS).toList()
    fun read(name: String) = data().optJSONObject(name)?.takeIf { it.optInt("version", 1) == 1 }?.let(WolfControlLayout::normalized)
    fun save(name: String, layout: JSONObject) {
        val clean = WolfControlLayout.presetName(name)
        require(clean.isNotBlank()) { "Donnez un nom au preset." }
        val data = data()
        require(data.has(clean) || data.length() < WolfControlLayout.MAX_PRESETS) { "32 presets enregistrés. Supprimez-en un avant d’en ajouter un autre." }
        prefs.edit().putString("presets", data.put(clean, WolfControlLayout.normalized(layout)).toString()).apply()
    }
    fun delete(name: String) { val data = data(); data.remove(name); prefs.edit().putString("presets", data.toString()).apply() }
}
