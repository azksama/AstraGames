package fr.astragames.app.wolfnative

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Private versioned native saves: never overwrite or import Wolf Windows saves. */
internal class WolfNativeSaveStore(context: Context, gameId: String, private val gameFingerprint: String) {
    val directory = directoryFor(context, gameId).apply { check(mkdirs() || isDirectory) }
    companion object {
        private val MAGIC = "ASTRA-WOLF-NATIVE-1\n".toByteArray()
        private const val MAX_BYTES = 16 * 1024 * 1024
        internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun directoryFor(context: Context, gameId: String) = File(context.filesDir, "wolf-native/${digest(gameId.toByteArray())}/saves")
    }
    private fun slotFile(slot: Int): File { require(slot in 0..9999) { "Emplacement de sauvegarde invalide : $slot" }; return File(directory, "slot-$slot.astrawolf") }
    fun slots(): List<Int> = directory.listFiles().orEmpty().mapNotNull {
        Regex("slot-(\\d{1,4})\\.astrawolf").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull()
    }.filter { it in 0..9999 && slotFile(it).isFile }.distinct().sorted()
    @Synchronized fun write(slot: Int, state: ByteArray) {
        require(state.size <= MAX_BYTES) { "Sauvegarde native trop volumineuse" }
        val file = slotFile(slot)
        if (file.isFile) {
            check(file.length() <= MAX_BYTES + 512) { "L’ancienne sauvegarde native est trop volumineuse ; elle est conservée." }
            val old = AtomicFile(file).readFully()
            val backup = AtomicFile(File(directory, "${file.name}.previous"))
            val stream = backup.startWrite()
            try { stream.write(old); backup.finishWrite(stream) } catch (error: Exception) { backup.failWrite(stream); throw error }
        }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            java.io.DataOutputStream(stream).apply {
                write(MAGIC); writeUTF(gameFingerprint); writeUTF(digest(state)); writeInt(state.size); write(state); flush()
            }
            atomic.finishWrite(stream)
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    @Synchronized fun read(slot: Int): ByteArray {
        val file = slotFile(slot)
        check(file.length() <= MAX_BYTES + 512) { "Sauvegarde native trop volumineuse" }
        return java.io.DataInputStream(AtomicFile(file).openRead()).use {
            val magic = ByteArray(MAGIC.size); it.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Format de sauvegarde native inconnu" }
            require(it.readUTF() == gameFingerprint) { "Cette sauvegarde native appartient à une autre version du jeu." }
            val checksum = it.readUTF()
            val size = it.readInt(); require(size in 0..MAX_BYTES) { "Taille de sauvegarde native invalide" }
            val bytes = ByteArray(size); it.readFully(bytes)
            require(it.read() == -1 && digest(bytes) == checksum) { "Sauvegarde native incomplète ou endommagée" }
            bytes
        }
    }
    @Synchronized fun export(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            directory.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".astrawolf") || it.name.endsWith(".previous")) }.sortedBy { it.name }.forEach { file ->
                zip.putNextEntry(ZipEntry("native-saves/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("native-saves/README.txt"))
            zip.write("Sauvegardes du moteur natif Astra. Elles ne sont pas compatibles avec le format Wolf Windows/Winlator.\n".toByteArray())
            zip.closeEntry()
        }
    }
}
