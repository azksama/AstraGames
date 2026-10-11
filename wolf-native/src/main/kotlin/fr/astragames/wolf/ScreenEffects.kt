package fr.astragames.wolf

/** W2 004ada90/004adb30 setters and 004cc0c0 frame update. */
internal class WolfScreenEffects {
    data class State(
        val tone: NativeTone = NativeTone(), val from: NativeTone = NativeTone(), val target: NativeTone = NativeTone(),
        val toneTotal: Int = 0, val toneRemaining: Int = -1,
        val flash: NativeTone = NativeTone(255, 255, 255), val flashTotal: Int = 0, val flashRemaining: Int = 0,
    )
    var state = State(); private set

    fun tone(target: NativeTone, duration: Int) {
        require(duration in 0..32766) { "Durée de tonalité Wolf hors limites" }
        validateTone(target)
        state = state.copy(from = state.tone, target = target, toneTotal = duration + 1, toneRemaining = duration + 1)
    }
    fun flash(color: NativeTone, duration: Int) {
        require(duration in 0..32766) { "Durée de flash Wolf hors limites" }
        validateTone(color)
        state = state.copy(flash = NativeTone((color.red + 255).coerceIn(0, 255), (color.green + 255).coerceIn(0, 255),
            (color.blue + 255).coerceIn(0, 255)), flashTotal = duration + 1, flashRemaining = duration + 1)
    }
    fun tick() {
        val before = state
        var tone = before.tone
        if (before.toneRemaining >= 0) {
            fun interpolate(from: Int, target: Int) = target + (from - target) * before.toneRemaining / before.toneTotal
            tone = NativeTone(interpolate(before.from.red, before.target.red), interpolate(before.from.green, before.target.green),
                interpolate(before.from.blue, before.target.blue))
        }
        state = before.copy(tone = tone,
            toneRemaining = when { before.toneRemaining < 0 -> -1; before.toneRemaining == 0 -> -1; else -> before.toneRemaining - 1 },
            flashRemaining = (before.flashRemaining - 1).coerceAtLeast(0))
    }
    fun flashFrame(): NativeFlash? = if (state.flashRemaining <= 0) null else
        NativeFlash(state.flash.red, state.flash.green, state.flash.blue, state.flashRemaining * 255 / state.flashTotal)

    fun restore(value: State) {
        validateTone(value.tone); validateTone(value.from); validateTone(value.target)
        require(value.toneTotal in 0..32767 && value.toneRemaining in -1..value.toneTotal &&
            (value.toneRemaining < 0 || value.toneTotal > 0)) { "Interpolation de tonalité invalide" }
        require(value.flashTotal in 0..32767 && value.flashRemaining in 0..value.flashTotal &&
            listOf(value.flash.red, value.flash.green, value.flash.blue).all { it in 0..255 } &&
            (value.flashRemaining == 0 || value.flashTotal > 0)) { "Interpolation de flash invalide" }
        state = value
    }
    fun reset() { state = State() }

    companion object {
        fun percentage(value: Int): Int = ((value - 100) * 255 / 100).toShort().toInt()
        private fun validateTone(tone: NativeTone) {
            require(listOf(tone.red, tone.green, tone.blue).all { it in Short.MIN_VALUE..Short.MAX_VALUE }) { "Tonalité Wolf hors limites" }
        }
    }
}
