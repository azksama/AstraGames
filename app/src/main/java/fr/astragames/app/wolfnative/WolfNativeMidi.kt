package fr.astragames.app.wolfnative

/** SMF timing is expressed in MIDI ticks, unlike Wolf's sampled-audio milliseconds. */
internal class WolfNativeMidi private constructor(
    private val division: Int,
    private val tempos: List<Pair<Long, Int>>,
    private val notes: List<Int>,
) {
    fun millis(ticks: Int): Int {
        require(ticks >= 0)
        if (division and 0x8000 != 0) {
            val frames = -(division shr 8).toByte().toInt()
            val rate = if (frames == 29) 30_000.0 / 1001 else frames.toDouble()
            return (ticks * 1000.0 / (rate * (division and 255))).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        var from = 0L; var tempo = 500_000; var micros = 0L
        for ((at, next) in tempos) {
            if (at > ticks) break
            micros += (at - from) * tempo / division
            from = at; tempo = next
        }
        micros += (ticks - from) * tempo / division
        return (micros / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun transpose(bytes: ByteArray, semitones: Int): ByteArray {
        require(semitones in -127..127) { "Transposition MIDI hors plage" }
        if (semitones == 0) return bytes
        return bytes.copyOf().also { copy -> notes.forEach { at ->
            copy[at] = ((copy[at].toInt() and 127) + semitones).coerceIn(0, 127).toByte()
        } }
    }

    companion object {
        fun parse(bytes: ByteArray): WolfNativeMidi? {
            if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != "MThd") return null
            fun u(at: Int) = bytes[at].toInt() and 255
            fun be(at: Int, count: Int): Int {
                require(at >= 0 && at.toLong() + count <= bytes.size) { "MIDI tronqué" }
                var result = 0; repeat(count) { result = (result shl 8) or u(at + it) }; return result
            }
            val header = be(4, 4); require(header >= 6 && header.toLong() + 8 <= bytes.size)
            require(be(8, 2) in 0..1) { "MIDI à séquences indépendantes non décodable par Android" }
            val tracks = be(10, 2); require(tracks in 1..1024)
            val division = be(12, 2)
            if (division and 0x8000 == 0) require(division > 0) else {
                require(-(division shr 8).toByte().toInt() in setOf(24, 25, 29, 30) && division and 255 > 0)
            }
            var cursor = 8 + header
            val tempos = mutableListOf<Pair<Long, Int>>(); val notes = mutableListOf<Int>()
            repeat(tracks) {
                require(cursor + 8 <= bytes.size && String(bytes, cursor, 4, Charsets.US_ASCII) == "MTrk")
                val size = be(cursor + 4, 4); cursor += 8
                require(size >= 0 && cursor.toLong() + size <= bytes.size)
                val end = cursor + size; var ticks = 0L; var running = 0
                fun variable(): Int {
                    var value = 0
                    repeat(4) {
                        require(cursor < end) { "Événement MIDI tronqué" }
                        val byte = u(cursor++); value = (value shl 7) or (byte and 127)
                        if (byte and 128 == 0) return value
                    }
                    error("Longueur MIDI invalide")
                }
                while (cursor < end) {
                    ticks += variable(); require(cursor < end)
                    var status = u(cursor)
                    if (status and 128 != 0) { cursor++; if (status < 0xf0) running = status }
                    else { require(running in 0x80..0xef); status = running }
                    when (status) {
                        0xff -> {
                            require(cursor < end); val type = u(cursor++); val length = variable()
                            require(cursor.toLong() + length <= end)
                            if (type == 0x51 && length == 3) {
                                val tempo = be(cursor, 3); require(tempo > 0); tempos.add(ticks to tempo)
                            }
                            cursor += length
                        }
                        0xf0, 0xf7 -> { running = 0; val length = variable(); require(cursor.toLong() + length <= end); cursor += length }
                        else -> {
                            require(status in 0x80..0xef)
                            val count = if (status and 0xf0 in setOf(0xc0, 0xd0)) 1 else 2
                            require(cursor + count <= end && (0 until count).all { u(cursor + it) < 128 })
                            // Percussion channel10 and controller/program messages retain their original bytes.
                            if (status and 0xf0 in setOf(0x80, 0x90, 0xa0) && status and 15 != 9) notes.add(cursor)
                            cursor += count
                        }
                    }
                }
            }
            return WolfNativeMidi(division, tempos.sortedBy { it.first }, notes)
        }
    }
}
