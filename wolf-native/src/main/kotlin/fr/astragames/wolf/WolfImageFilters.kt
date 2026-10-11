package fr.astragames.wolf

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The destructive bitmap conversions of W3 picture command 290. The caller
 * owns their ordered history; this helper never edits the original asset.
 *
 * Formulas follow DxLib's PMA HSB/HLOCK, Gauss, Invert and Level shaders.
 * "HSB" here uses lightness (HSL), not HSV. Each conversion, and each of the
 * two Gaussian passes, writes an 8-bit premultiplied texture. This follows the
 * D3D11 path; D3D9 additionally quantizes relative hue through an HSL texture.
 * GPU float/UNORM rounding can differ by one component at a rounding boundary.
 */
object WolfImageFilters {
    val operations = setOf(21, 22, 23, 24, 26)

    /** Input/output are straight ARGB, matching Bitmap.getPixels/setPixels. */
    fun convert(width: Int, height: Int, argb: IntArray, filters: List<NativeImageFilter>): IntArray {
        require(width in 1..16_384 && height in 1..16_384 && width.toLong() * height <= 16_777_216L) {
            "dimensions de conversion d’image Wolf invalides"
        }
        require(argb.size == width * height) { "taille des pixels de conversion Wolf invalide" }
        require(filters.all { it.operation in operations }) { "conversion d’image Wolf inconnue" }
        if (filters.isEmpty()) return argb.copyOf()
        var pixels = IntArray(argb.size) { premultiply(argb[it]) }
        for (filter in filters) {
            check(!Thread.currentThread().isInterrupted) { "conversion d’image Wolf interrompue" }
            if (filter.operation == 23) pixels = blur(width, height, pixels, filter.first)
            else transform(pixels, filter)
        }
        for (index in pixels.indices) pixels[index] = unpremultiply(pixels[index])
        return pixels
    }

    private fun transform(pixels: IntArray, filter: NativeImageFilter) {
        val relativeHue = (((filter.first % 360 + 180) % 360) - 180).toFloat() / 360f * 6f
        val absoluteHue = (filter.first.toFloat() / 360f).coerceIn(0f, 1f) * 6f
        val saturation = (filter.second.toFloat() * 100f / 255f / 100f).coerceIn(-1f, 1f)
        val saturationFactor = 1f + if (saturation > 0f) saturation * 5f else saturation
        val brightness = filter.third.toFloat() * 100f / 255f / 100f
        val oldMaximum = filter.second.coerceIn(0, 255).toFloat() / 255f
        val oldMinimum = min(filter.first.coerceIn(0, 255).toFloat() / 255f, oldMaximum)
        val inverseRange = 1f / (oldMaximum - oldMinimum)
        val newMaximum = filter.fourth.coerceIn(0, 255).toFloat() / 255f
        val newMinimum = min(filter.third.coerceIn(0, 255).toFloat() / 255f, newMaximum)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val alpha = pixel ushr 24
            if (alpha == 0) { pixels[index] = 0; continue }
            var red = (pixel ushr 16 and 255).toFloat() / alpha
            var green = (pixel ushr 8 and 255).toFloat() / alpha
            var blue = (pixel and 255).toFloat() / alpha
            when (filter.operation) {
                21, 22 -> {
                    if (brightness > 0f) {
                        red += (1f - red) * brightness
                        green += (1f - green) * brightness
                        blue += (1f - blue) * brightness
                    } else {
                        red *= 1f + brightness; green *= 1f + brightness; blue *= 1f + brightness
                    }
                    red = saturate(red); green = saturate(green); blue = saturate(blue)
                    val largest = max(red, max(green, blue))
                    val smallest = min(red, min(green, blue))
                    val delta = largest - smallest
                    val lightness = (largest + smallest) / 2f
                    var hue = 0f
                    var sat = 0f
                    if (largest != smallest) {
                        sat = delta / if (lightness > .5f) 2f - largest - smallest else largest + smallest
                        hue = when (largest) {
                            red -> (largest - blue) / delta - (largest - green) / delta
                            green -> 2f + (largest - red) / delta - (largest - blue) / delta
                            else -> 4f + (largest - green) / delta - (largest - red) / delta
                        }
                        if (hue < 0f) hue += 6f
                    }
                    if (filter.operation == 22) hue = absoluteHue
                    else {
                        hue += relativeHue
                        if (hue < 0f) hue += 6f else if (hue >= 6f) hue -= 6f
                    }
                    sat = saturate(sat * saturationFactor)
                    when {
                        hue < 1f -> { red = 1f; green = hue; blue = 0f }
                        hue < 2f -> { red = 1f - (hue - 1f); green = 1f; blue = 0f }
                        hue < 3f -> { red = 0f; green = 1f; blue = hue - 2f }
                        hue < 4f -> { red = 0f; green = 1f - (hue - 3f); blue = 1f }
                        hue < 5f -> { red = hue - 4f; green = 0f; blue = 1f }
                        else -> { red = 1f; green = 0f; blue = 1f - (hue - 5f) }
                    }
                    red += (.5f - red) * (1f - sat)
                    green += (.5f - green) * (1f - sat)
                    blue += (.5f - blue) * (1f - sat)
                    if (lightness <= .5f) {
                        red *= lightness * 2f; green *= lightness * 2f; blue *= lightness * 2f
                    } else {
                        val multiplier = (lightness - .5f) * 2f
                        red += (1f - red) * multiplier
                        green += (1f - green) * multiplier
                        blue += (1f - blue) * multiplier
                    }
                }
                24 -> { red = 1f - red; green = 1f - green; blue = 1f - blue }
                26 -> {
                    // Gamma is fixed to 100 (=1). DxLib samples its 256-entry
                    // identity ramp with normalized coordinates and LINEAR.
                    fun level(value: Float): Float {
                        val normalized = saturate((value - oldMinimum) * inverseRange)
                        val ratio = (normalized * 256f - .5f).coerceIn(0f, 255f) / 255f
                        return newMinimum + (newMaximum - newMinimum) * ratio
                    }
                    red = level(red); green = level(green); blue = level(blue)
                }
            }
            pixels[index] = pack(alpha, quantize(red * alpha, alpha), quantize(green * alpha, alpha), quantize(blue * alpha, alpha))
        }
    }

    private fun blur(width: Int, height: Int, pixels: IntArray, strength: Int): IntArray {
        // W3 chooses the kernel before clamping, and uses the original
        // strength+1 in the variance (signed 32-bit arithmetic).
        val count = when { strength < 8 -> 4; strength < 12 -> 8; else -> 16 }
        val variance = max((strength.coerceIn(0, 16) * (strength + 1) * 10).toFloat(), 1e-10f)
        val weights = FloatArray(count)
        var total = 0f
        for (index in weights.indices) {
            weights[index] = exp((-.5f * (index * index).toFloat() / variance).toDouble()).toFloat()
            total += if (index == 0) weights[index] else 2f * weights[index]
        }
        for (index in weights.indices) weights[index] /= total
        if (weights.drop(1).all { it == 0f }) return pixels
        var source = pixels
        repeat(2) { pass ->
            val destination = IntArray(source.size)
            for (y in 0 until height) {
                check(!Thread.currentThread().isInterrupted) { "flou d’image Wolf interrompu" }
                for (x in 0 until width) {
                    val center = source[y * width + x]
                    var alpha = (center ushr 24) * weights[0]
                    var red = (center ushr 16 and 255) * weights[0]
                    var green = (center ushr 8 and 255) * weights[0]
                    var blue = (center and 255) * weights[0]
                    for (index in 1 until count) {
                        // ±(2*i-.5) samples are linear averages of the two
                        // adjacent texels; this is not an integer-radius blur.
                        val near = 2 * index - 1; val far = near + 1
                        val first: Int; val second: Int; val third: Int; val fourth: Int
                        if (pass == 0) {
                            val row = y * width
                            first = source[row + (x + near).coerceIn(0, width - 1)]
                            second = source[row + (x + far).coerceIn(0, width - 1)]
                            third = source[row + (x - near).coerceIn(0, width - 1)]
                            fourth = source[row + (x - far).coerceIn(0, width - 1)]
                        } else {
                            first = source[(y + near).coerceIn(0, height - 1) * width + x]
                            second = source[(y + far).coerceIn(0, height - 1) * width + x]
                            third = source[(y - near).coerceIn(0, height - 1) * width + x]
                            fourth = source[(y - far).coerceIn(0, height - 1) * width + x]
                        }
                        val weight = weights[index] * .5f
                        alpha += ((first ushr 24) + (second ushr 24) + (third ushr 24) + (fourth ushr 24)) * weight
                        red += ((first ushr 16 and 255) + (second ushr 16 and 255) + (third ushr 16 and 255) + (fourth ushr 16 and 255)) * weight
                        green += ((first ushr 8 and 255) + (second ushr 8 and 255) + (third ushr 8 and 255) + (fourth ushr 8 and 255)) * weight
                        blue += ((first and 255) + (second and 255) + (third and 255) + (fourth and 255)) * weight
                    }
                    val outputAlpha = quantize(alpha, 255)
                    destination[y * width + x] = pack(outputAlpha, quantize(red, outputAlpha), quantize(green, outputAlpha), quantize(blue, outputAlpha))
                }
            }
            source = destination
        }
        return source
    }

    // HLSL saturate(NaN) is zero, including levels with equal input bounds.
    private fun saturate(value: Float) = if (!(value > 0f)) 0f else min(value, 1f)
    private fun quantize(value: Float, maximum: Int) = floor(value.toDouble() + .5).toInt().coerceIn(0, maximum)
    private fun pack(alpha: Int, red: Int, green: Int, blue: Int) = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    private fun premultiply(pixel: Int): Int {
        val alpha = pixel ushr 24
        return pack(alpha, ((pixel ushr 16 and 255) * alpha + 127) / 255, ((pixel ushr 8 and 255) * alpha + 127) / 255, ((pixel and 255) * alpha + 127) / 255)
    }
    private fun unpremultiply(pixel: Int): Int {
        val alpha = pixel ushr 24
        if (alpha == 0) return 0
        return pack(alpha, ((pixel ushr 16 and 255) * 255 + alpha / 2) / alpha, ((pixel ushr 8 and 255) * 255 + alpha / 2) / alpha, ((pixel and 255) * 255 + alpha / 2) / alpha)
    }
}
