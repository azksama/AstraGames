package fr.astragames.app.data.saves

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Ren'Py stores the pickle in `log`; other entries must survive an edit. */
internal object RenPyArchive {
    data class Archive(val payload: ByteArray, val entries: Map<String, ByteArray>?, val compressed: Boolean)

    fun decode(data: ByteArray): Archive {
        require(data.size <= MAX_SAVE_BYTES) { "Sauvegarde trop volumineuse." }
        if (data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4b.toByte()) {
            val entries = linkedMapOf<String, ByteArray>()
            var total = 0
            ZipInputStream(data.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(entries.size < 128 && entry.name !in entries) { "Archive Ren'Py invalide." }
                    val bytes = zip.readBounded((MAX_SAVE_BYTES - total).toLong())
                    total += bytes.size
                    require(total <= MAX_SAVE_BYTES) { "Sauvegarde trop volumineuse." }
                    entries[entry.name] = bytes
                }
            }
            return Archive(entries["log"] ?: error("Le ZIP ne contient pas de sauvegarde Ren'Py (log absent)."), entries, false)
        }
        val compressed = data.size > 2 && data[0] == 0x78.toByte()
        return Archive(if (compressed) inflateSave(data) else data, null, compressed)
    }

    fun encode(archive: Archive, payload: ByteArray): ByteArray {
        val entries = archive.entries ?: return if (archive.compressed) deflateSave(payload) else payload
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                // Old signatures cannot authenticate the modified log. Ren'Py asks the player to trust it.
                zip.write(when (name) { "log" -> payload; "signatures" -> ByteArray(0); else -> bytes })
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
