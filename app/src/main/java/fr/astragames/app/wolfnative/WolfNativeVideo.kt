package fr.astragames.app.wolfnative

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import fr.astragames.wolf.NativeVideoState
import fr.astragames.wolf.WolfAssetSource
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A real Android movie decoder, sharing the game's picture composition.
 * MediaPlayer owns A/V timing and AudioTrack; its SurfaceTexture is read into
 * immutable Canvas bitmaps on a GL worker. No game read, media call or GL call
 * happens in onDraw or on the Android main thread.
 *
 * MP4 uses Android's media decoders. OGV uses pinned Theora/Vorbis decoders
 * and a separate AudioTrack worker. AVI/MPG still report their missing codec.
 */
internal class WolfNativeVideo(
    source: WolfAssetSource,
    private val onChanged: () -> Unit,
    private val onError: (String) -> Unit,
    private val resourceBytes: WolfNativeResourceBytes = WolfNativeResourceBytes(source),
) : Closeable {
    data class Key(val id: Int, val token: Long, val path: String)
    data class Diagnostics(val videoMime: String, val videoDecoder: String,
        val audioMime: String?, val audioDecoder: String?, val audioSessionId: Int,
        val decodedFrames: Long, val playbackSpeed: Float, val effectiveVolume: Float,
        val actualVideoDecoder: String?, val actualAudioDecoder: String?, val decodedAudioBytes: Long = 0,
        val writtenAudioBytes: Long = 0)

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("Wolf-native-video").apply { start() }
    private val worker = Handler(thread.looper)
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "Wolf-native-video-read") }
    private val sessions = ConcurrentHashMap<Key, Session>()
    private val closed = AtomicBoolean()
    private val changedQueued = AtomicBoolean()
    private val allocationLock = Any()
    private var compressedBytes = 0L
    private var frameBytes = 0L
    @Volatile private var suspended = false
    @Volatile private var masterGain = 1f
    @Volatile var onState: (Int, NativeVideoState) -> Unit = { _, _ -> }
    private var gl: MovieGl? = null // worker only

    fun key(id: Int, path: String, state: NativeVideoState) = Key(id, state.token, WolfNativeResources.logicalPath(path))

    fun request(id: Int, path: String, state: NativeVideoState): Key {
        val key = key(id, path, state)
        if (closed.get()) return key
        val session = sessions.computeIfAbsent(key) { Session(it, state) }
        val prior = session.desired
        session.desired = state
        if (session.started.compareAndSet(false, true)) session.load()
        else if (prior.seekRevision != state.seekRevision || prior.speedPercent != state.speedPercent ||
            prior.volumePercent != state.volumePercent || prior.playing != state.playing || prior.loop != state.loop) session.queueControls()
        return key
    }

    fun peek(key: Key): Bitmap? = sessions[key]?.bitmap
    fun state(key: Key): NativeVideoState? = sessions[key]?.status
    fun ready(keys: Collection<Key>) = keys.all { sessions[it]?.let { session -> session.bitmap != null && !session.failed } == true }
    fun diagnostics(key: Key): Diagnostics? = sessions[key]?.diagnostics
    internal fun activeCount() = sessions.size

    /** SHOW tokens, including outgoing transitions, define the lifetime of a decoder. */
    fun retain(keys: Set<Key>) {
        sessions.entries.forEach { (key, session) -> if (key !in keys && sessions.remove(key, session)) session.close() }
    }

    fun setAudioMaster(percent: Int) {
        masterGain = percent.coerceIn(0, 100) / 100f
        sessions.values.forEach(Session::queueControls)
    }

    fun suspendPlayback(paused: Boolean) {
        suspended = paused
        sessions.values.forEach(Session::queueControls)
    }

    private fun changed() {
        if (!closed.get() && changedQueued.compareAndSet(false, true)) main.post {
            changedQueued.set(false)
            if (!closed.get()) onChanged()
        }
    }

    private data class Probe(val width: Int, val height: Int, val durationMs: Long,
        val videoMime: String, val videoDecoder: String, val audioMime: String?, val audioDecoder: String?)

    /** Each extractor/player gets an independent, seekable view of the same immutable bytes. */
    private class Bytes(private val bytes: ByteArray) : MediaDataSource() {
        override fun getSize() = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0) return -1
            if (position >= bytes.size) return -1
            val count = minOf(size.toLong(), bytes.size.toLong() - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() = Unit
    }

    private fun probe(bytes: ByteArray): Probe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(Bytes(bytes))
            var video: MediaFormat? = null; var audio: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (video == null && mime.startsWith("video/")) video = format
                if (audio == null && mime.startsWith("audio/")) audio = format
            }
            val format = video ?: error("aucune piste vidéo reconnue par MediaExtractor")
            val videoMime = format.getString(MediaFormat.KEY_MIME) ?: error("type de vidéo absent")
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val decoder = codecs.findDecoderForFormat(format) ?: error("codec vidéo Android absent : $videoMime")
            val audioMime = audio?.getString(MediaFormat.KEY_MIME)
            val audioDecoder = audio?.let { codecs.findDecoderForFormat(it) ?: error("codec audio Android absent : $audioMime") }
            val width = format.getInteger(MediaFormat.KEY_WIDTH); val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= MAX_PIXELS) {
                "vidéo trop grande pour la composition Canvas ($width × $height ; maximum $MAX_PIXELS pixels)"
            }
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            return Probe(width, height, duration, videoMime, decoder, audioMime, audioDecoder)
        } finally { extractor.release() }
    }

    private inner class Session(val key: Key, initial: NativeVideoState) : Closeable {
        val started = AtomicBoolean()
        private val cancelled = AtomicBoolean()
        private val controlsQueued = AtomicBoolean()
        private val statusQueued = AtomicBoolean()
        @Volatile var desired = initial
        @Volatile var bitmap: Bitmap? = null
        @Volatile var status: NativeVideoState? = null
        @Volatile var diagnostics: Diagnostics? = null
        @Volatile var failed = false
        private var loadTask: Future<*>? = null
        private var player: MediaPlayer? = null // subsequent fields worker only
        private var theora: WolfNativeTheoraMovie? = null
        private var surface: MovieSurface? = null
        private var mediaBytes: Bytes? = null
        private var metadata: Probe? = null
        private var reservedCompressed = 0L
        private var reservedFrames = 0L
        private var prepared = false
        private var seeking = false
        private var ended = false
        private var appliedSeek = Long.MIN_VALUE
        private var seekInFlight = Long.MIN_VALUE
        private var requestedPlay = false
        private var appliedSpeed = Float.NaN
        private var appliedVolume = Float.NaN
        private var frames = 0L
        private var lastStatusAt = 0L
        private var lastPlaying = false
        private var restoringEnd = false
        private var soughtImage: Bitmap? = null
        private val poll = object : Runnable {
            override fun run() {
                if (cancelled.get() || failed || !prepared) return
                guarded { publishStatus(); worker.postDelayed(this, 50) }
            }
        }

        fun load() {
            loadTask = io.submit {
                var nativeHandle = 0L
                try {
                    val payloadPath = WolfNativeResourceBytes.payloadPath(key.path)
                    val extension = payloadPath.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                    require(extension == "mp4" || extension == "ogv") {
                        "format ${payloadPath.substringAfterLast('.', "inconnu").uppercase(java.util.Locale.ROOT)} : codec natif absent (MP4 et OGV Theora/Vorbis pris en charge)"
                    }
                    val bytes = resourceBytes.read(fr.astragames.wolf.WolfParser.rootAssetPath(key.path), MAX_FILE_BYTES)
                    check(!cancelled.get() && !closed.get()) { "lecture vidéo annulée" }
                    require(bytes.size <= MAX_FILE_BYTES) { "vidéo dépassant 128 Mio" }
                    synchronized(allocationLock) {
                        check(!cancelled.get() && !closed.get()) { "lecture vidéo annulée" }
                        require(compressedBytes + bytes.size <= COMPRESSED_BUDGET) { "vidéos simultanées dépassant 192 Mio de données compressées" }
                        compressedBytes += bytes.size; reservedCompressed = bytes.size.toLong()
                    }
                    if (extension == "ogv") {
                        WolfNativeTheora.ensureAvailable()
                        nativeHandle = WolfNativeTheora.open(bytes)
                        val values = WolfNativeTheora.info(nativeHandle)
                        val handle = nativeHandle
                        val info = Probe(values[0].toInt(), values[1].toInt(), values[2], "video/theora", "libtheora-1.2.0",
                            if (values[3] > 0) "audio/vorbis" else null, if (values[3] > 0) "libvorbis-1.3.7" else null)
                        worker.post {
                            if (!cancelled.get() && !closed.get()) guarded { openTheora(handle, values, info) }
                            else { WolfNativeTheora.close(handle); releaseReservations() }
                        }
                        nativeHandle = 0 // ownership passed to the worker
                    } else {
                        val info = probe(bytes)
                        worker.post { if (!cancelled.get() && !closed.get()) guarded { open(bytes, info) } else releaseReservations() }
                    }
                } catch (error: Exception) { if (!cancelled.get() && !closed.get()) worker.post { fail(error) } }
                catch (_: OutOfMemoryError) { if (!cancelled.get() && !closed.get()) worker.post { fail(IllegalStateException("mémoire insuffisante pour lire la vidéo")) } }
                finally { if (nativeHandle != 0L) WolfNativeTheora.close(nativeHandle) }
            }
        }

        private fun reserveFrames(info: Probe) {
            val reservation = info.width.toLong() * info.height * 16 // decode/GL + readback + scratch + immutable bitmap
            synchronized(allocationLock) {
                require(frameBytes + reservation <= FRAME_BUDGET) { "vidéos simultanées dépassant 96 Mio de textures et tampons" }
                frameBytes += reservation; reservedFrames = reservation
            }
        }

        private fun openTheora(handle: Long, values: LongArray, info: Probe) {
            try {
                metadata = info
                reserveFrames(info)
                theora = WolfNativeTheoraMovie(handle, values, worker, desired,
                    onFrame = { image -> if (!cancelled.get()) { bitmap = image; changed() } },
                    onState = { value, decoded, sessionId, speed, volume, pcm, written ->
                        status = value
                        diagnostics = Diagnostics(info.videoMime, info.videoDecoder, info.audioMime, info.audioDecoder,
                            sessionId, decoded, speed, volume, info.videoDecoder, info.audioDecoder, pcm, written)
                        postStatus()
                    }, onError = { failure -> fail(failure) })
                prepared = true
                applyControls()
            } catch (failure: Throwable) {
                if (theora == null) WolfNativeTheora.close(handle)
                throw failure
            }
        }

        private fun open(bytes: ByteArray, info: Probe) {
            metadata = info
            reserveFrames(info)
            val context = gl ?: MovieGl().also { gl = it }
            surface = MovieSurface(context, info.width, info.height, worker) { capture() }
            mediaBytes = Bytes(bytes)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                setSurface(surface!!.surface)
                setVolume(0f, 0f) // preparing and the initial seek must never emit stray audio
                setOnPreparedListener { guarded { onPrepared() } }
                setOnSeekCompleteListener { guarded { onSeekComplete() } }
                setOnCompletionListener { guarded {
                    ended = true
                    publishStatus(force = true)
                    changed()
                } }
                setOnErrorListener { _, what, extra ->
                    fail(IllegalStateException("décodeur ${info.videoDecoder} (${info.videoMime}), erreur MediaPlayer $what/$extra"))
                    true
                }
                setDataSource(mediaBytes!!)
                prepareAsync()
            }
        }

        private fun onPrepared() {
            prepared = true
            val media = player ?: return
            val info = metadata!!
            val width = media.videoWidth.takeIf { it > 0 } ?: info.width
            val height = media.videoHeight.takeIf { it > 0 } ?: info.height
            require(width == info.width && height == info.height) { "résolution vidéo modifiée par le décodeur ($width × $height)" }
            metadata = info.copy(durationMs = media.duration.toLong().takeIf { it > 0 } ?: info.durationMs)
            val control = desired
            // A saved decoder resumes at its measured timeline, never at frame zero.
            val initialPosition = if (control.seekMs != 0L || control.seekRevision != 0L) control.seekMs else control.positionMs
            restoringEnd = !control.playing && metadata!!.durationMs > 0 && initialPosition >= metadata!!.durationMs
            seeking = true; seekInFlight = control.seekRevision
            media.seekTo(validSeek(initialPosition), MediaPlayer.SEEK_CLOSEST)
            publishStatus(force = true)
            worker.post(poll)
        }

        private fun validSeek(position: Long): Long {
            val duration = metadata?.durationMs ?: 0
            // DxLib OGV refuses positions >= duration. The initial restoration of
            // an ended movie decodes its last frame, while keeping its EOS state.
            return position.coerceAtLeast(0).let { if (duration > 0 && it >= duration) duration - 1 else it }
        }

        private fun onSeekComplete() {
            appliedSeek = seekInFlight
            seeking = false
            ended = restoringEnd
            restoringEnd = false
            soughtImage?.let { bitmap = it; soughtImage = null; frames++; changed() }
            applyControls()
            publishStatus(force = true)
        }

        fun queueControls() {
            if (!cancelled.get() && controlsQueued.compareAndSet(false, true)) worker.post {
                controlsQueued.set(false)
                if (!cancelled.get() && !closed.get()) guarded { applyControls() }
            }
        }

        private fun applyControls() {
            theora?.let { it.controls(desired, suspended, masterGain); return }
            val media = player ?: return
            if (!prepared) return
            val control = desired
            if (seeking) return // the latest command is applied by onSeekComplete
            if (control.seekRevision != appliedSeek) {
                val duration = metadata!!.durationMs
                appliedSeek = control.seekRevision
                if (control.seekMs >= 0 && (duration <= 0 || control.seekMs < duration)) {
                    if (media.isPlaying) media.pause()
                    seeking = true; seekInFlight = control.seekRevision
                    soughtImage = null
                    media.seekTo(control.seekMs, MediaPlayer.SEEK_CLOSEST)
                    return
                }
            }
            media.isLooping = control.loop
            val volume = control.volumePercent.coerceIn(0, 100) / 100f * masterGain
            if (volume != appliedVolume) { media.setVolume(volume, volume); appliedVolume = volume }
            val wantPlay = control.playing && control.speedPercent > 0
            val resumed = wantPlay && !requestedPlay
            requestedPlay = wantPlay
            if (resumed) ended = false
            if (!wantPlay || suspended || ended) {
                if (media.isPlaying) media.pause()
            } else {
                val speed = control.speedPercent / 100f
                if (speed != appliedSpeed) {
                    // DxLib changes the movie sound's playback frequency together
                    // with the movie clock; pitch follows speed, as for game audio.
                    media.playbackParams = PlaybackParams().allowDefaults().setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
                        .setSpeed(speed).setPitch(speed)
                    appliedSpeed = speed
                }
                if (!media.isPlaying) media.start()
            }
            publishStatus(force = true)
        }

        private fun capture() = guarded {
            val output = surface ?: return@guarded
            val image = output.capture()
            if (seeking) soughtImage = image
            else {
                bitmap = image
                frames++
                publishStatus()
                changed()
            } // published bitmaps are immutable; display lists own their lifetime
        }

        private fun publishStatus(force: Boolean = false) {
            val media = player ?: return
            val info = metadata ?: return
            if (!prepared) return
            val control = desired
            val playing = control.playing && control.speedPercent > 0 && !ended
            val now = SystemClock.uptimeMillis()
            if (!force && playing == lastPlaying && now - lastStatusAt < 100) return
            val duration = info.durationMs.coerceAtLeast(0)
            val current = if (ended) duration else media.currentPosition.toLong().coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
            // Host suspension temporarily stops Android audio; it must not change
            // the VM's nominal pause/play setting or corrupt a subsequent resume.
            status = control.copy(positionMs = current, durationMs = duration, playing = playing, width = info.width, height = info.height)
            val priorDiagnostics = diagnostics
            val metrics = if (priorDiagnostics?.actualVideoDecoder == null || info.audioMime != null && priorDiagnostics?.actualAudioDecoder == null) media.metrics else null
            diagnostics = Diagnostics(info.videoMime, info.videoDecoder, info.audioMime, info.audioDecoder,
                media.audioSessionId, frames, appliedSpeed.takeIf { it.isFinite() } ?: 1f, appliedVolume.takeIf { it.isFinite() } ?: 0f,
                metrics?.getString(MediaPlayer.MetricsConstants.CODEC_VIDEO) ?: priorDiagnostics?.actualVideoDecoder,
                metrics?.getString(MediaPlayer.MetricsConstants.CODEC_AUDIO) ?: priorDiagnostics?.actualAudioDecoder)
            lastStatusAt = now; lastPlaying = playing
            postStatus()
        }

        private fun postStatus() {
            if (statusQueued.compareAndSet(false, true)) main.post {
                statusQueued.set(false)
                if (!closed.get() && !cancelled.get() && sessions[key] === this) status?.let { onState(key.id, it) }
            }
        }

        private inline fun guarded(action: () -> Unit) {
            if (cancelled.get() || closed.get() || failed) return
            try { action() }
            catch (error: Exception) { fail(error) }
            catch (_: OutOfMemoryError) { fail(IllegalStateException("mémoire insuffisante pour décoder/composer la vidéo")) }
        }

        private fun fail(error: Exception) {
            if (failed || cancelled.get() || closed.get()) return
            failed = true
            dispose()
            main.post { if (!cancelled.get() && !closed.get() && sessions[key] === this) {
                onError("Vidéo Wolf « ${key.path} » : ${error.message ?: error.javaClass.simpleName}")
            } }
        }

        private fun releaseReservations() = synchronized(allocationLock) {
            compressedBytes -= reservedCompressed; reservedCompressed = 0
            frameBytes -= reservedFrames; reservedFrames = 0
        }

        private fun dispose() {
            worker.removeCallbacks(poll)
            theora?.close(); theora = null
            player?.let { media ->
                media.setOnPreparedListener(null); media.setOnSeekCompleteListener(null); media.setOnCompletionListener(null); media.setOnErrorListener(null)
                runCatching { media.release() }
            }
            player = null; mediaBytes = null
            surface?.let { runCatching { it.close() } }; surface = null
            bitmap = null; soughtImage = null; prepared = false
            releaseReservations()
        }

        override fun close() {
            if (!cancelled.compareAndSet(false, true)) return
            loadTask?.cancel(true)
            worker.post { dispose() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resourceBytes.close()
        main.removeCallbacksAndMessages(null)
        sessions.values.forEach(Session::close); sessions.clear()
        io.shutdownNow()
        worker.post {
            gl?.let { runCatching { it.close() } }; gl = null
            thread.quitSafely()
        }
    }

    /** Shared EGL context, never made current by the game's Canvas/UI thread. */
    private class MovieGl : Closeable {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val window: EGLSurface
        val program: Int
        val position: Int
        val coordinates: Int
        val textureMatrix: Int
        val vertex: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        val uv: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

        init {
            require(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, null, 0, null, 0)) { "initialisation EGL vidéo impossible" }
            val configurations = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            require(EGL14.eglChooseConfig(display, attributes, 0, configurations, 0, 1, count, 0) && count[0] > 0) { "configuration EGL vidéo absente" }
            context = EGL14.eglCreateContext(display, configurations[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            require(context != EGL14.EGL_NO_CONTEXT) { "contexte EGL vidéo impossible" }
            window = EGL14.eglCreatePbufferSurface(display, configurations[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            require(window != EGL14.EGL_NO_SURFACE) { "surface EGL vidéo impossible" }
            current()
            val vs = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 p; attribute vec2 uv; uniform mat4 transform; varying vec2 t; void main(){gl_Position=p;t=(transform*vec4(uv,0.0,1.0)).xy;}")
            val fs = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 t; void main(){gl_FragColor=texture2D(image,t);}")
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program)
            val success = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, success, 0)
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
            require(success[0] != 0) { "programme GL vidéo : $log" }
            position = GLES20.glGetAttribLocation(program, "p"); coordinates = GLES20.glGetAttribLocation(program, "uv")
            textureMatrix = GLES20.glGetUniformLocation(program, "transform")
            checkGl("initialisation vidéo")
        }

        fun current() { check(EGL14.eglMakeCurrent(display, window, window, context)) { "contexte vidéo EGL perdu" } }
        private fun shader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code); GLES20.glCompileShader(shader)
            val success = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, success, 0)
            if (success[0] == 0) { val reason = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); error("shader vidéo : $reason") }
            return shader
        }
        override fun close() {
            current(); GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, window); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
        }
    }

    private class MovieSurface(private val gl: MovieGl, private val width: Int, private val height: Int,
        handler: Handler, onFrame: () -> Unit) : Closeable {
        private val external = IntArray(1)
        private val target = IntArray(1)
        private val framebuffer = IntArray(1)
        private val matrix = FloatArray(16)
        private val readback = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        private val colors = IntArray(width * height)
        private val texture: SurfaceTexture
        val surface: Surface

        init {
            gl.current()
            GLES20.glGenTextures(1, external, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, external[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            texture = SurfaceTexture(external[0]).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener({ onFrame() }, handler)
            }
            surface = Surface(texture)
            GLES20.glGenTextures(1, target, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glGenFramebuffers(1, framebuffer, 0); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target[0], 0)
            require(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "tampon GL vidéo incomplet" }
            checkGl("surface vidéo")
        }

        fun capture(): Bitmap {
            gl.current()
            texture.updateTexImage(); texture.getTransformMatrix(matrix)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0]); GLES20.glViewport(0, 0, width, height)
            GLES20.glDisable(GLES20.GL_BLEND); GLES20.glUseProgram(gl.program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, external[0])
            GLES20.glUniformMatrix4fv(gl.textureMatrix, 1, false, matrix, 0)
            GLES20.glEnableVertexAttribArray(gl.position); GLES20.glEnableVertexAttribArray(gl.coordinates)
            gl.vertex.position(0); gl.uv.position(0)
            GLES20.glVertexAttribPointer(gl.position, 2, GLES20.GL_FLOAT, false, 0, gl.vertex)
            GLES20.glVertexAttribPointer(gl.coordinates, 2, GLES20.GL_FLOAT, false, 0, gl.uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            readback.position(0)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
            checkGl("lecture de l’image vidéo")
            // GL rows start at the bottom, Canvas rows at the top. Read RGBA
            // bytes explicitly so this is independent of native integer order.
            for (y in 0 until height) for (x in 0 until width) {
                val at = ((height - 1 - y) * width + x) * 4
                val r = readback.get(at).toInt() and 255
                val g = readback.get(at + 1).toInt() and 255
                val b = readback.get(at + 2).toInt() and 255
                colors[y * width + x] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        }

        override fun close() {
            texture.setOnFrameAvailableListener(null); surface.release()
            gl.current(); texture.release()
            GLES20.glDeleteFramebuffers(1, framebuffer, 0); GLES20.glDeleteTextures(1, target, 0); GLES20.glDeleteTextures(1, external, 0)
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 128 * 1024 * 1024
        private const val MAX_PIXELS = 4_194_304L
        private const val COMPRESSED_BUDGET = 192L * 1024 * 1024
        private const val FRAME_BUDGET = 96L * 1024 * 1024
        private fun checkGl(stage: String) {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "$stage : erreur GL 0x${error.toString(16)}" }
        }
    }
}
