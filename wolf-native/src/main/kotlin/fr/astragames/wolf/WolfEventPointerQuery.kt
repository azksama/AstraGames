package fr.astragames.wolf

/** W3 a16ce9/a172a7. Pointer hit-testing uses the collision foot, not the sprite rectangle. */
internal object WolfEventPointerQuery {
    data class Actor(val id: Int, val x: Int, val y: Int, val square: Boolean, val reachX: Int = 0, val reachY: Int = 0)
    fun find(actors: Iterable<Actor>, x: Int, y: Int, tile: Int, width: Int, height: Int,
        loopX: Boolean, loopY: Boolean, extended: Boolean): Int {
        require(tile > 0 && width > 0 && height > 0)
        var selected = -1
        for (actor in actors) for (copy in 0..3) {
            val wrapX = copy and 1 != 0
            val wrapY = copy and 2 != 0
            // The ordinary modern branch checks all four copies unconditionally.
            if (extended && (wrapX && !loopX || wrapY && !loopY)) continue
            val dx = x - (actor.x - if (wrapX) width else 0)
            val dy = y - (actor.y - if (wrapY) height else 0)
            val rx = if (extended) actor.reachX * tile / 2 else 0
            val ry = if (extended) actor.reachY * tile / 2 else 0
            if (dx < -rx || dx >= tile + rx) continue
            // CDQ clears EDX before the subtraction in both branches: a flat half-tile foot.
            val lower = if (actor.square) 0 else tile / 2
            if (dy < lower - ry || dy >= tile + ry) continue
            if (selected == -1 || actor.id < selected) selected = actor.id
        }
        return selected
    }
}
