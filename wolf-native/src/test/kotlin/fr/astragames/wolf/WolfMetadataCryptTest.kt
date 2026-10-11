package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfMetadataCryptTest {
    /** Ciphertext generated with the pinned C++ WolfTL routines, not the Kotlin port. */
    private val reference = javaClass.getResourceAsStream("/wolf-pro-metadata-vectors.txt")!!
        .bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith('#') }
    private val fixtures = javaClass.getResourceAsStream("/wolf-pro-structured-fixtures.txt")!!
        .bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith('#') }
        .associate { it.substringBefore(' ') to hex(it.substringAfter(' ')) }

    @Test fun gameFormatsDecodeAllProProfilesIncludingZeroFirstSeedAndNonAscii() {
        for (version in 0x54..0x57) {
            val input = fixtures.getValue("Gamev$version")
            if (version > 0x54) assertEquals(0, input[0].toInt())
            val original = input.copyOf()
            val game = WolfFormats.parseGame(input)
            assertEquals(WolfEncoding.UTF8, game.encoding)
            assertEquals("魔女の道", game.title); assertEquals("副題", game.subtitle)
            assertEquals("CharaChip\\魔女.png", game.defaultHeroGraphic)
            assertEquals(16, game.tileSize); assertEquals(60, game.fps)
            assertEquals(1280, game.width); assertEquals(720, game.height)
            assertEquals(372, game.engineCompatibilityVersion)
            assertEquals(14, game.strings.size)
            assertEquals("Picture\\読込.png", game.strings[9])
            assertEquals("追加", game.strings[13])
            assertArrayEquals(original, input)
        }
    }

    @Test fun commonFormatsPreserveProtectedCommandsRoutesExtensionsAndText() {
        for (version in 0x54..0x57) {
            val events = WolfFormats.parseCommonEvents(fixtures.getValue("Commonv$version"))
            assertEquals(1, events.size)
            val event = events.single()
            assertEquals(7, event.id); assertEquals("魔女", event.name)
            assertEquals(0x12345670, event.conditionWord)
            assertEquals(listOf("数値", "名前"), event.argumentNames)
            assertEquals("説明：魔女の道", event.description)
            assertEquals("最後", event.localVariableNames.last())
            assertEquals("結果", event.returnName); assertEquals(2, event.returnValue)
            assertEquals(2, event.commands.size)
            val message = event.commands[0]
            assertEquals(101, message.opcode); assertEquals(2, message.indent)
            assertArrayEquals(intArrayOf(-1,2000000), message.arguments)
            assertEquals(listOf("魔女の道", "\\v[2000000]"), message.strings)
            val move = event.commands[1]
            assertEquals(201, move.opcode); assertEquals("CharaChip\\魔女.png", move.strings.single())
            val route = move.route!!
            assertEquals(7, route.flags)
            assertArrayEquals(byteArrayOf(0,3,3,3,0), route.header)
            assertEquals(listOf(15,50), route.commands.map { it.opcode })
            assertArrayEquals(intArrayOf(1,-2), route.commands[0].arguments)
            assertTrue(move.offset > message.offset)
            assertArrayEquals(if (version == 0x57) byteArrayOf(0,127,-2) else byteArrayOf(), message.extension)
            assertArrayEquals(if (version == 0x57) byteArrayOf(32,0,-1) else byteArrayOf(), move.extension)
        }
    }

    @Test fun protectedDatabaseAndProjectStayPairedAcrossVersionSeedRules() {
        for (version in 0x54..0x57) {
            val project = fixtures.getValue("Projectv$version")
            val data = fixtures.getValue("Databasev$version")
            val projectOriginal = project.copyOf(); val dataOriginal = data.copyOf()
            val db = WolfFormats.parseDatabase(project, data)
            assertEquals(WolfEncoding.UTF8, db.encoding)
            assertEquals(if (version == 0x57) 0xc4 else 0xc3, db.version)
            val type = db.types.single()
            assertEquals("属性", type.name); assertEquals("説明", type.description)
            assertEquals(listOf("文","第二","第一"), type.fields.map { it.name })
            assertEquals(listOf(WolfValueType.STRING, WolfValueType.NUMBER, WolfValueType.NUMBER), type.fields.map { it.valueType })
            assertEquals(listOf(0,1,0), type.fields.map { it.index })
            assertEquals(listOf(0,9,12), type.fields.map { it.defaultValue })
            assertEquals("行零", type.rows[0].name); assertEquals("こんにちは", type.rows[0].strings.single())
            assertArrayEquals(intArrayOf(11,22), type.rows[0].numbers)
            assertArrayEquals(intArrayOf(-3,-7), type.rows[1].numbers)
            assertArrayEquals(projectOriginal, project); assertArrayEquals(dataOriginal, data)
        }
        rejects { WolfFormats.parseDatabase(fixtures.getValue("Projectv84"), fixtures.getValue("Databasev87")) }
        // Calling another decoder cannot change a database's project RNG state.
        WolfFormats.parseGame(fixtures.getValue("Gamev87"))
        assertEquals("属性", WolfFormats.parseDatabase(fixtures.getValue("Projectv84"), fixtures.getValue("Databasev84")).types.single().name)
    }

    @Test fun protectedTileset35PreservesAllAutoSlotsAndPassageWords() {
        val set = WolfFormats.parseTilesets(fixtures.getValue("Tilesetv87")).single()
        assertEquals("森", set.name); assertEquals("TileSet\\森.png", set.baseImage)
        assertEquals(31, set.autoImages.size)
        assertEquals("TileSet\\自動0.png", set.autoImages.first())
        assertEquals("TileSet\\自動30.png", set.autoImages.last())
        // Wire autotile N selects slot N-1; normal chip zero follows 31 auto slots + the reserved slot.
        assertEquals(0x210, set.passageForTile(204444))
        assertEquals(0x300, set.passageForTile(0)); assertEquals(0x40, set.passageForTile(1))
        assertEquals(3, set.tagForTile(204444)); assertEquals(255, set.tagForTile(1))
    }

    @Test fun protectedFormatEntriesStillRejectPartialBodiesAndFutureProtection() {
        for (version in 0x54..0x57) {
            for (label in listOf("Game", "Common", "Database")) {
                val input = fixtures.getValue("${label}v$version")
                fun parse(bytes: ByteArray): Any = when (label) {
                    "Game" -> WolfFormats.parseGame(bytes)
                    "Common" -> WolfFormats.parseCommonEvents(bytes)
                    else -> WolfFormats.parseDatabase(fixtures.getValue("Projectv$version"), bytes)
                }
                for (size in listOf(0,1,9,10,19,20,142,input.size/2,input.size-1).filter { it < input.size }.distinct())
                    rejects { parse(input.copyOf(size)) }
                rejects { parse(input.clone().also { it[5] = 0x58 }) }
                rejects { parse(input.clone().also { it[3] = (it[3].toInt() xor 1).toByte(); it[8] = (it[8].toInt() xor 1).toByte() }) }
            }
        }
        val input = fixtures.getValue("Tilesetv87")
        rejects { WolfFormats.parseTilesets(input.copyOf(input.size-1)) }
        rejects { WolfFormats.parseTilesets(input.clone().also { it[5] = 0x58 }) }
    }

    @Test fun allKnownNoPasswordProProfilesMatchCppByteForByte() {
        val plain = ByteArray(466) { ((37 * it + 11) and 255).toByte() }
        val kinds = mapOf(0 to WolfMetadataKind.GAME, 1 to WolfMetadataKind.COMMON,
            2 to WolfMetadataKind.DATABASE, 5 to WolfMetadataKind.TILESET)
        var checked = 0
        for (line in reference.filter { it.startsWith("kind") }) {
            val label = line.substringBefore(' ')
            val kind = kinds.getValue(label.substringAfter("kind").substringBefore('v').toInt())
            val version = label.substringAfter('v').toInt()
            val encrypted = hex(line.substringAfter(' '))
            val original = encrypted.copyOf()
            val result = WolfMetadataCrypt.decode(encrypted, kind, label, WolfParseBudget())
            assertTrue(label, result.encrypted)
            assertArrayEquals(label, kind.header(true) + plain.copyOfRange(10, plain.size), result.bytes)
            assertArrayEquals("input preservation: $label", original, encrypted)
            assertEquals(label, if (kind != WolfMetadataKind.DATABASE) null else
                when (version) { 0x54 -> -91; 0x55, 0x56 -> 179; else -> 0 }, result.projectKey)
            checked++
        }
        assertEquals(13, checked)
    }

    @Test fun modifiedSha512MatchesIndependentReferenceIncludingHighSaltBytes() {
        val rows = reference.filter { it.startsWith("hash ") }
        assertEquals(3, rows.size)
        for (line in rows) {
            val fields = line.split(' ')
            assertEquals(fields[2], WolfMetadataHash.digest(hex(fields[1])))
        }
    }

    @Test fun rejectUnknownProfilesTruncatedHeadersAndAllocationQuotas() {
        for (line in reference.filter { it.startsWith("kind") }) {
            val label = line.substringBefore(' ')
            val kind = when (label.substringAfter("kind").substringBefore('v').toInt()) {
                0 -> WolfMetadataKind.GAME; 1 -> WolfMetadataKind.COMMON
                2 -> WolfMetadataKind.DATABASE; else -> WolfMetadataKind.TILESET
            }
            val encrypted = hex(line.substringAfter(' '))
            val minimum = if (encrypted[5].toInt() == 0x54) 20 else 143
            for (size in 1 until minimum) rejects {
                WolfMetadataCrypt.decode(encrypted.copyOf(size), kind, label, WolfParseBudget())
            }
            rejects { WolfMetadataCrypt.decode(encrypted, kind, label, WolfParseBudget(maxAllocationBytes = 10)) }
            rejects { WolfMetadataCrypt.decode(encrypted.clone().also { it[5] = 0x58 }, kind, label, WolfParseBudget()) }
        }
        val tiles = reference.single { it.startsWith("kind5v87 ") }.substringAfter(' ').let(::hex)
        for (v in 0x54..0x56) rejects {
            WolfMetadataCrypt.decode(tiles.clone().also { it[5] = v.toByte() }, WolfMetadataKind.TILESET, "tiles", WolfParseBudget())
        }
        val db31 = reference.single { it.startsWith("kind2v84 ") }.substringAfter(' ').let(::hex)
        rejects { WolfMetadataCrypt.decode(db31.clone().also { it[7] = 0 }, WolfMetadataKind.DATABASE, "db", WolfParseBudget()) }
    }

    @Test fun databaseProjectKeysHaveIndependentSignedAndUnsignedSeedSemantics() {
        val plain = ByteArray(512) { ((19 * it + 5) and 255).toByte() }
        for (key in intArrayOf(-91, 179, 0)) {
            var state = key.toLong() and 0xffffffffL
            val encrypted = plain.map { value ->
                state = (state * 214013 + 2531011) and 0xffffffffL
                (value.toInt() xor ((state ushr 16).toInt() and 32767)).toByte()
            }.toByteArray()
            val original = encrypted.copyOf()
            assertArrayEquals(plain, WolfMetadataCrypt.project(encrypted, key, "project", WolfParseBudget()))
            assertArrayEquals(original, encrypted)
            rejects { WolfMetadataCrypt.project(encrypted, key, "project", WolfParseBudget(maxAllocationBytes = 10)) }
        }
        assertSame(plain, WolfMetadataCrypt.project(plain, null, "clear", WolfParseBudget()))
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun rejects(action: () -> Any?) {
        try { action(); fail("Expected a WolfFormatException") } catch (_: WolfFormatException) { }
    }
}
