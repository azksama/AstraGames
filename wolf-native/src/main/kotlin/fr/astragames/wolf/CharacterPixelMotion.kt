package fr.astragames.wolf

/** W3 FUN_009eeec0. Logical half-cells and residual pixels are independent of interpolated pixels. */
internal object WolfCharacterPixelMotion {
    data class State(
        val halfX: Int, val halfY: Int,
        val offsetX: Int = 0, val offsetY: Int = 0,
        val requestedX: Int = 0, val requestedY: Int = 0,
        val actualX: Int = 0, val actualY: Int = 0,
        val moved: Boolean = false, val crossed: Boolean = false,
    )
    data class Result(val state: State, val realXQuarter: Int, val realYQuarter: Int)

    fun move(initial: State, dx: Int, dy: Int, tileSize: Int, realXQuarter: Int, realYQuarter: Int,
        passage: (Int, Int, Int) -> Boolean): Result {
        require(tileSize in 2..256 && tileSize % 2 == 0) { "Taille de grille pixel invalide" }
        val half = tileSize / 2
        require(initial.offsetX in 0 until half && initial.offsetY in 0 until half) { "Reste de déplacement pixel invalide" }
        require(kotlin.math.abs(dx.toLong()) <= 1_000_000 && kotlin.math.abs(dy.toLong()) <= 1_000_000) { "Déplacement pixel hors limites" }
        var hx = initial.halfX; var hy = initial.halfY
        var ox = initial.offsetX; var oy = initial.offsetY
        var rx = realXQuarter; var ry = realYQuarter
        var crossed = initial.crossed
        var x = dx; var y = dy
        if (dx < 0) while (x != 0) {
            if (!passage(hx, hy, 4) || oy > 0 && !passage(hx - 1, hy, 2)) {
                val delta = maxOf(x, -ox); rx += delta * 4; ox += delta; break
            }
            if (ox + x >= 0) { rx += x * 4; ox += x; break }
            hx--; crossed = true; rx -= half * 4; x += half
        }
        if (dx > 0) while (x != 0) {
            if (!passage(hx, hy, 6) || oy > 0 && !passage(hx + 1, hy, 2)) {
                rx -= ox * 4; ox = 0; break
            }
            if (ox + x < half) { rx += x * 4; ox += x; break }
            hx++; crossed = true; rx += half * 4; x -= half
        }
        if (dy < 0) while (y != 0) {
            if (!passage(hx, hy, 8) || ox > 0 && !passage(hx, hy - 1, 6)) {
                val delta = maxOf(y, -oy); ry += delta * 4; oy += delta; break
            }
            if (oy + y >= 0) { ry += y * 4; oy += y; break }
            hy--; crossed = true; ry -= half * 4; y += half
        }
        if (dy > 0) while (y != 0) {
            if (!passage(hx, hy, 2) || ox > 0 && !passage(hx, hy + 1, 6)) {
                ry -= oy * 4; oy = 0; break
            }
            if (oy + y < half) { ry += y * 4; oy += y; break }
            hy++; crossed = true; ry += half * 4; y -= half
        }
        return Result(State(hx, hy, ox, oy, initial.requestedX + dx, initial.requestedY + dy,
            initial.actualX + (rx - realXQuarter) / 4, initial.actualY + (ry - realYQuarter) / 4,
            initial.moved || dx != 0 || dy != 0, crossed), rx, ry)
    }
}
