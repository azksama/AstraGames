package fr.astragames.app.wolfnative

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodecList
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.wolf.NativeFrame
import fr.astragames.wolf.NativePicture
import fr.astragames.wolf.NativeTone
import fr.astragames.wolf.NativeVideoState
import fr.astragames.wolf.NativeVisibilityMask
import fr.astragames.wolf.WolfAssetSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real Android media decoders + EGL readback + the ordinary Wolf Canvas path. */
@RunWith(AndroidJUnit4::class)
class WolfNativeVideoInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun clip(name: String = "reference.mp4") = instrumentation.context.assets.open("wolf-native/video/$name").use { it.readBytes() }

    @Test fun mp4DecodesRealFramesAndAudioWithoutMainThreadIo() {
        movie(NativeVideoState(1, loop = true)).use { state ->
            state.await { state.engine.ready(setOf(state.key)) && (state.engine.state(state.key)?.positionMs ?: 0) >= 300 }
            val status = state.engine.state(state.key)!!
            assertEquals(64, status.width); assertEquals(48, status.height)
            assertTrue("duration=${status.durationMs}", status.durationMs in 2900..3100)
            assertRgb("first red video frame", 254, 0, 0, state.pixel())
            state.await {
                val diagnostic = state.engine.diagnostics(state.key)
                diagnostic?.actualAudioDecoder?.isNotBlank() == true && diagnostic.actualVideoDecoder?.isNotBlank() == true
            }
            val diagnostic = state.engine.diagnostics(state.key)!!
            assertEquals("video/avc", diagnostic.videoMime); assertEquals("audio/mp4a-latm", diagnostic.audioMime)
            assertTrue(diagnostic.audioSessionId > 0)
            assertTrue("actual video frames=${diagnostic.decodedFrames}", diagnostic.decodedFrames >= 2)
            assertEquals(1, state.reads.get()); assertEquals(0, state.mainReads.get()); assertEquals(0, state.backgroundCallbacks.get())
            // Feed measured statuses back as the VM does. This must never seek
            // or create an unbounded callback → frame → callback feedback loop.
            val before = state.callbacks.get()
            val start = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - start < 500) {
                state.engine.state(state.key)?.let { state.engine.request(8, "movie.mp4", it) }
                SystemClock.sleep(20)
            }
            assertTrue("callbacks were not coalesced", state.callbacks.get() - before <= 12)
            assertEquals(1, state.reads.get())
        }
    }

    @Test fun seekPauseAndPlaybackSpeedKeepTheDecodedTimeline() {
        movie(NativeVideoState(2, seekMs = 1500, playing = false)).use { state ->
            state.await { state.engine.ready(setOf(state.key)) && Color.green(state.pixel()) > 200 }
            assertRgb("paused seek must decode the green section", 0, 254, 0, state.pixel())
            val first = state.engine.state(state.key)!!.positionMs
            SystemClock.sleep(250)
            assertTrue(abs(state.engine.state(state.key)!!.positionMs - first) <= 50)
            state.control { it.copy(seekMs = 0, seekRevision = 1, speedPercent = 200, playing = true) }
            // Start measurement after the asynchronous seek/start has actually
            // advanced the clock and decoded the first section again.
            state.await { state.engine.diagnostics(state.key)?.playbackSpeed == 2f &&
                state.engine.state(state.key)!!.positionMs in 200..800 &&
                Color.red(state.pixel()) > 200 && Color.green(state.pixel()) < 40 }
            val before = state.engine.state(state.key)!!.positionMs
            val startedAt = SystemClock.uptimeMillis()
            SystemClock.sleep(500)
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val advanced = state.engine.state(state.key)!!.positionMs - before
            // Status is coalesced to100ms, giving at most200ms of endpoint
            // uncertainty at2x, plus50ms for the poll interval. A1x or frozen
            // clock must fail; startup/buffering is not part of this interval.
            assertTrue("2x advanced=$advanced ms over $elapsed ms", abs(advanced - elapsed * 2) <= 250)
            state.control { it.copy(playing = false, speedPercent = 0) }
            state.await { state.engine.state(state.key)?.playing == false }
            val paused = state.engine.state(state.key)!!.positionMs
            SystemClock.sleep(250)
            assertTrue(abs(state.engine.state(state.key)!!.positionMs - paused) <= 60)
            state.control { it.copy(seekMs = 9000, seekRevision = 2) }
            SystemClock.sleep(150)
            assertTrue("out-of-range seek must not clamp to EOS", abs(state.engine.state(state.key)!!.positionMs - paused) <= 60)
        }
    }

    @Test fun systemGainAndHostSuspensionPreserveNominalControls() {
        movie(NativeVideoState(3, volumePercent = 80, loop = true)).use { state ->
            state.engine.setAudioMaster(25)
            state.await { state.engine.ready(setOf(state.key)) && state.engine.diagnostics(state.key)?.effectiveVolume == .2f }
            assertEquals(80, state.engine.state(state.key)!!.volumePercent)
            state.engine.suspendPlayback(true)
            SystemClock.sleep(180)
            val suspended = state.engine.state(state.key)!!.positionMs
            SystemClock.sleep(220)
            assertTrue(abs(state.engine.state(state.key)!!.positionMs - suspended) <= 50)
            assertTrue("activity pause must not overwrite the VM's playing flag", state.engine.state(state.key)!!.playing)
            state.engine.setAudioMaster(100)
            state.engine.suspendPlayback(false)
            state.await { state.engine.state(state.key)!!.positionMs > suspended + 120 && state.engine.diagnostics(state.key)?.effectiveVolume == .8f }
            assertEquals(80, state.engine.state(state.key)!!.volumePercent)
        }
    }

    @Test fun completionLoopAndShowReplacementKeepTheirOwnTokens() {
        movie(NativeVideoState(4, seekMs = 2700, speedPercent = 200)).use { state ->
            state.await { state.engine.state(state.key)?.let { !it.playing && it.positionMs == it.durationMs && it.durationMs > 0 } == true }
            assertRgb("EOS keeps the decoded blue image", 0, 0, 254, state.pixel())
            val oldKey = state.key
            val fresh = NativeVideoState(5, seekMs = 2700, speedPercent = 200, loop = true)
            state.replace(fresh)
            state.await { state.engine.ready(setOf(state.key)) && state.engine.state(state.key)!!.positionMs < 1700 }
            assertTrue(state.engine.state(state.key)!!.playing)
            assertEquals(5L, state.engine.state(state.key)!!.token)
            assertEquals(1, state.engine.activeCount())
            assertEquals(null, state.engine.peek(oldKey))
            assertEquals(2, state.reads.get())
        }
    }

    @Test fun decodedFramesUsePictureRotationToneAndVisibilityMasks() = composedMovie("mp4")

    @Test fun ogvDecodedFramesUseTheSameRotationToneAndVisibilityMasks() = composedMovie("ogv")

    private fun composedMovie(extension: String) {
        val path = "quadrants.$extension"
        val bytes = clip(path)
        val errors = ConcurrentLinkedQueue<String>()
        lateinit var resources: WolfNativeResources; lateinit var view: WolfNativeView
        val video = NativeVideoState(6, playing = false)
        val picture = NativePicture(2, path, x = 32f, y = 24f, anchor = 1,
            rotation = 180f, tone = NativeTone(-128, 0, 0), maskEnabled = true, video = video)
        val mask = NativeVisibilityMask(64, 48, ByteArray(64 * 48) { index -> if (index % 64 >= 32) 1 else 0 })
        val frame = NativeFrame(64, 48, null, listOf(picture), emptyList(), null, 0, visibilityMask = mask)
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray { assertFalse(Looper.myLooper() == Looper.getMainLooper()); return bytes }
            override fun exists(path: String) = path == "Data/quadrants.$extension"
            override fun list(path: String) = emptyList<String>()
        }
        instrumentation.runOnMainSync {
            resources = WolfNativeResources(source, { view.invalidate() }, { errors.add(it) })
            view = WolfNativeView(instrumentation.targetContext, resources,
                WolfNativeView.Callbacks({ _, _ -> }, { _, _, _ -> }, error = { errors.add(it) }))
            view.configure(WolfNativeView.Config(showFps = false, smooth = false))
            view.layout(0, 0, 64, 48); view.submitFrame(frame)
        }
        try {
            await(errors) { resources.videos.ready(setOf(resources.videos.key(2, path, video))) && view.areVisibilityMasksReady() }
            val output = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { view.draw(Canvas(output)) }
            // Original quadrants: red/green on top, blue/white on bottom.
            // Rotation puts white at top-left; negative red tone halves it.
            assertRgb("rotated white after picture tone", 127, 255, 255, output.getPixel(8, 8))
            assertRgb("rotated green keeps its proportions", 0, 255, 0, output.getPixel(8, 40))
            assertEquals(Color.BLACK, output.getPixel(56, 8)); assertEquals(Color.BLACK, output.getPixel(56, 40))
            assertTrue(errors.isEmpty()); output.recycle()
        } finally { instrumentation.runOnMainSync { view.close(); resources.close() } }
    }

    @Test fun missingContainerAndDamagedMp4ReportAnExplicitDecoderError() {
        val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
            .flatMap { codec -> codec.supportedTypes.filter { it.startsWith("video/") || it.contains("vorbis") }.map { "${codec.name}:$it" } }
        Log.i("WolfNativeVideoTest", "Android codec inventory: ${types.joinToString()}")
        val errors = ConcurrentLinkedQueue<String>(); val reads = AtomicInteger()
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray { reads.incrementAndGet(); return ByteArray(256) }
            override fun exists(path: String) = path == "Data/broken.mp4" || path == "Data/clip.avi"
            override fun list(path: String) = emptyList<String>()
        }
        WolfNativeVideo(source, {}, { errors.add(it) }).use { engine ->
            engine.request(1, "clip.avi", NativeVideoState(7))
            await(errors, permitError = true) { errors.isNotEmpty() }
            assertTrue(errors.single().contains("AVI")); assertEquals(0, reads.get())
            errors.clear()
            engine.request(2, "broken.mp4", NativeVideoState(8))
            await(errors, permitError = true) { errors.isNotEmpty() }
            assertTrue(errors.single().contains("broken.mp4")); assertEquals(1, reads.get())
            SystemClock.sleep(150); assertEquals(1, errors.size)
        }
    }

    @Test fun ogvNativeDecodersReadThreeRealFramesAndVorbisPcm() {
        WolfNativeTheora.ensureAvailable()
        val handle = WolfNativeTheora.open(clip("reference.ogv"))
        try {
            val info = WolfNativeTheora.info(handle)
            assertEquals(listOf(64L, 48L, 3000L, 48000L, 1L), info.toList())
            val colors = IntArray(64 * 48)
            assertEquals(0L, WolfNativeTheora.frameAt(handle, 0, colors))
            assertRgb("native Theora red", 254, 0, 0, colors[24 * 64 + 32])
            assertEquals(15L, WolfNativeTheora.frameAt(handle, 1500, colors))
            assertRgb("native Theora green", 0, 254, 0, colors[24 * 64 + 32])
            assertEquals(27L, WolfNativeTheora.frameAt(handle, 2700, colors))
            assertRgb("native Theora blue", 0, 0, 254, colors[24 * 64 + 32])
            assertEquals(0L, WolfNativeTheora.frameAt(handle, 0, colors))
            assertRgb("backward seek resets Theora packets", 254, 0, 0, colors[24 * 64 + 32])
            val pcm = ByteArray(4096)
            var count = 0; var energy = 0.0; var crossings = 0; var prior = 0
            while (count < 12000) {
                val bytes = WolfNativeTheora.readAudio(handle, pcm)
                assertTrue("Vorbis unexpectedly ended", bytes > 0 && bytes % 2 == 0)
                for (index in 0 until bytes step 2) {
                    if (count >= 12000) break
                    val sample = ((pcm[index].toInt() and 255) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
                    energy += sample.toDouble() * sample
                    if ((prior < 0 && sample >= 0) || (prior > 0 && sample <= 0)) crossings++
                    prior = sample; count++
                }
            }
            val rms = kotlin.math.sqrt(energy / count)
            assertTrue("decoded Vorbis PCM RMS=$rms", rms in 400.0..6000.0)
            assertTrue("440 Hz Vorbis has $crossings zero crossings per quarter second", crossings in 180..250)
            WolfNativeTheora.seekAudio(handle, 1500)
            assertTrue(WolfNativeTheora.readAudio(handle, pcm) > 0)
        } finally { WolfNativeTheora.close(handle) }
        assertThrows(IllegalStateException::class.java) { WolfNativeTheora.info(handle) }
    }

    @Test fun ogvAudioTrackClockDecodesWithoutMainThreadIo() {
        movie(NativeVideoState(20, loop = true), "reference.ogv").use { state ->
            state.await { state.engine.ready(setOf(state.key)) && (state.engine.state(state.key)?.positionMs ?: 0) >= 300 &&
                (state.engine.diagnostics(state.key)?.decodedAudioBytes ?: 0) >= 1000 }
            val status = state.engine.state(state.key)!!
            assertEquals(64, status.width); assertEquals(48, status.height); assertEquals(3000L, status.durationMs)
            assertRgb("Theora plays red during the first second", 254, 0, 0, state.pixel())
            val decoder = state.engine.diagnostics(state.key)!!
            assertEquals("video/theora", decoder.videoMime); assertEquals("audio/vorbis", decoder.audioMime)
            assertEquals("libtheora-1.2.0", decoder.actualVideoDecoder); assertEquals("libvorbis-1.3.7", decoder.actualAudioDecoder)
            assertTrue("a real AudioTrack is required", decoder.audioSessionId > 0)
            assertTrue(decoder.decodedFrames >= 2)
            assertEquals(1, state.reads.get()); assertEquals(0, state.mainReads.get()); assertEquals(0, state.backgroundCallbacks.get())
            val before = state.callbacks.get()
            val start = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - start < 400) {
                state.engine.state(state.key)?.let { state.engine.request(8, "movie.ogv", it) }
                SystemClock.sleep(20)
            }
            assertTrue("Theora callbacks must be coalesced", state.callbacks.get() - before <= 10)
            assertEquals(1, state.reads.get())
        }
    }

    @Test fun ogvPauseSeekAndSpeedFollowTheSharedAudioTimeline() {
        movie(NativeVideoState(21, seekMs = 1500, playing = false), "reference.ogv").use { state ->
            state.await { state.engine.ready(setOf(state.key)) && Color.green(state.pixel()) > 200 }
            val first = state.engine.state(state.key)!!.positionMs
            SystemClock.sleep(220)
            assertTrue(abs(state.engine.state(state.key)!!.positionMs - first) <= 35)
            state.control { it.copy(seekMs = 0, seekRevision = 1, speedPercent = 200, playing = true) }
            state.await { state.engine.diagnostics(state.key)?.playbackSpeed == 2f && state.engine.state(state.key)!!.positionMs < 350 }
            SystemClock.sleep(500)
            assertTrue("2x OGV timeline=${state.engine.state(state.key)!!.positionMs}", state.engine.state(state.key)!!.positionMs in 650..1650)
            state.control { it.copy(playing = false, speedPercent = 0) }
            state.await { state.engine.state(state.key)?.playing == false }
            SystemClock.sleep(120)
            val paused = state.engine.state(state.key)!!.positionMs
            SystemClock.sleep(220)
            assertTrue(abs(state.engine.state(state.key)!!.positionMs - paused) <= 35)
            state.control { it.copy(seekMs = 1500, seekRevision = 2) }
            state.await { Color.green(state.pixel()) > 200 && state.engine.state(state.key)!!.positionMs in 1470..1530 }
            state.control { it.copy(seekMs = 9000, seekRevision = 3) }
            SystemClock.sleep(150)
            assertTrue("invalid OGV seek must remain ignored", state.engine.state(state.key)!!.positionMs in 1470..1530)
        }
    }

    @Test fun ogvGainAndSuspensionDoNotRewriteTheGameVolumeOrPlayingFlag() {
        movie(NativeVideoState(22, volumePercent = 80, loop = true), "reference.ogv").use { state ->
            state.engine.setAudioMaster(25)
            state.await { state.engine.ready(setOf(state.key)) && state.engine.diagnostics(state.key)?.effectiveVolume == .2f }
            state.engine.suspendPlayback(true)
            SystemClock.sleep(180)
            val paused = state.engine.state(state.key)!!.positionMs
            SystemClock.sleep(220)
            assertTrue(abs(state.engine.state(state.key)!!.positionMs - paused) <= 35)
            assertEquals(80, state.engine.state(state.key)!!.volumePercent)
            assertTrue(state.engine.state(state.key)!!.playing)
            state.engine.setAudioMaster(100); state.engine.suspendPlayback(false)
            state.await { state.engine.state(state.key)!!.positionMs > paused + 120 && state.engine.diagnostics(state.key)?.effectiveVolume == .8f }
            assertEquals(80, state.engine.state(state.key)!!.volumePercent)
        }
    }

    @Test fun ogvFourTimesPlaybackFeedsEnoughRealPcmToItsAudioTrack() {
        movie(NativeVideoState(26, playing = false), "reference.ogv").use { state ->
            state.await { state.engine.ready(setOf(state.key)) && state.engine.diagnostics(state.key)?.audioSessionId?.let { it > 0 } == true }
            state.control { it.copy(seekMs = 0, seekRevision = 1, speedPercent = 400, playing = true) }
            state.await { state.engine.diagnostics(state.key)?.playbackSpeed == 4f && state.engine.state(state.key)!!.positionMs < 350 }
            SystemClock.sleep(350)
            val current = state.engine.state(state.key)!!.positionMs
            assertTrue("4x must follow consumed PCM, timeline=$current", current in 900..1900)
            assertTrue("4x Vorbis must feed its real track", state.engine.diagnostics(state.key)!!.decodedAudioBytes >= 80_000)
            assertTrue("resampled PCM must actually reach AudioTrack", state.engine.diagnostics(state.key)!!.writtenAudioBytes >= 20_000)
            state.control { it.copy(playing = false) }
            state.await { state.engine.state(state.key)?.playing == false }
        }
    }

    @Test fun ogvPcmRateConversionPreservesStereoPitchAndFractionalPacketBoundaries() {
        fun rate(percent: Int, inputFrames: Int, outputFrames: Int): List<Pair<Int, Int>> {
            val samples = ByteArray(8 * 4)
            for (frame in 0 until 8) for (channel in 0..1) {
                val value = (frame * 1000 - 3000) * (if (channel == 0) 1 else -1)
                samples[frame * 4 + channel * 2] = value.toByte()
                samples[frame * 4 + channel * 2 + 1] = (value shr 8).toByte()
            }
            var read = 0
            val converter = WolfNativePcmRateConverter(2, inputFrames) { buffer ->
                val count = minOf(buffer.size, samples.size - read)
                samples.copyInto(buffer, 0, read, read + count); read += count; count
            }
            val output = ByteArray(outputFrames * 4)
            val values = mutableListOf<Pair<Int, Int>>()
            while (true) {
                val count = converter.read(output, percent)
                if (count == 0) break
                for (offset in 0 until count step 4) {
                    fun sample(channel: Int) = ((output[offset + channel * 2].toInt() and 255) or
                        (output[offset + channel * 2 + 1].toInt() shl 8)).toShort().toInt()
                    values += sample(0) to sample(1)
                }
            }
            assertEquals(samples.size.toLong(), converter.decodedBytes)
            return values
        }
        // Independent exact interpolation of the authored linear stereo ramp:
        // the two channels remain opposite and 4x advances four input frames.
        assertEquals(listOf(-3000 to 3000, 1000 to -1000), rate(400, 3, 2))
        assertEquals(listOf(-3000 to 3000, -500 to 500, 2000 to -2000, 4000 to -4000), rate(250, 2, 3))
        val half = (0 until 16).map { index ->
            val value = minOf(index * 500 - 3000, 4000); value to -value
        }
        assertEquals(half, rate(50, 1, 3))
        assertEquals(half, rate(50, 5, 1))
        assertEquals((0 until 8).map { (it * 1000 - 3000) to (3000 - it * 1000) }, rate(100, 2, 4))
    }

    @Test fun ogvCompletionAndLoopRetainTheirImageAndReplaceDecoderTokens() {
        movie(NativeVideoState(23, seekMs = 2700, speedPercent = 200), "reference.ogv").use { state ->
            state.await { state.engine.state(state.key)?.let { !it.playing && it.positionMs == it.durationMs && it.durationMs > 0 } == true }
            assertRgb("Theora EOS keeps blue", 0, 0, 254, state.pixel())
            val oldKey = state.key
            state.replace(NativeVideoState(24, seekMs = 2700, speedPercent = 200, loop = true))
            state.await { state.engine.ready(setOf(state.key)) && state.engine.state(state.key)!!.positionMs < 1700 }
            assertTrue(state.engine.state(state.key)!!.playing)
            assertEquals(24L, state.engine.state(state.key)!!.token); assertEquals(1, state.engine.activeCount())
            assertEquals(null, state.engine.peek(oldKey)); assertEquals(2, state.reads.get())
        }
    }

    @Test fun protectedMp4AliasesUseTheRealDecoderAudioClockPauseAndSeek() = protectedMoviePlayback("mp4")

    @Test fun protectedOgvAliasesUseTheRealTheoraVorbisAudioTrackClockPauseAndSeek() = protectedMoviePlayback("ogv")

    private fun protectedMoviePlayback(extension: String) {
        val bytes = instrumentation.context.assets.open("wolf-native/wolfx/reference.$extension.wolfx").use { it.readBytes() }
        for (explicit in listOf(false, true)) {
            val path = "movie.$extension" + if (explicit) ".wolfx" else ""
            Movie(NativeVideoState(100 + if (explicit) 1L else 0L, seekMs = 1200, playing = false, volumePercent = 80), bytes, path, protectedPayload = true).use { state ->
                state.await { state.engine.ready(setOf(state.key)) && state.engine.state(state.key)?.playing == false &&
                    state.engine.state(state.key)!!.positionMs in 1100..1400 && Color.green(state.pixel()) > 200 &&
                    (state.engine.diagnostics(state.key)?.audioSessionId ?: 0) > 0 }
                assertRgb("protected $extension decodes the original green frame", 0, 254, 0, state.pixel())
                val info = state.engine.state(state.key)!!
                assertEquals(64, info.width); assertEquals(48, info.height)
                assertTrue(info.durationMs in 2900..3100); assertTrue(info.positionMs in 1100..1400)
                val decoder = state.engine.diagnostics(state.key)!!
                assertTrue(decoder.videoMime.contains(if (extension == "ogv") "theora" else "avc"))
                assertTrue(decoder.audioMime.orEmpty().contains(if (extension == "ogv") "vorbis" else "mp4a"))
                assertTrue("a real Android audio session is required", decoder.audioSessionId > 0)
                state.engine.setAudioMaster(25)
                state.control { it.copy(playing = true) }
                state.await { state.engine.state(state.key)!!.positionMs > 1500 && state.engine.diagnostics(state.key)!!.effectiveVolume == .2f }
                assertEquals(80, state.engine.state(state.key)!!.volumePercent)
                if (extension == "ogv") {
                    assertTrue(state.engine.diagnostics(state.key)!!.decodedAudioBytes > 0)
                    assertTrue(state.engine.diagnostics(state.key)!!.writtenAudioBytes > 0)
                }
                state.control { it.copy(playing = false, speedPercent = 0) }
                state.await { state.engine.state(state.key)?.playing == false }
                val paused = state.engine.state(state.key)!!.positionMs
                SystemClock.sleep(180)
                assertTrue(abs(state.engine.state(state.key)!!.positionMs - paused) <= 60)
                state.control { it.copy(seekMs = 2400, seekRevision = 1) }
                state.await { state.engine.state(state.key)!!.positionMs >= 2300 && Color.blue(state.pixel()) > 200 && Color.green(state.pixel()) < 40 }
                assertRgb("protected $extension seek decodes blue without a bitmap placeholder", 0, 0, 254, state.pixel())
                assertEquals(1, state.reads.get()); assertEquals(0, state.mainReads.get()); assertEquals(0, state.backgroundCallbacks.get())
            }
        }
    }

    @Test fun ogvFrameCropAndCorruptionHaveExplicitNativeResults() {
        WolfNativeTheora.ensureAvailable()
        val handle = WolfNativeTheora.open(clip("crop.ogv"))
        try {
            assertEquals(listOf(50L, 34L, 1000L, 0L, 0L), WolfNativeTheora.info(handle).toList())
            val pixels = IntArray(50 * 34)
            WolfNativeTheora.frameAt(handle, 0, pixels)
            assertRgb("crop top-left", 254, 0, 0, pixels[5 * 50 + 5])
            assertRgb("crop top-right", 0, 254, 0, pixels[5 * 50 + 44])
            assertRgb("crop bottom-left", 0, 0, 254, pixels[29 * 50 + 5])
            assertRgb("crop bottom-right", 255, 255, 255, pixels[29 * 50 + 44])
        } finally { WolfNativeTheora.close(handle) }
        val damaged = clip("reference.ogv").also { it[30] = (it[30].toInt() xor 1).toByte() }
        val error = assertThrows(IllegalStateException::class.java) { WolfNativeTheora.open(damaged) }
        assertTrue("CRC should fail without aborting ART: ${error.message}", error.message.orEmpty().contains("corrompue"))
        val errors = ConcurrentLinkedQueue<String>()
        val source = object : WolfAssetSource {
            override fun read(path: String) = damaged
            override fun exists(path: String) = path == "Data/damaged.ogv"
            override fun list(path: String) = emptyList<String>()
        }
        WolfNativeVideo(source, {}, { errors.add(it) }).use { engine ->
            engine.request(1, "damaged.ogv", NativeVideoState(25))
            await(errors, permitError = true) { errors.isNotEmpty() }
            assertTrue(errors.single().contains("damaged.ogv")); assertTrue(errors.single().contains("corrompue"))
            SystemClock.sleep(120); assertEquals(1, errors.size)
        }
    }

    @Test fun ogvCodedFrameLimitsAlsoCoverPaddingOutsideTheVisiblePicture() {
        WolfNativeTheora.ensureAvailable()
        val bytes = clip("crop.ogv")
        val segments = bytes[26].toInt() and 255
        val payload = 27 + segments
        assertEquals(0x80, bytes[payload].toInt() and 255)
        assertEquals("theora", String(bytes, payload + 1, 6, Charsets.US_ASCII))
        // Theora's identification header stores coded width in 16px blocks.
        // Keep its 50×34 visible crop but declare 16384 coded pixels per row.
        bytes[payload + 10] = 4; bytes[payload + 11] = 0
        var pageLength = payload
        for (index in 0 until segments) pageLength += bytes[27 + index].toInt() and 255
        for (index in 22..25) bytes[index] = 0
        var crc = 0
        for (index in 0 until pageLength) {
            crc = crc xor ((bytes[index].toInt() and 255) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
        }
        for (index in 0..3) bytes[22 + index] = (crc ushr (index * 8)).toByte()
        val error = assertThrows(IllegalStateException::class.java) { WolfNativeTheora.open(bytes) }
        assertTrue("coded-size rejection expected: ${error.message}", error.message.orEmpty().contains("cadre codé"))
    }

    private fun assertRgb(message: String, red: Int, green: Int, blue: Int, actual: Int) {
        val expected = intArrayOf(red, green, blue); val channels = intArrayOf(Color.red(actual), Color.green(actual), Color.blue(actual))
        assertTrue("$message expected=${expected.toList()} got=${channels.toList()}", channels.indices.all { abs(expected[it] - channels[it]) <= 6 })
        assertEquals(255, Color.alpha(actual))
    }

    private fun await(errors: ConcurrentLinkedQueue<String>, permitError: Boolean = false, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!predicate() && (permitError || errors.isEmpty()) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        if (!permitError) assertTrue(errors.joinToString(), errors.isEmpty())
        assertTrue("video condition timed out; errors=${errors.joinToString()}", predicate())
    }

    private fun movie(initial: NativeVideoState, name: String = "reference.mp4"): Movie {
        val bytes = clip(name)
        return Movie(initial, bytes, "movie.${name.substringAfterLast('.')}")
    }

    private inner class Movie(initial: NativeVideoState, private val bytes: ByteArray, private val path: String,
        private val protectedPayload: Boolean = false) : Closeable {
        val errors = ConcurrentLinkedQueue<String>()
        val reads = AtomicInteger(); val mainReads = AtomicInteger(); val callbacks = AtomicInteger(); val backgroundCallbacks = AtomicInteger()
        val measured = AtomicReference<NativeVideoState?>()
        private var control = initial
        private val originalPath = "Data/${WolfNativeResourceBytes.payloadPath(path)}"
        private val protectedPath = "$originalPath.wolfx"
        private val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray {
                reads.incrementAndGet(); if (Looper.myLooper() == Looper.getMainLooper()) mainReads.incrementAndGet()
                assertEquals(if (protectedPayload) protectedPath else originalPath, path)
                return bytes
            }
            override fun exists(path: String) = if (protectedPayload) path == originalPath || path == protectedPath else path == originalPath
            override fun list(path: String) = emptyList<String>()
        }
        val engine = WolfNativeVideo(source, {}, { errors.add(it) }).apply { onState = { _, value ->
            if (Looper.myLooper() != Looper.getMainLooper()) backgroundCallbacks.incrementAndGet()
            callbacks.incrementAndGet(); measured.set(value)
        } }
        var key = engine.request(8, path, initial)
            private set
        fun control(change: (NativeVideoState) -> NativeVideoState) { control = change(control); engine.request(8, path, control) }
        fun replace(value: NativeVideoState) { control = value; key = engine.request(8, path, value); engine.retain(setOf(key)) }
        fun pixel(): Int = engine.peek(key)?.getPixel(32, 24) ?: 0
        fun await(predicate: () -> Boolean) {
            try { this@WolfNativeVideoInstrumentedTest.await(errors, predicate = predicate) }
            catch (failure: AssertionError) {
                throw AssertionError("${failure.message}; state=${engine.state(key)}; decoder=${engine.diagnostics(key)}", failure)
            }
        }
        override fun close() = engine.close()
    }
}
