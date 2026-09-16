package fr.astragames.app.data.saves

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readBounded(limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16384)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), limit - output.size() + 1).toInt())
        if (count < 0) return output.toByteArray()
        require(output.size().toLong() + count <= limit) { "Fichier trop volumineux." }
        output.write(buffer, 0, count)
    }
}
