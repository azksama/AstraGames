package fr.astragames.wolf

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Read-only Wolf on-disk readers. Structure adapted from WolfTL and
 * wolf-rpg-formats (MIT; see THIRD_PARTY_NOTICES.md). No original EXE is loaded.
 * Unknown protection/format dialects fail before they reach the interpreter.
 */
object WolfFormats {
    const val MAX_BYTES = 128 * 1024 * 1024
    const val MAX_RECORDS = 1_000_000
    private val mapMagic = ByteArray(10) + byteArrayOf(87, 79, 76, 70, 77, 0, 0, 0, 0, 0)
    private val commonMagic = byteArrayOf(0, 87, 0, 0, 79, 76, 0, 70, 67, 0)
    private val dataMagic = byteArrayOf(0, 87, 0, 0, 79, 76, 0, 70, 77, 0)

    fun parseMap(data: ByteArray, name: String = "map"): WolfMap = parseMap(data, name, WolfParseBudget())

    internal fun parseMap(data: ByteArray, name: String, budget: WolfParseBudget): WolfMap {
        budget.input(data.size, name)
        val source = WolfReader(data, name, budget = budget)
        val header = source.bytes(20)
        val encoding = encoding(header[16].toInt() and 0xff, source)
        header[16] = 0
        source.require(header.contentEquals(mapMagic), "unsupported/protected map header")
        val version = source.int()
        source.require(version in 100..103, "unsupported map wire version $version")
        val layout = source.byte()
        val payload = if (version >= 101) unpack(source) else source.remainingBytes()
        source.end()
        val reader = WolfReader(payload, "$name:payload", encoding, budget)
        val title = reader.string()
        val tileset = reader.int()
        val width = reader.unsigned("width")
        val height = reader.unsigned("height")
        val eventCount = reader.count("event", 26)
        val modernMetadata = if (version >= 103) reader.int() else null
        val layerCount = if (version >= 103) reader.unsigned("layers") else 3
        reader.require(width in 1..10000 && height in 1..10000 && layerCount in 1..64,
            "unsupported map dimensions ${width}x${height}x$layerCount")
        val tileCount = width.toLong() * height * layerCount
        reader.require(tileCount * 4 <= MAX_BYTES, "tile matrix exceeds resource bound")
        val tiles = if (encoding == WolfEncoding.UTF8 && reader.peekInt() == -1) {
            reader.int()
            IntArray(0)
        } else {
            reader.require(tileCount * 4 <= reader.remaining, "truncated tile matrix")
            reader.allocate(tileCount * 4 + 24)
            IntArray(tileCount.toInt()) { reader.int() }
        }
        val events = List(eventCount) {
            reader.allocate(48)
            reader.expect(byteArrayOf(0x6f, 0x39, 0x30, 0, 0))
            val id = reader.int()
            val eventName = reader.string()
            val x = reader.int()
            val y = reader.int()
            val pageCount = reader.count("page", 64)
            reader.expect(ByteArray(4))
            val pages = List(pageCount) { page(reader, version >= 103, it) }
            reader.expect(0x70)
            WolfEvent(id, eventName, x, y, pages)
        }
        reader.expect(0x66)
        reader.end()
        return WolfMap(name, title, version, encoding, tileset, width, height, layerCount,
            tiles, events, layout, modernMetadata)
    }

    fun parseCommonEvents(data: ByteArray, name: String = "CommonEvent.dat"): List<WolfCommon> =
        parseCommonEvents(data, name, WolfParseBudget())

    internal fun parseCommonEvents(data: ByteArray, name: String, budget: WolfParseBudget): List<WolfCommon> {
        budget.input(data.size, name)
        val source = WolfReader(data, name, budget = budget)
        val magic = source.bytes(10)
        val encoding = encoding(magic[6].toInt() and 0xff, source)
        magic[6] = 0
        source.require(magic.contentEquals(commonMagic), "unsupported/protected common-event header")
        val version = source.byte()
        source.require(version == 0x8f || version == 0x93, "unsupported common-event version $version")
        val packed = version == 0x93
        val payload = if (packed) unpack(source) else source.remainingBytes()
        source.end()
        val reader = WolfReader(payload, "$name:payload", encoding, budget)
        val events = List(reader.count("common event", 50)) { index ->
            reader.allocate(128)
            val offset = reader.position
            reader.expect(0x8e)
            val id = reader.int()
            val condition = reader.int()
            val settings = reader.bytes(7)
            val eventName = reader.string()
            val commands = List(reader.count("command", 8)) { command(reader, packed) }
            val metadata = reader.string()
            val description = reader.string()
            reader.expect(0x8f)
            val argumentNames = reader.strings("argument name")
            val modes = reader.bytes(reader.count("argument mode"))
            val stringOptions = List(reader.count("string option table", 4)) { reader.strings("string option") }
            val numericOptions = List(reader.count("numeric option table", 4)) { reader.ints("numeric option") }
            val defaults = reader.ints("argument default")
            reader.expect(0x90)
            val color = reader.int()
            reader.allocate(100 * 8 + 24)
            val localNames = List(100) { reader.string() }
            reader.expect(0x91)
            val metadataExtra = reader.string()
            val end = reader.byte()
            var returnName: String? = null
            var returnValue: Int? = null
            if (end == 0x92) {
                returnName = reader.string()
                returnValue = reader.int()
                reader.expect(0x92)
            } else reader.require(end == 0x91, "invalid common-event end marker $end")
            WolfCommon(index, id, eventName, condition, settings, commands, metadata, description,
                argumentNames, modes, stringOptions, numericOptions, defaults, color, localNames,
                metadataExtra, returnName, returnValue, offset)
        }
        reader.expect(if (packed) 0x92 else 0x8f)
        reader.end()
        return events
    }

    fun parseGame(data: ByteArray, name: String = "Game.dat"): WolfGameConfig = parseGame(data, name, WolfParseBudget())

    internal fun parseGame(data: ByteArray, name: String, budget: WolfParseBudget): WolfGameConfig {
        budget.input(data.size, name)
        val reader = WolfReader(data, name, budget = budget)
        val magic = reader.bytes(10)
        val encoding = encoding(magic[9].toInt() and 0xff, reader)
        magic[9] = 0
        reader.require(magic.contentEquals(dataMagic), "unsupported/protected Game.dat header")
        reader.encoding = encoding
        val byteSettings = reader.bytes(reader.count("game byte settings"))
        reader.require(byteSettings.size in 21..64, "unsupported game byte-settings length ${byteSettings.size}")
        val count = reader.count("game string", 5)
        reader.require(count in 9..14, "unsupported game string-settings count $count")
        // The key at index 2 is a byte array, not necessarily text. It is not
        // needed for these unprotected files and is never exposed as a title.
        val strings = List(count) { if (it == 2) { reader.bytes(reader.count("key")); "" } else reader.string() }
        reader.require(strings[1] == "0000-0000", "unexpected game serial marker")
        val fileSize = reader.unsigned("file size")
        reader.require(fileSize == data.size - 1, "Game.dat file-size marker mismatch")
        reader.int() // Editor-owned metadata; retained source bytes are not written.
        val wordCount = reader.count("game word settings", 2)
        reader.require(wordCount in 19..64, "unsupported game word-settings length $wordCount")
        val words = IntArray(wordCount) { reader.short() }
        // The following two words and random table are editor data, not a VM.
        reader.int()
        reader.int()
        reader.bytes(reader.remaining - 1)
        val footer = reader.byte()
        reader.require(footer in 0xc2..0xc4, "unsupported game footer $footer")
        reader.end()
        val tile = byteSettings[0].toInt() and 0xff
        val fps = byteSettings[4].toInt() and 0xff
        val width = words[16]
        val height = words[17]
        reader.require(tile in setOf(16, 32, 40, 48), "unsupported tile size $tile")
        reader.require(fps == 30 || fps == 60, "unsupported logical frame rate $fps")
        reader.require(width in 1..8192 && height in 1..8192, "unsupported logical screen ${width}x$height")
        return WolfGameConfig(strings[0], strings.getOrElse(8) { "" }, encoding, tile, width, height, fps,
            strings[7], byteSettings[1].toInt() and 0xff, byteSettings[2].toInt() and 0xff,
            byteSettings[7].toInt() and 0xff, strings[3], strings.subList(4, 7), byteSettings, words, strings, footer)
    }

    fun parseDatabase(projectBytes: ByteArray, dataBytes: ByteArray, name: String = "DataBase"): WolfDatabase =
        parseDatabase(projectBytes, dataBytes, name, WolfParseBudget())

    internal fun parseDatabase(projectBytes: ByteArray, dataBytes: ByteArray, name: String, budget: WolfParseBudget): WolfDatabase {
        budget.input(projectBytes.size, "$name.project")
        budget.input(dataBytes.size, "$name.dat")
        val source = WolfReader(dataBytes, "$name.dat", budget = budget)
        val magic = source.bytes(10)
        val encoding = encoding(magic[6].toInt() and 0xff, source)
        magic[6] = 0
        source.require(magic.contentEquals(dataMagic), "unsupported/protected database header")
        val version = source.byte()
        source.require(version in 0xc1..0xc4, "unsupported database version $version")
        val payload = if (version == 0xc4) unpack(source) else source.remainingBytes()
        source.end()
        val reader = WolfReader(payload, "$name.dat:payload", encoding, budget)
        val project = WolfReader(projectBytes, "$name.project", encoding, budget)
        val types = List(project.count("database type", 24)) { projectType(project) }
        project.end()
        val typeCount = reader.count("database type", 16)
        reader.require(typeCount == types.size, "database/project type count mismatch")
        val result = types.mapIndexed { index, type ->
            reader.allocate(64)
            reader.expect(byteArrayOf(-2, -1, -1, -1))
            val idMethod = reader.int()
            val fieldCount = reader.count("database field", 4)
            reader.require(fieldCount <= type.names.size, "database field count exceeds project fields")
            val idString = if (idMethod == 120000) reader.string() else null
            val positions = IntArray(fieldCount) { reader.int() }
            val numbers = positions.count { it in 1000..1999 }
            val strings = positions.count { it in 2000..2999 }
            reader.require(numbers + strings == fieldCount, "unsupported database field position")
            val numberPositions = positions.filter { it in 1000..1999 }.map { it - 1000 }
            val stringPositions = positions.filter { it in 2000..2999 }.map { it - 2000 }
            reader.require(numberPositions.toSet() == (0 until numbers).toSet() &&
                stringPositions.toSet() == (0 until strings).toSet(), "non-contiguous or duplicate database field slots")
            val fields = positions.mapIndexed { fieldIndex, position ->
                reader.allocate(64)
                WolfDatabaseField(type.names[fieldIndex], if (position >= 2000) WolfValueType.STRING else WolfValueType.NUMBER,
                    position % 1000, type.fieldModes.getOrElse(fieldIndex) { 0 }.toInt() and 0xff,
                    type.metadata.getOrElse(fieldIndex) { "" }, type.stringOptions.getOrElse(fieldIndex) { emptyList() },
                    type.numericOptions.getOrElse(fieldIndex) { IntArray(0) }, type.defaults.getOrElse(fieldIndex) { 0 })
            }
            val rows = List(reader.count("database row", (numbers * 4 + strings * 5).coerceAtLeast(1))) { row ->
                reader.allocate(48 + numbers.toLong() * 4 + strings.toLong() * 8 + 48)
                reader.require(row < type.rowNames.size, "database rows exceed project row definitions")
                WolfDatabaseRow(row, type.rowNames[row], IntArray(numbers) { reader.int() }, List(strings) { reader.string() })
            }
            WolfDatabaseType(index, type.name, type.description, fields, rows, idMethod, idString)
        }
        reader.expect(version)
        reader.end()
        return WolfDatabase(name, encoding, version, result)
    }

    fun parseTilesets(data: ByteArray, name: String = "TileSetData.dat"): List<WolfTileset> =
        parseTilesets(data, name, WolfParseBudget())

    internal fun parseTilesets(data: ByteArray, name: String, budget: WolfParseBudget): List<WolfTileset> {
        budget.input(data.size, name)
        val reader = WolfReader(data, name, budget = budget)
        val magic = reader.bytes(10)
        val encoding = encoding(magic[6].toInt() and 0xff, reader)
        magic[6] = 0
        reader.require(magic.contentEquals(dataMagic), "unsupported/protected tileset header")
        reader.encoding = encoding
        val version = reader.byte()
        reader.require(version == 209 || version == 210, "unsupported tileset version $version")
        val result = List(reader.count("tileset", 90)) { index ->
            reader.allocate(64 + 31 * 8 + 24)
            val title = reader.string()
            val base = reader.string()
            val autos = List(if (version == 209) 15 else 31) { reader.string() }
            reader.expect(0xff)
            val tags = reader.bytes(reader.count("tile tag"))
            reader.expect(0xff)
            val passage = reader.ints("tile passage")
            reader.require(tags.size == passage.size, "tile tags/passage length mismatch")
            WolfTileset(index, title, base, autos, tags, passage)
        }
        reader.expect(0xcf)
        reader.end()
        return result
    }

    private fun page(reader: WolfReader, extended: Boolean, index: Int): WolfPage {
        reader.allocate(96)
        val offset = reader.position
        reader.expect(0x79)
        val tile = reader.int()
        val file = reader.string()
        val direction = reader.byte()
        val frame = reader.byte()
        val opacity = reader.byte()
        val blend = reader.byte()
        val conditions = reader.bytes(37)
        val movement = reader.bytes(4)
        val flags = reader.byte()
        val routeFlags = reader.byte()
        val route = route(reader, routeFlags)
        val commands = List(reader.count("command", 8)) { command(reader, extended) }
        val featureCount = reader.unsigned("page feature count")
        reader.require(featureCount == 3 || featureCount == 4, "unsupported page feature count $featureCount")
        val features = reader.bytes(3)
        val transfer = if (featureCount > 3) reader.byte() else null
        reader.expect(0x7a)
        return WolfPage(index, tile, file, direction, frame, opacity, blend, conditions,
            movement, flags, route, commands, featureCount, features, transfer, offset)
    }

    private fun command(reader: WolfReader, extended: Boolean): WolfCommand {
        reader.allocate(64)
        val offset = reader.position
        val count = reader.byte()
        reader.require(count > 0, "zero command word count")
        val code = reader.int()
        reader.allocate((count - 1).toLong() * 4 + 24)
        val args = IntArray(count - 1) { reader.int() }
        val indent = reader.byte()
        val stringCount = reader.byte()
        reader.allocate(stringCount.toLong() * 8 + 24)
        val strings = List(stringCount) { reader.string() }
        val routeFlag = reader.byte()
        reader.require(routeFlag == 0 || routeFlag == 1, "invalid command route marker $routeFlag")
        val route = if (routeFlag == 1) {
            val header = reader.bytes(5)
            route(reader, reader.byte(), header)
        } else null
        val extension = if (extended) reader.bytes(reader.byte()) else ByteArray(0)
        return WolfCommand(code, args, indent, strings, route, extension, offset)
    }

    private fun route(reader: WolfReader, flags: Int, header: ByteArray = byteArrayOf()): WolfRoute =
        WolfRoute(flags, List(reader.count("route", 4)) {
            reader.allocate(32)
            val code = reader.byte()
            val argCount = reader.byte()
            reader.allocate(argCount.toLong() * 4 + 24)
            val args = IntArray(argCount) { reader.int() }
            reader.expect(1)
            reader.expect(0)
            WolfRouteCommand(code, args)
        }, header)

    private fun unpack(reader: WolfReader): ByteArray {
        val expanded = reader.unsigned("expanded size")
        val compressed = reader.unsigned("compressed size")
        reader.require(expanded <= MAX_BYTES, "expanded payload exceeds resource bound")
        reader.allocate(expanded.toLong() + 24)
        val result = decodeLz4(reader.bytes(compressed), expanded)
        reader.end()
        return result
    }

    private fun encoding(marker: Int, reader: WolfReader): WolfEncoding = when (marker) {
        0 -> WolfEncoding.CP932
        0x55 -> WolfEncoding.UTF8
        else -> reader.fail("unsupported encoding marker $marker")
    }

    private data class ProjectType(val name: String, val names: List<String>, val rowNames: List<String>,
        val description: String, val fieldModes: ByteArray, val metadata: List<String>,
        val stringOptions: List<List<String>>, val numericOptions: List<IntArray>, val defaults: IntArray)

    private fun projectType(reader: WolfReader): ProjectType {
        reader.allocate(96)
        val name = reader.string()
        val fields = reader.strings("field name")
        val rows = reader.strings("row name")
        val description = reader.string()
        val modes = reader.bytes(reader.count("field mode"))
        reader.require(modes.size >= fields.size, "field mode table is shorter than field names")
        val metadata = reader.strings("field metadata")
        val strings = List(reader.count("field string option table", 4)) { reader.strings("field string option") }
        val numbers = List(reader.count("field numeric option table", 4)) { reader.ints("field numeric option") }
        val defaults = reader.ints("field default")
        reader.require(metadata.size <= fields.size && strings.size <= fields.size && numbers.size <= fields.size &&
            defaults.size <= fields.size, "project field metadata exceeds field count")
        return ProjectType(name, fields, rows, description, modes, metadata, strings, numbers, defaults)
    }

    /** Raw LZ4 block, exact input/output bounds and overlapping copies. */
    fun decodeLz4(block: ByteArray, expandedSize: Int): ByteArray {
        if (expandedSize !in 0..MAX_BYTES) throw WolfFormatException("LZ4: invalid expanded size $expandedSize")
        val input = WolfReader(block, "LZ4")
        val output = ByteArray(expandedSize)
        var position = 0
        fun length(nibble: Int): Int {
            var value = nibble.toLong()
            if (nibble == 15) do {
                val extra = input.byte()
                value += extra
                input.require(value <= expandedSize, "sequence exceeds expanded-size bound")
            } while (extra == 255)
            return value.toInt()
        }
        while (input.remaining > 0) {
            val token = input.byte()
            val literals = length(token ushr 4)
            input.require(literals <= expandedSize - position, "literal exceeds output bound")
            input.copyTo(output, position, literals)
            position += literals
            if (input.remaining == 0) break
            val offset = input.short()
            input.require(offset > 0 && offset <= position, "invalid match offset $offset")
            val match = length(token and 15).toLong() + 4
            input.require(match <= expandedSize - position, "match exceeds output bound")
            repeat(match.toInt()) { output[position] = output[position - offset]; position++ }
        }
        input.end()
        input.require(position == expandedSize, "expanded size mismatch $position != $expandedSize")
        return output
    }
}

/**
 * A shared envelope prevents individually valid metadata files from exhausting
 * the host together. Allocation charges are conservative accounting units,
 * not a claim about a particular JVM's object layout. They cover copies,
 * strings, collection entries and records before allocation takes place.
 */
internal class WolfParseBudget(
    private val maxInputBytes: Long = WolfFormats.MAX_BYTES.toLong(),
    private val maxAllocationBytes: Long = WolfFormats.MAX_BYTES.toLong(),
) {
    private var inputBytes = 0L
    private var allocationBytes = 0L

    fun input(bytes: Int, name: String) {
        if (bytes < 0 || bytes.toLong() > maxInputBytes - inputBytes)
            throw WolfFormatException("$name: cumulative input exceeds native metadata budget")
        inputBytes += bytes
    }

    fun allocate(bytes: Long, name: String) {
        if (bytes < 0 || bytes > maxAllocationBytes - allocationBytes)
            throw WolfFormatException("$name: cumulative allocations exceed native parse budget")
        allocationBytes += bytes
    }
}

internal class WolfReader(
    private val data: ByteArray,
    private val name: String,
    var encoding: WolfEncoding = WolfEncoding.CP932,
    private val budget: WolfParseBudget = WolfParseBudget(),
) {
    var position: Int = 0
        private set
    val remaining: Int get() = data.size - position
    init { require(data.size <= WolfFormats.MAX_BYTES, "file exceeds resource bound") }
    fun allocate(bytes: Int) = allocate(bytes.toLong())
    fun allocate(bytes: Long) = budget.allocate(bytes, "$name@0x${position.toString(16)}")
    fun fail(message: String): Nothing = throw WolfFormatException("$name@0x${position.toString(16)}: $message")
    fun require(value: Boolean, message: String) { if (!value) fail(message) }
    fun byte(): Int { require(remaining >= 1, "truncated byte"); return data[position++].toInt() and 0xff }
    fun short(): Int = byte() or (byte() shl 8)
    fun int(): Int = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)
    fun peekInt(): Int {
        require(remaining >= 4, "truncated integer")
        return (data[position].toInt() and 0xff) or ((data[position + 1].toInt() and 0xff) shl 8) or
            ((data[position + 2].toInt() and 0xff) shl 16) or (data[position + 3].toInt() shl 24)
    }
    fun unsigned(kind: String): Int = int().also { require(it >= 0, "invalid $kind ${it.toUInt()}") }
    fun bytes(size: Int): ByteArray {
        require(size >= 0 && size <= remaining, "truncated record: requested $size, remaining $remaining")
        allocate(size.toLong() + 24)
        return data.copyOfRange(position, position + size).also { position += size }
    }
    fun copyTo(output: ByteArray, target: Int, size: Int) {
        require(size >= 0 && size <= remaining, "truncated literal")
        data.copyInto(output, target, position, position + size)
        position += size
    }
    fun remainingBytes(): ByteArray = bytes(remaining)
    fun count(kind: String, minimumBytes: Int = 1): Int = unsigned("$kind count").also {
        require(it <= WolfFormats.MAX_RECORDS && it.toLong() * minimumBytes <= remaining, "invalid $kind count $it")
        allocate(it.toLong() * 8 + 24)
    }
    fun expect(marker: Int) { val actual = byte(); require(actual == marker, "marker mismatch $actual != $marker") }
    fun expect(marker: ByteArray) { require(bytes(marker.size).contentEquals(marker), "marker mismatch") }
    fun string(): String {
        val size = unsigned("string length")
        require(size in 1..WolfFormats.MAX_BYTES && size <= remaining, "invalid string byte length $size")
        allocate(40 + (size - 1).toLong() * 2)
        val start = position
        position += size
        require(data[position - 1].toInt() == 0, "string lacks trailing NUL")
        for (offset in start until position - 1) require(data[offset].toInt() != 0, "string contains embedded NUL")
        val charset = if (encoding == WolfEncoding.UTF8) Charsets.UTF_8 else Charset.forName("windows-31j")
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, start, size - 1)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw WolfFormatException("$name@0x${start.toString(16)}: invalid ${charset.name()} string", error)
        }
    }
    fun strings(kind: String): List<String> = List(count(kind, 5)) { string() }
    fun ints(kind: String): IntArray = IntArray(count(kind, 4)) { int() }
    fun end() { require(remaining == 0, "unexpected $remaining trailing bytes") }
}
