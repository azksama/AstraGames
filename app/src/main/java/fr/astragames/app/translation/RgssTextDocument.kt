package fr.astragames.app.translation

import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.IdentityHashMap

/** RGSS data, never Scripts or arbitrary Ruby: traverse only the standard RPG object schema. */
internal class RgssTextDocument(private val filename: String, private val original: ByteArray) : GameTextDocument {
    private data class Slot(val entry: RpgTextDocument.Entry, val value: MarshalValue.StringValue, val charset: Charset, val write: (MarshalValue) -> Unit)
    private val root = Marshal.load(original)
    private val slots = mutableListOf<Slot>()
    override val entries get() = slots.map { it.entry }
    init {
        require(original.size <= RpgTextDocument.MAX_FILE_BYTES)
        val seen = IdentityHashMap<MarshalValue, Boolean>()
        fun visit(node: MarshalValue, path: String, depth: Int) {
            require(depth <= 128 && seen.size <= 500_000) { "Données RGSS trop complexes." }
            if (seen.put(node, true) != null) return
            when (node) {
                is MarshalValue.ArrayValue -> node.items.forEachIndexed { i, child -> visit(child, "$path/$i", depth + 1) }
                is MarshalValue.HashValue -> node.entries.forEach { (key, child) ->
                    if (key is MarshalValue.IntValue) visit(child, "$path/${key.value}", depth + 1)
                }
                is MarshalValue.ObjectValue -> {
                    val name = node.className.name
                    if (name == "RPG::EventCommand") command(node, path)
                    val fields = when (name) {
                        "RPG::Actor" -> listOf("@name", "@nickname", "@description")
                        "RPG::Class", "RPG::Enemy" -> listOf("@name")
                        "RPG::Item", "RPG::Weapon", "RPG::Armor" -> listOf("@name", "@description")
                        "RPG::Skill" -> listOf("@name", "@description", "@message1", "@message2")
                        "RPG::State" -> listOf("@name", "@message1", "@message2", "@message3", "@message4")
                        "RPG::Map" -> listOf("@display_name")
                        "RPG::System" -> listOf("@game_title", "@currency_unit")
                        "RPG::System::Words" -> node.ivars.keys.map { it.name }
                        else -> emptyList()
                    }
                    fields.forEach { field -> field(node, field, path, if (name == "RPG::Actor" && field == "@name") integer(node, "@id") else null) }
                    if (name == "RPG::System") {
                        listOf("@elements", "@skill_types", "@weapon_types", "@armor_types", "@equip_types").forEach { key -> strings(value(node, key), "$path/$key") }
                    }
                    // These container edges are structural. Unknown object contents stay untouched.
                    val children = when (name) {
                        "RPG::Map" -> listOf("@events")
                        "RPG::Event", "RPG::Troop" -> listOf("@pages")
                        "RPG::Event::Page", "RPG::Troop::Page", "RPG::CommonEvent" -> listOf("@list")
                        "RPG::System" -> listOf("@words", "@terms")
                        else -> emptyList()
                    }
                    children.forEach { key -> value(node, key)?.let { visit(it, "$path/$key", depth + 1) } }
                    if (name == "RPG::System::Terms") listOf("@basic", "@params", "@etypes", "@commands").forEach { key -> strings(value(node, key), "$path/$key") }
                }
                else -> Unit
            }
        }
        visit(root, "", 0)
        require(slots.size <= 200_000)
    }

    private fun value(obj: MarshalValue.ObjectValue, key: String) = obj.ivars[MarshalValue.SymbolValue(key)]
    private fun integer(obj: MarshalValue.ObjectValue, key: String) = (value(obj, key) as? MarshalValue.IntValue)?.value?.toInt()
    private fun field(obj: MarshalValue.ObjectValue, key: String, path: String, actor: Int? = null) {
        slot(value(obj, key), "$path/$key", actor) { obj.ivars[MarshalValue.SymbolValue(key)] = it }
    }
    private fun strings(value: MarshalValue?, path: String) {
        val array = value as? MarshalValue.ArrayValue ?: return
        array.items.forEachIndexed { index, child -> slot(child, "$path/$index") { array.items[index] = it } }
    }
    private fun command(obj: MarshalValue.ObjectValue, path: String) {
        val parameters = value(obj, "@parameters") as? MarshalValue.ArrayValue ?: return
        fun number(index: Int) = (parameters.items.getOrNull(index) as? MarshalValue.IntValue)?.value?.toInt()
        fun text(index: Int, actor: Int? = null, reference: Boolean = false) {
            slot(parameters.items.getOrNull(index), "$path/@parameters/$index", actor, reference) { parameters.items[index] = it }
        }
        when (integer(obj, "@code")) {
            401, 405 -> text(0)
            101 -> if (filename.endsWith(".rxdata")) text(0) // XP's first message is in 101; VX's face path is opaque.
            102 -> strings(parameters.items.getOrNull(0), "$path/@parameters/0")
            402, 324 -> text(1)
            320 -> text(1, number(0))
            111 -> if (number(0) == 4 && number(2) == 1) text(3, number(1), true)
        }
    }
    private fun slot(node: MarshalValue?, path: String, actor: Int? = null, reference: Boolean = false, write: (MarshalValue) -> Unit) {
        val value = node as? MarshalValue.StringValue ?: return
        val candidates = if (value.ivars.isNotEmpty()) listOfNotNull(value.charset) else listOf(Charsets.UTF_8, Charset.forName("windows-31j"))
        val decoded = candidates.firstNotNullOfOrNull { charset -> runCatching {
            val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value.bytes)).toString()
            require(text.toByteArray(charset).contentEquals(value.bytes)); text to charset
        }.getOrNull() } ?: return
        if (decoded.first.isBlank()) return
        val group = actor?.takeIf { it > 0 }?.let { "actor-name:$it:${textHash(decoded.first.toByteArray(Charsets.UTF_8))}" }
        slots += Slot(RpgTextDocument.Entry(path, decoded.first, group, reference), value, decoded.second, write)
    }
    override fun translatedEntries(dictionary: Map<String, String>): ByteArray {
        val paths = slots.mapTo(hashSetOf()) { it.entry.path }
        require(dictionary.keys.all { it in paths }) { "Emplacement RGSS inconnu." }
        if (slots.none { dictionary[it.entry.path]?.let { text -> text != it.entry.text } == true }) return original.copyOf()
        slots.forEach { slot ->
            val text = dictionary[slot.entry.path] ?: slot.entry.text
            if (text != slot.entry.text) {
                require('\u0000' !in text && ProtectedText.controls(text) == ProtectedText.controls(slot.entry.text)) { "Commande RGSS altérée : ${slot.entry.path}" }
                val encoded = slot.charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
                val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
                val replacement = MarshalValue.StringValue(bytes, LinkedHashMap(slot.value.ivars))
                replacement.attributes.putAll(slot.value.attributes)
                slot.write(replacement)
            }
        }
        val result = Marshal.dump(root)
        require(result.size <= RpgTextDocument.MAX_FILE_BYTES)
        val after = RgssTextDocument(filename, result).entries.associateBy { it.path }
        slots.forEach { check(after[it.entry.path]?.text == (dictionary[it.entry.path] ?: it.entry.text)) { "Vérification RGSS échouée." } }
        return result
    }
}
