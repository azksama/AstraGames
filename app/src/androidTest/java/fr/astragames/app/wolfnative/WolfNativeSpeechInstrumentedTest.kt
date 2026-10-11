package fr.astragames.app.wolfnative

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.wolf.NativeEffect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class WolfNativeSpeechInstrumentedTest {
    @Test fun installedAndroidSpeechEngineReturnsVoicesAndCompletesOrStopsRealUtterances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val voices = ConcurrentLinkedQueue<Pair<Long, List<String>>>()
        val playing = ConcurrentLinkedQueue<Pair<Long, Boolean>>()
        val diagnostic = ConcurrentLinkedQueue<String>()
        val host = WolfNativeSpeech(context, { id, value -> playing.add(id to value) },
            { id, values -> voices.add(id to values) }, { diagnostic.add(it) })
        fun waitUntil(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 20_000
            while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
            assertTrue("Voices=$voices; playing=$playing; diagnostics=$diagnostic", predicate())
        }
        try {
            host.voices(NativeEffect.SpeechVoices(1))
            waitUntil { voices.any { it.first == 1L } }
            val names = voices.first { it.first == 1L }.second
            assumeTrue("Android has no installed speech data: $diagnostic", names.isNotEmpty())
            assertEquals(names.distinct().sorted(), names)
            val english = names.firstOrNull { it.contains("en-us", true) } ?: names.first()
            host.speak(NativeEffect.Speech(2, "Astra can play Wolf RPG directly on Android.", voice = english))
            waitUntil { playing.any { it == 2L to true } || diagnostic.any { it.contains("erreur") || it.contains("refusé") } }
            assertTrue("Real speech did not start: $diagnostic", playing.any { it == 2L to true })
            host.stop()
            waitUntil { playing.any { it == 2L to false } }
            host.speak(NativeEffect.Speech(3, "Wolf RPG.", speed = 3, volume = 40, voice = english))
            waitUntil { playing.any { it == 3L to true } }
            waitUntil { playing.any { it == 3L to false } }
        } finally { host.close() }
    }
}
