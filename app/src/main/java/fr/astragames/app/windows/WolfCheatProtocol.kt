package fr.astragames.app.windows

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class WolfCheatType(val label: String, val bytes: Int) {
    INT8("Entier 8 bits", 1), INT16("Entier 16 bits", 2), INT32("Entier 32 bits", 4),
    INT64("Entier 64 bits", 8), FLOAT("Décimal 32 bits", 4), DOUBLE("Décimal 64 bits", 8);
    fun parse(text: String): Long = when (this) {
        FLOAT -> text.trim().replace(',', '.').toFloat().also { require(it.isFinite()) { "Valeur décimale finie requise" } }.toRawBits().toLong() and 0xffffffffL
        DOUBLE -> text.trim().replace(',', '.').toDouble().also { require(it.isFinite()) { "Valeur décimale finie requise" } }.toRawBits()
        else -> text.trim().toLong().also {
            val min = when (bytes) { 1 -> -128L; 2 -> -32768L; 4 -> Int.MIN_VALUE.toLong(); else -> Long.MIN_VALUE }
            val max = when (bytes) { 1 -> 127L; 2 -> 32767L; 4 -> Int.MAX_VALUE.toLong(); else -> Long.MAX_VALUE }
            require(it in min..max) { "Valeur hors de l’intervalle $min à $max" }
        }.let { if (bytes == 8) it else it and ((1L shl (bytes * 8)) - 1) }
    }
    fun format(bits: Long): String = when (this) {
        INT8 -> bits.toByte().toString(); INT16 -> bits.toShort().toString(); INT32 -> bits.toInt().toString()
        INT64 -> bits.toString(); FLOAT -> Float.fromBits(bits.toInt()).toString(); DOUBLE -> Double.fromBits(bits).toString()
    }
}

internal data class WolfCheatRow(val address: Long, val bits: Long)
internal data class WolfCheatResult(val type: WolfCheatType, val count: Long, val scanned: Long, val truncated: Boolean, val rows: List<WolfCheatRow>)
internal object WolfCheatProtocol {
    const val MAGIC = 0x41535443
    fun request(id: Int, op: Int, type: WolfCheatType, mode: Int = 0, address: Long = 0, value: Long = 0, other: Long = 0, offset: Int = 0): ByteArray {
        require(id > 0 && op in 0..10 && mode in 0..6 && address >= 0 && offset >= 0)
        return ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(MAGIC); putInt(id); putInt(op); putInt(type.ordinal); putInt(mode); putInt(128)
            putLong(address); putLong(value); putLong(other); putInt(offset); putInt(0)
        }.array()
    }
    fun response(bytes: ByteArray, id: Int): WolfCheatResult {
        require(bytes.size >= 48 && bytes.size <= 48 + 128 * 16) { "Réponse du moteur de recherche invalide" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == MAGIC && b.int == id) { "Réponse d’une autre recherche" }
        val status = b.int; val type = b.int; val count = b.long; val scanned = b.long
        val flags = b.int; val error = b.int; val length = b.int; b.int
        check(status == 0) { when (status) {
            1 -> "Processus du jeu introuvable ou ambigu. Entrez dans une partie puis réessayez."
            4 -> "La session du jeu est terminée. Les anciennes adresses ne seront pas réutilisées."
            5 -> "Cette valeur n’est plus accessible en lecture/écriture (Windows : $error)."
            6 -> "Recherche annulée. Les résultats précédents sont conservés."
            8 -> "Mémoire de recherche ou liste de valeurs figées pleine."
            10 -> "La valeur a changé entre la lecture et la modification. Relisez-la avant de modifier."
            else -> "Recherche incompatible. Commencez une nouvelle recherche avec ce type de valeur."
        } }
        require(type in WolfCheatType.entries.indices && count in 0..2_097_152 && scanned >= 0 && length in 0..128 && bytes.size == 48 + length * 16)
        val rows = List(length) { WolfCheatRow(b.long.also { require(it >= 0) }, b.long) }
        return WolfCheatResult(WolfCheatType.entries[type], count, scanned, flags and 1 != 0, rows)
    }
}
