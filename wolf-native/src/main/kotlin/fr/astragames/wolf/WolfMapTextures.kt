package fr.astragames.wolf

/**
 * WOLF 2/3 map background/fog phase and clipped bitmap geometry.
 *
 * REA: Game.exe 4ee2a905..., 9ce6e0/9cf200 (tick), 9ce7f0/9cfe90
 * (draw), 753d60 (DerivationGraph), 75a0a0 (DrawRectGraph). All arithmetic
 * below is signed Int32, including wrapping multiplication/addition and
 * division towards zero. W2: Game.exe 795203a6..., 4d4160/4d32b0
 * (tick), 4d3480/4d1e20 (draw). The profile preserves W2's second
 * extent addition and signed remainder, instead of W3's negative loop.
 * Resource lookup and variable bindings belong to the VM.
 */
object WolfMapTextures {
    enum class Kind { BACKGROUND, FOG }

    data class State(
        val active: Boolean = false,
        val width: Int = 2,
        val height: Int = 2,
        val accumulatedX: Int = 0,
        val accumulatedY: Int = 0,
        /** Visible phase, in eighths of a logical pixel. */
        val phaseX: Int = 0,
        val phaseY: Int = 0,
        val parallaxDivisor: Int = 0,
    )

    data class Parameters(
        val speedX: Int = 0,
        val speedY: Int = 0,
        /** Native 60 / configured FPS, as established by the config binder. */
        val fpsDivisor: Int = 1,
        val cameraQuarterX: Int = 0,
        val cameraQuarterY: Int = 0,
        /** Raw native shake pixel offsets, added directly to eighth-pixel phases. */
        val shakeEighthX: Int = 0,
        val shakeEighthY: Int = 0,
    )

    data class Rectangle(
        val sourceLeft: Int, val sourceTop: Int,
        val sourceRight: Int, val sourceBottom: Int,
        val destinationLeft: Int, val destinationTop: Int,
        val destinationRight: Int, val destinationBottom: Int,
    ) {
        val drawable: Boolean get() = sourceRight > sourceLeft && sourceBottom > sourceTop
    }

    data class Geometry(
        /** Draw mutates phases 4..7 to 8 only on the non-unit zoom path. */
        val phaseX: Int,
        val phaseY: Int,
        val rectangles: List<Rectangle>,
    )

    fun cold(): State = State()

    /** Filename reload clears phases without the ID loader's initial advance. */
    fun reset(width: Int, height: Int, parallaxDivisor: Int = 0, active: Boolean = true): State =
        State(active, width, height, parallaxDivisor = parallaxDivisor).also(::validate)

    /** ID loaders clear all phases and immediately perform one native advance. */
    fun reload(
        width: Int, height: Int, kind: Kind, parameters: Parameters,
        parallaxDivisor: Int = 0, active: Boolean = true, behaviorVersion: Int = 3,
    ): State = advance(reset(width, height, parallaxDivisor, active), kind, parameters, behaviorVersion)

    fun validate(state: State, behaviorVersion: Int = 3) {
        require(behaviorVersion == 2 || behaviorVersion == 3) { "Unknown map texture behavior profile" }
        require(state.width in 0..Int.MAX_VALUE / 8 && state.height in 0..Int.MAX_VALUE / 8) {
            "Map texture dimensions overflow the native eighth-pixel range"
        }
        // Equality is possible when draw's 4..7 -> 8 correction reaches a 1px edge.
        fun minimum(extent: Int): Int = if (behaviorVersion == 2 && extent > 0) 1 - extent * 8 else 0
        require(state.phaseX in minimum(state.width)..state.width * 8 &&
            state.phaseY in minimum(state.height)..state.height * 8) {
            "Map texture phase is outside its native bitmap extent"
        }
    }

    fun advance(state: State, kind: Kind, parameters: Parameters, behaviorVersion: Int = 3): State {
        validate(state, behaviorVersion)
        require(parameters.fpsDivisor > 0) { "Native FPS divisor must be positive" }
        if (!state.active || state.width == 0 || state.height == 0) return state
        fun axis(accumulated: Int, extent: Int, speed: Int, camera: Int, shake: Int): Pair<Int, Int> {
            val range = extent * 8
            val increment = when (kind) {
                Kind.BACKGROUND -> speed * parameters.fpsDivisor
                Kind.FOG -> speed / parameters.fpsDivisor
            }
            var accumulator = accumulated + increment + range
            val cameraOffset = if (state.parallaxDivisor == 0) 0
                else nativeDivide(-2 * camera, state.parallaxDivisor)
            var value = accumulator + cameraOffset + shake
            if (behaviorVersion == 2) {
                // W2 adds the bitmap extent twice and leaves negative remainders signed.
                value += range
            } else if (value < 0) {
                // Equivalent to W3's repeated additions while value < 0.
                value = Math.floorMod(value, range)
            }
            if (range < value) accumulator += value % range - value
            return accumulator to value % range
        }
        val x = axis(state.accumulatedX, state.width, parameters.speedX,
            parameters.cameraQuarterX, parameters.shakeEighthX)
        val y = axis(state.accumulatedY, state.height, parameters.speedY,
            parameters.cameraQuarterY, parameters.shakeEighthY)
        return state.copy(accumulatedX = x.first, accumulatedY = y.first, phaseX = x.second, phaseY = y.second)
    }

    /**
     * Four clipped parts of one bitmap extent, not a repeat-to-viewport fill.
     * Coordinates are native physical pixels. Camera/shake are already in the
     * supplied phase. This function never advances the simulation.
     */
    fun layout(
        width: Int, height: Int, phaseX: Int, phaseY: Int,
        physicalScale: Int = 1, zoom: Int = 100, originX: Int = 0, originY: Int = 0,
        behaviorVersion: Int = 3,
    ): Geometry {
        validate(State(width = width, height = height, phaseX = phaseX, phaseY = phaseY), behaviorVersion)
        require(physicalScale > 0) { "Native physical scale must be positive" }
        require(zoom > 0) { "Native map zoom must be positive" }
        if (width == 0 || height == 0) return Geometry(phaseX, phaseY, emptyList())
        val unit = physicalScale * zoom == 100
        val px = if (!unit && phaseX in 4..7) 8 else phaseX
        val py = if (!unit && phaseY in 4..7) 8 else phaseY
        val cutX = px / 8
        val cutY = py / 8
        fun coordinates(phase: Int, cut: Int, extent: Int, origin: Int): IntArray {
            if (unit) return intArrayOf(0, cut, extent)
            val delta = zoom - 100
            // The negative reciprocal IMUL is equivalent to negating the
            // quotient, after the signed Int32 product has already wrapped.
            val first = -(delta * origin * physicalScale / 100)
            val middle = phase * physicalScale / 8 + (cut - origin) * delta * physicalScale / 100
            val last = extent * physicalScale + (extent - origin) * delta * physicalScale / 100
            return intArrayOf(first, middle, last)
        }
        val x = coordinates(px, cutX, width, originX)
        val y = coordinates(py, cutY, height, originY)
        val rectangles = listOf(
            Rectangle(0, 0, width - cutX, height - cutY, x[1], y[1], x[2], y[2]),
            Rectangle(width - cutX, height - cutY, width, height, x[0], y[0], x[1], y[1]),
            Rectangle(width - cutX, 0, width, height - cutY, x[0], y[1], x[1], y[2]),
            Rectangle(0, height - cutY, width - cutX, height, x[1], y[0], x[2], y[1]),
        )
        return Geometry(px, py, rectangles)
    }

    fun layout(state: State, physicalScale: Int = 1, zoom: Int = 100, originX: Int = 0, originY: Int = 0,
        behaviorVersion: Int = 3): Geometry {
        validate(state, behaviorVersion)
        if (!state.active) return Geometry(state.phaseX, state.phaseY, emptyList())
        return layout(state.width, state.height, state.phaseX, state.phaseY, physicalScale, zoom, originX, originY,
            behaviorVersion)
    }

    private fun nativeDivide(value: Int, divisor: Int): Int {
        if (value == Int.MIN_VALUE && divisor == -1) throw ArithmeticException("Native IDIV quotient overflows Int32")
        return value / divisor
    }
}
