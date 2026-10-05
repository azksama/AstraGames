package fr.astragames.app.data.saves

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readBounded(limit: Long): ByteArray {
    require(limit in 0..Int.MAX_VALUE.toLong()) { "Limite de lecture invalide." }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16384)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), limit - output.size() + 1).toInt())
        if (count < 0) return output.toByteArray()
        if (count == 0) {
            val single = read()
            if (single < 0) return output.toByteArray()
            require(output.size().toLong() < limit) { "Fichier trop volumineux." }
            output.write(single)
            continue
        }
        require(output.size().toLong() + count <= limit) { "Fichier trop volumineux." }
        output.write(buffer, 0, count)
    }
}
