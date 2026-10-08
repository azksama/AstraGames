package fr.astragames.app.windows

import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.core.content.FileProvider
import fr.astragames.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Private, bounded reports, written while the runtime is running (not just on orderly exit). */
internal class WolfDiagnostics(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "wolf-diagnostics")
    private val preferences = context.getSharedPreferences("wolf_diagnostics", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("verbose", false)
        set(value) { check(preferences.edit().putBoolean("verbose", value).commit()) }

    fun begin(title: String, executable: String): WolfDiagnosticSession {
        root.mkdirs()
        installCrashHandler()
        val directory = File(root, "${System.currentTimeMillis()}-${UUID.randomUUID()}").apply { mkdirs() }
        val session = WolfDiagnosticSession(directory, enabled)
        active[directory.name] = session
        session.event("Astra ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); runtime ${WolfRuntimeInstaller.REVISION}")
        session.event("Jeu : ${title.take(200)}; exécutable : ${executable.take(300)}; debug=${session.verbose}")
        session.event("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}")
        session.event("ABI=${Build.SUPPORTED_ABIS.joinToString()}; matériel=${Build.HARDWARE}; pages=${runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull()}; espace libre=${root.usableSpace}")
        if (Build.VERSION.SDK_INT >= 31) session.event("SoC=${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        session.stage("Préparation du lancement")
        // Only this app's PID; no READ_LOGS permission and no system-wide log collection.
        if (session.verbose) runCatching {
            session.logcat = ProcessBuilder("/system/bin/logcat", "--pid=${android.os.Process.myPid()}", "-v", "threadtime", "-T", "1")
                .redirectErrorStream(true).start().also { session.capture(it, "android") }
        }.onFailure { session.event("Logcat indisponible : ${it.message}") }
        reports().drop(5).filter { !active.containsKey(it.name) }.forEach { it.deleteRecursively() }
        return session
    }

    fun reports(): List<File> = root.listFiles()?.filter { it.isDirectory && File(it, "session.json").isFile }
        ?.sortedByDescending { it.name } ?: emptyList()

    fun report(directory: File): String {
        require(directory.canonicalFile.parentFile == root.canonicalFile)
        val metadata = JSONObject(AtomicFile(File(directory, "session.json")).readFully().toString(Charsets.UTF_8))
        val unfinished = !metadata.optBoolean("finished") && !active.containsKey(directory.name)
        val result = StringBuilder("ASTRA — DIAGNOSTIC WOLF RPG\n")
        result.append("Session : ${directory.name}\nDernière étape : ${metadata.optString("stage")}\n")
        result.append("État : ${if (unfinished) "Session interrompue sans résultat final. Ce constat seul ne prouve pas un crash." else metadata.optString("result", "En cours")}\n")
        if (unfinished) {
            result.append(androidExit(metadata))
        }
        for (name in listOf("events", "runtime", "audio", "android")) {
            result.append("\n===== $name =====\n")
            for (suffix in listOf(".previous.log", ".log")) {
                val file = File(directory, name + suffix)
                if (file.isFile) result.append(readTail(file, LOG_LIMIT)).append('\n')
            }
        }
        return result.toString().replace(context.noBackupFilesDir.path, "<astra-private>")
            .replace(context.filesDir.path, "<astra-files>").replace(context.cacheDir.path, "<astra-cache>")
    }

    private fun androidExit(metadata: JSONObject): String {
        if (Build.VERSION.SDK_INT < 30) return "Android ne fournit pas l’historique des arrêts avant Android 11. Les journaux ci-dessous restent disponibles.\n"
        return runCatching {
            val exit = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, metadata.getInt("pid"), 8)
                .firstOrNull { it.timestamp >= metadata.getLong("started") && it.processName == context.packageName }
            if (exit == null) "Aucune cause d’arrêt fournie par Android pour ce processus.\n"
            else "Arrêt Android : raison=${exit.reason}, statut=${exit.status}, date=${java.util.Date(exit.timestamp)}, description=${exit.description}; PSS=${exit.pss} Ko, RSS=${exit.rss} Ko.\n" +
                "Raisons Android : 2=signal, 3=mémoire insuffisante, 4=crash Java, 5=crash natif, 6=ANR, 10=arrêt demandé par l’utilisateur.\n"
        }.getOrElse { "Historique Android indisponible : ${it.message}\n" }
    }

    fun shareIntent(directory: File): Intent {
        val exports = File(context.cacheDir, "wolf-reports").apply { mkdirs() }
        // A fixed snapshot keeps the shared URI readable while the chooser is open.
        val file = File(exports, "Astra-Wolf-${directory.name}.txt").apply { writeText(report(directory)) }
        exports.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("Diagnostic Wolf RPG", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        internal const val LOG_LIMIT = 512 * 1024
        private val active = ConcurrentHashMap<String, WolfDiagnosticSession>()
        private var handlerInstalled = false
        @Synchronized private fun installCrashHandler() {
            if (handlerInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
            handlerInstalled = true
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                active.values.forEach { runCatching { it.failure("Exception non interceptée (${thread.name})", error) } }
                previous.uncaughtException(thread, error)
            }
        }
        internal fun completed(session: WolfDiagnosticSession) { active.remove(session.directory.name) }
        internal fun readTail(file: File, limit: Int): String = RandomAccessFile(file, "r").use {
            val skipped = (it.length() - limit).coerceAtLeast(0)
            it.seek(skipped)
            val bytes = ByteArray((it.length() - skipped).toInt()); it.readFully(bytes)
            (if (skipped > 0) "[Début tronqué]\n" else "") + bytes.toString(Charsets.UTF_8)
        }
    }
}

internal class WolfDiagnosticSession(val directory: File, val verbose: Boolean) {
    private val sinks = ConcurrentHashMap<String, WolfLogSink>()
    private val readers = ConcurrentHashMap<Process, Thread>()
    private val metadata = JSONObject().put("pid", android.os.Process.myPid()).put("started", System.currentTimeMillis()).put("finished", false)
    var logcat: Process? = null
    private fun sink(name: String) = sinks.getOrPut(name) { WolfLogSink(File(directory, "$name.log")) }
    fun event(message: String) { sink("events").write("${java.time.Instant.now()} $message\n".toByteArray()) }
    @Synchronized fun stage(value: String) {
        if (metadata.optString("stage") == value) return
        metadata.put("stage", value); persist(); event("Étape : $value")
    }
    private fun persist() {
        val file = AtomicFile(File(directory, "session.json"))
        val out = file.startWrite()
        try { out.write(metadata.toString().toByteArray()); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    fun failure(label: String, error: Throwable) { event("$label\n${error.stackTraceToString().take(32_000)}") }
    fun capture(process: Process, name: String) {
        val output = sink(name)
        readers[process] = thread(name = "wolf-$name-log", isDaemon = true) {
            runCatching { process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer.copyOf(count)) }
            } }.onFailure { runCatching { event("Lecture $name interrompue : ${it.message}") } }
        }
    }
    fun exited(process: Process, label: String, code: Int) {
        readers[process]?.join(500)
        event("$label : ${exitDescription(code)}")
    }
    @Synchronized fun finish(result: String) {
        logcat?.destroy(); logcat = null
        readers.values.forEach { it.join(500) }
        event(result)
        metadata.put("finished", true).put("result", result); persist()
        WolfDiagnostics.completed(this)
    }
    companion object {
        fun exitDescription(code: Int): String {
            val signal = mapOf(132 to "SIGILL — instruction illégale", 134 to "SIGABRT — abandon", 135 to "SIGBUS — accès mémoire", 137 to "SIGKILL — arrêt forcé", 139 to "SIGSEGV — accès mémoire invalide")[code]
            return "code de sortie $code" + (signal?.let { " (compatible avec $it ; à confirmer dans le journal)" } ?: "")
        }
    }
}

/** Two bounded files per stream. Each write reaches the OS immediately, surviving app death. */
internal class WolfLogSink(private val file: File, private val limit: Int = WolfDiagnostics.LOG_LIMIT) {
    @Synchronized fun write(bytes: ByteArray) {
        file.parentFile!!.mkdirs()
        if (file.length() + bytes.size > limit) {
            val previous = File(file.parentFile, file.nameWithoutExtension + ".previous.log")
            previous.delete()
            if (file.exists() && !file.renameTo(previous)) file.writeBytes(byteArrayOf())
        }
        // appendBytes opens/closes the file; there is no buffered tail lost on a native crash.
        file.appendBytes(if (bytes.size > limit) bytes.copyOfRange(bytes.size - limit, bytes.size) else bytes)
    }
}
