package fr.astragames.app.wolfnative

/** Bounded OpenType name-table reading, including every face in a TTC. */
internal object WolfNativeFontInfo {
    data class Face(val index: Int, val names: Set<String>)

    fun faces(bytes: ByteArray): List<Face> {
        fun range(offset: Long, length: Long): Int {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "table de police hors fichier" }
            return offset.toInt()
        }
        fun u16(offset: Int): Int {
            range(offset.toLong(), 2)
            return ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        }
        fun u32(offset: Int): Long = (u16(offset).toLong() shl 16) or u16(offset + 2).toLong()
        fun tag(offset: Int) = String(bytes, range(offset.toLong(), 4), 4, Charsets.ISO_8859_1)
        val offsets = if (tag(0) == "ttcf") {
            require(u32(4) == 0x10000L || u32(4) == 0x20000L) { "version TTC inconnue" }
            val count = u32(8)
            require(count in 1..256) { "nombre de faces TTC invalide" }
            range(12, count * 4)
            (0 until count.toInt()).map { range(u32(12 + it * 4), 12) }
        } else listOf(0)
        return offsets.mapIndexed { index, face ->
            require(u32(face) == 0x10000L || tag(face) in setOf("OTTO", "true", "typ1")) { "format de police absent ou inconnu" }
            val count = u16(face + 4)
            require(count in 1..256) { "nombre de tables de police invalide" }
            range(face.toLong() + 12, count * 16L)
            var names = emptySet<String>()
            for (table in 0 until count) {
                val record = face + 12 + table * 16
                if (tag(record) != "name") continue
                val length = u32(record + 12)
                val start = range(u32(record + 8), length)
                require(length >= 6 && u16(start) in 0..1) { "table name invalide" }
                val records = u16(start + 2)
                val strings = u16(start + 4)
                require(records <= 8192 && 6L + records * 12L <= length && strings <= length) { "enregistrements name invalides" }
                val found = mutableSetOf<String>()
                val localized = mutableMapOf<Triple<Int, Int, Int>, MutableMap<Int, String>>()
                for (entry in 0 until records) {
                    val at = start + 6 + entry * 12
                    val platform = u16(at); val encoding = u16(at + 2); val language = u16(at + 4)
                    val id = u16(at + 6); val size = u16(at + 8); val offset = u16(at + 10)
                    if (id !in setOf(1, 2, 4, 6, 16, 17) || platform !in setOf(0, 3)) continue
                    require(size % 2 == 0 && strings.toLong() + offset + size <= length) { "chaîne name hors table" }
                    val name = String(bytes, start + strings + offset, size, Charsets.UTF_16BE).trim().trimEnd('\u0000')
                    if (name.isEmpty()) continue
                    if (id in setOf(1, 4, 6, 16)) found += name
                    localized.getOrPut(Triple(platform, encoding, language)) { mutableMapOf() }[id] = name
                }
                localized.values.forEach { group ->
                    for ((family, style) in listOf(1 to 2, 16 to 17)) {
                        if (group[family] != null && group[style] != null) found += "${group[family]} ${group[style]}"
                    }
                }
                names = found
            }
            require(names.isNotEmpty()) { "aucun nom de famille Unicode dans la police" }
            Face(index, names)
        }
    }
}
