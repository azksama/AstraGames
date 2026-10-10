package fr.astragames.app.wolfnative

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.wolf.WolfAssetSource
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** Reads the granted game directory. Only directories actually visited are indexed. */
internal class AndroidWolfAssetSource(context: Context, uri: Uri) : WolfAssetSource, AutoCloseable {
    private val resolver = context.applicationContext.contentResolver
    private val localRoot = FileAccessResolver(context).physicalPath(uri)?.let(::File)
        ?.takeIf { it.isDirectory && it.canRead() }?.canonicalFile
    private val treeUri = uri.takeIf { localRoot == null && it.scheme == "content" && DocumentsContract.isTreeUri(it) }
    private val rootId = treeUri?.let {
        if (DocumentsContract.isDocumentUri(context, it)) DocumentsContract.getDocumentId(it)
        else DocumentsContract.getTreeDocumentId(it)
    }
    @Volatile private var closed = false
    private val cancellations = java.util.concurrent.ConcurrentHashMap.newKeySet<android.os.CancellationSignal>()
    private data class Entry(val name: String, val file: File? = null, val id: String? = null, val directory: Boolean, val size: Long)
    private val directories = LinkedHashMap<String, List<Entry>>(32, .75f, true)
    private val metadataHashes = java.util.TreeMap<String, String>()
    var bytesRead: Long = 0; private set
    var filesRead: Int = 0; private set
    var directoriesRead: Int = 0; private set
    val description: String get() = if (localRoot != null) "Dossier d’origine · accès local" else "Dossier d’origine · autorisation Android"
    val metadataFingerprint: String get() = synchronized(this) {
        WolfNativeSaveStore.digest(metadataHashes.entries.joinToString("\n") { "${it.key}:${it.value}" }.toByteArray())
    }

    init { require(localRoot != null || rootId != null) { "Sélectionnez de nouveau le dossier du jeu dans Astra." } }

    companion object {
        private const val MAX_BYTES = 128 * 1024 * 1024
        private const val MAX_CHILDREN = 100_000
        private const val MAX_DIRECTORIES = 256
        internal fun normalize(path: String): String {
            require(path.length <= 4096 && '\u0000' !in path && ':' !in path && !path.startsWith('/') && !path.startsWith('\\')) { "Chemin Wolf invalide : $path" }
            val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            require(segments.size <= 64 && segments.none { it == ".." }) { "Chemin hors du dossier du jeu : $path" }
            return segments.joinToString("/")
        }
        private fun InputStream.boundedBytes(checkOpen: () -> Unit): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var count = 0
            while (true) {
                checkOpen()
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Lecture du jeu annulée")
                val size = read(buffer)
                if (size == -1) break
                count += size
                require(count <= MAX_BYTES) { "Ressource Wolf trop volumineuse (128 Mio maximum)." }
                out.write(buffer, 0, size)
            }
            return out.toByteArray()
        }
    }

    /** Index cache and counters are shared with asynchronous decoders; reads remain serialized. */
    @Synchronized private fun entry(path: String): Entry {
        checkOpen()
        val relative = normalize(path)
        if (relative.isEmpty()) return Entry("", localRoot, rootId, true, 0)
        var current = Entry("", localRoot, rootId, true, 0)
        var parent = ""
        for (name in relative.split('/')) {
            if (!current.directory) throw FileNotFoundException(relative)
            val children = children(parent, current)
            // Windows names are case-insensitive, but exact names have priority.
            current = children.firstOrNull { it.name == name } ?: children.filter { it.name.equals(name, true) }.let {
                if (it.size > 1) error("Noms Wolf ambigus dans $parent : $name")
                it.singleOrNull() ?: throw FileNotFoundException(relative)
            }
            parent = if (parent.isEmpty()) current.name else "$parent/${current.name}"
        }
        return current
    }

    private fun children(path: String, parent: Entry): List<Entry> {
        directories[path]?.let { return it }
        val result = if (parent.file != null) {
            val base = requireNotNull(localRoot).toPath()
            val listed = parent.file.listFiles() ?: error("Le dossier Wolf est inaccessible : $path")
            require(listed.size <= MAX_CHILDREN) { "Trop de fichiers dans $path" }
            listed.map { file ->
                val canonical = file.canonicalFile
                require(canonical.toPath().startsWith(base)) { "Lien hors du dossier du jeu : ${file.name}" }
                Entry(file.name, canonical, directory = canonical.isDirectory, size = canonical.length())
            }
        } else {
            val tree = requireNotNull(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, requireNotNull(parent.id))
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
            val entries = ArrayList<Entry>()
            val cancellation = android.os.CancellationSignal().also { cancellations.add(it) }
            try { resolver.query(childrenUri, columns, null, null, null, cancellation)?.use { cursor ->
                while (cursor.moveToNext()) {
                    require(entries.size < MAX_CHILDREN) { "Trop de fichiers dans $path" }
                    val name = cursor.getString(1) ?: error("Nom de fichier Wolf manquant")
                    require(name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) { "Nom de fichier Wolf invalide" }
                    entries += Entry(name, id = cursor.getString(0), directory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (cursor.isNull(3)) -1 else cursor.getLong(3))
                }
            } ?: error("Autorisation du dossier Wolf expirée : $path")
            } finally { cancellations.remove(cancellation) }
            entries
        }
        directoriesRead++
        if (directories.size >= MAX_DIRECTORIES) directories.remove(directories.keys.first())
        directories[path] = result
        return result
    }

    override fun read(path: String): ByteArray {
        val target = entry(path)
        if (target.directory) throw FileNotFoundException("$path est un dossier")
        require(target.size <= MAX_BYTES) { "Ressource Wolf trop volumineuse : $path" }
        val bytes = if (target.file != null) target.file.inputStream().use { it.boundedBytes(::checkOpen) }
        else {
            val cancellation = android.os.CancellationSignal().also { cancellations.add(it) }
            try {
                checkOpen()
                resolver.openAssetFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(requireNotNull(treeUri), requireNotNull(target.id)), "r", cancellation)
                    ?.use { descriptor -> descriptor.createInputStream().use { it.boundedBytes(::checkOpen) } } ?: throw FileNotFoundException(path)
            } finally { cancellations.remove(cancellation) }
        }
        synchronized(this) {
            bytesRead += bytes.size; filesRead++
            val relative = normalize(path).lowercase(java.util.Locale.ROOT)
            if (relative.contains("basicdata/") && (relative.endsWith(".dat") || relative.endsWith(".project")))
                metadataHashes[relative] = WolfNativeSaveStore.digest(bytes)
        }
        return bytes
    }
    override fun list(path: String): List<String> = synchronized(this) {
        val target = entry(path)
        if (!target.directory) throw FileNotFoundException("$path n’est pas un dossier")
        children(normalize(path), target).map { it.name }
    }
    override fun exists(path: String): Boolean = try { entry(path); true } catch (_: FileNotFoundException) { false }
    private fun checkOpen() { check(!closed) { "Session native fermée" } }
    override fun close() { closed = true; cancellations.forEach { it.cancel() }; cancellations.clear() }
}
