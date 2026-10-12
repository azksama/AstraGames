package fr.astragames.app.windows

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * Wine gets seekable provider FDs over SCM_RIGHTS. No byte cache or placeholder asset tree.
 * Changed files are private, fsynced transactions until a verified provider publication succeeds.
 * The socket is a process-local capability: only peers with Astra's UID are accepted.
 */
internal class WolfSafMount(private val context: Context, private val tree: Uri,
    val game: File, private val stateRoot: File, private var iniPath: String?) : Closeable {
    private data class Entry(val uri: Uri, val name: String, val directory: Boolean, val size: Long,
        val modified: Long, val flags: Int)
    private data class DirectoryIndex(val loaded: Long, val entries: List<Entry>)
    private val resolver = context.contentResolver
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val index = LinkedHashMap<String, DirectoryIndex>(16, .75f, true)
    private var indexedEntries = 0
    private val writes = File(stateRoot, "writes").apply { mkdirs() }
    private val transactions = File(stateRoot, "transactions").apply { mkdirs() }
    private val recovery = File(stateRoot, "recovery").apply { mkdirs() }
    private val moves = File(stateRoot, "moves").apply { mkdirs() }
    private val reservations = mutableMapOf<String, Long>()
    private val lock = Any()
    private val slots = Semaphore(8)
    private val workers = Executors.newFixedThreadPool(4) { task -> Thread(task, "wolf-saf-worker").apply { isDaemon = true } }
    private val socketName = "astra-saf-${UUID.randomUUID()}"
    private var server: LocalServerSocket? = null
    private val requests = AtomicLong()
    private val readOpens = AtomicLong()
    private val commits = AtomicLong()
    @Volatile private var closed = false
    @Volatile var lastFailure: String? = null
        private set
    private val listener = Thread({ listen() }, "wolf-saf-listener").apply { isDaemon = true }
    private var originalIniHash: String? = null
    private val iniBaseline = File(stateRoot, "ini-baseline.json")
    private val exportBaseline = File(stateRoot, "export-baseline.json")
    private val recoveryExports = File(stateRoot, "recovery-exports.json")

    val environment get() = mapOf("ASTRA_SAF_ROOT" to game.canonicalPath, "ASTRA_SAF_SOCKET" to socketName)
    val description get() = "SAF à la demande · ${tree.authority} · lecture par descripteur, sauvegardes journalisées"
    val hasPendingWrites get() = transactions.listFiles().orEmpty().filter { it.extension == "json" }
        .any { runCatching { JSONObject(it.readText()).optBoolean("dirty") }.getOrDefault(true) } || moves.listFiles().orEmpty().isNotEmpty() || recovery.listFiles().orEmpty().isNotEmpty() ||
        runCatching {
            if (!iniBaseline.isFile) false else {
                val previous = JSONObject(iniBaseline.readText()); val local = safeFile(game, previous.getString("path"))
                local.isFile && digest(local) != previous.optString("hash")
            }
        }.getOrDefault(true)
    fun pendingSaveFiles() = transactions.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
        val record = JSONObject(it.readText()); val path = record.getString("path")
        if (!record.optBoolean("dirty") || path.substringAfterLast('/').equals("Game.ini", true)) null
        else payloadFile(path).takeIf { payload -> payload.isFile && !record.optBoolean("incomplete") && sealed(record, payload) }
            ?.let { payload -> WolfGameStorage.SaveFile(payload, path) }
    }
    fun baseline(path: String) = JSONObject(journalFile(path).readText()).optString("baseline").takeUnless { it.isEmpty() || it == "null" }
    val statistics get() = "SAF: requêtes=${requests.get()}, ouvertures directes=${readOpens.get()}, publications=${commits.get()}, index=$indexedEntries entrées (limite $MAX_INDEX_ENTRIES), erreur=${lastFailure ?: "aucune"}"

    /** Probe real capabilities, not optimistic DocumentFile permission flags. */
    fun prepare(executable: String, progress: (String) -> Unit) {
        try {
            progress("Vérification du fournisseur SAF : lecture, accès aléatoire et sauvegardes…")
            val root = metadata(rootUri)
            check(root.directory && root.flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "Le fournisseur ne permet pas de créer des sauvegardes." }
            val entry = requireEntry(executable)
            check(!entry.directory) { "L’exécutable SAF est un dossier." }
            openProvider(entry, "r").use { fd ->
                check(OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) { "Le fournisseur renvoie un flux au lieu d’un fichier." }
                Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
                if (entry.size > 0) ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    val mapped = Os.mmap(0, 1, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, input.fd, 0)
                    Os.munmap(mapped, 1)
                }
            }
            probePublication(rootUri)
            progress("Récupération des écritures SAF interrompues…")
            // These temporary files are never referenced by a transaction or handed to Wine.
            writes.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(".astra-payload-") }.forEach { check(it.delete()) }
            recoverArchives()
            recoverPublications()
            recoverMoves()
            flush()
            // ProcessBuilder needs only the executable's working directory to exist locally.
            // The zero-length entry is never read by Wine: open/stat are served by the bridge.
            safeFile(game, executable).apply { parentFile!!.mkdirs(); if (!exists()) createNewFile() }
            iniPath = iniPath?.let(::canonicalCase)
            iniPath?.let { path ->
                val local = safeFile(game, path)
                val previous = runCatching { JSONObject(iniBaseline.readText()) }.getOrNull()
                if (previous?.optString("path") == path && local.isFile) {
                    originalIniHash = previous.optString("hash").takeUnless { it.isEmpty() || it == "null" }
                    // WolfDirectConfiguration restores temporary renderer keys before prepare().
                    // Recover any game settings written immediately before the process died.
                    if (digest(local) != originalIniHash) publishConfiguration()
                }
                val ini = find(path, reload = true)
                originalIniHash = ini?.let(::hash)
                local.parentFile!!.mkdirs()
                if (ini != null) openProvider(ini, "r").use { fd ->
                    ParcelFileDescriptor.AutoCloseInputStream(fd).use { input -> local.outputStream().use { output -> copyBounded(input, output, MAX_CONFIG_BYTES) } }
                } else if (local.exists()) local.delete()
                checkpoint(iniBaseline, JSONObject().put("path", path).put("hash", originalIniHash ?: JSONObject.NULL))
            }
            server = LocalServerSocket(socketName)
            listener.start()
            progress("Dossier SAF prêt · aucun import des assets")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** Game.ini is restored by WolfDirectConfiguration before this private override is published. */
    fun publishConfiguration() = synchronized(lock) {
        val path = iniPath ?: return@synchronized
        val local = safeFile(game, path)
        if (!local.isFile) return@synchronized
        val digest = digest(local)
        if (digest == originalIniHash) return@synchronized
        val journal = journalFile(path)
        local.inputStream().use { replacePayload(path, it, local.length(), MAX_CONFIG_BYTES) }
        if (!journal.isFile) {
            checkpoint(journal, JSONObject().put("path", path).put("baseline", originalIniHash ?: JSONObject.NULL)
                .put("dirty", true).put("state", "OPEN"))
        }
        seal(path)
        publish(path, finalize = true)
    }

    fun verifyAccess() { metadata(rootUri) }

    /** All dirty data survives a revoked grant or process death; conflicts never overwrite source. */
    fun flush(): Int = synchronized(lock) {
        var conflicts = 0
        transactions.listFiles().orEmpty().filter { it.extension == "json" }.forEach { file ->
            val record = JSONObject(file.readText())
            if (record.optBoolean("dirty")) {
                val path = record.getString("path")
                val payload = payloadFile(path)
                if (!sealed(record, payload)) {
                    // A process lost during write/truncate must never replace the source save.
                    record.put("incomplete", true).put("state", "INCOMPLETE")
                    checkpoint(file, record)
                    conflicts++; lastFailure = "$path : écriture interrompue conservée dans Astra ; sauvegarde source utilisée."
                } else try { publish(path, finalize = true) }
                catch (error: Conflict) { conflicts++; lastFailure = error.message }
            }
        }
        conflicts
    }

    /** Materialize save data only for export/backup UI; assets remain outside the cache. */
    fun saveFiles(): List<WolfGameStorage.SaveFile> = synchronized(lock) {
        val paths = linkedSetOf<String>()
        transactions.listFiles().orEmpty().filter { it.extension == "json" }.forEach {
            val record = JSONObject(it.readText()); paths += record.getString("path")
        }
        fun scan(path: String, depth: Int) {
            check(depth <= 16 && paths.size < MAX_SAVE_FILES) { "Trop de fichiers de sauvegarde SAF." }
            for (entry in children(path)) {
                if (entry.name.startsWith(".astra-")) continue
                val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                if (entry.directory) {
                    if (path.isNotEmpty() || entry.name.lowercase() in SAVE_FOLDERS) scan(child, depth + 1)
                } else if (path.isNotEmpty() || entry.name.substringAfterLast('.', "").lowercase() in setOf("sav", "save")) paths += child
            }
        }
        // Revoked access must still allow export of pending private writes.
        runCatching {
            scan("", 0)
            // Probe only known nested save locations; never recursively scan the asset tree.
            for (folder in listOf("Data/Save", "Data/SaveData", "Data/Save_MTool"))
                if (runCatching { find(folder)?.directory == true }.getOrDefault(false)) scan(folder, 1)
        }.onFailure { lastFailure = "Sauvegardes source inaccessibles : ${it.message}" }
        val result = paths.filterNot { it == iniPath || it.substringAfterLast('.').lowercase() in setOf("exe", "dll", "log", "tmp") }.mapNotNull { path ->
            val payload = payloadFile(path)
            val journal = journalFile(path)
            val record = if (journal.isFile) JSONObject(journal.readText()) else null
            val export = safeFile(File(stateRoot, "export"), path).apply { parentFile!!.mkdirs() }
            val exported = runCatching { JSONObject(exportBaseline.readText()) }.getOrDefault(JSONObject())
            if (payload.isFile && record?.optBoolean("dirty") == true) {
                payload.inputStream().use { input -> exportSave(export, input, payload.length()) }
                exported.put(path, record.opt("baseline") ?: JSONObject.NULL)
            } else {
                val source = runCatching { find(path) }.onFailure { lastFailure = "Source inaccessible : ${it.message}" }.getOrNull() ?: return@mapNotNull null
                openProvider(source, "r").use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    exportSave(export, input, Os.fstat(input.fd).st_size)
                } }
                exported.put(path, digest(export))
            }
            checkpoint(exportBaseline, exported)
            WolfGameStorage.SaveFile(export, path)
        }
        val recoveredExports = runCatching { JSONObject(recoveryExports.readText()) }.getOrDefault(JSONObject())
        val recovered = recovery.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            val record = JSONObject(file.readText())
            val payload = File(recovery, "${file.nameWithoutExtension}.payload")
            if (!payload.isFile) return@mapNotNull null
            val relative = "Recovered/${file.nameWithoutExtension}/${record.getString("path")}"
            val export = safeFile(File(stateRoot, "export"), relative).apply { parentFile!!.mkdirs() }
            payload.inputStream().use { exportSave(export, it, payload.length()) }
            recoveredExports.put(relative, JSONObject().put("path", record.getString("path"))
                .put("baseline", record.opt("baseline") ?: JSONObject.NULL))
            WolfGameStorage.SaveFile(export, relative)
        }
        if (recovered.isNotEmpty()) checkpoint(recoveryExports, recoveredExports)
        result + recovered
    }

    /** Bounded, durable exports preserve the previously editable copy if a read is interrupted. */
    private fun exportSave(destination: File, input: java.io.InputStream, size: Long) {
        val exportRoot = File(stateRoot, "export")
        val used = exportRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val allowance = minOf(MAX_FILE_BYTES, MAX_EXPORT_BYTES - used)
        check(size in 0..allowance) { "Cache d’export des sauvegardes plein (512 Mio). Les écritures privées restent conservées." }
        val temporary = File(destination.parentFile, ".astra-export-${UUID.randomUUID()}")
        try {
            temporary.outputStream().use { output -> copyBounded(input, output, allowance); output.fd.sync() }
            check(temporary.renameTo(destination)) { "Impossible de conserver l’export de la sauvegarde." }
            syncDirectory(destination.parentFile!!)
        } finally { temporary.delete() }
    }

    /** Adopt an explicitly edited export while retaining the snapshot's conflict baseline. */
    fun savedFileChanged(file: File) = synchronized(lock) {
        val edited = file.canonicalFile
        val exportRoot = File(stateRoot, "export").canonicalFile
        val recordFile = transactions.listFiles().orEmpty().filter { it.extension == "json" }.firstOrNull {
            val record = JSONObject(it.readText()); payloadFile(record.getString("path")).canonicalFile == edited
        }
        val record = recordFile?.let { JSONObject(it.readText()) }
        val exportPath = if (edited.toPath().startsWith(exportRoot.toPath())) normalize(edited.relativeTo(exportRoot).invariantSeparatorsPath) else null
        val recovered = exportPath?.let { runCatching { JSONObject(recoveryExports.readText()).optJSONObject(it) }.getOrNull() }
        val path = record?.getString("path") ?: recovered?.getString("path") ?: run {
            require(edited.toPath().startsWith(exportRoot.toPath())) { "La sauvegarde ne provient pas de ce montage SAF." }
            requireNotNull(exportPath)
        }
        val baseline = record?.opt("baseline") ?: recovered?.opt("baseline") ?: JSONObject(exportBaseline.readText()).get(path)
        check(edited.isFile && edited.length() <= MAX_FILE_BYTES) { "Sauvegarde SAF absente ou trop volumineuse." }
        val payload = payloadFile(path)
        if (payload.canonicalFile != edited) edited.inputStream().use { replacePayload(path, it, edited.length(), MAX_FILE_BYTES) }
        else {
            check(stagingBytes() <= MAX_STAGING_BYTES) { "Journal des sauvegardes plein (512 Mio). La source reste inchangée." }
            java.io.RandomAccessFile(edited, "rw").use { it.fd.sync() }
        }
        checkpoint(journalFile(path), JSONObject().put("path", path).put("baseline", baseline)
            .put("dirty", true).put("sealed", digest(payload)).put("state", "OPEN"))
    }

    fun refreshDiagnosticLog(executable: String) = runCatching {
        val folder = executable.substringBeforeLast('/', "")
        val path = if (folder.isEmpty()) "Game_ErrorLog.txt" else "$folder/Game_ErrorLog.txt"
        val payload = payloadFile(path)
        val source = if (payload.isFile) null else find(path)
        val destination = safeFile(game, path)
        when {
            payload.isFile -> payload.inputStream().use { input -> destination.outputStream().use { copyBounded(input, it, MAX_CONFIG_BYTES) } }
            source != null -> openProvider(source, "r").use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                destination.outputStream().use { copyBounded(input, it, MAX_CONFIG_BYTES) }
            } }
        }
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
        server = null
        workers.shutdown()
    }

    private fun listen() {
        while (!closed) {
            val socket = try { requireNotNull(server).accept() } catch (_: Exception) { break }
            if (socket.peerCredentials.uid != Process.myUid() || !slots.tryAcquire()) { socket.close(); continue }
            workers.execute { try { socket.use(::serve) } finally { slots.release() } }
        }
    }

    private fun serve(socket: LocalSocket) {
        socket.soTimeout = 15_000
        var descriptor: ParcelFileDescriptor? = null
        try {
            val header = ByteBuffer.wrap(readExactly(socket, 24)).order(ByteOrder.LITTLE_ENDIAN)
            check(header.int == MAGIC) { "Protocole SAF invalide." }
            val operation = header.int; val flags = header.int; val offset = header.int
            val size = header.int; val secondSize = header.int
            require(size in 0..4096 && secondSize in 0..4096)
            val rawPath = normalize(String(readExactly(socket, size), Charsets.UTF_8))
            val rawSecond = normalize(String(readExactly(socket, secondSize), Charsets.UTF_8))
            requests.incrementAndGet()
            var mode = 0; var length = 0L; var modified = 0L; var inode = 0L; var payload = ByteArray(0)
            synchronized(lock) {
                // Fsync seals a private descriptor even when provider permission is gone.
                // These operations must not depend on a metadata refresh from that provider.
                val path = if (operation == OP_COMMIT || operation == OP_RESERVE) localJournalPath(rawPath) ?: rawPath else canonicalCase(rawPath)
                val second = canonicalCase(rawSecond)
                when (operation) {
                    OP_STAT -> {
                        val entry = entryForStat(path)
                        mode = if (entry.directory) DIR_MODE else FILE_MODE
                        length = entry.size; modified = entry.modified; inode = identity(path)
                    }
                    OP_OPEN -> {
                        val entry = find(path)
                        val writing = flags and OsConstants.O_ACCMODE != OsConstants.O_RDONLY
                        if (entry?.directory == true || flags and O_DIRECTORY != 0) {
                            if (entry?.directory != true && path.isNotEmpty()) throw Missing(path)
                            safeDirectory(game, path).mkdirs()
                            descriptor = ParcelFileDescriptor.open(safeDirectory(game, path), ParcelFileDescriptor.MODE_READ_ONLY)
                            mode = DIR_MODE; inode = identity(path)
                        } else if (path == iniPath) {
                            val local = safeFile(game, path)
                            if (!local.isFile && flags and OsConstants.O_CREAT == 0) throw Missing(path)
                            descriptor = ParcelFileDescriptor.open(local, parcelMode(flags))
                            mode = FILE_MODE; length = local.length(); inode = identity(path)
                        } else if (writing || flags and (OsConstants.O_CREAT or OsConstants.O_TRUNC) != 0) {
                            descriptor = openWrite(path, flags)
                            mode = FILE_MODE; length = payloadFile(path).length(); inode = identity(path)
                        } else {
                            val pending = playablePending(path)
                            if (entry == null && !pending) throw Missing(path)
                            descriptor = if (pending) ParcelFileDescriptor.open(payloadFile(path), ParcelFileDescriptor.MODE_READ_ONLY) else openProvider(requireNotNull(entry), "r")
                            mode = FILE_MODE; length = Os.fstat(descriptor!!.fileDescriptor).st_size; modified = entry?.modified ?: payloadFile(path).lastModified(); inode = identity(path)
                            readOpens.incrementAndGet()
                        }
                    }
                    OP_LIST -> {
                        val entries = children(path).filterNot { it.name.startsWith(".astra-") }.toMutableList()
                        val configPath = iniPath
                        if (configPath != null && configPath.substringBeforeLast('/', "") == path && safeFile(game, configPath).isFile && entries.none { it.name == configPath.substringAfterLast('/') }) {
                            entries += Entry(Uri.EMPTY, configPath.substringAfterLast('/'), false, 0, 0, 0)
                        }
                        for (journal in transactions.listFiles().orEmpty().filter { it.extension == "json" }) {
                            val record = JSONObject(journal.readText()); val pendingPath = record.getString("path")
                            if (record.optBoolean("dirty") && !record.optBoolean("incomplete") && pendingPath.substringBeforeLast('/', "") == path && entries.none { it.name == pendingPath.substringAfterLast('/') })
                                entries += Entry(Uri.EMPTY, pendingPath.substringAfterLast('/'), false, payloadFile(pendingPath).length(), payloadFile(pendingPath).lastModified(), 0)
                        }
                        val page = entries.drop(offset.coerceAtLeast(0)).take(128)
                        val output = java.io.ByteArrayOutputStream()
                        for (entry in page) {
                            val name = entry.name.toByteArray(Charsets.UTF_8)
                            val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                            output.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(name.size).putInt(if (entry.directory) DIR_MODE else FILE_MODE).putLong(identity(child)).array())
                            output.write(name)
                        }
                        payload = output.toByteArray(); length = page.size.toLong()
                    }
                    OP_COMMIT -> if (path != iniPath) { seal(path); publish(path) }
                    OP_RESERVE -> {
                        val required = offset.toLong()
                        if (required < 0 || required > MAX_FILE_BYTES) throw ErrnoException("SAF file quota", OsConstants.EFBIG)
                        val usage = stagingBytes() + reservations.entries.sumOf { (name, size) ->
                            (size - payloadFile(name).length()).coerceAtLeast(0)
                        }
                        val additional = (required - maxOf(payloadFile(path).length(), reservations[path] ?: 0)).coerceAtLeast(0)
                        if (usage + additional > MAX_STAGING_BYTES || writes.usableSpace < additional + 16L * 1024 * 1024)
                            throw ErrnoException("SAF staging quota", OsConstants.ENOSPC)
                        reservations[path] = maxOf(required, reservations[path] ?: 0)
                    }
                    OP_MKDIR -> {
                        if (find(path) != null) throw ErrnoException("mkdir", OsConstants.EEXIST)
                        val parent = requireEntry(path.substringBeforeLast('/', ""))
                        requireNotNull(DocumentsContract.createDocument(resolver, parent.uri, Document.MIME_TYPE_DIR, path.substringAfterLast('/')))
                        invalidate(); safeDirectory(game, path).mkdirs()
                    }
                    OP_DELETE -> {
                        val entry = requireEntry(path)
                        if (entry.directory && flags and AT_REMOVEDIR == 0) throw ErrnoException("unlink", OsConstants.EISDIR)
                        if (!entry.directory && flags and AT_REMOVEDIR != 0) throw ErrnoException("rmdir", OsConstants.ENOTDIR)
                        if (entry.directory && children(path).isNotEmpty()) throw ErrnoException("rmdir", OsConstants.ENOTEMPTY)
                        check(DocumentsContract.deleteDocument(resolver, entry.uri)); invalidate()
                        journalFile(path).delete(); payloadFile(path).delete()
                    }
                    OP_RENAME -> rename(path, second)
                    else -> throw ErrnoException("SAF operation", OsConstants.ENOSYS)
                }
            }
            val response = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(mode)
                .putLong(length).putLong(modified).putLong(inode).putInt(payload.size).putInt(0).array()
            descriptor?.let { socket.setFileDescriptorsForSend(arrayOf(it.fileDescriptor)) }
            socket.outputStream.write(response); socket.outputStream.write(payload); socket.outputStream.flush()
        } catch (error: Throwable) {
            if (error !is Missing) lastFailure = "${error.javaClass.simpleName}: ${error.message}"
            val errno = when (error) {
                is SecurityException -> OsConstants.EACCES
                is Missing -> OsConstants.ENOENT
                is Conflict -> OsConstants.EAGAIN
                is ErrnoException -> error.errno
                is IllegalArgumentException -> OsConstants.EINVAL
                else -> OsConstants.EIO
            }
            runCatching { socket.outputStream.write(ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putInt(errno).array()) }
        } finally { descriptor?.close() }
    }

    private fun openWrite(path: String, flags: Int): ParcelFileDescriptor {
        val source = find(path)
        val journal = journalFile(path)
        val payload = payloadFile(path)
        var record = if (journal.isFile) JSONObject(journal.readText()) else null
        if (record?.optBoolean("incomplete") == true) {
            archiveInterrupted(path, record)
            record = null
        }
        val dirty = record?.optBoolean("dirty") == true
        if (source == null && !dirty && flags and OsConstants.O_CREAT == 0) throw Missing(path)
        if ((source != null || dirty) && flags and (OsConstants.O_CREAT or OsConstants.O_EXCL) == (OsConstants.O_CREAT or OsConstants.O_EXCL)) throw ErrnoException("open", OsConstants.EEXIST)
        if (!dirty) {
            check(transactions.listFiles().orEmpty().count { it.extension == "json" } < MAX_SAVE_FILES) { "Limite du journal SAF atteinte." }
            // Assets have no byte cache. Clean staging payloads are evictable; dirty data is never evicted.
            val total = stagingBytes()
            if (total > MAX_STAGING_BYTES) evictCleanPayloads()
            if (stagingBytes() + (source?.size ?: 0) > MAX_STAGING_BYTES)
                throw ErrnoException("SAF staging quota", OsConstants.ENOSPC)
            val baseline = source?.let(::hash)
            if (source != null && flags and OsConstants.O_TRUNC == 0) {
                check(source.size <= MAX_FILE_BYTES) { "Fichier modifié trop grand pour le journal SAF." }
                openProvider(source, "r").use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { input -> payload.outputStream().use { copyBounded(input, it, MAX_FILE_BYTES) } } }
            } else payload.outputStream().use { it.fd.sync() }
            checkpoint(journal, JSONObject().put("path", path).put("baseline", baseline ?: JSONObject.NULL).put("dirty", true).put("state", "OPEN"))
        } else {
            // Checkpoint before truncation: process death can never certify a half-written save.
            val next = requireNotNull(record).put("dirty", true).put("incomplete", false).put("state", "OPEN")
            next.remove("sealed")
            checkpoint(journal, next)
        }
        return ParcelFileDescriptor.open(payload, parcelMode(flags))
    }

    private fun parcelMode(flags: Int): Int = (when (flags and OsConstants.O_ACCMODE) {
        OsConstants.O_WRONLY -> ParcelFileDescriptor.MODE_WRITE_ONLY
        OsConstants.O_RDWR -> ParcelFileDescriptor.MODE_READ_WRITE
        else -> ParcelFileDescriptor.MODE_READ_ONLY
    }) or (if (flags and OsConstants.O_CREAT != 0) ParcelFileDescriptor.MODE_CREATE else 0) or
        (if (flags and OsConstants.O_TRUNC != 0) ParcelFileDescriptor.MODE_TRUNCATE else 0) or
        (if (flags and OsConstants.O_APPEND != 0) ParcelFileDescriptor.MODE_APPEND else 0)

    private fun playablePending(path: String): Boolean = journalFile(path).takeIf(File::isFile)?.let {
        val record = JSONObject(it.readText())
        record.optBoolean("dirty") && !record.optBoolean("incomplete")
    } == true

    private fun localJournalPath(path: String): String? {
        journalFile(path).takeIf(File::isFile)?.let { return JSONObject(it.readText()).getString("path") }
        return transactions.listFiles().orEmpty().filter { it.extension == "json" }.map {
            JSONObject(it.readText()).getString("path")
        }.singleOrNull { it.equals(path, ignoreCase = true) }
    }

    private fun sealed(record: JSONObject, payload: File): Boolean = payload.isFile &&
        record.optString("sealed").takeIf(String::isNotEmpty)?.let { it == digest(payload) } == true

    /** COMMIT is sent only after native fsync succeeds; editor adoption fsyncs before sealing. */
    private fun seal(path: String) {
        val journal = journalFile(path)
        if (!journal.isFile) return
        val record = JSONObject(journal.readText())
        if (!record.optBoolean("dirty")) return
        val payload = payloadFile(path)
        java.io.RandomAccessFile(payload, "rw").use { it.fd.sync() }
        record.put("sealed", digest(payload)).put("incomplete", false)
        checkpoint(journal, record)
    }

    private fun stagingBytes(): Long = writes.listFiles().orEmpty().sumOf { it.length() } +
        recovery.listFiles().orEmpty().filter { it.extension == "payload" }.sumOf { it.length() }

    /** Keep both the previous payload and journal intact until an edited copy is fully durable. */
    private fun replacePayload(path: String, input: java.io.InputStream, size: Long, limit: Long): File {
        val existingJournal = journalFile(path)
        check(existingJournal.isFile || transactions.listFiles().orEmpty().count { it.extension == "json" } < MAX_SAVE_FILES) {
            "Limite du journal SAF atteinte."
        }
        val allowance = minOf(limit, MAX_STAGING_BYTES - stagingBytes())
        check(size in 0..allowance && writes.usableSpace >= size + 16L * 1024 * 1024) {
            "Journal des sauvegardes plein (512 Mio). Le payload et la source précédents restent conservés."
        }
        val temporary = File(writes, ".astra-payload-${UUID.randomUUID()}")
        try {
            temporary.outputStream().use { output -> copyBounded(input, output, allowance); output.fd.sync() }
            val target = payloadFile(path)
            check(temporary.renameTo(target)) { "Impossible de conserver la sauvegarde modifiée ; original privé préservé." }
            syncDirectory(writes)
            return target
        } finally { temporary.delete() }
    }

    /** Retain interrupted data before the next legitimate save reuses its logical filename. */
    private fun archiveInterrupted(path: String, record: JSONObject) {
        val payload = payloadFile(path)
        if (payload.isFile) {
            check(recovery.listFiles().orEmpty().count { it.extension == "json" } < MAX_RECOVERY_FILES) {
                "Trop de sauvegardes interrompues conservées. Exportez-les avant de réessayer."
            }
            val hash = digest(payload)
            val id = "${pathId(path)}-$hash"
            val archived = File(recovery, "$id.payload")
            val archiveRecord = JSONObject(record.toString()).put("id", id).put("recoveryHash", hash)
            checkpoint(File(recovery, "$id.json"), archiveRecord)
            if (archived.isFile) check(digest(archived) == hash && payload.delete())
            else check(payload.renameTo(archived)) { "Impossible de conserver l’écriture interrompue." }
            syncDirectory(recovery)
        }
        check(!journalFile(path).exists() || journalFile(path).delete())
        reservations.remove(path)
    }

    private fun recoverArchives() {
        for (file in recovery.listFiles().orEmpty().filter { it.extension == "json" }) {
            val record = JSONObject(file.readText())
            val path = record.getString("path")
            val archived = File(recovery, "${file.nameWithoutExtension}.payload")
            if (!archived.isFile) {
                val original = payloadFile(path)
                check(original.isFile && digest(original) == record.getString("recoveryHash")) {
                    "Copie d’une écriture interrompue introuvable ; journal conservé."
                }
                check(original.renameTo(archived)); syncDirectory(recovery)
            }
            val current = journalFile(path)
            if (current.isFile && !payloadFile(path).isFile && JSONObject(current.readText()).optBoolean("incomplete"))
                check(current.delete())
        }
    }

    private fun publish(path: String, finalize: Boolean = false) {
        reservations.remove(path)
        val journal = journalFile(path)
        if (!journal.isFile) return
        val record = JSONObject(journal.readText())
        if (!record.optBoolean("dirty")) return
        val local = payloadFile(path)
        val incoming = digest(local)
        check(sealed(record, local)) { "$path : écriture incomplète conservée dans Astra ; aucune publication." }
        val current = find(path, reload = true)
        val currentHash = current?.let(::hash)
        if (currentHash == incoming) {
            record.put("baseline", incoming).put("dirty", !finalize).put("state", "COMMITTED")
            checkpoint(journal, record); return
        }
        val baseline = record.optString("baseline").takeUnless { it.isEmpty() || it == "null" }
        if (currentHash != baseline) throw Conflict("$path a changé dans le dossier source ; la sauvegarde reste dans Astra.")
        val parentPath = path.substringBeforeLast('/', "")
        val parent = requireEntry(parentPath)
        val name = path.substringAfterLast('/')
        val stageName = ".astra-saf-pending-${UUID.randomUUID()}"
        val backupName = ".astra-saf-backup-${UUID.randomUUID()}-$name".take(240)
        val previousBackup = record.optString("backupUri").takeIf(String::isNotEmpty)
        record.remove("backupUri")
        record.put("state", "PUBLISH").put("stageName", stageName).put("backupName", backupName).put("incoming", incoming)
        checkpoint(journal, record)
        val staged = requireNotNull(DocumentsContract.createDocument(resolver, parent.uri, "application/octet-stream", stageName))
        record.put("stageUri", staged.toString()); checkpoint(journal, record)
        resolver.openFileDescriptor(staged, "wt")!!.use { fd ->
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { output -> local.inputStream().use { input -> copyBounded(input, output, MAX_FILE_BYTES) }; output.fd.sync() }
        }
        check(hash(metadata(staged)) == incoming) { "Le fournisseur a modifié la sauvegarde pendant son écriture." }
        val last = find(path, reload = true)
        if (last?.let(::hash) != baseline) throw Conflict("$path a été modifié pendant la sauvegarde ; copie conservée dans Astra.")
        if (last != null) {
            val backup = requireNotNull(DocumentsContract.renameDocument(resolver, last.uri, backupName))
            record.put("backupUri", backup.toString())
        }
        record.put("state", "BACKED_UP"); checkpoint(journal, record)
        try {
            requireNotNull(DocumentsContract.renameDocument(resolver, staged, name))
            // An fsync/close from one dup/fork does not prove another writer is finished.
            // Keep the journal dirty throughout the session; finalize only after Wine has stopped.
            val committed = JSONObject().put("path", path).put("baseline", incoming).put("sealed", incoming)
                .put("dirty", !finalize || digest(local) != incoming).put("state", "COMMITTED")
            record.optString("backupUri").takeIf(String::isNotEmpty)?.let { committed.put("backupUri", it) }
            checkpoint(journal, committed)
            // Keep one last original per modified file, after the new version is verified/durable.
            previousBackup?.let { runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(it)) } }
            commits.incrementAndGet(); invalidate()
        } catch (error: Throwable) {
            val backup = record.optString("backupUri").takeIf(String::isNotEmpty)
            if (backup != null && find(path, reload = true) == null) runCatching { DocumentsContract.renameDocument(resolver, Uri.parse(backup), name) }
            throw error
        }
    }

    private fun recoverPublications() = synchronized(lock) {
        transactions.listFiles().orEmpty().filter { it.extension == "json" }.forEach { journal ->
            val record = JSONObject(journal.readText())
            if (record.optString("state") !in setOf("PUBLISH", "BACKED_UP")) return@forEach
            val path = record.getString("path")
            val parentPath = path.substringBeforeLast('/', "")
            val entries = children(parentPath, reload = true)
            val target = entries.firstOrNull { it.name == path.substringAfterLast('/') }
            val staged = entries.firstOrNull { it.name == record.optString("stageName") }
            val backup = entries.firstOrNull { it.name == record.optString("backupName") }
            when {
                target == null && staged != null && hash(staged) == record.optString("incoming") -> {
                    requireNotNull(DocumentsContract.renameDocument(resolver, staged.uri, path.substringAfterLast('/')))
                    record.put("baseline", record.getString("incoming")).put("sealed", record.getString("incoming"))
                        .put("dirty", true).put("state", "COMMITTED")
                    checkpoint(journal, record)
                }
                target == null && backup != null -> {
                    requireNotNull(DocumentsContract.renameDocument(resolver, backup.uri, path.substringAfterLast('/')))
                    record.put("state", "OPEN"); checkpoint(journal, record)
                }
                target != null -> {
                    if (hash(target) == record.optString("incoming")) record.put("baseline", record.getString("incoming"))
                    record.put("state", "OPEN"); checkpoint(journal, record)
                    staged?.let { runCatching { DocumentsContract.deleteDocument(resolver, it.uri) } }
                }
            }
            invalidate()
        }
    }

    private fun rename(path: String, destination: String) {
        if (path == destination) { requireEntry(path); return }
        if (path.substringBeforeLast('/', "") != destination.substringBeforeLast('/', "")) throw ErrnoException("rename across SAF directories", OsConstants.EXDEV)
        if (playablePending(path)) { seal(path); publish(path) }
        val source = requireEntry(path)
        val existing = find(destination, reload = true)
        if (existing != null && (existing.directory || source.directory)) throw ErrnoException("rename", OsConstants.EEXIST)
        val backupName = ".astra-saf-backup-${UUID.randomUUID()}-${existing?.name.orEmpty()}".take(240)
        val move = File(moves, "${UUID.randomUUID()}.json")
        val record = JSONObject().put("path", path).put("destination", destination).put("sourceUri", source.uri.toString())
            .put("directory", source.directory).put("incoming", if (source.directory) JSONObject.NULL else hash(source))
            .put("backupName", backupName).put("state", "PLANNED")
        checkpoint(move, record)
        if (existing != null) {
            val backup = requireNotNull(DocumentsContract.renameDocument(resolver, existing.uri, backupName))
            record.put("backupUri", backup.toString()).put("state", "BACKED_UP"); checkpoint(move, record)
        }
        try {
            requireNotNull(DocumentsContract.renameDocument(resolver, source.uri, destination.substringAfterLast('/')))
            record.put("state", "RENAMED"); checkpoint(move, record)
            finishMove(record)
            move.delete(); invalidate()
        } catch (error: Throwable) {
            if (existing != null && find(destination, reload = true) == null) runCatching {
                val backup = children(destination.substringBeforeLast('/', ""), reload = true).firstOrNull { it.name == backupName }
                backup?.let { DocumentsContract.renameDocument(resolver, it.uri, destination.substringAfterLast('/')) }
            }
            throw error
        }
    }

    private fun finishMove(record: JSONObject) {
        val path = record.getString("path"); val destination = record.getString("destination")
        val oldJournal = journalFile(path)
        if (oldJournal.isFile) {
            // rename(2) preserves the inode of open writers. Move the private payload, too.
            val incoming = JSONObject(oldJournal.readText()).put("path", destination).put("baseline", record.opt("incoming"))
                .put("state", "COMMITTED")
            if (payloadFile(path).isFile) check(payloadFile(path).renameTo(payloadFile(destination))) { "Reprise de la sauvegarde renommée impossible." }
            else check(payloadFile(destination).isFile) { "Copie privée de la sauvegarde renommée introuvable." }
            checkpoint(journalFile(destination), incoming)
            oldJournal.delete()
        }
        val local = safeFile(game, path)
        if (local.exists()) {
            val next = safeFile(game, destination).apply { parentFile!!.mkdirs() }
            check(local.renameTo(next)) { "Reprise du dossier de travail renommé impossible." }
        }
    }

    private fun recoverMoves() {
        moves.listFiles().orEmpty().filter { it.extension == "json" }.forEach { move ->
            val record = JSONObject(move.readText())
            val path = record.getString("path"); val destination = record.getString("destination")
            val source = find(path, reload = true); val target = find(destination, reload = true)
            if (source == null && target != null && (record.optBoolean("directory") || hash(target) == record.optString("incoming"))) {
                finishMove(record); move.delete()
            } else if (source != null) {
                if (target == null) children(destination.substringBeforeLast('/', ""), reload = true)
                    .firstOrNull { it.name == record.optString("backupName") }?.let {
                        requireNotNull(DocumentsContract.renameDocument(resolver, it.uri, destination.substringAfterLast('/')))
                    }
                move.delete()
            } else throw Conflict("Renommage SAF interrompu : $path → $destination ; journal conservé.")
            invalidate()
        }
    }

    private fun entryForStat(path: String): Entry {
        if (path == iniPath && safeFile(game, path).isFile) return Entry(Uri.EMPTY, "Game.ini", false, safeFile(game, path).length(), safeFile(game, path).lastModified(), 0)
        val journal = journalFile(path)
        if (playablePending(path)) {
            val file = payloadFile(path); return Entry(Uri.EMPTY, path.substringAfterLast('/'), false, file.length(), file.lastModified(), 0)
        }
        return requireEntry(path)
    }

    private fun requireEntry(path: String) = find(path) ?: throw Missing(path)
    private fun canonicalCase(path: String): String {
        if (path.isEmpty()) return path
        var parent = ""
        val parts = normalize(path).split('/')
        for ((position, part) in parts.withIndex()) {
            val entries = children(parent)
            val found = entries.firstOrNull { it.name == part } ?: entries.singleOrNull { it.name.equals(part, true) }
            val name = found?.name ?: part
            parent = if (parent.isEmpty()) name else "$parent/$name"
            if (found == null) return (listOf(parent) + parts.drop(position + 1)).joinToString("/")
        }
        return parent
    }
    private fun find(path: String, reload: Boolean = false): Entry? {
        val normalized = normalize(path)
        if (normalized.isEmpty()) return metadata(rootUri)
        val parent = normalized.substringBeforeLast('/', "")
        val name = normalized.substringAfterLast('/')
        val entries = children(parent, reload)
        return entries.firstOrNull { it.name == name } ?: entries.singleOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun children(path: String, reload: Boolean = false): List<Entry> {
        val normalized = normalize(path)
        val now = android.os.SystemClock.elapsedRealtime()
        if (!reload) index[normalized]?.takeIf { now - it.loaded < 30_000 }?.let { return it.entries }
        val parent = if (normalized.isEmpty()) metadata(rootUri) else requireEntry(normalized)
        check(parent.directory) { "Un fichier empêche l’accès au dossier $normalized." }
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.uri))
        val entries = requireNotNull(resolver.query(query, COLUMNS, null, null, null)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(size < MAX_INDEX_ENTRIES) { "Le dossier dépasse la limite de l’index SAF ($MAX_INDEX_ENTRIES)." }
                    val name = requireNotNull(cursor.getString(1))
                    normalize(name); require('/' !in name && '\\' !in name && name.toByteArray(Charsets.UTF_8).size <= 255)
                    add(Entry(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), name,
                        cursor.getString(2) == Document.MIME_TYPE_DIR, cursor.getLong(3), cursor.getLong(4), cursor.getInt(5)))
                }
            }
        }
        index.remove(normalized)?.let { indexedEntries -= it.entries.size }
        while (index.isNotEmpty() && (indexedEntries + entries.size > MAX_INDEX_ENTRIES || index.size >= 256)) {
            val eldest = index.entries.first(); indexedEntries -= eldest.value.entries.size; index.remove(eldest.key)
        }
        index[normalized] = DirectoryIndex(now, entries); indexedEntries += entries.size
        return entries
    }

    private fun metadata(uri: Uri): Entry = requireNotNull(resolver.query(uri, COLUMNS, null, null, null)).use { cursor ->
        check(cursor.moveToFirst()) { "Document SAF inaccessible." }
        Entry(uri, cursor.getString(1), cursor.getString(2) == Document.MIME_TYPE_DIR, cursor.getLong(3), cursor.getLong(4), cursor.getInt(5))
    }

    private fun probePublication(parent: Uri) {
        var probe: Uri? = null
        try {
            probe = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/octet-stream", ".astra-saf-probe-${UUID.randomUUID()}"))
            resolver.openFileDescriptor(probe, "rwt")!!.use { fd -> Os.write(fd.fileDescriptor, byteArrayOf(1), 0, 1); Os.fsync(fd.fileDescriptor); Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET) }
            probe = requireNotNull(DocumentsContract.renameDocument(resolver, probe, ".astra-saf-probe-${UUID.randomUUID()}"))
            check(DocumentsContract.deleteDocument(resolver, probe)); probe = null
        } finally { probe?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }; invalidate() }
    }

    private fun openProvider(entry: Entry, mode: String) = requireNotNull(resolver.openFileDescriptor(entry.uri, mode)).also { fd ->
        try {
            check(OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) { "Le fournisseur ne fournit pas de fichier à accès aléatoire." }
            Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
        } catch (error: Throwable) { fd.close(); throw error }
    }

    private fun hash(entry: Entry): String = openProvider(entry, "r").use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { digest(it) } }
    private fun digest(file: File) = file.inputStream().use { digest(it) }
    private fun digest(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
        return md.digest().hexString()
    }
    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maximum: Long) {
        val buffer = ByteArray(65536); var total = 0L
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; check(total <= maximum) { "Fichier trop grand pour le journal SAF." }; output.write(buffer, 0, count) }
    }
    private fun invalidate() { index.clear(); indexedEntries = 0 }
    private fun pathId(path: String) = MessageDigest.getInstance("SHA-256").digest(normalize(path).toByteArray(Charsets.UTF_8)).hexString()
    private fun payloadFile(path: String) = File(writes, pathId(path))
    private fun journalFile(path: String) = File(transactions, "${pathId(path)}.json")
    private fun checkpoint(file: File, record: JSONObject) {
        val pending = File(file.parentFile, "${file.name}.new")
        pending.outputStream().use { it.write(record.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(pending.renameTo(file)) { "Journal SAF inaccessible." }
        syncDirectory(file.parentFile!!)
    }
    private fun syncDirectory(directory: File) {
        val directoryFd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or O_DIRECTORY, 0)
        try { Os.fsync(directoryFd) } finally { Os.close(directoryFd) }
    }
    private fun evictCleanPayloads() {
        transactions.listFiles().orEmpty().filter { it.extension == "json" }.forEach { journal ->
            val record = JSONObject(journal.readText())
            if (!record.optBoolean("dirty")) { payloadFile(record.getString("path")).delete(); journal.delete() }
        }
    }
    private fun identity(path: String): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))).long and Long.MAX_VALUE
    private fun readExactly(socket: LocalSocket, size: Int): ByteArray {
        val bytes = ByteArray(size); var offset = 0
        while (offset < size) { val read = socket.inputStream.read(bytes, offset, size - offset); check(read > 0) { "Connexion SAF interrompue." }; offset += read }
        return bytes
    }
    private class Missing(path: String) : java.io.FileNotFoundException(path)
    private class Conflict(message: String) : java.io.IOException(message)
    companion object {
        private const val MAGIC = 0x41534631
        private const val OP_OPEN = 1; private const val OP_STAT = 2; private const val OP_LIST = 3
        private const val OP_COMMIT = 4; private const val OP_MKDIR = 5; private const val OP_DELETE = 6; private const val OP_RENAME = 7
        private const val OP_RESERVE = 8
        private const val DIR_MODE = 0x41C0 // S_IFDIR | 0700
        private const val FILE_MODE = 0x8180 // S_IFREG | 0600
        private const val O_DIRECTORY = 0x10000 // Bionic arm64/x86_64 Linux ABI; omitted by Android OsConstants.
        private const val AT_REMOVEDIR = 0x200
        private const val MAX_INDEX_ENTRIES = 100_000
        private const val MAX_SAVE_FILES = 4096
        private const val MAX_RECOVERY_FILES = 128
        private const val MAX_EXPORT_BYTES = 512L * 1024 * 1024
        internal const val MAX_FILE_BYTES = 256L * 1024 * 1024
        private const val MAX_STAGING_BYTES = 512L * 1024 * 1024
        private const val MAX_CONFIG_BYTES = 2L * 1024 * 1024
        private val SAVE_FOLDERS = setOf("save", "saves", "savedata", "savefiles", "save_mtool")
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        internal fun normalize(path: String): String {
            require(!path.startsWith('/') && '\u0000' !in path && '\\' !in path)
            val parts = path.split('/').filter(String::isNotEmpty)
            require(parts.size <= 64 && parts.none { it == "." || it == ".." })
            return parts.joinToString("/")
        }
        private fun safeDirectory(root: File, path: String) = if (path.isEmpty()) root else safeFile(root, path)
    }
}
