package fr.astragames.app.wolfnative

/**
 * Streaming PCM16 rate conversion. Wolf changes pitch with its movie speed;
 * generating samples at a fixed AudioTrack rate avoids device-specific limits
 * on PlaybackParams. Positions use hundredths of an input frame, not a
 * floating-point accumulator, so packet boundaries never alter the timeline.
 */
internal class WolfNativePcmRateConverter(
    channels: Int,
    inputFrames: Int,
    private val readPcm: (ByteArray) -> Int,
) {
    private val channels = channels.also { require(it in 1..2) }
    private val bytesPerFrame = channels * 2
    private val input = ByteArray(inputFrames.coerceAtLeast(1) * bytesPerFrame)
    private val first = IntArray(channels)
    private var start = 0L
    private var frames = 0
    private var ended = false
    private var sourceHundredths = 0L
    private var cachedIndex = -1L
    var decodedBytes = 0L
        private set

    fun reset() {
        start = 0; frames = 0; ended = false; sourceHundredths = 0; cachedIndex = -1
    }

    fun read(output: ByteArray, speedPercent: Int): Int {
        require(speedPercent > 0)
        val maximum = output.size / bytesPerFrame
        var count = 0
        while (count < maximum) {
            val index = sourceHundredths / 100
            if (cachedIndex != index) {
                if (!ensure(index)) break
                val offset = ((index - start) * bytesPerFrame).toInt()
                for (channel in 0 until channels) first[channel] = sample(offset + channel * 2)
                cachedIndex = index
            }
            val fraction = (sourceHundredths % 100).toInt()
            val hasNext = if (fraction != 0) ensure(index + 1) else false
            val nextOffset = if (hasNext) ((index + 1 - start) * bytesPerFrame).toInt() else 0
            for (channel in 0 until channels) {
                val value = if (hasNext) first[channel] +
                    (sample(nextOffset + channel * 2) - first[channel]) * fraction / 100 else first[channel]
                val destination = count * bytesPerFrame + channel * 2
                output[destination] = value.toByte()
                output[destination + 1] = (value shr 8).toByte()
            }
            sourceHundredths += speedPercent.toLong()
            count++
        }
        return count * bytesPerFrame
    }

    private fun ensure(index: Long): Boolean {
        while (index >= start + frames) {
            if (ended) return false
            val nextStart = start + frames
            val bytes = readPcm(input)
            check(bytes in 0..input.size && bytes % bytesPerFrame == 0) { "trame PCM Vorbis invalide ($bytes)" }
            decodedBytes += bytes
            if (bytes == 0) { ended = true; return false }
            start = nextStart; frames = bytes / bytesPerFrame
        }
        check(index >= start) { "position PCM antérieure au paquet courant" }
        return true
    }

    private fun sample(offset: Int) = ((input[offset].toInt() and 255) or
        (input[offset + 1].toInt() shl 8)).toShort().toInt()
}
