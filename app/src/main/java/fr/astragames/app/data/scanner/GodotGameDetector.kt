package fr.astragames.app.data.scanner

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.core.model.DetectionResult
import fr.astragames.app.core.model.GameEngine
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Read only bounded headers; never unpack a game's resources to determine its engine. */
internal object GodotGameDetector {
    fun detect(context: Context, children: List<DocumentFile>, executable: String?): DetectionResult? {
        val files = children.filterNot { it.isDirectory }
        val packs = files.filter { it.name.orEmpty().endsWith(".pck", ignoreCase = true) }
        val matchingPack = packs.firstOrNull {
            it.name?.substringBeforeLast('.')?.equals(executable?.substringBeforeLast('.'), ignoreCase = true) == true
        } ?: packs.singleOrNull()
        matchingPack?.let { pack ->
            readEngine(context, pack)?.let { return result(it, pack.name, "En-tête du pack Godot") }
        }
        files.firstOrNull { it.name.equals("project.godot", ignoreCase = true) }?.let { project ->
            val engine = runCatching {
                context.contentResolver.openInputStream(project.uri)?.use {
                    projectEngine(it.readPrefix(MAX_PROJECT_BYTES).toString(Charsets.UTF_8))
                }
            }.getOrNull()
            if (engine != null) return result(engine, project.name, "Configuration du projet Godot")
        }
        files.firstOrNull { it.name.equals(executable, ignoreCase = true) }?.let { binary ->
            readEngine(context, binary, embedded = true)?.let {
                return result(it, binary.name, "Pack Godot intégré à l’exécutable")
            }
        }
        return null
    }

    private fun result(engine: GameEngine, entry: String?, evidence: String) =
        DetectionResult(engine, .99f, listOf(evidence), entry)

    private fun readEngine(context: Context, document: DocumentFile, embedded: Boolean = false): GameEngine? = runCatching {
        if (!embedded) return@runCatching context.contentResolver.openInputStream(document.uri)?.use {
            packEngine(it.readPrefix(HEADER_BYTES))
        }
        context.contentResolver.openFileDescriptor(document.uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                embeddedEngine(input.channel.size()) { offset, count -> readAt(input.channel, offset, count) }
            }
        }
    }.getOrNull()

    private fun InputStream.readPrefix(limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = read(bytes, count, limit - count)
            if (read <= 0) break
            count += read
        }
        return bytes.copyOf(count)
    }

    private fun readAt(channel: FileChannel, offset: Long, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0) break
        }
        return buffer.array().copyOf(buffer.position())
    }

    internal fun packEngine(header: ByteArray): GameEngine? {
        if (header.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != PACK_MAGIC || buffer.int !in 1..3) return null
        return when (buffer.int) {
            3 -> GameEngine.GODOT_3
            4 -> GameEngine.GODOT_4
            else -> null
        }
    }

    internal fun projectEngine(text: String): GameEngine? = when (CONFIG_VERSION.find(text)?.groupValues?.get(1)) {
        "4" -> GameEngine.GODOT_3
        "5" -> GameEngine.GODOT_4
        else -> null
    }

    internal fun embeddedEngine(size: Long, read: (Long, Int) -> ByteArray): GameEngine? {
        if (size < HEADER_BYTES + FOOTER_BYTES) return null
        val footer = read(size - FOOTER_BYTES, FOOTER_BYTES)
        if (footer.size != FOOTER_BYTES) return null
        val buffer = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN)
        val packSize = buffer.long
        if (buffer.int != PACK_MAGIC || packSize < HEADER_BYTES || packSize > size - FOOTER_BYTES) return null
        return packEngine(read(size - FOOTER_BYTES - packSize, HEADER_BYTES))
    }

    private const val HEADER_BYTES = 20
    private const val FOOTER_BYTES = 12
    private const val PACK_MAGIC = 0x43504447 // "GDPC", little endian, from Godot's file_access_pack.cpp.
    private const val MAX_PROJECT_BYTES = 64 * 1024
    private val CONFIG_VERSION = Regex("(?m)^\\s*config_version\\s*=\\s*([0-9]+)\\s*(?:;[^\\r\\n]*)?$")
}
