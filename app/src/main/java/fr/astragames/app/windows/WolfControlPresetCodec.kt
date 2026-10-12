package fr.astragames.app.windows

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.charset.StandardCharsets

internal data class WolfControlPresetDocument(val name: String, val layout: JSONObject)

/** Only controls cross this boundary. No game paths, scripts, runtime settings or save data. */
internal object WolfControlPresetCodec {
    const val FORMAT = "astra-wolf-controls"
    const val VERSION = 1
    const val MAX_BYTES = 256 * 1024
    private const val MAX_DEPTH = 16

    fun encode(name: String, layout: JSONObject): String {
        val clean = WolfControlLayout.presetName(name)
        require(clean.isNotBlank()) { "Donnez un nom au preset." }
        val normalized = WolfControlLayout.normalized(layout)
        validateLayout(normalized)
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("name", clean).put("layout", normalized).toString(2)
            .also { require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "Preset trop volumineux." } }
    }

    fun decode(text: String): WolfControlPresetDocument {
        require(text.length <= MAX_BYTES && text.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "Un preset ne peut pas dépasser 256 Kio." }
        val source = text.removePrefix("\uFEFF")
        checkDepth(source)
        return try {
            val tokener = JSONTokener(source)
            val root = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException("Le preset doit être un objet JSON.")
            require(tokener.nextClean() == '\u0000') { "Données ajoutées après le preset." }
            require(root.keys().asSequence().all { it in setOf("format", "version", "name", "layout") }) { "Champ de document inconnu." }
            require(root.getString("format") == FORMAT) { "Ce fichier n’est pas un preset de contrôles Wolf Astra." }
            require(integer(root.get("version"), VERSION, VERSION, "Version de document") == VERSION)
            val originalName = root.get("name") as? String ?: throw IllegalArgumentException("Nom du preset invalide.")
            val name = WolfControlLayout.presetName(originalName)
            require(name.isNotBlank() && name == originalName) { "Le nom doit contenir de 1 à 48 caractères sans caractères de contrôle." }
            val layout = root.getJSONObject("layout")
            validateLayout(layout)
            WolfControlPresetDocument(name, WolfControlLayout.normalized(layout))
        } catch (error: IllegalArgumentException) { throw error }
        catch (error: Exception) { throw IllegalArgumentException("Preset JSON invalide : ${error.message?.take(120).orEmpty()}", error) }
    }

    internal fun integer(value: Any, min: Int, max: Int, label: String): Int {
        require(value is Number) { "$label : nombre entier requis." }
        val number = value.toDouble()
        require(number.isFinite() && number % 1.0 == 0.0 && number >= min && number <= max) { "$label doit être compris entre $min et $max." }
        return number.toInt()
    }

    private fun checkDepth(source: String) {
        var depth = 0; var quoted = false; var escaped = false
        source.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= MAX_DEPTH) { "Preset JSON trop imbriqué." } }
                '}', ']' -> depth--
            }
        }
        require(!quoted && depth == 0) { "Preset JSON incomplet." }
    }

    internal fun validateLayout(layout: JSONObject) {
        val version = if (layout.has("version")) integer(layout.get("version"), 1, WolfControlLayout.VERSION, "Version de disposition") else 1
        val custom = layout.optJSONArray("customControls")
        require(!layout.has("customControls") || custom != null) { "Liste de boutons invalide." }
        require(custom == null || custom.length() <= WolfControlLayout.MAX_CUSTOM) { "24 boutons personnalisés maximum." }
        val customIds = mutableSetOf<String>()
        if (custom != null) for (index in 0 until custom.length()) {
            val row = custom.getJSONObject(index)
            require(row.keys().asSequence().all { it in setOf("id", "label", "code") }) { "Champ de bouton inconnu." }
            val id = row.getString("id")
            require(WolfControlLayout.customIdValid(id) && customIds.add(id)) { "Identifiant de bouton invalide ou dupliqué." }
            val label = row.get("label") as? String ?: throw IllegalArgumentException("Nom de bouton invalide.")
            require(label.isNotBlank() && WolfControlLayout.label(label) == label) { "Le nom du bouton doit contenir de 1 à 16 caractères sans caractères de contrôle." }
            require(WolfKeyboard.parseValue(row.get("code")) != null) { "Touche de bouton inconnue." }
        }
        val ids = WolfControlLayout.standardIds + "joystick" + customIds
        layout.keys().asSequence().forEach { key ->
            when {
                key == "version" || key == "customControls" -> Unit
                key == "movementMode" -> require(layout.get("movementMode") is String && runCatching { WolfMovementMode.valueOf(layout.getString(key)) }.isSuccess) { "Mode de déplacement inconnu." }
                key == "opacity" -> integer(layout.get(key), 15, 100, "Opacité")
                key == "size" -> integer(layout.get(key), 70, 150, "Taille globale")
                key.startsWith("key_") -> require(key.removePrefix("key_") in ids - "joystick" && WolfKeyboard.parseValue(layout.get(key)) != null) { "Touche ou identifiant de contrôle inconnu." }
                key.startsWith("action_") -> {
                    require(version >= 3 && key.removePrefix("action_") in ids - "joystick") { "Action ou version de contrôle inconnue." }
                    WolfControlAction.decode(layout.getJSONObject(key))
                }
                key.startsWith("size_") -> { require(key.removePrefix("size_") in ids) { "Identifiant de taille inconnu." }; integer(layout.get(key), 50, 200, "Taille individuelle") }
                key.startsWith("opacity_") -> { require(key.removePrefix("opacity_") in ids) { "Identifiant d’opacité inconnu." }; integer(layout.get(key), 15, 100, "Opacité individuelle") }
                key.startsWith("position_false_") || key.startsWith("position_true_") -> {
                    val id = key.removePrefix("position_false_").removePrefix("position_true_")
                    require(id in ids) { "Identifiant de position inconnu." }
                    val position = layout.getJSONArray(key)
                    require(position.length() == 2) { "Une position contient exactement deux nombres." }
                    for (index in 0..1) {
                        val value = position.get(index)
                        require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..1.0) { "Position invalide : nombre entre 0 et 1 requis." }
                    }
                }
                else -> throw IllegalArgumentException("Champ de disposition inconnu : ${key.take(64)}")
            }
        }
    }
}
