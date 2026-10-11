package fr.astragames.wolf

/** MIT WolfChaCha20 port: CC2 uses key bytes 0..31, nonce bytes 34..45, counter one. */
internal class WolfArchiveChaCha(material: ByteArray, nonceOffset: Int = 34) : WolfArchiveTransform {
    private val initial = IntArray(16)
    init {
        if (material.size < 32 || nonceOffset < 32 || material.size < nonceOffset + 12)
            throw WolfFormatException("Wolf ChaCha archive: missing key/nonce material")
        val constant = "expand 32-byte k".toByteArray(Charsets.US_ASCII)
        for (i in 0..3) initial[i] = ccWord(constant, 4 * i)
        for (i in 0..7) initial[4 + i] = ccWord(material, 4 * i)
        initial[12] = 1
        for (i in 0..2) initial[13 + i] = ccWord(material, nonceOffset + 4 * i)
    }
    override fun configureIndex(start: Long) {}
    override fun decodeOuter(data: ByteArray, absoluteOffset: Long) {}
    override fun decodeAddresses(header: ByteArray) {}

    override fun decodeInner(data: ByteArray, streamPosition: Long) {
        if (streamPosition < 0) throw WolfFormatException("Wolf CC2 archive: negative stream position")
        // DXArchive casts its positional input to uint32 before calling execute.
        val position = streamPosition and 0xffffffffL
        val state = initial.copyOf()
        state[12] += (position ushr 6).toInt()
        var within = (position and 63).toInt()
        var output = 0
        while (output < data.size) {
            val words = state.copyOf()
            fun qr(a: Int, b: Int, c: Int, d: Int) {
                words[a] += words[b]; words[d] = Integer.rotateLeft(words[d] xor words[a], 16)
                words[c] += words[d]; words[b] = Integer.rotateLeft(words[b] xor words[c], 12)
                words[a] += words[b]; words[d] = Integer.rotateLeft(words[d] xor words[a], 8)
                words[c] += words[d]; words[b] = Integer.rotateLeft(words[b] xor words[c], 7)
            }
            repeat(10) {
                qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
                qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
            }
            for (i in words.indices) words[i] += state[i]
            val count = minOf(64 - within, data.size - output)
            repeat(count) { i ->
                val at = within + i
                val mask = words[at / 4] ushr (8 * (at % 4))
                data[output + i] = (data[output + i].toInt() xor mask).toByte()
            }
            state[12]++
            if (state[12] == 0) state[13]++
            output += count
            within = 0
        }
    }
}

private fun ccWord(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { result, i ->
    result or ((bytes[offset + i].toInt() and 255) shl (8 * i))
}
