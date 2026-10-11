package fr.astragames.wolf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

/** Complete native state plus the independent variable writes of Wolf's save commands. */
data class WolfSaveData(
    val snapshot: ByteArray? = null,
    val numbers: Map<Int, Int> = emptyMap(),
    val strings: Map<Int, String> = emptyMap(),
) {
    fun encode(): ByteArray {
        val output = LimitedOutput()
        DataOutputStream(output).use { writer ->
            writer.writeInt(MAGIC); writer.writeInt(1)
            val state = snapshot
            writer.writeInt(state?.size ?: -1)
            state?.let { require(it.size <= MAX_BYTES); writer.write(it) }
            require(numbers.size <= MAX_ENTRIES && strings.size <= MAX_ENTRIES)
            writer.writeInt(numbers.size)
            numbers.toSortedMap().forEach { (key, value) -> writer.writeInt(key); writer.writeInt(value) }
            writer.writeInt(strings.size)
            strings.toSortedMap().forEach { (key, value) ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_TEXT_BYTES) { "Chaîne de sauvegarde trop grande" }
                writer.writeInt(key); writer.writeInt(bytes.size); writer.write(bytes)
            }
        }
        val body = output.toByteArray()
        return ByteArrayOutputStream().also { out -> DataOutputStream(out).use { writer ->
            writer.writeInt(body.size); writer.write(body)
            writer.writeInt(CRC32().apply { update(body) }.value.toInt())
        } }.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x41574e46 // AWNF; distinct from the complete-state AWNS format.
        private const val SNAPSHOT_MAGIC = 0x41574e53
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_ENTRIES = 100_000
        private const val MAX_TEXT_BYTES = 1_000_000

        fun decode(bytes: ByteArray): WolfSaveData {
            require(bytes.size in 16..MAX_BYTES) { "Taille de sauvegarde native invalide" }
            val outer = DataInputStream(ByteArrayInputStream(bytes))
            val size = outer.readInt()
            require(size == bytes.size - 8) { "Sauvegarde native tronquée" }
            val body = ByteArray(size).also(outer::readFully)
            require(outer.readInt() == CRC32().apply { update(body) }.value.toInt()) { "Somme de contrôle de sauvegarde incorrecte" }
            val input = DataInputStream(ByteArrayInputStream(body))
            val magic = input.readInt()
            // Beta14 files remain readable; their game identity is checked by the VM on load.
            if (magic == SNAPSHOT_MAGIC) return WolfSaveData(snapshot = bytes.copyOf())
            require(magic == MAGIC && input.readInt() == 1) { "Format de sauvegarde native inconnu" }
            val snapshotSize = input.readInt()
            require(snapshotSize == -1 || snapshotSize in 16..MAX_BYTES && snapshotSize <= input.available() - 8) { "État de sauvegarde tronqué" }
            val state = if (snapshotSize == -1) null else ByteArray(snapshotSize).also(input::readFully)
            val numbers = buildMap {
                repeat(input.count(8)) {
                    val key = input.readInt(); require(key !in this) { "Variable de sauvegarde dupliquée" }
                    put(key, input.readInt())
                }
            }
            val strings = buildMap {
                repeat(input.count(8)) {
                    val key = input.readInt(); require(key !in this) { "Chaîne de sauvegarde dupliquée" }
                    val length = input.readInt()
                    require(length in 0..MAX_TEXT_BYTES && length <= input.available()) { "Chaîne de sauvegarde tronquée" }
                    val bytes = ByteArray(length).also(input::readFully)
                    val text = try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    } catch (error: java.nio.charset.CharacterCodingException) {
                        throw IllegalArgumentException("Chaîne de sauvegarde UTF-8 invalide", error)
                    }
                    put(key, text)
                }
            }
            require(input.available() == 0) { "Données de sauvegarde supplémentaires" }
            return WolfSaveData(state, numbers, strings)
        }
        private fun DataInputStream.count(minimum: Int): Int = readInt().also {
            require(it in 0..MAX_ENTRIES && it.toLong() * minimum <= available()) { "Compte de sauvegarde invalide" }
        }
    }

    private class LimitedOutput : ByteArrayOutputStream() {
        override fun write(value: Int) { require(count < MAX_BYTES - 8) { "Sauvegarde native trop grande" }; super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && count.toLong() + length <= MAX_BYTES - 8) { "Sauvegarde native trop grande" }
            super.write(bytes, offset, length)
        }
    }
}
