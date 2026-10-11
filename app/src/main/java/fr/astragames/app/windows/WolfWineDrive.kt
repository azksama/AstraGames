package fr.astragames.app.windows

import java.io.File
import java.nio.file.Files
import java.util.UUID

/** G: is local to this game's prefix. Z: / is Android's read-only system volume. */
internal class WolfWineDrive(private val prefix: File, private val game: File) {
    fun mount() {
        val target = game.canonicalFile
        check(target.isDirectory) { "Le dossier du jeu est inaccessible." }
        val devices = File(prefix, "dosdevices")
        check(devices.isDirectory || devices.mkdirs()) { "Impossible de préparer le lecteur du jeu." }
        check(!Files.isSymbolicLink(devices.toPath())) { "Le dossier des lecteurs Windows est invalide." }
        val link = File(devices, "g:").toPath()
        if (Files.isSymbolicLink(link)) {
            if (link.toFile().canonicalFile == target) return
        } else {
            // Never replace a real directory or file that could contain existing saves.
            check(!Files.exists(link)) { "Le lecteur G: contient déjà des fichiers. Les sauvegardes sont conservées." }
        }
        val pending = File(devices, ".astra-game-drive-${UUID.randomUUID()}")
        try {
            Files.createSymbolicLink(pending.toPath(), target.toPath())
            check(pending.renameTo(link.toFile())) { "Impossible de raccorder le dossier au lecteur G:." }
        } finally { Files.deleteIfExists(pending.toPath()) }
    }

    fun executable(relative: String): String {
        val file = safeFile(game, relative)
        return "G:\\" + file.relativeTo(game.canonicalFile).invariantSeparatorsPath.replace('/', '\\')
    }

    /** Check the actual save filesystem, without modifying any existing file. */
    fun verifyWritable(executableDirectory: File): Long {
        val directories = listOf(executableDirectory) + executableDirectory.listFiles().orEmpty().filter {
            it.isDirectory && it.name.lowercase() in setOf("save", "saves", "savedata", "savefiles")
        }
        for (directory in directories) {
            val probe = File(directory, ".astra-wolf-write-${UUID.randomUUID()}")
            try { probe.outputStream().use { it.write(1); it.fd.sync() } }
            catch (error: Exception) {
                throw IllegalStateException("Impossible d’écrire dans ${directory.name}. Réautorisez le dossier du jeu ou choisissez la copie privée dans les réglages.", error)
            } finally { probe.delete() }
        }
        return executableDirectory.usableSpace
    }
}
