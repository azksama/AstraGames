package fr.astragames.app.wolfnative

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.wolf.NativeEffect
import fr.astragames.wolf.WolfAssetSource
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class WolfNativeAudioInstrumentedTest {
    @Test fun realAndroidDecoderPlaysPausesResumesAndStopsWavAndMidi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-audio-test-${UUID.randomUUID()}").apply { mkdirs() }
        val events = ConcurrentLinkedQueue<String>(); val errors = ConcurrentLinkedQueue<String>()
        val files = mapOf("Data/test.wav" to wave(), "Data/test.mid" to midi())
        val source = object : WolfAssetSource {
            override fun read(path: String) = files[path] ?: error("Missing authored audio $path")
            override fun list(path: String) = emptyList<String>()
            override fun exists(path: String) = path in files
        }
        val audio = WolfNativeAudio(context, source, root, { errors.offer(it) }, { events.offer(it) })
        fun await(event: String, path: String) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (errors.isEmpty() && SystemClock.elapsedRealtime() < end && events.none { it.startsWith("$event |") && it.contains(path) }) SystemClock.sleep(30)
            assertTrue(errors.joinToString(), errors.isEmpty())
            assertTrue("Missing $event for $path: ${events.joinToString()}", events.any { it.startsWith("$event |") && it.contains(path) })
        }
        try {
            audio.apply(NativeEffect.Audio(0, "test.wav", loop = true)); await("started", "test.wav")
            assertTrue(events.any { it.startsWith("started |") && it.contains("playing=true") })
            audio.pause(); await("paused", "test.wav"); audio.resume(); await("resumed", "test.wav")
            audio.apply(NativeEffect.Audio(0, stop = true)); await("stopped", "test.wav")
            audio.apply(NativeEffect.Audio(0, "test.mid", loop = true)); await("prepared", "test.mid"); await("started", "test.mid")
            audio.apply(NativeEffect.Audio(0, stop = true)); await("stopped", "test.mid")
        } finally {
            audio.close()
            val end = SystemClock.elapsedRealtime() + 2_000
            while (root.listFiles().orEmpty().isNotEmpty() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }

    private fun wave(): ByteArray = ByteArrayOutputStream().run {
        fun le(value: Int, size: Int = 4) { repeat(size) { write(value ushr (it * 8) and 255) } }
        val samples = 16_000
        write("RIFF".toByteArray()); le(36 + samples * 2); write("WAVEfmt ".toByteArray()); le(16)
        le(1, 2); le(1, 2); le(8_000); le(16_000); le(2, 2); le(16, 2); write("data".toByteArray()); le(samples * 2)
        repeat(samples) { le((kotlin.math.sin(it * 2.0 * Math.PI * 440 / 8_000) * 2_000).toInt(), 2) }; toByteArray()
    }
    private fun midi(): ByteArray = byteArrayOf(
        0x4d, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0, 96,
        0x4d, 0x54, 0x72, 0x6b, 0, 0, 0, 13,
        0, 0x90.toByte(), 60, 64, 0x83.toByte(), 0, 0x80.toByte(), 60, 0, 0, 0xff.toByte(), 0x2f, 0,
    )
}
