package fr.astragames.app.windows

import android.util.Base64
import org.json.JSONObject
import java.io.File

/** A durable original survives process death; restoration never overwrites another writer. */
internal class WolfDirectConfiguration(private val directory: File) {
    private val state = File(directory, "source-configuration.json")

    fun apply(root: File, executable: String?) {
        restore()
        val parent = executable?.let { safeFile(root, it).parentFile } ?: root
        val ini = parent!!.listFiles()?.firstOrNull { it.name.equals("Game.ini", true) } ?: File(parent, "Game.ini")
        safeFile(root, ini.relativeTo(root).invariantSeparatorsPath)
        val original = if (ini.isFile) ini.readBytes() else null
        val configured = wolfSoftwareConfiguration(original?.toString(Charsets.ISO_8859_1).orEmpty()).toByteArray(Charsets.ISO_8859_1)
        if (original != null && original.contentEquals(configured)) return
        directory.mkdirs()
        val record = JSONObject().put("root", root.canonicalPath).put("path", ini.relativeTo(root).invariantSeparatorsPath)
            .put("original", original?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL)
            .put("configured", java.security.MessageDigest.getInstance("SHA-256").digest(configured).hexString())
        val pending = File(directory, "source-configuration.new").apply {
            outputStream().use { it.write(record.toString().toByteArray()); it.fd.sync() }
        }
        check(pending.renameTo(state)) { "Impossible de conserver la configuration d’origine." }
        replace(ini, configured)
    }

    fun restore() {
        if (!state.isFile) return
        val record = JSONObject(state.readText())
        val ini = safeFile(File(record.getString("root")), record.getString("path"))
        val original = if (record.isNull("original")) null else Base64.decode(record.getString("original"), Base64.NO_WRAP)
        // A process may have died after the journal was written but before the patch.
        if ((!ini.exists() && original == null) || (ini.isFile && original != null && ini.readBytes().contentEquals(original))) {
            check(state.delete()); return
        }
        check(ini.isFile) { "Game.ini est inaccessible. Sa configuration d’origine reste conservée dans Astra." }
        val unchanged = WolfRuntimeInstaller.hash(ini) == record.getString("configured")
        val restored = if (unchanged) original else mergeGameChanges(ini.readBytes(), original)
        if (restored == null) check(ini.delete()) else replace(ini, restored)
        check(state.delete()) { "Impossible de finaliser la restauration de Game.ini." }
    }

    private fun mergeGameChanges(current: ByteArray, original: ByteArray?): ByteArray? {
        var text = current.toString(Charsets.ISO_8859_1)
        val before = original?.toString(Charsets.ISO_8859_1).orEmpty()
        for (key in listOf("SoftModeFlag", "WindowModeFlag")) {
            val pattern = Regex("(?mi)^[ \\t]*$key[ \\t]*=[ \\t]*([^\\r\\n]*)")
            val matches = pattern.findAll(text).toList()
            val previous = pattern.find(before)?.value
            check(matches.size == 1 && matches.single().groupValues[1].trim() == "1") {
            "Game.ini a changé pendant la partie. Sa configuration d’origine est conservée dans Astra ; aucun écrasement effectué."
            }
            text = if (previous != null) text.replaceRange(matches.single().range, previous)
                else text.replace(Regex("(?mi)^[ \\t]*$key[ \\t]*=[^\\r\\n]*(?:\\r?\\n|$)"), "")
        }
        return if (original == null && text.isBlank()) null else text.toByteArray(Charsets.ISO_8859_1)
    }

    private fun replace(file: File, bytes: ByteArray) {
        val pending = File(file.parentFile, ".astra-wolf-ini-${java.util.UUID.randomUUID()}")
        try {
            pending.outputStream().use { it.write(bytes); it.fd.sync() }
            check(pending.renameTo(file)) { "Impossible de remplacer Game.ini ; l’original est conservé dans Astra." }
        } finally { pending.delete() }
    }
}
