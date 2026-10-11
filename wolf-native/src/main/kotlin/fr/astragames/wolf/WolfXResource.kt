package fr.astragames.wolf

/** A required WOLFX key or variable is unknown; no fabricated payload is returned. */
class WolfXResourceUnavailable(message: String) : IllegalArgumentException(message)

/**
 * Individual WOLFX resources, separate from DX archives. Adapted from the MIT WolfX
 * implementation in UberWolf f4191375d289171b1f29dbc5ca320133b2b4b96a (Sinflower, 2025).
 * The caller supplies exact key/string bytes in the game's encoding, if referenced.
 * This decoder performs no IO, key search, global variable lookup, or decompression.
 */
object WolfXResource {
    const val MAX_RESOURCE_BYTES = 64 * 1024 * 1024
    private const val MAX_KEY_BYTES = 65_536
    private const val MAX_STRING_BYTES = 4 * 1024 * 1024
    private val magic = byteArrayOf(0x57, 0x4f, 0x4c, 0x46, 0x58)

    fun isEncrypted(bytes: ByteArray): Boolean = bytes.size >= magic.size &&
        magic.indices.all { bytes[it] == magic[it] }

    /**
     * The default key admits only the established empty-key profile. A referenced
     * string/numeric variable must be supplied explicitly. Five native check bytes
     * detect a wrong key/value; they are not an authenticated integrity checksum.
     */
    fun decode(
        bytes: ByteArray,
        key: ByteArray = byteArrayOf(),
        stringVariable: ((Int) -> ByteArray?)? = null,
        numberVariable: ((Int) -> Int?)? = null,
    ): ByteArray {
        require(bytes.size in 20..MAX_RESOURCE_BYTES && isEncrypted(bytes)) {
            "Ressource WOLFX absente, tronquée ou hors capacité"
        }
        require(key.size <= MAX_KEY_BYTES) { "Clé WOLFX hors capacité" }
        val static = staticBlob(key)
        val offset = 512 + static[0] + static[1]
        require(offset < bytes.size) { "En-tête WOLFX tronqué ou clé incorrecte" }
        var seed = fnv(static) xor bigEndian(bytes, 5, 4)
        for (value in static) seed = seed xor value
        val blob = IntArray(256) {
            seed = seed * 1_664_525 + 1_013_904_223
            seed and 255
        }
        val header = IntArray(5) { unsigned(bytes[10 + it]) xor blob[it] }
        val stringIndex = (header[0] shl 8) or header[1]
        val string = if (stringIndex < 10_000) {
            stringVariable?.invoke(stringIndex) ?: throw WolfXResourceUnavailable(
                "Variable de chaîne WOLFX $stringIndex indisponible pour la clé fournie",
            )
        } else byteArrayOf()
        require(string.size <= MAX_STRING_BYTES) { "Chaîne de clé WOLFX hors capacité" }
        val stringHash = fnv(string)
        val numberIndex = ((stringHash and -65_536) ushr 8) xor
            (stringHash and 65_535) xor ((header[2] shl 16) or (header[3] shl 8) or header[4])
        val number = if (numberIndex < 1_000_000) {
            numberVariable?.invoke(numberIndex) ?: throw WolfXResourceUnavailable(
                "Variable numérique WOLFX $numberIndex indisponible pour la clé fournie",
            )
        } else 0
        val numberHash = (number shl 13) xor (73_244_475 * number)
        // combineBytes/extractBytes in the pinned upstream are BIG-ENDIAN.
        for (i in blob.indices) {
            val textByte = if (string.isEmpty()) 0 else unsigned(string[i % string.size])
            val intByte = (numberHash ushr ((3 - (i and 3)) * 8)) and 255
            val indexByte = (numberIndex ushr ((2 - i % 3) * 8)) and 255
            blob[i] = blob[i] xor header[i % 2] xor textByte xor intByte xor indexByte
        }
        fun decoded(index: Int) = (unsigned(bytes[index]) xor blob[(index - 10) and 255]).toByte()
        val output = ByteArray(bytes.size - offset) { decoded(offset + it) }
        for (i in 0..4) {
            val position = ((output.size - 1).toLong() * i / 4).toInt()
            if (output[position] != decoded(15 + i)) throw WolfXResourceUnavailable(
                "Contrôle WOLFX invalide : clé, variable ou ressource incorrecte",
            )
        }
        return output
    }

    private fun staticBlob(key: ByteArray): IntArray {
        val data = IntArray(64) { 0xaa }
        var dynamic = 0xbe
        key.forEachIndexed { i, byte ->
            val value = unsigned(byte)
            val index = i and 63
            val mixed = ((data[index] xor value) + dynamic) and 255
            data[index] = ((mixed shl 3) or (mixed ushr 5)) and 255
            dynamic = (value xor (0xb3 * dynamic)) and 255
        }
        repeat(5) {
            for (j in data.indices) {
                val mixed = (data[(j + 13) and 63] + (data[j] xor data[(j + 7) and 63])) and 255
                data[j] = ((mixed ushr 7) or (mixed shl 1)) and 255
            }
        }
        for (j in data.indices) if (data[j] == 0) data[j] = 1
        return data
    }

    private fun fnv(bytes: ByteArray): Int {
        var hash = 0x811c9dc5.toInt()
        for (byte in bytes) hash = 0x01000193 * (hash xor unsigned(byte))
        return hash
    }
    private fun fnv(bytes: IntArray): Int {
        var hash = 0x811c9dc5.toInt()
        for (byte in bytes) hash = 0x01000193 * (hash xor byte)
        return hash
    }
    private fun bigEndian(bytes: ByteArray, offset: Int, count: Int): Int {
        var value = 0
        repeat(count) { value = (value shl 8) or unsigned(bytes[offset + it]) }
        return value
    }
    private fun unsigned(byte: Byte) = byte.toInt() and 255
}
