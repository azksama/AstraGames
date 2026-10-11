package fr.astragames.wolf

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WolfFormatsTest {
    @Test fun exactMetadataNoticePreservesClearBodiesAndNeverAliasesEncryptionOrAnotherKind() {
        val notice = "Extracting data from encrypted files violates the guidelines.\u0000".toByteArray(Charsets.US_ASCII)
        assertEquals(62, notice.size)
        for (encoding in WolfEncoding.entries) {
            val game = gameFixture(encoding, 9)
            val common = commonEnvelope(commonFixture(encoding).payload, encoding, 0xcc)
            val (project, database) = minimalDatabase()
            val tiles = tilesetFixture(211, encoding)
            for ((kind, plain) in listOf(WolfMetadataKind.GAME to game, WolfMetadataKind.COMMON to common,
                    WolfMetadataKind.DATABASE to database, WolfMetadataKind.TILESET to tiles)) {
                val wrapped = notice + plain; val original = wrapped.copyOf()
                val decoded = WolfMetadataCrypt.decode(wrapped, kind, "notice", WolfParseBudget())
                assertArrayEquals(plain, decoded.bytes); assertFalse(decoded.encrypted); assertNull(decoded.projectKey)
                assertArrayEquals(original, wrapped)
                rejects { WolfMetadataCrypt.decode(wrapped, kind, "notice", WolfParseBudget(maxAllocationBytes = 10)) }
                for (length in 0..9) rejects {
                    WolfMetadataCrypt.decode(notice + plain.copyOf(length), kind, "notice", WolfParseBudget())
                }
                rejects { WolfMetadataCrypt.decode(notice + notice + plain, kind, "notice", WolfParseBudget()) }
                rejects { WolfMetadataCrypt.decode(wrapped.clone().also { it[notice.size + 8] = 0 }, kind, "notice", WolfParseBudget()) }
            }
            assertEquals("魔女の道", WolfFormats.parseGame(notice + game).title)
            assertCommonsEqual(WolfFormats.parseCommonEvents(common), WolfFormats.parseCommonEvents(notice + common))
            assertEquals("hello", WolfFormats.parseDatabase(project, notice + database).types.single().string(0, 0))
            assertEquals("森0", WolfFormats.parseTilesets(notice + tiles).first().name)
            rejects { WolfFormats.parseGame(notice + common) }
            val encrypted = historicalEnvelope(game, byteArrayOf(0x39, 0x57, 0x12, 0x53, 0x24, 0x43, 0x35, 0x46, 0x67, 0x78), intArrayOf(0, 8, 6))
            rejects { WolfFormats.parseGame(notice + encrypted) }
            for (cut in 1 until notice.size) rejects { WolfFormats.parseGame(notice.copyOf(cut) + game) }
            rejects { WolfFormats.parseGame(notice + game + byteArrayOf(0)) }
        }
    }

    @Test fun d3TilesetsPreserveEveryStringAndPropertyWithoutAdmittingFutureLayouts() {
        for (encoding in WolfEncoding.entries) for (version in 209..211) {
            val data = tilesetFixture(version, encoding)
            val original = data.copyOf()
            val sets = WolfFormats.parseTilesets(data)
            assertEquals(2, sets.size)
            val slots = if (version == 209) 15 else 31
            for ((index, set) in sets.withIndex()) {
                assertEquals("森$index", set.name); assertEquals("MapChip/森$index.png", set.baseImage)
                assertEquals(List(slots) { "MapChip/自動${index}_$it.png" }, set.autoImages)
                assertArrayEquals(ByteArray(slots + 4) { (it * 13 + index).toByte() }, set.tags)
                assertArrayEquals(IntArray(slots + 4) { (it * 0x101) xor (index shl 16) }, set.passage)
            }
            assertArrayEquals(original, data)
            if (version == 211) {
                for (length in data.indices) rejects { WolfFormats.parseTilesets(data.copyOf(length)) }
                rejects { WolfFormats.parseTilesets(data + byteArrayOf(0)) }
                rejects { WolfFormats.parseTilesets(data.clone().also { it[10] = 212.toByte() }) }
                rejects { WolfFormats.parseTilesets(data.clone().also { it[it.lastIndex] = 0xce.toByte() }) }
                rejects { WolfFormats.parseTilesets(data.clone().also { putInt(it, 11, Int.MAX_VALUE) }) }
            }
        }
    }

    private fun tilesetFixture(version: Int, encoding: WolfEncoding): ByteArray = WireWriter(encoding).run {
        write(byteArrayOf(0, 87, 0, 0, 79, 76, if (encoding == WolfEncoding.UTF8) 0x55 else 0, 70, 77, 0))
        write(version); int(2)
        val slots = if (version == 209) 15 else 31
        repeat(2) { index ->
            string("森$index"); string("MapChip/森$index.png")
            repeat(slots) { string("MapChip/自動${index}_$it.png") }
            write(0xff); int(slots + 4); write(ByteArray(slots + 4) { (it * 13 + index).toByte() })
            write(0xff); int(slots + 4); repeat(slots + 4) { int((it * 0x101) xor (index shl 16)) }
        }
        write(0xcf); toByteArray()
    }

    @Test fun historicalEncryptedGameAndCommonBodiesUseTheirOwnSeedPositions() {
        val header = byteArrayOf(0x39, 0x57, 0x12, 0x53, 0x24, 0x43, 0x35, 0x46, 0x67, 0x78)
        val plainGame = gameFixture(WolfEncoding.CP932, 9)
        val encryptedGame = historicalEnvelope(plainGame, header, intArrayOf(0, 8, 6))
        val expectedGame = WolfFormats.parseGame(plainGame)
        val actualGame = WolfFormats.parseGame(encryptedGame)
        assertEquals(expectedGame.title, actualGame.title)
        assertEquals(expectedGame.subtitle, actualGame.subtitle)
        assertEquals(expectedGame.defaultHeroGraphic, actualGame.defaultHeroGraphic)
        assertEquals(expectedGame.engineCompatibilityVersion, actualGame.engineCompatibilityVersion)
        assertArrayEquals(expectedGame.byteSettings, actualGame.byteSettings)
        assertArrayEquals(expectedGame.wordSettings, actualGame.wordSettings)
        for (dialect in 0x8f..0x92) {
            val fixture = commonFixture(WolfEncoding.CP932, false, 0x92)
            val plain = commonEnvelope(fixture.payload, WolfEncoding.CP932, dialect)
            val encrypted = historicalEnvelope(plain, header, intArrayOf(0, 3, 9))
            assertCommonsEqual(WolfFormats.parseCommonEvents(plain), WolfFormats.parseCommonEvents(encrypted))
        }
        assertArrayEquals(header, encryptedGame.copyOf(10))
        assertArrayEquals(plainGame, gameFixture(WolfEncoding.CP932, 9))
    }

    @Test fun historicalMetadataRejectsTruncationUnknownProAndMismatchedSeeds() {
        val header = byteArrayOf(0x39, 0x57, 0x12, 0x53, 0x24, 0x43, 0x35, 0x46, 0x67, 0x78)
        val valid = historicalEnvelope(gameFixture(WolfEncoding.CP932, 9), header, intArrayOf(0, 8, 6))
        for (size in valid.indices) rejects { WolfFormats.parseGame(valid.copyOf(size)) }
        rejects { WolfFormats.parseGame(valid.clone().also { it[8] = (it[8].toInt() xor 1).toByte() }) }
        rejects { WolfFormats.parseGame(valid.clone().also { it[1] = 0x50 }) }
        val common = historicalEnvelope(commonEnvelope(commonFixture(WolfEncoding.CP932, false, 0x92).payload, WolfEncoding.CP932, 0x90), header, intArrayOf(0, 3, 9))
        rejects { WolfFormats.parseCommonEvents(common.clone().also { it[3] = (it[3].toInt() xor 1).toByte() }) }
        rejects { WolfFormats.parseCommonEvents(common.clone().also { it[1] = 0x50; it[5] = 0x57 }) }
        rejects { WolfFormats.parseCommonEvents(common, "historical", WolfParseBudget(maxAllocationBytes = 10)) }
    }

    private fun historicalEnvelope(plain: ByteArray, header: ByteArray, seeds: IntArray): ByteArray {
        val bytes = plain.copyOfRange(10, plain.size)
        for ((i, interval) in listOf(1, 2, 5).withIndex()) {
            var state = header[seeds[i]].toLong() and 255
            for (j in bytes.indices step interval) {
                state = (state * 214013 + 2531011) and 0xffffffffL
                val random = (state ushr 16) and 32767
                bytes[j] = (bytes[j].toInt() xor (random ushr 12).toInt()).toByte()
            }
        }
        return header + bytes
    }

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

    @Test fun asymmetricWireColumnsBecomeRowsAtEveryLayerAndMapDialect() {
        // Independently authored MPS wire order follows the original readers:
        // layer, X, Y. Explicit coordinates catch both a transpose and a layer
        // stride error; the engine's row-major WolfMap API remains unchanged.
        for (version in 100..103) {
            val layers = if (version == 103) 4 else 3
            val payload = WireWriter().run {
                string("asymmetric"); int(0); int(2); int(3); int(0)
                if (version == 103) { int(0); int(layers) }
                repeat(layers) { layer ->
                    int(layer * 100 + 11); int(layer * 100 + 12); int(layer * 100 + 13)
                    int(layer * 100 + 21); int(layer * 100 + 22); int(layer * 100 + 23)
                }
                write(0x66); toByteArray()
            }
            val fixture = WireWriter().run {
                write(ByteArray(10)); write(byteArrayOf(87, 79, 76, 70, 77, 0, 0x55, 0, 0, 0))
                int(version); write(0x65)
                write(if (version >= 101) packedPayload(payload) else payload)
                toByteArray()
            }
            val map = WolfFormats.parseMap(fixture)
            for (layer in 0 until layers) {
                val offset = layer * 100
                assertArrayEquals(intArrayOf(offset + 11, offset + 21, offset + 12,
                    offset + 22, offset + 13, offset + 23), map.tiles.copyOfRange(layer * 6, layer * 6 + 6))
                assertEquals(offset + 11, map.tile(0, 0, layer))
                assertEquals(offset + 21, map.tile(1, 0, layer))
                assertEquals(offset + 12, map.tile(0, 1, layer))
                assertEquals(offset + 22, map.tile(1, 1, layer))
                assertEquals(offset + 13, map.tile(0, 2, layer))
                assertEquals(offset + 23, map.tile(1, 2, layer))
            }
        }
    }

    @Test fun mapLoopingComesFromTheSystemRegistryAndRawMapsStayUnconfigured() {
        val loops = intArrayOf(0, 1, 2, 3, 4, -1, Int.MAX_VALUE)
        val (systemProject, systemData) = loopRegistryFixture(loops)
        val (emptyProject, emptyData) = minimalDatabase()
        val files = mapOf(
            "Data/BasicData/Game.dat" to gameFixture(WolfEncoding.UTF8, 9),
            "Data/BasicData/CommonEvent.dat" to commonEnvelope(WireWriter().run { int(0); write(0x92); toByteArray() }, WolfEncoding.UTF8, 0x92),
            "Data/BasicData/DataBase.project" to emptyProject,
            "Data/BasicData/DataBase.dat" to emptyData,
            "Data/BasicData/CDataBase.project" to emptyProject,
            "Data/BasicData/CDataBase.dat" to emptyData,
            "Data/BasicData/SysDatabase.project" to systemProject,
            "Data/BasicData/SysDatabase.dat" to systemData,
            "Data/BasicData/TileSetData.dat" to WireWriter().run {
                write(byteArrayOf(0, 87, 0, 0, 79, 76, 0, 70, 77, 0)); write(209); int(0); write(0xcf); toByteArray()
            },
            "Data/MapData/test.mps" to minimalMap(),
        )
        val source = object : WolfAssetSource {
            override fun read(path: String) = files.getValue(path)
            override fun exists(path: String) = path in files
            override fun list(path: String) = emptyList<String>()
        }
        val parser = WolfParser(source)
        loops.indices.forEach { id ->
            val map = parser.loadMap(id)
            assertEquals("map$id loopX", loops[id] == 1 || loops[id] == 3, map.loopX)
            assertEquals("map$id loopY", loops[id] == 2 || loops[id] == 3, map.loopY)
        }
        val raw = WolfFormats.parseMap(minimalMap())
        assertFalse(raw.loopX); assertFalse(raw.loopY)
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

    @Test fun encryptedMapAliasRefusesBeforeReadingClearOrTreatingTheMapAsAbsent() {
        val (systemProject, systemData) = loopRegistryFixture(intArrayOf(0))
        val (emptyProject, emptyData) = minimalDatabase()
        val metadata = mapOf(
            "Data/BasicData/Game.dat" to gameFixture(WolfEncoding.UTF8, 9),
            "Data/BasicData/CommonEvent.dat" to commonEnvelope(WireWriter().run { int(0); write(0x92); toByteArray() }, WolfEncoding.UTF8, 0x92),
            "Data/BasicData/DataBase.project" to emptyProject,
            "Data/BasicData/DataBase.dat" to emptyData,
            "Data/BasicData/CDataBase.project" to emptyProject,
            "Data/BasicData/CDataBase.dat" to emptyData,
            "Data/BasicData/SysDatabase.project" to systemProject,
            "Data/BasicData/SysDatabase.dat" to systemData,
            "Data/BasicData/TileSetData.dat" to WireWriter().run {
                write(byteArrayOf(0, 87, 0, 0, 79, 76, 0, 70, 77, 0)); write(209); int(0); write(0xcf); toByteArray()
            },
        )
        for (clearPresent in listOf(true, false)) {
            val mapReads = mutableListOf<String>()
            val source = object : WolfAssetSource {
                override fun read(path: String): ByteArray {
                    if (path.startsWith("Data/MapData/")) mapReads += path
                    return metadata[path] ?: if (clearPresent && path == "Data/MapData/test.mps") minimalMap()
                        else throw java.io.FileNotFoundException(path)
                }
                override fun exists(path: String) = path in metadata || path == "Data/MapData/test.mps.wolfx" ||
                    clearPresent && path == "Data/MapData/test.mps"
                override fun list(path: String) = emptyList<String>()
            }
            val failure = assertThrows(WolfFormatException::class.java) { WolfParser(source).loadMap(0) }
            assertTrue(failure.message.orEmpty().contains("Carte WOLFX"))
            assertTrue(failure.message.orEmpty().contains("Data/MapData/test.mps.wolfx"))
            assertTrue("Neither map payload should be read", mapReads.isEmpty())
        }
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

    @Test fun commonCcAnd93DecodeIndependentCommandsRoutesExtensionsAndJapanese() {
        for (encoding in WolfEncoding.entries) {
            val fixture = commonFixture(encoding)
            for (version in listOf(0x93, 0xcc)) {
                val events = WolfFormats.parseCommonEvents(commonEnvelope(fixture.payload, encoding, version))
                assertEquals(2, events.size)
                val event = events.first()
                assertEquals(4, event.offset)
                assertEquals(7, event.id)
                assertEquals("魔女", event.name)
                assertEquals(0x12345670, event.conditionWord)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7), event.argumentSettings)
                assertEquals("説明：魔女の道", event.description)
                assertEquals(listOf("数値", "名前"), event.argumentNames)
                assertArrayEquals(byteArrayOf(2, 1), event.argumentModes)
                assertEquals(listOf(listOf("北", "南"), listOf("表\\ソ")), event.argumentStringOptions)
                assertArrayEquals(intArrayOf(0, -1), event.argumentNumericOptions[0])
                assertArrayEquals(intArrayOf(6, 2, 1), event.argumentNumericOptions[1])
                assertArrayEquals(intArrayOf(123, -7, 0, Int.MIN_VALUE, Int.MAX_VALUE), event.argumentDefaults)
                assertEquals(4, event.color)
                assertEquals(100, event.localVariableNames.size)
                assertEquals("最後", event.localVariableNames[99])
                assertEquals("追加情報", event.metadataExtra)
                assertEquals("結果", event.returnName)
                assertEquals(2, event.returnValue)
                val message = event.commands[0]
                assertEquals(101, message.opcode)
                assertEquals(2, message.indent)
                assertEquals(fixture.offsets.getValue("message"), message.offset)
                assertArrayEquals(intArrayOf(-1, 2_000_000), message.arguments)
                assertEquals(listOf("魔女の道", "\\v[2000000]"), message.strings)
                assertArrayEquals(byteArrayOf(0, 0x7f, 0xfe.toByte()), message.extension)
                assertNull(message.route)
                val move = event.commands[1]
                assertEquals(201, move.opcode)
                assertEquals(fixture.offsets.getValue("move"), move.offset)
                assertArrayEquals(intArrayOf(1_000_000), move.arguments)
                assertEquals(listOf("CharaChip\\魔女.png"), move.strings)
                assertArrayEquals(byteArrayOf(0x20, 0, 0xff.toByte()), move.extension)
                val route = move.route!!
                assertEquals(7, route.flags)
                assertArrayEquals(byteArrayOf(0, 3, 3, 3, 0), route.header)
                assertEquals(listOf(15, 50), route.commands.map { it.opcode })
                assertArrayEquals(intArrayOf(1, -2), route.commands[0].arguments)
                assertTrue(route.commands[1].arguments.isEmpty())
                assertEquals(9, events[1].id)
                assertEquals(fixture.offsets.getValue("second event"), events[1].offset)
                assertTrue(events[1].commands.isEmpty())
                assertNull(events[1].returnName)
                assertNull(events[1].returnValue)
            }
        }
    }

    @Test fun commonFooterIsIndependentAndDialectDoesNotLeakBetweenFiles() {
        val encoding = WolfEncoding.CP932
        // The primary readers validate >= 0x89, rather than version equality.
        for (footer in listOf(0x89, 0x8f, 0x90, 0x91, 0x92, 0xcc, 0xff)) {
            val fixture = commonFixture(encoding, footer = footer)
            assertEquals(2, WolfFormats.parseCommonEvents(commonEnvelope(fixture.payload, encoding, 0xcc)).size)
        }
        for (version in 0x8f..0x92) {
            val plain = commonFixture(encoding, extended = false)
            val events = WolfFormats.parseCommonEvents(commonEnvelope(plain.payload, encoding, version))
            assertTrue(events.first().commands.all { it.extension.isEmpty() })
            assertEquals("魔女", events.first().name)
            val packed = commonFixture(WolfEncoding.UTF8)
            assertEquals(2, WolfFormats.parseCommonEvents(commonEnvelope(packed.payload, WolfEncoding.UTF8, 0xcc)).size)
            assertCommonsEqual(events, WolfFormats.parseCommonEvents(commonEnvelope(plain.payload, encoding, version)))
        }
    }

    @Test fun commonCcRejectsTruncationInvalidLengthsMarkersAndEncoding() {
        val fixture = commonFixture(WolfEncoding.UTF8)
        val valid = commonEnvelope(fixture.payload, WolfEncoding.UTF8, 0xcc)
        for (size in valid.indices) rejects { WolfFormats.parseCommonEvents(valid.copyOf(size)) }
        rejects { WolfFormats.parseCommonEvents(valid + byteArrayOf(0)) }
        rejects { WolfFormats.parseCommonEvents(valid.clone().also { it[0] = 1 }) }
        rejects { WolfFormats.parseCommonEvents(valid.clone().also { it[6] = 1 }) }
        rejects { WolfFormats.parseCommonEvents(valid.clone().also { it[10] = 0xcd.toByte() }) }
        rejects { WolfFormats.parseCommonEvents(valid.clone().also { putInt(it, 11, Int.MAX_VALUE) }) }
        rejects { WolfFormats.parseCommonEvents(valid.clone().also { putInt(it, 15, Int.MAX_VALUE) }) }
        rejects { WolfFormats.parseCommonEvents(valid.clone().also { putInt(it, 11, fixture.payload.size + 1) }) }
        rejects { WolfFormats.parseCommonEvents(valid.clone().also { putInt(it, 15, valid.size - 20) }) }
        fun mutated(change: (ByteArray) -> Unit) = commonEnvelope(fixture.payload.clone().also(change), WolfEncoding.UTF8, 0xcc)
        rejects { WolfFormats.parseCommonEvents(mutated { it[it.lastIndex] = 0x88.toByte() }) }
        rejects { WolfFormats.parseCommonEvents(mutated { putInt(it, 0, Int.MAX_VALUE) }) }
        rejects { WolfFormats.parseCommonEvents(mutated { it[4] = 0 }) }
        rejects { WolfFormats.parseCommonEvents(mutated { it[fixture.offsets.getValue("message")] = 0 }) }
        rejects { WolfFormats.parseCommonEvents(mutated { it[fixture.offsets.getValue("route marker")] = 2 }) }
        rejects { WolfFormats.parseCommonEvents(mutated { it[fixture.offsets.getValue("route terminator")] = 0 }) }
        rejects { WolfFormats.parseCommonEvents(mutated { it[fixture.offsets.getValue("extension length")] = 0xff.toByte() }) }
        rejects { WolfFormats.parseCommonEvents(mutated { it[fixture.offsets.getValue("metadata marker")] = 0 }) }
        rejects { WolfFormats.parseCommonEvents(mutated { it[fixture.offsets.getValue("return marker")] = 0 }) }
        rejects { WolfFormats.parseCommonEvents(mutated { it[fixture.offsets.getValue("name bytes")] = 0xff.toByte() }) }
        rejects { WolfFormats.parseCommonEvents(commonEnvelope(fixture.payload + byteArrayOf(0), WolfEncoding.UTF8, 0xcc)) }
        rejects { WolfFormats.parseCommonEvents(valid, "small allocation quota", WolfParseBudget(maxAllocationBytes = fixture.payload.size.toLong() - 1)) }
        val cp932 = commonFixture(WolfEncoding.CP932)
        rejects { WolfFormats.parseCommonEvents(commonEnvelope(cp932.payload.clone().also {
            // A terminal lead byte is malformed windows-31j, not a replacement character.
            val start = cp932.offsets.getValue("name bytes")
            it[start + 2] = 'A'.code.toByte()
            it[start + 3] = 0x81.toByte()
        }, WolfEncoding.CP932, 0xcc)) }
    }

    @Test fun packedAndPlainMapEnvelopesPreserveModernLayerMetadata() {
        for (version in 100..103) {
            val fixture = mapFixture(version)
            val map = WolfFormats.parseMap(fixture)
            assertEquals(version, map.wireVersion)
            assertEquals("魔女の村", map.title)
            assertEquals(if (version == 103) 4 else 3, map.layerCount)
            assertEquals(if (version == 103) 0x12345678 else null, map.modernMetadata)
            assertArrayEquals(IntArray(4 * map.layerCount) {
                val layer = it / 4
                val position = it % 4
                32 + layer * 4 + (position % 2) * 2 + position / 2
            }, map.tiles)
            val page = map.events.single().pages.single()
            assertEquals("CharaChip\\魔女.png", page.graphicFile)
            assertEquals(4, page.featureCount)
            assertEquals(0, page.pageTransfer)
            assertEquals(101, page.commands.single().opcode)
            assertEquals(listOf("魔女の道"), page.commands.single().strings)
            assertArrayEquals(if (version == 103) byteArrayOf(0x11, 0x22) else byteArrayOf(), page.commands.single().extension)
        }
        val modern = mapFixture(103)
        for (size in modern.indices) rejects { WolfFormats.parseMap(modern.copyOf(size)) }
        rejects { WolfFormats.parseMap(modern + byteArrayOf(0)) }
        rejects { WolfFormats.parseMap(modern.clone().also { putInt(it, 20, 104) }) }
        rejects { WolfFormats.parseMap(modern.clone().also { putInt(it, 25, Int.MAX_VALUE) }) }
    }

    @Test fun databaseKnownVersionsPreserveBlocksAndValidatePackedFooter() {
        val (project, raw) = minimalDatabase()
        for (version in 0xc1..0xc4) {
            val payload = raw.copyOfRange(11, raw.size).also { it[it.lastIndex] = version.toByte() }
            val data = raw.copyOfRange(0, 10) + byteArrayOf(version.toByte()) +
                if (version == 0xc4) packedPayload(payload) else payload
            val database = WolfFormats.parseDatabase(project, data)
            assertEquals(version, database.version)
            assertEquals("hello", database.types.single().string(0, 0))
            assertEquals(-7, database.types.single().number(1, 1))
            if (version == 0xc4) {
                for (size in data.indices) rejects { WolfFormats.parseDatabase(project, data.copyOf(size)) }
                rejects { WolfFormats.parseDatabase(project, data + byteArrayOf(0)) }
                rejects { WolfFormats.parseDatabase(project, data.clone().also { putInt(it, 11, Int.MAX_VALUE) }) }
                val wrongFooter = payload.clone().also { it[it.lastIndex] = 0xc3.toByte() }
                rejects { WolfFormats.parseDatabase(project, raw.copyOfRange(0, 10) + byteArrayOf(0xc4.toByte()) + packedPayload(wrongFooter)) }
            }
        }
    }

    @Test fun gameBaseAndProStringLayoutsPreserveDeclaredVersionAndOpaqueKey() {
        for (encoding in WolfEncoding.entries) for (count in listOf(8, 9, 13, 14)) {
            val game = WolfFormats.parseGame(gameFixture(encoding, count))
            assertEquals("魔女の道", game.title)
            assertEquals(if (count == 8) "" else "副題", game.subtitle)
            assertEquals("CharaChip\\魔女.png", game.defaultHeroGraphic)
            assertEquals(count, game.strings.size)
            assertEquals(43, game.byteSettings.size)
            assertEquals(23, game.wordSettings.size)
            assertEquals(372, game.engineCompatibilityVersion)
            assertEquals(1280, game.width)
            assertEquals(720, game.height)
            assertEquals(60, game.fps)
            assertEquals(encoding, game.encoding)
            assertEquals("", game.strings[2])
            if (count >= 13) assertEquals("読込中", game.strings[11])
            if (count == 14) assertEquals("追加", game.strings[13])
        }
        val valid = gameFixture(WolfEncoding.UTF8, 14)
        for (size in valid.indices) rejects { WolfFormats.parseGame(valid.copyOf(size)) }
        rejects { WolfFormats.parseGame(valid + byteArrayOf(0)) }
        for (unknown in listOf(7, 10, 11, 12, 15)) {
            rejects { WolfFormats.parseGame(valid.clone().also { putInt(it, 57, unknown) }) }
        }
    }

    @Test fun officialPackedCommon93AndCcAliasesAreStructurallyEquivalentWithoutChangingOriginal() {
        val root = File(System.getProperty("wolf.fixtureRoot", "../build/wolf-research"), "sample3729/WOLF_RPG_Editor3")
        val file = File(root, "Data/BasicData/CommonEvent.dat")
        assumeTrue("Official research samples are optional local fixtures", file.isFile)
        val original = file.readBytes()
        assertEquals(0x93, original[10].toInt() and 0xff)
        val alias = original.clone().also { it[10] = 0xcc.toByte() }
        assertCommonsEqual(WolfFormats.parseCommonEvents(original), WolfFormats.parseCommonEvents(alias))
        assertArrayEquals(original, file.readBytes())
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

    @Test fun databaseNameOnlyRowsDoNotRequireBytesFromTheNextTypeOrFooter() {
        for (encoding in WolfEncoding.entries) for (version in 0xc1..0xc4) for (followingValues in listOf(false, true)) {
            val (project, data) = nameOnlyDatabase(encoding, version, followingValues = followingValues)
            val originalProject = project.copyOf(); val originalData = data.copyOf()
            val database = WolfFormats.parseDatabase(project, data, "SysDatabase")
            val names = database.types[0]
            assertEquals(701, names.rows.size)
            assertTrue(names.fields.isEmpty())
            names.rows.forEachIndexed { index, row ->
                assertEquals(index, row.id); assertEquals("名前$index", row.name)
                assertTrue(row.numbers.isEmpty()); assertTrue(row.strings.isEmpty())
            }
            if (followingValues) {
                assertEquals(42, database.types[1].number(0, 0))
                assertEquals("値", database.types[1].string(0, 1))
            }
            assertArrayEquals(originalProject, project); assertArrayEquals(originalData, data)
        }
    }

    @Test fun databaseNameOnlyRowsStillRequireProjectDefinitionsAndBoundedCounts() {
        for (version in 0xc1..0xc4) {
            for (declared in listOf(702, WolfFormats.MAX_RECORDS + 1, Int.MAX_VALUE, -1)) {
                val (project, data) = nameOnlyDatabase(WolfEncoding.CP932, version, declaredRows = declared)
                rejects { WolfFormats.parseDatabase(project, data, "SysDatabase") }
            }
            // A project may retain additional names when fewer rows are stored in the DAT.
            val (project, data) = nameOnlyDatabase(WolfEncoding.CP932, version, declaredRows = 2)
            val database = WolfFormats.parseDatabase(project, data)
            assertEquals(listOf("名前0", "名前1"), database.types[0].rows.map { it.name })
            assertEquals(42, database.types[1].number(0, 0))
        }
    }

    @Test fun databaseNameOnlyRowsStillValidateExactFooterAndPayloadBounds() {
        for (version in 0xc1..0xc4) {
            val (project, data) = nameOnlyDatabase(WolfEncoding.UTF8, version, followingValues = false)
            rejects { WolfFormats.parseDatabase(project, data.copyOf(data.size - 1)) }
            rejects { WolfFormats.parseDatabase(project, data + byteArrayOf(0)) }
            val (_, wrongFooter) = nameOnlyDatabase(WolfEncoding.UTF8, version, followingValues = false, footer = 0)
            rejects { WolfFormats.parseDatabase(project, wrongFooter) }
        }
        // A real numeric/string row still needs its own payload, even after an empty type.
        val (project, data) = nameOnlyDatabase(WolfEncoding.CP932, 0xc1)
        rejects { WolfFormats.parseDatabase(project, data.copyOf(data.size - 6) + byteArrayOf(0xc1.toByte())) }
    }

    @Test fun databaseNameOnlyRowsAreChargedToTheSharedAllocationBudget() {
        val (project, data) = nameOnlyDatabase(WolfEncoding.CP932, 0xc1)
        val failure = assertThrows(WolfFormatException::class.java) {
            WolfFormats.parseDatabase(project, data, "SysDatabase", WolfParseBudget(maxAllocationBytes = 80_000))
        }
        assertTrue(failure.message.orEmpty().contains("SysDatabase.dat:payload"))
        assertTrue(failure.message.orEmpty().contains("cumulative allocations"))
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
        assertEquals(16, old.passageForTile(0))
        assertEquals(32, modern.passageForTile(0))
        assertEquals(6, modern.passageForTile(704444))
        assertEquals(15, old.passageForTile(1604444))
        assertEquals(31, modern.passageForTile(3204444))
        assertEquals(0, modern.passageForTile(100000))
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
        assertEquals(if (encoding == WolfEncoding.CP932) 225 else 372, game.config.engineCompatibilityVersion)
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

    /** WolfTL Data::ReadDat reads zero values when fieldsSize is zero; names are in PROJECT. */
    private fun nameOnlyDatabase(
        encoding: WolfEncoding,
        version: Int,
        declaredRows: Int = 701,
        followingValues: Boolean = true,
        footer: Int = version,
    ): Pair<ByteArray, ByteArray> {
        val project = WireWriter(encoding).run {
            int(if (followingValues) 2 else 1)
            string("名前のみ"); int(0); int(701); repeat(701) { string("名前$it") }
            string(""); repeat(5) { int(0) }
            if (followingValues) {
                string("値付き"); int(2); string("number"); string("text"); int(1); string("row")
                string(""); int(2); write(ByteArray(2)); repeat(4) { int(0) }
            }
            toByteArray()
        }
        val payload = WireWriter(encoding).run {
            int(if (followingValues) 2 else 1)
            int(-2); int(0); int(0); int(declaredRows) // No per-row bytes for a name-only type.
            if (followingValues) {
                int(-2); int(0); int(2); int(1000); int(2000); int(1); int(42); string("値")
            }
            write(footer); toByteArray()
        }
        val header = byteArrayOf(0, 87, 0, 0, 79, 76, if (encoding == WolfEncoding.UTF8) 0x55 else 0, 70, 77, 0, version.toByte())
        return project to (header + if (version == 0xc4) packedPayload(payload) else payload)
    }

    private fun loopRegistryFixture(loops: IntArray): Pair<ByteArray, ByteArray> {
        val project = WireWriter(WolfEncoding.CP932).run {
            int(8)
            repeat(8) { type ->
                string("type$type")
                val fields = when (type) { 0 -> 4; 7 -> 3; else -> 0 }
                int(fields); repeat(fields) { string("field$it") }
                val rows = when (type) { 0 -> loops.size; 7 -> 1; else -> 0 }
                int(rows); repeat(rows) { string("row$it") }
                string(""); int(fields); write(ByteArray(fields))
                repeat(4) { int(0) }
            }
            toByteArray()
        }
        val data = WireWriter(WolfEncoding.CP932).run {
            write(byteArrayOf(0, 87, 0, 0, 79, 76, 0, 70, 77, 0)); write(0xc1); int(8)
            repeat(8) { type ->
                int(-2); int(0)
                val fields = when (type) { 0 -> 4; 7 -> 3; else -> 0 }
                int(fields)
                if (type == 0) { int(2000); int(1000); int(1001); int(1002) }
                else repeat(fields) { int(1000 + it) }
                val rows = when (type) { 0 -> loops.size; 7 -> 1; else -> 0 }
                int(rows)
                repeat(rows) { row ->
                    int(0); int(0); int(if (type == 0) loops[row] else 0)
                    if (type == 0) string("MapData/test.mps")
                }
            }
            write(0xc1); toByteArray()
        }
        return project to data
    }

    /** Independent wire assembler; no production reader/serializer is used. */
    private class WireWriter(val encoding: WolfEncoding = WolfEncoding.UTF8) : ByteArrayOutputStream() {
        val offsets = mutableMapOf<String, Int>()
        fun mark(label: String) { offsets[label] = size() }
        fun int(value: Int) { repeat(4) { write(value ushr (it * 8) and 255) } }
        fun short(value: Int) { write(value and 255); write(value ushr 8 and 255) }
        fun string(value: String) {
            val charset = if (encoding == WolfEncoding.UTF8) Charsets.UTF_8 else Charset.forName("windows-31j")
            val text = value.toByteArray(charset)
            int(text.size + 1); write(text); write(0)
        }
    }

    private data class CommonFixture(val payload: ByteArray, val offsets: Map<String, Int>)

    private fun commonFixture(encoding: WolfEncoding, extended: Boolean = true, footer: Int = 0xcc): CommonFixture {
        val writer = WireWriter(encoding)
        with(writer) {
            int(2); write(0x8e); int(7); int(0x12345670); write(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
            offsets["name bytes"] = size() + 4; string("魔女"); int(2)
            mark("message"); write(3); int(101); int(-1); int(2_000_000); write(2); write(2)
            string("魔女の道"); string("\\v[2000000]"); write(0)
            if (extended) { mark("extension length"); write(3); write(byteArrayOf(0, 0x7f, 0xfe.toByte())) }
            mark("move"); write(2); int(201); int(1_000_000); write(0); write(1); string("CharaChip\\魔女.png")
            mark("route marker"); write(1); write(byteArrayOf(0, 3, 3, 3, 0)); write(7); int(2)
            write(15); write(2); int(1); int(-2); mark("route terminator"); write(1); write(0)
            write(50); write(0); write(1); write(0)
            if (extended) { write(3); write(byteArrayOf(0x20, 0, 0xff.toByte())) }
            string(""); string("説明：魔女の道"); mark("metadata marker"); write(0x8f)
            int(2); string("数値"); string("名前"); int(2); write(byteArrayOf(2, 1))
            int(2); int(2); string("北"); string("南"); int(1); string("表\\ソ")
            int(2); int(2); int(0); int(-1); int(3); int(6); int(2); int(1)
            int(5); int(123); int(-7); int(0); int(Int.MIN_VALUE); int(Int.MAX_VALUE); write(0x90); int(4)
            repeat(100) { string(if (it == 99) "最後" else "") }; write(0x91); string("追加情報")
            mark("return marker"); write(0x92); string("結果"); int(2); write(0x92)
            mark("second event"); write(0x8e); int(9); int(0); write(ByteArray(7)); string(""); int(0)
            string(""); string(""); write(0x8f); int(0); int(0); int(0); int(0)
            int(5); repeat(5) { int(0) }; write(0x90); int(0); repeat(100) { string("") }
            write(0x91); string(""); write(0x91); write(footer)
        }
        return CommonFixture(writer.toByteArray(), writer.offsets.toMap())
    }

    private fun commonEnvelope(payload: ByteArray, encoding: WolfEncoding, version: Int): ByteArray {
        val header = byteArrayOf(0, 87, 0, 0, 79, 76, if (encoding == WolfEncoding.UTF8) 0x55 else 0, 70, 67, 0, version.toByte())
        return header + if (version == 0x93 || version == 0xcc) packedPayload(payload) else payload
    }

    private fun packedPayload(payload: ByteArray): ByteArray = WireWriter().run {
        // A valid raw LZ4 last sequence consisting only of literals. Its
        // explicit length makes compressed bounds independently testable.
        val block = WireWriter().run {
            write(minOf(payload.size, 15) shl 4)
            if (payload.size >= 15) {
                var length = payload.size - 15
                while (length >= 255) { write(255); length -= 255 }
                write(length)
            }
            write(payload); toByteArray()
        }
        int(payload.size); int(block.size); write(block); toByteArray()
    }

    private fun mapFixture(version: Int): ByteArray {
        val payload = WireWriter().run {
            string("魔女の村"); int(0); int(2); int(2); int(1)
            if (version == 103) { int(0x12345678); int(4) }
            repeat(if (version == 103) 16 else 12) { int(32 + it) }
            write(byteArrayOf(0x6f, 0x39, 0x30, 0, 0)); int(7); string("魔女"); int(1); int(1); int(1); int(0)
            write(0x79); int(0); string("CharaChip\\魔女.png"); write(byteArrayOf(2, 1, 100, 0))
            write(ByteArray(37)); write(ByteArray(4)); write(0); write(0); int(0); int(1)
            write(1); int(101); write(0); write(1); string("魔女の道"); write(0)
            if (version == 103) { write(2); write(byteArrayOf(0x11, 0x22)) }
            int(4); write(byteArrayOf(2, 0, 2, 0)); write(0x7a); write(0x70); write(0x66); toByteArray()
        }
        return WireWriter().run {
            write(ByteArray(10)); write(byteArrayOf(87, 79, 76, 70, 77, 0, 0x55, 0, 0, 0)); int(version); write(0x65)
            write(if (version >= 101) packedPayload(payload) else payload); toByteArray()
        }
    }

    private fun gameFixture(encoding: WolfEncoding, stringCount: Int): ByteArray = WireWriter(encoding).run {
        write(byteArrayOf(0, 87, 0, 0, 79, 76, 0, 70, 77, if (encoding == WolfEncoding.UTF8) 0x55 else 0))
        val settings = ByteArray(43).also { it[0] = 16; it[1] = 4; it[2] = 4; it[4] = 60; it[7] = 3 }
        int(settings.size); write(settings); int(stringCount)
        val text = listOf("魔女の道", "0000-0000", "", "font", "sub1", "sub2", "sub3", "CharaChip\\魔女.png",
            "副題", "Picture\\読込.png", "Picture\\目盛.png", "読込中", "ゲーム", "追加")
        repeat(stringCount) {
            if (it == 2) { int(3); write(byteArrayOf(0xff.toByte(), 0, 0x81.toByte())) } else string(text[it])
        }
        val fileSizeOffset = size(); int(0); int(0); int(23)
        repeat(23) { short(when (it) { 16 -> 1280; 17 -> 720; 18 -> 372; else -> it }) }
        int(0); int(0); write(byteArrayOf(17, 42, 99, 0xc4.toByte()))
        toByteArray().also { putInt(it, fileSizeOffset, it.size - 1) }
    }

    private fun putInt(data: ByteArray, offset: Int, value: Int) {
        repeat(4) { data[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private fun assertCommonsEqual(expected: List<WolfCommon>, actual: List<WolfCommon>) {
        assertEquals(expected.size, actual.size)
        for ((a, b) in expected.zip(actual)) {
            assertEquals(a.index, b.index); assertEquals(a.id, b.id); assertEquals(a.name, b.name)
            assertEquals(a.conditionWord, b.conditionWord); assertArrayEquals(a.argumentSettings, b.argumentSettings)
            assertEquals(a.metadataString, b.metadataString); assertEquals(a.description, b.description)
            assertEquals(a.argumentNames, b.argumentNames); assertArrayEquals(a.argumentModes, b.argumentModes)
            assertEquals(a.argumentStringOptions, b.argumentStringOptions)
            assertEquals(a.argumentNumericOptions.size, b.argumentNumericOptions.size)
            a.argumentNumericOptions.zip(b.argumentNumericOptions).forEach { (x, y) -> assertArrayEquals(x, y) }
            assertArrayEquals(a.argumentDefaults, b.argumentDefaults); assertEquals(a.color, b.color)
            assertEquals(a.localVariableNames, b.localVariableNames); assertEquals(a.metadataExtra, b.metadataExtra)
            assertEquals(a.returnName, b.returnName); assertEquals(a.returnValue, b.returnValue); assertEquals(a.offset, b.offset)
            assertEquals(a.commands.size, b.commands.size)
            for ((x, y) in a.commands.zip(b.commands)) {
                assertEquals(x.opcode, y.opcode); assertEquals(x.indent, y.indent); assertEquals(x.offset, y.offset)
                assertArrayEquals(x.arguments, y.arguments); assertEquals(x.strings, y.strings); assertArrayEquals(x.extension, y.extension)
                assertEquals(x.route == null, y.route == null)
                if (x.route != null && y.route != null) {
                    assertEquals(x.route.flags, y.route.flags); assertArrayEquals(x.route.header, y.route.header)
                    assertEquals(x.route.commands.size, y.route.commands.size)
                    for ((r, s) in x.route.commands.zip(y.route.commands)) {
                        assertEquals(r.opcode, s.opcode); assertArrayEquals(r.arguments, s.arguments)
                    }
                }
            }
        }
    }

    private fun rejects(action: () -> Any?) {
        try { action(); fail("Expected WolfFormatException") }
        catch (_: WolfFormatException) { }
    }
}
