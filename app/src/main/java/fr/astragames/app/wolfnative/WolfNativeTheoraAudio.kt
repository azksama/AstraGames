package fr.astragames.app.wolfnative

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Vorbis streaming and its playback-head clock; all calls stay off the UI. */
internal class WolfNativeTheoraAudio(
    private val handle: Long,
    private val sampleRate: Int,
    private val channels: Int,
    initialMs: Long,
    private val onError: (Exception) -> Unit,
) : Closeable {
    private val thread = HandlerThread("Wolf-native-vorbis").apply { start() }
    private val worker = Handler(thread.looper)
    private val closed = AtomicBoolean()
    @Volatile var positionMs = initialMs
        private set
    @Volatile var audioSessionId = 0
        private set
    @Volatile var ready = false
        private set
    @Volatile var writtenPcmBytes = 0L
        private set
    @Volatile var decodedPcmBytes = 0L
        private set
    @Volatile var appliedSeek = 0L
        private set
    private val requestedSeek = AtomicLong()
    private var track: AudioTrack? = null // worker only
    private val bytesPerFrame = channels * 2
    private val pcm = ByteArray((sampleRate / 25).coerceAtLeast(1) * bytesPerFrame)
    private val converter = WolfNativePcmRateConverter(channels, sampleRate / 25) { input ->
        WolfNativeTheora.readAudio(handle, input)
    }
    private var pendingBytes = 0
    private var pendingOffset = 0
    private var writtenFrames = 0L
    private var lastHead = 0L
    private var headWrap = 0L
    private var originHead = 0L
    private var baseMs = initialMs
    private var speedPercent = 100
    private var playing = false
    private var volume = 0f
    private var eof = false
    private var tailAt = -1L
    private var tailBase = initialMs
    private val pump = object : Runnable {
        override fun run() {
            if (closed.get()) return
            guarded {
                val audio = track ?: return@guarded
                updateClock()
                if (playing) {
                    val consumed = consumedFrames(audio)
                    var queued = writtenFrames - consumed
                    // A new streaming AudioTrack does not consume its first
                    // frame until its start threshold is filled. Older Android
                    // versions require priming the buffer, not a smaller cap.
                    val ahead = if (consumed == 0L) maxOf(sampleRate * 120L / 1000, audio.bufferSizeInFrames.toLong())
                        else sampleRate * 120L / 1000
                    var packets = 0
                    while (!eof && queued < ahead && packets++ < 16) {
                        if (pendingOffset == pendingBytes) {
                            pendingBytes = converter.read(pcm, speedPercent)
                            decodedPcmBytes = converter.decodedBytes
                            pendingOffset = 0
                            eof = pendingBytes == 0
                        }
                        if (pendingOffset < pendingBytes) {
                            val count = audio.write(pcm, pendingOffset, pendingBytes - pendingOffset, AudioTrack.WRITE_NON_BLOCKING)
                            check(count >= 0 && count % bytesPerFrame == 0) { "écriture PCM Vorbis impossible ($count)" }
                            if (count == 0) break
                            pendingOffset += count; writtenFrames += count / bytesPerFrame
                            queued += count / bytesPerFrame
                            writtenPcmBytes += count
                        }
                    }
                }
                updateClock()
                worker.postDelayed(this, 10)
            }
        }
    }

    init { worker.post { guarded {
        if (closed.get()) return@guarded
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "sortie AudioTrack Vorbis indisponible ($sampleRate Hz, $channels canaux)" }
        val audio = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * bytesPerFrame / 5))
            .build()
        track = audio
        check(audio.state == AudioTrack.STATE_INITIALIZED) { "initialisation AudioTrack Vorbis impossible" }
        if (Build.VERSION.SDK_INT >= 31) audio.setStartThresholdInFrames((sampleRate / 25).coerceAtLeast(1))
        audioSessionId = audio.audioSessionId
        seekNow(initialMs)
        ready = true
        worker.post(pump)
    } } }

    fun controls(wantPlay: Boolean, speedPercent: Int, gain: Float) {
        worker.post { guarded {
            if (closed.get()) return@guarded
            val audio = track ?: return@guarded
            updateClock()
            if (speedPercent > 0 && speedPercent != this.speedPercent) {
                // Discard queued samples converted at the old rate. Seek from
                // the consumed PCM clock, preserving the same AudioTrack.
                this.speedPercent = speedPercent
                seekNow(positionMs)
            }
            if (gain != volume) { audio.setVolume(gain.coerceIn(0f, 1f)); volume = gain }
            if (wantPlay != playing) {
                if (wantPlay) {
                    if (tailAt >= 0) { tailBase = positionMs; tailAt = SystemClock.uptimeMillis() }
                    audio.play()
                } else {
                    audio.pause()
                    if (tailAt >= 0) { tailBase = positionMs; tailAt = SystemClock.uptimeMillis() }
                }
                playing = wantPlay
            } else if (tailAt >= 0) { tailBase = positionMs; tailAt = SystemClock.uptimeMillis() }
        } }
    }

    fun seek(position: Long): Long {
        val revision = requestedSeek.incrementAndGet()
        worker.post { guarded { if (!closed.get()) { seekNow(position); appliedSeek = revision } } }
        return revision
    }

    private fun seekNow(position: Long) {
        val audio = track ?: return
        audio.pause(); audio.flush()
        WolfNativeTheora.seekAudio(handle, position)
        converter.reset()
        baseMs = position; positionMs = position
        pendingBytes = 0; pendingOffset = 0; writtenFrames = 0
        lastHead = audio.playbackHeadPosition.toLong() and 0xffffffffL
        headWrap = 0; originHead = lastHead
        eof = false; tailAt = -1
        if (playing) audio.play()
    }

    private fun consumedFrames(audio: AudioTrack): Long {
        val raw = audio.playbackHeadPosition.toLong() and 0xffffffffL
        if (raw < lastHead && lastHead - raw > 0x80000000L) headWrap += 0x100000000L
        lastHead = raw
        return (raw + headWrap - originHead).coerceAtLeast(0)
    }

    private fun updateClock() {
        val audio = track ?: return
        val head = consumedFrames(audio)
        if (tailAt < 0 && eof && head >= writtenFrames) {
            tailBase = baseMs + head * speedPercent * 1000 / (sampleRate * 100L)
            tailAt = SystemClock.uptimeMillis()
        }
        positionMs = if (tailAt >= 0) tailBase + (if (playing) (SystemClock.uptimeMillis() - tailAt) * speedPercent / 100 else 0)
            else baseMs + head * speedPercent * 1000 / (sampleRate * 100L)
    }

    private inline fun guarded(action: () -> Unit) {
        if (closed.get()) return
        try { action() }
        catch (failure: Exception) { onError(failure); close() }
        catch (_: OutOfMemoryError) { onError(IllegalStateException("mémoire insuffisante pour le décodage Vorbis")); close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.removeCallbacks(pump)
        worker.post { track?.let { runCatching { it.release() } }; track = null; thread.quitSafely() }
    }
}
