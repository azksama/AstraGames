package fr.astragames.app.mkxpz

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Versioned extraction of the bundled, source-built standard library, never game-supplied ZIPs. */
internal object MkxpzRuby {
    @Synchronized fun loadPaths(context: Context): List<String> {
        val asset = "mkxpz/ruby-stdlib.zip"
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(asset).use { input ->
            val buffer = ByteArray(32768)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val root = File(context.filesDir, "mkxpz-ruby/$hash").canonicalFile
        if (!File(root, "complete").isFile) {
            root.mkdirs()
            ZipInputStream(context.assets.open(asset)).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(root, entry.name).canonicalFile
                    require(target.toPath().startsWith(root.toPath()) && target != root) { "Archive Ruby invalide." }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile!!.mkdirs()
                        val part = File.createTempFile("ruby-", ".part", target.parentFile)
                        try {
                            part.outputStream().use { output ->
                                val buffer = ByteArray(32768)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) { break }
                                    total += count
                                    require(total <= 64L * 1024 * 1024) { "Archive Ruby trop volumineuse." }
                                    output.write(buffer, 0, count)
                                }
                            }
                            check(part.renameTo(target)) { "Installation Ruby impossible." }
                        } finally { part.delete() }
                    }
                    zip.closeEntry()
                }
            }
            File(root, "complete").writeText(hash)
        }
        val library = File(root, "3.1.0")
        val arch = if (android.os.Build.SUPPORTED_ABIS.first() == "arm64-v8a") "aarch64" else "x86_64"
        return listOf(library.absolutePath, File(library, "$arch-linux-android-android").absolutePath)
    }
}
