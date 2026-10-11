package fr.astragames.wolf

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/** W3's five additional picture effects, separate from the W2-compatible 43-word state. */
data class WolfExtendedPictureEffectState(val id: Int, val words: List<Int>)

/**
 * W3 command 290 picture operations 11, 12, 13, 14 and 17.
 * Evidence: original setters 0099b680, tick 0098f1a0 and draw 0098fd20.
 *
 * Apply [endpoints] to BOTH endpoints of picture150 before interpolating them.
 * Apply [preTransform] after wave7 adds to the current scale and before zoom4/shift2.
 * Insert [flash] after flash0 and before tint1/blink5/6; it transforms the integer
 * flash accumulator, not the final picture150 color. The host performs these calls
 * on the current draw state and never stores transformed endpoints back into150.
 *
 * Durations are already native ticks; command dispatch owns the FPS divisor/delay.
 */
class WolfExtendedPictureEffects {
    private val effects = linkedMapOf<Int, IntArray>()

    fun apply(id: Int, operation: Int, first: Int, second: Int, third: Int, duration: Int) {
        require(operation in OPERATIONS) { "Effet étendu d'image Wolf non porté : $operation" }
        require(duration in 0..MAX_DURATION) { "Durée d'effet étendu d'image hors limites" }
        if (operation != 17) {
            require(first in -MAX_VALUE..MAX_VALUE) { "Paramètre d'effet étendu d'image hors limites" }
            if (operation in 11..12) require(second in -MAX_VALUE..MAX_VALUE) { "Paramètre d'effet étendu d'image hors limites" }
        }
        require(id in effects || effects.size < MAX_EFFECTS) { "Trop d'effets étendus d'image" }
        val w = effects.getOrPut(id, ::initialWords)
        when (operation) {
            11 -> retargetPair(w, PRE_X, PRE_Y, PRE_TOTAL, PRE_LEFT, first, second, duration)
            12 -> retargetPair(w, SCALE_X, SCALE_Y, SCALE_TOTAL, SCALE_LEFT, first, second, duration)
            13 -> retarget(w, ANGLE, ANGLE_TOTAL, ANGLE_LEFT, first, duration)
            14 -> retarget(w, OPACITY, OPACITY_TOTAL, OPACITY_LEFT, first, duration)
            17 -> { w[FLASH] = rgb(first, second, third); w[FLASH_TOTAL] = duration; w[FLASH_LEFT] = duration }
        }
        // Original setters leave 508/509/50a unchanged. The next native tick
        // computes these gates, including a fresh zero-duration effect.
    }

    fun tick() {
        for (w in effects.values) {
            if (w[FLASH_LEFT] > 0) w[FLASH_LEFT]--
            w[PRE_ACTIVE] = 1
            if (w[PRE_LEFT] < 1) {
                if (w[PRE_X] == 0 && w[PRE_Y] == 0) w[PRE_ACTIVE] = 0
            } else if (--w[PRE_LEFT] < 1) { w[PRE_X + 1] = w[PRE_X]; w[PRE_Y + 1] = w[PRE_Y] }
            w[SCALE_ACTIVE] = 1
            if (w[SCALE_LEFT] < 1) {
                if (w[SCALE_X] == 100 && w[SCALE_Y] == 100) w[SCALE_ACTIVE] = 0
            } else if (--w[SCALE_LEFT] < 1) { w[SCALE_X + 1] = w[SCALE_X]; w[SCALE_Y + 1] = w[SCALE_Y] }
            w[ANGLE_OPACITY_ACTIVE] = 1
            if (w[ANGLE_LEFT] > 0 && --w[ANGLE_LEFT] < 1) w[ANGLE + 1] = w[ANGLE]
            if (w[OPACITY_LEFT] > 0 && --w[OPACITY_LEFT] < 1) w[OPACITY + 1] = w[OPACITY]
            if (w[ANGLE_LEFT] == 0 && w[OPACITY_LEFT] == 0 && w[ANGLE] == 0 && w[OPACITY] == 0) w[ANGLE_OPACITY_ACTIVE] = 0
        }
    }

    /** Correct scale, angle and alpha endpoints before command150 interpolates. */
    fun endpoints(base: NativePicture): NativePicture {
        val w = effects[base.id] ?: return base
        var scaleX = base.scaleX
        var scaleY = base.scaleY
        var rotation = base.rotation
        var opacity = base.opacity
        if (w[SCALE_ACTIVE] != 0) {
            scaleX = (base.scaleX.toDouble() * currentFloat(w, SCALE_X, SCALE_LEFT, SCALE_TOTAL) / 100.0).toFloat()
            scaleY = (base.scaleY.toDouble() * currentFloat(w, SCALE_Y, SCALE_LEFT, SCALE_TOTAL) / 100.0).toFloat()
        }
        if (w[ANGLE_OPACITY_ACTIVE] != 0) {
            // SSE converts the sum of the integer base angle and target first,
            // subtracts a float32 correction, then CVTTSS2SI. Adding a separately
            // rounded angle offset after150 changes the original result.
            val tenths = nativeTenths(base.rotation)
            val angleProduct = (w[ANGLE] - w[ANGLE + 1]).toFloat() * w[ANGLE_LEFT].toFloat()
            val angle = (tenths + w[ANGLE]).toFloat() - angleProduct / w[ANGLE_TOTAL].coerceAtLeast(1).toFloat()
            rotation = nativeTruncate(angle.toDouble()) / 10f
            val alphaProduct = (w[OPACITY] - w[OPACITY + 1]).toFloat() * w[OPACITY_LEFT].toFloat()
            val alpha = (w[OPACITY].toFloat() - alphaProduct / w[OPACITY_TOTAL].coerceAtLeast(1).toFloat()) + base.opacity.toFloat()
            opacity = nativeTruncate(alpha.toDouble())
        }
        return base.copy(scaleX = scaleX, scaleY = scaleY, rotation = rotation, opacity = opacity)
    }

    /** Relative XY movement uses the current angle and wave-adjusted scale. */
    fun preTransform(base: NativePicture): NativePicture {
        val w = effects[base.id] ?: return base
        if (w[PRE_ACTIVE] == 0) return base
        val x = currentDouble(w, PRE_X, PRE_LEFT, PRE_TOTAL)
        val y = currentDouble(w, PRE_Y, PRE_LEFT, PRE_TOTAL)
        val angle = nativeTenths(base.rotation) * 3.1415926535 / 1800.0
        val scaleX = base.scaleX.toDouble() * 100.0
        val scaleY = base.scaleY.toDouble() * 100.0
        val dx = (cos(angle) * x * scaleX + sin(-angle) * y * scaleY) / 100.0
        val dy = (sin(angle) * x * scaleX + cos(angle) * y * scaleY) / 100.0
        return base.copy(x = (base.x + dx).toFloat(), y = (base.y + dy).toFloat())
    }

    /** W3 flash17's triangular Double accumulator, before tint and cyclic blink. */
    fun flash(id: Int, prior: NativeTone): NativeTone {
        val w = effects[id] ?: return prior
        if (w[FLASH_LEFT] <= 0) return prior
        val half = w[FLASH_TOTAL] / 2
        val weight = (half - abs(w[FLASH_LEFT] - half)).toDouble() / half.toDouble()
        fun channel(shift: Int, value: Int) = nativeTruncate(((w[FLASH] ushr shift and 255) - 100) * 2.55 * weight + value.toDouble())
        // Duration1 follows CVTTSD2SI's indefinite-integer result when its half
        // is zero. Silently clamping that period invents a different native flash.
        return NativeTone(channel(16, prior.red), channel(8, prior.green), channel(0, prior.blue))
    }

    fun erase(id: Int) { effects.remove(id) }
    fun reset() { effects.clear() }
    fun state(): List<WolfExtendedPictureEffectState> = effects.map { (id, w) -> WolfExtendedPictureEffectState(id, w.toList()) }
    fun restore(states: List<WolfExtendedPictureEffectState>) {
        require(states.size <= MAX_EFFECTS) { "Trop d'effets étendus d'image sauvegardés" }
        require(states.map { it.id }.toSet().size == states.size) { "État d'effet étendu d'image dupliqué" }
        require(states.all { validWords(it.words) }) { "État d'effet étendu d'image invalide" }
        effects.clear()
        for (state in states) effects[state.id] = state.words.toIntArray()
    }

    companion object {
        const val WORD_COUNT = 26
        const val MAX_DURATION = 360_000
        const val MAX_EFFECTS = 10_000
        private const val MAX_VALUE = 1_000_000
        private val OPERATIONS = setOf(11, 12, 13, 14, 17)
        // Native words10f..122, e4..e6, followed by the three byte gates.
        private const val PRE_X = 0; private const val PRE_Y = 2; private const val PRE_TOTAL = 4; private const val PRE_LEFT = 5
        private const val SCALE_X = 6; private const val SCALE_Y = 8; private const val SCALE_TOTAL = 10; private const val SCALE_LEFT = 11
        private const val ANGLE = 12; private const val ANGLE_TOTAL = 14; private const val ANGLE_LEFT = 15
        private const val OPACITY = 16; private const val OPACITY_TOTAL = 18; private const val OPACITY_LEFT = 19
        private const val FLASH = 20; private const val FLASH_TOTAL = 21; private const val FLASH_LEFT = 22
        private const val PRE_ACTIVE = 23; private const val SCALE_ACTIVE = 24; private const val ANGLE_OPACITY_ACTIVE = 25

        private fun initialWords() = IntArray(WORD_COUNT).apply { for (index in SCALE_X..SCALE_Y + 1) this[index] = 100 }
        private fun rgb(red: Int, green: Int, blue: Int) = ((red / 2 + 100).coerceIn(0, 200) shl 16) or
            ((green / 2 + 100).coerceIn(0, 200) shl 8) or (blue / 2 + 100).coerceIn(0, 200)
        private fun currentInteger(w: IntArray, target: Int, left: Int, total: Int) =
            w[target] - (w[target] - w[target + 1]) * w[left] / w[total].coerceAtLeast(1)
        private fun currentFloat(w: IntArray, target: Int, left: Int, total: Int) =
            w[target].toFloat() - (w[target] - w[target + 1]).toFloat() * w[left].toFloat() / w[total].coerceAtLeast(1).toFloat()
        private fun currentDouble(w: IntArray, target: Int, left: Int, total: Int) =
            w[target].toDouble() - (w[target] - w[target + 1]).toDouble() * w[left].toDouble() / w[total].coerceAtLeast(1).toDouble()
        private fun nativeTenths(angle: Float): Int {
            require(angle.isFinite() && abs(angle) <= 1_000_000f) { "Angle de picture Wolf invalide" }
            return round(angle * 10f).toInt()
        }
        private fun nativeTruncate(value: Double) = if (value.isNaN() || value < Int.MIN_VALUE || value >= 2_147_483_648.0) Int.MIN_VALUE else value.toInt()
        private fun retarget(w: IntArray, target: Int, total: Int, left: Int, value: Int, duration: Int) {
            w[target + 1] = currentInteger(w, target, left, total)
            w[target] = value; w[total] = duration; w[left] = duration
        }
        private fun retargetPair(w: IntArray, x: Int, y: Int, total: Int, left: Int, first: Int, second: Int, duration: Int) {
            val fromX = currentInteger(w, x, left, total)
            val fromY = currentInteger(w, y, left, total)
            w[x] = first; w[x + 1] = fromX; w[y] = second; w[y + 1] = fromY; w[total] = duration; w[left] = duration
        }
        private fun validWords(w: List<Int>): Boolean {
            if (w.size != WORD_COUNT) return false
            // Retargeting uses signed32 IMUL/IDIV. Its derived FROM words can
            // exceed the parameter bounds after native overflow; retain them.
            for (index in intArrayOf(PRE_X, PRE_Y, SCALE_X, SCALE_Y, ANGLE, OPACITY)) if (w[index] !in -MAX_VALUE..MAX_VALUE) return false
            for ((total, left) in listOf(PRE_TOTAL to PRE_LEFT, SCALE_TOTAL to SCALE_LEFT, ANGLE_TOTAL to ANGLE_LEFT, OPACITY_TOTAL to OPACITY_LEFT, FLASH_TOTAL to FLASH_LEFT)) if (w[total] !in 0..MAX_DURATION || w[left] !in 0..w[total]) return false
            if (w[FLASH] < 0 || w[FLASH] ushr 24 != 0) return false
            for (shift in intArrayOf(0, 8, 16)) if (((w[FLASH] ushr shift) and 255) > 200) return false
            return w[PRE_ACTIVE] in 0..1 && w[SCALE_ACTIVE] in 0..1 && w[ANGLE_OPACITY_ACTIVE] in 0..1
        }
    }
}
