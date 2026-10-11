package fr.astragames.wolf

/**
 * Small command124 contracts from the pinned W3 Game.exe (a14330 / 98c140).
 * Field availability and host telemetry are deliberately checked by the runtime separately.
 * Evidence and the observed integer boundaries are recorded in STATE_READ_REA.md.
 */
internal object WolfStateReadSemantics {
    /** Bit6 is meaningful only for picture queries; position fields occupy bits16..23. */
    fun recognizedFlags(flags: Int): Boolean {
        val category = flags ushr 12 and 15
        if (category !in 1..4 || flags ushr 8 and 15 !in 0..8) return false
        val mask = when (category) {
            2 -> 0x00ff_ff31
            4 -> 0x0000_ff51
            else -> 0x0000_ff11
        }
        return flags and mask.inv() == 0
    }

    /** Native anchor enum to decimal horizontal/vertical reference (field24). */
    fun pictureAnchor(anchor: Int): Int = when (anchor) {
        1 -> 11
        2 -> 2
        3 -> 20
        4 -> 22
        5 -> 10
        6 -> 12
        else -> 0
    }

    /** Fields36/37 search IDs, including queries for an ID absent from the array. */
    fun neighborPictureId(ids: Iterable<Int>, id: Int, next: Boolean): Int {
        var candidate: Int? = null
        for (value in ids) {
            if (next && value > id && (candidate == null || value < candidate) ||
                !next && value < id && (candidate == null || value > candidate)) candidate = value
        }
        return candidate ?: id
    }

    /** a98c140 uses2e9 as both initial minimum and its no-result sentinel. */
    fun minimumPositiveDelay(delays: Iterable<Int>): Int {
        var minimum = 2_000_000_000
        for (delay in delays) if (delay > 0 && delay <= minimum) minimum = delay
        return if (minimum == 2_000_000_000) -1 else minimum
    }

    fun maximumPositiveDelay(delays: Iterable<Int>): Int {
        var maximum = -1
        for (delay in delays) if (delay > 0 && delay >= maximum) maximum = delay
        return maximum
    }

    /** 98f1a0: completed ordinary picture processing settles at-1; its configured total survives. */
    fun pictureRemainingAfterTick(remaining: Int, engineFrameStep: Int): Int =
        (remaining - engineFrameStep).coerceAtLeast(-1)

    /** a14a2a: wrapped int32 multiply followed by signed truncating /100, after shake. */
    fun zoomedCoordinate(coordinate: Int, shake: Int, center: Int, zoomPercent: Int): Int {
        val shifted = coordinate + shake
        return shifted + (shifted - center) * (zoomPercent - 100) / 100
    }

    /** The caller supplies real button states for the current logical frame. */
    fun pointerButtonState(pressed: Boolean, newPress: Boolean): Int =
        if (newPress) 2 else if (pressed) 1 else 0

    /** a171ca and cd8580. Unknown configuration bytes are not qualified as a language. */
    fun language(rawByte: Byte): Int? = when (rawByte.toInt() % 100) {
        0, 1 -> 1
        2 -> 6
        3 -> 5
        4 -> 4
        5 -> 2
        6 -> 3
        else -> null
    }

    /** 98e525: DIVSS by the exact float constant, promote, then add double0.5 and100. */
    fun colorPercentage(brightness: Int): Int =
        ((brightness.toFloat() / Float.fromBits(0x4023_3333)).toDouble() + 0.5 + 100.0).toInt()
}
