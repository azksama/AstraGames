package fr.astragames.wolf

import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.zip.CRC32

/** Positional reads supplied by the host. Opening an archive never extracts its files. */
interface WolfArchiveSource : AutoCloseable {
    val size: Long
    /** Return exactly length bytes in a new array; the decoder may modify that array. */
    fun read(offset: Long, length: Int): ByteArray
    override fun close() {}
}

data class WolfArchiveLimits(
    val maxIndexBytes: Int = 64 * 1024 * 1024,
    val maxStoredBytes: Int = 128 * 1024 * 1024,
    val maxAssetBytes: Int = 128 * 1024 * 1024,
    val maxEntries: Int = 500_000,
    val maxDepth: Int = 128,
    val maxPathBytes: Int = 4096,
    val maxIndexAllocationBytes: Long = 256L * 1024 * 1024,
) {
    init {
        require(maxIndexBytes > 0 && maxStoredBytes > 0 && maxAssetBytes > 0)
        require(maxEntries > 0 && maxDepth > 0 && maxPathBytes > 0)
        require(maxIndexAllocationBytes > 0)
    }
}

/**
 * Read-only DX5/DX6/DX8 asset filesystem, fixed profiles and established Pro carriers.
 * A protected Pro container requires its matching carrier; unknown keys are not inferred.
 *
 * Wire evidence: UberWolf f4191375d289171b1f29dbc5ca320133b2b4b96a,
 * 3rdParty/DXLib/{DXArchive,DXArchiveVer5,DXArchiveVer6,Huffman}.{h,cpp}.
 * Wolf XOR/AES adaptations and attribution: see THIRD_PARTY_NOTICES.md.
 */
class WolfArchive private constructor(
    private val source: WolfArchiveSource,
    private val header: Header,
    private val outer: WolfArchiveTransform?,
    private val password: ByteArray,
    private val legacyKey: ByteArray?,
    private val limits: WolfArchiveLimits,
) : WolfAssetSource, AutoCloseable {
    val version: Int get() = header.version
    val cryptVersion: Int get() = header.cryptVersion

    private data class Name(val original: String, val upperBytes: ByteArray)
    private data class Entry(
        val path: String,
        val name: String,
        val dataOffset: Long,
        val size: Int,
        val lzSize: Int?,
        val huffmanSize: Int?,
        val key: ByteArray?,
    )
    private data class PendingDirectory(
        val offset: Int,
        val parentOffset: Int,
        val ownFileOffset: Int,
        val path: String,
        val upperAncestors: List<ByteArray>,
        val depth: Int,
    )
    private val files = LinkedHashMap<String, Entry>()
    private val directories = LinkedHashMap<String, MutableList<String>>()
    @Volatile private var closed = false

    override fun exists(path: String): Boolean {
        checkOpen()
        val key = pathKey(path)
        return key in files || key in directories
    }

    override fun list(path: String): List<String> {
        checkOpen()
        return directories[pathKey(path)]?.toList() ?: emptyList()
    }

    /** Only this asset's stored bytes and expanded bytes are allocated. */
    override fun read(path: String): ByteArray {
        checkOpen()
        val entry = files[pathKey(path)] ?: throw FileNotFoundException("Wolf archive asset: $path")
        if (entry.size == 0) return byteArrayOf()
        val encodedSize = entry.lzSize ?: entry.size
        val huffmanSize = entry.huffmanSize
        val encoded = if (huffmanSize == null) {
            readAssetBytes(entry, 0, encodedSize)
        } else {
            val edge = header.huffmanKB * 1024
            val partial = header.huffmanKB != 0xff && encodedSize > edge * 2
            val decodedSize = if (partial) edge * 2 else encodedSize
            val compressed = readAssetBytes(entry, 0, huffmanSize)
            val huffman = WolfArchiveCodecs.huffman(compressed, decodedSize, limits.maxStoredBytes)
            if (!partial) huffman else {
                val middle = readAssetBytes(entry, huffmanSize, encodedSize - edge * 2)
                ByteArray(encodedSize).also {
                    huffman.copyInto(it, 0, 0, edge)
                    middle.copyInto(it, edge)
                    huffman.copyInto(it, encodedSize - edge, edge)
                }
            }
        }
        return if (entry.lzSize == null) encoded
        else WolfArchiveCodecs.lz(encoded, entry.size, limits.maxAssetBytes)
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            source.close()
            files.clear()
            directories.clear()
        }
    }

    private fun checkOpen() = check(!closed) { "Wolf archive is closed" }

    private fun readDecoded(offset: Long, length: Int): ByteArray {
        range(offset, length.toLong(), source.size, "archive read")
        val data = source.read(offset, length)
        if (data.size != length) fail("short positional read at 0x${offset.toString(16)}: ${data.size}/$length")
        outer?.decodeOuter(data, offset)
        return data
    }

    private fun readAssetBytes(entry: Entry, streamOffset: Int, length: Int): ByteArray {
        val data = readDecoded(header.dataStart + entry.dataOffset + streamOffset, length)
        if (!header.noKey) {
            val position = entry.size.toLong() + streamOffset
            if (outer != null) outer.decodeInner(data, position)
            else xor(data, entry.key ?: legacyKey ?: fail("missing file key"), position)
        }
        return data
    }

    private fun loadIndex() {
        var allocated = 0L
        fun charge(bytes: Long) {
            if (bytes < 0 || bytes > limits.maxIndexAllocationBytes - allocated) fail("archive index allocation exceeds ${limits.maxIndexAllocationBytes} bytes")
            allocated += bytes
        }
        val indexSize = checkedSize(header.indexSize, limits.maxIndexBytes, "expanded archive index")
        val storedSize = if (header.rawIndex) indexSize
        else checkedSize(source.size - header.nameStart, limits.maxStoredBytes, "stored archive index")
        charge(storedSize.toLong())
        val stored = readDecoded(header.nameStart, storedSize)
        if (!header.noKey) {
            if (outer != null) outer.decodeInner(stored, 0)
            else xor(stored, legacyKey ?: key7(password), 0)
        }
        val index = if (header.rawIndex) stored else {
            val lz = WolfArchiveCodecs.huffman(stored, null, limits.maxStoredBytes) { charge(it.toLong()) }
            WolfArchiveCodecs.lz(lz, indexSize, limits.maxIndexBytes) { charge(it.toLong()) }
        }
        val fileBase = header.fileTable.toInt()
        val dirBase = header.directoryTable.toInt()
        val recordSize = if (version == 5) 44 else if (version == 6) 64 else 72
        val dirSize = if (version == 5) 16 else 32
        val fileBytes = dirBase - fileBase
        if (fileBytes % recordSize != 0 || (index.size - dirBase) % dirSize != 0)
            fail("archive record tables are not aligned")
        val charset = when (header.codePage) {
            932L -> Charset.forName("windows-31j")
            65001L -> Charsets.UTF_8
            32765L, 20127L -> Charsets.US_ASCII
            else -> fail("unsupported archive filename code page ${header.codePage}")
        }
        val names = HashMap<Int, Name>()
        fun getName(offset: Int): Name = names.getOrPut(offset) {
            range(offset.toLong(), 4, fileBase.toLong(), "filename header")
            val padded = u16(index, offset) * 4
            if (padded <= 0 || padded > limits.maxPathBytes) fail("invalid filename padded length $padded")
            range(offset.toLong() + 4, padded.toLong(), fileBase.toLong(), "uppercase filename")
            val upperStart = offset + 4
            var upperEnd = upperStart
            while (upperEnd < upperStart + padded && index[upperEnd] != 0.toByte()) upperEnd++
            if (upperEnd == upperStart + padded) fail("unterminated uppercase filename")
            val originalStart = upperStart + padded
            var originalEnd = originalStart
            while (originalEnd < fileBase && originalEnd - originalStart <= limits.maxPathBytes && index[originalEnd] != 0.toByte()) originalEnd++
            if (originalEnd >= fileBase || originalEnd - originalStart > limits.maxPathBytes) fail("unterminated original filename")
            val original = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(index, originalStart, originalEnd - originalStart)).toString()
            } catch (e: CharacterCodingException) { throw WolfFormatException("Wolf archive: invalid filename encoding", e) }
            component(original)
            charge(96L + original.length * 2L + upperEnd - upperStart)
            Name(original, index.copyOfRange(upperStart, upperEnd))
        }
        fun scalar(position: Int): Long = if (version == 5) u32(index, position) else i64(index, position)
        fun offset(value: Long, limit: Int, what: String, alignment: Int = 1): Int {
            if (value < 0 || value > limit || value % alignment != 0L) fail("invalid $what: $value")
            return value.toInt()
        }
        val queue = ArrayDeque<PendingDirectory>()
        queue.add(PendingDirectory(0, -1, -1, "", emptyList(), 0))
        val visitedDirectories = HashSet<Int>()
        val visitedFiles = HashSet<Int>()
        directories[""] = mutableListOf()
        var totalEntries = 0
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            if (!visitedDirectories.add(d.offset)) fail("directory cycle or shared directory at ${d.offset}")
            range(d.offset.toLong(), dirSize.toLong(), (index.size - dirBase).toLong(), "directory record")
            val base = dirBase + d.offset
            val word = if (version == 5) 4 else 8
            val self = scalar(base)
            val parent = scalar(base + word)
            val rootSentinel = if (version == 5) 0xffffffffL else -1L
            if (d.depth == 0) {
                if (parent != rootSentinel) fail("root directory has a parent")
            } else if (self != d.ownFileOffset.toLong() || parent != d.parentOffset.toLong()) {
                fail("inconsistent directory ancestry for ${d.path}")
            }
            val count = checkedSize(scalar(base + word * 2), limits.maxEntries, "directory entry count")
            if (count > limits.maxEntries - totalEntries) fail("archive entry count exceeds ${limits.maxEntries}")
            totalEntries += count
            val first = offset(scalar(base + word * 3), fileBytes, "first child record", recordSize)
            range(first.toLong(), count.toLong() * recordSize, fileBytes.toLong(), "child records")
            val children = directories.getValue(pathKey(d.path))
            for (i in 0 until count) {
                val fileOffset = first + i * recordSize
                if (!visitedFiles.add(fileOffset)) fail("shared archive child record at $fileOffset")
                val f = fileBase + fileOffset
                val name = getName(offset(scalar(f), fileBase, "filename offset"))
                val attributes = scalar(f + word)
                val dataWord = f + word * 2 + 24
                val dataOffset = scalar(dataWord)
                val path = if (d.path.isEmpty()) name.original else "${d.path}/${name.original}"
                charge(256L + path.length * 4L + d.upperAncestors.size * 8L)
                if (path.toByteArray(Charsets.UTF_8).size > limits.maxPathBytes) fail("archive path exceeds ${limits.maxPathBytes} bytes")
                val key = pathKey(path)
                if (key in files || key in directories) fail("duplicate archive path: $path")
                children += name.original
                if (attributes and 0x10L != 0L) {
                    if (d.depth >= limits.maxDepth) fail("archive directory depth exceeds ${limits.maxDepth}")
                    val next = offset(dataOffset, index.size - dirBase, "child directory", dirSize)
                    directories[key] = mutableListOf()
                    queue.add(PendingDirectory(next, d.offset, fileOffset, path, listOf(name.upperBytes) + d.upperAncestors, d.depth + 1))
                } else {
                    if (dataOffset < 0) fail("invalid asset offset: $path")
                    val size = checkedSize(scalar(dataWord + word), limits.maxAssetBytes, "asset $path")
                    val sentinel = if (version == 5) 0xffffffffL else -1L
                    val lzRaw = scalar(dataWord + word * 2)
                    val huffmanRaw = if (version == 8) scalar(dataWord + word * 3) else sentinel
                    val lz = if (lzRaw == sentinel) null else checkedSize(lzRaw, limits.maxStoredBytes, "LZ asset $path")
                    val huffman = if (huffmanRaw == sentinel) null else checkedSize(huffmanRaw, limits.maxStoredBytes, "Huffman asset $path")
                    val encodedSize = lz ?: size
                    val edge = header.huffmanKB * 1024
                    val partial = huffman != null && header.huffmanKB != 0xff && encodedSize > edge * 2
                    val storedLength = if (huffman == null) encodedSize.toLong()
                        else huffman.toLong() + if (partial) encodedSize - edge * 2 else 0
                    checkedSize(storedLength, limits.maxStoredBytes, "stored asset $path")
                    if (header.dataStart > header.nameStart || dataOffset > header.nameStart - header.dataStart)
                        fail("asset outside data region: $path")
                    range(dataOffset, storedLength, header.nameStart - header.dataStart, "asset $path")
                    val innerKey = if (version != 8 || header.noKey || outer != null) null else {
                        val keyParts = listOf(password, name.upperBytes) + d.upperAncestors
                        val length = keyParts.sumOf { it.size }
                        if (length >= 2040) fail("per-file key path exceeds DX8 limit")
                        key7(ByteArray(length).also { output ->
                            var cursor = 0
                            for (part in keyParts) { part.copyInto(output, cursor); cursor += part.size }
                        })
                    }
                    files[key] = Entry(path, name.original, dataOffset, size, lz, huffman, innerKey)
                }
            }
        }
    }

    private data class Header(
        val version: Int,
        val indexSize: Long,
        val dataStart: Long,
        val nameStart: Long,
        val fileTable: Long,
        val directoryTable: Long,
        val codePage: Long,
        val flags: Long,
        val huffmanKB: Int,
    ) {
        val noKey: Boolean get() = version == 8 && flags and 1L != 0L
        val rawIndex: Boolean get() = version != 8 || flags and 2L != 0L
        val cryptVersion: Int get() = if (version == 8) (flags ushr 16).toInt() else 0
    }

    companion object {
        /** Source ownership transfers on entry, including a failed open. */
        fun open(source: WolfArchiveSource, key: ByteArray? = null, limits: WolfArchiveLimits = WolfArchiveLimits(),
            carrierKey: WolfArchiveKey? = null): WolfArchive {
            try {
                if (key != null && carrierKey != null) fail("both raw password and carrier key were supplied")
                if (source.size < 28) fail("truncated archive header")
                val raw = source.read(0, minOf(64L, source.size).toInt())
                if (raw.size != minOf(64L, source.size).toInt()) fail("short archive header read")
                val clear = raw.size >= 4 && raw[0] == 0x44.toByte() && raw[1] == 0x58.toByte()
                val candidates: List<Pair<ByteArray, ByteArray?>> = if (clear) listOf(raw.copyOf() to null) else {
                    val passwords = if (key != null) listOf(key) else LEGACY_PASSWORDS
                    passwords.map { password -> legacyKey(password).let { k -> raw.copyOf().also { xor(it, k, 0) } to k } }
                }
                var legacyFailure: WolfFormatException? = null
                for ((candidate, oldKey) in candidates) {
                    try {
                        if (u16(candidate, 0) != 0x5844) fail("unrecognized DX archive signature / key")
                        val version = u16(candidate, 2)
                        if (version !in setOf(5, 6, 8)) fail("unsupported DX archive version $version")
                        if (version != 8 && oldKey == null) fail("DX$version requires an established legacy key")
                        val headerSize = when (version) { 5 -> 28; 6 -> 48; else -> 64 }
                        if (candidate.size < headerSize) fail("truncated DX$version header")
                        val cryptVersion = if (version == 8) (u32(candidate, 44) ushr 16).toInt() else 0
                        if (cryptVersion !in setOf(0, 100, 200, 300, 314, 331, 350, 1000, 1010, 1020))
                            fail("unsupported Wolf archive cryptVersion $cryptVersion; no established key/decoder")
                        if (carrierKey != null && carrierKey.cryptVersion != cryptVersion)
                            fail("carrier profile ${carrierKey.cryptVersion} does not match container profile $cryptVersion")
                        val noKeyFlag = version == 8 && u32(candidate, 44) and 1L != 0L
                        val needsCarrier = cryptVersion in setOf(1010, 1020) ||
                            (!noKeyFlag && cryptVersion in setOf(200, 1000))
                        if (needsCarrier && carrierKey == null)
                            fail("Wolf Pro archive profile $cryptVersion requires its established external carrier")
                        val password = if (version != 8 || cryptVersion in setOf(100, 200) ||
                            (cryptVersion == 1000 && carrierKey == null && noKeyFlag)) byteArrayOf()
                            else carrierKey?.password ?: cPassword(key ?: FIXED_PASSWORDS.getValue(cryptVersion))
                        val crypto: WolfArchiveTransform? = when (cryptVersion) {
                            331, 350, 1010, 1020 -> WolfArchiveCrypto(candidate.copyOfRange(49, 64), password, cryptVersion, source.size,
                                if (cryptVersion in setOf(1010, 1020)) carrierKey!!.proKey else null)
                            100 -> WolfArchiveChaCha(hex("c982f8b42c939e830ebcbc92688d59a14a9e7fb0acaf1d8f8eb83b9ee889d9adffbc2dab9d8b0fb4bb9a6985"), 32)
                            200 -> carrierKey?.let { WolfArchiveChaCha(it.material) }
                            else -> null
                        }
                        crypto?.decodeAddresses(candidate)
                        val h = if (version == 5) Header(version, u32(candidate, 4), u32(candidate, 8), u32(candidate, 12),
                            u32(candidate, 16), u32(candidate, 20), u32(candidate, 24), 0, 0)
                        else Header(version, u32(candidate, 4), i64(candidate, 8), i64(candidate, 16), i64(candidate, 24),
                            i64(candidate, 32), if (version == 6) i64(candidate, 40) else u32(candidate, 40),
                            if (version == 8) u32(candidate, 44) else 0, if (version == 8) byte(candidate, 48) else 0)
                        checkedSize(h.indexSize, limits.maxIndexBytes, "expanded archive index")
                        if (h.dataStart < headerSize || h.nameStart < h.dataStart || h.nameStart > source.size)
                            fail("invalid archive data/index addresses")
                        if (h.fileTable < 0 || h.directoryTable < h.fileTable || h.directoryTable >= h.indexSize)
                            fail("invalid archive table addresses")
                        if (h.flags and 0xfffcL != 0L) fail("unknown DX8 flags 0x${h.flags.toString(16)}")
                        crypto?.configureIndex(h.nameStart)
                        val archive = WolfArchive(source, h, crypto, password, oldKey, limits)
                        archive.loadIndex()
                        return archive
                    } catch (e: WolfFormatException) {
                        if (clear) throw e
                        legacyFailure = e
                    }
                }
                throw WolfFormatException("Wolf archive: no established legacy key matched DX5/DX6", legacyFailure)
            } catch (e: Throwable) {
                try { source.close() } catch (close: Throwable) { e.addSuppressed(close) }
                throw e
            }
        }

        private val LEGACY_PASSWORDS = listOf(
            hex("0f53e13e04371217600f53e1"), hex("4cd92ab7289bac073e77ec4c"), hex("38504028724f21703b733538"),
        )
        private val FIXED_PASSWORDS = mapOf(
            0 to "WLFRPrO!p(;s5((8P@((UFWlu$#5(=".toByteArray(Charsets.US_ASCII),
            300 to hex("0f53e13e8eb54191521655ae34c98f79592f596b95199b1b359a2fdec97c1296c314b50f53e13e8e"),
            314 to hex("31f90136a3e38d3c7bc37d25ad6328191bf78e6cc4e5e27682ea4fed61dae0445bb6463b06d5ceb67858d07c82"),
            331 to hex("ca084c5d170ddaa1d727c8415438823254b7f9468e136bcad05c9595e2dc0353609b4a3817f36959a4c79a4363e654afdbbb4358"),
            350 to hex("d284ce28ce8882e42a182e4c06b4ea8406b8c6885aa09e7c5640ba3452ccc67c2e141268fe5c769486788e4cbe88669c1ee08e6c"),
        )
    }
}

private fun fail(message: String): Nothing = throw WolfFormatException("Wolf archive: $message")
private fun byte(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xff
private fun u16(data: ByteArray, offset: Int): Int = byte(data, offset) or (byte(data, offset + 1) shl 8)
private fun u32(data: ByteArray, offset: Int): Long = (0..3).fold(0L) { result, i -> result or (byte(data, offset + i).toLong() shl (i * 8)) }
private fun i64(data: ByteArray, offset: Int): Long = (0..7).fold(0L) { result, i -> result or (byte(data, offset + i).toLong() shl (i * 8)) }
private fun checkedSize(size: Long, maximum: Int, what: String): Int {
    if (size < 0 || size > maximum) fail("$what size $size exceeds $maximum bytes/count")
    return size.toInt()
}
private fun range(offset: Long, length: Long, size: Long, what: String) {
    if (offset < 0 || length < 0 || size < 0 || offset > size || length > size - offset) fail("$what outside declared bounds")
}
private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun component(value: String) {
    if (value.isEmpty() || value == "." || value == ".." || value.any { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) fail("unsafe archive filename")
}
private fun pathKey(path: String): String {
    val normalized = path.replace('\\', '/')
    if (normalized.startsWith('/')) fail("absolute archive path")
    val parts = normalized.trimEnd('/').split('/')
    if (normalized.isNotEmpty()) parts.forEach { component(it) }
    return buildString(normalized.length) { for (c in normalized.trimEnd('/')) append(if (c in 'a'..'z') c.uppercaseChar() else c) }
}
private fun cPassword(raw: ByteArray): ByteArray {
    val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
    if (end > 63) fail("DX8 password exceeds 63 bytes")
    return raw.copyOfRange(0, end)
}
private fun xor(data: ByteArray, key: ByteArray, position: Long) {
    if (key.isEmpty()) fail("empty XOR key")
    var j = (position % key.size).toInt()
    for (i in data.indices) { data[i] = (data[i].toInt() xor key[j].toInt()).toByte(); j = (j + 1) % key.size }
}
internal fun key7(password: ByteArray): ByteArray {
    val source = if (password.size < 4) password + "DXBDXARC".toByteArray(Charsets.US_ASCII) else password
    val even = CRC32()
    val odd = CRC32()
    source.forEachIndexed { i, b -> (if (i % 2 == 0) even else odd).update(b.toInt() and 0xff) }
    return ByteArray(7) { i -> (if (i < 4) even.value ushr (i * 8) else odd.value ushr ((i - 4) * 8)).toByte() }
}
internal fun legacyKey(password: ByteArray): ByteArray {
    if (password.isEmpty()) fail("empty legacy password")
    val raw = ByteArray(12) { password[it % password.size] }
    fun swap(v: Int) = (v shl 4 or (v ushr 4)) and 0xff
    fun ror(v: Int, count: Int) = (v ushr count or (v shl (8 - count))) and 0xff
    val p = raw.map { it.toInt() and 0xff }
    return intArrayOf(p[0].inv(), swap(p[1]), p[2] xor 0x8a, swap(p[3]).inv(), p[4].inv(), p[5] xor 0xac,
        p[6].inv(), ror(p[7], 3).inv(), ror(p[8], 5), p[9] xor 0x7f, swap(p[10]) xor 0xd6, p[11] xor 0xcc).map { it.toByte() }.toByteArray()
}

/** Independent bounds-checked implementations of the documented DX archive codecs. */
internal object WolfArchiveCodecs {
    fun lz(input: ByteArray, expected: Int? = null, maximum: Int, beforeAllocation: (Int) -> Unit = {}): ByteArray {
        if (input.size < 9) fail("truncated DX LZ header")
        val outputSize = checkedSize(u32(input, 0), maximum, "DX LZ expansion")
        if (expected != null && outputSize != expected) fail("DX LZ expanded size $outputSize != $expected")
        if (u32(input, 4) != input.size.toLong()) fail("DX LZ compressed size mismatch")
        beforeAllocation(outputSize)
        val output = ByteArray(outputSize)
        val escape = byte(input, 8)
        var source = 9
        var dest = 0
        fun take(): Int = if (source < input.size) byte(input, source++) else fail("truncated DX LZ token")
        while (source < input.size) {
            val literal = take()
            if (literal != escape) {
                if (dest == output.size) fail("DX LZ literal exceeds expansion")
                output[dest++] = literal.toByte()
            } else {
                var code = take()
                if (code == escape) {
                    if (dest == output.size) fail("DX LZ escape exceeds expansion")
                    output[dest++] = escape.toByte()
                } else {
                    if (code > escape) code--
                    var length = code ushr 3
                    if (code and 4 != 0) length = length or (take() shl 5)
                    length += 4
                    val distanceBytes = (code and 3) + 1
                    if (distanceBytes == 4) fail("reserved DX LZ distance mode")
                    var distance = 0
                    for (i in 0 until distanceBytes) distance = distance or (take() shl (i * 8))
                    distance++
                    if (distance > dest || length > output.size - dest) fail("invalid DX LZ backreference")
                    repeat(length) { output[dest] = output[dest - distance]; dest++ }
                }
            }
        }
        if (dest != output.size) fail("DX LZ produced $dest/${output.size} bytes")
        return output
    }

    fun huffman(input: ByteArray, expected: Int? = null, maximum: Int, beforeAllocation: (Int) -> Unit = {}): ByteArray {
        val bits = HeaderBits(input)
        val size = checkedSize(bits.read(bits.read(6).toInt() + 1), maximum, "DX Huffman expansion")
        val pressed = checkedSize(bits.read(bits.read(6).toInt() + 1), input.size, "DX Huffman payload")
        if (expected != null && size != expected) fail("DX Huffman expanded size $size != $expected")
        val weights = LongArray(511)
        for (i in 0 until 256) {
            val width = (bits.read(3).toInt() + 1) * 2
            val minus = bits.read(1) == 1L
            val delta = bits.read(width)
            weights[i] = if (i == 0) delta else weights[i - 1] + if (minus) -delta else delta
            if (weights[i] !in 0..65535) fail("invalid DX Huffman weight")
        }
        val headerSize = (bits.position + 7) / 8
        if (pressed != input.size - headerSize) fail("DX Huffman payload size mismatch")
        val used = BooleanArray(511)
        val left = IntArray(511) { -1 }
        val right = IntArray(511) { -1 }
        for (node in 256..510) {
            var first = -1
            var second = -1
            for (i in 0 until node) if (!used[i]) {
                if (first < 0 || weights[i] < weights[first]) { second = first; first = i }
                else if (second < 0 || weights[i] < weights[second]) second = i
            }
            if (first < 0 || second < 0) fail("invalid DX Huffman tree")
            used[first] = true
            used[second] = true
            left[node] = first
            right[node] = second
            weights[node] = weights[first] + weights[second]
        }
        beforeAllocation(size)
        val output = ByteArray(size)
        var position = 0L
        for (i in output.indices) {
            var node = 510
            while (node >= 256) {
                if (position >= pressed.toLong() * 8) fail("truncated DX Huffman bitstream")
                val bit = byte(input, headerSize + (position / 8).toInt()) ushr (position % 8).toInt() and 1
                position++
                node = if (bit == 0) left[node] else right[node]
            }
            output[i] = node.toByte()
        }
        // The writer always emits at least one byte, also for an empty source.
        if (pressed != maxOf(1L, (position + 7) / 8).toInt()) fail("unused DX Huffman payload bytes")
        return output
    }

    private class HeaderBits(private val input: ByteArray) {
        var position: Int = 0
        fun read(width: Int): Long {
            if (width !in 1..63 || width.toLong() > input.size.toLong() * 8 - position) fail("truncated/oversized DX Huffman header")
            var result = 0L
            repeat(width) {
                result = (result shl 1) or ((byte(input, position / 8) ushr (7 - position % 8)) and 1).toLong()
                position++
            }
            return result
        }
    }
}

/** MIT WolfCrypt/WolfAes port: each keystream can seek without touching other assets. */
internal interface WolfArchiveTransform {
    fun configureIndex(start: Long)
    fun decodeOuter(data: ByteArray, absoluteOffset: Long)
    fun decodeInner(data: ByteArray, streamPosition: Long)
    fun decodeAddresses(header: ByteArray)
}

internal class WolfArchiveCrypto(
    private val password15: ByteArray,
    password: ByteArray,
    private val version: Int,
    private val archiveSize: Long,
    private val proKey: ByteArray? = null,
) : WolfArchiveTransform {
    private val v35 = version == 350 || version == 1020
    private val innerKey = makeKey(password15, password, false)
    private val outerKey = makeKey(password15, password, true)
    private val aes = WolfAesCtr(password15, version, proKey)
    private var indexStart = Long.MAX_VALUE
    private val bodySize: Long

    init {
        if (password15.size != 15 || version !in setOf(331, 350, 1010, 1020)) fail("unsupported Wolf XOR/AES profile")
        if (version in setOf(1010, 1020) && (proKey?.size != 4 || password.isEmpty())) fail("missing Pro password/AES material")
        if (archiveSize < 1088) fail("truncated Wolf XOR/AES archive")
        bodySize = if (!v35) 1024 else {
            var state = byte(password15, 2) * byte(password15, 4) + if (version == 1020)
                byte(proKey!!, 0) * byte(proKey, 1) + byte(password15, 11) else byte(password15, 12)
            if (state == 0) state = 1
            fun next(): Long {
                state = state xor (state shl 11)
                state = state xor (state ushr 19)
                state = state xor (state shl 7)
                return state.toLong() and 0xffffffffL
            }
            next()
            if (archiveSize >= next() % 500 + 800) next()
            if (archiveSize - 64 >= next() % 500 + 800) next() % 500 + 800 else archiveSize - 64
        }
    }

    override fun configureIndex(start: Long) { indexStart = start }

    override fun decodeOuter(data: ByteArray, absoluteOffset: Long) {
        // Both transformations are XOR masks. Their ranges may overlap; preserve both.
        transformRange(data, absoluteOffset, 64, archiveSize - 64) { slice, position -> wolfXor(slice, outerKey, position) }
        transformRange(data, absoluteOffset, 64, 64 + bodySize) { slice, position -> aes.xor(slice, position - 64, 0) }
        transformRange(data, absoluteOffset, indexStart, archiveSize) { slice, position -> aes.xor(slice, position - indexStart, (bodySize + 15) / 16) }
    }

    override fun decodeInner(data: ByteArray, streamPosition: Long) = wolfXor(data, innerKey, streamPosition)

    override fun decodeAddresses(header: ByteArray) {
        val p = password15
        val seed = if (v35) 12 + byte(p, 9) * byte(p, 10) + byte(p, 3)
            else byte(p, 0) + byte(p, 7) * byte(p, 12)
        val rng = MsvcRandom(seed)
        fun halfwords(start: Int, words: Int) {
            repeat(words) { word ->
                for (j in 3 downTo 0) {
                    val offset = start + word * 8 + j * 2
                    val value = u16(header, offset) xor rng.next()
                    header[offset] = value.toByte()
                    header[offset + 1] = (value ushr 8).toByte()
                }
            }
        }
        if (!v35) halfwords(8, 4) else {
            halfwords(8, 2)
            val r0 = rng.next().toLong() shl 17
            val r1 = rng.next().toLong() shl 31
            val value = i64(header, 24) xor (r0 or r1 or rng.next().toLong())
            for (i in 0..7) header[24 + i] = (value ushr (i * 8)).toByte()
            halfwords(32, 1)
        }
    }

    private fun transformRange(data: ByteArray, offset: Long, start: Long, end: Long, transform: (ByteArray, Long) -> Unit) {
        val from = maxOf(offset, start)
        val until = minOf(offset + data.size, end)
        if (from >= until) return
        val first = (from - offset).toInt()
        val last = (until - offset).toInt()
        if (first == 0 && last == data.size) transform(data, from) else {
            val slice = data.copyOfRange(first, last)
            transform(slice, from)
            slice.copyInto(data, first)
        }
    }

    private fun wolfXor(data: ByteArray, key: ByteArray, position: Long) {
        var first = (position and 255).toInt()
        var second = ((position ushr 8) and 255).toInt()
        var third = ((position ushr 16) and 255).toInt()
        val modified = if (v35) ByteArray(512) { (byte(key, it % 256) xor (7 * it)).toByte() } else null
        for (i in data.indices) {
            val mask = if (modified == null) byte(key, first) xor byte(key, second + 256) xor byte(key, third + 512)
                else byte(modified, first) xor byte(modified, second + 256)
            data[i] = (byte(data, i) xor mask).toByte()
            first++
            if (first == 256) { first = 0; second++; if (second == 256) { second = 0; third = (third + 1) and 255 } }
        }
    }

    private fun makeKey(p: ByteArray, password: ByteArray, other: Boolean): ByteArray {
        if (p.size != 15) fail("missing Wolf archive password seed")
        var s3 = 0
        val count = byte(p, if (other) 8 else 11) / if (other) 4 else 3
        repeat(count) { i -> s3 = (i xor rotateByte(s3 xor byte(p, i % 15), if (other) 2 else 3)) and 255 }
        val rng = MsvcRandom(byte(p, 2) * byte(p, 5) + byte(p, 12) + s3)
        val factors = IntArray(3)
        factors[s3 % 3] = rng.next() % 256
        if (!other && v35) factors[1] = rng.next() % 251
        val key = ByteArray(768)
        for (i in 0..255) {
            val rn = rng.next()
            key[i] = (factors[0] xor (rng.next() and 255)).toByte()
            key[i + 256] = (factors[1] xor (rn ushr 8)).toByte()
            key[i + 512] = (factors[2] xor rn).toByte()
        }
        if (!other && proKey != null) for (i in 0..127) {
            val rn = rng.next()
            key[i] = (byte(key, i) xor s3 xor byte(proKey, 2) xor (rn ushr 8)).toByte()
            key[i + 256] = (byte(key, i + 256) xor s3 xor byte(proKey, 0) xor rn).toByte()
        }
        if (!other) return key
        val saltString = if (version == 350) "958".toByteArray(Charsets.US_ASCII) else password
        if (saltString.isEmpty()) fail("missing salt for Wolf XOR/AES archive")
        val salt = ByteArray(128) { (it / saltString.size + byte(saltString, it % saltString.size)).toByte() }
        val modulus = if (v35) 16 else 7
        if (v35) s3 = (s3 + 0x22) and 255
        for (i in 0..2) {
            var t = s3
            for (j in 0..255) {
                val s = byte(salt, j and 127)
                val s2 = byte(salt, (j + i) % 128)
                val k = byte(key, i * 256 + j)
                val sxk = s xor k
                val round = (s2 or (s shl 8)) % modulus
                var result = sxk
                var skip = false
                when (round) {
                    1 -> if (s2 % 11 == 0) result = k
                    2 -> if (s % 29 == 0) result = sxk.inv()
                    3 -> if ((round + j) % 37 == 0) result = s2 xor sxk
                    4 -> if ((s + s2) % 97 == 0) result = s + sxk
                    5 -> if (j * round % 123 == 0) result = sxk xor t
                    6 -> if (s == 255 && s2 == 0) { result = 0; skip = true }
                    7 -> if (v35 && ((round + j) % 51 == 0 || version == 1020)) result = result xor s
                    8 -> if (v35 && (s % 29 == 0 || version == 1020)) result = result xor s
                }
                if ((j + i) % (s % 5 + 1) == 0) result = result xor t else if (skip) result = sxk.inv()
                key[i * 256 + j] = result.toByte()
                t += i
            }
        }
        return key
    }
}

private class MsvcRandom(private var state: Int) {
    fun next(): Int { state = state * 214013 + 2531011; return (state ushr 16) and 0x7fff }
}
private fun rotateByte(value: Int, count: Int): Int = ((value ushr count) or (value shl (8 - count))) and 255

/** Wolf uses standard AES rounds with a nonstandard schedule; JCA AES cannot substitute. */
internal class WolfAesCtr private constructor(material: Pair<IntArray, IntArray>) {
    constructor(password: ByteArray, version: Int, proKey: ByteArray? = null) : this(archiveMaterial(password, version, proKey))
    constructor(key: ByteArray, vector: ByteArray) : this(
        key.map { it.toInt() and 255 }.toIntArray() to vector.map { it.toInt() and 255 }.toIntArray())
    private val schedule = IntArray(176)
    private val iv = ByteArray(16)
    init {
        val (key, vector) = material
        require(key.size == 16 && vector.size == 16)
        for (i in 0..15) { schedule[i] = key[i]; iv[i] = vector[i].toByte() }
        var rcon = 1
        for (word in 4..43) {
            val temp = IntArray(4) { schedule[(word - 1) * 4 + it] }
            if (word % 4 == 0) {
                val first = temp[0]
                temp[0] = SBOX[temp[1]] xor rcon
                temp[1] = SBOX[temp[2]] ushr 4
                temp[2] = SBOX[temp[3]] xor 255
                temp[3] = rotateByte(SBOX[first], 7)
                rcon = xtime(rcon)
            }
            for (i in 0..3) schedule[word * 4 + i] = schedule[(word - 4) * 4 + i] xor temp[i]
        }
    }

    fun xor(data: ByteArray, streamPosition: Long, priorBlocks: Long) {
        val firstBlock = streamPosition / 16 + priorBlocks
        val counter = iv.copyOf()
        var carry = firstBlock
        for (i in 15 downTo 0) {
            val sum = byte(counter, i) + (carry and 255).toInt()
            counter[i] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
        }
        var block = encrypt(counter)
        var index = (streamPosition % 16).toInt()
        for (i in data.indices) {
            if (index == 16) {
                for (j in 15 downTo 0) { counter[j] = (byte(counter, j) + 1).toByte(); if (counter[j] != 0.toByte()) break }
                block = encrypt(counter)
                index = 0
            }
            data[i] = (byte(data, i) xor block[index++]).toByte()
        }
    }

    private fun encrypt(counter: ByteArray): IntArray {
        val state = IntArray(16) { byte(counter, it) xor schedule[it] }
        for (round in 1..10) {
            for (i in state.indices) state[i] = SBOX[state[i]]
            val before = state.copyOf()
            for (row in 1..3) for (column in 0..3) state[column * 4 + row] = before[((column + row) % 4) * 4 + row]
            if (round != 10) for (column in 0..3) {
                val n = column * 4
                val first = state[n]
                val all = state[n] xor state[n + 1] xor state[n + 2] xor state[n + 3]
                state[n] = state[n] xor all xor xtime(state[n] xor state[n + 1])
                state[n + 1] = state[n + 1] xor all xor xtime(state[n + 1] xor state[n + 2])
                state[n + 2] = state[n + 2] xor all xor xtime(state[n + 2] xor state[n + 3])
                state[n + 3] = state[n + 3] xor all xor xtime(state[n + 3] xor first)
            }
            for (i in state.indices) state[i] = state[i] xor schedule[round * 16 + i]
        }
        return state
    }

    companion object {
        private fun archiveMaterial(password: ByteArray, version: Int, proKey: ByteArray?): Pair<IntArray, IntArray> {
            require(password.size == 15 && version in setOf(331, 350, 1010, 1020))
            require(proKey == null || proKey.size == 4)
            val pro = proKey ?: ByteArray(4)
            val key = IntArray(16)
            val vector = IntArray(16)
            for (i in 0..14) {
                if (version == 350 || version == 1020) {
                    val p = byte(pro, i % 4)
                    val keyIndex = ((i * (p % 5 + 7)) xor (3 * byte(password, i))) % 15
                    val ivIndex = ((i * (byte(pro, (i + 1) % 4) % 7 + 11)) xor (5 * byte(password, (i + 3) % 15))) % 15
                    key[i] = ((i xor p) + (byte(password, keyIndex) shl (i % 3))) % 251
                    vector[i] = ((byte(password, ivIndex) ushr (i % 2)) + (i * i xor byte(pro, (i + 2) % 4))) % 246
                    key[15] = key[15] xor ((7 * (byte(password, i) + ((i + 1) xor p))) % 253)
                    vector[15] = vector[15] xor ((11 * ((byte(password, i) - (2 * i xor byte(pro, (i + 2) % 4))) and 0xffff)) % 256)
                } else if (version == 1010) {
                    key[i] = ((byte(password, i * 7 % 15) + byte(pro, i and 3)) * i * i) and 255
                    vector[i] = (byte(password, i * 11 % 15) + byte(pro, (i + 2) % 4) - i * i) and 255
                    key[15] = key[15] xor ((3 * i + byte(password, i) + byte(pro, i and 3)) and 255)
                    vector[15] = vector[15] xor ((5 * i + byte(password, i) + byte(pro, (i + 2) % 4)) and 255)
                } else {
                    key[i] = (byte(password, i * 7 % 15) + i * i) and 255
                    vector[i] = (byte(password, i * 11 % 15) - i * i) and 255
                    key[15] = key[15] xor ((byte(password, i) + 3 * i) and 255)
                    vector[15] = vector[15] xor ((byte(password, i) + 5 * i) and 255)
                }
            }
            for (i in 0..3) {
                key[i * 4] = key[i * 4] xor byte(pro, i)
                val at = intArrayOf(10, 1, 4, 7)[i]
                vector[at] = vector[at] xor byte(pro, i)
            }
            return key to vector
        }
        private fun xtime(value: Int): Int = ((value shl 1) xor (if (value and 128 != 0) 0x1b else 0)) and 255
        // Derive the AES substitution table in GF(2^8); no native AES dependency.
        private val SBOX = IntArray(256) { value ->
            fun multiply(left: Int, right: Int): Int {
                var a = left
                var b = right
                var result = 0
                repeat(8) { if (b and 1 != 0) result = result xor a; a = xtime(a); b = b ushr 1 }
                return result
            }
            var inverse = 1
            var power = value
            var exponent = 254
            while (exponent > 0) { if (exponent and 1 != 0) inverse = multiply(inverse, power); power = multiply(power, power); exponent = exponent ushr 1 }
            if (value == 0) inverse = 0
            inverse xor rotateByte(inverse, 7) xor rotateByte(inverse, 6) xor rotateByte(inverse, 5) xor rotateByte(inverse, 4) xor 0x63
        }
    }
}
