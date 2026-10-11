package fr.astragames.wolf

import java.util.zip.CRC32

data class WolfImageHeaderLimits(
    val maxWidth: Int = 32768,
    val maxHeight: Int = 32768,
    val maxPixels: Long = 64L * 1024 * 1024,
    val maxHeaderBytes: Int = 1024 * 1024,
    val maxRecords: Int = 4096,
) {
    init { require(maxWidth > 0 && maxHeight > 0 && maxPixels > 0 && maxHeaderBytes > 0 && maxRecords > 0) }
}

/**
 * Intrinsic stored pixel dimensions, without decoding pixels or applying EXIF orientation.
 * A result validates the dimension header, not the entire compressed image or animation.
 * Unknown signatures return null; recognized invalid/unsupported headers fail explicitly.
 * Specifications: PNG IHDR (W3C), JPEG T.81 B.2, GIF89a logical screen (W3C),
 * Microsoft BITMAP{CORE,INFO,V4,V5}HEADER, Google WebP RIFF container.
 */
object WolfImageHeader {
    fun dimensions(data: ByteArray, name: String = "image", limits: WolfImageHeaderLimits = WolfImageHeaderLimits()): Pair<Int, Int>? {
        val reader = HeaderReader(data, name, limits)
        return when {
            data.size >= 8 && reader.ascii(0, "\u0089PNG\r\n\u001a\n") -> png(reader)
            data.size >= 2 && reader.byte(0) == 255 && reader.byte(1) == 216 -> jpeg(reader)
            data.size >= 2 && reader.ascii(0, "BM") -> bmp(reader)
            data.size >= 6 && (reader.ascii(0, "GIF87a") || reader.ascii(0, "GIF89a")) -> gif(reader)
            data.size >= 12 && reader.ascii(0, "RIFF") && reader.ascii(8, "WEBP") -> webp(reader)
            else -> null
        }
    }

    private fun png(r: HeaderReader): Pair<Int, Int> {
        r.need(0, 33)
        r.check(r.be32(8) == 13L && r.ascii(12, "IHDR"), "PNG must begin with a 13-byte IHDR")
        val depth = r.byte(24)
        val color = r.byte(25)
        val legal = when (color) { 0 -> setOf(1,2,4,8,16); 2,4,6 -> setOf(8,16); 3 -> setOf(1,2,4,8); else -> emptySet() }
        r.check(depth in legal && r.byte(26) == 0 && r.byte(27) == 0 && r.byte(28) in 0..1,
            "unsupported PNG IHDR color/depth/compression/filter/interlace")
        val crc = CRC32().also { it.update(r.data, 12, 17) }.value
        r.check(crc == r.be32(29), "PNG IHDR CRC mismatch")
        return r.size(r.be32(16), r.be32(20))
    }

    private fun jpeg(r: HeaderReader): Pair<Int, Int> {
        var offset = 2
        var records = 0
        while (offset < r.data.size) {
            r.record(++records)
            r.need(offset, 1)
            r.check(r.byte(offset) == 255, "invalid JPEG marker prefix")
            do { offset++; r.need(offset, 1) } while (r.byte(offset) == 255)
            val marker = r.byte(offset++)
            r.check(marker != 0, "JPEG byte stuffing before image data")
            r.check(marker != 0xd9 && marker != 0xda, "JPEG has no supported frame dimensions before scan/end")
            r.check(marker != 0xd8 && marker !in 0xd0..0xd7, "unexpected JPEG standalone marker")
            r.check(marker != 0xde, "hierarchical JPEG dimension headers are not supported")
            if (marker == 1) continue // TEM has no segment payload.
            r.need(offset, 2)
            val length = r.be16(offset)
            r.check(length >= 2, "invalid JPEG segment length")
            r.need(offset, length)
            val frame = marker in 0xc0..0xcf && marker !in setOf(0xc4,0xc8,0xcc)
            if (frame) {
                r.check(marker !in setOf(0xc5,0xc6,0xc7,0xcd,0xce,0xcf), "differential JPEG frame dimensions are not supported")
                r.check(length >= 8, "truncated JPEG frame header")
                val count = r.byte(offset + 7)
                r.check(count > 0 && length == 8 + 3 * count, "JPEG frame component/length mismatch")
                r.check(marker !in setOf(0xc2,0xca) || count <= 4, "JPEG progressive component count exceeds format bound")
                val precision = r.byte(offset + 2)
                val supportedPrecision = when (marker) {
                    0xc0 -> precision == 8
                    0xc3,0xc7,0xcb,0xcf -> precision in 2..16
                    else -> precision == 8 || precision == 12
                }
                r.check(supportedPrecision, "unsupported JPEG sample precision")
                val identifiers = BooleanArray(256)
                for (i in 0 until count) {
                    val at = offset + 8 + 3 * i
                    val id = r.byte(at)
                    val factors = r.byte(at + 1)
                    r.check(!identifiers[id] && factors ushr 4 in 1..4 && factors and 15 in 1..4,
                        "invalid JPEG component identifier/sampling")
                    identifiers[id] = true
                    r.check(r.byte(at + 2) in 0..(if (marker == 0xc3 || marker == 0xcb) 0 else 3),
                        "invalid JPEG quantization destination")
                }
                val width = r.be16(offset + 5)
                val height = r.be16(offset + 3)
                r.check(height != 0, "JPEG deferred DNL height is not supported")
                return r.size(width.toLong(), height.toLong())
            }
            offset += length
        }
        r.fail("truncated JPEG without a frame header")
    }

    private fun bmp(r: HeaderReader): Pair<Int, Int> {
        r.need(0, 18)
        val header = r.le32(14)
        r.check(header in setOf(12L,40L,108L,124L), "unsupported BMP DIB header $header")
        r.need(14, header.toInt())
        r.check(r.le32(10) >= 14 + header, "BMP pixel offset overlaps header")
        if (header == 12L) {
            r.check(r.le16(22) == 1 && r.le16(24) in setOf(1,4,8,24), "invalid BMP core planes/bit depth")
            return r.size(r.le16(18).toLong(), r.le16(20).toLong())
        }
        val width = r.le32(18).toInt().toLong()
        val signedHeight = r.le32(22).toInt().toLong()
        val compression = r.le32(30)
        val depth = r.le16(28)
        r.check(r.le16(26) == 1 && depth in setOf(1,4,8,16,24,32), "invalid BMP planes/bit depth")
        r.check(compression in setOf(0L,1L,2L,3L,6L), "unsupported BMP compression $compression")
        r.check(compression != 1L || depth == 8, "BMP RLE8 bit depth mismatch")
        r.check(compression != 2L || depth == 4, "BMP RLE4 bit depth mismatch")
        r.check(compression !in setOf(3L,6L) || depth in setOf(16,32), "BMP bitfields bit depth mismatch")
        r.check(signedHeight >= 0 || compression in setOf(0L,3L,6L), "compressed BMP cannot have a top-down height")
        return r.size(width, kotlin.math.abs(signedHeight))
    }

    private fun gif(r: HeaderReader): Pair<Int, Int> {
        r.need(0, 13)
        return r.size(r.le16(6).toLong(), r.le16(8).toLong())
    }

    private fun webp(r: HeaderReader): Pair<Int, Int> {
        r.need(0, 12)
        val end = r.le32(4) + 8
        r.check(end >= 12 && end <= r.data.size, "truncated/invalid WebP RIFF size")
        var offset = 12
        var records = 0
        while (offset.toLong() < end) {
            r.record(++records)
            r.check(offset.toLong() + 8 <= end, "truncated WebP chunk header")
            r.need(offset, 8)
            val count = r.le32(offset + 4)
            val body = offset + 8
            val next = body.toLong() + count + (count and 1)
            r.check(next <= end, "WebP chunk exceeds RIFF bounds")
            when {
                r.ascii(offset, "VP8X") -> {
                    r.check(count == 10L, "invalid WebP VP8X header size")
                    r.need(body, 10)
                    return r.size(1 + r.le24(body + 4), 1 + r.le24(body + 7))
                }
                r.ascii(offset, "VP8 ") -> {
                    r.check(count >= 10, "truncated WebP VP8 frame header")
                    r.need(body, 10)
                    r.check(r.byte(body) and 1 == 0 && r.byte(body + 3) == 0x9d &&
                        r.byte(body + 4) == 1 && r.byte(body + 5) == 0x2a, "invalid WebP VP8 key frame")
                    return r.size((r.le16(body + 6) and 0x3fff).toLong(), (r.le16(body + 8) and 0x3fff).toLong())
                }
                r.ascii(offset, "VP8L") -> {
                    r.check(count >= 5, "truncated WebP VP8L frame header")
                    r.need(body, 5)
                    val packed = r.le32(body + 1)
                    r.check(r.byte(body) == 0x2f && packed ushr 29 == 0L, "unsupported WebP VP8L signature/version")
                    return r.size((packed and 0x3fff) + 1, ((packed ushr 14) and 0x3fff) + 1)
                }
            }
            r.check(next <= r.limits.maxHeaderBytes, "image header scan exceeds byte budget")
            offset = next.toInt()
        }
        r.fail("WebP has no supported dimension chunk")
    }

    private class HeaderReader(val data: ByteArray, val name: String, val limits: WolfImageHeaderLimits) {
        fun fail(reason: String): Nothing = throw WolfFormatException("$name: $reason")
        fun check(condition: Boolean, reason: String) { if (!condition) fail(reason) }
        fun need(offset: Int, length: Int) {
            check(offset >= 0 && length >= 0 && offset.toLong() + length <= data.size, "truncated image header")
            check(offset.toLong() + length <= limits.maxHeaderBytes, "image header scan exceeds byte budget")
        }
        fun byte(offset: Int) = data[offset].toInt() and 255
        fun ascii(offset: Int, text: String) = offset >= 0 && offset.toLong() + text.length <= data.size &&
            text.indices.all { byte(offset + it) == text[it].code }
        fun le16(offset: Int) = byte(offset) or (byte(offset + 1) shl 8)
        fun be16(offset: Int) = (byte(offset) shl 8) or byte(offset + 1)
        fun le24(offset: Int) = (byte(offset).toLong() or (byte(offset + 1).toLong() shl 8) or (byte(offset + 2).toLong() shl 16))
        fun le32(offset: Int) = (0..3).fold(0L) { n, i -> n or (byte(offset + i).toLong() shl (8 * i)) }
        fun be32(offset: Int) = (0..3).fold(0L) { n, i -> (n shl 8) or byte(offset + i).toLong() }
        fun record(count: Int) = check(count <= limits.maxRecords, "image header record budget exceeded")
        fun size(width: Long, height: Long): Pair<Int, Int> {
            check(width in 1..limits.maxWidth.toLong() && height in 1..limits.maxHeight.toLong() &&
                width * height <= limits.maxPixels, "image dimensions exceed native bounds: ${width}x$height")
            return width.toInt() to height.toInt()
        }
    }
}
