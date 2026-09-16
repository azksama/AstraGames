package fr.astragames.app.data.saves

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Arbre pickle Ren'Py avec positions absolues, permettant une chirurgie par octets. */
sealed class PickleNode {
    abstract val start: Int
    abstract val end: Int

    data object PNil : PickleNode() {
        override val start = 0; override val end = 0
    }
    data class PBool(val value: Boolean, override val start: Int, override val end: Int) : PickleNode()
    data class PInt(val value: Long, override val start: Int, override val end: Int) : PickleNode()
    data class PFloat(val value: Double, override val start: Int, override val end: Int) : PickleNode()
    data class PString(val text: String, override val start: Int, override val end: Int) : PickleNode()
    data class PBytes(val bytes: ByteArray, override val start: Int, override val end: Int) : PickleNode() {
        override fun equals(other: Any?) = other is PBytes && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    data class PList(val items: MutableList<PickleNode>, override val start: Int, override val end: Int) : PickleNode()
    data class PTuple(val items: List<PickleNode>, override val start: Int, override val end: Int) : PickleNode()
    data class PDict(val entries: LinkedHashMap<PickleNode, PickleNode>, override val start: Int, override val end: Int) : PickleNode()
    data class PSet(val items: MutableList<PickleNode>, val frozen: Boolean, override val start: Int, override val end: Int) : PickleNode()
    /** Objet custom (REDUCE/OBJ/NEWOBJ...) : structure connue mais reecrit telle quelle. */
    data class PObject(val className: String, val args: List<PickleNode>, var state: PickleNode?, override val start: Int, override val end: Int,
        val listItems: MutableList<PickleNode> = mutableListOf(),
        val dictEntries: LinkedHashMap<PickleNode, PickleNode> = linkedMapOf()
    ) : PickleNode()
}

/** Lecture d'un flux pickle (protocoles 0-4 suffisants pour les sauvegardes Ren'Py). */
class PickleParser(private val data: ByteArray) {
    private var pos = 0
    private val stack = mutableListOf<PickleNode>()
    private val marks = mutableListOf<Int>()
    private val memo = mutableMapOf<Int, PickleNode>()
    private val meta = java.util.IdentityHashMap<PickleNode, Pair<Int, Int>>()
    val frames = mutableListOf<Pair<Int, Int>>()

    fun parse(): PickleNode {
        while (pos < data.size) {
            val op = data[pos].toInt() and 0xFF
            pos++
            if (op == OP_STOP) {
                require(stack.size == 1 && marks.isEmpty()) { "Pile pickle invalide." }
                return stack.single()
            }
            execute(op)
        }
        error("Sauvegarde pickle tronquee (STOP absent).")
    }

    /** Plages d'octets pour un noeud, y compris les operandes consommes sur la pile. */
    fun range(node: PickleNode): IntRange? = meta[node]?.let { it.first until it.second }

    private fun push(node: PickleNode, start: Int) {
        stack += node
        meta[node] = start to pos
    }

    private fun execute(op: Int) {
        val start = pos - 1
        when (op) {
            OP_PROTO -> require(u8() <= 5) { "Protocole pickle non pris en charge." }
            OP_FRAME -> {
                val size = java.nio.ByteBuffer.wrap(bytes(8)).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
                require(size in 0..(data.size - pos).toLong()) { "Frame pickle tronquee." }
                frames += start to (pos + size.toInt())
            }
            OP_NONE -> push(PickleNode.PNil, start)
            OP_NEWTRUE -> push(PickleNode.PBool(true, start, pos), start)
            OP_NEWFALSE -> push(PickleNode.PBool(false, start, pos), start)
            OP_INT -> {
                val raw = readLine().trim()
                if (raw == "00" || raw == "01") push(PickleNode.PBool(raw == "01", start, pos), start)
                else push(PickleNode.PInt(raw.toLong(), start, pos), start)
            }
            OP_LONG -> push(PickleNode.PInt(readLine().trim().removeSuffix("L").toLong(), start, pos), start)
            OP_BININT -> push(PickleNode.PInt(int32().toLong(), start, pos), start)
            OP_BININT1 -> push(PickleNode.PInt(u8().toLong(), start, pos), start)
            OP_BININT2 -> push(PickleNode.PInt(u16().toLong(), start, pos), start)
            OP_LONG1 -> {
                val size = u8()
                require(size in 0..8 && pos + size <= data.size) { "Entier pickle hors limites." }
                var value = 0L
                repeat(size) { value = value or ((data[pos + it].toLong() and 0xFF) shl (8 * it)) }
                if (size > 0 && size < 8 && data[pos + size - 1].toInt() and 0x80 != 0) {
                    value = value or (-1L shl (8 * size))
                }
                pos += size
                push(PickleNode.PInt(value, start, pos), start)
            }
            OP_LONG4 -> {
                val size = int32()
                require(size in 0..8) { "LONG4 hors limites" }
                var value = 0L
                repeat(size) { value = value or ((data[pos + it].toLong() and 0xFF) shl (8 * it)) }
                if (size > 0 && size < 8 && data[pos + size - 1].toInt() and 0x80 != 0) {
                    value = value or (-1L shl (8 * size))
                }
                pos += size
                push(PickleNode.PInt(value, start, pos), start)
            }
            OP_FLOAT -> push(PickleNode.PFloat(readLine().trim().toDouble(), start, pos), start)
            OP_BINFLOAT -> {
                val bits = java.nio.ByteBuffer.wrap(data, pos, 8).long
                pos += 8
                push(PickleNode.PFloat(java.lang.Double.longBitsToDouble(bits), start, pos), start)
            }
            OP_SHORT_BINUNICODE -> push(PickleNode.PString(utf8(u8()), start, pos), start)
            OP_BINUNICODE -> push(PickleNode.PString(utf8(int32()), start, pos), start)
            OP_SHORT_BINSTRING -> push(PickleNode.PBytes(bytes(u8()), start, pos), start)
            OP_BINSTRING -> push(PickleNode.PBytes(bytes(int32()), start, pos), start)
            OP_STRING -> push(PickleNode.PBytes(decodePythonLiteral(readLine()), start, pos), start)
            OP_BINBYTES -> push(PickleNode.PBytes(bytes(int32()), start, pos), start)
            OP_SHORT_BINBYTES -> push(PickleNode.PBytes(bytes(u8()), start, pos), start)
            OP_EMPTY_LIST -> push(PickleNode.PList(mutableListOf(), start, pos), start)
            OP_EMPTY_TUPLE -> push(PickleNode.PTuple(emptyList(), start, pos), start)
            OP_EMPTY_DICT -> push(PickleNode.PDict(LinkedHashMap(), start, pos), start)
            OP_EMPTY_SET -> push(PickleNode.PSet(mutableListOf(), frozen = false, start, pos), start)
            OP_FROZENSET -> {
                val mark = marks.removeAt(marks.lastIndex)
                val items = stack.subList(mark, stack.size).toMutableList()
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
                push(PickleNode.PSet(items, frozen = true, start, pos), start)
            }
            OP_TUPLE1 -> {
                val item = stack.removeAt(stack.lastIndex)
                push(PickleNode.PTuple(listOf(item), meta[item]?.first ?: start, pos), start)
            }
            OP_TUPLE2, OP_TUPLE3 -> {
                val count = if (op == OP_TUPLE2) 2 else 3
                val items = (0 until count).map { stack.removeAt(stack.lastIndex) }.reversed()
                val first = items.first()
                push(PickleNode.PTuple(items.toList(), meta[first]?.first ?: start, pos), start)
            }
            OP_TUPLE -> {
                val mark = marks.removeAt(marks.lastIndex)
                val items = stack.subList(mark, stack.size).toList()
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
                push(PickleNode.PTuple(items, startOf(items), pos), start)
            }
            OP_LIST -> {
                val mark = marks.removeAt(marks.lastIndex)
                val items = stack.subList(mark, stack.size).toList()
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
                push(PickleNode.PList(items.toMutableList(), startOf(items), pos), start)
            }
            OP_DICT -> {
                val mark = marks.removeAt(marks.lastIndex)
                val dict = LinkedHashMap<PickleNode, PickleNode>()
                val items = stack.subList(mark, stack.size).toList()
                for (index in items.indices step 2) dict[items[index]] = items[index + 1]
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
                push(PickleNode.PDict(dict, startOf(items), pos), start)
            }
            OP_SET -> {
                val mark = marks.removeAt(marks.lastIndex)
                val items = stack.subList(mark, stack.size).toList()
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
                push(PickleNode.PSet(items.toMutableList(), frozen = false, startOf(items), pos), start)
            }
            OP_APPEND -> {
                val item = stack.removeAt(stack.lastIndex)
                listItems(stack.last()).add(item)
                extend(stack.last(), start)
            }
            OP_APPENDS -> {
                val mark = marks.removeAt(marks.lastIndex)
                val items = stack.subList(mark, stack.size).toList()
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
                listItems(stack.last()).addAll(items)
                extend(stack.last(), start)
            }
            OP_ADDITEMS -> {
                val mark = marks.removeAt(marks.lastIndex)
                val items = stack.subList(mark, stack.size).toList()
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
                (stack.last() as? PickleNode.PSet)?.items?.addAll(items)
                extend(stack.last(), start)
            }
            OP_SETITEM -> {
                val value = stack.removeAt(stack.lastIndex)
                val key = stack.removeAt(stack.lastIndex)
                dictEntries(stack.last())[key] = value
                extend(stack.last(), start)
            }
            OP_SETITEMS -> {
                val mark = marks.removeAt(marks.lastIndex)
                val items = stack.subList(mark, stack.size).toList()
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
                val dict = dictEntries(stack.last())
                for (index in items.indices step 2) dict[items[index]] = items[index + 1]
                extend(stack.last(), start)
            }
            OP_GLOBAL -> {
                val module = readLine(); val name = readLine()
                push(PickleNode.PObject(module + "." + name, emptyList(), null, start, pos), start)
            }
            OP_STACK_GLOBAL -> {
                val name = stack.removeAt(stack.lastIndex).text()
                val module = stack.removeAt(stack.lastIndex).text()
                push(PickleNode.PObject(module + "." + name, emptyList(), null, start, pos), start)
            }
            OP_REDUCE -> {
                val args = stack.removeAt(stack.lastIndex)
                val callable = stack.removeAt(stack.lastIndex)
                val className = callable.text()
                val argList = (args as? PickleNode.PTuple)?.items ?: listOf(args)
                val begin = meta[callable]?.first ?: start
                push(PickleNode.PObject(className, argList, null, begin, pos), start)
            }
            OP_OBJ -> {
                val mark = marks.removeAt(marks.lastIndex)
                val items = stack.subList(mark, stack.size).toList()
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
                val className = items.firstOrNull()?.text() ?: "?"
                push(PickleNode.PObject(className, items.drop(1), null, startOf(items), pos), start)
            }
            OP_NEWOBJ -> {
                val args = stack.removeAt(stack.lastIndex)
                val className = stack.removeAt(stack.lastIndex).text()
                val argList = (args as? PickleNode.PTuple)?.items ?: listOf(args)
                push(PickleNode.PObject(className, argList, null, start, pos), start)
            }
            OP_BUILD -> {
                val state = stack.removeAt(stack.lastIndex)
                val target = stack.last()
                if (target is PickleNode.PObject) {
                    target.state = state
                    extend(target, start)
                } else {
                    extend(target, start)
                }
            }
            OP_MARK -> marks += stack.size
            OP_BINPUT -> { memo[u8()] = stack.last() }
            OP_LONG_BINPUT -> { memo[int32()] = stack.last() }
            OP_PUT -> { memo[readLine().trim().toInt()] = stack.last() }
            OP_BINGET -> { stack += memo[u8()] ?: error("Reference pickle absente.") }
            OP_LONG_BINGET -> { stack += memo[int32()] ?: error("Reference pickle absente.") }
            OP_GET -> { stack += memo[readLine().trim().toInt()] ?: error("Reference pickle absente.") }
            OP_MEMOIZE -> { memo[memo.size] = stack.last() }
            OP_PERSID, OP_BINPERSID -> error("References persistantes pickle non prises en charge.")
            OP_POP -> stack.removeAt(stack.lastIndex)
            OP_POP_MARK -> {
                val mark = marks.removeAt(marks.lastIndex)
                repeat(stack.size - mark) { stack.removeAt(stack.lastIndex) }
            }
            OP_DUP -> stack += stack.last()
            else -> error("Opcode pickle inconnu : " + op)
        }
    }

    private fun listItems(node: PickleNode): MutableList<PickleNode> = when (node) {
        is PickleNode.PList -> node.items
        is PickleNode.PObject -> node.listItems
        else -> error("Liste pickle attendue.")
    }

    private fun dictEntries(node: PickleNode): MutableMap<PickleNode, PickleNode> = when (node) {
        is PickleNode.PDict -> node.entries
        is PickleNode.PObject -> node.dictEntries
        else -> error("Dictionnaire pickle attendu.")
    }

    private fun PickleNode.text(): String = when (this) {
        is PickleNode.PString -> text
        is PickleNode.PBytes -> String(bytes, StandardCharsets.ISO_8859_1)
        is PickleNode.PObject -> className
        else -> "?"
    }

    private fun startOf(items: List<PickleNode>): Int = items.firstOrNull()?.let { meta[it]?.first } ?: 0

    private fun extend(node: PickleNode, start: Int) {
        val current = meta[node] ?: return
        meta[node] = minOf(current.first, start) to pos
    }

    private fun u8(): Int = data[pos++].toInt() and 0xFF

    private fun u16(): Int { val value = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return value }

    private fun int32(): Int {
        val value = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or ((data[pos + 3].toInt() shl 24))
        pos += 4
        return value
    }

    private fun utf8(size: Int): String {
        require(size in 0..MAX_SAVE_BYTES && size <= data.size - pos) { "Chaine pickle trop volumineuse" }
        val value = String(data, pos, size, StandardCharsets.UTF_8)
        pos += size
        return value
    }

    private fun bytes(size: Int): ByteArray {
        require(size in 0..MAX_SAVE_BYTES && size <= data.size - pos) { "Bloc pickle trop volumineux" }
        val value = data.copyOfRange(pos, pos + size)
        pos += size
        return value
    }

    private fun readLine(): String {
        val end = run { var i = pos; while (i < data.size && data[i] != '\n'.code.toByte()) i++; if (i < data.size) i else -1 }.let { if (it < 0) data.size else it }
        val value = String(data, pos, end - pos, StandardCharsets.ISO_8859_1)
        pos = end + 1
        return value
    }

    private fun decodePythonLiteral(raw: String): ByteArray =
        raw.trim().removeSurrounding("'").toByteArray(StandardCharsets.ISO_8859_1)

    companion object {
        const val OP_MARK = 40; const val OP_STOP = 46; const val OP_POP = 48; const val OP_POP_MARK = 49
        const val OP_DUP = 50; const val OP_FLOAT = 70; const val OP_INT = 73; const val OP_BININT = 74
        const val OP_BININT1 = 75; const val OP_LONG = 76; const val OP_BININT2 = 77; const val OP_NONE = 78
        const val OP_PERSID = 80; const val OP_BINPERSID = 81; const val OP_REDUCE = 82; const val OP_STRING = 83
        const val OP_BINSTRING = 84; const val OP_SHORT_BINSTRING = 85; const val OP_TUPLE = 116
        const val OP_TUPLE1 = 133; const val OP_TUPLE2 = 134; const val OP_TUPLE3 = 135; const val OP_NEWTRUE = 136
        const val OP_NEWFALSE = 137; const val OP_LONG1 = 138; const val OP_LONG4 = 139; const val OP_EMPTY_LIST = 93
        const val OP_APPEND = 97; const val OP_BUILD = 98; const val OP_GLOBAL = 99; const val OP_DICT = 100
        const val OP_EMPTY_DICT = 125; const val OP_APPENDS = 101; const val OP_GET = 103; const val OP_BINGET = 104
        const val OP_LONG_BINGET = 106; const val OP_LIST = 108; const val OP_SETITEM = 115; const val OP_SET = 123
        const val OP_FROZENSET = 145; const val OP_EMPTY_SET = 143; const val OP_ADDITEMS = 144
        const val OP_BINPUT = 113; const val OP_LONG_BINPUT = 114; const val OP_PUT = 112; const val OP_MEMOIZE = 148
        const val OP_BINFLOAT = 71; const val OP_SHORT_BINBYTES = 67; const val OP_BINBYTES = 66
        const val OP_SHORT_BINUNICODE = 140; const val OP_BINUNICODE = 88; const val OP_PROTO = 128
        const val OP_FRAME = 149; const val OP_EMPTY_TUPLE = 41; const val OP_SETITEMS = 117; const val OP_OBJ = 111
        const val OP_NEWOBJ = 129; const val OP_STACK_GLOBAL = 147
    }
}

/** Chirurgie des valeurs feuilles d'un pickle sans reencoder le reste du graphe. */
object PickleSplicer {
    fun spliceInt(data: ByteArray, range: IntRange, value: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        buffer.write(OP_LONG1)
        val bytes = if (value == 0L) ByteArray(0) else {
            val out = mutableListOf<Byte>()
            var remaining = value
            val negative = value < 0
            while (true) {
                out += (remaining and 0xFF).toByte()
                remaining = remaining shr 8
                val finished = if (negative) remaining == -1L && out.last().toInt() and 0x80 != 0 else remaining == 0L && out.last().toInt() and 0x80 == 0
                if (finished || out.size >= 8) break
            }
            out.toByteArray()
        }
        buffer.write(bytes.size)
        buffer.write(bytes)
        return replace(data, range, buffer.toByteArray())
    }

    fun spliceFloat(data: ByteArray, range: IntRange, value: Double): ByteArray {
        val buffer = ByteArrayOutputStream()
        buffer.write(OP_BINFLOAT)
        buffer.write(java.nio.ByteBuffer.allocate(8).putDouble(value).array())
        return replace(data, range, buffer.toByteArray())
    }

    fun spliceBool(data: ByteArray, range: IntRange, value: Boolean): ByteArray =
        replace(data, range, byteArrayOf(if (value) OP_NEWTRUE.toByte() else OP_NEWFALSE.toByte()))

    fun spliceString(data: ByteArray, range: IntRange, value: String): ByteArray {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteArrayOutputStream()
        buffer.write(OP_BINUNICODE)
        buffer.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(encoded.size).array())
        buffer.write(encoded)
        return replace(data, range, buffer.toByteArray())
    }

    private fun replace(data: ByteArray, range: IntRange, patch: ByteArray): ByteArray {
        require(!range.isEmpty() && range.first >= 0 && range.last < data.size) { "Plage pickle invalide" }
        val out = ByteArray(data.size - range.last + range.first - 1 + patch.size)
        data.copyInto(out, 0, 0, range.first)
        patch.copyInto(out, range.first)
        data.copyInto(out, range.first + patch.size, range.last + 1)
        // FRAME lengths count payload bytes, so edits must update their containing frame.
        if (data.firstOrNull() == OP_PROTO_BYTE) {
            val parser = PickleParser(data)
            parser.parse()
            parser.frames.filter { (start, end) -> range.first >= start + 9 && range.last < end }.forEach { (start, end) ->
                val length = end - start - 9L + patch.size - range.count()
                java.nio.ByteBuffer.wrap(out, start + 1, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(length)
            }
        }
        return out
    }

    private val OP_PROTO_BYTE = 128.toByte()
    const val OP_LONG1 = 138
    const val OP_BINFLOAT = 71
    const val OP_NEWTRUE = 136
    const val OP_NEWFALSE = 137
    const val OP_SHORT_BINUNICODE = 140
    const val OP_BINUNICODE = 88
}
