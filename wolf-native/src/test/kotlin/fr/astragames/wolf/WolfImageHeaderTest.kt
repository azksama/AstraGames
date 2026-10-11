package fr.astragames.wolf

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class WolfImageHeaderTest {
    @Test fun pngReadsIntrinsicDimensionsWithIhdrIntegrityAndColorRules() {
        val bytes = png(640,480)
        val original = bytes.clone()
        assertEquals(640 to 480, WolfImageHeader.dimensions(bytes))
        assertArrayEquals(original,bytes)
        rejects { WolfImageHeader.dimensions(bytes.clone().also { it[16] = 1 }) }
        rejects { WolfImageHeader.dimensions(png(640,480,color=3,depth=16)) }
        rejects { WolfImageHeader.dimensions(png(0,480)) }
        for (size in 8 until bytes.size) rejects { WolfImageHeader.dimensions(bytes.copyOf(size)) }
    }

    @Test fun jpegReadsBaselineProgressiveAndActualEncodedImageHeaders() {
        for (marker in listOf(0xc0,0xc2,0xc3)) assertEquals(73 to 29, WolfImageHeader.dimensions(jpeg(marker,73,29)))
        val encoded = ByteArrayOutputStream()
        assertTrue(ImageIO.write(BufferedImage(73,29,BufferedImage.TYPE_3BYTE_BGR),"jpeg",encoded))
        assertEquals(73 to 29, WolfImageHeader.dimensions(encoded.toByteArray()))
        val bytes = jpeg(0xc0,73,29)
        for (size in 2 until bytes.size-2) rejects { WolfImageHeader.dimensions(bytes.copyOf(size)) }
        rejects { WolfImageHeader.dimensions(jpeg(0xc0,73,0)) }
        rejects { WolfImageHeader.dimensions(jpeg(0xc0,73,29,precision=12)) }
        rejects { WolfImageHeader.dimensions(byteArrayOf(-1,-40,-1,-38,0,2)) }
        rejects { WolfImageHeader.dimensions(byteArrayOf(-1,-40,-1,-32,0,1)) }
    }

    @Test fun bmpReadsCoreAndWindowsHeadersIncludingTopDownHeight() {
        assertEquals(7 to 11,WolfImageHeader.dimensions(bmp(12,7,11)))
        for (header in listOf(40,108,124)) for (height in listOf(-11,11))
            assertEquals(7 to 11,WolfImageHeader.dimensions(bmp(header,7,height)))
        rejects { WolfImageHeader.dimensions(bmp(40,-7,11)) }
        rejects { WolfImageHeader.dimensions(bmp(40,7,Int.MIN_VALUE)) }
        rejects { WolfImageHeader.dimensions(bmp(40,7,11).also { put(it,14,64,4) }) }
        rejects { WolfImageHeader.dimensions(bmp(40,7,11).also { put(it,10,18,4) }) }
        rejects { WolfImageHeader.dimensions(bmp(40,7,-11).also { put(it,30,1,4); put(it,28,8,2) }) }
        for (size in 2 until 54) rejects { WolfImageHeader.dimensions(bmp(40,7,11).copyOf(size)) }
    }

    @Test fun gifReturnsLogicalCanvasSizeForBothEstablishedVersions() {
        for (version in listOf("87a","89a")) {
            val bytes = ByteArray(13).also { ("GIF$version").toByteArray().copyInto(it); put(it,6,33,2); put(it,8,91,2) }
            assertEquals(33 to 91,WolfImageHeader.dimensions(bytes))
            for (size in 6 until 13) rejects { WolfImageHeader.dimensions(bytes.copyOf(size)) }
            rejects { WolfImageHeader.dimensions(bytes.clone().also { put(it,6,0,2) }) }
        }
    }

    @Test fun webpReadsExtendedLossyLosslessAndSkipsBoundedPaddedChunks() {
        val x = ByteArray(10).also { put(it,4,320,3); put(it,7,172,3) }
        val lossy = ByteArray(10).also { it[3]=0x9d.toByte(); it[4]=1; it[5]=0x2a; put(it,6,321,2); put(it,8,173,2) }
        val lossless = ByteArray(5).also { it[0]=0x2f; put(it,1,320 or (172 shl 14),4) }
        for ((kind,payload) in listOf("VP8X" to x,"VP8 " to lossy,"VP8L" to lossless)) {
            assertEquals(321 to 173,WolfImageHeader.dimensions(webp(kind,payload)))
            assertEquals(321 to 173,WolfImageHeader.dimensions(webp(kind,payload,prefix=chunk("JUNK",byteArrayOf(7)))))
            val bytes = webp(kind,payload)
            for (size in 12 until bytes.size) rejects { WolfImageHeader.dimensions(bytes.copyOf(size)) }
        }
        rejects { WolfImageHeader.dimensions(webp("VP8X",x.copyOf(9))) }
        rejects { WolfImageHeader.dimensions(webp("VP8 ",lossy.clone().also { it[3]=0 })) }
        rejects { WolfImageHeader.dimensions(webp("VP8L",lossless.clone().also { it[4]=0x20 })) }
        rejects { WolfImageHeader.dimensions(webp("JUNK",byteArrayOf(1))) }
    }

    @Test fun scanAndPixelQuotasBoundMetadataWorkBeforeRendering() {
        rejects { WolfImageHeader.dimensions(png(1000,1000),limits=WolfImageHeaderLimits(maxPixels=100)) }
        rejects { WolfImageHeader.dimensions(png(640,480),limits=WolfImageHeaderLimits(maxWidth=100)) }
        rejects { WolfImageHeader.dimensions(png(640,480),limits=WolfImageHeaderLimits(maxHeaderBytes=32)) }
        val manySegments = byteArrayOf(-1,-40)+ByteArray(24) { when(it%4) { 0 -> -1; 1 -> -32; 2 -> 0; else -> 2 } }+
            jpeg(0xc0,73,29).copyOfRange(2,jpeg(0xc0,73,29).size)
        rejects { WolfImageHeader.dimensions(manySegments,limits=WolfImageHeaderLimits(maxRecords=3)) }
        rejects { WolfImageHeader.dimensions(manySegments,limits=WolfImageHeaderLimits(maxHeaderBytes=20)) }
        val prefixed = webp("VP8X",ByteArray(10),prefix=chunk("JUNK",ByteArray(256)))
        rejects { WolfImageHeader.dimensions(prefixed,limits=WolfImageHeaderLimits(maxHeaderBytes=64)) }
        assertNull(WolfImageHeader.dimensions(byteArrayOf()))
        assertNull(WolfImageHeader.dimensions("unsupported image format".toByteArray()))
        assertNull(WolfImageHeader.dimensions("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
    }

    private fun png(width:Int,height:Int,color:Int=2,depth:Int=8):ByteArray {
        val out = ByteArray(33)
        byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10).copyInto(out)
        put(out,8,13,4,false); "IHDR".toByteArray().copyInto(out,12)
        put(out,16,width,4,false); put(out,20,height,4,false); out[24]=depth.toByte(); out[25]=color.toByte()
        val crc = CRC32().also { it.update(out,12,17) }.value.toInt()
        put(out,29,crc,4,false)
        return out
    }
    private fun jpeg(marker:Int,width:Int,height:Int,precision:Int=8):ByteArray = byteArrayOf(-1,-40,-1,-32,0,5,1,2,3,-1,marker.toByte(),0,11,
        precision.toByte(),(height ushr 8).toByte(),height.toByte(),(width ushr 8).toByte(),width.toByte(),1,1,0x11,0,-1,-39)
    private fun bmp(header:Int,width:Int,height:Int):ByteArray = ByteArray(14+header).also {
        "BM".toByteArray().copyInto(it); put(it,2,it.size,4); put(it,10,it.size,4); put(it,14,header,4)
        if(header==12) { put(it,18,width,2); put(it,20,height,2); put(it,22,1,2); put(it,24,24,2) }
        else { put(it,18,width,4); put(it,22,height,4); put(it,26,1,2); put(it,28,24,2) }
    }
    private fun chunk(kind:String,payload:ByteArray):ByteArray = ByteArray(8).also { kind.toByteArray().copyInto(it); put(it,4,payload.size,4) }+
        payload+(if(payload.size%2==1)byteArrayOf(0)else byteArrayOf())
    private fun webp(kind:String,payload:ByteArray,prefix:ByteArray=byteArrayOf()):ByteArray {
        val body = "WEBP".toByteArray()+prefix+chunk(kind,payload)
        return ByteArray(8).also { "RIFF".toByteArray().copyInto(it); put(it,4,body.size,4) }+body
    }
    private fun put(data:ByteArray,offset:Int,value:Int,count:Int,little:Boolean=true) {
        repeat(count) { data[offset+it]=(value ushr (8*(if(little)it else count-1-it))).toByte() }
    }
    private fun rejects(action:()->Any?) = assertThrows(WolfFormatException::class.java) { action(); Unit }
}
