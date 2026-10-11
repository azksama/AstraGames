package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfVisibilityMaskTest {
    @Test fun independentOriginalByteLoopsMatchClippingAnchorAndEveryGrayValue() {
        var mask = WolfVisibilityMask(1, 1)
        var context = ""
        var samples = 0
        var frozen: NativeVisibilityMask? = null
        var frozenBytes: ByteArray? = null
        val vectors = javaClass.getResourceAsStream("/visibility-mask/w3-native.tsv") ?: error("Missing W3 mask byte reference")
        vectors.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
            if (line.isBlank() || line.startsWith('#')) return@forEach
            val a = line.split('\t')
            when(a[0]) {
                "case" -> { mask = WolfVisibilityMask(a[2].toInt(), a[3].toInt()); context = a[1]; frozen=null;frozenBytes=null }
                "fill" -> mask.fill(a[1] == "1")
                "reset" -> mask.reset()
                "draw" -> mask.draw(a[1].toInt(), a[2].toInt(), bytes(a[7]), a[3].toInt(), a[4].toInt(), a[5].toInt(), a[6].toInt())
                "sample" -> {
                    if (a[1] == "-") assertNull(context, mask.frame()) else assertArrayEquals(context, bytes(a[1]), mask.frame()!!.pixels)
                    frozen?.let { assertArrayEquals("$context frozen frame", frozenBytes, it.pixels) }
                    frozen = mask.frame(); frozenBytes=frozen?.pixels?.copyOf()
                    val restored=WolfVisibilityMask(mask.width,mask.height); restored.restore(mask.state())
                    if(mask.frame()==null)assertNull(restored.frame()) else assertArrayEquals(mask.frame()!!.pixels,restored.frame()!!.pixels)
                    samples++
                }
                else -> error("Unexpected mask reference token")
            }
        } }
        assertEquals(2736, samples)
    }

    @Test fun lazyCreationResetAndSavedBytesRetainIndependentOwnership() {
        val mask=WolfVisibilityMask(4,2)
        mask.reset();assertNull(mask.frame())
        mask.ensure(); val visible=mask.frame()!!;assertSame(visible,mask.frame())
        mask.fill(true); val hidden=mask.frame()!!
        assertArrayEquals(ByteArray(8),visible.pixels)
        val saved=mask.state()!!;saved.pixels.fill(13)
        assertTrue(hidden.pixels.all { it.toInt() == -1 })
        mask.restore(saved);saved.pixels.fill(12)
        assertTrue(mask.frame()!!.pixels.all { it.toInt() == 13 })
        mask.reset();assertNotNull(mask.frame());assertArrayEquals(ByteArray(8),mask.frame()!!.pixels)
        assertTrue(hidden.pixels.all { it.toInt() == -1 })
        mask.restore(null);assertNull(mask.frame())
    }

    @Test fun rejectsInvalidStateAndImportBeforeChangingTheCanvas() {
        val mask=WolfVisibilityMask(2,2);mask.fill(true);val before=mask.frame()
        fun rejects(action:()->Unit) { assertThrows(IllegalArgumentException::class.java,action);assertSame(before,mask.frame()) }
        rejects { mask.restore(NativeVisibilityMask(1,4,ByteArray(4))) }
        rejects { mask.restore(NativeVisibilityMask(2,2,ByteArray(3))) }
        rejects { mask.draw(2,2,ByteArray(3),0,0,0,1) }
        rejects { mask.draw(2,2,ByteArray(4),0,0,2,1) }
        rejects { mask.draw(2,2,ByteArray(4),0,0,0,2) }
        val large = WolfVisibilityMask(8192,8192)
        large.reset(); assertNull(large.frame())
        assertThrows(IllegalArgumentException::class.java) { large.ensure() }
        assertNull(large.frame())
    }

    private fun bytes(text:String)=ByteArray(text.length/2){text.substring(it*2,it*2+2).toInt(16).toByte()}
}
