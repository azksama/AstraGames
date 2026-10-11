package fr.astragames.app.wolfnative

/** Pinned BSD Xiph decoders, independent of optional Android OGV codecs. */
internal object WolfNativeTheora {
    @Volatile private var loaded = false
    @Synchronized fun ensureAvailable() {
        if (loaded) return
        try { System.loadLibrary("astra_wolfcodecs"); loaded = true }
        catch (failure: LinkageError) { throw IllegalStateException("bibliothèque native Theora/Vorbis absente pour cette ABI", failure) }
    }

    external fun open(bytes: ByteArray): Long
    /** width, height, durationMs, audioSampleRate (0 for silent), channels. */
    external fun info(handle: Long): LongArray
    fun frameAt(handle: Long, positionMs: Long, pixels: IntArray, previousFrame: Long = Long.MIN_VALUE): Long =
        decodeFrame(handle, positionMs, pixels, previousFrame)
    private external fun decodeFrame(handle: Long, positionMs: Long, pixels: IntArray, previousFrame: Long): Long
    external fun readAudio(handle: Long, pcm16le: ByteArray): Int
    external fun seekAudio(handle: Long, positionMs: Long)
    external fun close(handle: Long)
}
