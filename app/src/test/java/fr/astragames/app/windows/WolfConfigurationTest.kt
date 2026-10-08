package fr.astragames.app.windows

import org.junit.Assert.*
import org.junit.Test

class WolfConfigurationTest {
    @Test fun softwareModeIsInsertedWhenIniHasOnlyOtherSettings() {
        val configured = wolfSoftwareConfiguration("Start=0\r\nSEandBGM=3\r\n")
        assertTrue(configured.contains("SoftModeFlag=1"))
        assertTrue(configured.contains("WindowModeFlag=1"))
        assertTrue(configured.contains("SEandBGM=3"))
        assertEquals(configured, wolfSoftwareConfiguration(configured))
    }
    @Test fun existingValuesWithWhitespaceAndCaseAreReplaced() {
        val configured = wolfSoftwareConfiguration(" softmodeflag = 0\r\nWindowModeFlag=2\r\n")
        assertEquals("SoftModeFlag=1\r\nWindowModeFlag=1\r\n", configured)
    }
    @Test fun fastHexKeepsSignedBytesAndLeadingZeroes() {
        assertEquals("00017f80ff", byteArrayOf(0, 1, 127, -128, -1).hexString())
    }
}
