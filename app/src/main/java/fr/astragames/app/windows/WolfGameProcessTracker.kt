package fr.astragames.app.windows

import android.os.Process
import android.system.Os
import java.io.File

/** Only the game launch inherits this token; prefix services and Astra tools do not. */
internal class WolfGameProcessTracker(private val token: String, private val prefix: String, private val runtime: String) {
    fun liveChildren(): List<Int> = File("/proc").listFiles().orEmpty().mapNotNull { entry ->
        val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
        if (pid == Process.myPid()) return@mapNotNull null
        runCatching {
            if (Os.stat(entry.path).st_uid != Process.myUid()) return@runCatching null
            val state = File(entry, "stat").readText().substringAfterLast(") ").substringBefore(' ')
            if (state == "Z" || state == "X") return@runCatching null
            val environment = readBounded(File(entry, "environ")).split('\u0000').mapNotNull {
                val split = it.indexOf('=')
                if (split > 0) it.substring(0, split) to it.substring(split + 1) else null
            }.toMap()
            if (!belongsToSession(environment, token, prefix, runtime)) return@runCatching null
            if (belongsToGame(environment, readBounded(File(entry, "cmdline")), token, prefix, runtime)) pid else null
        }.getOrNull()
    }

    private fun readBounded(file: File): String = file.inputStream().use { input ->
        val bytes = ByteArray(4096)
        val output = java.io.ByteArrayOutputStream(4096)
        while (output.size() < 131_072) {
            val count = input.read(bytes, 0, minOf(bytes.size, 131_072 - output.size()))
            if (count <= 0) break
            output.write(bytes, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    companion object {
        private val services = setOf("wineboot.exe", "services.exe", "winedevice.exe", "explorer.exe",
            "rpcss.exe", "plugplay.exe", "svchost.exe", "conhost.exe", "winedbg.exe")
        private fun belongsToSession(environment: Map<String, String>, token: String, prefix: String, runtime: String): Boolean =
            environment["ASTRA_GAME_SESSION"] == token && environment["WINEPREFIX"] == prefix && environment["ASTRA_RUNTIME_ROOT"] == runtime
        internal fun belongsToGame(environment: Map<String, String>, commandLine: String,
                                   token: String, prefix: String, runtime: String): Boolean {
            if (!belongsToSession(environment, token, prefix, runtime)) return false
            // Wine may start these services lazily from the first client. They must not keep a closed game alive.
            return commandLine.split('\u0000').any { argument ->
                // Wine can rewrite argv[0] to the complete Windows command line.
                val command = argument.replace('\\', '/').trim().trimStart('"').lowercase()
                val extension = command.indexOf(".exe")
                if (extension < 0 || (extension + 4 < command.length && command[extension + 4] !in " \t\"")) return@any false
                val path = command.substring(0,extension + 4)
                !path.startsWith("c:/windows/") &&
                    path.substringAfterLast('/') !in services
            }
        }
    }
}

/** A normal launcher exit is not a game exit: allow a spawned/restarted child to take over. */
internal class WolfProcessLiveness(private val quietMillis: Long = 5_000) {
    private var emptySince: Long? = null
    fun alive(initialAlive: Boolean, childAlive: Boolean, now: Long): Boolean {
        if (initialAlive || childAlive) { emptySince = null; return true }
        val since = emptySince ?: now.also { emptySince = it }
        return now - since < quietMillis
    }
}
