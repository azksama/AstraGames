package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfSystemAudioTest {
    @Test fun signedAllOnesSourceModesStopTheThreeAudioChannelsWithoutDatabaseReads() {
        val runtime = fixture(listOf(
            WolfCommand(140, intArrayOf(-256, 7, 0, 0), 0, emptyList()),
            WolfCommand(140, intArrayOf(-240, 9, 0, 0), 0, emptyList()),
            WolfCommand(140, intArrayOf(-224, 11, 0, 0, 0, 0, -1), 0, emptyList()),
            WolfCommand(173, intArrayOf(), 0, emptyList()),
        ), compatibility = 350)
        assertTrue(runtime.inspectCapabilities().supported)
        runtime.tick()
        val audio = runtime.drainEffects().filterIsInstance<NativeEffect.Audio>()
        assertEquals(listOf(0, 1, 2), audio.map { it.channel })
        assertTrue(audio.all { it.stop && it.path == null })
        assertEquals(listOf(7, 9, 11), audio.map { it.fadeTicks })
        assertEquals(-1, audio.last().voice)
        assertTrue(runtime.snapshotState().audio.isEmpty())
        val invalid = fixture(listOf(WolfCommand(140, intArrayOf(-255), 0, emptyList())))
        assertFalse(invalid.inspectCapabilities().supported)
    }

    @Test fun nativeAudioDefaultsAndStoppedParametersRemainReadableAfterSaving() {
        fun command(op: Int, vararg args: Int, path: String? = null) = WolfCommand(op, args, 0, listOfNotNull(path))
        val runtime = fixture(listOf(
            command(121, 2_000_000, 9_000_095, 0, 0), command(121, 2_000_001, 9_000_096, 0, 0),
            command(140, 0x02000000, 0, 0, 0, 80, 120, path = "Music/field.ogg"),
            command(121, 9_000_095, 40, 0, 0), command(121, 9_000_096, 75, 0, 0),
            command(140, 0x00ffff00, 0, 0, 0), command(173),
        ))
        runtime.tick()
        val state = runtime.snapshotState()
        assertEquals(0, state.numbers[0]); assertEquals(100, state.numbers[1])
        assertEquals(40, state.system[95]); assertEquals(75, state.system[96])
        assertTrue(state.audio.isEmpty())
        val updates = runtime.drainEffects().filterIsInstance<NativeEffect.Audio>().filter { it.path == null && !it.stop }
        assertEquals(listOf(40 to 120, 40 to 75), updates.map { it.volume to it.pitch })
        val bytes = runtime.snapshot()
        assertEquals(40, runtime.savedNumber(bytes, 9_000_095)); assertEquals(75, runtime.savedNumber(bytes, 9_000_096))
        val fresh = fixture(emptyList())
        fresh.restore(bytes)
        assertEquals(40, fresh.savedNumber(fresh.snapshot(), 9_000_095))
        assertEquals(75, fresh.savedNumber(fresh.snapshot(), 9_000_096))
    }

    @Test fun databaseVariableSourcePreloadingAndReleaseDoNotPretendToPlay() {
        val fields = listOf(WolfDatabaseField("path", WolfValueType.STRING, 0, 0, "", emptyList(), intArrayOf(), 0),
            WolfDatabaseField("volume", WolfValueType.NUMBER, 0, 0, "", emptyList(), intArrayOf(), 100),
            WolfDatabaseField("pitch", WolfValueType.NUMBER, 0, 0, "", emptyList(), intArrayOf(), 100))
        val type = WolfDatabaseType(0, "music", "", fields, listOf(WolfDatabaseRow(0, "field", intArrayOf(80, 120), listOf("Music/field.ogg"))), 0, null)
        val db = WolfDatabase("audio", WolfEncoding.UTF8, 1, listOf(type, type))
        val runtime = fixture(listOf(
            WolfCommand(121, intArrayOf(2_000_000, 0, 0, 0), 0, emptyList()),
            WolfCommand(140, intArrayOf(0x01000001, 0, 2_000_000, 0), 0, emptyList()),
            WolfCommand(140, intArrayOf(2, 0, 0, 0), 0, emptyList()),
            WolfCommand(140, intArrayOf(3), 0, emptyList()), WolfCommand(173, intArrayOf(), 0, emptyList()),
        ), mapOf(WolfDatabaseKind.SYSTEM to db))
        runtime.tick()
        val effects = runtime.drainEffects()
        assertTrue(effects.none { it is NativeEffect.Audio })
        assertEquals(listOf(NativeEffect.AudioCache(1, "Data/Music/field.ogg"),
            NativeEffect.AudioCache(2, "Data/Music/field.ogg"), NativeEffect.AudioCache(3)),
            effects.filterIsInstance<NativeEffect.AudioCache>())
    }

    @Test fun mapAudioUsesOriginalDatabaseBindingAndKeepsTheSameTrackPlaying() {
        fun field(name: String, type: WolfValueType, index: Int) = WolfDatabaseField(name, type, index, 0, "", emptyList(), intArrayOf(), 0)
        val fields = listOf(field("map", WolfValueType.STRING, 0), field("BGM", WolfValueType.NUMBER, 0),
            field("BGS", WolfValueType.NUMBER, 1), field("loop", WolfValueType.NUMBER, 2),
            field("unused", WolfValueType.NUMBER, 3), field("BGM file", WolfValueType.STRING, 1), field("BGS file", WolfValueType.STRING, 2))
        val settings = WolfDatabaseType(0, "maps", "", fields, listOf(
            WolfDatabaseRow(0, "initial", intArrayOf(-3, -1, 0, 0), listOf("initial.mps", "Music/field.ogg\n80\n120\n0", "")),
            WolfDatabaseRow(1, "next", intArrayOf(-3, -2, 0, 0), listOf("next.mps", "Music/field.ogg\n60\n100\n0", "")),
        ), 0, null)
        val runtime = fixture(listOf(
            WolfCommand(121, intArrayOf(2_000_000, 9_000_095, 0, 0), 0, emptyList()),
            WolfCommand(130, intArrayOf(-1, 2, 2, 1, 0), 0, emptyList()),
        ), mapOf(WolfDatabaseKind.SYSTEM to WolfDatabase("maps", WolfEncoding.UTF8, 1, listOf(settings))))
        runtime.tick()
        val state = runtime.snapshotState()
        assertEquals(80, state.numbers[0]); assertEquals(60, state.system[95])
        assertEquals(100, runtime.savedNumber(runtime.snapshot(), 9_000_098))
        val audio = runtime.drainEffects().filterIsInstance<NativeEffect.Audio>()
        assertEquals(1, audio.count { it.path == "Data/Music/field.ogg" })
        assertEquals(1, audio.count { it.channel == 1 && it.stop })
        assertEquals(60, audio.single { it.channel == 0 && it.path == null }.volume)
        assertEquals("Data/Music/field.ogg", state.audio.single().path)
    }

    @Test fun wolf3KeepsIndependentBgsVoicesAndTheirStoppedParametersAcrossSaveLoad() {
        fun command(op: Int, vararg args: Int, path: String? = null) = WolfCommand(op, args, 0, listOfNotNull(path))
        val runtime = fixture(listOf(
            command(140, 0x02000010, 0, 0, 500, 80, 100, 250, -255, 0, path = "Music/rain.ogg"),
            command(140, 0x02000010, 0, 0, 700, 30, 120, 400, 255, 3, path = "Music/wind.ogg"),
            command(140, 0x00ffff10, 0, 0, 0, 0, 0, 0, 0, 3),
            command(121, 9_000_099, 0, 0, 0),
            command(121, 2_000_000, 9_000_097, 0, 0),
            command(121, 9_000_099, 3, 0, 0),
            command(121, 2_000_001, 9_000_098, 0, 0), command(173),
        ), compatibility = 350)
        runtime.tick()
        val state = runtime.snapshotState()
        assertEquals(80, state.numbers[0]); assertEquals(120, state.numbers[1])
        assertEquals(3, state.system[99]); assertEquals(30, state.system[97])
        assertEquals(1, state.audio.size)
        val rain = state.audio.single()
        assertEquals(0, rain.voice); assertEquals(-255, rain.pan)
        assertEquals(500, rain.position); assertEquals(250, rain.loopStart)
        val restored = fixture(emptyList(), compatibility = 350)
        restored.restore(runtime.snapshot())
        assertEquals(120, restored.savedNumber(restored.snapshot(), 9_000_098))
        assertEquals(state.audio, restored.snapshotState().audio)
        assertEquals(mapOf(0 to 80, 3 to 30), restored.snapshotState().bgsVolumes)
    }

    @Test fun wolf3UsesTheActualGameSettingForZeroVolumeAndKeepsSeDelayVoiceAndPan() {
        val runtime = fixture(listOf(
            WolfCommand(140, intArrayOf(0x02000000, 0, 0, 0, 0, 100, 0, 0, 0), 0,
                listOf("Music/field.ogg")),
            WolfCommand(140, intArrayOf(0x02000020, 12, 0, 0, 65, 100, 4, -128), 0,
                listOf("Sound/click.wav")), WolfCommand(173, intArrayOf(), 0, emptyList()),
        ), compatibility = 350)
        runtime.tick()
        val audio = runtime.drainEffects().filterIsInstance<NativeEffect.Audio>().filter { !it.stop }
        assertEquals(0, audio.single { it.channel == 0 }.volume)
        val se = audio.single { it.channel == 2 }
        assertEquals(12, se.delayTicks); assertEquals(0, se.fadeTicks)
        assertEquals(4, se.voice); assertEquals(-128, se.pan)
        val legacy = fixture(listOf(WolfCommand(140, intArrayOf(0x02000000, 0, 0, 0, 0, 100, 0), 0,
            listOf("Music/field.ogg"))), compatibility = 350, legacyZero = true)
        legacy.tick()
        assertEquals(100, legacy.snapshotState().audio.single().volume)
    }

    private fun fixture(commands: List<WolfCommand>, databases: Map<WolfDatabaseKind, WolfDatabase> = emptyMap(),
        compatibility: Int = 0, legacyZero: Boolean = false): WolfRuntime {
        val source = object : WolfAssetSource {
            override fun read(path: String) = byteArrayOf()
            override fun list(path: String) = emptyList<String>()
            override fun exists(path: String) = true
        }
        val config = WolfGameConfig("audio", "", WolfEncoding.UTF8, 16, 96, 96, 60, "Hero.png", 4, 4, 3,
            "sans", emptyList(), ByteArray(40).also { it[8] = 1; it[9] = 1; it[39] = if (legacyZero) 1 else 0 },
            IntArray(20).also { it[18] = compatibility }, emptyList(), 0xc4)
        val page = WolfPage(0, -1, "", 2, 1, 255, 0, ByteArray(37).also { it[0] = 1 }, byteArrayOf(3, 3, 3, 0),
            3, WolfRoute(2, emptyList()), commands, 3, byteArrayOf(0, 0, 0), null, 0)
        val map = WolfMap("map", "map", 103, WolfEncoding.UTF8, 0, 6, 6, 3, IntArray(108) { 100000 },
            listOf(WolfEvent(0, "audio", 2, 2, listOf(page))), 0x69, 0)
        val data = WolfGameData(config, emptyList(), databases,
            listOf(WolfTileset(0, "", "", emptyList(), byteArrayOf(), IntArray(64))),
            listOf(WolfMapReference(0, "map", "map.mps")), WolfStartPosition(0, 2, 2))
        return WolfRuntime.fromData(source, data) { map }
    }
}
