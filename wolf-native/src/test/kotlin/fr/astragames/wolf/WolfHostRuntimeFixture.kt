package fr.astragames.wolf

internal fun wolfHostRuntimeFixture(commands: List<WolfCommand>, title: String = "host", fps: Int = 60,
    movement: ByteArray = byteArrayOf(3, 3, 3, 0)): WolfRuntime {
    val source = object : WolfAssetSource {
        override fun read(path: String) = byteArrayOf()
        override fun list(path: String) = emptyList<String>()
        override fun exists(path: String) = true
    }
    val config = WolfGameConfig(title, "", WolfEncoding.UTF8, 16, 96, 96, fps, "Hero.png", 4, 4, 3,
        "sans", emptyList(), ByteArray(40).also { it[8] = 1; it[9] = 1 }, IntArray(20).also { it[18] = 350 }, emptyList(), 0xc4)
    val page = WolfPage(0, -1, "", 2, 1, 255, 0, ByteArray(37).also { it[0] = 1 }, movement,
        3, WolfRoute(2, emptyList()), commands, 3, byteArrayOf(0, 0, 0), null, 0)
    val map = WolfMap("map", "map", 103, WolfEncoding.UTF8, 0, 6, 6, 3, IntArray(108) { 100000 },
        listOf(WolfEvent(0, "host", 2, 2, listOf(page))), 0x69, 0)
    val game = WolfGameData(config, emptyList(), emptyMap(),
        listOf(WolfTileset(0, "", "", emptyList(), byteArrayOf(), IntArray(64))),
        listOf(WolfMapReference(0, "map", "map.mps")), WolfStartPosition(0, 2, 2))
    return WolfRuntime.fromData(source, game) { map }
}
