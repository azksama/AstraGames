package fr.astragames.app.core.filesystem

import java.io.InputStream
import java.io.OutputStream

/** Streams untrusted content without allocating an array as large as the input. */
internal fun InputStream.copyBoundedTo(output: OutputStream, maxBytes: Long): Long {
    require(maxBytes >= 0) { "Limite de fichier invalide." }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return copied
        if (count == 0) {
            val next = read()
            if (next < 0) return copied
            require(copied < maxBytes) { "Fichier trop volumineux." }
            output.write(next)
            copied++
        } else {
            require(count.toLong() <= maxBytes - copied) { "Fichier trop volumineux." }
            output.write(buffer, 0, count)
            copied += count
        }
    }
}
