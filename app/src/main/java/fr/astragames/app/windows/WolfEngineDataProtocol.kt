package fr.astragames.app.windows

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Versioned, bounded IPC with the original Windows helper; no native addresses supplied by callers. */
internal object WolfEngineDataProtocol {
    const val MAGIC = 0x41534445
    const val VERSION = 1
    const val MAX_TEXT_BYTES = 4096
    const val HEADER_BYTES = 104
    private const val ROW_BYTES = 40
    const val MAX_RESPONSE_BYTES = HEADER_BYTES + 128 * (ROW_BYTES + MAX_TEXT_BYTES)

    fun request(id: Int, op: Int, token: Long = 0, category: EngineDataCategory? = null, bank: Int? = null,
                query: String = "", variable: Int = 0, before: String = "", after: String = "", offset: Int = 0): ByteArray {
        require(id > 0 && op in 11..14 && variable >= 0 && offset in 0..3_000_000 && (bank == null || bank in 0..9999))
        val parts = listOf(query, before, after).map { text ->
            require('\u0000' !in text) { "Le texte ne peut pas contenir de caractère nul." }
            val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
            require(bytes.remaining() <= MAX_TEXT_BYTES) { "4096 octets UTF-8 maximum par texte." }
            ByteArray(bytes.remaining()).also(bytes::get)
        }
        return ByteBuffer.allocate(56 + 40 + parts.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(WolfCheatProtocol.MAGIC); putInt(id); putInt(op); putInt(0); putInt(0); putInt(128)
            putLong(variable.toLong()); putLong(0); putLong(0); putInt(offset); putInt(0)
            putInt(MAGIC); putInt(VERSION); putLong(token); putInt(category?.wire ?: 0); putInt(bank ?: -1)
            parts.forEach { putInt(it.size) }; putInt(0); parts.forEach { put(it) }
        }.array()
    }

    data class Response(val status: Int, val probe: EngineDataProbe, val count: Long, val variables: List<EngineDataVariable>) {
        fun checked(): Response { check(status == 0) { statusMessage(status) }; return this }
    }

    fun response(bytes: ByteArray, id: Int): Response {
        require(bytes.size in HEADER_BYTES..MAX_RESPONSE_BYTES) { "Réponse des variables Wolf invalide." }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == MAGIC && b.int == id) { "Réponse d’une autre session de variables." }
        val status = b.int
        require(b.int == VERSION) { "Version du protocole Wolf incompatible." }
        val token = b.long; val layout = b.int; val codepage = b.int; val rows = b.int; val payload = b.int; val count = b.long
        val bankCount = b.int; val textCount = b.int; val commonCount = b.int; val flags = b.int
        val banks = List(10) { b.int }
        require(layout in 0..2 && rows in 0..128 && payload == bytes.size - HEADER_BYTES && count in 0..3_000_000 && flags == 0)
        require(bankCount in 0..10 && textCount in 0..1_000_000 && commonCount in 0..10_000 && banks.all { it in 0..100_000 })
        require(status == 0 || (rows == 0 && payload == 0))
        val probe = if (status == 0) {
            require(layout in 1..2 && codepage == (if (layout == 1) 932 else 65001) && token != 0L)
            EngineDataProbe.Supported(if (layout == 1) "Wolf 2.2961" else "Wolf 3.729", token, codepage, banks.take(bankCount), textCount, commonCount)
        } else EngineDataProbe.Unavailable(statusMessage(status))
        val variables = List(rows) {
            require(b.remaining() >= ROW_BYTES)
            val variableId = b.int; val category = b.int; val bank = b.int; val index = b.int; val type = b.int; val capacity = b.int
            val rowToken = b.long; val length = b.int; val rowFlags = b.int
            require(length in 0..MAX_TEXT_BYTES && b.remaining() >= length && rowToken != 0L && rowFlags in 0..1)
            require(category in 1..4 && type in 0..1 && capacity in 0..16 * 1024 * 1024)
            require(if (type == 0) capacity == 4 && rowFlags == 0 else capacity >= 15)
            require(validId(variableId, category, bank, index) && type == if (category in listOf(2, 4)) 1 else 0)
            val slice = b.slice().apply { limit(length) }
            val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(slice).toString()
            b.position(b.position() + length)
            require('\u0000' !in value)
            EngineDataVariable(variableId, EngineDataCategory.entries.first { it.wire == category }, bank, index,
                if (type == 0) EngineDataValue.Number(value.toInt()) else EngineDataValue.Text(value), rowToken, capacity, rowFlags != 0)
        }
        require(!b.hasRemaining() && variables.map { it.id }.distinct().size == variables.size && count >= variables.size)
        return Response(status, probe, count, variables)
    }

    private fun validId(id: Int, category: Int, bank: Int, index: Int): Boolean = when (category) {
        1 -> bank in 0..9 && index in 0..99999 && id == 2_000_000 + bank * 100_000 + index
        2 -> bank == 0 && index in 0..999999 && id == 3_000_000 + index
        3 -> bank in 0..9999 && index in 0..99 && index !in 5..9 && id == 15_000_000 + bank * 100 + index
        4 -> bank in 0..9999 && index in 5..9 && id == 15_000_000 + bank * 100 + index
        else -> false
    }

    fun statusMessage(status: Int): String = when (status) {
        1 -> "Processus du jeu introuvable ou ambigu. Entrez dans une partie puis réessayez."
        4 -> "La session du jeu est terminée. Les anciennes variables ne seront pas réutilisées."
        5 -> "Les données du jeu ne sont plus accessibles en lecture/écriture."
        6 -> "Recherche des variables annulée."
        10 -> "La valeur a changé. Relisez cette variable avant de la modifier."
        11 -> "L’écriture n’a pas pu être confirmée par relecture. Relisez la variable."
        20 -> "Cet exécutable Wolf n’a pas de format mémoire validé. Le scanner Mémoire reste disponible."
        21 -> "Les tableaux Wolf ont changé ou ne correspondent plus au format validé. Actualisez la sonde ; le scanner Mémoire reste disponible."
        22 -> "Les variables Wolf ne sont pas encore initialisées. Entrez dans une partie puis réessayez."
        23 -> "Cette variable n’existe pas dans les tableaux chargés."
        24 -> "Ce texte ne peut pas être modifié sans perte dans l’encodage du jeu, ou dépasse la limite de lecture."
        25 -> "Les données Wolf ont été déplacées. Relisez cette variable avant de la modifier."
        26 -> "Une valeur mémoire figée touche cette variable. Libérez-la avant de modifier."
        27 -> "Le texte dépasse la capacité actuellement allouée par le jeu. Raccourcissez-le."
        else -> "Requête des variables Wolf incompatible."
    }
}
