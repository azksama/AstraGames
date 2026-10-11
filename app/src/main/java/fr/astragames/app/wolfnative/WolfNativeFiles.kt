package fr.astragames.app.wolfnative

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import fr.astragames.wolf.NativeEffect
import fr.astragames.wolf.WolfAssetSource
import fr.astragames.wolf.WolfFileText
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Original assets are read directly. Game text writes live in a persistent, private overlay. */
internal class WolfNativeFiles(context: Context, gameId: String, private val original: WolfAssetSource) : WolfAssetSource, Closeable {
    val directory = File(WolfNativeSaveStore.directoryFor(context, gameId).parentFile, "files").canonicalFile
    private val backups = File(directory.parentFile, "file-backups").canonicalFile
    private val temporaryDirectory = File(context.cacheDir, "wolf-native-file-probes")
    private val closed = AtomicBoolean()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64)) { job ->
        Thread(job, "wolf-native-file-io").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Resolve Windows casing while checking every existing symlink before following it. */
    @Synchronized private fun privateEntry(path: String, createParents: Boolean = false): File {
        check(!closed.get()) { "Fichiers de jeu natif fermés" }
        val segments = WolfFileText.path(path).split('/').filter(String::isNotBlank)
        require(segments.size <= 64) { "Chemin Wolf trop profond" }
        var current = directory
        for ((index, segment) in segments.withIndex()) {
            if (createParents && !current.exists()) check(current.mkdirs() || current.isDirectory) { "Dossier privé du jeu inaccessible" }
            val files = if (current.isDirectory) current.listFiles() ?: error("Dossier privé du jeu illisible") else emptyArray()
            require(files.size <= 100_000) { "Dossier privé Wolf trop volumineux" }
            val exact = files.firstOrNull { it.name == segment }
            val matching = files.filter { it.name.equals(segment, true) }
            require(exact != null || matching.size <= 1) { "Fichiers privés ambigus : $path" }
            current = (exact ?: matching.firstOrNull() ?: File(current, segment)).canonicalFile
            require(current.toPath().startsWith(directory.toPath())) { "Fichier privé hors du jeu : $path" }
            if (index < segments.lastIndex && current.exists()) require(current.isDirectory) { "Parent Wolf non répertoire" }
        }
        return current
    }

    override fun read(path: String): ByteArray {
        if (!directory.exists()) { check(!closed.get()); return original.read(path) }
        val bytes = synchronized(this) {
            val overlay = privateEntry(path)
            if (!overlay.exists()) null else {
                if (!overlay.isFile) throw FileNotFoundException("$path est un dossier")
                require(overlay.length() <= 16 * 1024 * 1024) { "Fichier privé Wolf trop volumineux" }
                AtomicFile(overlay).readFully()
            }
        }
        return bytes ?: original.read(path)
    }
    override fun exists(path: String): Boolean = if (!directory.exists()) { check(!closed.get()); original.exists(path) }
        else privateEntry(path).exists() || original.exists(path)
    override fun list(path: String): List<String> {
        if (!directory.exists()) { check(!closed.get()); return original.list(path) }
        val (own, present) = synchronized(this) {
            val overlay = privateEntry(path)
            if (overlay.exists() && !overlay.isDirectory) throw FileNotFoundException("$path est un fichier")
            overlay.listFiles().orEmpty().map { it.name } to overlay.isDirectory
        }
        val base = try { original.list(path) } catch (missing: FileNotFoundException) {
            if (!present) throw missing else emptyList()
        }
        return (own + base).distinctBy { it.lowercase(Locale.ROOT) }
    }

    @Synchronized fun write(path: String, bytes: ByteArray) {
        WolfFileText.path(path, writing = true)
        require(bytes.size <= 16 * 1024 * 1024) { "Fichier privé Wolf trop volumineux" }
        val file = privateEntry(path, createParents = true)
        require(file != directory && (!file.exists() || file.isFile)) { "Cible d'écriture Wolf invalide" }
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        if (file.isFile) {
            require(file.length() <= 16 * 1024 * 1024) { "Ancien fichier privé trop volumineux ; conservé" }
            check(backups.mkdirs() || backups.isDirectory)
            val prior = AtomicFile(file).readFully()
            val backup = AtomicFile(File(backups, WolfNativeSaveStore.digest(path.lowercase(Locale.ROOT).toByteArray()) + ".previous"))
            val output = backup.startWrite()
            try { output.write(prior); backup.finishWrite(output) } catch (error: Exception) { backup.failWrite(output); throw error }
        }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { check(!closed.get()); output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }

    fun execute(effect: NativeEffect.FileOperation, completed: (Result<String>) -> Unit) {
        if (closed.get()) return
        try { worker.execute {
            val result = runCatching { perform(effect) }
            if (!closed.get()) completed(result)
        } } catch (error: java.util.concurrent.RejectedExecutionException) { if (!closed.get()) completed(Result.failure(error)) }
    }

    internal fun perform(effect: NativeEffect.FileOperation): String = when (effect.operation) {
        5 -> {
            rejectProtectedText(effect.path)
            try { WolfFileText.decode(read(effect.path), effect.encodingMode, effect.modernText) }
            catch (_: FileNotFoundException) { "<NoFile>" }
        }
        6 -> { write(effect.path, WolfFileText.encode(effect.text, effect.encodingMode, effect.modernText, effect.windowsTextMode)); "" }
        7 -> try { list(effect.path).joinToString("\n") } catch (_: FileNotFoundException) { "" }
        8 -> if (exists(effect.path)) "1" else "0"
        9 -> soundTime(effect.path).toString()
        else -> error("Opération de fichier Wolf inconnue : ${effect.operation}")
    }

    private fun rejectProtectedText(path: String) {
        val normalized = WolfFileText.path(path)
        val protectedPath = if (normalized.endsWith(".wolfx", ignoreCase = true)) normalized else "$normalized.wolfx"
        require(!exists(protectedPath)) {
            "Lecture de texte WOLFX non qualifiée : $path. Utilisez le secours Winlator pour cette ressource."
        }
    }

    private fun soundTime(path: String): Long {
        val bytes = try { WolfNativeResourceBytes(this).use { it.read(path, 64 * 1024 * 1024) } }
            catch (_: FileNotFoundException) { return -1 }
        require(bytes.size <= 64 * 1024 * 1024) { "Audio de sondage Wolf trop volumineux" }
        check(temporaryDirectory.mkdirs() || temporaryDirectory.isDirectory)
        val suffix = WolfNativeResourceBytes.payloadPath(path).substringAfterLast('.', "bin").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "bin"
        val file = File(temporaryDirectory, "${UUID.randomUUID()}.$suffix")
        val retriever = MediaMetadataRetriever()
        return try {
            file.outputStream().use { it.write(bytes) }
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
        } finally { retriever.release(); file.delete() }
    }

    /** Export only data the game created; never enumerate or copy its original asset tree. */
    @Synchronized fun export(zip: ZipOutputStream) {
        check(!closed.get())
        var count = 0
        var total = 0L
        fun visit(root: File, file: File, prefix: String, depth: Int) {
            require(depth <= 64 && ++count <= 100_000) { "Fichiers natifs trop nombreux pour l’export" }
            require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Fichier natif hors du jeu" }
            if (file.isDirectory) file.listFiles().orEmpty().sortedBy { it.name }.forEach { visit(root, it, prefix, depth + 1) }
            else if (file.isFile) {
                require(file.length() <= 16 * 1024 * 1024) { "Fichier natif trop volumineux pour l’export" }
                total += file.length()
                require(total <= 256L * 1024 * 1024) { "Export des fichiers natifs trop volumineux" }
                val relative = file.relativeTo(root).invariantSeparatorsPath
                require(WolfFileText.path(relative) == relative)
                zip.putNextEntry(ZipEntry("$prefix/$relative"))
                zip.write(AtomicFile(file).readFully()); zip.closeEntry()
            }
        }
        for ((root, prefix) in listOf(directory to "game-files", backups to "file-backups"))
            if (root.isDirectory) visit(root, root, prefix, 0)
    }

    override fun close() { if (closed.compareAndSet(false, true)) worker.shutdownNow() }
}
