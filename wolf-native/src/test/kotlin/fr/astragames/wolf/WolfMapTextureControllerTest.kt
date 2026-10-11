package fr.astragames.wolf

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class WolfMapTextureControllerTest {
    @Test fun databaseIdLoadUsesEachNativeClockAndSameIdReloadClearsThePriorPhase() {
        for (version in listOf(225, 369)) {
            val f = fixture(version)
            f.write(66, 0); f.write(61, 0)
            assertEquals(3, f.controller.state.backdrop.scroll.phaseX)
            assertEquals(254, f.controller.state.fog.scroll.phaseX)
            assertEquals(listOf(3, -3, 3, -3), listOf(67, 68, 62, 63).map { f.numbers[it] })
            assertEquals(2, f.controller.state.fog.scroll.parallaxDivisor)
            assertEquals(if (version < 300) 2 else 3, f.controller.state.backdrop.behaviorVersion)
            f.controller.advance(f::parameters)
            assertEquals(9, f.controller.state.backdrop.scroll.phaseX)
            assertEquals(255, f.controller.state.fog.scroll.phaseX)
            f.write(66, 0)
            assertEquals(3, f.controller.state.backdrop.scroll.phaseX)
            assertEquals(255, f.controller.state.fog.scroll.phaseX)
        }
    }

    @Test fun filenameReloadWaitsForDrawParsesLfTokensAndDoesNotPerformAnInitialTick() {
        val f = fixture(369)
        f.write(66, 0)
        val prior = f.controller.state.backdrop.scroll
        f.strings[30] = "Texture.png\r\n  -3tail\r\n\r\n1001\r"
        f.controller.writeString(30)
        assertEquals(prior, f.controller.state.backdrop.scroll)
        assertTrue(f.controller.state.backdrop.stringReloadPending)
        assertEquals(0, f.frame(WolfMapTextures.Kind.BACKGROUND)!!.phaseX)
        assertEquals(-2, f.numbers[66]); assertEquals(-3, f.numbers[67]); assertEquals(0, f.numbers[68])
        assertEquals(999, f.controller.state.backdrop.scroll.parallaxDivisor)
        assertFalse(f.controller.state.backdrop.stringReloadPending)
        f.strings[30] = "Texture.png\n+5"; f.controller.writeString(30); f.frame(WolfMapTextures.Kind.BACKGROUND)
        assertEquals(5, f.numbers[67]); assertEquals(0, f.numbers[68])
        assertEquals(999, f.controller.state.backdrop.scroll.parallaxDivisor)
        f.strings[30] = "Texture.png\n7\n8\n-1001"; f.controller.writeString(30); f.frame(WolfMapTextures.Kind.BACKGROUND)
        assertEquals(-999, f.controller.state.backdrop.scroll.parallaxDivisor)
        // Without LF, the native fast path does not strip an isolated CR.
        f.strings[30] = "Texture.png\r"; f.controller.writeString(30)
        assertNull(f.frame(WolfMapTextures.Kind.BACKGROUND))
        assertEquals(-2, f.numbers[66]); assertEquals(7, f.numbers[67]); assertEquals(8, f.numbers[68])
        assertTrue(f.notices.last().contains("Texture.png\r"))
    }

    @Test fun numericSuffixOverflowFollowsTheDifferentShippedCrtContracts() {
        for (version in listOf(225, 369)) {
            val f = fixture(version)
            f.strings[30] = "Texture.png\n2147483648suffix\n-2147483649\n4294967296"
            f.controller.writeString(30); f.frame(WolfMapTextures.Kind.BACKGROUND)
            assertEquals(if (version < 300) Int.MIN_VALUE else Int.MAX_VALUE, f.numbers[67])
            assertEquals(if (version < 300) Int.MAX_VALUE else Int.MIN_VALUE, f.numbers[68])
            assertEquals(if (version < 300) 0 else 999, f.controller.state.backdrop.scroll.parallaxDivisor)
        }
    }

    @Test fun missingBackgroundAndMissingFilenameAreOmittedWithoutInventingAResource() {
        val f = fixture(369)
        f.assets.remove("Data/Texture.png")
        f.write(66, 0)
        assertNull(f.frame(WolfMapTextures.Kind.BACKGROUND))
        assertEquals(0, f.numbers[66])
        assertEquals(1, f.notices.size)
        f.strings[30] = "Missing.png\n4\n-2"; f.controller.writeString(30)
        assertNull(f.frame(WolfMapTextures.Kind.BACKGROUND))
        assertEquals(0, f.numbers[66]); assertEquals(4, f.numbers[67]); assertEquals(-2, f.numbers[68])
        f.strings[30] = ""; f.controller.writeString(30)
        assertNull(f.frame(WolfMapTextures.Kind.BACKGROUND)); assertEquals(-1, f.numbers[66])
        f.strings[31] = "MissingFog.png"; f.controller.writeString(31)
        assertNull(f.frame(WolfMapTextures.Kind.FOG)); assertEquals(-1, f.controller.state.fog.id)
        assertTrue(f.notices.last().contains("MissingFog.png"))
        assertThrows(IllegalStateException::class.java) { f.write(61, 0) }
    }

    @Test fun zoomCorrectionIsPersistedButRepeatedFramesNeverAdvanceAClock() {
        val f = fixture(369)
        f.strings[31] = "Texture.png\n5\n7"; f.controller.writeString(31); f.frame(WolfMapTextures.Kind.FOG)
        f.controller.advance { WolfMapTextures.Parameters(5, 7) }
        val clock = f.controller.state.fog.scroll
        assertEquals(5, clock.phaseX); assertEquals(7, clock.phaseY)
        val a = f.frame(WolfMapTextures.Kind.FOG, zoom = 151)
        assertEquals(8, a!!.phaseX); assertEquals(8, a.phaseY)
        assertEquals(clock.accumulatedX, f.controller.state.fog.scroll.accumulatedX)
        assertEquals(a, f.frame(WolfMapTextures.Kind.FOG, zoom = 151))
    }

    @Test fun restoreChecksTheRealImageAgainAndDoesNotTrustACachedHeaderOrANewDatabasePath() {
        val f = fixture(369)
        f.write(66, 0)
        val original = f.controller.state
        f.rowPath = "ChangedAfterLoad.png"
        f.controller.validate(original, f.numbers)
        f.assets["Data/Texture.png"] = png(17, 13)
        assertThrows(IllegalArgumentException::class.java) { f.controller.validate(original, f.numbers) }
        assertEquals(original, f.controller.state)
        f.assets["Data/Texture.png"] = png(32, 24)
        assertThrows(IllegalArgumentException::class.java) { f.controller.validate(original.copy(
            backdrop = original.backdrop.copy(behaviorVersion = 2)), f.numbers) }
        assertThrows(IllegalArgumentException::class.java) { f.controller.validate(original.copy(
            backdrop = original.backdrop.copy(path = "game-root:../../outside.png")), f.numbers) }
        assertEquals(original, f.controller.state)
    }

    @Test fun signedWolf2PhasesRequireTheirOriginalProfileAndResourceDimensions() {
        val f = fixture(225)
        f.numbers[66] = -2
        val back = WolfMapTextureState(-2, "Data/Texture.png",
            WolfMapTextures.State(true, 32, 24, -1000, -900, -255, -191), behaviorVersion = 2)
        val state = f.controller.state.copy(backdrop = back)
        f.controller.validate(state, f.numbers); f.controller.restore(state)
        assertEquals(-255, f.frame(WolfMapTextures.Kind.BACKGROUND)!!.phaseX)
        assertThrows(IllegalArgumentException::class.java) {
            fixture(369).controller.validate(state, f.numbers)
        }
    }

    @Test fun encryptedAliasHasPriorityAndItsHeaderRequiresTheRealKeyContract() {
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/wolf-map-textures/$name")).use { it.readBytes() }
        val encrypted = resource("quadrants.png.wolfx")
        val expected = WolfImageHeader.dimensions(resource("quadrants.png"))!!
        val f = fixture(369)
        // A different clear file is intentionally present at the same logical name.
        f.assets["Data/Texture.png.wolfx"] = encrypted
        f.write(66, 0)
        assertEquals(expected, f.controller.state.backdrop.scroll.let { it.width to it.height })
        val state = f.controller.state
        f.assets.remove("Data/Texture.png")
        f.controller.validate(state, f.numbers)
        f.assets["Data/Texture.png.wolfx"] = resource("quadrants.png.string-key.wolfx")
        assertThrows(WolfXResourceUnavailable::class.java) { f.controller.validate(state, f.numbers) }
        assertEquals(state, f.controller.state)
    }

    private class Fixture(val version: Int) {
        val numbers = linkedMapOf<Int, Int>()
        val strings = linkedMapOf<Int, String>()
        val assets = linkedMapOf("Data/Texture.png" to png(32, 24))
        val notices = mutableListOf<String>()
        var rowPath = "Texture.png"
        private val fields = listOf(field("file", WolfValueType.STRING, 0), field("x", WolfValueType.NUMBER, 0),
            field("y", WolfValueType.NUMBER, 1), field("divisor", WolfValueType.NUMBER, 2))
        private val types = (0..13).map { id -> WolfDatabaseType(id, "type$id", "", if (id == 13) fields else emptyList(),
            if (id == 13) listOf(WolfDatabaseRow(0, "texture", intArrayOf(3, -3, 2), listOf("Texture.png"))) else emptyList(), 0, null) }
        private val databases = mapOf(WolfDatabaseKind.SYSTEM to WolfDatabase("textures", WolfEncoding.UTF8, 1, types))
        private val source = object : WolfAssetSource {
            override fun read(path: String) = assets[path] ?: throw java.io.FileNotFoundException(path)
            override fun exists(path: String) = path in assets
            override fun list(path: String) = emptyList<String>()
        }
        val controller = WolfMapTextureController(source, version, numbers, strings, databases,
            { type, row, field -> types[type].number(row, field)!! }, { _, _, _ -> rowPath }, notices::add)
        fun parameters(kind: WolfMapTextures.Kind): WolfMapTextures.Parameters {
            val index = if (kind == WolfMapTextures.Kind.FOG) 62 else 67
            return WolfMapTextures.Parameters(numbers[index] ?: 0, numbers[index + 1] ?: 0, 2, 4, 0, 1, 0)
        }
        fun write(id: Int, value: Int) { numbers[id] = value; controller.writeNumber(id, value, ::parameters) }
        fun frame(kind: WolfMapTextures.Kind, zoom: Int = 100) = controller.frame(kind, parameters(kind), zoom, 0, 0)
    }
    private fun fixture(version: Int) = Fixture(version)
    companion object {
        private fun field(name: String, type: WolfValueType, index: Int) =
            WolfDatabaseField(name, type, index, 0, "", emptyList(), intArrayOf(), 0)
        internal fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { out ->
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "PNG", out); out.toByteArray()
        }
    }
}
