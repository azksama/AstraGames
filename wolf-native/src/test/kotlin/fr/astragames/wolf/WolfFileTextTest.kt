package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class WolfFileTextTest {
    @Test fun readsLegacyUtf8BomAndAutomaticDetectionWithoutLosingJapanese() {
        val legacy = "日本語".toByteArray(Charset.forName("windows-31j"))
        assertEquals("日本語", WolfFileText.decode(legacy, 0, true))
        assertEquals("日本語", WolfFileText.decode(legacy, 2, true))
        assertEquals("日本語", WolfFileText.decode(legacy, 0, false))
        val utf8 = "日本語".toByteArray()
        assertEquals("日本語", WolfFileText.decode(utf8, 1, true))
        assertEquals("日本語", WolfFileText.decode(byteArrayOf(-17, -69, -65) + utf8 + byteArrayOf(0, 66), 0, true))
    }
    @Test fun writesTextModeBytesAndRejectsEveryEscapingWriteBeforeIo() {
        val bytes = WolfFileText.encode("日本語\nfin", 1, true)
        assertArrayEquals(byteArrayOf(-17, -69, -65) + "日本語\r\nfin".toByteArray(), bytes)
        assertArrayEquals("a\r\nb\n".toByteArray(), WolfFileText.encode("a\r\nb\n", 0, true, windowsTextMode = false))
        assertEquals("save/test.txt", WolfFileText.path("save/test.txt", true))
        for (path in listOf("../a", "/a", "C:/a", "a\\b", "a//b", "a/./b", "a/../b", "a%", "a?", "")) {
            try { WolfFileText.path(path, true); fail("Accepted $path") } catch (_: IllegalArgumentException) {}
        }
    }
}
