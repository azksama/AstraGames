package fr.astragames.app.wolfnative

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.AtomicFile
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import fr.astragames.wolf.WolfParser
import fr.astragames.wolf.WolfSaveData

/** Private versioned native saves: never overwrite or import Wolf Windows saves. */
internal class WolfNativeSaveStore(context: Context, gameId: String, private val gameFingerprint: String) {
    val directory = directoryFor(context, gameId).apply { check(mkdirs() || isDirectory) }
    private data class FileStamp(val device: Long, val inode: Long, val size: Long,
        val modifiedSeconds: Long, val modifiedNanos: Long, val changedSeconds: Long, val changedNanos: Long)
    private data class ReadCache(val file: File, val stamp: FileStamp, val data: WolfSaveData)
    private var readCache: ReadCache? = null // One validated file, never the whole save catalogue.
    internal var envelopesRead = 0L
        private set
    companion object {
        private val MAGIC = "ASTRA-WOLF-NATIVE-1\n".toByteArray()
        private val DATA_MAGIC = "ASTRA-WOLF-NATIVE-2\n".toByteArray()
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_FILE_BYTES = MAX_BYTES + 4096
        private const val MAX_CACHED_BYTES = 4 * 1024 * 1024
        internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun directoryFor(context: Context, gameId: String) = File(context.filesDir, "wolf-native/${digest(gameId.toByteArray())}/saves")
    }
    private fun slotFile(slot: Int): File { require(slot in 0..9999) { "Emplacement de sauvegarde invalide : $slot" }; return File(directory, "slot-$slot.astrawolf") }
    private fun referenceFile(key: String): File {
        if (key.startsWith("slot:")) {
            require(Regex("slot:(0|[1-9][0-9]{0,3})").matches(key)) { "Emplacement de sauvegarde invalide" }
            return slotFile(key.substringAfter(':').toInt())
        }
        require(key.isNotEmpty() && key.toByteArray(Charsets.UTF_8).size <= 1024 &&
            key.none { it in "%:*?\"<>|" } && WolfParser.normalizePath(key) == key && !key.contains("//")) {
            "Nom de sauvegarde invalide : $key"
        }
        // Custom game paths are identities inside private native storage, never OS paths.
        return File(directory, "named-${digest(key.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))}.astrawolf")
    }
    fun slots(): List<Int> = directory.listFiles().orEmpty().mapNotNull {
        Regex("slot-(\\d{1,4})\\.astrawolf").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull()
    }.filter { it in 0..9999 && slotFile(it).isFile }.distinct().sorted()
    @Synchronized fun write(slot: Int, state: ByteArray) = writeEnvelope(slotFile(slot), state, null)
    @Synchronized fun exists(key: String): Boolean = referenceFile(key).isFile
    @Synchronized fun writeData(key: String, data: WolfSaveData) = writeEnvelope(referenceFile(key), data.encode(), key)
    @Synchronized fun readData(key: String): WolfSaveData {
        val file = referenceFile(key)
        val before = stamp(file)
        readCache?.takeIf { before != null && it.file == file && it.stamp == before }?.let {
            return detached(it.data)
        }
        readCache = null
        val bytes = readEnvelope(file, key)
        val data = WolfSaveData.decode(bytes)
        if (bytes.size <= MAX_CACHED_BYTES && before != null && before == stamp(file)) {
            readCache = ReadCache(file, before, data)
            return detached(data)
        }
        return data
    }
    private fun detached(data: WolfSaveData) = data.copy(snapshot = data.snapshot?.copyOf(),
        numbers = data.numbers.toMap(), strings = data.strings.toMap())
    private fun stamp(file: File): FileStamp? {
        // Android 8 lacks nanosecond stamps. Keep its uncached validation rather
        // than mistaking two same-second writes for the same file.
        if (Build.VERSION.SDK_INT < 27) return null
        if (File("${file.path}.bak").exists() || File("${file.path}.new").exists()) return null
        return try { Os.stat(file.absolutePath).let { FileStamp(it.st_dev, it.st_ino, it.st_size,
            it.st_mtim.tv_sec, it.st_mtim.tv_nsec, it.st_ctim.tv_sec, it.st_ctim.tv_nsec) } }
        catch (_: android.system.ErrnoException) { null }
    }
    private fun writeEnvelope(file: File, state: ByteArray, key: String?) {
        require(state.size <= MAX_BYTES) { "Sauvegarde native trop volumineuse" }
        if (readCache?.file == file) readCache = null
        if (file.isFile) {
            check(file.length() <= MAX_FILE_BYTES) { "L’ancienne sauvegarde native est trop volumineuse ; elle est conservée." }
            val old = AtomicFile(file).readFully()
            val backup = AtomicFile(File(directory, "${file.name}.previous"))
            val stream = backup.startWrite()
            try { stream.write(old); backup.finishWrite(stream) } catch (error: Exception) { backup.failWrite(stream); throw error }
        }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            java.io.DataOutputStream(stream).apply {
                write(if (key == null) MAGIC else DATA_MAGIC); writeUTF(gameFingerprint)
                if (key != null) writeUTF(key)
                writeUTF(digest(state)); writeInt(state.size); write(state); flush()
            }
            atomic.finishWrite(stream)
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    @Synchronized fun read(slot: Int): ByteArray {
        val file = slotFile(slot)
        val payload = readEnvelope(file, "slot:$slot")
        // Public legacy API returns a complete state, including when commands created its file.
        if (payload.size >= 8 && java.nio.ByteBuffer.wrap(payload, 4, 4).int == 0x41574e46)
            return requireNotNull(WolfSaveData.decode(payload).snapshot) { "Cette sauvegarde contient seulement des variables." }
        return payload
    }
    private fun readEnvelope(file: File, key: String): ByteArray {
        envelopesRead++
        check(file.length() <= MAX_FILE_BYTES) { "Sauvegarde native trop volumineuse" }
        return java.io.DataInputStream(AtomicFile(file).openRead()).use {
            val magic = ByteArray(MAGIC.size); it.readFully(magic)
            val dataFormat = magic.contentEquals(DATA_MAGIC)
            require(dataFormat || magic.contentEquals(MAGIC)) { "Format de sauvegarde native inconnu" }
            require(it.readUTF() == gameFingerprint) { "Cette sauvegarde native appartient à une autre version du jeu." }
            if (dataFormat) require(it.readUTF().lowercase(Locale.ROOT) == key.lowercase(Locale.ROOT)) { "Identité de sauvegarde native incorrecte" }
            val checksum = it.readUTF()
            val size = it.readInt(); require(size in 0..MAX_BYTES) { "Taille de sauvegarde native invalide" }
            val bytes = ByteArray(size); it.readFully(bytes)
            require(it.read() == -1 && digest(bytes) == checksum) { "Sauvegarde native incomplète ou endommagée" }
            bytes
        }
    }
    fun export(output: OutputStream) = export(output, null)
    @Synchronized fun export(output: OutputStream, files: WolfNativeFiles?) {
        ZipOutputStream(output).use { zip ->
            directory.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".astrawolf") || it.name.endsWith(".previous")) }.sortedBy { it.name }.forEach { file ->
                require(file.canonicalFile.parentFile == directory.canonicalFile && file.length() <= MAX_FILE_BYTES) { "Sauvegarde native hors capacité d’export" }
                zip.putNextEntry(ZipEntry("native-saves/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            files?.export(zip)
            zip.putNextEntry(ZipEntry("native-saves/README.txt"))
            zip.write("Sauvegardes du moteur natif Astra. Elles ne sont pas compatibles avec le format Wolf Windows/Winlator.\nLes fichiers texte créés par le jeu et leurs versions précédentes sont conservés dans game-files et file-backups. Les fichiers du jeu d’origine restent dans leur dossier.\n".toByteArray())
            zip.closeEntry()
        }
    }
}
