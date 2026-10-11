package fr.astragames.wolf

/** W2 44f3f0 checks the tile property on every layer, independently of passage priority. */
internal object WolfCounterInteraction {
    /** 4492b0: asymmetric foot range; the square foot extends toward positive Y. */
    fun inRange(deltaX: Int, deltaY: Int, reachX: Int, reachY: Int, squareFoot: Boolean,
        horizontalExtra: Int = 0): Boolean {
        require(reachX in 0..255 && reachY in 0..255 && horizontalExtra in 0..1)
        return kotlin.math.abs(deltaX) <= reachX + 1 &&
            deltaY >= -reachY - horizontalExtra && deltaY <= reachY + if (squareFoot) 1 else 0
    }
    fun isCounter(map: WolfMap, tileset: WolfTileset?, halfX: Int, halfY: Int, halfStep: Boolean): Boolean {
        if (tileset == null) return false
        val points = if (halfStep) listOf(halfX to halfY, halfX - 1 to halfY, halfX + 1 to halfY,
            halfX to halfY + 1, halfX - 1 to halfY + 1, halfX + 1 to halfY + 1) else listOf(halfX to halfY)
        fun tile(x: Int, y: Int, layer: Int): Int {
            val tx = Math.floorDiv(x, 2); val ty = Math.floorDiv(y, 2)
            return map.tile(if (map.loopX) Math.floorMod(tx, map.width) else tx,
                if (map.loopY) Math.floorMod(ty, map.height) else ty, layer)
        }
        for (layer in map.layerCount - 1 downTo 0) {
            val raw = points.map { (x, y) -> tile(x, y, layer) }
            if (raw.all { it >= 0 } && raw.any { tileset.passageForTile(it) and 0x80 != 0 }) return true
        }
        return false
    }
}
