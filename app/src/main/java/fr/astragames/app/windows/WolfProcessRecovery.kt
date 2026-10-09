package fr.astragames.app.windows

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File

/** A killed Android activity/process cannot run finally. Reap only Astra's own runtime children. */
internal class WolfProcessRecovery(private val context: Context) {
    private val games = File(context.filesDir, "wolf-games").canonicalFile.path + "/"
    private val runtimes = File(context.noBackupFilesDir, "wolf-runtime").canonicalFile.path + "/"

    fun recover(progress: (String) -> Unit = {}): Int {
        var stopped = 0
        for (entry in File("/proc").listFiles().orEmpty()) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == Process.myPid()) continue
            val identity = runCatching { File(entry, "stat").readText().substringAfterLast(") ").split(' ').getOrNull(19) }.getOrNull() ?: continue
            val owned = runCatching {
                if (Os.stat(entry.path).st_uid != Process.myUid()) return@runCatching false
                val environment = File(entry, "environ").inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() < 131072) {
                        val read = input.read(buffer, 0, minOf(buffer.size, 131072 - output.size()))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                    .toString(Charsets.UTF_8).split('\u0000').mapNotNull {
                        val split = it.indexOf('='); if (split > 0) it.substring(0, split) to it.substring(split + 1) else null
                    }.toMap()
                fun canonical(value: String?) = value?.let { File(it).canonicalPath }
                val prefix = canonical(environment["WINEPREFIX"])
                val runtime = canonical(environment["ASTRA_RUNTIME_ROOT"])
                val wine = prefix?.startsWith(games) == true && prefix.substringAfter(games).matches(Regex("[a-f0-9]{64}/prefix")) &&
                    runtime?.startsWith(runtimes) == true
                val pulseHome = canonical(environment["HOME"])
                val pulse = pulseHome?.startsWith(runtimes) == true && pulseHome.endsWith("/pulse") &&
                    canonical(environment["TMPDIR"]) == File(context.cacheDir, "wolf-tmp").canonicalPath &&
                    File(entry, "cmdline").readText().split('\u0000').any { it == "$pulseHome/../libs/usr/lib/libpulseaudio.so" ||
                        canonical(it) == File(pulseHome, "../libs/usr/lib/libpulseaudio.so").canonicalPath }
                wine || pulse
            }.getOrDefault(false)
            if (owned) {
                // Recheck uid immediately before the signal; never use process-name-wide pkill.
                runCatching {
                    if (Os.stat(entry.path).st_uid == Process.myUid() &&
                        File(entry, "stat").readText().substringAfterLast(") ").split(' ').getOrNull(19) == identity) {
                        Process.killProcess(pid); stopped++
                        progress("Récupération de la session interrompue · $stopped processus arrêté(s)")
                    }
                }
            }
        }
        return stopped
    }
}
