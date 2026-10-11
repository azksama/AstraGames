package fr.astragames.wolf

/** Current-map changes from 240/241/242. Published maps and tilesets are never mutated in place. */
class WolfMapChanges(private val original: WolfMap, private val tilesets: List<WolfTileset>) {
    data class State(val tilesetId: Int, val tiles: Map<Int, Int>, val passage: Map<Int, Int>)

    var map: WolfMap = original
        private set
    private var changedTiles = linkedMapOf<Int, Int>()
    private var changedPassage = linkedMapOf<Int, Int>()
    var tileset: WolfTileset? = tilesets.getOrNull(original.tilesetId)
        private set

    fun state(): State? = if (map.tilesetId == original.tilesetId && changedTiles.isEmpty() && changedPassage.isEmpty()) null
        else State(map.tilesetId, changedTiles.toMap(), changedPassage.toMap())

    fun switchTileset(id: Int) {
        val selected = tilesets.getOrNull(id) ?: return // Native invalid tileset IDs leave the map unchanged.
        map = map.copy(tilesetId = id)
        tileset = selected
        changedPassage.clear()
    }

    fun passage(index: Int, flags: Int) {
        val current = tileset ?: error("Tileset absent de la carte")
        if (index !in current.passage.indices) return
        require(flags and 0x3ff.inv() == 0) { "Propriété de tuile non portée : $flags" }
        val copy = current.passage.copyOf()
        copy[index] = flags
        val initial = tilesets[map.tilesetId].passage[index]
        if (flags == initial) changedPassage.remove(index) else changedPassage[index] = flags
        tileset = current.copy(passage = copy)
    }

    fun resetPassage() {
        tileset = tilesets.getOrNull(map.tilesetId)
        changedPassage.clear()
    }

    /** Index is in the native property table, after the operand's literal/variable conversion. */
    fun rectangle(layer: Int, x: Int, y: Int, width: Int, height: Int, propertyIndex: Int) {
        val selected = tileset ?: error("Tileset absent de la carte")
        if (layer !in 0 until map.layerCount || x !in 0 until map.width || y !in 0 until map.height ||
            propertyIndex !in selected.passage.indices || width <= 0 || height <= 0) return
        require(map.tiles.size.toLong() == map.width.toLong() * map.height * map.layerCount) { "Matrice de carte absente" }
        val endX = x + minOf(width, map.width - x)
        val endY = y + minOf(height, map.height - y)
        val autoSlots = selected.autoImages.size + 1
        val tile = if (propertyIndex < autoSlots) (propertyIndex + 1) * 100_000 + 4444 else propertyIndex - autoSlots
        val copy = map.tiles.copyOf()
        fun index(tx: Int, ty: Int) = (layer * map.height + ty) * map.width + tx
        for (tx in x until endX) for (ty in y until endY) copy[index(tx, ty)] = tile
        // 466430/a1f8d0 recompute only the rectangle perimeter and its one-cell border.
        // Out-of-map neighbours connect; even a looping map does not wrap this operation.
        for (tx in maxOf(0, x - 1) until minOf(map.width, endX + 1)) {
            for (ty in maxOf(0, y - 1) until minOf(map.height, endY + 1)) {
                if (tx > x && tx < endX - 1 && ty > y && ty < endY - 1) continue
                val at = index(tx, ty)
                val auto = copy[at] / 100_000
                if (auto < 2) continue // Reserved transparent autotile 1 is not edge-shaped.
                val neighbours = BooleanArray(9)
                for (dx in -1..1) for (dy in -1..1) {
                    val nx = tx + dx; val ny = ty + dy
                    neighbours[(dy + 1) * 3 + dx + 1] = nx !in 0 until map.width || ny !in 0 until map.height || copy[index(nx, ny)] / 100_000 == auto
                }
                copy[at] = auto * 100_000 + corners(neighbours)
            }
        }
        val newChanges = LinkedHashMap(changedTiles)
        for (tx in maxOf(0, x - 1) until minOf(map.width, endX + 1)) {
            for (ty in maxOf(0, y - 1) until minOf(map.height, endY + 1)) {
                val at = index(tx, ty)
                if (copy[at] == original.tiles[at]) newChanges.remove(at) else newChanges[at] = copy[at]
            }
        }
        require(newChanges.size <= MAX_CHANGES) { "Trop de tuiles modifiées pour la capacité de sauvegarde native" }
        changedTiles = newChanges
        map = map.copy(tiles = copy)
    }

    /** Validate into a fresh object before replacing any live interpreter state. */
    fun restore(state: State?) {
        if (state == null) return
        val selected = tilesets.getOrNull(state.tilesetId) ?: error("Tileset de sauvegarde absent")
        require(state.tiles.size <= MAX_CHANGES && state.passage.size <= selected.passage.size) { "Overlay de carte trop grand" }
        require(state.tiles.keys.all { it in original.tiles.indices }) { "Tuile de sauvegarde hors carte" }
        require(state.tiles.values.all { raw -> raw >= 0 && selected.passageIndex(raw) in selected.passage.indices &&
            (raw < 100_000 || raw % 100_000 <= 4444 && raw % 10 in 0..4 && raw / 10 % 10 in 0..4 && raw / 100 % 10 in 0..4 && raw / 1000 % 10 in 0..4) }) { "Tuile de sauvegarde invalide" }
        require(state.passage.all { (index, flags) -> index in selected.passage.indices && flags and 0x3ff.inv() == 0 }) { "Propriété de sauvegarde invalide" }
        val tileCopy = if (state.tiles.isEmpty()) original.tiles else original.tiles.copyOf().apply { state.tiles.forEach { (index, value) -> this[index] = value } }
        val passageCopy = if (state.passage.isEmpty()) selected.passage else selected.passage.copyOf().apply { state.passage.forEach { (index, value) -> this[index] = value } }
        changedTiles = LinkedHashMap(state.tiles); changedPassage = LinkedHashMap(state.passage)
        map = original.copy(tilesetId = state.tilesetId, tiles = tileCopy)
        tileset = selected.copy(passage = passageCopy)
    }

    companion object {
        const val MAX_CHANGES = 100_000

        /** 466240/4662f0: each digit selects one of five atlas rows for a quarter. */
        internal fun corners(n: BooleanArray): Int {
            require(n.size == 9)
            fun quarter(vertical: Int, horizontal: Int, diagonal: Int) = when {
                n[vertical] && n[horizontal] -> if (n[diagonal]) 4 else 3
                n[vertical] -> 1
                n[horizontal] -> 2
                else -> 0
            }
            return quarter(1, 3, 0) * 1000 + quarter(1, 5, 2) * 100 + quarter(7, 3, 6) * 10 + quarter(7, 5, 8)
        }

        /** Literal editor operands encode extra autotile rows as negative IDs. */
        internal fun literalIndex(value: Int, wolf3: Boolean): Int = when {
            value >= 8 -> Math.addExact(value, if (wolf3) 24 else 8)
            value in -18..-11 || wolf3 && value in -34..-19 -> -3 - value
            value in 0..7 -> value
            else -> error("Numéro de tuile de l'éditeur invalide : $value")
        }
    }
}
