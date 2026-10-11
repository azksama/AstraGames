package fr.astragames.wolf

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.io.File
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real event data and native VM state; Android Canvas/audio are verified by separate SDK tests. */
class WolfOfficialSaveJourneyTest {
    @Test fun bothOfficialGamesReachTheirDungeonAndWinANaturalEncounterThroughNativeInputs() {
        val fontFile = File(System.getProperty("wolf.projectRoot", ".."), "app/src/main/assets/windows/ipaexg.ttf")
        assertTrue("The application's Japanese font must be available to the official journey tests", fontFile.isFile)
        val font = Font.createFont(Font.TRUETYPE_FONT, fontFile)
        val heroField = WolfRuntime::class.java.getDeclaredField("player").also { it.isAccessible = true }
        val foregroundField = WolfRuntime::class.java.getDeclaredField("foreground").also { it.isAccessible = true }
        val pathMethod = WolfRuntime::class.java.declaredMethods.first { it.name == "gridPath" }.also { it.isAccessible = true }
        listOf("sample22961/WOLF_RPG_Editor2", "sample3729/WOLF_RPG_Editor3").forEach { folder ->
            val root = File(System.getProperty("wolf.fixtureRoot"), folder); assumeTrue(File(root, "Data/BasicData/Game.dat").isFile)
            val source = object : WolfAssetSource {
                override fun read(path: String) = File(root, path).readBytes()
                override fun list(path: String) = File(root, path).list()?.toList().orEmpty()
                override fun exists(path: String) = File(root, path).exists()
            }
            val host = SaveHost(WolfRuntime.load(source), font)
            repeat(120) { if (host.runtime.frame().dialog?.choices?.size != 3) host.tick() }
            assertEquals(0, host.runtime.frame().dialog!!.selectedChoice); host.pulse(NativeKey.ACCEPT)
            repeat(600) { index -> if (index % 8 == 0 && host.runtime.frame().dialog != null) host.pulse(NativeKey.ACCEPT); host.tick() }
            assertEquals(1, host.runtime.mapId)
            var held: NativeKey? = null; var leg = 0
            var target: Pair<Int, Int>? = null; var path = emptyList<Pair<Int, Int>>()
            var sawGoblin = false; var sawAttack = false; var sawDamage = false; var sawReward = false; var sawDefeatedEnemy = false; var returnedToMap = false
            for (index in 0 until 9000) {
                host.tick(); val frame = host.runtime.frame(); val hero = host.runtime.snapshotState().hero!!
                sawGoblin = sawGoblin || frame.pictures.any { it.path?.endsWith("GD_Goblin(Red).png") == true }
                val attackFile = if (folder.startsWith("sample22961")) "[Attack]Slash01_panop.png" else "Attack_Slash01_panop.png"
                sawAttack = sawAttack || frame.pictures.any { it.path?.endsWith(attackFile) == true }
                sawDamage = sawDamage || frame.pictures.any { it.id in 19000..19999 && it.text?.contains("\\f[13]") == true }
                sawReward = sawReward || frame.pictures.any { it.text?.contains("経験値") == true && it.text.contains("入手") }
                sawDefeatedEnemy = sawDefeatedEnemy || frame.pictures.any { it.id == 10805 && it.text?.endsWith("HP \\font[1]0") == true }
                val foreground = foregroundField.get(host.runtime) != null
                if (sawReward && !foreground && frame.pictures.isEmpty()) { returnedToMap = true; break }
                if (hero.moveTicks > 0 || frame.dialog != null || foreground) {
                    held?.let { host.runtime.logicalKey(it, false) }; held = null
                    if (frame.dialog != null || host.runtime.mapId >= 2 && index % 30 == 0) host.pulse(NativeKey.ACCEPT)
                    continue
                }
                val goal = if (host.runtime.mapId == 1) 15 to 0 else if (leg % 2 == 0) 14 to 16 else 7 to 16
                val here = (hero.x * 2).toInt() to (hero.y * 2).toInt(); val wanted = goal.first * 2 to goal.second * 2
                if (here == wanted) { leg++; path = emptyList(); target = null; continue }
                if (target != wanted || path.isEmpty()) {
                    // Read-only planning does not teleport or call a combat event; normal movement triggers the encounter.
                    @Suppress("UNCHECKED_CAST")
                    val planned = pathMethod.invoke(host.runtime, heroField.get(host.runtime), here.first, here.second,
                        wanted.first, wanted.second, hero.stepSize.takeIf { it != 0 } ?: (host.runtime.game.config.byteSettings[8].toInt() + 1),
                        true, 16_384, null) as List<Pair<Int, Int>>?
                    path = planned ?: throw AssertionError("No playable path from $here to $wanted in $folder")
                    target = wanted
                }
                while (path.firstOrNull() == here) path = path.drop(1)
                val next = path.firstOrNull() ?: continue
                val key = when { next.first < here.first -> NativeKey.LEFT; next.first > here.first -> NativeKey.RIGHT; next.second < here.second -> NativeKey.UP; else -> NativeKey.DOWN }
                held?.let { host.runtime.logicalKey(it, false) }; held = key; host.runtime.logicalKey(key, true)
            }
            held?.let { host.runtime.logicalKey(it, false) }
            assertEquals(2, host.runtime.mapId); assertTrue("Actual goblin graphic", sawGoblin)
            assertTrue("Actual attack animation", sawAttack); assertTrue("Actual damage picture", sawDamage)
            if (folder.startsWith("sample22961")) assertTrue("WOLF2's original victory debug print", host.diagnostics.any { it.startsWith("敵全滅") })
            else assertTrue("WOLF3's actual enemy status must reach HP0", sawDefeatedEnemy)
            assertTrue("The original victory must award experience", sawReward)
            assertTrue("Victory must return control to the map", returnedToMap); assertNull(host.runtime.frame().diagnostic)
            val before = host.runtime.snapshotState(); val restored = WolfRuntime.load(source); restored.restore(host.runtime.snapshot())
            assertEquals(before.mutableNumbers, restored.snapshotState().mutableNumbers); assertEquals(before.mapId, restored.mapId)
            println("$folder: dungeon reached through movement, goblin defeated, native post-combat restart preserved game state")
        }
    }

    @Test fun bothOfficialGamesSaveAndReloadThroughTheirMenusAfterCreatingANewRuntime() {
        val fontFile = File(System.getProperty("wolf.projectRoot", ".."), "app/src/main/assets/windows/ipaexg.ttf")
        assertTrue("The application's Japanese font must be available to the official journey tests", fontFile.isFile)
        val font = Font.createFont(Font.TRUETYPE_FONT, fontFile)
        listOf("sample22961/WOLF_RPG_Editor2", "sample3729/WOLF_RPG_Editor3").forEach { folder ->
            val root = File(System.getProperty("wolf.fixtureRoot"), folder)
            assumeTrue(File(root, "Data/BasicData/Game.dat").isFile)
            val source = object : WolfAssetSource {
                override fun read(path: String) = File(root, path).readBytes()
                override fun list(path: String) = File(root, path).list()?.toList().orEmpty()
                override fun exists(path: String) = File(root, path).exists()
            }
            val host = SaveHost(WolfRuntime.load(source), font)
            repeat(120) { if (host.runtime.frame().dialog?.choices?.size != 3) host.tick() }
            assertEquals(3, host.runtime.frame().dialog!!.choices.size)
            assertEquals(0, host.runtime.frame().dialog!!.selectedChoice)
            host.pulse(NativeKey.ACCEPT); host.tick()
            repeat(600) { index ->
                if (index % 8 == 0 && host.runtime.frame().dialog != null) host.pulse(NativeKey.ACCEPT)
                host.tick()
            }
            assertEquals(1, host.runtime.mapId); assertNull(host.runtime.frame().dialog)
            host.pulse(NativeKey.BACK); repeat(120) { host.tick() }
            val menu = host.runtime.frame()
            val text = menu.pictures.mapNotNull { it.text }.joinToString("\n")
            val stateBeforeSave = host.runtime.snapshotState()
            assertEquals("The real party has one member, not six separator records", 1, stateBeforeSave.common[79 to 22])
            assertTrue("Menu must display Wolfarl's actual name", text.contains("ウルファール"))
            val portrait = menu.pictures.single { it.path?.endsWith("FaceGraphic_Wolfarl.png") == true }
            val intrinsic = WolfImageHeader.dimensions(source.read(requireNotNull(portrait.path)))!!
            assertEquals("The actual portrait must fit its 48px frame, even after a temporary zoom0 measurement", 48f, intrinsic.first * portrait.scaleX, .001f)
            assertEquals(48f, intrinsic.second * portrait.scaleY, .001f)
            assertEquals(-1, stateBeforeSave.common[105 to 11])
            repeat(5) { host.pulse(NativeKey.DOWN); repeat(45) { host.tick() } }
            repeat(12) { if (host.savedState == null) { host.pulse(NativeKey.ACCEPT); repeat(45) { host.tick() } } }
            val state = host.savedState ?: throw AssertionError("$folder did not reach its actual save command")
            assertEquals(if (folder.startsWith("sample22961")) "slot:1" else "Save/SaveData01.sav", host.savedKey)
            assertTrue(host.savedBytes > 1000)
            host.runtime = WolfRuntime.load(source)
            repeat(120) { if (host.runtime.frame().dialog?.choices?.size != 3) host.tick() }
            assertEquals(0, host.runtime.mapId); assertEquals(3, host.runtime.frame().dialog!!.choices.size)
            when (host.runtime.frame().dialog!!.selectedChoice) {
                0 -> { host.pulse(NativeKey.DOWN); host.tick() }
                2 -> { host.pulse(NativeKey.UP); host.tick() }
            }
            assertEquals("The title must select Continue through its real key reader", 1, host.runtime.frame().dialog!!.selectedChoice)
            host.pulse(NativeKey.ACCEPT); host.tick()
            repeat(24) { if (host.loadedState == null) { host.pulse(NativeKey.ACCEPT); repeat(45) { host.tick() } } }
            val restored = host.loadedState ?: throw AssertionError("$folder did not reload its saved slot through the title menu")
            assertEquals(state.mapId, restored.mapId); assertEquals(state.x, restored.x, .0001f); assertEquals(state.y, restored.y, .0001f)
            assertEquals(state.numbers, restored.numbers); assertEquals(state.strings, restored.strings)
            assertEquals(state.mutableNumbers, restored.mutableNumbers); assertEquals(state.mutableStrings, restored.mutableStrings)
            assertEquals(state.mutableRows, restored.mutableRows); assertEquals(state.companions, restored.companions)
            assertNull(host.runtime.frame().diagnostic)
            println("$folder: official save ${host.savedKey} (${host.savedBytes} bytes), fresh native runtime, title Continue and load passed")
        }
    }

    private class SaveHost(var runtime: WolfRuntime, private val font: Font) {
        private val files = linkedMapOf<String, ByteArray>()
        private val frc = FontRenderContext(AffineTransform(), true, true)
        var savedState: WolfSnapshot.State? = null
        var loadedState: WolfSnapshot.State? = null
        var savedKey: String? = null
        var savedBytes = 0
        val diagnostics = mutableListOf<String>()
        fun pulse(key: NativeKey) { runtime.key(key, true); runtime.key(key, false) }
        fun tick() {
            runtime.tick()
            runtime.drainEffects().forEach { effect -> when (effect) {
                is NativeEffect.ReadSaveValue -> {
                    val data = files[effect.key]?.let(WolfSaveData::decode)
                    val override = data != null && if (effect.stringValue) effect.variableCode in data.strings else effect.variableCode in data.numbers
                    runtime.completeSaveValue(effect.requestId, data != null, data?.numbers?.get(effect.variableCode) ?: 0,
                        data?.strings?.get(effect.variableCode).orEmpty(), if (override) null else data?.snapshot)
                }
                is NativeEffect.WriteSaveValue -> {
                    val previous = files[effect.key]?.let(WolfSaveData::decode) ?: WolfSaveData()
                    // Wolf does not create mutable DB storage in a value-only save.
                    if (previous.snapshot != null || effect.variableCode !in 1_100_000_000..1_199_999_999) files[effect.key] = previous.copy(
                        numbers = effect.number?.let { previous.numbers + (effect.variableCode to it) } ?: previous.numbers,
                        strings = effect.text?.let { previous.strings + (effect.variableCode to it) } ?: previous.strings).encode()
                    runtime.completeSaveWrite(effect.requestId, true)
                }
                is NativeEffect.Save -> {
                    val key = effect.key ?: "slot:${effect.slot}"
                    val bytes = WolfSaveData(snapshot = runtime.snapshot()).encode()
                    files[key] = bytes; savedState = runtime.snapshotState(); savedKey = key; savedBytes = bytes.size
                    runtime.completeHostRequest(true)
                }
                is NativeEffect.Load -> {
                    val bytes = files[effect.key ?: "slot:${effect.slot}"]
                    assertNotNull("Game tried to load an absent native slot", bytes)
                    runtime.restoreSaveData(WolfSaveData.decode(bytes!!)); loadedState = runtime.snapshotState(); runtime.completeHostRequest(true)
                }
                is NativeEffect.MeasurePicture -> measure(effect)
                is NativeEffect.Audio, is NativeEffect.AudioGain -> Unit
                is NativeEffect.Diagnostic -> { diagnostics.add(effect.message); assertNull("A game debug-print must not be a fatal native diagnostic", runtime.frame().diagnostic) }
                else -> if (effect.javaClass.simpleName != "AudioCache") fail("Unexpected host request in actual save journey: $effect")
            } }
        }
        private fun measure(effect: NativeEffect.MeasurePicture) {
            val picture = effect.picture
            var size = picture.fontSize; var x = 0.0; var y = 0.0; var width = 0.0; var bottom = 0.0
            var nextLineX = 0.0; var lineGap = 0.0; var condense = 0.0; var lineHeight = size.toDouble()
            val input = picture.text ?: error("The official menu measurement must be a text picture")
            var offset = 0
            while (offset < input.length) {
                val escape = WolfTextCodes.escape.matchAt(input, offset)
                if (escape != null) {
                    val code = escape.groupValues[1]; val p = escape.groupValues[2]
                    when (code) {
                        "f" -> size = p.toFloat(); "f+" -> size += p.toFloat()
                        "mx" -> x += p.toDouble(); "my" -> y += p.toDouble(); "bx" -> nextLineX = p.toDouble()
                        "ax" -> x = p.toDouble(); "ay" -> y = p.toDouble(); "space" -> lineGap = p.toDouble(); "-" -> condense = p.toDouble()
                        "fR", "sp", "se", "A+", "A-", "c", "E", "N", "m", "wE", "cE", "cS", "cR", "font", "" -> Unit
                        else -> error("Java2D menu host cannot prove text code $code; use the SDK host")
                    }
                    lineHeight = maxOf(lineHeight, size.toDouble()); offset += escape.value.length
                } else if (input.startsWith("<L>", offset) || input.startsWith("<C>", offset) || input.startsWith("<R>", offset)) offset += 3
                else {
                    val cp = input.codePointAt(offset)
                    if (cp == 10) { width = maxOf(width, x); x = nextLineX; bottom = maxOf(bottom, y + lineHeight); y += lineHeight + lineGap; lineHeight = size.toDouble() }
                    else if (cp != 13) { x += font.deriveFont(size).getStringBounds(String(Character.toChars(cp)), frc).width - condense; width = maxOf(width, x); bottom = maxOf(bottom, y + lineHeight) }
                    offset += Character.charCount(cp)
                }
            }
            runtime.completePictureMeasure(effect.requestId, ceil(maxOf(width, x)).toInt(), ceil(maxOf(bottom, y + lineHeight)).toInt())
        }
    }
}
