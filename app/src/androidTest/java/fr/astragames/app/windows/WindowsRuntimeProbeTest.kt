package fr.astragames.app.windows

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in low-level diagnostic; full game coverage is in WolfIntegratedRuntimeTest. */
class WindowsRuntimeProbeTest {
    @Test fun wineStartsUnderTheApplicationsSelinuxDomain() {
        probe(command = false)
    }

    @Test fun windowsCommandRunsUnderTheApplicationsSelinuxDomain() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("windowsProbeCommand") == "true")
        probe(command = true)
    }

    private fun probe(command: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("windowsProbe") == "true")
        assertEquals("This probe uses native x86_64 Wine, not ARM emulation", "x86_64", Build.SUPPORTED_ABIS.first())
        val context = instrumentation.targetContext
        assertEquals(36, context.applicationInfo.targetSdkVersion)
        val root = File(context.filesDir, "wolf-probe")
        val prefix = File(root, "prefix-app")
        val wine = File(root, "proton/bin/wine")
        assertTrue("Prepare the research runtime before invoking the probe", wine.isFile)
        val log = File(root, if (command) "command.log" else "probe.log")
        val args = if (command) listOf("cmd", "/c", "echo", "ASTRA_WOLF_SMOKE") else listOf("--version")
        val process = ProcessBuilder(listOf("/system/bin/linker64", wine.path) + args)
            .directory(root)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply {
                environment()["WINEDLLPATH"] = File(root, "proton/lib/wine/x86_64-unix").path
                environment()["WINEPREFIX"] = prefix.path
                environment()["HOME"] = root.path
                environment()["WINESERVER"] = File(root, "proton/bin/wineserver").path
                environment()["WINELOADER"] = wine.path
                if (InstrumentationRegistry.getArguments().getString("windowsProbeBridge") == "true") {
                    environment()["LD_PRELOAD"] = File(root, "libastra_exec_bridge.so").path
                    environment()["ASTRA_RUNTIME_ROOT"] = File(root, "proton").path
                    environment()["ASTRA_WINE_LOADER"] = wine.path
                }
            }.start()
        try {
            val finished = process.waitFor(if (command) 60 else 20, TimeUnit.SECONDS)
            assertTrue("Wine timed out: ${log.readText().takeLast(8192)}", finished)
            val output = log.readText()
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains(if (command) "ASTRA_WOLF_SMOKE" else "wine-"))
            File(root, "app-domain.txt").writeText(File("/proc/self/attr/current").readText())
        } finally {
            process.destroyForcibly()
            if (command) {
                val stop = ProcessBuilder("/system/bin/linker64", File(root, "proton/bin/wineserver").path, "-k")
                    .redirectErrorStream(true).redirectOutput(File(root, "stop.log"))
                    .apply { environment()["WINEPREFIX"] = prefix.path }.start()
                try { stop.waitFor(5, TimeUnit.SECONDS) } finally { stop.destroyForcibly() }
            }
        }
    }
}
