package fr.astragames.wolf

/**
 * W3 command290 category3's shared, physical-pixel visibility canvas.
 * Original975170/7801f0 combine source bytes with AND/OR, never alpha blend.
 * Original9750f0 clears an existing canvas to visible0 without deleting it.
 *
 * [frame] is immutable by ownership: mutations allocate another ByteArray,
 * retaining every previous frame/transition. Hosts must never mutate its bytes.
 * [state] and [restore] detach bytes at the save boundary.
 */
class WolfVisibilityMask(val width: Int, val height: Int) {
    private var current: NativeVisibilityMask? = null

    // A high-resolution game which never uses category3 must not allocate a
    // mask or be rejected solely because a potential canvas exceeds its budget.
    init { require(width in 1..8192 && height in 1..8192) { "Dimensions du masque de visibilité Wolf invalides." } }

    fun ensure() { if (current == null) fill(false) }

    fun fill(hidden: Boolean) {
        require(validDimensions(width, height)) { "Le masque de visibilité Wolf dépasse le budget de pixels." }
        current = NativeVisibilityMask(width, height, ByteArray(width * height) { if (hidden) -1 else 0 })
    }

    /** Inputs are decoded8-bit mask bytes. Image alpha does not participate. */
    fun draw(imageWidth: Int, imageHeight: Int, pixels: ByteArray, x: Int, y: Int, anchor: Int, mode: Int) {
        require(validImageDimensions(imageWidth, imageHeight) && pixels.size == imageWidth * imageHeight) {
            "Image du masque de visibilité Wolf invalide."
        }
        require(anchor in 0..1 && mode in 0..1) { "Ancrage ou combinaison du masque Wolf inconnu." }
        ensure()
        //975170 centers in physical image pixels, then clamps ONLY x to-9998.
        val left = (x - if (anchor == 1) imageWidth / 2 else 0).coerceAtLeast(-9998).toLong()
        val top = (y - if (anchor == 1) imageHeight / 2 else 0).toLong()
        val fromX = maxOf(0L, left).coerceAtMost(width.toLong()).toInt()
        val fromY = maxOf(0L, top).coerceAtMost(height.toLong()).toInt()
        val untilX = minOf(width.toLong(), left + imageWidth).coerceAtLeast(0).toInt()
        val untilY = minOf(height.toLong(), top + imageHeight).coerceAtLeast(0).toInt()
        if (fromX >= untilX || fromY >= untilY) return
        val next = current!!.pixels.copyOf()
        for (dy in fromY until untilY) {
            var source = ((dy.toLong() - top) * imageWidth + fromX - left).toInt()
            var destination = dy * width + fromX
            repeat(untilX - fromX) {
                val old = next[destination].toInt()
                val value = pixels[source++].toInt()
                next[destination++] = (if (mode == 0) old and value else old or value).toByte()
            }
        }
        current = NativeVisibilityMask(width, height, next)
    }

    fun reset() { if (current != null) fill(false) }

    fun frame(): NativeVisibilityMask? = current

    fun state(): NativeVisibilityMask? = current?.let { it.copy(pixels = it.pixels.copyOf()) }

    fun restore(value: NativeVisibilityMask?) {
        require(value == null || value.width == width && value.height == height &&
            validDimensions(value.width, value.height) && value.pixels.size == width * height) {
            "État du masque de visibilité Wolf incompatible."
        }
        current = value?.let { it.copy(pixels = it.pixels.copyOf()) }
    }

    companion object {
        const val MAX_PIXELS = 4_194_304
        fun validDimensions(width: Int, height: Int) = width in 1..8192 && height in 1..8192 &&
            width.toLong() * height in 1..MAX_PIXELS.toLong()
        /** Imported images can be larger than the screen; drawing clips them. */
        fun validImageDimensions(width: Int, height: Int) = width in 1..16_384 && height in 1..16_384 &&
            width.toLong() * height in 1..16_777_216L
    }
}
