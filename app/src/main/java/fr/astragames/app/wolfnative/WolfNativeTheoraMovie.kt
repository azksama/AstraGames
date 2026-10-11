package fr.astragames.app.wolfnative

import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import fr.astragames.wolf.NativeVideoState
import java.io.Closeable

/** Theora/Vorbis A/V controller, called only by WolfNativeVideo's worker. */
internal class WolfNativeTheoraMovie(
    private val handle: Long,
    val info: LongArray,
    private val worker: Handler,
    initial: NativeVideoState,
    private val onFrame: (Bitmap) -> Unit,
    private val onState: (NativeVideoState, Long, Int, Float, Float, Long, Long) -> Unit,
    private val onError: (Exception) -> Unit,
) : Closeable {
    private val width = info[0].toInt()
    private val height = info[1].toInt()
    private val duration = info[2]
    private val pixels = IntArray(width * height)
    private var control = initial
    private var closed = false
    private var suspended = false
    private var gain = 1f
    private var appliedSeek = initial.seekRevision
    private var frameNumber = -1L
    private var frames = 0L
    private var ended = false
    private var requestedPlay = initial.playing && initial.speedPercent > 0
    private var playing = false
    private var baseMs = initial.let { if (it.seekMs != 0L || it.seekRevision != 0L) it.seekMs else it.positionMs }
        .coerceIn(0, duration)
    private var baseAt = SystemClock.uptimeMillis()
    private var speedPercent = initial.speedPercent
    private var waitingAudioSeek: Long? = null
    private var audio: WolfNativeTheoraAudio? = null
    private var lastStateAt = 0L
    private var forceState = true
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            guarded {
                waitingAudioSeek?.let { revision ->
                    if (audio?.ready == true && audio!!.appliedSeek >= revision) waitingAudioSeek = null
                }
                var position = position()
                if (playing && position >= duration) {
                    if (control.loop) { seekNow(0); position = 0 }
                    else { ended = true; playing = false; baseMs = duration; baseAt = SystemClock.uptimeMillis(); audioControls(); forceState = true; position = duration }
                }
                val number = WolfNativeTheora.frameAt(handle, position, pixels, frameNumber)
                if (number != frameNumber) {
                    frameNumber = number; frames++
                    onFrame(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
                }
                publish(position)
                worker.postDelayed(this, 16)
            }
        }
    }

    init {
        ended = !initial.playing && baseMs >= duration
        if (info[3] > 0) audio = WolfNativeTheoraAudio(handle, info[3].toInt(), info[4].toInt(),
            baseMs.coerceAtMost(duration - 1)) { failure -> worker.post { if (!closed) onError(failure) } }
        worker.post(tick)
    }

    fun controls(value: NativeVideoState, suspend: Boolean, master: Float) {
        val nowPosition = position()
        baseMs = nowPosition; baseAt = SystemClock.uptimeMillis()
        control = value; suspended = suspend; gain = master
        if (value.seekRevision != appliedSeek) {
            appliedSeek = value.seekRevision
            if (value.seekMs in 0 until duration) seekNow(value.seekMs)
        }
        val wantPlay = value.playing && value.speedPercent > 0
        if (wantPlay && !requestedPlay) ended = false
        requestedPlay = wantPlay
        speedPercent = value.speedPercent
        playing = wantPlay && !suspended && !ended
        audioControls(); forceState = true
        publish(position())
    }

    private fun seekNow(position: Long) {
        baseMs = position; baseAt = SystemClock.uptimeMillis(); ended = false
        audio?.let { waitingAudioSeek = it.seek(position) }
        frameNumber = -1; forceState = true
    }

    private fun audioControls() = audio?.controls(playing, speedPercent, control.volumePercent.coerceIn(0, 100) / 100f * gain)

    private fun position(): Long {
        if (ended) return duration
        val sound = audio
        if (sound?.ready == true && waitingAudioSeek == null) return sound.positionMs.coerceIn(0, duration)
        return (baseMs + if (playing) (SystemClock.uptimeMillis() - baseAt) * speedPercent / 100 else 0).coerceIn(0, duration)
    }

    private fun publish(position: Long) {
        val now = SystemClock.uptimeMillis()
        if (!forceState && now - lastStateAt < 100) return
        forceState = false; lastStateAt = now
        onState(control.copy(positionMs = position, durationMs = duration,
            playing = requestedPlay && !ended, width = width, height = height), frames, audio?.audioSessionId ?: 0,
            speedPercent.takeIf { it > 0 }?.div(100f) ?: 0f, control.volumePercent.coerceIn(0, 100) / 100f * gain,
            audio?.decodedPcmBytes ?: 0, audio?.writtenPcmBytes ?: 0)
    }

    private inline fun guarded(action: () -> Unit) {
        if (closed) return
        try { action() }
        catch (failure: Exception) { onError(failure); close() }
        catch (_: OutOfMemoryError) { onError(IllegalStateException("mémoire insuffisante pour le décodage Theora")); close() }
    }

    override fun close() {
        if (closed) return
        closed = true; worker.removeCallbacks(tick)
        audio?.close(); audio = null
        WolfNativeTheora.close(handle)
    }
}
