package fr.astragames.app.windows

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in Windows PlaySound fixture; observes a real PulseAudio playback stream. */
class WolfAudioRuntimeTest {
    @Test fun windowsAudioReachesAndroidSink() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wolfAudio") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "wolf-probe/audio")
        assertTrue(File(source, "Game.exe").isFile)
        val runtime = WolfRuntimeInstaller(context).root
        ActivityScenario.launch<WolfRuntimeActivity>(WolfRuntimeActivity.intent(context, "synthetic-audio-test", source.toURI().toString(), "Game.exe", "Audio test")).use {
            var observed = false
            for (attempt in 0 until 200) {
                Thread.sleep(1000)
                if (!File(context.cacheDir, "wolf-tmp/pulse.sock").exists()) continue
                val process = ProcessBuilder("/system/bin/linker64", File(runtime, "pulse/pactl").path, "list", "sink-inputs")
                    .redirectErrorStream(true).apply {
                        environment()["PULSE_SERVER"] = "unix:${context.cacheDir}/wolf-tmp/pulse.sock"
                        environment()["LD_LIBRARY_PATH"] = "$runtime/libs/usr/lib:$runtime/pulse/modules:/system/lib64"
                    }.start()
                val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
                process.waitFor()
                if (output.contains("Sink Input #") && output.contains("Corked: no")) {
                    File(context.filesDir, "wolf-probe/audio-stream.txt").writeText(output)
                    observed = true
                    break
                }
            }
            assertTrue("No Windows playback stream reached PulseAudio", observed)
        }
    }
}
