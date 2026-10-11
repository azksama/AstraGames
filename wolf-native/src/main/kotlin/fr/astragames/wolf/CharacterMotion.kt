package fr.astragames.wolf

/** Integer pixel displacement reconstructed from W2 447ad0 and W3 9ead50. */
object WolfCharacterMotion {
    data class State(
        val frequency: Int = 6,
        val autonomousMode: Int = 0,
        val alternateSpeed: Boolean = false,
    )
    data class Step(val xQuarter: Int, val yQuarter: Int, val arrived: Boolean, val moved: Boolean)

    fun validate(state: State): State {
        require(state.frequency in Short.MIN_VALUE..Short.MAX_VALUE && state.autonomousMode in 0..3) {
            "État de déplacement autonome invalide"
        }
        return state
    }

    /** The speed operand is a raw short. It is not a Wolf variable reference. */
    fun increment(words: IntArray, speed: Int, alternate: Boolean, delta: Int): Int {
        require(speed in 0..6 && delta in 1..2) { "Vitesse ou cadence de déplacement hors capacité" }
        val index = speed * 2 + if (alternate) 1 else 2
        require(index < words.size) { "Table des vitesses absente de Game.dat : réglage $index" }
        return words[index].toShort().toInt() * delta
    }

    /** Frequency is a raw signed short; the x86 multiplication has signed 32-bit overflow. */
    fun autonomousReady(state: State, idleTicks: Int): Boolean = idleTicks > state.frequency * state.frequency * 20

    fun step(xQuarter: Int, yQuarter: Int, targetXQuarter: Int, targetYQuarter: Int,
        increment: Int, modern: Boolean, diagonalX: Int = 100, diagonalY: Int = 100): Step {
        require(diagonalX in 0..10_000 && diagonalY in 0..10_000) { "Facteur diagonal invalide" }
        var x = xQuarter
        var y = yQuarter
        fun xStep() = if (!modern || y == targetYQuarter) increment else maxOf(1, increment * diagonalX / 100)
        fun yStep() = if (!modern || x == targetXQuarter) increment else maxOf(1, increment * diagonalY / 100)
        // Native order is observable when one axis arrives before the other.
        if (y < targetYQuarter) y = minOf(targetYQuarter, y + yStep())
        if (x > targetXQuarter) x = maxOf(targetXQuarter, x - xStep())
        if (x < targetXQuarter) x = minOf(targetXQuarter, x + xStep())
        if (y > targetYQuarter) y = maxOf(targetYQuarter, y - yStep())
        return Step(x, y, x == targetXQuarter && y == targetYQuarter, x != xQuarter || y != yQuarter)
    }
}
