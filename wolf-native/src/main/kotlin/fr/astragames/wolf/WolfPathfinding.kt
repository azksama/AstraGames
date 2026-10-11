package fr.astragames.wolf

import kotlin.math.abs

/** Full-tile hidden-code search reconstructed from W3 42ba10/42b7d0/42f9c0. */
internal object WolfPathfinding {
    data class Point(val x: Int, val y: Int)
    data class Request(
        val directions: Int,
        val start: Point,
        val goal: Point,
        val version2: Boolean = false,
        val positions: Boolean = false,
        val steps: Boolean = false,
        val time: Boolean = false,
        val length: Int = 0,
        val executions: Int = 10_000,
        val movingEvent: Int = -999,
    )
    data class Result(val path: List<Point>?, val pops: Int, val tooFar: Boolean) {
        val fullSteps get() = maxOf(0, (path?.size ?: 0) - 1)
    }

    data class Occupant(val id: Int, val halfX: Int, val halfY: Int, val through: Boolean,
        val foot: Boolean, val graphicLength: Int, val graphicTile: Int)
    class Occupancy(val width: Int, val height: Int, private val values: Map<Long, Int>) {
        fun marker(x: Int, y: Int) = values[coordinateKey(x, y)] ?: 0
        val markers get() = IntArray(width * height) { marker(it / height, it % height) }
        fun allows(from: Point, to: Point): Boolean = marker(to.x, to.y) < 1 &&
            (from.x == to.x || from.y == to.y || marker(to.x, from.y) < 1 && marker(from.x, to.y) < 1)
    }
    private fun coordinateKey(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    /** W3 95a9b0→95acb0: snapped half-grid footprints, overlap sentinels and goal allowance. */
    fun occupancy(width: Int, height: Int, actors: List<Occupant>, movingId: Int, goal: Point): Occupancy {
        require(width > 0 && height > 0 && width.toLong() * height <= 4_000_000 && actors.size <= 100_000)
        val cells = hashMapOf<Long, Int>()
        actors.forEach { a ->
            if (a.through || movingId == -2 && a.id >= 0 && a.graphicLength == 0 && a.graphicTile == -1) return@forEach
            val x = a.halfX / 2
            val columns = if (a.halfX % 2 == 1) 2 else 1
            var halfY = a.halfY
            val rows = if (halfY % 2 == 1 && a.foot) 2 else 1
            if (halfY % 2 == 1 && !a.foot) halfY++
            for (cx in x until x + columns) for (cy in halfY / 2 until halfY / 2 + rows) {
                val key = coordinateKey(cx, cy)
                cells[key] = if ((cells[key] ?: 0) == 0) a.id + 10 else Int.MAX_VALUE
            }
        }
        actors.firstOrNull { it.id == movingId }?.let { a ->
            if (a.through) cells.clear()
            else for (x in a.halfX / 2 until a.halfX / 2 + 2) for (y in a.halfY / 2 until a.halfY / 2 + 2) {
                val key = coordinateKey(x, y)
                if (cells[key] == movingId + 10) cells.remove(key)
            }
        }
        val goalMarker = cells[coordinateKey(goal.x, goal.y)] ?: 0
        if (goalMarker != 0) for (x in goal.x until goal.x + 2) for (y in goal.y until goal.y + 2) {
            val key = coordinateKey(x, y)
            if (cells[key] == goalMarker) cells.remove(key)
        }
        return Occupancy(width, height, cells)
    }

    private data class Entry(val point: Point, val cost: Long)
    private val directions4 = listOf(Point(0, -1), Point(1, 0), Point(0, 1), Point(-1, 0))
    private val directions8 = listOf(Point(0, -1), Point(1, -1), Point(1, 0), Point(1, 1),
        Point(0, 1), Point(-1, 1), Point(-1, 0), Point(-1, -1))

    /** Byte grid uses native column-major indexing. Bits follow clockwise directions from up. */
    class Grid(val width: Int, val height: Int, val wrapX: Boolean, val wrapY: Boolean, val masks: IntArray) {
        init { require(width > 0 && height > 0 && width.toLong() * height <= 4_000_000 && masks.size == width * height) }
        fun index(x: Int, y: Int): Int? {
            val px = if (wrapX) Math.floorMod(x, width) else x
            val py = if (wrapY) Math.floorMod(y, height) else y
            return if (px in 0 until width && py in 0 until height) px * height + py else null
        }
        fun mask(x: Int, y: Int) = index(x, y)?.let { masks[it] } ?: 0
        fun allows(from: Point, rawNext: Point): Boolean {
            val delta = Point(rawNext.x - from.x, rawNext.y - from.y)
            val bit = directions8.indexOf(delta)
            return bit >= 0 && mask(from.x, from.y) and (1 shl bit) != 0
        }
        /** v2 only opens cardinal entrances from already nonempty neighbors (42b4a0/42b4f0). */
        fun openedGoal(goal: Point): Grid {
            val result = Grid(width, height, wrapX, wrapY, masks.copyOf())
            result.index(goal.x, goal.y)?.let { result.masks[it] = 255 }
            listOf(Point(-1, 0) to 4, Point(1, 0) to 64, Point(0, -1) to 16, Point(0, 1) to 1).forEach { (delta, bit) ->
                result.index(goal.x + delta.x, goal.y + delta.y)?.let { if (result.masks[it] != 0) result.masks[it] = result.masks[it] or bit }
            }
            return result
        }
    }

    /** 9fb530: source/destination cardinal passage and every edge of a diagonal square. */
    fun buildGrid(width: Int, height: Int, wrapX: Boolean, wrapY: Boolean, cardinalFlags: IntArray): Grid {
        val raw = Grid(width, height, wrapX, wrapY, cardinalFlags)
        val output = IntArray(cardinalFlags.size)
        fun has(x: Int, y: Int, bit: Int) = raw.mask(x, y) and bit != 0
        for (x in 0 until width) for (y in 0 until height) {
            val up = has(x, y, 1) && has(x, y - 1, 16)
            val right = has(x, y, 4) && has(x + 1, y, 64)
            val down = has(x, y, 16) && has(x, y + 1, 1)
            val left = has(x, y, 64) && has(x - 1, y, 4)
            var mask = (if (up) 1 else 0) or (if (right) 4 else 0) or (if (down) 16 else 0) or (if (left) 64 else 0)
            if (up && left && has(x - 1, y, 1) && has(x - 1, y - 1, 16) && has(x, y - 1, 64) && has(x - 1, y - 1, 4)) mask = mask or 128
            if (up && right && has(x + 1, y, 1) && has(x + 1, y - 1, 16) && has(x, y - 1, 4) && has(x + 1, y - 1, 64)) mask = mask or 2
            if (down && left && has(x - 1, y, 16) && has(x - 1, y + 1, 1) && has(x, y + 1, 64) && has(x - 1, y + 1, 4)) mask = mask or 32
            if (down && right && has(x + 1, y, 16) && has(x + 1, y + 1, 1) && has(x, y + 1, 4) && has(x + 1, y + 1, 64)) mask = mask or 8
            output[x * height + y] = mask
        }
        return Grid(width, height, wrapX, wrapY, output)
    }

    /** C++ heap equal priorities stay put on push; the right child wins a tie on pop. */
    private class Heap {
        private val entries = ArrayList<Entry>()
        val isEmpty get() = entries.isEmpty()
        fun push(entry: Entry) {
            var hole = entries.size
            entries.add(entry)
            while (hole > 0) {
                val parent = (hole - 1) / 2
                if (entries[parent].cost <= entry.cost) break
                entries[hole] = entries[parent]; hole = parent
            }
            entries[hole] = entry
        }
        fun pop(): Entry {
            val first = entries[0]
            val value = entries.removeAt(entries.lastIndex)
            if (entries.isEmpty()) return first
            var hole = 0
            var right = 2
            while (right < entries.size) {
                val child = if (entries[right].cost <= entries[right - 1].cost) right else right - 1
                entries[hole] = entries[child]; hole = child; right = (hole + 1) * 2
            }
            if (right == entries.size) { entries[hole] = entries[right - 1]; hole = right - 1 }
            while (hole > 0) {
                val parent = (hole - 1) / 2
                if (entries[parent].cost <= value.cost) break
                entries[hole] = entries[parent]; hole = parent
            }
            entries[hole] = value
            return first
        }
    }

    /** The caller supplies the engine's tile passage, and optional actor-grid test. */
    fun search(width: Int, height: Int, wrapX: Boolean, wrapY: Boolean, request: Request,
        passage: (Point, Point) -> Boolean, actorPassage: ((Point, Point) -> Boolean)? = null): Result {
        require(width > 0 && height > 0 && width.toLong() * height <= 4_000_000) { "Carte de recherche hors capacité" }
        require(request.directions in setOf(4, 8)) { "Nombre de directions de recherche non porté" }
        require(request.executions <= 1_000_000) { "Budget de recherche hors capacité" }
        val start = Point(Math.floorMod(request.start.x, width), Math.floorMod(request.start.y, height))
        val goal = Point(Math.floorMod(request.goal.x, width), Math.floorMod(request.goal.y, height))
        fun heuristic(p: Point): Int {
            var dx = abs(p.x - goal.x); var dy = abs(p.y - goal.y)
            if (wrapX) dx = minOf(dx, width - dx)
            if (wrapY) dy = minOf(dy, height - dy)
            // The original helper performs signed 32-bit arithmetic before extending the priority.
            return if (request.directions == 8) dx * dx + dy * dy else dx + dy
        }
        val open = Heap()
        val best = hashMapOf(start to 0L)
        val previous = hashMapOf<Point, Point>()
        open.push(Entry(start, heuristic(start).toLong()))
        var pops = 0
        var found = false
        val deltas = if (request.directions == 8) directions8 else directions4
        while (pops < request.executions && !open.isEmpty) {
            val current = open.pop().point; pops++
            if (current == goal) { found = true; break }
            deltas.forEach { delta ->
                val raw = Point(current.x + delta.x, current.y + delta.y)
                if (!wrapX && raw.x !in 0 until width || !wrapY && raw.y !in 0 until height) return@forEach
                val next = Point(if (wrapX) Math.floorMod(raw.x, width) else raw.x,
                    if (wrapY) Math.floorMod(raw.y, height) else raw.y)
                if (!passage(current, raw) || actorPassage?.invoke(current, raw) == false) return@forEach
                // There is no stale-entry shortcut in 42ba10: every extraction counts and uses best G.
                val distance = (best[current] ?: error("Coût de recherche absent")) + if (delta.x == 0 || delta.y == 0) 100 else 101
                if (distance < (best[next] ?: Long.MAX_VALUE)) {
                    best[next] = distance; previous[next] = current
                    open.push(Entry(next, distance + heuristic(next)))
                }
            }
        }
        // Dispatcher a2e2d0 reports TooFar even when the goal was extracted on the last allowed pop.
        val tooFar = (!open.isEmpty || found) && pops >= request.executions
        if (!found || tooFar) return Result(null, pops, tooFar)
        val reverse = ArrayList<Point>(); var cursor = goal
        reverse.add(cursor)
        while (cursor != start) {
            cursor = previous[cursor] ?: error("Chemin natif incomplet")
            reverse.add(cursor)
            require(reverse.size <= width.toLong() * height + 1) { "Cycle dans la recherche de chemin" }
        }
        reverse.reverse()
        return Result(reverse, pops, false)
    }

    /** W3 42c6a0/42ca30: LEN limits formatting only, never search or the full STEP result. */
    fun format(result: Result, request: Request, width: Int, height: Int, wrapX: Boolean, wrapY: Boolean,
        elapsedMilliseconds: Int = 0): String {
        val path = result.path
        val body = when {
            path == null -> if (result.tooFar) "<<NoPath/TooFar>>" else "<<NoPath>>"
            else -> serialize(path, request.positions, request.length, width, height, wrapX, wrapY)
        }
        val prefix = if (request.steps) "${result.fullSteps}(=STEP)\n" else ""
        val suffix = if (request.time) "\nTIME=${elapsedMilliseconds}ms/EXE=${result.pops}/STEP=${result.fullSteps}" else ""
        return prefix + body + suffix
    }

    fun serialize(path: List<Point>, positions: Boolean, length: Int, width: Int, height: Int,
        wrapX: Boolean, wrapY: Boolean): String {
        require(path.isNotEmpty()) { "Chemin sans origine" }
        val count = path.size - 1
        val output = StringBuilder()
        for (index in 1..count) {
            if (length != 0 && length <= index - 1) return output.toString()
            val previous = path[index - 1]; val point = path[index]
            if (positions) output.append(point.x).append(',').append(point.y).append('/')
            else {
                fun wrappedDelta(a: Int, b: Int, dimension: Int, wraps: Boolean): Int {
                    var d = b - a
                    if (wraps && d > dimension / 2) d -= dimension
                    else if (wraps && d < -(dimension / 2)) d += dimension
                    return d.coerceIn(-1, 1)
                }
                val dx = wrappedDelta(previous.x, point.x, width, wrapX)
                val dy = wrappedDelta(previous.y, point.y, height, wrapY)
                output.append(when (dy) { -1 -> when (dx) { -1 -> '7'; 0 -> '8'; else -> '9' }
                    0 -> when (dx) { -1 -> '4'; 0 -> '5'; else -> '6' }
                    else -> when (dx) { -1 -> '1'; 0 -> '2'; else -> '3' } })
            }
        }
        output.append(if (positions) "END" else "/END")
        return output.toString()
    }
}
