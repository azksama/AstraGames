package fr.astragames.app.wolfnative

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import fr.astragames.wolf.NativeEffect
import fr.astragames.wolf.WolfAssetSource
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Android decoders/synthesizer, with file preparation off the UI and VM threads. */
internal class WolfNativeAudio(
    context: Context,
    private val source: WolfAssetSource,
    private val cacheDirectory: File,
    private val onError: (String) -> Unit,
    private val onPlaybackEvent: (String) -> Unit = {},
) : Closeable {
    private val thread = HandlerThread("Wolf-native-audio").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val io = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32)) { task ->
        Thread(task, "Wolf-native-audio-read").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ state ->
            duck = if (state == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) .2f else 1f
            focusPaused = state == AudioManager.AUDIOFOCUS_LOSS || state == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            updatePause()
        }, handler).build()
    private val closed = AtomicBoolean()
    private val serial = AtomicLong()
    private val generations = ConcurrentHashMap<Int, Long>()
    private val channelControls = ConcurrentHashMap<Int, ChannelControl>()
    private val prepared = ConcurrentHashMap<String, AudioFile>()
    private val ownedFiles = ConcurrentHashMap.newKeySet<File>()
    private val sessionName = "wolf-audio-${UUID.randomUUID()}-"
    private val players = linkedMapOf<Long, Playing>() // audio-thread owned
    private var paused = false
    private var focusPaused = false
    private var duck = 1f
    private var cachedBytes = 0L // synchronized with prepared
    private var lastPump = SystemClock.uptimeMillis()

    private data class AudioFile(val file: File, val loopStartMs: Int = 0, val loopEndMs: Int = 0)
    private data class ChannelControl(val order: Long, val volume: Int, val pitch: Int, val fadeTicks: Int, val fps: Int)
    private class Playing(val id: Long, val channel: Int, val path: String, val player: MediaPlayer,
        val effect: NativeEffect.Audio, val audio: AudioFile, var volume: Float,
        var fadeFrom: Float = 0f, var fadeTarget: Float = 0f, var fadeRemainingMs: Long = 0L,
        var fadeDurationMs: Long = 0L, var stopAfterFade: Boolean = false, var ready: Boolean = false,
        var pitch: Int = 100, var requestedPitch: Int = effect.pitch, var started: Boolean = false)

    private val pump = object : Runnable {
        override fun run() {
            if (closed.get()) return
            val now = SystemClock.uptimeMillis()
            val delta = (now - lastPump).coerceIn(0, 250)
            lastPump = now
            if (!paused && !focusPaused) {
                players.values.toList().forEach { state ->
                    if (!state.ready) return@forEach
                    try {
                        if (state.fadeRemainingMs > 0) {
                            state.fadeRemainingMs = (state.fadeRemainingMs - delta).coerceAtLeast(0)
                            val t = 1f - state.fadeRemainingMs.toFloat() / state.fadeDurationMs.coerceAtLeast(1)
                            state.volume = state.fadeFrom + (state.fadeTarget - state.fadeFrom) * t
                            setVolume(state)
                            if (state.fadeRemainingMs == 0L && state.stopAfterFade) { release(state); return@forEach }
                        }
                        if (state.effect.loop && state.audio.loopEndMs > 0 &&
                            state.player.isPlaying && state.player.currentPosition >= state.audio.loopEndMs) {
                            state.player.seekTo(state.audio.loopStartMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                        }
                    } catch (error: Exception) { failure(state.path, error); release(state) }
                }
            }
            handler.postDelayed(this, 16)
        }
    }

    init { handler.post {
        event("focus-requested", detail = "result=${manager.requestAudioFocus(focus)}")
        handler.post(pump)
    } }

    fun apply(effect: NativeEffect.Audio, logicalFps: Int = 60) {
        if (closed.get()) return
        val requestedPath = effect.path
        event(if (effect.stop) "stop-requested" else if (requestedPath.isNullOrBlank()) "update-requested" else "play-requested",
            effect.channel, requestedPath, "volume=${effect.volume} pitch=${effect.pitch} loop=${effect.loop} fadeTicks=${effect.fadeTicks}")
        val generation = serial.incrementAndGet()
        // BGM/BGS and looping channels replace previous requests. One-shot SEs overlap.
        val single = effect.channel < 2 || effect.loop || effect.stop || requestedPath.isNullOrBlank()
        if (single && (effect.stop || !requestedPath.isNullOrBlank())) generations[effect.channel] = generation
        if (effect.stop || requestedPath.isNullOrBlank()) {
            if (!effect.stop) channelControls[effect.channel] = ChannelControl(generation, effect.volume, effect.pitch, effect.fadeTicks, logicalFps)
            handler.post {
                if (!closed.get()) players.values.filter { it.channel == effect.channel }.toList().forEach { state ->
                    try {
                        if (effect.stop) {
                            if (effect.fadeTicks > 0 && state.ready) fade(state, 0f, effect.fadeTicks, logicalFps, true) else release(state)
                        } else {
                            control(state, ChannelControl(generation, effect.volume, effect.pitch, effect.fadeTicks, logicalFps))
                        }
                    } catch (error: Exception) { failure(state.path, error); release(state) }
                }
            }
            return
        }
        val path = try { WolfNativeResources.logicalPath(requestedPath) }
        catch (error: Exception) { failure(requestedPath, error); return }
        try {
            io.execute {
                try {
                    val audio = materialize(path)
                    handler.post {
                        if (!closed.get() && (!single || generations[effect.channel] == generation))
                            start(effect, audio, path, generation, single, logicalFps)
                    }
                } catch (error: Exception) { failure(path, error) }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            if (!closed.get()) failure(path, IllegalStateException("trop de demandes audio simultanées", error))
        }
    }

    private fun materialize(path: String): AudioFile {
        prepared[path]?.let { return it }
        val bytes = source.read(path)
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) { "fichier audio vide ou dépassant 64 Mio" }
        require(!closed.get()) { "session audio fermée" }
        val suffix = path.substringAfterLast('.', "bin").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,6}")) } ?: "bin"
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        synchronized(prepared) {
            prepared[path]?.let { return it }
            require(!closed.get()) { "session audio fermée" }
            require(cachedBytes + bytes.size <= 256L * 1024 * 1024) { "cache audio de la session dépassant 256 Mio" }
            require(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "cache audio non accessible" }
            val file = File(cacheDirectory, "$sessionName$digest.$suffix")
            val temporary = File(cacheDirectory, "$sessionName$digest.tmp")
            ownedFiles += temporary
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            require(!closed.get()) { "session audio fermée" }
            require(temporary.renameTo(file)) { "écriture atomique du cache audio impossible" }
            ownedFiles.remove(temporary); ownedFiles += file
            val loop = vorbisLoop(bytes)
            val result = AudioFile(file, loop.first, loop.second)
            cachedBytes += bytes.size
            prepared[path] = result
            return result
        }
    }

    private fun start(effect: NativeEffect.Audio, audio: AudioFile, path: String, id: Long, single: Boolean, fps: Int) {
        if (single) players.values.filter { it.channel == effect.channel }.toList().forEach(::release)
        else if (players.values.count { it.channel >= 2 } >= 16) {
            failure(path, IllegalStateException("plus de 16 effets sonores simultanés")); return
        }
        val player = MediaPlayer()
        val targetVolume = effect.volume.coerceIn(0, 100) / 100f
        val state = Playing(id, effect.channel, path, player, effect, audio,
            if (effect.fadeTicks > 0) 0f else targetVolume)
        players[id] = state
        try {
            if (effect.fadeTicks > 0) fade(state, targetVolume, effect.fadeTicks, fps)
            channelControls[effect.channel]?.takeIf { it.order > id }?.let { control(state, it) }
            player.setAudioAttributes(attributes)
            player.setDataSource(audio.file.absolutePath)
            player.isLooping = effect.loop && audio.loopStartMs == 0 && audio.loopEndMs == 0
            player.setOnPreparedListener {
                if (closed.get() || players[id] !== state) { release(state); return@setOnPreparedListener }
                state.ready = true
                try {
                    event("prepared", state.channel, state.path, "durationMs=${player.duration} id=${state.id}")
                    if (effect.loop && (audio.loopStartMs > 0 || audio.loopEndMs > 0)) {
                        val duration = player.duration
                        require(duration > audio.loopStartMs && (audio.loopEndMs == 0 || audio.loopEndMs in (audio.loopStartMs + 1)..duration)) {
                            "bornes de boucle Vorbis invalides : ${audio.loopStartMs}–${audio.loopEndMs} ms pour $duration ms"
                        }
                    }
                    changePitch(state, state.requestedPitch)
                    setVolume(state)
                    if (!paused && !focusPaused) {
                        player.start()
                        state.started = player.isPlaying
                        event("started", state.channel, state.path, "playing=${state.started} id=${state.id} volume=${state.volume} pitch=${state.pitch}")
                    }
                } catch (error: Exception) { failure(path, error); release(state) }
            }
            player.setOnCompletionListener {
                if (!closed.get() && effect.loop && (audio.loopStartMs > 0 || audio.loopEndMs > 0)) {
                    try {
                        player.seekTo(audio.loopStartMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                        if (!paused && !focusPaused) player.start()
                    } catch (error: Exception) { failure(path, error); release(state) }
                } else release(state)
            }
            player.setOnErrorListener { _, what, extra ->
                failure(path, IllegalStateException("décodeur Android : erreur $what / $extra")); release(state); true
            }
            player.prepareAsync()
        } catch (error: Exception) { failure(path, error); release(state) }
    }

    private fun control(state: Playing, control: ChannelControl) {
        state.requestedPitch = control.pitch
        changePitch(state, control.pitch)
        val volume = control.volume.coerceIn(0, 100) / 100f
        if (control.fadeTicks > 0) fade(state, volume, control.fadeTicks, control.fps)
        else {
            state.fadeRemainingMs = 0; state.stopAfterFade = false
            state.volume = volume
            if (state.ready) setVolume(state)
        }
    }

    private fun changePitch(state: Playing, pitch: Int) {
        require(pitch in 25..400) { "fréquence audio hors plage : $pitch %" }
        if (!state.ready || pitch == state.pitch) return
        val rate = pitch / 100f
        val wasPlaying = state.player.isPlaying
        state.player.playbackParams = PlaybackParams().allowDefaults().setSpeed(rate).setPitch(rate)
        // Setting a non-zero speed can start a prepared/paused MediaPlayer.
        // Preserve pause and defer the first audible start to the explicit host action.
        if (!wasPlaying && state.player.isPlaying) state.player.pause()
        state.pitch = pitch
    }

    private fun setVolume(state: Playing) {
        val volume = (state.volume * duck).coerceIn(0f, 1f)
        state.player.setVolume(volume, volume)
    }

    private fun fade(state: Playing, target: Float, ticks: Int, fps: Int, stop: Boolean = false) {
        state.fadeFrom = state.volume; state.fadeTarget = target
        state.fadeDurationMs = (ticks.toLong() * 1000 / fps.coerceIn(1, 240)).coerceAtLeast(1)
        state.fadeRemainingMs = state.fadeDurationMs; state.stopAfterFade = stop
    }

    fun pause() { handler.post { paused = true; updatePause() } }
    fun resume() { handler.post { paused = false; lastPump = SystemClock.uptimeMillis(); updatePause() } }

    private fun updatePause() {
        if (closed.get()) return
        players.values.toList().forEach { state ->
            if (!state.ready) return@forEach
            try {
                setVolume(state)
                if (paused || focusPaused) {
                    if (state.player.isPlaying) {
                        state.player.pause()
                        event("paused", state.channel, state.path, "playing=${state.player.isPlaying} positionMs=${state.player.currentPosition} id=${state.id}")
                    }
                } else if (!state.player.isPlaying) {
                    state.player.start()
                    val action = if (state.started) "resumed" else "started"
                    state.started = state.player.isPlaying
                    event(action, state.channel, state.path, "playing=${state.started} positionMs=${state.player.currentPosition} id=${state.id}")
                }
            } catch (error: Exception) { failure(state.path, error); release(state) }
        }
    }

    private fun release(state: Playing) {
        if (players.remove(state.id) !== state) return
        val released = runCatching { state.player.setOnErrorListener(null); state.player.setOnPreparedListener(null); state.player.release() }.isSuccess
        event("stopped", state.channel, state.path, "released=$released id=${state.id}")
    }

    /** The origin thread is captured before delivering the event on Android's main thread. */
    private fun event(action: String, channel: Int? = null, path: String? = null, detail: String = "") {
        if (closed.get()) return
        val origin = Thread.currentThread().name
        val message = buildString {
            append(action); append(" | thread="); append(origin)
            if (channel != null) { append(" | channel="); append(channel) }
            if (path != null) { append(" | resource="); append(path) }
            if (detail.isNotBlank()) { append(" | "); append(detail) }
        }
        main.post { if (!closed.get()) onPlaybackEvent(message) }
    }

    private fun failure(path: String, error: Exception) {
        if (closed.get()) return
        event("error", path = path, detail = "cause=${error.message ?: error.javaClass.simpleName}")
        main.post { if (!closed.get()) onError("Audio Wolf « $path » : ${error.message ?: error.javaClass.simpleName}") }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        io.shutdownNow()
        handler.post {
            handler.removeCallbacksAndMessages(null)
            players.values.toList().forEach(::release)
            manager.abandonAudioFocusRequest(focus)
            thread.quitSafely()
            // Only individually named files created by this session are ever removed.
            Thread({
                io.awaitTermination(10, TimeUnit.SECONDS)
                val root = cacheDirectory.canonicalFile
                ownedFiles.forEach { file -> if (file.canonicalFile.parentFile == root && file.name.startsWith(sessionName)) file.delete() }
                ownedFiles.clear(); prepared.clear(); channelControls.clear(); generations.clear()
            }, "Wolf-native-audio-close").apply { isDaemon = true; start() }
        }
    }

    companion object {
        /** Vorbis LOOPSTART/LOOPLENGTH units are samples, per Wolf's material specification. */
        private fun vorbisLoop(bytes: ByteArray): Pair<Int, Int> {
            val limit = minOf(bytes.size, 256 * 1024)
            val header = String(bytes, 0, limit, Charsets.ISO_8859_1)
            val signature = header.indexOf("\u0001vorbis")
            if (signature < 0 || signature + 16 > bytes.size) return 0 to 0
            val rate = ByteBuffer.wrap(bytes, signature + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (rate !in 8_000..384_000) return 0 to 0
            fun value(name: String): Long? = Regex("$name=(\\d{1,18})", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.toLongOrNull()
            val start = value("LOOPSTART") ?: return 0 to 0
            val length = value("LOOPLENGTH")
            val end = value("LOOPEND") ?: length?.let { if (it > Long.MAX_VALUE - start) null else start + it }
            fun millis(samples: Long): Int = (samples.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 / rate)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            return millis(start) to (end?.let(::millis) ?: 0)
        }
    }
}
