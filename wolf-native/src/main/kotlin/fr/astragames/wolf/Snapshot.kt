package fr.astragames.wolf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

/** Astra-owned format. Never written over the game's Windows save files. */
internal object WolfSnapshot {
    private const val MAGIC = 0x41574e53 // AWNS
    private const val VERSION = 2
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    data class State(
        val title: String, val ticks: Long, val mapId: Int,
        val x: Float, val y: Float, val direction: Int, val path: String, val randomState: Int,
        val numbers: Map<Int, Int>, val strings: Map<Int, String>,
        val self: Map<Triple<Int, Int, Int>, Int>, val common: Map<Pair<Int, Int>, Int>,
        val commonStrings: Map<Pair<Int, Int>, String>, val system: Map<Int, Int>, val systemStrings: Map<Int, String>,
        val mutableNumbers: Map<Triple<Int, Int, Int>, Int>, val mutableStrings: Map<Triple<Int, Int, Int>, String>,
        val hero: CharacterState? = null, val actors: List<CharacterState> = emptyList(),
        val erasedEvents: Set<Int> = emptySet(), val pictures: List<NativePicture> = emptyList(),
        val allowMovement: Boolean = false, val freezeWorld: Boolean = false,
        val bannedKeys: Set<NativeKey> = emptySet(),
    )
    data class CharacterState(val id: Int, val x: Float, val y: Float, val path: String,
        val direction: Int, val pattern: Int, val opacity: Int, val flags: Int,
        val speed: Int, val tileId: Int, val targetX: Float, val targetY: Float, val moveTicks: Int)

    fun encode(state: State): ByteArray {
        val output = LimitedOutput()
        DataOutputStream(output).use { writer ->
            writer.writeInt(MAGIC); writer.writeInt(VERSION)
            writer.text(state.title); writer.writeLong(state.ticks); writer.writeInt(state.mapId)
            writer.writeFloat(state.x); writer.writeFloat(state.y); writer.writeInt(state.direction)
            writer.text(state.path); writer.writeInt(state.randomState)
            writer.numberMap(state.numbers); writer.stringMap(state.strings)
            writer.tripleNumberMap(state.self); writer.pairNumberMap(state.common)
            writer.pairStringMap(state.commonStrings); writer.numberMap(state.system); writer.stringMap(state.systemStrings)
            writer.tripleNumberMap(state.mutableNumbers); writer.tripleStringMap(state.mutableStrings)
            writer.writeBoolean(state.hero != null); state.hero?.let { writer.character(it) }
            writer.count(state.actors.size); state.actors.forEach { writer.character(it) }
            writer.count(state.erasedEvents.size); state.erasedEvents.sorted().forEach(writer::writeInt)
            writer.count(state.pictures.size); state.pictures.forEach { writer.picture(it) }
            writer.writeBoolean(state.allowMovement); writer.writeBoolean(state.freezeWorld)
            writer.count(state.bannedKeys.size); state.bannedKeys.sortedBy { it.ordinal }.forEach { writer.writeInt(it.ordinal) }
        }
        val body = output.toByteArray()
        require(body.size <= MAX_BYTES - 8) { "Sauvegarde native trop grande" }
        val checked = ByteArrayOutputStream()
        DataOutputStream(checked).use { writer -> writer.writeInt(body.size); writer.write(body); writer.writeInt(CRC32().apply { update(body) }.value.toInt()) }
        return checked.toByteArray()
    }

    fun decode(bytes: ByteArray, expectedTitle: String): State {
        require(bytes.size in 12..MAX_BYTES) { "Taille de sauvegarde native invalide" }
        val outer = DataInputStream(ByteArrayInputStream(bytes))
        val size = outer.readInt()
        require(size == bytes.size - 8 && size >= 8) { "Sauvegarde native tronquée" }
        val body = ByteArray(size).also(outer::readFully)
        require(outer.readInt() == CRC32().apply { update(body) }.value.toInt()) { "Somme de contrôle de sauvegarde incorrecte" }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC) { "Format de sauvegarde native non pris en charge" }
        val version = input.readInt(); require(version in 1..VERSION) { "Version de sauvegarde native non prise en charge" }
        val title = input.text()
        require(title == expectedTitle) { "Sauvegarde d'un autre jeu" }
        val ticks = input.readLong(); val map = input.readInt()
        val x = input.readFloat(); val y = input.readFloat(); val direction = input.readInt()
        val path = input.text(); val random = input.readInt()
        require(ticks >= 0 && map >= 0 && x.isFinite() && y.isFinite() && direction in 1..9) { "État de sauvegarde invalide" }
        var state = State(title, ticks, map, x, y, direction, path, random,
            input.numberMap(), input.stringMap(), input.tripleNumberMap(), input.pairNumberMap(), input.pairStringMap(),
            input.numberMap(), input.stringMap(), input.tripleNumberMap(), input.tripleStringMap())
        if (version >= 2) {
            val hero = if (input.readBoolean()) input.character() else null
            val actors = buildList { repeat(input.count(48)) { add(input.character()) } }
            require(actors.map { it.id }.distinct().size == actors.size) { "Événements de sauvegarde dupliqués" }
            val erased = buildSet { repeat(input.count(4)) { require(add(input.readInt())) { "Événement effacé dupliqué" } } }
            val pictures = buildList { repeat(input.count(68)) { add(input.picture()) } }
            require(pictures.map { it.id }.distinct().size == pictures.size) { "Images de sauvegarde dupliquées" }
            val movement = input.readBoolean(); val frozen = input.readBoolean()
            val banned = buildSet { repeat(input.count(4)) { val key = NativeKey.entries.getOrNull(input.readInt()) ?: error("Touche de sauvegarde invalide"); require(add(key)); } }
            state = state.copy(hero = hero, actors = actors, erasedEvents = erased, pictures = pictures, allowMovement = movement, freezeWorld = frozen, bannedKeys = banned)
        }
        require(input.available() == 0) { "Données de sauvegarde supplémentaires" }
        return state
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_000_000) { "Chaîne de sauvegarde trop grande" }
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..1_000_000 && size <= available()) { "Chaîne de sauvegarde tronquée" }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
    private fun DataOutputStream.count(size: Int) { require(size in 0..MAX_ENTRIES); writeInt(size) }
    private fun DataInputStream.count(minimumBytes: Int): Int = readInt().also { require(it in 0..MAX_ENTRIES && it.toLong() * minimumBytes <= available()) { "Compte de sauvegarde invalide" } }
    private fun DataOutputStream.numberMap(map: Map<Int, Int>) { count(map.size); map.toSortedMap().forEach { (k, v) -> writeInt(k); writeInt(v) } }
    private fun DataInputStream.numberMap(): Map<Int, Int> = buildMap { repeat(count(8)) { val k = readInt(); require(k !in this); put(k, readInt()) } }
    private fun DataOutputStream.stringMap(map: Map<Int, String>) { count(map.size); map.toSortedMap().forEach { (k, v) -> writeInt(k); text(v) } }
    private fun DataInputStream.stringMap(): Map<Int, String> = buildMap { repeat(count(8)) { val k = readInt(); require(k !in this); put(k, text()) } }
    private fun DataOutputStream.pairNumberMap(map: Map<Pair<Int, Int>, Int>) { count(map.size); map.forEach { (k, v) -> writeInt(k.first); writeInt(k.second); writeInt(v) } }
    private fun DataInputStream.pairNumberMap(): Map<Pair<Int, Int>, Int> = buildMap { repeat(count(12)) { val k = readInt() to readInt(); require(k !in this); put(k, readInt()) } }
    private fun DataOutputStream.pairStringMap(map: Map<Pair<Int, Int>, String>) { count(map.size); map.forEach { (k, v) -> writeInt(k.first); writeInt(k.second); text(v) } }
    private fun DataInputStream.pairStringMap(): Map<Pair<Int, Int>, String> = buildMap { repeat(count(12)) { val k = readInt() to readInt(); require(k !in this); put(k, text()) } }
    private fun DataOutputStream.tripleNumberMap(map: Map<Triple<Int, Int, Int>, Int>) { count(map.size); map.forEach { (k, v) -> writeInt(k.first); writeInt(k.second); writeInt(k.third); writeInt(v) } }
    private fun DataInputStream.tripleNumberMap(): Map<Triple<Int, Int, Int>, Int> = buildMap { repeat(count(16)) { val k = Triple(readInt(), readInt(), readInt()); require(k !in this); put(k, readInt()) } }
    private fun DataOutputStream.tripleStringMap(map: Map<Triple<Int, Int, Int>, String>) { count(map.size); map.forEach { (k, v) -> writeInt(k.first); writeInt(k.second); writeInt(k.third); text(v) } }
    private fun DataInputStream.tripleStringMap(): Map<Triple<Int, Int, Int>, String> = buildMap { repeat(count(16)) { val k = Triple(readInt(), readInt(), readInt()); require(k !in this); put(k, text()) } }
    private fun DataOutputStream.character(c: CharacterState) {
        writeInt(c.id); writeFloat(c.x); writeFloat(c.y); text(c.path); writeInt(c.direction); writeInt(c.pattern)
        writeInt(c.opacity); writeInt(c.flags); writeInt(c.speed); writeInt(c.tileId)
        writeFloat(c.targetX); writeFloat(c.targetY); writeInt(c.moveTicks)
    }
    private fun DataInputStream.character(): CharacterState {
        val c = CharacterState(readInt(), readFloat(), readFloat(), text(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readFloat(), readFloat(), readInt())
        require(c.x.isFinite() && c.y.isFinite() && c.targetX.isFinite() && c.targetY.isFinite() && c.direction in 1..9 && c.direction != 5 && c.pattern in 0..16 && c.opacity in 0..255 && c.speed in 0..6 && c.moveTicks in 0..600) { "État de personnage invalide" }
        return c
    }
    private fun DataOutputStream.optionalText(s: String?) { writeBoolean(s != null); s?.let { text(it) } }
    private fun DataInputStream.optionalText(): String? = if (readBoolean()) text() else null
    private fun DataOutputStream.picture(p: NativePicture) {
        writeInt(p.id); optionalText(p.path); optionalText(p.text)
        writeFloat(p.x); writeFloat(p.y); writeFloat(p.width); writeFloat(p.height)
        writeFloat(p.scaleX); writeFloat(p.scaleY); writeInt(p.opacity); writeFloat(p.rotation)
        writeInt(p.blend); writeInt(p.z); writeInt(p.color); writeInt(p.anchor); writeFloat(p.fontSize); writeInt(p.fontColor); writeBoolean(p.screenRelative)
        writeBoolean(p.sourceRect != null); p.sourceRect?.let { writeFloat(it.x); writeFloat(it.y); writeFloat(it.width); writeFloat(it.height) }
    }
    private fun DataInputStream.picture(): NativePicture {
        val id = readInt(); val path = optionalText(); val text = optionalText()
        val x = readFloat(); val y = readFloat(); val width = readFloat(); val height = readFloat()
        val sx = readFloat(); val sy = readFloat(); val opacity = readInt(); val rotation = readFloat()
        val blend = readInt(); val z = readInt(); val color = readInt(); val anchor = readInt(); val fontSize = readFloat(); val fontColor = readInt(); val relative = readBoolean()
        val rect = if (readBoolean()) NativeRect(readFloat(), readFloat(), readFloat(), readFloat()) else null
        require(listOf(x, y, width, height, sx, sy, rotation, fontSize).all { it.isFinite() } && width in 0f..16384f && height in 0f..16384f && opacity in 0..255 && blend in 0..3 && anchor in 0..1 && fontSize in 0f..1024f) { "Image de sauvegarde invalide" }
        require(rect == null || listOf(rect.x, rect.y, rect.width, rect.height).all { it.isFinite() } && rect.width in 0f..16384f && rect.height in 0f..16384f) { "Zone d'image de sauvegarde invalide" }
        return NativePicture(id, path, text, x, y, width, height, sx, sy, opacity, rotation, blend, z, rect, color, anchor, fontSize, fontColor, relative)
    }
    /** Abort while writing, before a save with many large strings can grow without bound. */
    private class LimitedOutput : ByteArrayOutputStream() {
        override fun write(b: Int) { require(count < MAX_BYTES - 8) { "Sauvegarde native trop grande" }; super.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { require(len >= 0 && count.toLong() + len <= MAX_BYTES - 8) { "Sauvegarde native trop grande" }; super.write(b, off, len) }
    }
}
