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
import fr.astragames.wolf.WolfParser
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
    source: WolfAssetSource,
    private val cacheDirectory: File,
    private val onError: (String) -> Unit,
    private val onPlaybackEvent: (String) -> Unit = {},
    private val onPlaybackState: (List<fr.astragames.wolf.WolfAudioPlayback>) -> Unit = {},
) : Closeable {
    private val resourceBytes = WolfNativeResourceBytes(source)
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
    private val generations = ConcurrentHashMap<Pair<Int, Int>, Long>()
    private val soundGenerations = ConcurrentHashMap<Triple<Int, Int, String>, Long>()
    private val stoppedVoices = ConcurrentHashMap<Pair<Int, Int>, Long>()
    private val stoppedChannels = ConcurrentHashMap<Int, Long>()
    private val channelControls = ConcurrentHashMap<Pair<Int, Int>, ChannelControl>()
    private val prepared = ConcurrentHashMap<String, AudioFile>()
    private val ownedFiles = ConcurrentHashMap.newKeySet<File>()
    private val sessionName = "wolf-audio-${UUID.randomUUID()}-"
    private val players = linkedMapOf<Long, Playing>() // audio-thread owned
    private val channelGains = mutableMapOf<Int, Float>() // audio-thread owned; nominal voice volumes remain unchanged
    // Compare requests before allocating handler messages or diagnostic events. Serialize
    // the comparison and enqueue so concurrent callers cannot reverse distinct changes.
    private val requestedGlobalGains = IntArray(3) { 100 }
    @Volatile private var nextSeMaster = 100
    private var paused = false
    private var focusPaused = false
    private var duck = 1f
    private var cachedBytes = 0L // synchronized with prepared
    private var lastPump = SystemClock.uptimeMillis()
    private var nextPlaybackSample = 0L

    private data class AudioFile(val file: File, val loopStartMs: Int = 0, val loopEndMs: Int = 0,
        val midi: WolfNativeMidi? = null, val logicalPath: String = "")
    private data class ChannelControl(val order: Long, val volume: Int, val pitch: Int, val fadeTicks: Int,
        val fps: Int, val pan: Int = 0, val position: Int = 0, val loopStart: Int = 0)
    private class Playing(val id: Long, val channel: Int, val path: String, val player: MediaPlayer,
        val effect: NativeEffect.Audio, val audio: AudioFile, var volume: Float,
        var fadeFrom: Float = 0f, var fadeTarget: Float = 0f, var fadeRemainingMs: Long = 0L,
        var fadeDurationMs: Long = 0L, var stopAfterFade: Boolean = false, var ready: Boolean = false,
        var pitch: Int = 100, var requestedPitch: Int = effect.pitch, var started: Boolean = false,
        var pan: Int = effect.pan, var position: Int = effect.position,
        var delayMs: Long = 0L, var requestedLoopStart: Int = effect.loopStart) {
        val voice: Int get() = effect.voice
        val loopStart: Int get() = if (audio.midi == null && requestedLoopStart > 0) requestedLoopStart else audio.loopStartMs
        val loopEnd: Int get() = if (audio.midi == null && requestedLoopStart > 0) 0 else audio.loopEndMs
    }

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
                        if (!state.started && state.delayMs > 0) {
                            state.delayMs = (state.delayMs - delta).coerceAtLeast(0)
                            if (state.delayMs == 0L) beginPlayback(state)
                            return@forEach
                        }
                        if (state.fadeRemainingMs > 0) {
                            state.fadeRemainingMs = (state.fadeRemainingMs - delta).coerceAtLeast(0)
                            val t = 1f - state.fadeRemainingMs.toFloat() / state.fadeDurationMs.coerceAtLeast(1)
                            state.volume = state.fadeFrom + (state.fadeTarget - state.fadeFrom) * t
                            setVolume(state)
                            if (state.fadeRemainingMs == 0L && state.stopAfterFade) { release(state); return@forEach }
                        }
                        if (state.effect.loop && state.loopEnd > 0 &&
                            state.player.isPlaying && state.player.currentPosition >= state.loopEnd) {
                            state.player.seekTo(state.loopStart.toLong(), MediaPlayer.SEEK_CLOSEST)
                        }
                    } catch (error: Exception) { failure(state.path, error); release(state) }
                }
            }
            if (now >= nextPlaybackSample) {
                nextPlaybackSample = now + 50
                val states = players.values.toList().mapNotNull { state ->
                    if (!state.ready || state.channel !in 0..1) null else try {
                        val position = state.player.currentPosition.coerceAtLeast(0)
                        val duration = state.player.duration.coerceAtLeast(0)
                        val midi = state.audio.midi
                        fr.astragames.wolf.WolfAudioPlayback(state.channel, state.voice, state.path,
                            midi?.ticks(position)?.coerceAtMost(midi.lengthTicks) ?: position, midi?.lengthTicks ?: duration)
                    } catch (error: Exception) { failure(state.path, error); release(state); null }
                }
                onPlaybackState(states)
            }
            handler.postDelayed(this, 16)
        }
    }

    init { handler.post {
        event("focus-requested", detail = "result=${manager.requestAudioFocus(focus)}")
        handler.post(pump)
    } }

    /** Sys100/101 changes playing music; Sys102 is sampled only when a new SE is requested. */
    fun setGlobalGain(channel: Int, percentage: Int) {
        if (closed.get()) return
        if (channel !in 0..2 || percentage < 0) {
            failure("Sys${100 + channel}", IllegalArgumentException("gain global hors plage : canal $channel, $percentage %"))
            return
        }
        synchronized(requestedGlobalGains) {
            if (closed.get() || requestedGlobalGains[channel] == percentage) return
            requestedGlobalGains[channel] = percentage
            if (channel == 2) {
                nextSeMaster = percentage
                event("global-gain", channel, detail = "percentage=$percentage futureVoicesOnly=true")
                return
            }
            handler.post {
                if (closed.get()) return@post
                channelGains[channel] = percentage / 100f
                val voices = players.values.filter { it.channel == channel }
                voices.forEach { state ->
                    if (!state.ready) return@forEach
                    try {
                        setVolume(state)
                        event("gain-applied", channel, state.path,
                            "nominal=${state.volume} gain=${channelGains[channel]} effective=${effectiveVolume(state)} playing=${state.player.isPlaying} positionMs=${state.player.currentPosition} id=${state.id}")
                    } catch (error: Exception) { failure(state.path, error); release(state) }
                }
                event("global-gain", channel, detail = "percentage=$percentage voices=${voices.size}")
            }
        }
    }

    fun apply(effect: NativeEffect.Audio, logicalFps: Int = 60) {
        if (closed.get()) return
        val requestedPath = effect.path
        val seMaster = nextSeMaster
        if (effect.channel == 2 && !effect.stop && !requestedPath.isNullOrBlank() && seMaster == 0) {
            event("muted-before-load", effect.channel, requestedPath)
            return
        }
        event(if (effect.stop) "stop-requested" else if (requestedPath.isNullOrBlank()) "update-requested" else "play-requested",
            effect.channel, requestedPath, "volume=${effect.volume} pitch=${effect.pitch} loop=${effect.loop} fadeTicks=${effect.fadeTicks} voice=${effect.voice} pan=${effect.pan} position=${effect.position} delayTicks=${effect.delayTicks}")
        val generation = serial.incrementAndGet()
        val voice = effect.channel to effect.voice
        // BGM/BGS and looping channels replace previous requests. One-shot SEs overlap.
        val single = effect.channel < 2 || effect.loop || effect.stop || requestedPath.isNullOrBlank()
        if (effect.stop) {
            if (effect.voice == -1) stoppedChannels[effect.channel] = generation else stoppedVoices[voice] = generation
        } else if (single && !requestedPath.isNullOrBlank()) generations[voice] = generation
        if (effect.stop || requestedPath.isNullOrBlank()) {
            if (!effect.stop) channelControls[voice] = ChannelControl(generation, effect.volume, effect.pitch, effect.fadeTicks, logicalFps, effect.pan, effect.position, effect.loopStart)
            handler.post {
                if (!closed.get()) players.values.filter { it.channel == effect.channel && (effect.voice == -1 || it.voice == effect.voice) }.toList().forEach { state ->
                    try {
                        if (effect.stop) {
                            if (effect.fadeTicks > 0 && state.ready) fade(state, 0f, effect.fadeTicks, logicalFps, true) else release(state)
                        } else {
                            control(state, ChannelControl(generation, effect.volume, effect.pitch, effect.fadeTicks, logicalFps, effect.pan, effect.position, effect.loopStart))
                        }
                    } catch (error: Exception) { failure(state.path, error); release(state) }
                }
            }
            return
        }
        val path = try { WolfNativeResources.logicalPath(requestedPath) }
        catch (error: Exception) { failure(requestedPath, error); return }
        val soundKey = Triple(effect.channel, effect.voice, path)
        if (!single) soundGenerations[soundKey] = generation
        try {
            io.execute {
                try {
                    val audio = materialize(path, effect.loopStart)
                    handler.post {
                        if (!closed.get() && (stoppedChannels[effect.channel] ?: 0) < generation &&
                            (stoppedVoices[voice] ?: 0) < generation &&
                            (if (single) generations[voice] == generation else soundGenerations[soundKey] == generation))
                            start(effect, audio, path, generation, single, logicalFps, seMaster)
                    }
                } catch (error: Exception) { failure(path, error) }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            if (!closed.get()) failure(path, IllegalStateException("trop de demandes audio simultanées", error))
        }
    }

    fun cache(effect: NativeEffect.AudioCache) {
        if (closed.get()) return
        if (effect.operation == 1) {
            val path = effect.path ?: return
            try { io.execute {
                try { materialize(WolfNativeResources.logicalPath(path)); event("preloaded", path = path) }
                catch (error: Exception) { if (!closed.get()) failure(path, error) }
            } } catch (error: Exception) { failure(path, error) }
        } else handler.post {
            if (closed.get()) return@post
            val usedFiles = players.values.mapTo(hashSetOf()) { it.audio.file }
            synchronized(prepared) {
                val released = prepared.entries.filter { (path, value) -> value.file !in usedFiles &&
                    (effect.operation == 3 || value.logicalPath == effect.path) }
                released.forEach { (path, value) ->
                    prepared.remove(path); cachedBytes -= value.file.length()
                    // Keep owned files until close: an asynchronous pending player can still
                    // hold the prepared descriptor after its cache entry has been released.
                }
                event("cache-released", path = effect.path, detail = "assets=${released.size}")
            }
        }
    }

    private fun materialize(path: String, loopOrKey: Int = 0): AudioFile {
        val payloadPath = WolfNativeResourceBytes.payloadPath(path)
        val cacheKey = if (payloadPath.substringAfterLast('.').equals("mid", true) || payloadPath.substringAfterLast('.').equals("midi", true)) "$path#$loopOrKey" else path
        prepared[cacheKey]?.let { return it }
        val original = resourceBytes.read(WolfParser.rootAssetPath(path), 64 * 1024 * 1024)
        val midi = WolfNativeMidi.parse(original)
        require(midi != null || loopOrKey >= 0) { "Position de boucle négative pour un son échantillonné" }
        val bytes = midi?.transpose(original, loopOrKey) ?: original
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) { "fichier audio vide ou dépassant 64 Mio" }
        require(!closed.get()) { "session audio fermée" }
        val suffix = payloadPath.substringAfterLast('.', "bin").lowercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,6}")) } ?: "bin"
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        synchronized(prepared) {
            prepared[cacheKey]?.let { return it }
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
            val result = AudioFile(file, loop.first, loop.second, midi, path)
            cachedBytes += bytes.size
            prepared[cacheKey] = result
            return result
        }
    }

    private fun start(effect: NativeEffect.Audio, audio: AudioFile, path: String, id: Long, single: Boolean, fps: Int, seMaster: Int) {
        players.values.filter { it.channel == effect.channel && it.voice == effect.voice && (single || it.path == path) }.toList().forEach(::release)
        if (!single && players.values.count { it.channel >= 2 } >= 256) {
            failure(path, IllegalStateException("plus de 256 effets sonores simultanés")); return
        }
        val player = MediaPlayer()
        val targetVolume = if (effect.channel == 2) maxOf(1L, effect.volume.coerceIn(0, 100).toLong() * seMaster / 100).coerceAtMost(100) / 100f
            else effect.volume.coerceIn(0, 100) / 100f
        val state = Playing(id, effect.channel, path, player, effect, audio,
            if (effect.fadeTicks > 0) 0f else targetVolume, delayMs = effect.delayTicks.toLong() * 1000 / fps.coerceIn(1, 240))
        players[id] = state
        try {
            if (effect.fadeTicks > 0) fade(state, targetVolume, effect.fadeTicks, fps)
            channelControls[effect.channel to effect.voice]?.takeIf { it.order > id }?.let { control(state, it) }
            player.setAudioAttributes(attributes)
            player.setDataSource(audio.file.absolutePath)
            player.isLooping = effect.loop && state.loopStart == 0 && state.loopEnd == 0
            player.setOnPreparedListener {
                if (closed.get() || players[id] !== state) { release(state); return@setOnPreparedListener }
                state.ready = true
                try {
                    event("prepared", state.channel, state.path, "durationMs=${player.duration} id=${state.id}")
                    if (effect.loop && (state.loopStart > 0 || state.loopEnd > 0)) {
                        val duration = player.duration
                        require(duration > state.loopStart && (state.loopEnd == 0 || state.loopEnd in (state.loopStart + 1)..duration)) {
                            "bornes de boucle invalides : ${state.loopStart}–${state.loopEnd} ms pour $duration ms"
                        }
                    }
                    changePitch(state, state.requestedPitch)
                    setVolume(state)
                    seek(state, state.position)
                    if (!paused && !focusPaused && state.delayMs == 0L) beginPlayback(state)
                } catch (error: Exception) { failure(path, error); release(state) }
            }
            player.setOnCompletionListener {
                if (!closed.get() && effect.loop && (state.loopStart > 0 || state.loopEnd > 0)) {
                    try {
                        player.seekTo(state.loopStart.toLong(), MediaPlayer.SEEK_CLOSEST)
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
        state.pan = control.pan
        if (state.audio.midi == null) {
            require(control.loopStart >= 0) { "Position de boucle négative" }
            if (state.ready && state.effect.loop && control.loopStart > 0)
                require(control.loopStart < state.player.duration) { "Début de boucle hors de la piste" }
            state.requestedLoopStart = control.loopStart
            state.player.isLooping = state.effect.loop && state.loopStart == 0 && state.loopEnd == 0
            if (state.ready) event("loop-updated", state.channel, state.path, "startMs=${state.loopStart} endMs=${state.loopEnd} id=${state.id}")
        }
        if (control.position > 0) { state.position = control.position; if (state.ready) seek(state, control.position) }
        changePitch(state, control.pitch)
        val volume = control.volume.coerceIn(0, 100) / 100f
        if (control.fadeTicks > 0) fade(state, volume, control.fadeTicks, control.fps)
        else {
            state.fadeRemainingMs = 0; state.stopAfterFade = false
            state.volume = volume
            if (state.ready) setVolume(state)
        }
        if (state.ready) stereoEvent(state)
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
        val volume = effectiveVolume(state)
        val pan = state.pan.coerceIn(-255, 255) / 255f
        val left = volume * if (pan > 0) 1f - pan else 1f
        val right = volume * if (pan < 0) 1f + pan else 1f
        state.player.setVolume(left, right)
    }

    private fun stereoEvent(state: Playing) {
        val volume = effectiveVolume(state); val pan = state.pan / 255f
        event("stereo-volume", state.channel, state.path,
            "voice=${state.voice} left=${volume * if (pan > 0) 1f - pan else 1f} right=${volume * if (pan < 0) 1f + pan else 1f} pan=${state.pan} id=${state.id}")
    }

    private fun seek(state: Playing, position: Int) {
        if (position <= 0) return
        val millis = state.audio.midi?.millis(position) ?: position
        state.player.seekTo(millis.toLong(), MediaPlayer.SEEK_CLOSEST)
        event("seek", state.channel, state.path, "voice=${state.voice} position=$position positionMs=$millis id=${state.id}")
    }

    private fun beginPlayback(state: Playing) {
        state.player.start(); state.started = state.player.isPlaying
        stereoEvent(state)
        event("started", state.channel, state.path,
            "playing=${state.started} id=${state.id} voice=${state.voice} volume=${state.volume} effectiveVolume=${effectiveVolume(state)} pitch=${state.pitch} positionMs=${state.player.currentPosition}")
    }

    private fun effectiveVolume(state: Playing): Float =
        (state.volume * (channelGains[state.channel] ?: 1f) * duck).coerceIn(0f, 1f)

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
                } else if (!state.player.isPlaying && state.delayMs == 0L) {
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
        resourceBytes.close()
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
                soundGenerations.clear(); stoppedVoices.clear(); stoppedChannels.clear()
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
