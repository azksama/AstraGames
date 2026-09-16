package fr.astragames.app.data.saves

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Graphe de valeurs decode du format Ruby Marshal (sauvegardes RGSS : RPG Maker XP/VX/VX Ace). */
sealed class MarshalValue {
    data object NilValue : MarshalValue()
    data class Bool(val value: Boolean) : MarshalValue()
    data class IntValue(val value: Long) : MarshalValue()
    data class FloatValue(val value: Double) : MarshalValue()
    data class SymbolValue(val name: String) : MarshalValue()
    /** Chaine octets + attributs eventuels (encodage, etc.). */
    data class StringValue(val bytes: ByteArray, val ivars: LinkedHashMap<String, MarshalValue> = LinkedHashMap()) : MarshalValue() {
        val text: String get() = String(bytes, StandardCharsets.UTF_8)
        override fun equals(other: Any?) = other is StringValue && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    data class ArrayValue(val items: MutableList<MarshalValue>) : MarshalValue()
    data class HashValue(val entries: LinkedHashMap<MarshalValue, MarshalValue>, var defaultValue: MarshalValue? = null) : MarshalValue()
    data class ObjectValue(val className: SymbolValue, val ivars: LinkedHashMap<SymbolValue, MarshalValue>) : MarshalValue()
    data class StructValue(val className: SymbolValue, val members: LinkedHashMap<SymbolValue, MarshalValue>) : MarshalValue()
    /** Objet avec marshal_dump personnalise : conserve brut pour la reecriture. */
    data class UserDefined(val className: SymbolValue, val payload: ByteArray) : MarshalValue() {
        override fun equals(other: Any?) = other is UserDefined && className == other.className && payload.contentEquals(other.payload)
        override fun hashCode() = payload.contentHashCode()
    }
    /** Objet etendu par un module. */
    data class ExtendedValue(val module: SymbolValue, val value: MarshalValue) : MarshalValue()
    /** Ancien format de module, conserve brut. */
    data class ModuleValue(val name: SymbolValue, val value: MarshalValue) : MarshalValue()
}

/** Lecture/ecriture du format Ruby Marshal 4.8 utilise par les sauvegardes RGSS. */
object Marshal {
    private const val MAJOR = 4
    private const val MINOR = 8

    fun load(data: ByteArray): MarshalValue {
        val roots = loadAll(data)
        require(roots.size == 1) { "Plusieurs flux Marshal presents." }
        return roots.single()
    }

    fun loadAll(data: ByteArray): List<MarshalValue> {
        val stream = ByteArrayInputStream(data)
        val roots = mutableListOf<MarshalValue>()
        while (stream.available() > 0) {
            require(stream.read() == MAJOR && stream.read() == MINOR) { "En-tete Marshal invalide." }
            roots += MarshalReader(stream).readValue()
            require(roots.size <= 128) { "Trop de flux Marshal." }
        }
        require(roots.isNotEmpty()) { "Sauvegarde Marshal vide." }
        return roots
    }

    fun dump(value: MarshalValue): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(MAJOR); output.write(MINOR)
        MarshalWriter(output).writeValue(value)
        return output.toByteArray()
    }

    private class MarshalReader(private val stream: InputStream) {
        private val symbols = mutableListOf<String>()
        private val registry = mutableListOf<MarshalValue>()

        fun readValue(): MarshalValue {
            val type = byte()
            return when (type) {
                '0'.code -> MarshalValue.NilValue
                'T'.code -> MarshalValue.Bool(true)
                'F'.code -> MarshalValue.Bool(false)
                'i'.code -> MarshalValue.IntValue(long())
                'f'.code -> register(MarshalValue.FloatValue(parseFloat(stringBody())))
                '"'.code -> register(MarshalValue.StringValue(bodyBytes()))
                ':'.code -> readSymbol()
                ';'.code -> MarshalValue.SymbolValue(symbols[long().toInt()])
                '@'.code -> registry[long().toInt()]
                '['.code -> {
                    val size = long().toInt()
                    val items = MutableList(size) { MarshalValue.NilValue as MarshalValue }
                    val array = MarshalValue.ArrayValue(items)
                    registry += array
                    repeat(size) { index -> items[index] = readValue() }
                    array
                }
                '{'.code -> readHash(hasDefault = false)
                '}'.code -> readHash(hasDefault = true)
                'o'.code -> {
                    val className = readValue() as MarshalValue.SymbolValue
                    val count = long().toInt()
                    val ivars = LinkedHashMap<MarshalValue.SymbolValue, MarshalValue>()
                    val target = MarshalValue.ObjectValue(className, ivars)
                    registry += target
                    repeat(count) {
                        val key = readValue() as MarshalValue.SymbolValue
                        ivars[key] = readValue()
                    }
                    target
                }
                'S'.code -> {
                    val className = readValue() as MarshalValue.SymbolValue
                    val count = long().toInt()
                    val members = LinkedHashMap<MarshalValue.SymbolValue, MarshalValue>()
                    val target = MarshalValue.StructValue(className, members)
                    registry += target
                    repeat(count) {
                        val key = readValue() as MarshalValue.SymbolValue
                        members[key] = readValue()
                    }
                    target
                }
                'u'.code -> {
                    val className = readValue() as MarshalValue.SymbolValue
                    register(MarshalValue.UserDefined(className, bodyBytes()))
                }
                'e'.code -> MarshalValue.ExtendedValue(readValue() as MarshalValue.SymbolValue, readValue())
                'M'.code -> MarshalValue.ModuleValue(readValue() as MarshalValue.SymbolValue, readValue())
                'I'.code -> {
                    val wrapped = readValue()
                    val count = long().toInt()
                    val ivars = LinkedHashMap<String, MarshalValue>()
                    repeat(count) {
                        val key = readValue() as MarshalValue.SymbolValue
                        ivars[key.name] = readValue()
                    }
                    require(wrapped is MarshalValue.StringValue) { "Attributs Marshal non pris en charge pour cet objet." }
                    wrapped.ivars.putAll(ivars)
                    wrapped
                }
                else -> error("Type Marshal inconnu : " + type)
            }
        }

        private fun readHash(hasDefault: Boolean): MarshalValue {
            val size = long().toInt()
            val entries = LinkedHashMap<MarshalValue, MarshalValue>()
            val hash = MarshalValue.HashValue(entries)
            registry += hash
            repeat(size) {
                val key = readValue()
                entries[key] = readValue()
            }
            if (hasDefault) {
                val default = readValue()
                hash.defaultValue = default
            }
            return hash
        }

        private fun readSymbol(): MarshalValue.SymbolValue {
            val bytes = bodyBytes()
            val name = String(bytes, StandardCharsets.UTF_8)
            symbols += name
            return MarshalValue.SymbolValue(name)
        }

        private fun <T : MarshalValue> register(value: T): T { registry += value; return value }

        private fun parseFloat(raw: String): Double = when (raw) {
            "inf" -> Double.POSITIVE_INFINITY
            "-inf" -> Double.NEGATIVE_INFINITY
            "nan" -> Double.NaN
            else -> raw.toDouble()
        }

        private fun stringBody(): String = String(bodyBytes(), StandardCharsets.UTF_8)

        private fun bodyBytes(): ByteArray {
            val size = long().toInt()
            require(size in 0..64 * 1024 * 1024) { "Bloc Marshal trop volumineux." }
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) {
                val chunk = stream.read(bytes, read, size - read)
                if (chunk < 0) error("Donnees Marshal tronquees.")
                read += chunk
            }
            return bytes
        }

        private fun byte(): Int = stream.read().also { if (it < 0) error("Fin de donnees Marshal.") }

        private fun long(): Long {
            var c = byte()
            if (c >= 128) c -= 256
            if (c == 0) return 0
            if (c > 0) {
                if (c > 4) return (c - 5).toLong()
                var value = 0L
                repeat(c) { value = value or ((byte().toLong() and 0xFF) shl (8 * it)) }
                return value
            }
            if (c < -4) return (c + 5).toLong()
            var value = -1L
            repeat(-c) { value = (value and (0xFFL shl (8 * it)).inv()) or ((byte().toLong() and 0xFF) shl (8 * it)) }
            return value
        }
    }

    private class MarshalWriter(private val stream: ByteArrayOutputStream) {
        private val references = java.util.IdentityHashMap<MarshalValue, Int>()

        fun writeValue(value: MarshalValue) {
            val referenceable = value is MarshalValue.FloatValue || value is MarshalValue.StringValue ||
                value is MarshalValue.ArrayValue || value is MarshalValue.HashValue || value is MarshalValue.ObjectValue ||
                value is MarshalValue.StructValue || value is MarshalValue.UserDefined
            if (referenceable) {
                references[value]?.let { stream.write('@'.code); writeFixnum(it.toLong()); return }
                references[value] = references.size
            }
            when (value) {
                MarshalValue.NilValue -> stream.write('0'.code)
                is MarshalValue.Bool -> stream.write(if (value.value) 'T'.code else 'F'.code)
                is MarshalValue.IntValue -> writeLong(value.value)
                is MarshalValue.FloatValue -> writeFloat(value.value)
                is MarshalValue.SymbolValue -> { stream.write(':'.code); writeBody(value.name.toByteArray(StandardCharsets.UTF_8)) }
                is MarshalValue.StringValue -> {
                    if (value.ivars.isEmpty()) {
                        stream.write('"'.code)
                        writeBody(value.bytes)
                    } else {
                        stream.write('I'.code)
                        stream.write('"'.code)
                        writeBody(value.bytes)
                        writeFixnum(value.ivars.size.toLong())
                        value.ivars.forEach { (name, attribute) ->
                            stream.write(':'.code)
                            writeBody(name.toByteArray(StandardCharsets.UTF_8))
                            writeValue(attribute)
                        }
                    }
                }
                is MarshalValue.ArrayValue -> {
                    stream.write('['.code)
                    writeFixnum(value.items.size.toLong())
                    value.items.forEach(::writeValue)
                }
                is MarshalValue.HashValue -> {
                    stream.write(if (value.defaultValue == null) '{'.code else '}'.code)
                    writeFixnum(value.entries.size.toLong())
                    value.entries.forEach { (key, entry) -> writeValue(key); writeValue(entry) }
                    value.defaultValue?.let(::writeValue)
                }
                is MarshalValue.ObjectValue -> {
                    stream.write('o'.code)
                    writeValue(value.className)
                    writeFixnum(value.ivars.size.toLong())
                    value.ivars.forEach { (name, attribute) -> writeValue(name); writeValue(attribute) }
                }
                is MarshalValue.StructValue -> {
                    stream.write('S'.code)
                    writeValue(value.className)
                    writeFixnum(value.members.size.toLong())
                    value.members.forEach { (name, member) -> writeValue(name); writeValue(member) }
                }
                is MarshalValue.UserDefined -> {
                    stream.write('u'.code)
                    writeValue(value.className)
                    writeFixnum(value.payload.size.toLong())
                    stream.write(value.payload)
                }
                is MarshalValue.ExtendedValue -> {
                    stream.write('e'.code)
                    writeValue(value.module)
                    writeValue(value.value)
                }
                is MarshalValue.ModuleValue -> {
                    stream.write('M'.code)
                    writeValue(value.name)
                    writeValue(value.value)
                }
            }
        }

        private fun writeBody(bytes: ByteArray) { writeFixnum(bytes.size.toLong()); stream.write(bytes) }

        private fun writeFloat(value: Double) {
            stream.write('f'.code)
            val text = when {
                value == Double.POSITIVE_INFINITY -> "inf"
                value == Double.NEGATIVE_INFINITY -> "-inf"
                value.isNaN() -> "nan"
                else -> String.format(java.util.Locale.US, "%.17g", value)
            }
            writeBody(text.toByteArray(StandardCharsets.UTF_8))
        }

        private fun writeLong(value: Long) {
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Entier RGSS hors limites (32 bits)." }
            stream.write('i'.code)
            writeFixnum(value)
        }

        private fun writeFixnum(value: Long) {
            when {
                value == 0L -> stream.write(0)
                value in 1..122 -> stream.write((value + 5).toInt())
                value in -123..-1 -> stream.write((value - 5).toInt() and 0xFF)
                value > 0 -> {
                    val bytes = littleEndian(value)
                    stream.write(bytes.size)
                    bytes.forEach { stream.write(it.toInt() and 0xFF) }
                }
                else -> {
                    val bytes = littleEndianNegative(value)
                    stream.write((-bytes.size) and 0xFF)
                    bytes.forEach { stream.write(it.toInt() and 0xFF) }
                }
            }
        }

        private fun littleEndian(value: Long): ByteArray {
            var remaining = value
            val bytes = mutableListOf<Byte>()
            while (remaining > 0) { bytes += (remaining and 0xFF).toByte(); remaining = remaining shr 8 }
            return bytes.toByteArray()
        }

        private fun littleEndianNegative(value: Long): ByteArray {
            var remaining = value
            val bytes = mutableListOf<Byte>()
            while (true) {
                val current = (remaining and 0xFF).toByte()
                bytes += current
                remaining = remaining shr 8
                val finished = if (remaining == -1L) current.toInt() and 0x80 != 0 else remaining == 0L && current.toInt() and 0x80 == 0
                if (finished || bytes.size >= 8) break
            }
            return bytes.toByteArray()
        }
    }
}
