package fr.astragames.wolf

/** Actual decoder state. SMF positions and lengths use ticks, other codecs milliseconds. */
data class WolfAudioPlayback(val channel: Int, val voice: Int, val path: String, val position: Int, val duration: Int) {
    init { require(channel in 0..2 && voice in 0..999_999 && position >= 0 && duration >= 0) }
}

/** Host masks are adapted to Wolf: primary1, secondary2, tertiary4. */
internal class WolfPointerState {
    var x = -1f; private set
    var y = -1f; private set
    var inside = false; private set
    private var buttons = 0
    private var frameButtons = 0
    private var presses = 0
    private var releases = 0
    private var framePresses = 0
    private var frameReleases = 0
    private var pendingWheel = 0
    private var previousX: Float? = null
    private var previousY: Float? = null
    private val heldTicks = IntArray(3)
    var wheel = 0; private set
    var deltaX = 0; private set
    var deltaY = 0; private set
    fun update(x: Float, y: Float, buttons: Int, wheel: Int = 0, inside: Boolean = true) {
        require(x.isFinite() && y.isFinite() && buttons and 7.inv() == 0)
        presses = presses or (buttons and this.buttons.inv())
        releases = releases or (this.buttons and buttons.inv())
        this.buttons = buttons; this.x = x; this.y = y; this.inside = inside
        pendingWheel = (pendingWheel.toLong() + wheel).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
    fun frame() {
        frameButtons = buttons; framePresses = presses; frameReleases = releases
        presses = 0; releases = 0; wheel = pendingWheel; pendingWheel = 0
        deltaX = previousX?.let { (x - it).toInt() } ?: 0
        deltaY = previousY?.let { (y - it).toInt() } ?: 0
        previousX = x; previousY = y
        heldTicks.indices.forEach { heldTicks[it] = if (held(1 shl it)) (heldTicks[it].toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0 }
    }
    fun pulse(x: Float, y: Float, press: Boolean) {
        require(x.isFinite() && y.isFinite())
        this.x = x; this.y = y; this.inside = true
        if (press) presses = presses or 1
    }
    fun state(mask: Int) = WolfStateReadSemantics.pointerButtonState(frameButtons and mask != 0, framePresses and mask != 0)
    fun held(mask: Int) = (frameButtons or framePresses) and mask != 0
    fun pressed(mask: Int) = framePresses and mask != 0
    fun released(mask: Int) = frameReleases and mask != 0
    fun duration(mask: Int) = heldTicks[Integer.numberOfTrailingZeros(mask)]
    fun reset() {
        x = -1f; y = -1f; inside = false; buttons = 0; frameButtons = 0
        presses = 0; releases = 0; framePresses = 0; frameReleases = 0
        pendingWheel = 0; wheel = 0; deltaX = 0; deltaY = 0; previousX = null; previousY = null; heldTicks.fill(0)
    }
}
