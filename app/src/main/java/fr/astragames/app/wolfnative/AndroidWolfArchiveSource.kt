package fr.astragames.app.wolfnative

import fr.astragames.wolf.WolfArchiveSource
import java.io.Closeable
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positional reads of an Android-granted archive; there is no extraction or staging copy. */
internal class AndroidWolfArchiveSource(
    private val channel: FileChannel,
    private val owner: Closeable,
    private val startOffset: Long,
    override val size: Long,
    private val checkSession: () -> Unit,
) : WolfArchiveSource {
    @Volatile private var closed = false
    init {
        require(size >= 0 && startOffset >= 0 && startOffset <= Long.MAX_VALUE - size) { "Taille d’archive Wolf invalide" }
        // A pipe from a remote DocumentsProvider cannot supply random resource reads.
        channel.position(startOffset)
    }
    override fun read(offset: Long, length: Int): ByteArray {
        checkSession(); check(!closed) { "Archive Wolf fermée" }
        require(length in 0..128 * 1024 * 1024 && offset >= 0 && offset <= size && length.toLong() <= size - offset) {
            "Lecture hors de l’archive Wolf"
        }
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            checkSession(); check(!closed) { "Archive Wolf fermée" }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Lecture d’archive annulée")
            val count = channel.read(buffer, startOffset + offset + buffer.position())
            if (count < 0) throw EOFException("Archive Wolf tronquée")
            check(count > 0) { "L’archive Wolf n’est pas lisible par accès direct" }
        }
        return bytes
    }
    override fun close() { if (!closed) { closed = true; owner.close() } }
}
