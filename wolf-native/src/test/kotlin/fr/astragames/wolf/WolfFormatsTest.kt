package fr.astragames.wolf

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WolfFormatsTest {
    @Test fun overlappingLz4MatchIsDecoded() {
        assertArrayEquals("abababababab".toByteArray(),
            WolfFormats.decodeLz4(byteArrayOf(0x26, 'a'.code.toByte(), 'b'.code.toByte(), 2, 0), 12))
    }

    @Test fun lz4RejectsBadOffsetAndOutputSize() {
        rejects { WolfFormats.decodeLz4(byteArrayOf(0, 0, 0), 4) }
        rejects { WolfFormats.decodeLz4(byteArrayOf(0x10, 1), 2) }
        rejects { WolfFormats.decodeLz4(byteArrayOf(0x10, 1, 2, 0), 5) }
        rejects { WolfFormats.decodeLz4(byteArrayOf(0), -1) }
        rejects { WolfFormats.decodeLz4(byteArrayOf(0), Int.MAX_VALUE) }
    }

    @Test fun minimalMapPreservesLayerOrderAndNoEvents() {
        val map = WolfFormats.parseMap(minimalMap())
        assertEquals(1, map.width)
        assertEquals(1, map.height)
        assertArrayEquals(intArrayOf(32, 33, 34), map.tiles)
        assertEquals(34, map.tile(0, 0, 2))
        assertEquals(-1, map.tile(1, 0, 2))
        assertTrue(map.events.isEmpty())
    }

    @Test fun mapRejectsTruncationMarkersQuotasAndTrailingData() {
        val valid = minimalMap()
        for (size in valid.indices) rejects { WolfFormats.parseMap(valid.copyOf(size)) }
        rejects { WolfFormats.parseMap(valid + byteArrayOf(0)) }
        rejects { WolfFormats.parseMap(valid.clone().also { it[0] = 1 }) }
        rejects { WolfFormats.parseMap(minimalMap(width = Int.MAX_VALUE)) }
        rejects { WolfFormats.parseMap(minimalMap(eventCount = Int.MAX_VALUE)) }
    }

    @Test fun resourcePathsStayInsideDataDirectory() {
        assertEquals("Data/MapData/Test.mps", WolfParser.dataPath("MapData\\Test.mps"))
        assertEquals("Data/Picture/X.png", WolfParser.dataPath("Data/Picture/X.png"))
        assertEquals("", WolfParser.dataPath(""))
        rejects { WolfParser.dataPath("../../Save/a") }
        rejects { WolfParser.dataPath("C:/windows/a") }
        rejects { WolfParser.dataPath("/absolute/a") }
        rejects { WolfParser.dataPath("Picture/\u0000x") }
    }

    @Test fun strictTextDecodingRejectsMalformedCp932AndUtf8() {
        val map = minimalMap()
        val malformedCp932 = map.copyOfRange(0, 25) + byteArrayOf(2, 0, 0, 0, 0x81.toByte(), 0) + map.copyOfRange(30, map.size)
        rejects { WolfFormats.parseMap(malformedCp932) }
        val malformedUtf8 = malformedCp932.clone().also { it[16] = 0x55; it[29] = 0xff.toByte() }
        rejects { WolfFormats.parseMap(malformedUtf8) }
        val embeddedNul = map.copyOfRange(0, 25) + byteArrayOf(3, 0, 0, 0, 0, 65, 0) + map.copyOfRange(30, map.size)
        rejects { WolfFormats.parseMap(embeddedNul) }
    }

    @Test fun databaseFieldPositionsSelectTheRightStorageBlock() {
        val (project, dat) = minimalDatabase()
        val database = WolfFormats.parseDatabase(project, dat)
        val type = database.types.single()
        assertEquals("hello", type.string(0, 0))
        assertEquals(22, type.number(0, 1))
        assertEquals(11, type.number(0, 2))
        assertEquals(-7, type.number(1, 1))
        assertNull(type.number(0, 0))
        assertNull(type.string(0, 1))
    }

    @Test fun databaseRejectsDuplicateSlotsAndOutOfRangeRows() {
        val (project, dat) = minimalDatabase()
        // Second numeric slot duplicates slot zero (the first field is a string).
        rejects { WolfFormats.parseDatabase(project, dat.clone().also {
            it[31] = 0xe8.toByte(); it[32] = 3
        }) }
        rejects { WolfFormats.parseDatabase(project, dat.copyOf(dat.size - 1)) }
        rejects { WolfFormats.parseDatabase(project + byteArrayOf(0), dat) }
        rejects { WolfFormats.parseDatabase(project, dat + byteArrayOf(0)) }
        rejects { WolfFormats.parseDatabase(project, dat.clone().also { it[0] = 1 }) }
    }

    @Test fun passageLookupAccountsForTheVersionSpecificAutotileTable() {
        val old = WolfTileset(0, "", "", List(15) { "" }, ByteArray(20), IntArray(20) { it })
        val modern = WolfTileset(0, "", "", List(31) { "" }, ByteArray(40), IntArray(40) { it })
        assertEquals(16, old.passageForTile(32))
        assertEquals(32, modern.passageForTile(32))
        assertEquals(7, modern.passageForTile(704444))
        assertEquals(0, modern.passageForTile(0))
        assertEquals(0x0f, modern.passageForTile(Int.MAX_VALUE))
    }

    @Test fun sharedBudgetRejectsManyIndividuallyValidResources() {
        val data = minimalMap()
        val budget = WolfParseBudget(maxInputBytes = data.size.toLong() * 2 - 1)
        WolfFormats.parseMap(data, "first map", budget)
        rejects { WolfFormats.parseMap(data, "second map", budget) }
        // The database pair must share the same envelope as well.
        val (project, database) = minimalDatabase()
        rejects { WolfFormats.parseDatabase(project, database, "database",
            WolfParseBudget(maxInputBytes = maxOf(project.size, database.size).toLong())) }
    }

    @Test fun sharedBudgetBoundsSmallObjectsAndNestedCollections() {
        // A short binary table can otherwise create many empty strings/objects.
        val strings = ByteArrayOutputStream().run {
            repeat(10) { write(byteArrayOf(1, 0, 0, 0, 0)) }
            toByteArray()
        }
        val reader = WolfReader(strings, "string table", budget = WolfParseBudget(maxAllocationBytes = 200))
        repeat(5) { assertEquals("", reader.string()) }
        rejects { reader.string() }
        rejects { WolfFormats.parseMap(minimalMap(), "allocation bound",
            WolfParseBudget(maxAllocationBytes = 32)) }
    }

    @Test fun officialWolf2SampleParsesAllMetadataAndMaps() = sample("sample22961/WOLF_RPG_Editor2", 526, 25611, WolfEncoding.CP932)
    @Test fun officialWolf3SampleParsesAllMetadataAndMaps() = sample("sample3729/WOLF_RPG_Editor3", 887, 26699, WolfEncoding.UTF8)

    private fun sample(folder: String, mapCommands: Int, commonCommands: Int, encoding: WolfEncoding) {
        val root = File(System.getProperty("wolf.fixtureRoot", "../build/wolf-research"), folder)
        assumeTrue("Official research samples are optional local fixtures", File(root, "Data/BasicData/Game.dat").isFile)
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray = File(root, path).readBytes()
            override fun exists(path: String): Boolean = File(root, path).isFile
            override fun list(path: String): List<String> = File(root, path).list()?.toList() ?: emptyList()
        }
        val parser = WolfParser(source)
        val game = parser.loadGame()
        assertEquals(encoding, game.config.encoding)
        assertEquals(320, game.config.width)
        assertEquals(240, game.config.height)
        assertEquals(16, game.config.tileSize)
        assertEquals(60, game.config.fps)
        assertEquals(225, game.commonEvents.size)
        assertEquals(commonCommands, game.commonEvents.sumOf { it.commands.size })
        assertEquals(4, game.maps.size)
        assertEquals(6, game.tilesets.size)
        assertEquals(0, game.start.mapId)
        assertEquals(if (encoding == WolfEncoding.CP932) WolfStartPosition(0, 8, 5) else WolfStartPosition(0, 6, 6), game.start)
        val system = game.databases.getValue(WolfDatabaseKind.SYSTEM)
        // Type 6 supplies system-variable names, not numerical defaults.
        assertTrue(system.types[6].fields.isEmpty())
        assertEquals(if (encoding == WolfEncoding.CP932) 140 else 170, system.types[6].rows.size)
        val palette = system.types[12]
        assertEquals(255, palette.number(0, 0)); assertEquals(255, palette.number(0, 1)); assertEquals(255, palette.number(0, 2))
        assertEquals(130, palette.number(1, 0)); assertEquals(130, palette.number(1, 1)); assertEquals(210, palette.number(1, 2))
        val maps = game.maps.map { parser.loadMap(it.id) }
        assertEquals(mapCommands, maps.sumOf { map -> map.events.sumOf { event -> event.pages.sumOf { it.commands.size } } })
        assertEquals(if (encoding == WolfEncoding.CP932) 31 else 37, maps.sumOf { it.events.size })
        for (map in maps) assertEquals(map.width * map.height * map.layerCount, map.tiles.size)
        val directions = maps.flatMap { it.events }.flatMap { it.pages }.map { it.graphicDirection }.toSet()
        assertEquals(setOf(1, 2, 3, 4, 6, 7, 8), directions)
        for (map in maps) for (tile in map.tiles.filter { it >= 100000 }) {
            var quadrants = tile % 10000
            repeat(4) { assertTrue(quadrants % 10 in 0..4); quadrants /= 10 }
        }
        for (database in game.databases.values) {
            assertTrue(database.types.isNotEmpty())
            for (type in database.types) for (row in type.rows) for ((index, field) in type.fields.withIndex()) {
                if (field.valueType == WolfValueType.NUMBER) assertNotNull(type.number(row.id, index))
                else assertNotNull(type.string(row.id, index))
            }
        }
    }

    private fun minimalMap(width: Int = 1, eventCount: Int = 0): ByteArray = ByteArrayOutputStream().run {
        fun int(value: Int) { repeat(4) { write(value ushr (it * 8) and 255) } }
        write(ByteArray(10)); write(byteArrayOf(87, 79, 76, 70, 77, 0, 0, 0, 0, 0))
        int(100); write(0x65); int(1); write(0); int(0); int(width); int(1); int(eventCount)
        int(32); int(33); int(34); write(0x66); toByteArray()
    }

    private fun minimalDatabase(): Pair<ByteArray, ByteArray> {
        class Writer : ByteArrayOutputStream() {
            fun int(value: Int) { repeat(4) { write(value ushr (it * 8) and 255) } }
            fun string(value: String) { val text = value.toByteArray(); int(text.size + 1); write(text); write(0) }
        }
        val project = Writer().run {
            int(1); string("type"); int(3); string("text"); string("second"); string("first")
            int(2); string("row0"); string("row1"); string(""); int(3); write(byteArrayOf(0, 0, 0))
            int(0); int(0); int(0); int(0); toByteArray()
        }
        val data = Writer().run {
            write(byteArrayOf(0, 87, 0, 0, 79, 76, 0, 70, 77, 0)); write(0xc1); int(1)
            int(-2); int(0); int(3); int(2000); int(1001); int(1000); int(2)
            int(11); int(22); string("hello"); int(-3); int(-7); string(""); write(0xc1); toByteArray()
        }
        return project to data
    }

    private fun rejects(action: () -> Any?) {
        try { action(); fail("Expected WolfFormatException") }
        catch (_: WolfFormatException) { }
    }
}
