package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException

/** Admission uses real resource names; it never reads or invents pixels during the audit. */
class WolfCapabilityResourcesTest {
    @Test fun encryptedAliasIsAdmittedAndTheSameRuntimeReaderDecodesItsRealDimensions() {
        val encrypted = resource("quadrants.png.wolfx")
        val clear = resource("quadrants.png")
        val expected = requireNotNull(WolfImageHeader.dimensions(clear))
        val source = StrictSource(mapOf("Data/Picture/alias.png.wolfx" to encrypted))
        val f = fixture(listOf(picture("Picture/alias.png"), size(2, 0), size(3, 1)), source = source)
        assertTrue(f.runtime.inspectCapabilities().description(), f.runtime.inspectCapabilities().supported)
        assertEquals(0, source.reads)
        f.runtime.tick()
        assertEquals(expected.first, f.runtime.snapshotState().numbers[0])
        assertEquals(expected.second, f.runtime.snapshotState().numbers[1])
    }

    @Test fun explicitProtectedPathAndProtectedPrecedenceMatchTheResourceReader() {
        val bytes = resource("quadrants.png.wolfx")
        val expected = requireNotNull(WolfImageHeader.dimensions(resource("quadrants.png")))
        for (path in listOf("Picture/alias.png", "Picture/alias.png.wolfx")) {
            val source = StrictSource(mapOf("Data/Picture/alias.png.wolfx" to bytes,
                "Data/Picture/alias.png" to WolfMapTextureControllerTest.png(17, 13)))
            val f = fixture(listOf(picture(path), size(2, 0), size(3, 1)), source = source)
            assertTrue(f.runtime.inspectCapabilities().supported)
            f.runtime.tick()
            assertEquals(expected.first, f.runtime.snapshotState().numbers[0])
            assertEquals(expected.second, f.runtime.snapshotState().numbers[1])
        }
    }

    @Test fun presenceDoesNotCertifyAnUnavailableEncryptionKeyOrFalsePayload() {
        for (bytes in listOf(resource("quadrants.png.string-key.wolfx"), byteArrayOf(1, 2, 3))) {
            val f = fixture(listOf(picture("Picture/alias.png"), size(2, 0)),
                source = StrictSource(mapOf("Data/Picture/alias.png.wolfx" to bytes)))
            assertTrue(f.runtime.inspectCapabilities().supported)
            val error = assertThrows(WolfRuntimeException::class.java) { f.runtime.tick() }
            assertTrue(error.message!!, error.message!!.contains("WOLFX") || error.message!!.contains("Format d'image"))
            assertTrue(f.runtime.snapshotState().numbers.isEmpty())
        }
    }

    @Test fun gameRootAndDataRelativeParentPathsResolveToTheSameSavedImage() {
        val bytes = WolfMapTextureControllerTest.png(17, 13)
        for (path in listOf("../Save/shot.png", "game-root:Save/shot.png")) {
            val f = fixture(listOf(picture(path), size(2, 0), size(3, 1)),
                source = StrictSource(mapOf("Save/shot.png" to bytes)))
            assertTrue(f.runtime.inspectCapabilities().description(), f.runtime.inspectCapabilities().supported)
            f.runtime.tick()
            assertEquals(mapOf(0 to 17, 1 to 13), f.runtime.snapshotState().numbers)
            assertEquals("game-root:Save/shot.png", f.runtime.frame().pictures.single().path)
        }
    }

    @Test fun dynamicStringFilenameIsResolvedAtShowInsteadOfUsedAsAnInventoryAddress() {
        val source = StrictSource(mapOf("Data/Picture/hero.png" to WolfMapTextureControllerTest.png(19, 11)))
        val f = fixture(listOf(command(122, intArrayOf(3_000_000, 0), listOf("hero.png")),
            picture("Picture/\\s[0]"), size(2, 0)), source = source)
        assertTrue(f.runtime.inspectCapabilities().description(), f.runtime.inspectCapabilities().supported)
        assertTrue(source.queries.none { it.contains("\\s[") })
        f.runtime.tick()
        assertEquals("Data/Picture/hero.png", f.runtime.frame().pictures.single().path)
        assertEquals(19, f.runtime.snapshotState().numbers[0])
    }

    @Test fun nestedDatabaseFilenameUsesTheRuntimeLexerWithoutLookingUpLexicalPlaceholders() {
        val source = StrictSource(mapOf("Data/Picture/hero.png" to WolfMapTextureControllerTest.png(19, 11)))
        val field = WolfDatabaseField("file", WolfValueType.STRING, 0, 0, "", emptyList(), intArrayOf(), 0)
        val database = WolfDatabase("fixture", WolfEncoding.UTF8, 1, listOf(WolfDatabaseType(0, "Pictures", "",
            listOf(field), listOf(WolfDatabaseRow(0, "Hero", intArrayOf(), listOf("Picture/hero.png"))), 0, null)))
        val f = fixture(listOf(picture("\\udb[\\v[0]:\\v[1]:file]"), size(2, 0)), source = source,
            databases = mapOf(WolfDatabaseKind.USER to database))
        assertTrue(f.runtime.inspectCapabilities().description(), f.runtime.inspectCapabilities().supported)
        assertTrue(source.queries.none { it.contains("udb[") || it.contains("v[") })
        f.runtime.tick()
        assertEquals("Data/Picture/hero.png", f.runtime.frame().pictures.single().path)
        assertEquals(19, f.runtime.snapshotState().numbers[0])
    }

    @Test fun missingDynamicResourceStillFailsWhenItsActualNameIsRequired() {
        val f = fixture(listOf(command(122, intArrayOf(3_000_000, 0), listOf("missing.png")),
            picture("Picture/\\s[0]"), size(2, 0)))
        assertTrue(f.runtime.inspectCapabilities().supported)
        val error = assertThrows(WolfRuntimeException::class.java) { f.runtime.tick() }
        assertTrue(error.message!!, error.message!!.contains("Data/Picture/missing.png"))
    }

    @Test fun quotedVariableLookingFilenameIsLiteralAndMissingAssetsRemainBlocking() {
        val f = fixture(listOf(picture("Picture/\\\\s[0].png")))
        assertFalse(f.runtime.inspectCapabilities().supported)
        assertTrue(f.runtime.inspectCapabilities().issues.any { it.reason == "Ressource de pic absente : Data/Picture/s[0].png" })
        val escaped = fixture(listOf(picture("../../outside.png")))
        assertTrue(escaped.runtime.inspectCapabilities().issues.any { it.reason == "Chemin de pic invalide" })
    }

    @Test fun fullAuditRetainsMissingAssetsInUnusedCommonEventsAndOtherMaps() {
        val common = common(71, listOf(picture("Picture/library.png")))
        val other = map("other", listOf(picture("Picture/other.png")))
        val f = fixture(emptyList(), commons = listOf(common), extraMaps = mapOf(1 to other))
        val report = f.runtime.inspectCapabilities(includeAllMaps = true)
        assertFalse(report.supported)
        assertTrue(report.inspectedAllMaps)
        assertEquals(2, report.scannedMapCount)
        assertTrue(report.issues.any { it.file == "CommonEvent.dat" && it.eventId == 71 && "library.png" in it.reason })
        assertTrue(report.issues.any { it.file == "other" && "other.png" in it.reason })
    }

    @Test fun issueQuotaBoundsMemoryButDoesNotStopInspectionOfLaterMaps() {
        val extra = (1..525).associateWith { id -> map("map$id", listOf(command(10_000 + id))) }
        val f = fixture(listOf(command(10_000)), extraMaps = extra)
        val report = f.runtime.inspectCapabilities(includeAllMaps = true)
        assertFalse(report.supported)
        assertFalse(report.inspectedAllMaps) // The diagnostic inventory, explicitly truncated, is incomplete.
        assertEquals(526, report.scannedMapCount)
        assertEquals((0..525).toList(), f.loadedMaps)
        assertEquals(513, report.issues.size)
        assertTrue(report.issues.last().reason.contains("limité à 512"))
    }

    @Test fun unreadableMapDoesNotHideErrorsInTheFollowingMap() {
        val f = fixture(emptyList(), extraMaps = mapOf(1 to map("bad", emptyList()),
            2 to map("after", listOf(picture("Picture/missing.png")))), unreadableMaps = setOf(1))
        val report = f.runtime.inspectCapabilities(includeAllMaps = true)
        assertEquals(3, report.scannedMapCount)
        assertTrue(report.issues.any { it.reason.startsWith("Carte non inspectable") })
        assertTrue(report.issues.any { it.file == "after" && it.reason.contains("Ressource de pic absente") })
        assertEquals(listOf(0, 1, 2), f.loadedMaps)
    }

    @Test fun movementFrequencyIsAnUnsignedRawByteAndInvalidSpeedOrModeRemainRefused() {
        val f = fixture(emptyList(), movement = byteArrayOf(3, 3, 255.toByte(), 0))
        assertTrue(f.runtime.inspectCapabilities().description(), f.runtime.inspectCapabilities().supported)
        f.runtime.tick()
        assertEquals(255, f.runtime.snapshotState().actors.single().motion!!.frequency)
        for (movement in listOf(byteArrayOf(3, 7, 3, 0), byteArrayOf(3, 3, 3, 4), byteArrayOf(7, 3, 3, 0))) {
            val bad = fixture(emptyList(), movement = movement)
            assertFalse(bad.runtime.inspectCapabilities().supported)
            assertTrue(bad.runtime.inspectCapabilities().issues.any { it.reason == "Mode autonome de page non implémenté" })
        }
    }

    @Test fun randomAndTowardsHeroModesAreAdmittedOnlyAlongsideTheirRealRuntimeMovement() {
        for (mode in listOf(2, 3)) {
            val f = fixture(emptyList(), movement = byteArrayOf(3, 3, 0, mode.toByte()))
            assertTrue(f.runtime.inspectCapabilities().description(), f.runtime.inspectCapabilities().supported)
            // The normal save flow starts after the page and its actors have been initialized.
            f.runtime.tick()
            f.runtime.restoreState(f.runtime.snapshotState().copy(randomState = 1))
            val before = f.runtime.snapshotState().actors.single()
            var moved = false
            var closest = distance(before)
            repeat(60) {
                f.runtime.tick()
                val actor = f.runtime.snapshotState().actors.single()
                moved = moved || actor.x != before.x || actor.y != before.y
                closest = minOf(closest, distance(actor))
            }
            val after = f.runtime.snapshotState().actors.single()
            assertEquals(mode, after.motion!!.autonomousMode)
            assertTrue("mode$mode must actually move", moved)
            if (mode == 3) assertTrue("mode3 must approach the nearby hero", closest < distance(before))
        }
    }

    @Test fun everyCommandDiagnosticReceivesOnlyItsBoundedWireOperands() {
        val unknown = command(10_000, IntArray(40) { if (it == 0) -1 else it }, listOf("private dialogue"))
            .copy(extension = ByteArray(80) { 0xff.toByte() }, route = WolfRoute(9,
                List(20) { WolfRouteCommand(10_001 + it, intArrayOf(it)) }))
        val missingVariable = command(121, intArrayOf(42_000_001, 1, 0, 12))
        val report = fixture(listOf(unknown, missingVariable)).runtime.inspectCapabilities()
        assertFalse(report.supported)
        val unsupported = report.issues.filter { it.opcode == unknown.opcode }
        assertTrue(unsupported.isNotEmpty())
        unsupported.forEach { issue ->
            assertEquals(WolfDiagnosticOperands.command(unknown), issue.operands)
            assertTrue(issue.operands!!.contains("-1(0xffffffff)"))
            assertTrue(issue.operands.contains("…(40 mots)"))
            assertTrue(issue.operands.contains("…(80 octets)"))
            assertTrue(issue.operands.contains("…(20 commandes)"))
            assertFalse(issue.operands.contains("private dialogue"))
            assertFalse(issue.operands.contains("10017[")) // Only the first16 route records.
        }
        val variables = report.issues.filter { it.opcode == missingVariable.opcode }
        assertTrue(variables.isNotEmpty())
        variables.forEach { assertEquals(WolfDiagnosticOperands.command(missingVariable), it.operands) }
        assertFalse(report.description().contains("args=["))
        assertTrue(report.description(includeOperands = true).contains("args=["))
    }

    @Test fun pageDiagnosticsRetainTheirPageBytesAndDoNotLeakTheLastCommandContext() {
        val missing = command(10_000, intArrayOf(17))
        val other = map("invalid-page", listOf(command(10_001, intArrayOf(23))), byteArrayOf(3, 7, 255.toByte(), 4))
        val page = other.events.single().pages.single().copy(index = 5,
            route = WolfRoute(9, listOf(WolfRouteCommand(10_002, intArrayOf(29)))))
        val withPage = other.copy(events = listOf(other.events.single().copy(pages = listOf(page))))
        val report = fixture(listOf(missing), extraMaps = mapOf(1 to withPage)).runtime.inspectCapabilities(includeAllMaps = true)
        val pageIssues = report.issues.filter { it.file == other.name && it.opcode in setOf(-1, 201) }
        assertEquals(2, pageIssues.size)
        pageIssues.forEach { issue ->
            assertEquals(WolfDiagnosticOperands.page(page), issue.operands)
            assertTrue(issue.operands!!.contains("page=5; movement=0307ff04"))
            assertTrue(issue.operands.contains("routeFlags=0x9"))
            assertTrue(issue.operands.contains("10002[29(0x1d)]"))
            assertFalse(issue.operands.contains("args=[17"))
        }
        assertEquals(WolfDiagnosticOperands.command(page.commands.single()),
            report.issues.single { it.file == other.name && it.opcode == 10_001 }.operands)
    }

    @Test fun failuresOutsidePagesDoNotInheritWireDataFromPreviouslyInspectedCommands() {
        val missing = command(10_000, intArrayOf(17))
        val noTileset = map("no-tileset", emptyList()).copy(tilesetId = 99)
        val report = fixture(listOf(missing), extraMaps = mapOf(1 to noTileset, 2 to map("unreadable", emptyList())),
            unreadableMaps = setOf(2)).runtime.inspectCapabilities(includeAllMaps = true)
        assertNotNull(report.issues.single { it.opcode == missing.opcode }.operands)
        assertNull(report.issues.single { it.reason == "Tileset absent" }.operands)
        assertNull(report.issues.single { it.reason.startsWith("Carte non inspectable") }.operands)
    }

    private fun distance(actor: WolfSnapshot.CharacterState) = kotlin.math.abs(actor.x - 8f) + kotlin.math.abs(actor.y - 8f)
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/wolf-map-textures/$name")).use { it.readBytes() }
    private class StrictSource(private val assets: Map<String, ByteArray> = emptyMap()) : WolfAssetSource {
        val queries = mutableListOf<String>()
        var reads = 0; private set
        override fun exists(path: String): Boolean { queries += path; return path in assets }
        override fun read(path: String): ByteArray { reads++; return assets[path] ?: throw FileNotFoundException(path) }
        override fun list(path: String) = emptyList<String>()
    }
    private data class Fixture(val runtime: WolfRuntime, val loadedMaps: List<Int>)
    private fun fixture(commands: List<WolfCommand>, source: StrictSource = StrictSource(),
        commons: List<WolfCommon> = emptyList(), extraMaps: Map<Int, WolfMap> = emptyMap(),
        databases: Map<WolfDatabaseKind, WolfDatabase> = emptyMap(), movement: ByteArray = byteArrayOf(3, 3, 3, 0),
        unreadableMaps: Set<Int> = emptySet()): Fixture {
        val config = WolfGameConfig("Capability fixture", "", WolfEncoding.UTF8, 16, 96, 96, 60, "", 4, 4, 3,
            "sans", emptyList(), ByteArray(40).also { it[8] = 1; it[9] = 1 }, IntArray(20).also {
                words -> repeat(7) { speed -> words[1 + speed * 2] = 32; words[2 + speed * 2] = 32 }; words[18] = 369
            }, emptyList(), 0xc4)
        val first = map("first", commands, movement)
        val data = WolfGameData(config, commons, databases,
            listOf(WolfTileset(0, "", "", emptyList(), byteArrayOf(), IntArray(64))),
            listOf(WolfMapReference(0, "first", "first.mps")) + extraMaps.keys.map { WolfMapReference(it, "map$it", "map$it.mps") },
            WolfStartPosition(0, 8, 8))
        val loaded = mutableListOf<Int>()
        val runtime = WolfRuntime.fromData(source, data) { id ->
            loaded += id
            if (id in unreadableMaps) throw WolfFormatException("authored unreadable map$id")
            if (id == 0) first else extraMaps.getValue(id)
        }
        return Fixture(runtime, loaded)
    }
    private fun map(name: String, commands: List<WolfCommand>, movement: ByteArray = byteArrayOf(3, 3, 3, 0)): WolfMap {
        val page = WolfPage(0, -1, "", 2, 1, 255, 0, ByteArray(37).also { it[0] = if (commands.isEmpty()) 0 else 1 },
            movement, 3, WolfRoute(2, emptyList()), commands, 3, byteArrayOf(0, 0, 0), null, 0)
        return WolfMap(name, name, 103, WolfEncoding.UTF8, 0, 16, 16, 3, IntArray(768) { 100000 },
            listOf(WolfEvent(0, "fixture", 4, 4, listOf(page))), 0x69, 0)
    }
    private fun command(opcode: Int, arguments: IntArray = intArrayOf(), strings: List<String> = emptyList()) =
        WolfCommand(opcode, arguments, 0, strings, offset = 0x1234)
    private fun picture(path: String) = command(150, intArrayOf(0, 1, 0, 1, 1, 1, 255, 0, 0, 100, 0), listOf(path))
    private fun size(field: Int, destination: Int) = command(124, intArrayOf(2_000_000 + destination, 0x4000, 1, field))
    private fun common(id: Int, commands: List<WolfCommand>) = WolfCommon(id, id, "unused$id", 0, ByteArray(7), commands,
        "", "", emptyList(), byteArrayOf(), emptyList(), emptyList(), intArrayOf(), 0, emptyList(), "", null, null, 0)
}
