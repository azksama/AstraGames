package fr.astragames.wolf

import kotlin.math.abs

/** W2's per-picture words 0xac..0xd6, independent from command 150's own interpolation. */
data class WolfPictureEffectState(val id: Int, val words: List<Int>)

/** Inspected native versions differ in the handling of shake counts above 99999. */
enum class WolfPictureEffectProfile { WOLF2, WOLF3 }

/**
 * Command 290 category 0, operations 0..10. Evidence: W2 0x4c1dd0 (set),
 * 0x4ce890 (tick), 0x4920a0 (draw), Game.exe SHA256 795203a6...c0.
 *
 * These words are relative effects: operation 2 translates, operation 4 zooms about a
 * screen point, operation 7 adds to the picture's original X/Y scale. They must be
 * composed after command 150, rather than overwriting its interpolation endpoints.
 * W3's additional operations and the native movie controls are not aliases of these.
 */
class WolfPictureEffects(private val profile: WolfPictureEffectProfile = WolfPictureEffectProfile.WOLF2) {
    private val effects = linkedMapOf<Int, IntArray>()

    fun apply(id: Int, operation: Int, first: Int, second: Int, third: Int, duration: Int) {
        require(operation in 0..10) { "Effet d'image non porté : $operation" }
        require(duration in 0..MAX_DURATION) { "Durée d'effet d'image hors limites" }
        if (operation in listOf(2, 3, 4, 7, 8, 9, 10)) require(first in -MAX_VALUE..MAX_VALUE && second in -MAX_VALUE..MAX_VALUE) { "Paramètre d'effet d'image hors limites" }
        if (operation == 3) require(third >= 0) { "Nombre de secousses hors limites" }
        if (operation == 4) require(third in -MAX_VALUE..MAX_VALUE) { "Zoom d'image hors limites" }
        if (operation == 7) require(third in -MAX_DURATION..MAX_DURATION) { "Pause d'oscillation hors limites" }
        require(id in effects || effects.size < MAX_EFFECTS) { "Trop d'effets d'image" }
        val w = effects.getOrPut(id, ::initialWords)
        when (operation) {
            0 -> { w[FLASH] = rgb(first, second, third); w[FLASH_TOTAL] = duration; w[FLASH_LEFT] = duration }
            1 -> {
                w[TINT_FROM] = channels { shift ->
                    interpolate(channel(w[TINT], shift), channel(w[TINT_FROM], shift), w[TINT_LEFT], w[TINT_TOTAL]).coerceIn(0, 200)
                }
                w[TINT] = rgb(first, second, third); w[TINT_TOTAL] = duration; w[TINT_LEFT] = duration
            }
            2 -> {
                w[X_FROM] = interpolate(w[X], w[X_FROM], w[SHIFT_LEFT], w[SHIFT_TOTAL])
                w[Y_FROM] = interpolate(w[Y], w[Y_FROM], w[SHIFT_LEFT], w[SHIFT_TOTAL])
                w[X] = first; w[Y] = second; w[SHIFT_TOTAL] = duration; w[SHIFT_LEFT] = duration
            }
            3 -> {
                w[SHAKE_X] = first; w[SHAKE_Y] = second; w[SHAKE_COUNT] = third
                w[SHAKE_HALF] = duration.coerceAtLeast(1); w[SHAKE_LEFT] = w[SHAKE_HALF] * 2
            }
            4 -> {
                w[ZOOM_FROM] = interpolate(w[ZOOM], w[ZOOM_FROM], w[ZOOM_LEFT], w[ZOOM_TOTAL])
                if (w[ZOOM_FROM] == 100) { w[CENTER_X_FROM] = first; w[CENTER_Y_FROM] = second }
                else {
                    w[CENTER_X_FROM] = interpolate(w[CENTER_X], w[CENTER_X_FROM], w[ZOOM_LEFT], w[ZOOM_TOTAL])
                    w[CENTER_Y_FROM] = interpolate(w[CENTER_Y], w[CENTER_Y_FROM], w[ZOOM_LEFT], w[ZOOM_TOTAL])
                }
                w[CENTER_X] = first; w[CENTER_Y] = second; w[ZOOM] = third
                w[ZOOM_TOTAL] = duration; w[ZOOM_LEFT] = duration
            }
            5, 6 -> { w[BLINK] = rgb(first, second, third); w[BLINK_PERIOD] = duration; w[BLINK_PHASE] = 0; w[BLINK_MODE] = operation - 5 }
            7 -> { w[WAVE_X] = first; w[WAVE_Y] = second; w[WAVE_GAP] = third; w[WAVE_PERIOD] = duration; w[WAVE_PHASE] = duration * 2 }
            8, 9, 10 -> {
                w[PATTERN_FIRST] = first; w[PATTERN_LAST] = second; w[PATTERN_PERIOD] = duration
                w[PATTERN_LEFT] = duration; w[PATTERN] = first; w[PATTERN_MODE] = operation - 8
            }
        }
    }

    /** Native operation 8 also commits pattern to the base picture; the host applies these updates. */
    fun tick(): Map<Int, Int> {
        val commits = linkedMapOf<Int, Int>()
        for ((id, w) in effects) {
            if (w[FLASH_LEFT] > 0) w[FLASH_LEFT]--
            if (w[TINT_LEFT] > 0) w[TINT_LEFT]--
            if (w[SHIFT_LEFT] > 0) w[SHIFT_LEFT]--
            if (w[SHAKE_COUNT] > 0 && --w[SHAKE_LEFT] < 1) {
                // W3 0098f1a0 keeps long shakes alive by alternating counter parity.
                val count = w[SHAKE_COUNT]
                w[SHAKE_COUNT] = if (profile == WolfPictureEffectProfile.WOLF3 && count > 99_999 && count % 2 == 0)
                    count + 1 else count - 1
                w[SHAKE_LEFT] = w[SHAKE_HALF] * 2
            }
            if (w[ZOOM_LEFT] > 0 && --w[ZOOM_LEFT] < 1) w[ZOOM_FROM] = w[ZOOM]
            if (w[BLINK_PERIOD] > 0) {
                val period = if (w[BLINK_MODE] == 0) w[BLINK_PERIOD] * 2 else w[BLINK_PERIOD]
                if (period > 0) w[BLINK_PHASE] = (w[BLINK_PHASE] + 1) % period
            }
            if (w[WAVE_PERIOD] > 0 && --w[WAVE_PHASE] <= -w[WAVE_GAP]) w[WAVE_PHASE] = w[WAVE_PERIOD] * 2
            if (w[PATTERN_PERIOD] > 0 && --w[PATTERN_LEFT] == 0) {
                val direction = (w[PATTERN_LAST].toLong() - w[PATTERN_FIRST]).compareTo(0L)
                when (w[PATTERN_MODE]) {
                    0 -> if (w[PATTERN] == w[PATTERN_LAST]) w[PATTERN_PERIOD] = 0 else {
                        w[PATTERN] += direction; w[PATTERN_LEFT] = w[PATTERN_PERIOD]; commits[id] = w[PATTERN]
                    }
                    1 -> { w[PATTERN] = if (w[PATTERN] == w[PATTERN_LAST]) w[PATTERN_FIRST] else w[PATTERN] + direction; w[PATTERN_LEFT] = w[PATTERN_PERIOD] }
                    2 -> {
                        if (w[PATTERN] == w[PATTERN_LAST]) { w[PATTERN] -= direction; w[PATTERN_MODE] = -2 }
                        else w[PATTERN] += direction
                        w[PATTERN_LEFT] = w[PATTERN_PERIOD]
                    }
                    -2 -> {
                        if (w[PATTERN] == w[PATTERN_FIRST]) { w[PATTERN] += direction; w[PATTERN_MODE] = 2 }
                        else w[PATTERN] -= direction
                        w[PATTERN_LEFT] = w[PATTERN_PERIOD]
                    }
                }
            }
        }
        return commits
    }

    /** W3 inserts flash17 before tint/blink, and shift11 after wave7 before zoom4. */
    fun picture(base: NativePicture, flashTransform: ((Int, NativeTone) -> NativeTone)? = null,
                preTransform: ((NativePicture) -> NativePicture)? = null): NativePicture {
        val w = effects[base.id] ?: if (flashTransform == null && preTransform == null) return base else initialWords()
        fun flash(shift: Int) = if (w[FLASH_LEFT] > 0 && w[FLASH_TOTAL] != 0)
            nativeChannel(channel(w[FLASH], shift) - 100, w[FLASH_LEFT], w[FLASH_TOTAL], 0) else 0
        val initialFlash = NativeTone(flash(16), flash(8), flash(0))
        val composedFlash = flashTransform?.invoke(base.id, initialFlash) ?: initialFlash
        fun tone(shift: Int): Int {
            var value = when (shift) { 16 -> composedFlash.red; 8 -> composedFlash.green; else -> composedFlash.blue }
            val tint = if (w[TINT_LEFT] > 0) interpolate(channel(w[TINT], shift), channel(w[TINT_FROM], shift), w[TINT_LEFT], w[TINT_TOTAL]) else channel(w[TINT], shift)
            value = nativeChannel(tint - 100, 1, 1, value)
            if (w[BLINK_PERIOD] > 0) {
                val phase = if (w[BLINK_MODE] == 0) abs(w[BLINK_PHASE] - w[BLINK_PERIOD]) else w[BLINK_PERIOD] - w[BLINK_PHASE]
                value = nativeChannel(channel(w[BLINK], shift) - 100, phase, w[BLINK_PERIOD], value)
            }
            return value
        }
        var shiftX = interpolate(w[X], w[X_FROM], w[SHIFT_LEFT], w[SHIFT_TOTAL])
        var shiftY = interpolate(w[Y], w[Y_FROM], w[SHIFT_LEFT], w[SHIFT_TOTAL])
        if (w[SHAKE_COUNT] > 0) {
            val sign = if (w[SHAKE_COUNT] % 2 == 0) -1 else 1
            val phase = w[SHAKE_LEFT]
            fun shake(amplitude: Int) = if (phase < w[SHAKE_HALF]) (amplitude.toLong() * phase / w[SHAKE_HALF]).toInt()
                else amplitude - ((phase - w[SHAKE_HALF]).toLong() * amplitude / w[SHAKE_HALF]).toInt()
            shiftX += sign * shake(w[SHAKE_X]); shiftY += sign * shake(w[SHAKE_Y])
        }
        val zoom = if (w[ZOOM] == 100 && w[ZOOM_FROM] == 100) 100 else interpolate(w[ZOOM], w[ZOOM_FROM], w[ZOOM_LEFT], w[ZOOM_TOTAL])
        val centerX = interpolate(w[CENTER_X], w[CENTER_X_FROM], w[ZOOM_LEFT], w[ZOOM_TOTAL])
        val centerY = interpolate(w[CENTER_Y], w[CENTER_Y_FROM], w[ZOOM_LEFT], w[ZOOM_TOTAL])
        fun zoomPoint(point: Float, center: Int) = if (zoom == 100) point else (((point.toInt().toLong() - center) * (zoom - 100)) / 100 + point.toInt()).toFloat()
        fun wave(amplitude: Int) = if (w[WAVE_PERIOD] != 0 && w[WAVE_PHASE] >= 0)
            ((w[WAVE_PERIOD] - abs(w[WAVE_PHASE] - w[WAVE_PERIOD])).toLong() * amplitude / w[WAVE_PERIOD]).toInt() else 0
        val waved = base.copy(scaleX = base.scaleX + wave(w[WAVE_X]) / 100f,
            scaleY = base.scaleY + wave(w[WAVE_Y]) / 100f)
        val transformed = preTransform?.invoke(waved) ?: waved
        return transformed.copy(
            x = zoomPoint(transformed.x, centerX) + shiftX, y = zoomPoint(transformed.y, centerY) + shiftY,
            scaleX = transformed.scaleX * zoom / 100f,
            scaleY = transformed.scaleY * zoom / 100f,
            pattern = if (w[PATTERN_PERIOD] > 0) w[PATTERN] else base.pattern,
            tone = NativeTone(base.tone.red + tone(16), base.tone.green + tone(8), base.tone.blue + tone(0)),
        )
    }

    fun erase(id: Int) { effects.remove(id) }
    fun reset() { effects.clear() }
    fun state(): List<WolfPictureEffectState> = effects.map { (id, w) -> WolfPictureEffectState(id, w.toList()) }
    fun restore(states: List<WolfPictureEffectState>) {
        require(states.size <= MAX_EFFECTS) { "Trop d'effets d'image sauvegardés" }
        require(states.all { it.words.size == WORD_COUNT }) { "État d'effet d'image incomplet" }
        require(states.map { it.id }.toSet().size == states.size) { "État d'effet d'image dupliqué" }
        require(states.all { validWords(it.words) }) { "État d'effet d'image invalide" }
        effects.clear(); for (state in states) effects[state.id] = state.words.toIntArray()
    }

    companion object {
        const val WORD_COUNT = 43
        const val MAX_DURATION = 360_000
        const val MAX_EFFECTS = 10_000
        private const val MAX_VALUE = 1_000_000
        private const val FLASH = 0; private const val FLASH_TOTAL = 1; private const val FLASH_LEFT = 2
        private const val TINT = 3; private const val TINT_FROM = 4; private const val TINT_TOTAL = 5; private const val TINT_LEFT = 6
        private const val X = 7; private const val X_FROM = 8; private const val Y = 9; private const val Y_FROM = 10
        private const val SHIFT_TOTAL = 11; private const val SHIFT_LEFT = 12
        private const val SHAKE_X = 13; private const val SHAKE_Y = 14; private const val SHAKE_COUNT = 17
        private const val SHAKE_HALF = 18; private const val SHAKE_LEFT = 19
        private const val CENTER_X = 20; private const val CENTER_X_FROM = 21; private const val CENTER_Y = 22; private const val CENTER_Y_FROM = 23
        private const val ZOOM = 24; private const val ZOOM_FROM = 25; private const val ZOOM_TOTAL = 26; private const val ZOOM_LEFT = 27
        private const val BLINK = 28; private const val BLINK_PERIOD = 29; private const val BLINK_PHASE = 30; private const val BLINK_MODE = 31
        private const val WAVE_X = 32; private const val WAVE_Y = 33; private const val WAVE_PHASE = 34; private const val WAVE_PERIOD = 35; private const val WAVE_GAP = 36
        private const val PATTERN_FIRST = 37; private const val PATTERN_LAST = 38; private const val PATTERN_LEFT = 39
        private const val PATTERN_PERIOD = 40; private const val PATTERN_MODE = 41; private const val PATTERN = 42

        private fun initialWords() = IntArray(WORD_COUNT).apply { this[TINT] = 0x646464; this[TINT_FROM] = 0x646464; this[ZOOM] = 100; this[ZOOM_FROM] = 100 }
        private fun channel(rgb: Int, shift: Int) = rgb ushr shift and 255
        private inline fun channels(value: (Int) -> Int) = (value(16) shl 16) or (value(8) shl 8) or value(0)
        private fun rgb(red: Int, green: Int, blue: Int) = ((red / 2 + 100).coerceIn(0, 200) shl 16) or
            ((green / 2 + 100).coerceIn(0, 200) shl 8) or (blue / 2 + 100).coerceIn(0, 200)
        private fun interpolate(target: Int, from: Int, remaining: Int, total: Int) =
            (target.toLong() - (target.toLong() - from) * remaining / total.coerceAtLeast(1)).toInt()

        private fun validWords(w: List<Int>): Boolean {
            fun tween(total: Int, remaining: Int) = w[total] in 0..MAX_DURATION && w[remaining] in 0..w[total]
            fun packed(index: Int) = w[index] >= 0 && w[index] ushr 24 == 0 &&
                channel(w[index], 16) <= 200 && channel(w[index], 8) <= 200 && channel(w[index], 0) <= 200
            if (!packed(FLASH) || !packed(TINT) || !packed(TINT_FROM) || !packed(BLINK)) return false
            if (!tween(FLASH_TOTAL, FLASH_LEFT) || !tween(TINT_TOTAL, TINT_LEFT) || !tween(SHIFT_TOTAL, SHIFT_LEFT) || !tween(ZOOM_TOTAL, ZOOM_LEFT)) return false
            for (index in intArrayOf(X, X_FROM, Y, Y_FROM, SHAKE_X, SHAKE_Y, CENTER_X, CENTER_X_FROM, CENTER_Y, CENTER_Y_FROM, ZOOM, ZOOM_FROM, WAVE_X, WAVE_Y, PATTERN_FIRST, PATTERN_LAST, PATTERN)) if (w[index] !in -MAX_VALUE..MAX_VALUE) return false
            if (w[SHAKE_COUNT] < 0 || w[SHAKE_HALF] !in 0..MAX_DURATION || w[SHAKE_LEFT] !in 0..w[SHAKE_HALF] * 2) return false
            if (w[SHAKE_COUNT] > 0 && (w[SHAKE_HALF] == 0 || w[SHAKE_LEFT] == 0)) return false
            if (w[BLINK_PERIOD] !in 0..MAX_DURATION || w[BLINK_MODE] !in 0..1) return false
            if (w[BLINK_PERIOD] == 0) { if (w[BLINK_PHASE] != 0) return false }
            else if (w[BLINK_PHASE] !in 0 until w[BLINK_PERIOD] * (if (w[BLINK_MODE] == 0) 2 else 1)) return false
            if (w[WAVE_PERIOD] !in 0..MAX_DURATION || w[WAVE_GAP] !in -MAX_DURATION..MAX_DURATION) return false
            if (w[WAVE_PERIOD] == 0) { if (w[WAVE_PHASE] != 0) return false }
            else if (w[WAVE_PHASE] !in -MAX_DURATION..w[WAVE_PERIOD] * 2) return false
            if (!tween(PATTERN_PERIOD, PATTERN_LEFT) || w[PATTERN_MODE] !in listOf(-2, 0, 1, 2)) return false
            if (w[PATTERN_PERIOD] > 0 && w[PATTERN_LEFT] == 0) return false
            if (w[PATTERN] !in minOf(w[PATTERN_FIRST], w[PATTERN_LAST])..maxOf(w[PATTERN_FIRST], w[PATTERN_LAST])) return false
            return w[15] == 0 && w[16] == 0
        }

        /** Preserve the instruction order, binary64 constant and Windows CRT's 53-bit arithmetic. */
        private fun nativeChannel(offset: Int, weight: Int, period: Int, previous: Int): Int =
            (offset * 2.55 * 2 * weight / period + previous).toInt()
    }
}
