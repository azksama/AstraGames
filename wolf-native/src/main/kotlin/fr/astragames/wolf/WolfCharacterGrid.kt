package fr.astragames.wolf

/** Resource9c8be0: the same CharaChip grid is used for rendering and command124. */
object WolfCharacterGrid {
    fun divisions(path: String, patterns: Int, directions: Int): Pair<Int, Int> {
        require(patterns in 1..5 && directions in setOf(4, 8))
        val name = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').uppercase()
        if (name.endsWith('$')) return 1 to 1
        val extra = if (name.endsWith("TX")) patterns else if (name.endsWith('T')) 1 else 0
        return (patterns + extra) * (if (directions == 8) 2 else 1) to 4
    }
}
