package fr.astragames.app.wolfnative

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.wolf.NativeEffect
import fr.astragames.wolf.WolfAssetSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeNotNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class WolfNativeAudioInstrumentedTest {
    @Test fun livePlaybackPublishesDecoderPositionsMidiTicksAndIndependentBgsVoiceLifetimes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-audio-state-${UUID.randomUUID()}").apply { mkdirs() }
        val errors = ConcurrentLinkedQueue<String>(); val events = ConcurrentLinkedQueue<String>()
        val latest = java.util.concurrent.atomic.AtomicReference<List<fr.astragames.wolf.WolfAudioPlayback>>(emptyList())
        val source = object : WolfAssetSource {
            val files = mapOf("Data/field.wav" to wave(), "Data/notes.mid" to midi())
            override fun read(path: String) = files[path] ?: error("Missing authored track $path")
            override fun exists(path: String) = path in files
            override fun list(path: String) = emptyList<String>()
        }
        val audio = WolfNativeAudio(context, source, root, { errors.offer(it) }, { events.offer(it) }, {
            assertNotEquals("Decoder sampling must stay off the main thread", android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
            latest.set(it)
        })
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (errors.isEmpty() && !condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertTrue(errors.joinToString(), errors.isEmpty()); assertTrue("Live audio state did not arrive: ${latest.get()}", condition())
        }
        try {
            audio.apply(NativeEffect.Audio(0, "field.wav", loop = true))
            audio.apply(NativeEffect.Audio(1, "notes.mid", loop = true, voice = 3))
            await { latest.get().size == 2 }
            val first = latest.get()
            assertEquals(2000, first.single { it.channel == 0 }.duration)
            assertEquals(384, first.single { it.channel == 1 }.duration)
            assertEquals(3, first.single { it.channel == 1 }.voice)
            await { latest.get().single { it.channel == 0 }.position > 100 && latest.get().single { it.channel == 1 }.position > 10 }
            audio.pause(); await { events.count { it.startsWith("paused |") } == 2 }
            SystemClock.sleep(100)
            val paused = latest.get().associate { it.channel to it.position }
            SystemClock.sleep(150)
            assertEquals(paused, latest.get().associate { it.channel to it.position })
            audio.resume(); await { events.count { it.startsWith("resumed |") } == 2 }
            await { latest.get().single { it.channel == 0 }.position > paused.getValue(0) }
            audio.apply(NativeEffect.Audio(1, stop = true, voice = 3))
            await { latest.get().size == 1 && latest.get().single().channel == 0 }
            audio.apply(NativeEffect.Audio(0, stop = true)); await { latest.get().isEmpty() }
        } finally {
            audio.close()
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (root.listFiles().orEmpty().isNotEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }

    @Test fun protectedWaveAliasesFeedRealAndroidPlayersAndMaterializeOnlyPlaintextPayloads() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val wrapped = instrumentation.context.assets.open("wolf-native/wolfx/sine.wav.wolfx").use { it.readBytes() }
        val plaintext = instrumentation.context.assets.open("wolf-native/wolfx/sine.wav").use { it.readBytes() }
        val root = File(context.cacheDir, "native-wolfx-audio-${UUID.randomUUID()}").apply { mkdirs() }
        val events = ConcurrentLinkedQueue<String>(); val errors = ConcurrentLinkedQueue<String>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                assertEquals("protected audio takes priority over the clear file", "Data/sine.wav.wolfx", path)
                reads.incrementAndGet(); return wrapped
            }
            override fun exists(path: String) = path == "Data/sine.wav" || path == "Data/sine.wav.wolfx"
            override fun list(path: String) = emptyList<String>()
        }
        val audio = WolfNativeAudio(context, source, root, { errors.offer(it) }, { events.offer(it) })
        fun await(predicate: (String) -> Boolean): String {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (errors.isEmpty() && events.none(predicate) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            assertTrue(errors.joinToString(), errors.isEmpty())
            return events.firstOrNull(predicate) ?: error(events.joinToString())
        }
        try {
            audio.apply(NativeEffect.Audio(0, "sine.wav", volume = 80, position = 500, loop = true))
            val first = await { it.startsWith("started |") && it.contains("channel=0") }
            assertTrue(first, first.contains("effectiveVolume=0.8"))
            audio.setGlobalGain(1, 25)
            audio.apply(NativeEffect.Audio(1, "sine.wav.wolfx", volume = 60, position = 800, loop = true, voice = 3))
            val second = await { it.startsWith("started |") && it.contains("channel=1") }
            assertTrue(second, second.contains("effectiveVolume=0.15"))
            assertEquals("implicit/explicit aliases share one worker read/decode", 1, reads.get())
            val files = root.listFiles().orEmpty().filter { it.extension == "wav" }
            assertEquals(2, files.size)
            files.forEach { file -> assertArrayEquals("Android MediaPlayer receives the original PCM bytes", plaintext, file.readBytes()) }
            assertTrue(root.listFiles().orEmpty().none { it.extension == "wolfx" || it.extension == "tmp" })
            audio.pause(); await { it.startsWith("paused |") && it.contains("channel=0") }
            audio.resume(); await { it.startsWith("resumed |") && it.contains("channel=1") }
            assertEquals(2, events.count { it.startsWith("started |") })
        } finally {
            audio.close()
            val end = SystemClock.elapsedRealtime() + 2_000
            while (root.listFiles().orEmpty().isNotEmpty() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }

    @Test fun seMasterIsCapturedAtRequestAndMuteAvoidsReadingAnUnavailableAsset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-se-master-${UUID.randomUUID()}").apply { mkdirs() }
        val events = ConcurrentLinkedQueue<String>(); val errors = ConcurrentLinkedQueue<String>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray { reads.incrementAndGet(); check(path == "Data/test.wav"); return wave() }
            override fun exists(path: String) = path == "Data/test.wav"
            override fun list(path: String) = emptyList<String>()
        }
        val audio = WolfNativeAudio(context, source, root, { errors.offer(it) }, { events.offer(it) })
        fun await(predicate: (String) -> Boolean): String {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (errors.isEmpty() && events.none(predicate) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            assertTrue(errors.joinToString(), errors.isEmpty())
            return events.firstOrNull(predicate) ?: error(events.joinToString())
        }
        try {
            audio.setGlobalGain(2, 0)
            audio.apply(NativeEffect.Audio(2, "missing.wav"))
            await { it.startsWith("muted-before-load |") }
            assertEquals(0, reads.get())
            audio.setGlobalGain(2, 50)
            audio.apply(NativeEffect.Audio(2, "test.wav", volume = 80))
            val started = await { it.startsWith("started |") }
            assertTrue(started, started.contains("effectiveVolume=0.4"))
            audio.setGlobalGain(2, 0)
            audio.pause(); await { it.startsWith("paused |") }
            audio.resume(); await { it.startsWith("resumed |") }
            assertFalse(events.any { it.startsWith("gain-applied |") && it.contains("channel=2") })
            assertEquals(1, reads.get())
        } finally {
            audio.close()
            val end = SystemClock.elapsedRealtime() + 2_000
            while (root.listFiles().orEmpty().isNotEmpty() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }

    @Test fun multipleBgsVoicesPanSeekAndDelayedSeUseIndependentRealAndroidPlayers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-audio-voices-${UUID.randomUUID()}").apply { mkdirs() }
        val events = ConcurrentLinkedQueue<String>(); val errors = ConcurrentLinkedQueue<String>()
        val files = mapOf("Data/rain.wav" to wave(), "Data/wind.wav" to wave(), "Data/click.wav" to wave(), "Data/notes.mid" to midi())
        val source = object : WolfAssetSource {
            override fun read(path: String) = files[path] ?: error("Missing audio $path")
            override fun exists(path: String) = path in files
            override fun list(path: String) = emptyList<String>()
        }
        val audio = WolfNativeAudio(context, source, root, { errors.offer(it) }, { events.offer(it) })
        fun await(predicate: (String) -> Boolean): String {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (errors.isEmpty() && events.none(predicate) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            assertTrue(errors.joinToString(), errors.isEmpty())
            return events.firstOrNull(predicate) ?: error(events.joinToString())
        }
        try {
            audio.apply(NativeEffect.Audio(1, "rain.wav", 80, loop = true, position = 400, loopStart = 500, pan = -255))
            await { it.startsWith("started |") && it.contains("rain.wav") }
            audio.apply(NativeEffect.Audio(1, "wind.wav", 60, loop = true, position = 700, loopStart = 200, voice = 3, pan = 255))
            await { it.startsWith("started |") && it.contains("wind.wav") }
            assertTrue(events.any { it.startsWith("stereo-volume |") && it.contains("rain.wav") && it.contains("left=0.8 right=0.0") })
            assertTrue(events.any { it.startsWith("stereo-volume |") && it.contains("wind.wav") && it.contains("left=0.0 right=0.6") })
            audio.apply(NativeEffect.Audio(1, volume = 40, loop = true, position = 1000))
            await { it.startsWith("seek |") && it.contains("rain.wav") && it.contains("positionMs=1000") }
            assertEquals(1, events.count { it.startsWith("started |") && it.contains("rain.wav") })
            audio.apply(NativeEffect.Audio(1, stop = true, voice = 3))
            await { it.startsWith("stopped |") && it.contains("wind.wav") }
            audio.setGlobalGain(1, 50)
            await { it.startsWith("gain-applied |") && it.contains("rain.wav") && it.contains("playing=true") }
            val begin = SystemClock.elapsedRealtime()
            audio.apply(NativeEffect.Audio(2, "click.wav", voice = 8, delayTicks = 12))
            await { it.startsWith("started |") && it.contains("click.wav") }
            assertTrue("12-frame SE delay was skipped", SystemClock.elapsedRealtime() - begin >= 150)
            audio.apply(NativeEffect.Audio(0, "notes.mid", loop = true, position = 96, loopStart = 12))
            await { it.startsWith("started |") && it.contains("notes.mid") }
            assertTrue(events.any { it.startsWith("seek |") && it.contains("notes.mid") && it.contains("positionMs=500") })
            audio.apply(NativeEffect.Audio(2, stop = true, voice = -1))
            await { it.startsWith("stopped |") && it.contains("click.wav") }
        } finally {
            audio.close()
            val end = SystemClock.elapsedRealtime() + 2_000
            while (root.listFiles().orEmpty().isNotEmpty() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }

    @Test fun globalGainUpdatesTheRealPlayingVoiceWithoutRestartAndAppliesToLaterVoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-audio-gain-test-${UUID.randomUUID()}").apply { mkdirs() }
        val events = ConcurrentLinkedQueue<String>(); val errors = ConcurrentLinkedQueue<String>()
        val files = mapOf("Data/first.wav" to wave(), "Data/second.wav" to wave())
        val source = object : WolfAssetSource {
            override fun read(path: String) = files[path] ?: error("Missing authored audio $path")
            override fun list(path: String) = emptyList<String>()
            override fun exists(path: String) = path in files
        }
        val audio = WolfNativeAudio(context, source, root, { errors.offer(it) }, { events.offer(it) })
        fun await(predicate: (String) -> Boolean): String {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (errors.isEmpty() && SystemClock.elapsedRealtime() < end && events.none(predicate)) SystemClock.sleep(20)
            assertTrue(errors.joinToString(), errors.isEmpty())
            return events.firstOrNull(predicate) ?: error("Missing audio event: ${events.joinToString()}")
        }
        fun field(event: String, name: String) = Regex("(?:\\s|^)${name}=([^\\s]+)").find(event)?.groupValues?.get(1)
            ?: error("Field $name absent: $event")
        try {
            audio.apply(NativeEffect.Audio(0, "first.wav", volume = 80, loop = true))
            val started = await { it.startsWith("started |") && it.contains("first.wav") }
            val id = field(started, "id")
            audio.setGlobalGain(0, 50)
            val gain = await { it.startsWith("gain-applied |") && it.contains("first.wav") }
            assertEquals(id, field(gain, "id"))
            assertEquals(.8f, field(gain, "nominal").toFloat(), .00001f)
            assertEquals(.4f, field(gain, "effective").toFloat(), .00001f)
            assertEquals("true", field(gain, "playing"))
            assertEquals(1, events.count { it.startsWith("started |") && it.contains("first.wav") })
            audio.apply(NativeEffect.Audio(0, "second.wav", volume = 60, loop = true))
            val later = await { it.startsWith("started |") && it.contains("second.wav") }
            assertEquals(.6f, field(later, "volume").toFloat(), .00001f)
            assertEquals(.3f, field(later, "effectiveVolume").toFloat(), .00001f)
            audio.apply(NativeEffect.Audio(2, "first.wav", volume = 100))
            val sound = await { it.startsWith("started |") && it.contains("first.wav") && it.contains("channel=2") }
            assertEquals(1f, field(sound, "effectiveVolume").toFloat(), .00001f)
        } finally {
            audio.close()
            val end = SystemClock.elapsedRealtime() + 2_000
            while (root.listFiles().orEmpty().isNotEmpty() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }

    @Test fun repeatedGlobalGainsAreSilentWhileChangesAndNewVoicesKeepTheirRealVolumes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-audio-gain-dedup-${UUID.randomUUID()}").apply { mkdirs() }
        val events = ConcurrentLinkedQueue<String>(); val errors = ConcurrentLinkedQueue<String>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val files = mapOf("Data/first.wav" to wave(), "Data/second.wav" to wave())
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray { reads.incrementAndGet(); return files[path] ?: error("Missing authored audio $path") }
            override fun exists(path: String) = path in files
            override fun list(path: String) = emptyList<String>()
        }
        val audio = WolfNativeAudio(context, source, root, { errors.offer(it) }, { events.offer(it) })
        fun await(predicate: (String) -> Boolean): String {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (errors.isEmpty() && events.none(predicate) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            assertTrue(errors.joinToString(), errors.isEmpty())
            return events.firstOrNull(predicate) ?: error("Missing audio event: ${events.joinToString()}")
        }
        fun field(event: String, name: String) = Regex("(?:\\s|^)${name}=([^\\s]+)").find(event)?.groupValues?.get(1)
            ?: error("Field $name absent: $event")
        fun globalCount() = events.count { it.startsWith("global-gain |") }
        fun gainCount(channel: Int) = events.count { it.startsWith("gain-applied |") && it.contains("channel=$channel") }
        fun repeated(music: Int, ambience: Int, sound: Int) {
            repeat(180) {
                audio.setGlobalGain(0, music); audio.setGlobalGain(1, ambience); audio.setGlobalGain(2, sound)
            }
        }
        try {
            repeated(100, 100, 100)
            audio.apply(NativeEffect.Audio(0, "first.wav", volume = 80, loop = true))
            val music = await { it.startsWith("started |") && it.contains("first.wav") && it.contains("channel=0") }
            assertEquals(.8f, field(music, "effectiveVolume").toFloat(), .00001f)
            assertEquals("initial defaults must not enqueue per-frame diagnostics", 0, globalCount())

            repeated(50, 25, 50)
            val changed = await { it.startsWith("gain-applied |") && it.contains("channel=0") }
            await { it.startsWith("global-gain |") && it.contains("channel=1") && it.contains("percentage=25") }
            await { globalCount() == 3 }
            assertEquals(field(music, "id"), field(changed, "id"))
            assertEquals(.4f, field(changed, "effective").toFloat(), .00001f)
            assertEquals(3, globalCount()); assertEquals(1, gainCount(0)); assertEquals(0, gainCount(1)); assertEquals(0, gainCount(2))
            assertTrue(events.any { it.startsWith("global-gain |") && it.contains("channel=2") && it.contains("futureVoicesOnly=true") })

            audio.apply(NativeEffect.Audio(0, "second.wav", volume = 60, loop = true, voice = 3))
            val laterMusic = await { it.startsWith("started |") && it.contains("second.wav") && it.contains("channel=0") }
            assertEquals(.3f, field(laterMusic, "effectiveVolume").toFloat(), .00001f)
            audio.apply(NativeEffect.Audio(1, "first.wav", volume = 60, loop = true))
            val ambience = await { it.startsWith("started |") && it.contains("channel=1") }
            assertEquals(.15f, field(ambience, "effectiveVolume").toFloat(), .00001f)
            audio.apply(NativeEffect.Audio(2, "first.wav", volume = 80, loop = true, voice = 7))
            val sound = await { it.startsWith("started |") && it.contains("channel=2") }
            assertEquals(.4f, field(sound, "effectiveVolume").toFloat(), .00001f)
            assertEquals("starting a new voice must not require resubmitting its master gain", 3, globalCount())

            repeated(75, 100, 0)
            val louder = await { it.startsWith("gain-applied |") && it.contains("channel=0") &&
                field(it, "id") == field(music, "id") && field(it, "gain").toFloat() == .75f }
            await { it.startsWith("global-gain |") && it.contains("channel=1") && it.contains("percentage=100") }
            await { globalCount() == 6 }
            assertEquals(.6f, field(louder, "effective").toFloat(), .00001f)
            assertEquals(6, globalCount()); assertEquals(3, gainCount(0)); assertEquals(1, gainCount(1)); assertEquals(0, gainCount(2))
            val readsBeforeMute = reads.get()
            audio.apply(NativeEffect.Audio(2, "missing.wav"))
            await { it.startsWith("muted-before-load |") && it.contains("missing.wav") }
            assertEquals(readsBeforeMute, reads.get())
            assertFalse("a SE master change must leave existing sound voices alive", events.any {
                it.startsWith("stopped |") && it.contains("id=${field(sound, "id")}")
            })

            repeated(50, 25, 50) // Revisiting a prior value is a real change, not an ever-seen cache hit.
            audio.apply(NativeEffect.Audio(2, "second.wav", volume = 60, loop = true, voice = 8))
            val laterSound = await { it.startsWith("started |") && it.contains("channel=2") && it.contains("second.wav") }
            await { globalCount() == 9 }
            assertEquals(.3f, field(laterSound, "effectiveVolume").toFloat(), .00001f)
            assertEquals(9, globalCount()); assertEquals(5, gainCount(0)); assertEquals(2, gainCount(1)); assertEquals(0, gainCount(2))
            assertEquals("global gain changes must not restart any existing voice", 5, events.count { it.startsWith("started |") })
            assertTrue(errors.joinToString(), errors.isEmpty())
        } finally {
            audio.close()
            val end = SystemClock.elapsedRealtime() + 2_000
            while (root.listFiles().orEmpty().isNotEmpty() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }

    @Test fun officialWolf2AndWolf3VorbisSoundsUseTheRealAndroidDecoder() {
        val official = InstrumentationRegistry.getArguments().getString("nativeOfficialRoot")
        assumeNotNull(official)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-official-audio-test-${UUID.randomUUID()}").apply { mkdirs() }
        val sounds = listOf("22961" to "Data/SystemFile/[SE]LevelUp.ogg", "3729" to "Data/SystemFile/SE_LevelUp.ogg")
        try {
            for ((version, path) in sounds) {
                val sourceRoot = File(official!!, version)
                val source = object : WolfAssetSource {
                    override fun read(path: String): ByteArray {
                        val file = File(sourceRoot, path)
                        check(file.canonicalPath.startsWith(sourceRoot.canonicalPath + File.separator))
                        return file.readBytes()
                    }
                    override fun exists(path: String) = File(sourceRoot, path).isFile
                    override fun list(path: String) = emptyList<String>()
                }
                val events = ConcurrentLinkedQueue<String>(); val errors = ConcurrentLinkedQueue<String>()
                val audio = WolfNativeAudio(context, source, root, { errors.offer(it) }, { events.offer(it) })
                try {
                    audio.apply(NativeEffect.Audio(2, path))
                    val end = SystemClock.elapsedRealtime() + 15_000
                    while (errors.isEmpty() && events.none { it.startsWith("started |") && it.contains(path) } && SystemClock.elapsedRealtime() < end) SystemClock.sleep(30)
                    assertTrue("WOLF $version: ${errors.joinToString()}", errors.isEmpty())
                    assertTrue("Official Vorbis did not prepare: ${events.joinToString()}", events.any { it.startsWith("prepared |") && it.contains(path) })
                    assertTrue("Official Vorbis did not start: ${events.joinToString()}", events.any { it.startsWith("started |") && it.contains(path) && it.contains("playing=true") })
                    audio.apply(NativeEffect.Audio(2, stop = true))
                } finally { audio.close() }
            }
        } finally {
            val end = SystemClock.elapsedRealtime() + 2_000
            while (root.listFiles().orEmpty().isNotEmpty() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }

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
