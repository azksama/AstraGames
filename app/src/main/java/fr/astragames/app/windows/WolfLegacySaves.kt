package fr.astragames.app.windows

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Read-only recovery of saves created before removal of the experimental native engine. */
internal class WolfLegacySaves(context: Context, gameId: String) {
    val directory = directoryFor(context, gameId)

    fun files(): List<File> = directory.listFiles().orEmpty().filter {
        it.isFile && it.canonicalFile.parentFile == directory.canonicalFile &&
            (it.name.endsWith(".astrawolf") || it.name.endsWith(".previous") ||
                it.name.endsWith(".astrawolf.bak") || it.name.endsWith(".astrawolf.new"))
    }.sortedBy { it.name }

    fun export(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            files().forEach { file ->
                require(file.length() <= 16 * 1024 * 1024 + 4096) { "Sauvegarde trop volumineuse ; le fichier original est conservé." }
                zip.putNextEntry(ZipEntry("native-saves/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("LISEZ-MOI.txt"))
            zip.write("Anciennes sauvegardes du moteur natif Astra, conservées sans modification. Ces fichiers ne sont pas des sauvegardes Windows Wolf RPG et ne peuvent pas être chargés avec Winlator.".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    companion object {
        fun directoryFor(context: Context, gameId: String): File {
            val digest = MessageDigest.getInstance("SHA-256").digest(gameId.toByteArray(Charsets.UTF_8)).hexString()
            return File(context.filesDir, "wolf-native/$digest/saves")
        }
    }
}
