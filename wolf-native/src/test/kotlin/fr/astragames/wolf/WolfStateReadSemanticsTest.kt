package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfStateReadSemanticsTest {
    @Test fun flagsKeepPictureCorrectionsSeparateFromPrecisionAndReservedBits() {
        for (category in 1..4) for (operation in 0..8) {
            val flags = category shl 12 or (operation shl 8) or 17
            assertTrue(WolfStateReadSemantics.recognizedFlags(flags))
            assertFalse(WolfStateReadSemantics.recognizedFlags(flags or 2))
            assertFalse(WolfStateReadSemantics.recognizedFlags(flags or 128))
            assertFalse(WolfStateReadSemantics.recognizedFlags(flags or (1 shl 24)))
        }
        assertTrue(WolfStateReadSemantics.recognizedFlags(0x4040))
        assertTrue(WolfStateReadSemantics.recognizedFlags(0x00a2_2020))
        listOf(0x1040, 0x2040, 0x3040, 0x1020, 0x3020, 0x4020, 0x4900, 0x5000)
            .forEach { assertFalse(it.toString(16), WolfStateReadSemantics.recognizedFlags(it)) }
    }

    @Test fun anchorNumbersDescribeTheNativeReferenceRatherThanItsEnum() {
        assertEquals(listOf(0, 11, 2, 20, 22, 10, 12), (0..6).map(WolfStateReadSemantics::pictureAnchor))
        assertEquals(0, WolfStateReadSemantics.pictureAnchor(-1))
        assertEquals(0, WolfStateReadSemantics.pictureAnchor(7))
    }

    @Test fun neighborsUseIdsAndPreserveNoNeighborEvenWhenTheRequestedPictureIsAbsent() {
        val ids = listOf(40, -10, 20)
        assertEquals(-10, WolfStateReadSemantics.neighborPictureId(ids, 20, false))
        assertEquals(40, WolfStateReadSemantics.neighborPictureId(ids, 20, true))
        assertEquals(20, WolfStateReadSemantics.neighborPictureId(ids, 21, false))
        assertEquals(40, WolfStateReadSemantics.neighborPictureId(ids, 21, true))
        assertEquals(-11, WolfStateReadSemantics.neighborPictureId(ids, -11, false))
        assertEquals(50, WolfStateReadSemantics.neighborPictureId(ids, 50, true))
        assertEquals(8, WolfStateReadSemantics.neighborPictureId(emptyList(), 8, false))
    }

    @Test fun delayQueriesRetainTheObservedMinimumSentinelBoundary() {
        val delays = listOf(0, -3, 30, 12, 2_000_000_000, Int.MAX_VALUE)
        assertEquals(12, WolfStateReadSemantics.minimumPositiveDelay(delays))
        assertEquals(Int.MAX_VALUE, WolfStateReadSemantics.maximumPositiveDelay(delays))
        assertEquals(-1, WolfStateReadSemantics.minimumPositiveDelay(listOf(2_000_000_000)))
        assertEquals(2_000_000_000, WolfStateReadSemantics.maximumPositiveDelay(listOf(2_000_000_000)))
        assertEquals(-1, WolfStateReadSemantics.minimumPositiveDelay(listOf(-1, 0)))
        assertEquals(-1, WolfStateReadSemantics.maximumPositiveDelay(emptyList()))
    }

    @Test fun coordinateQueriesTruncateSignedProductsAfterShakeAndPreserveInt32Overflow() {
        assertEquals(18, WolfStateReadSemantics.zoomedCoordinate(10, 2, 0, 150))
        assertEquals(-230, WolfStateReadSemantics.zoomedCoordinate(-150, 0, 10, 150))
        assertEquals(-1, WolfStateReadSemantics.zoomedCoordinate(-1, 0, 0, 150))
        assertEquals(12, WolfStateReadSemantics.zoomedCoordinate(10, 2, 50, 100))
        assertEquals(Int.MAX_VALUE - 1, WolfStateReadSemantics.zoomedCoordinate(Int.MAX_VALUE, 0, 0, 200))
    }

    @Test fun completedProcessingRetainsZeroForOneTickBeforeSettlingAtMinusOne() {
        var remaining = 2
        val frames = (1..4).map {
            remaining = WolfStateReadSemantics.pictureRemainingAfterTick(remaining, 1)
            remaining
        }
        assertEquals(listOf(1, 0, -1, -1), frames)
        assertEquals(-1, WolfStateReadSemantics.pictureRemainingAfterTick(0, 2))
    }

    @Test fun languageComesFromSetting17AndRetainsTheHundredsPrefix() {
        assertEquals(listOf(1, 1, 6, 5, 4, 2, 3), (0..6).map { WolfStateReadSemantics.language(it.toByte()) })
        assertEquals(2, WolfStateReadSemantics.language(105.toByte()))
        assertNull(WolfStateReadSemantics.language(7.toByte()))
        assertNull(WolfStateReadSemantics.language((-1).toByte()))
    }

    @Test fun colorsConvertSignedBrightnessBackToPercentAfterFloatDivision() {
        assertEquals(listOf(0, 99, 100, 100, 101, 200),
            listOf(-255, -2, 0, 1, 2, 255).map(WolfStateReadSemantics::colorPercentage))
        assertEquals(80, WolfStateReadSemantics.colorPercentage(-51))
        assertEquals(120, WolfStateReadSemantics.colorPercentage(51))
    }

    @Test fun buttonQueriesDistinguishAnEdgeFromAHeldButton() {
        assertEquals(0, WolfStateReadSemantics.pointerButtonState(false, false))
        assertEquals(1, WolfStateReadSemantics.pointerButtonState(true, false))
        assertEquals(2, WolfStateReadSemantics.pointerButtonState(true, true))
    }
}
