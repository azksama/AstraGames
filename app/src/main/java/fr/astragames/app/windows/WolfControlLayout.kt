package fr.astragames.app.windows

import com.winlator.xserver.XKeycode
import org.json.JSONArray
import org.json.JSONObject

internal data class WolfCustomControl(val id: String, val label: String, val code: XKeycode)
internal enum class WolfMovementMode { DPAD, JOYSTICK }

/** A portable controls-only record. Loading it never changes the runtime, storage or graphics. */
internal object WolfControlLayout {
    const val MAX_CUSTOM = 24
    const val MAX_PRESETS = 32
    val standardIds = setOf("up", "left", "right", "down", "accept", "back", "shift")
    val directionIds = setOf("up", "left", "right", "down")
    private val customId = Regex("custom_[a-f0-9]{32}")
    fun validId(id: String) = id == "joystick" || id in standardIds || customId.matches(id)
    fun movement(data: JSONObject) = runCatching { WolfMovementMode.valueOf(data.optString("movementMode")) }.getOrDefault(WolfMovementMode.DPAD)
    fun size(data: JSONObject, id: String) = data.optInt("size_$id", 100).coerceIn(50, 200)
    fun opacity(data: JSONObject, id: String, fallback: Int) = data.optInt("opacity_$id", fallback).coerceIn(15, 100)
    fun label(value: String) = value.filterNot(Char::isISOControl).trim().take(16)
    fun presetName(value: String) = value.filterNot(Char::isISOControl).trim().take(48)

    fun custom(data: JSONObject): List<WolfCustomControl> {
        val rows = data.optJSONArray("customControls") ?: return emptyList()
        val ids = mutableSetOf<String>()
        return (0 until minOf(rows.length(), MAX_CUSTOM)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            val code = WolfKeyboard.parse(row.optString("code")) ?: return@mapNotNull null
            val label = label(row.optString("label"))
            if (!customId.matches(id) || !ids.add(id) || label.isBlank()) null else WolfCustomControl(id, label, code)
        }
    }
    fun encodeCustom(controls: List<WolfCustomControl>) = JSONArray().apply {
        controls.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("code", it.code.name)) }
    }
    fun normalized(source: JSONObject): JSONObject {
        val controls = custom(source)
        return JSONObject().put("version", 2).put("customControls", encodeCustom(controls))
            .put("movementMode", movement(source).name)
            .put("opacity", source.optInt("opacity", 75).coerceIn(15, 100))
            .put("size", source.optInt("size", 100).coerceIn(70, 150)).apply {
                (standardIds + "joystick" + controls.map { it.id }).forEach { id ->
                    if (id != "joystick") WolfKeyboard.parse(source.optString("key_$id"))?.let { put("key_$id", it.name) }
                    if (source.has("size_$id")) put("size_$id", size(source, id))
                    if (source.has("opacity_$id")) put("opacity_$id", opacity(source, id, 75))
                    listOf(false, true).forEach { landscape ->
                        val name = "position_${landscape}_$id"
                        source.optJSONArray(name)?.let { position ->
                            val x = position.optDouble(0); val y = position.optDouble(1)
                            if (x.isFinite() && y.isFinite()) put(name, JSONArray().put(x.coerceIn(0.0, 1.0)).put(y.coerceIn(0.0, 1.0)))
                        }
                    }
                }
            }
    }
    fun clear(data: JSONObject) {
        data.keys().asSequence().filter { it.startsWith("position_") || it.startsWith("key_") || it.startsWith("size_") || it.startsWith("opacity_") }.toList().forEach(data::remove)
        data.remove("customControls"); data.remove("size"); data.remove("opacity"); data.remove("movementMode")
    }
    fun apply(data: JSONObject, layout: JSONObject) {
        require(layout.optInt("version", 1) in 1..2) { "Version de preset inconnue" }
        val validated = normalized(layout)
        clear(data)
        validated.keys().asSequence().filter { it != "version" }.forEach { data.put(it, validated.get(it)) }
    }
}
