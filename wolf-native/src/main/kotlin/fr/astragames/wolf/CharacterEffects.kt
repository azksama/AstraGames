package fr.astragames.wolf

import kotlin.math.abs

/** Portable order of W2 actor words 0xe0..0x114 (W3 0xd8..0x10c). */
data class WolfCharacterEffectState(val id: Int, val words: List<Int>)

/** The two inspected native updaters have different semantics for long shake counts. */
enum class WolfCharacterEffectProfile { WOLF2, WOLF3 }

/**
 * Character-category command 290 operations 0..3. W2 setters 0x4c3020/60/f0,
 * updater 0x4475b0 and draw 0x4c6e80. Unlike pictures, character RGB is clamped
 * directly to -100..100, ratios are quantized to thousandths before x87 colour
 * conversion, and shake offsets are computed before the phase is decremented.
 * The host resolves hero/current-event/party IDs before applying an operation.
 */
class WolfCharacterEffects(private val profile: WolfCharacterEffectProfile = WolfCharacterEffectProfile.WOLF2) {
    private val effects = linkedMapOf<Int, IntArray>()

    fun apply(id: Int, operation: Int, first: Int, second: Int, third: Int, duration: Int) {
        require(operation in 0..3) { "Effet de personnage non porté : $operation" }
        require(duration in 0..MAX_DURATION) { "Durée d'effet de personnage hors limites" }
        if (operation == 1) {
            require(first in -MAX_OFFSET..MAX_OFFSET && second in -MAX_OFFSET..MAX_OFFSET) { "Amplitude de personnage hors limites" }
            require(third >= 0) { "Nombre de secousses de personnage hors limites" }
        }
        require(id in effects || effects.size < MAX_EFFECTS) { "Trop d'effets de personnage" }
        val w = effects.getOrPut(id) { IntArray(WORD_COUNT) }
        when (operation) {
            0 -> { w[FLASH] = rgb(first, second, third); w[FLASH_TOTAL] = duration; w[FLASH_LEFT] = duration }
            1 -> {
                w[SHAKE_X] = first; w[SHAKE_Y] = second; w[SHAKE_COUNT] = third
                w[SHAKE_HALF] = duration.coerceAtLeast(1); w[SHAKE_PHASE] = w[SHAKE_HALF] * 2
            }
            2, 3 -> { w[BLINK] = rgb(first, second, third); w[BLINK_PERIOD] = duration; w[BLINK_PHASE] = 0; w[BLINK_MODE] = operation - 2 }
        }
    }

    fun tick() {
        for (w in effects.values) {
            if (w[FLASH_LEFT] > 0) w[FLASH_LEFT]--
            if (w[BLINK_PERIOD] > 0) w[BLINK_PHASE] = (w[BLINK_PHASE] + 1) %
                (w[BLINK_PERIOD] * (if (w[BLINK_MODE] == 0) 2 else 1))
            if (w[SHAKE_COUNT] == 0) { w[OFFSET_X] = 0; w[OFFSET_Y] = 0 }
            else {
                val sign = if (w[SHAKE_COUNT] % 2 == 0) -1 else 1
                fun offset(amplitude: Int) = if (w[SHAKE_PHASE] < w[SHAKE_HALF])
                    (amplitude.toLong() * w[SHAKE_PHASE] / w[SHAKE_HALF]).toInt()
                else amplitude - ((w[SHAKE_PHASE] - w[SHAKE_HALF]).toLong() * amplitude / w[SHAKE_HALF]).toInt()
                w[OFFSET_X] = sign * offset(w[SHAKE_X]); w[OFFSET_Y] = sign * offset(w[SHAKE_Y])
                if (--w[SHAKE_PHASE] < 1) {
                    val count = w[SHAKE_COUNT]
                    // W3 009ea7f0: counts above 99999 alternate parity forever. W2 always decrements.
                    w[SHAKE_COUNT] = if (profile == WolfCharacterEffectProfile.WOLF3 && count > 99_999 && count % 2 == 0)
                        count + 1 else count - 1
                    w[SHAKE_PHASE] = w[SHAKE_HALF] * 2
                }
            }
        }
    }

    /** Offsets also move the shadow's ground point; colour is applied to the sprite only. */
    fun character(base: NativeCharacter): NativeCharacter {
        val w = effects[base.id] ?: return base
        fun channel(shift: Int): Int {
            if (w[FLASH_LEFT] == 0 && w[BLINK_PERIOD] == 0) return 0
            val flashRatio = w[FLASH_LEFT] * 1000 / w[FLASH_TOTAL].coerceAtLeast(1)
            var value = nativeChannel((w[FLASH] ushr shift and 255) - 100, flashRatio, 0)
            val phase = if (w[BLINK_MODE] == 0) abs(w[BLINK_PHASE] - w[BLINK_PERIOD]) else w[BLINK_PERIOD] - w[BLINK_PHASE]
            val blinkRatio = phase * 1000 / w[BLINK_PERIOD].coerceAtLeast(1)
            value = nativeChannel((w[BLINK] ushr shift and 255) - 100, blinkRatio, value)
            return value
        }
        return base.copy(x = base.x + w[OFFSET_X], y = base.y + w[OFFSET_Y],
            tone = NativeTone(base.tone.red + channel(16), base.tone.green + channel(8), base.tone.blue + channel(0)))
    }

    fun erase(id: Int) { effects.remove(id) }
    fun reset() { effects.clear() }
    fun state(): List<WolfCharacterEffectState> = effects.map { (id, w) -> WolfCharacterEffectState(id, w.toList()) }
    fun restore(states: List<WolfCharacterEffectState>) {
        require(states.size <= MAX_EFFECTS && states.map { it.id }.toSet().size == states.size) { "États de personnage invalides" }
        require(states.all { it.words.size == WORD_COUNT && validWords(it.words) }) { "État d'effet de personnage invalide" }
        effects.clear(); for (state in states) effects[state.id] = state.words.toIntArray()
    }

    companion object {
        const val WORD_COUNT = 14
        const val MAX_DURATION = 360_000
        const val MAX_EFFECTS = 10_000
        private const val MAX_OFFSET = 1_000_000
        private const val FLASH = 0; private const val FLASH_TOTAL = 1; private const val FLASH_LEFT = 2
        private const val SHAKE_X = 3; private const val SHAKE_Y = 4; private const val SHAKE_COUNT = 5
        private const val SHAKE_HALF = 6; private const val SHAKE_PHASE = 7; private const val OFFSET_X = 8; private const val OFFSET_Y = 9
        private const val BLINK = 10; private const val BLINK_PERIOD = 11; private const val BLINK_PHASE = 12; private const val BLINK_MODE = 13
        private fun rgb(red: Int, green: Int, blue: Int) = ((red.coerceIn(-100, 100) + 100) shl 16) or
            ((green.coerceIn(-100, 100) + 100) shl 8) or (blue.coerceIn(-100, 100) + 100)
        private fun nativeChannel(offset: Int, ratio: Int, previous: Int) = ((offset * ratio / 1000) * 2.55 + previous).toInt()
        private fun validWords(w: List<Int>): Boolean {
            fun packed(index: Int) = w[index] >= 0 && w[index] ushr 24 == 0 &&
                (w[index] ushr 16 and 255) <= 200 && (w[index] ushr 8 and 255) <= 200 && (w[index] and 255) <= 200
            if (!packed(FLASH) || !packed(BLINK)) return false
            if (w[FLASH_TOTAL] !in 0..MAX_DURATION || w[FLASH_LEFT] !in 0..w[FLASH_TOTAL]) return false
            for (index in intArrayOf(SHAKE_X, SHAKE_Y, OFFSET_X, OFFSET_Y)) if (w[index] !in -MAX_OFFSET..MAX_OFFSET) return false
            if (w[SHAKE_COUNT] < 0 || w[SHAKE_HALF] !in 0..MAX_DURATION || w[SHAKE_PHASE] !in 0..w[SHAKE_HALF] * 2) return false
            if (w[SHAKE_COUNT] > 0 && (w[SHAKE_HALF] == 0 || w[SHAKE_PHASE] == 0)) return false
            if (w[BLINK_PERIOD] !in 0..MAX_DURATION || w[BLINK_MODE] !in 0..1) return false
            if (w[BLINK_PERIOD] == 0) return w[BLINK_PHASE] == 0
            return w[BLINK_PHASE] in 0 until w[BLINK_PERIOD] * (if (w[BLINK_MODE] == 0) 2 else 1)
        }
    }
}
