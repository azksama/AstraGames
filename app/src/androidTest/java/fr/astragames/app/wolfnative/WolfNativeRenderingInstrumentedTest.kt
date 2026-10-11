package fr.astragames.app.wolfnative

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.wolf.NativeCharacter
import fr.astragames.wolf.NativeDialog
import fr.astragames.wolf.NativeFrame
import fr.astragames.wolf.NativeFlash
import fr.astragames.wolf.NativeImageFilter
import fr.astragames.wolf.NativeKey
import fr.astragames.wolf.NativeMapScene
import fr.astragames.wolf.NativeMapTexture
import fr.astragames.wolf.NativePicture
import fr.astragames.wolf.NativeRect
import fr.astragames.wolf.NativeTransition
import fr.astragames.wolf.NativeTone
import fr.astragames.wolf.NativeTextPictureTiming
import fr.astragames.wolf.NativeVisibilityMask
import fr.astragames.wolf.WolfAssetSource
import fr.astragames.wolf.WolfEncoding
import fr.astragames.wolf.WolfFormats
import fr.astragames.wolf.WolfMap
import fr.astragames.wolf.WolfParser
import fr.astragames.wolf.WolfTileset
import fr.astragames.wolf.WolfTextPictureTiming
import fr.astragames.wolf.WolfXResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeNotNull
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Android Canvas/BitmapFactory/Input proof, independent of Wine and synthetic VM semantics. */
@RunWith(AndroidJUnit4::class)
class WolfNativeRenderingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun officialWolf2AndWolf3TitleTexturesKeepTheirOriginalPixels() {
        val root = InstrumentationRegistry.getArguments().getString("nativeOfficialRoot")
        assumeNotNull(root)
        for (version in listOf("22961", "3729")) {
            val file = File(root!!, "$version/Data/SystemFile/TitleGraphic.png")
            assertTrue("Official title absent: ${file.path}", file.isFile)
            val bytes = file.readBytes()
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(320, original.width); assertEquals(240, original.height)
            val frame = NativeFrame(320, 240, null, listOf(NativePicture(0, "SystemFile/TitleGraphic.png")), emptyList(), null, 0)
            fixture(null, mapOf("Data/SystemFile/TitleGraphic.png" to bytes), frame).use { state ->
                val expected = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                Canvas(expected).apply { drawColor(Color.BLACK); drawBitmap(original, 0f, 0f, Paint()) }
                val actual = state.render(320, 240)
                val expectedPixels = IntArray(320 * 240); val actualPixels = IntArray(expectedPixels.size)
                expected.getPixels(expectedPixels, 0, 320, 0, 0, 320, 240)
                actual.getPixels(actualPixels, 0, 320, 0, 0, 320, 240)
                assertArrayEquals("Native texture parity for official WOLF $version", expectedPixels, actualPixels)
                expected.recycle(); actual.recycle()
            }
            original.recycle()
        }
    }

    @Test fun anchorsMatchTheFiveWolf2WireValues() {
        val picture = NativePicture(1, x = 32f, y = 32f, width = 12f, height = 8f, color = Color.RED)
        val frame = NativeFrame(64, 64, null, listOf(picture), emptyList(), null, 1)
        val topLeft = listOf(32 to 32, 26 to 28, 32 to 24, 20 to 32, 20 to 24)
        fixture(null, emptyMap(), frame).use { state ->
            topLeft.forEachIndexed { anchor, (left, top) ->
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(picture.copy(anchor = anchor)))) }
                state.render(64, 64).let { bitmap ->
                    assertEquals("anchor=$anchor", Color.RED, bitmap.getPixel(left + 5, top + 3))
                    assertEquals("left anchor=$anchor", Color.BLACK, bitmap.getPixel(left - 1, top + 3))
                    assertEquals("bottom anchor=$anchor", Color.BLACK, bitmap.getPixel(left + 5, top + 8))
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun wolf2NonpositiveBitmapPatternOmitsDrawingWithoutSelectingTheFirstCell() {
        val texture = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        Canvas(texture).apply {
            drawRect(0f, 0f, 8f, 8f, Paint().apply { color = Color.RED })
            drawRect(8f, 0f, 16f, 8f, Paint().apply { color = Color.GREEN })
        }
        val picture = NativePicture(1, "split.png", divisionsX = 2, pattern = 0)
        val frame = NativeFrame(16, 8, null, listOf(picture), emptyList(), null, 1)
        val firstFrames = AtomicInteger()
        val firstFrameReady = CountDownLatch(1)
        try {
            fixture(null, mapOf("Data/split.png" to png(texture)), frame,
                firstFrame = { firstFrames.incrementAndGet(); firstFrameReady.countDown() }).use { state ->
                for (pattern in listOf(0, -1, -500)) {
                    val hidden = picture.copy(pattern = pattern)
                    instrumentation.runOnMainSync {
                        state.view.submitFrame(frame.copy(pictures = listOf(hidden)))
                        assertEquals("the hidden bitmap keeps its intrinsic cell size", 8 to 8, state.view.measurePicture(hidden, 5))
                    }
                    val bitmap = state.render(16, 8)
                    try { assertTrue("pattern$pattern must omit drawing", pixels(bitmap).all { it == Color.BLACK }) }
                    finally { bitmap.recycle() }
                    assertEquals("an omitted texture must not report the first game image", 0, firstFrames.get())
                }
                for ((pattern, color) in listOf(1 to Color.RED, 2 to Color.GREEN)) {
                    instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(picture.copy(pattern = pattern)))) }
                    val bitmap = state.render(16, 8)
                    try { assertEquals(color, bitmap.getPixel(3, 3)); assertEquals(Color.BLACK, bitmap.getPixel(12, 3)) }
                    finally { bitmap.recycle() }
                }
                assertTrue(firstFrameReady.await(2, TimeUnit.SECONDS))
                assertEquals(1, firstFrames.get())
            }
        } finally { texture.recycle() }
    }

    @Test fun wolf3UnsignedPatternClampSelectsTheLastCellButZeroStillOmitsDrawing() {
        val texture = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        Canvas(texture).apply {
            drawRect(0f, 0f, 8f, 8f, Paint().apply { color = Color.RED })
            drawRect(8f, 0f, 16f, 8f, Paint().apply { color = Color.GREEN })
        }
        val picture = NativePicture(1, "split.png", divisionsX = 2, pattern = 0)
        val frame = NativeFrame(16, 8, null, listOf(picture), emptyList(), null, 1)
        val firstFrames = AtomicInteger()
        val firstFrameReady = CountDownLatch(1)
        try {
            fixture(null, mapOf("Data/split.png" to png(texture)), frame,
                firstFrame = { firstFrames.incrementAndGet(); firstFrameReady.countDown() }).use { state ->
                instrumentation.runOnMainSync {
                    state.view.configure(WolfNativeView.Config(smooth = false, showFps = false, pictureBehaviorVersion = 3))
                    assertEquals(8 to 8, state.view.measurePicture(picture, 5))
                }
                val omitted = state.render(16, 8)
                try { assertTrue(pixels(omitted).all { it == Color.BLACK }) }
                finally { omitted.recycle() }
                assertEquals(0, firstFrames.get())
                for (pattern in listOf(-1, -500, Int.MIN_VALUE, 1, 2, 3, Int.MAX_VALUE)) {
                    val selected = picture.copy(pattern = pattern)
                    instrumentation.runOnMainSync {
                        state.view.submitFrame(frame.copy(pictures = listOf(selected)))
                        assertEquals("selection does not change intrinsic dimensions", 8 to 8, state.view.measurePicture(selected, 5))
                    }
                    val bitmap = state.render(16, 8)
                    try {
                        assertEquals("W3 pattern=$pattern", if (pattern == 1) Color.RED else Color.GREEN, bitmap.getPixel(3, 3))
                        assertEquals(Color.BLACK, bitmap.getPixel(12, 3))
                    } finally { bitmap.recycle() }
                }
                assertTrue(firstFrameReady.await(2, TimeUnit.SECONDS))
                assertEquals(1, firstFrames.get())
            }
        } finally { texture.recycle() }
    }

    @Test fun mapBackdropIgnoresFileAlphaWithoutChangingTheSharedPicturePixels() {
        val raw = IntArray(8 * 8) { index -> when (index % 8) {
            in 0..2 -> Color.argb(0, 220, 40, 90)
            in 3..5 -> Color.argb(128, 20, 180, 80)
            else -> Color.argb(255, 50, 70, 210)
        } }
        val bytes = rgbaPng(8, 8, raw)
        val base = map(intArrayOf(100_000)).copy(backdrop = NativeMapTexture("shared.png", 0, 0))
        val frame = NativeFrame(16, 8, base, listOf(NativePicture(1, "shared.png", x = 8f)), emptyList(), null, 1)
        fixture(base, mapOf("Data/shared.png" to bytes), frame, onRead = {
            assertTrue("background pixels must be decoded off the UI thread", Looper.myLooper() != Looper.getMainLooper())
        }).use { state ->
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val expected = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
            try {
                expected.eraseColor(Color.BLACK)
                expected.setPixels(IntArray(raw.size) { raw[it] or 0xff000000.toInt() }, 0, 8, 0, 0, 8, 8)
                Canvas(expected).drawBitmap(original, 8f, 0f, Paint())
                val actual = state.render(16, 8)
                try {
                    assertArrayEquals("trans=false on the backdrop, trans=true on the same picture resource", pixels(expected), pixels(actual))
                    assertEquals(Color.rgb(220, 40, 90), actual.getPixel(1, 3))
                    assertEquals(Color.BLACK, actual.getPixel(9, 3))
                } finally { actual.recycle() }
                assertEquals("the normal image cache retains its zero alpha", 0, Color.alpha(state.resources.peek("shared.png")!!.getPixel(1, 3)))
            } finally { original.recycle(); expected.recycle() }
        }
    }

    @Test fun mapTexturesWrapTheirFourSourcePartsWithinTheirOwnBitmapExtent() {
        val width = 6; val height = 4
        val raw = IntArray(width * height) { i -> Color.rgb(30 + i % width * 30, 40 + i / width * 40, 80 + i) }
        val bytes = rgbaPng(width, height, raw)
        val texture = NativeMapTexture("phase.png", 17, 9)
        val base = map(intArrayOf(100_000)).copy(cameraX = 80f, cameraY = 48f, backdrop = texture)
        val background = NativePicture(Int.MIN_VALUE, width = 12f, height = 8f, color = Color.BLUE)
        val frame = NativeFrame(12, 8, base, listOf(background), emptyList(), null, 1)
        val reads = AtomicInteger()
        fixture(base, mapOf("Data/phase.png" to bytes), frame, onRead = { reads.incrementAndGet() }).use { state ->
            // Independent pixel oracle: the native four cuts roll one 6×4 bitmap
            // by floor(phase/8). A 12×8 viewport must not enlarge that extent.
            val expected = IntArray(12 * 8) { i ->
                val x = i % 12; val y = i / 12
                if (x < width && y < height) raw[Math.floorMod(y - 1, height) * width + Math.floorMod(x - 2, width)] else Color.BLUE
            }
            for (opaque in listOf(true, false)) {
                instrumentation.runOnMainSync {
                    state.view.submitFrame(frame.copy(map = base.copy(backdrop = if (opaque) texture else null, fog = if (opaque) null else texture)))
                }
                state.awaitImages(); state.awaitVariants()
                repeat(3) {
                    val actual = state.render(12, 8)
                    try { assertArrayEquals("${if (opaque) "backdrop" else "fog"}: no second camera offset or viewport fill", expected, pixels(actual)) }
                    finally { actual.recycle() }
                }
            }
            assertEquals("opaque and normal images are each loaded once, with no I/O from draw", 2, reads.get())
        }
    }

    @Test fun backdropAndFogRespectPictureGroupsAndStayOutsideTheMapVisibilityMask() {
        val backdrop = NativeMapTexture("back.png", 0, 0)
        val fog = NativeMapTexture("fog.png", 0, 0)
        val base = map(intArrayOf(0)).copy(backdrop = backdrop, maskEnabled = true)
        val lower = NativePicture(-400_000, width = 16f, height = 16f, color = Color.BLUE)
        val frame = NativeFrame(16, 16, base, listOf(lower), emptyList(), null, 1,
            visibilityMask = NativeVisibilityMask(16, 16, ByteArray(16 * 16) { 1 }))
        val assets = mapOf(
            "Data/back.png" to rgbaPng(16, 16, IntArray(16 * 16) { Color.RED }),
            "Data/fog.png" to rgbaPng(16, 16, IntArray(16 * 16) { Color.argb(128, 0, 255, 0) }),
            "Data/MapChip/base.png" to rgbaPng(16, 16, IntArray(16 * 16) { Color.MAGENTA }))
        // Preload the fog at zero opacity; changing a phase/opacity must not do I/O.
        val initial = frame.copy(map = base.copy(fog = fog.copy(opacity = 0)))
        fixture(base, assets, initial).use { state ->
            fun sample(map: NativeMapScene, pictures: List<NativePicture>): Int {
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = map, pictures = pictures)) }
                val bitmap = state.render(16, 16)
                return try { bitmap.getPixel(8, 8) } finally { bitmap.recycle() }
            }
            assertEquals("backdrop is after the ≤−400000 tier and is outside the disabled map mask", Color.RED, sample(base, listOf(lower)))
            val aboveBackdrop = NativePicture(-399_999, width = 16f, height = 16f, color = Color.GREEN)
            assertEquals("the next negative tier is above the backdrop", Color.GREEN, sample(base, listOf(lower, aboveBackdrop)))
            val lastMapPicture = NativePicture(-1, width = 16f, height = 16f, color = Color.BLUE)
            assertRgbNear("fog follows even the final negative picture and is not masked", intArrayOf(0, 128, 127),
                sample(base.copy(fog = fog), listOf(lower, lastMapPicture)))
            val foreground = NativePicture(0, width = 16f, height = 16f, color = Color.RED)
            assertEquals("nonnegative pictures follow the fog", Color.RED, sample(base.copy(fog = fog), listOf(lower, lastMapPicture, foreground)))
        }
    }

    @Test fun fogUsesItsOpacityForNormalAddSubtractAndMulaBlends() {
        val base = map(intArrayOf(100_000)).copy(fog = NativeMapTexture("fog.png", 0, 0, opacity = 128))
        val background = NativePicture(-400_000, width = 16f, height = 16f, color = Color.rgb(100, 120, 140))
        val frame = NativeFrame(16, 16, base, listOf(background), emptyList(), null, 1)
        fixture(base, mapOf("Data/fog.png" to rgbaPng(16, 16, IntArray(16 * 16) { Color.argb(128, 60, 30, 200) })), frame,
            config = WolfNativeView.Config(smooth = false, showFps = false, pictureBehaviorVersion = 3)).use { state ->
            // Native MULA11: dst *= (1-alpha + alpha*src), unlike the picture
            // multiply mode whose picture opacity is ignored by the original.
            val expected = mapOf(0 to intArrayOf(90, 97, 155), 1 to intArrayOf(115, 128, 190),
                2 to intArrayOf(85, 112, 90), 3 to intArrayOf(81, 93, 132), 11 to intArrayOf(81, 93, 132))
            for ((blend, rgb) in expected) for (opacity in listOf(128, 0)) {
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = base.copy(fog = base.fog!!.copy(blend = blend, opacity = opacity)))) }
                val actual = state.render(16, 16)
                try { assertRgbNear("fog blend=$blend opacity=$opacity", if (opacity == 0) intArrayOf(100, 120, 140) else rgb, actual.getPixel(8, 8)) }
                finally { actual.recycle() }
            }
        }
    }

    @Test fun wolf2FogMultiplyUsesStraightRgbAndIgnoresBothFileAlphaAndOpacity() {
        val raw = IntArray(8 * 8) { i -> Color.argb(when (i % 8) { in 0..2 -> 0; in 3..5 -> 128; else -> 255 }, 60, 30, 200) }
        val base = map(intArrayOf(100_000)).copy(fog = NativeMapTexture("mul.png", 0, 0, blend = 3, opacity = 0))
        val background = NativePicture(-400_000, width = 16f, height = 8f, color = Color.rgb(100, 120, 140))
        val frame = NativeFrame(16, 8, base, listOf(background), emptyList(), null, 1)
        val reads = AtomicInteger()
        fixture(base, mapOf("Data/mul.png" to rgbaPng(8, 8, raw)), frame, onRead = { reads.incrementAndGet() }).use { state ->
            // DxLib D3D11 MUL4: ZERO/SRC_COLOR, without the MULA shader's
            // lerp or alpha-zero test. RGB under alpha0 remains significant.
            for (blend in listOf(3, 4, 11)) for (opacity in listOf(0, 1, 128, 255)) {
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = base.copy(fog = base.fog!!.copy(blend = blend, opacity = opacity)))) }
                val actual = state.render(16, 8)
                try {
                    for (x in listOf(1, 4, 7)) assertRgbNear("W2 MUL blend=$blend opacity=$opacity x=$x", intArrayOf(24, 14, 110), actual.getPixel(x, 3))
                    assertEquals("outside the native texture extent", Color.rgb(100, 120, 140), actual.getPixel(12, 3))
                } finally { actual.recycle() }
            }
            assertEquals("straight RGB conversion is prepared once, never in draw", 1, reads.get())
        }
    }

    @Test fun wolf2SignedNegativeTexturePhasesClipWithoutStretchingOrInventedWrapping() {
        val width = 6; val height = 4
        val raw = IntArray(width * height) { i -> Color.rgb(30 + i % width * 30, 40 + i / width * 40, 80 + i) }
        val texture = NativeMapTexture("negative.png", -17, -9)
        val base = map(intArrayOf(100_000)).copy(backdrop = texture)
        val background = NativePicture(-400_000, width = 12f, height = 8f, color = Color.BLUE)
        val frame = NativeFrame(12, 8, base, listOf(background), emptyList(), null, 1)
        fixture(base, mapOf("Data/negative.png" to rgbaPng(width, height, raw)), frame).use { state ->
            for ((phaseX, phaseY) in listOf(-17 to -9, -47 to -31, -7 to -7)) for (opaque in listOf(true, false)) {
                val selected = texture.copy(phaseX = phaseX, phaseY = phaseY)
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = base.copy(
                    backdrop = if (opaque) selected else null, fog = if (opaque) null else selected))) }
                state.awaitImages(); state.awaitVariants()
                val cutX = phaseX / 8; val cutY = phaseY / 8
                val expected = IntArray(12 * 8) { i ->
                    val sx = i % 12 - cutX; val sy = i / 12 - cutY
                    if (sx in 0 until width && sy in 0 until height) raw[sy * width + sx] else Color.BLUE
                }
                val actual = state.render(12, 8)
                try { assertArrayEquals("W2 signed phase=$phaseX,$phaseY ${if (opaque) "back" else "fog"}", expected, pixels(actual)) }
                finally { actual.recycle() }
            }
            // W2 DerivationGraph clamps the overflowing first crop, while the
            // starts of the other three crops lie outside the source and fail.
            // The first valid crop is then stretched to Wolf's original target.
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = base.copy(zoom = 1.5f))) }
            val zoomed = state.render(12, 8)
            val png = rgbaPng(width, height, raw)
            val image = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            val reference = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
            try {
                Canvas(reference).apply {
                    drawColor(Color.BLUE)
                    drawBitmap(image, android.graphics.Rect(0, 0, 6, 4), android.graphics.Rect(-3, -1, 9, 6),
                        Paint().apply { isFilterBitmap = false })
                }
                assertArrayEquals("only the native derived crop with a valid origin is drawn", pixels(reference), pixels(zoomed))
            } finally { zoomed.recycle(); reference.recycle(); image.recycle() }
        }
    }

    @Test fun fogRunsAfterBothMapTonePassesWithoutReceivingTheirTint() {
        val base = map(intArrayOf(100_000)).copy(backdrop = NativeMapTexture("back.png", 0, 0),
            fog = NativeMapTexture("fog.png", 0, 0, opacity = 128))
        val frame = NativeFrame(16, 16, base, emptyList(), emptyList(), null, 1, tone = NativeTone(-100, 40, 0))
        fixture(base, mapOf(
            "Data/back.png" to rgbaPng(16, 16, IntArray(16 * 16) { Color.rgb(200, 100, 80) }),
            "Data/fog.png" to rgbaPng(16, 16, IntArray(16 * 16) { Color.rgb(30, 60, 90) })), frame).use { state ->
            val actual = state.render(16, 16)
            // Backdrop (122,100,80), then global ADD(0,40,0), then untinted
            // fog NORMAL alpha128. Applying151 to the fog changes this value.
            try { assertRgbNear("negative and positive map tones precede the fog", intArrayOf(76, 100, 85), actual.getPixel(8, 8)) }
            finally { actual.recycle() }
        }
    }

    @Test fun mapTextureZoomUsesTheNativeIntegerCutsAndOriginExactlyOnce() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.setPixels(IntArray(64) { i -> Color.rgb(20 + i % 8 * 25, 20 + i / 8 * 25, 100) }, 0, 8, 0, 0, 8, 8)
        val texture = NativeMapTexture("zoom.png", 7, 15)
        val base = map(intArrayOf(100_000)).copy(backdrop = texture, zoom = 1.5f)
        val background = NativePicture(-400_000, width = 16f, height = 16f, color = Color.BLUE)
        val frame = NativeFrame(16, 16, base, listOf(background), emptyList(), null, 1)
        try {
            fixture(base, mapOf("Data/zoom.png" to png(source)), frame,
                config = WolfNativeView.Config(smooth = false, showFps = false, pictureBehaviorVersion = 3)).use { state ->
                // Golden four-rect destinations from the independently exported
                // W3 formula: phase7 mutates to8 at zoom150; phase15 stays15.
                val cases = listOf(
                    Triple(0f, 0f, listOf(intArrayOf(1, 1, 12, 12), intArrayOf(0, 0, 1, 1), intArrayOf(0, 1, 1, 12), intArrayOf(1, 0, 12, 1))),
                    Triple(4f, 2f, listOf(intArrayOf(0, 1, 10, 11), intArrayOf(-2, -1, 0, 1), intArrayOf(-2, 1, 0, 11), intArrayOf(0, -1, 10, 1))))
                val sources = listOf(android.graphics.Rect(0, 0, 7, 7), android.graphics.Rect(7, 7, 8, 8),
                    android.graphics.Rect(7, 0, 8, 7), android.graphics.Rect(0, 7, 7, 8))
                for ((originX, originY, destinations) in cases) for (opaque in listOf(true, false)) {
                    instrumentation.runOnMainSync {
                        state.view.submitFrame(frame.copy(map = base.copy(zoomCenterX = originX, zoomCenterY = originY,
                            backdrop = if (opaque) texture else null, fog = if (opaque) null else texture)))
                    }
                    state.awaitImages(); state.awaitVariants()
                    val expected = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(expected); canvas.drawColor(Color.BLUE)
                    // Paint() enables bilinear filtering on API29+, whereas
                    // this fixture explicitly requests nearest sampling.
                    val nearest = Paint().apply { isFilterBitmap = false }
                    destinations.forEachIndexed { i, d -> canvas.drawBitmap(source, sources[i], android.graphics.Rect(d[0], d[1], d[2], d[3]), nearest) }
                    val actual = state.render(16, 16)
                    try { assertArrayEquals("${if (opaque) "backdrop" else "fog"} zoom150 origin=$originX,$originY", pixels(expected), pixels(actual)) }
                    finally { expected.recycle(); actual.recycle() }
                }
            }
        } finally { source.recycle() }
    }

    @Test fun aFogAloneReportsTheFirstGameImageOnceWithNoIoDuringSubsequentDraws() {
        val base = map(intArrayOf(100_000)).copy(fog = NativeMapTexture("fog.png", 0, 0))
        val frame = NativeFrame(16, 16, base, emptyList(), emptyList(), null, 1)
        val reads = AtomicInteger(); val firstFrames = AtomicInteger(); val ready = CountDownLatch(1)
        fixture(base, mapOf("Data/fog.png" to rgbaPng(16, 16, IntArray(16 * 16) { Color.GREEN })), frame,
            onRead = { assertTrue(Looper.myLooper() != Looper.getMainLooper()); reads.incrementAndGet() },
            firstFrame = { firstFrames.incrementAndGet(); ready.countDown() }).use { state ->
            repeat(12) {
                val actual = state.render(16, 16)
                try { assertEquals(Color.GREEN, actual.getPixel(8, 8)) } finally { actual.recycle() }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertEquals(1, firstFrames.get()); assertEquals(1, reads.get())
        }
    }

    @Test fun protectedImagesShareDecodedBytesAcrossPicturesOpaqueVariantsFiltersAndBothMaskChannels() {
        val wrapped = instrumentation.context.assets.open("wolf-native/wolfx/quadrants.png.wolfx").use { it.readBytes() }
        val protectedPath = "protected.png"
        val decoy = rgbaPng(16, 16, IntArray(16 * 16) { Color.BLUE })
        val base = map(intArrayOf(100_000)).copy(backdrop = NativeMapTexture(protectedPath, 0, 0))
        val pictures = listOf(NativePicture(1, "$protectedPath.wolfx", x = 16f),
            NativePicture(2, protectedPath, y = 16f, imageFilters = listOf(NativeImageFilter(24))))
        val frame = NativeFrame(32, 32, base, pictures, emptyList(), null, 1)
        val reads = AtomicInteger()
        fixture(base, mapOf("Data/$protectedPath" to decoy, "Data/$protectedPath.wolfx" to wrapped), frame,
            onRead = { path ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                assertEquals("protected alias takes priority over the existing clear blue image", "Data/$protectedPath.wolfx", path)
                reads.incrementAndGet()
            }).use { state ->
            val actual = state.render(32, 32)
            try {
                assertEquals("opaque backdrop retains RGB under zero PNG alpha", Color.rgb(220, 40, 90), actual.getPixel(3, 3))
                assertEquals("normal picture remains transparent at the same point", Color.BLACK, actual.getPixel(19, 3))
                assertRgbNear("normal picture uses its file alpha", intArrayOf(10, 90, 40), actual.getPixel(27, 3))
                assertEquals(Color.rgb(40, 160, 220), actual.getPixel(19, 11))
                assertEquals("cumulative image filter receives plaintext pixels", Color.rgb(215, 95, 35), actual.getPixel(3, 27))
            } finally { actual.recycle() }
            instrumentation.runOnMainSync { state.resources.requestMask(protectedPath) }
            val end = SystemClock.uptimeMillis() + 5000
            while (!state.resources.areMasksReady(listOf(protectedPath)) && SystemClock.uptimeMillis() < end) SystemClock.sleep(10)
            assertTrue(state.resources.areMasksReady(listOf(protectedPath)))
            val red = state.resources.peekMask(protectedPath)!!.bitmap
            assertEquals(220, Color.alpha(red.getPixel(3, 3)))
            assertEquals(20, Color.alpha(red.getPixel(11, 3)))
            val done = CountDownLatch(1)
            var visibility: Result<NativeVisibilityMask>? = null
            instrumentation.runOnMainSync { state.resources.loadVisibilityImage("$protectedPath.wolfx") {
                assertEquals(Looper.getMainLooper(), Looper.myLooper()); visibility = it; done.countDown()
            } }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val blue = visibility!!.getOrThrow()
            assertEquals(16, blue.width); assertEquals(16, blue.height)
            assertEquals(90, blue.pixels[3 * 16 + 3].toInt() and 255)
            assertEquals(80, blue.pixels[3 * 16 + 11].toInt() and 255)
            assertEquals(220, blue.pixels[11 * 16 + 3].toInt() and 255)
            assertEquals(10, blue.pixels[11 * 16 + 11].toInt() and 255)
            assertEquals("all workers and aliases share the bounded decrypted payload", 1, reads.get())
        }
    }

    @Test fun optionalImagesDecodeAnExistingProtectedAliasWhenThePlainNameIsAbsent() {
        val wrapped = instrumentation.context.assets.open("wolf-native/wolfx/quadrants.png.wolfx").use { it.readBytes() }
        val reads = AtomicInteger()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val source = object : WolfAssetSource {
            override fun exists(path: String): Boolean {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                return path == "Data/shadow.png.wolfx"
            }
            override fun read(path: String): ByteArray {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                assertEquals("Data/shadow.png.wolfx", path)
                reads.incrementAndGet(); return wrapped
            }
            override fun list(path: String) = emptyList<String>()
        }
        lateinit var resources: WolfNativeResources
        val names = listOf("shadow.png", "shadow.png.wolfx")
        instrumentation.runOnMainSync {
            resources = WolfNativeResources(source, {}, { errors.add(it) })
            names.forEach { resources.request(it, optional = true) }
        }
        try {
            val end = SystemClock.uptimeMillis() + 5000
            while (names.any { resources.peek(it) == null } && errors.isEmpty() && SystemClock.uptimeMillis() < end) SystemClock.sleep(10)
            assertTrue(errors.joinToString(), errors.isEmpty())
            assertEquals("alias and explicit optional requests share the decoded bytes", 1, reads.get())
            for (name in names) {
                assertFalse("the existing protected image must not become an absent optional shadow", resources.isOptionalMissing(name))
                val bitmap = requireNotNull(resources.peek(name)) { "protected optional image $name was omitted" }
                assertEquals(16, bitmap.width); assertEquals(16, bitmap.height)
                val output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                try {
                    instrumentation.runOnMainSync { Canvas(output).apply { drawColor(Color.BLACK); drawBitmap(bitmap, 0f, 0f, Paint()) } }
                    assertEquals(Color.BLACK, output.getPixel(3, 3))
                    assertEquals(Color.rgb(40, 160, 220), output.getPixel(3, 11))
                    assertRgbNear("optional alpha comes from the protected PNG", intArrayOf(10, 90, 40), output.getPixel(11, 3))
                } finally { output.recycle() }
            }
        } finally { instrumentation.runOnMainSync { resources.close() } }
    }

    @Test fun easyWindowKeepsTheRealEighteenPixelSkinCornersFixed() {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.WHITE, Color.MAGENTA, Color.YELLOW, Color.GRAY, Color.rgb(30, 50, 70))
        val atlas = Bitmap.createBitmap(18, 18, Bitmap.Config.ARGB_8888)
        Canvas(atlas).let { canvas -> val paint = Paint(); for (row in 0..2) for (column in 0..2) {
            paint.color = colors[row * 3 + column]
            canvas.drawRect(column * 6f, row * 6f, (column + 1) * 6f, (row + 1) * 6f, paint)
        } }
        val scene = NativeFrame(48, 36, null, listOf(NativePicture(1, "BasicData/skin.png", width = 48f, height = 36f, kind = 3)), emptyList(), null, 1)
        fixture(null, mapOf("Data/BasicData/skin.png" to png(atlas)), scene).use { state ->
            state.render(48, 36).let { bitmap ->
                val xs = intArrayOf(2, 24, 45); val ys = intArrayOf(2, 18, 33)
                for (row in 0..2) for (column in 0..2) assertEquals(colors[row * 3 + column], bitmap.getPixel(xs[column], ys[row]))
                assertEquals(Color.RED, bitmap.getPixel(5, 5))
                assertEquals(Color.GREEN, bitmap.getPixel(6, 5))
                bitmap.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun additiveSubtractiveAndMultiplyBlendsHaveDistinctColorMath() {
        val back = NativePicture(1, width = 16f, height = 16f, color = Color.rgb(100, 120, 140))
        val front = NativePicture(2, width = 16f, height = 16f, color = Color.rgb(60, 30, 200))
        val scene = NativeFrame(16, 16, null, listOf(back, front), emptyList(), null, 1)
        fixture(null, emptyMap(), scene).use { state ->
            val expected = mapOf(1 to intArrayOf(160, 150, 255), 2 to intArrayOf(40, 90, 0), 3 to intArrayOf(24, 14, 110))
            expected.forEach { (blend, rgb) ->
                instrumentation.runOnMainSync { state.view.submitFrame(scene.copy(pictures = listOf(back, front.copy(blend = blend, opacity = if (blend == 3) 0 else 255)))) }
                state.render(16, 16).let { bitmap ->
                    val color = bitmap.getPixel(8, 8)
                    for ((actual, wanted) in listOf(Color.red(color) to rgb[0], Color.green(color) to rgb[1], Color.blue(color) to rgb[2]))
                        assertTrue("blend=$blend expected ${rgb.toList()} got $color", kotlin.math.abs(actual - wanted) <= 1)
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun multiplyKeepsTheDestinationBehindTransparentTexturePixels() {
        val texture = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        Canvas(texture).drawRect(8f, 0f, 16f, 16f, Paint().apply { color = Color.rgb(100, 120, 140) })
        val frame = NativeFrame(16, 16, null, listOf(
            NativePicture(1, width = 16f, height = 16f, color = Color.rgb(60, 80, 100)),
            NativePicture(2, "multiply.png", blend = 3, opacity = 0)), emptyList(), null, 1)
        fixture(null, mapOf("Data/multiply.png" to png(texture)), frame).use { state ->
            state.render(16, 16).let { image ->
                assertEquals("transparent multiplied pixels preserve the destination", Color.rgb(60, 80, 100), image.getPixel(4, 8))
                assertEquals("multiplied texture must retain the destination alpha", 255, Color.alpha(image.getPixel(12, 8)))
                assertRgbNear("opaque multiplied texture", intArrayOf(24, 38, 55), image.getPixel(12, 8))
                image.recycle()
            }
        }
        texture.recycle()
    }

    @Test fun pictureToneCombinesBaseBrightnessAndPreservesBlackAndTransparentTexturePixels() {
        val texture = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        Canvas(texture).apply {
            drawRect(4f, 0f, 8f, 16f, Paint().apply { color = Color.BLACK })
            drawRect(8f, 0f, 12f, 16f, Paint().apply { color = Color.rgb(100, 100, 100) })
            drawRect(12f, 0f, 16f, 16f, Paint().apply { color = Color.rgb(240, 240, 240) })
        }
        val picture = NativePicture(2, "tone.png", colorR = 1.5f, colorG = .5f,
            tone = NativeTone(-50, 50, 510))
        val frame = NativeFrame(16, 16, null, listOf(
            NativePicture(1, width = 16f, height = 16f, color = Color.rgb(20, 40, 60)), picture), emptyList(), null, 1)
        fixture(null, mapOf("Data/tone.png" to png(texture)), frame).use { state ->
            state.render(16, 16).let { image ->
                assertEquals("transparent source stays transparent in both passes", Color.rgb(20, 40, 60), image.getPixel(2, 8))
                assertEquals("picture ADD redraws its texture; a black texel stays black", Color.BLACK, image.getPixel(6, 8))
                assertRgbNear("150 brightness plus 290 signed tone", intArrayOf(130, 70, 200), image.getPixel(10, 8))
                assertRgbNear("ADD brightness saturates separately from the base pass", intArrayOf(255, 168, 255), image.getPixel(14, 8))
                image.recycle()
            }
            val neutral = picture.copy(colorR = 1.4f, colorG = 1f, tone = NativeTone())
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(neutral))) }
            state.render(16, 16).let { image ->
                assertRgbNear("140 percent keeps its integer +102 offset", intArrayOf(140, 100, 100), image.getPixel(10, 8))
                image.recycle()
            }
        }
        texture.recycle()
    }

    @Test fun positivePictureToneIsAdditiveAfterTheChosenBlendAndRespectsPictureOpacity() {
        val texture = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 60, 80)) }
        val background = NativePicture(1, width = 16f, height = 16f, color = Color.rgb(100, 120, 140))
        val picture = NativePicture(2, "tone.png", tone = NativeTone(127, 127, 127))
        val frame = NativeFrame(16, 16, null, listOf(background, picture), emptyList(), null, 1)
        fixture(null, mapOf("Data/tone.png" to png(texture)), frame).use { state ->
            val expected = mapOf(0 to intArrayOf(60, 90, 120), 1 to intArrayOf(160, 210, 255),
                2 to intArrayOf(80, 90, 100), 3 to intArrayOf(36, 58, 84))
            expected.forEach { (blend, rgb) ->
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(background, picture.copy(blend = blend)))) }
                state.render(16, 16).let { image ->
                    assertRgbNear("picture tone after blend $blend", rgb, image.getPixel(8, 8))
                    assertEquals(255, Color.alpha(image.getPixel(8, 8))); image.recycle()
                }
            }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(background, picture.copy(opacity = 128)))) }
            state.render(16, 16).let { image ->
                assertRgbNear("both picture passes retain opacity", intArrayOf(80, 105, 130), image.getPixel(8, 8))
                image.recycle()
            }
        }
        texture.recycle()
    }

    @Test fun pictureToneAppliesToTextAndWindowSkinsAsWellAsBitmaps() {
        val skin = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        val text = NativePicture(1, text = "WOLF", fontSize = 24f, fontColor = Color.rgb(100, 100, 100))
        val window = NativePicture(2, "skin.png", x = 100f, width = 24f, height = 24f, kind = 3)
        val frame = NativeFrame(128, 40, null, listOf(text, window), emptyList(), null, 1)
        fixture(null, mapOf("Data/skin.png" to png(skin)), frame).use { state ->
            val plain = state.render(128, 40)
            var textPixel: Pair<Int, Int>? = null
            for (y in 0 until 40) for (x in 0 until 96) if (Color.red(plain.getPixel(x, y)) == 100) textPixel = x to y
            assertTrue("Android must draw an opaque text glyph pixel", textPixel != null)
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = frame.pictures.map { it.copy(tone = NativeTone(127, -127, 0)) })) }
            state.render(128, 40).let { image ->
                val (x, y) = textPixel!!
                assertRgbNear("290 tone on rendered glyph texture", intArrayOf(150, 50, 100), image.getPixel(x, y))
                assertRgbNear("290 tone on nine-slice window texture", intArrayOf(150, 50, 100), image.getPixel(112, 12))
                assertEquals("transparent space around the glyph must not flash", Color.BLACK, image.getPixel(96, 32))
                image.recycle()
            }
            plain.recycle()
        }
        skin.recycle()
    }

    @Test fun pictureToneAndGlobalToneRespectTheMapAndInterfaceCompositionBoundary() {
        val texture = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        val picture = NativePicture(-1, "tone.png", tone = NativeTone(127, 0, 0))
        val scene = map(intArrayOf(100_000, 100_000)).let { it.copy(map = it.map.copy(width = 2)) }
        val frame = NativeFrame(32, 16, scene, listOf(picture, picture.copy(id = 0, x = 16f)),
            emptyList(), null, 1, tone = NativeTone(-127, 0, 20))
        fixture(scene, mapOf("Data/tone.png" to png(texture)), frame).use { state ->
            state.render(32, 16).let { image ->
                assertRgbNear("map tone darkens both picture passes before the global color pass", intArrayOf(75, 100, 120), image.getPixel(8, 8))
                assertRgbNear("interface retains its picture tone only", intArrayOf(150, 100, 100), image.getPixel(24, 8))
                image.recycle()
            }
        }
        texture.recycle()
    }

    @Test fun selectedAtlasPatternUsesCropRelativeToItsOwnCell() {
        val atlas = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        Canvas(atlas).let { canvas -> val paint = Paint(); for ((i, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
            paint.color = color; val x = i % 2 * 8f; val y = i / 2 * 8f; canvas.drawRect(x, y, x + 8f, y + 8f, paint)
        } }
        val picture = NativePicture(1, "atlas.png", x = 5f, y = 6f, divisionsX = 2, divisionsY = 2, pattern = 4, sourceRect = NativeRect(2f, 3f, 4f, 4f))
        val scene = NativeFrame(16, 16, null, listOf(picture), emptyList(), null, 1)
        fixture(null, mapOf("Data/atlas.png" to png(atlas)), scene).use { state ->
            state.render(16, 16).let { bitmap ->
                assertEquals(Color.YELLOW, bitmap.getPixel(7, 8))
                assertEquals(Color.BLACK, bitmap.getPixel(4, 8))
                assertEquals(Color.BLACK, bitmap.getPixel(7, 10))
                bitmap.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun unmaskedTransitionBlendsTheFrozenSceneAndTheCurrentScene() {
        val previous = NativeFrame(16, 16, null, listOf(NativePicture(1, width = 16f, height = 16f, color = Color.RED)), emptyList(), null, 1)
        val scene = previous.copy(pictures = listOf(NativePicture(1, width = 16f, height = 16f, color = Color.BLUE)), tick = 2,
            transition = NativeTransition(previous, .25f))
        fixture(null, emptyMap(), scene).use { state ->
            state.render(16, 16).let { bitmap ->
                val color = bitmap.getPixel(8, 8)
                assertTrue(kotlin.math.abs(Color.red(color) - 191) <= 1)
                assertEquals(0, Color.green(color)); assertTrue(kotlin.math.abs(Color.blue(color) - 64) <= 1)
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { state.view.submitFrame(scene.copy(transition = NativeTransition(previous, .9f, border = 64))) }
            state.render(16, 16).let { bitmap ->
                assertRgbNear("plain fade uses the VM's exact integer border", intArrayOf(191, 0, 64), bitmap.getPixel(8, 8))
                bitmap.recycle()
            }
        }
    }

    @Test fun maskedTransitionUsesTheOriginalRedChannelAndTheExactGpuBorderRampWithoutRereading() {
        // Independent RGBA encoding preserves red even where PNG alpha is zero.
        // A premultiplied decode or a luminance mask would lose these pixels.
        val columns = intArrayOf(Color.rgb(0, 255, 255), Color.rgb(64, 255, 0), Color.rgb(96, 255, 0),
            Color.rgb(128, 255, 0), Color.argb(128, 160, 0, 255), Color.rgb(192, 0, 255),
            Color.argb(0, 255, 0, 0), Color.WHITE)
        val mask = rgbaPng(16, 16, IntArray(16 * 16) { columns[it % 16 / 2] })
        val previous = NativeFrame(16, 16, null, listOf(NativePicture(1, width = 16f, height = 16f, color = Color.RED)), emptyList(), null, 1)
        val scene = previous.copy(pictures = listOf(NativePicture(1, width = 16f, height = 16f, color = Color.BLUE)), tick = 2,
            transition = NativeTransition(previous, .25f, "mask.png", border = 128))
        val reads = AtomicInteger()
        fixture(null, mapOf("Data/mask.png" to mask), scene, onRead = { reads.incrementAndGet() }).use { state ->
            state.render(16, 16).let { bitmap ->
                for (x in listOf(1, 3, 5)) assertEquals("red below the ramp", Color.BLUE, bitmap.getPixel(x, 8))
                assertRgbNear("GPU range64 ramp at red128", intArrayOf(124, 0, 131), bitmap.getPixel(7, 8))
                assertRgbNear("PNG alpha does not attenuate mask red160", intArrayOf(252, 0, 3), bitmap.getPixel(9, 8))
                for (x in listOf(11, 13, 15)) assertEquals("red above the ramp, including zero PNG alpha", Color.RED, bitmap.getPixel(x, 8))
                bitmap.recycle()
            }
            for (border in listOf(0, 64, 128, 192, 254, 255, 128)) {
                instrumentation.runOnMainSync { state.view.submitFrame(scene.copy(transition = scene.transition!!.copy(border = border))) }
                state.render(16, 16).let { bitmap ->
                    if (border in listOf(0, 255)) for (x in columns.indices) assertEquals(
                        "mask endpoint $border", if (border == 0) Color.RED else Color.BLUE, bitmap.getPixel(x * 2, 8))
                    bitmap.recycle()
                }
            }
            assertEquals("mask file is read once for the whole transition", 1, reads.get())
        }
    }

    @Test fun maskedTransitionRetainsThePhysicalMaskResolutionWhileComposingLogicalCoordinates() {
        val previous = NativeFrame(16, 16, null, listOf(NativePicture(1, width = 16f, height = 16f, color = Color.RED)), emptyList(), null, 1)
        val scene = previous.copy(pictures = listOf(NativePicture(1, width = 16f, height = 16f, color = Color.BLUE)), tick = 2,
            transition = NativeTransition(previous, .5f, "mask.png", border = 128))
        val mask = rgbaPng(32, 32, IntArray(32 * 32) { if (it % 32 < 16) Color.BLACK else Color.WHITE })
        fixture(null, mapOf("Data/mask.png" to mask), scene).use { state ->
            assertEquals(32, state.resources.peekMask("mask.png")!!.bitmap.width)
            assertEquals(32, state.resources.peekMask("mask.png")!!.bitmap.height)
            state.render(48, 48).let { bitmap ->
                assertEquals(Color.BLUE, bitmap.getPixel(10, 24))
                assertEquals(Color.RED, bitmap.getPixel(38, 24))
                bitmap.recycle()
            }
        }
    }

    @Test fun maskedTransitionComposesFrozenFlashesAndNestedCapturesInTheirOriginalOrder() {
        val previous = NativeFrame(16, 16, null, listOf(NativePicture(1, width = 16f, height = 16f, color = Color.RED)), emptyList(), null, 1,
            flash = NativeFlash(0, 255, 0, 255))
        val scene = previous.copy(pictures = listOf(NativePicture(1, width = 16f, height = 16f, color = Color.BLUE)), tick = 2,
            flash = NativeFlash(255, 0, 0, 128), transition = NativeTransition(previous, .5f, "mask.png", border = 128))
        val mask = rgbaPng(16, 16, IntArray(16 * 16) { if (it % 16 < 8) Color.BLACK else Color.WHITE })
        fixture(null, mapOf("Data/mask.png" to mask), scene).use { state ->
            state.render(16, 16).let { bitmap ->
                assertRgbNear("current flash under mask", intArrayOf(128, 0, 127), bitmap.getPixel(3, 8))
                assertEquals("captured flash survives", Color.GREEN, bitmap.getPixel(12, 8))
                bitmap.recycle()
            }
            val nested = scene.copy(pictures = listOf(NativePicture(1, width = 16f, height = 16f, color = Color.YELLOW)), tick = 3,
                flash = null, transition = NativeTransition(scene, .5f, "mask.png", border = 128))
            instrumentation.runOnMainSync { state.view.submitFrame(nested) }
            state.render(16, 16).let { bitmap ->
                assertEquals("current scene outside nested captures", Color.YELLOW, bitmap.getPixel(3, 8))
                assertEquals("nested capture retains the original flash", Color.GREEN, bitmap.getPixel(12, 8))
                bitmap.recycle()
            }
        }
    }

    @Test fun wolfTextStylesAndRevealCursorDoNotDrawFutureCharacters() {
        val text = "\\A-\\c[1]A\\c[2]B"
        val scene = NativeFrame(96, 32, null, emptyList(), emptyList(), NativeDialog(text, fontSize = 20f, bounds = NativeRect(2f, 1f, 90f, 30f), visibleCharacters = 1), 1)
        fixture(null, emptyMap(), scene).use { state ->
            instrumentation.runOnMainSync { state.view.configure(WolfNativeView.Config(smooth = false, showFps = false, textColors = mapOf(1 to Color.RED, 2 to Color.GREEN))) }
            fun count(image: Bitmap, channel: (Int) -> Int): Int = (0 until image.height).sumOf { y -> (0 until image.width).count { x -> channel(image.getPixel(x, y)) > 100 } }
            state.render(96, 32).let { bitmap ->
                assertTrue(count(bitmap, Color::red) > 0); assertEquals(0, count(bitmap, Color::green)); bitmap.recycle()
            }
            instrumentation.runOnMainSync { state.view.submitFrame(scene.copy(dialog = scene.dialog!!.copy(visibleCharacters = 2))) }
            state.render(96, 32).let { bitmap -> assertTrue(count(bitmap, Color::green) > 0); bitmap.recycle() }
        }
    }

    @Test fun textPictureRevealCountsJapaneseAndEscapedSlashInBothNativeEncodings() {
        for (encoding in listOf(WolfEncoding.UTF8, WolfEncoding.CP932)) {
            val third = if (encoding == WolfEncoding.UTF8) "😀" else "ア"
            val source = "A日${third}\\\\\r\nB"
            val prefixes = listOf("A", "A日", "A日$third", "A日${third}\\\\", source)
            val program = WolfTextPictureTiming.compile(source, textCharset(encoding), 369)
            assertEquals(5, program.glyphCount)
            var clock = WolfTextPictureTiming.initial(program, 30)
            val picture = NativePicture(1, text = source, kind = 2, fontSize = 24f, width = 240f,
                visibleCharacters = 0, textTiming = NativeTextPictureTiming(encoding, 369, true, clock))
            val frame = NativeFrame(240, 80, null, listOf(picture), emptyList(), null, 1)
            fixture(null, emptyMap(), frame).use { state ->
                var dimensions: Pair<Int, Int>? = null
                instrumentation.runOnMainSync { dimensions = state.view.measurePicture(picture, 5) }
                val expected = listOf(1, 1, 2, 2, 3, 4, 4, 5)
                for ((tick, visible) in expected.withIndex()) {
                    val layout = WolfTextPictureTiming.advance(program, clock, 30, 1)
                    clock = layout.state
                    assertEquals("$encoding tick${tick + 1}", visible, layout.visibleGlyphCount)
                    val current = picture.copy(visibleCharacters = visible,
                        textTiming = NativeTextPictureTiming(encoding, 369, true, clock))
                    instrumentation.runOnMainSync {
                        state.view.submitFrame(frame.copy(pictures = listOf(current), tick = tick.toLong() + 1))
                        assertEquals("measurement must keep the full picture geometry", dimensions, state.view.measurePicture(current, 5))
                    }
                    val actual = state.render(240, 80)
                    instrumentation.runOnMainSync {
                        state.view.submitFrame(frame.copy(pictures = listOf(picture.copy(text = prefixes[visible - 1],
                            visibleCharacters = Int.MAX_VALUE, textTiming = null))))
                    }
                    val reference = state.render(240, 80)
                    try { assertArrayEquals("$encoding tick${tick + 1} visible$visible", pixels(reference), pixels(actual)) }
                    finally { reference.recycle(); actual.recycle() }
                }
            }
        }
    }

    @Test fun textPictureStylesAndInlineAssetsConsumeOnlyTheirDrawnGlyph() {
        val icon = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val prefixes = listOf("\\A-\\c[1]A", "\\A-\\c[1]A\\c[2]日",
            "\\A-\\c[1]A\\c[2]日\\i[1]", "\\A-\\c[1]A\\c[2]日\\i[1]\\img[inline.png]",
            "\\A-\\c[1]A\\c[2]日\\i[1]\\img[inline.png]\\c[3]B")
        val source = prefixes.last()
        val program = WolfTextPictureTiming.compile(source, Charsets.UTF_8, 369)
        assertEquals(5, program.glyphCount)
        var clock = WolfTextPictureTiming.initial(program, 30)
        val picture = NativePicture(1, text = source, kind = 2, fontSize = 24f, width = 240f,
            visibleCharacters = 0, textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, clock))
        val frame = NativeFrame(240, 48, null, listOf(picture), emptyList(), null, 1)
        try {
            fixture(null, mapOf("Data/BasicData/icon001.png" to png(icon), "Data/inline.png" to png(image)), frame).use { state ->
                instrumentation.runOnMainSync { state.view.configure(WolfNativeView.Config(smooth = false, showFps = false,
                    textColors = mapOf(1 to Color.RED, 2 to Color.GREEN, 3 to Color.WHITE))) }
                for (visible in 1..5) {
                    var layout: WolfTextPictureTiming.Layout? = null
                    repeat(64) {
                        if (layout?.visibleGlyphCount != visible) {
                            layout = WolfTextPictureTiming.advance(program, clock, 30, 1); clock = layout!!.state
                        }
                    }
                    assertEquals(visible, layout!!.visibleGlyphCount)
                    val current = picture.copy(visibleCharacters = visible,
                        textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, clock))
                    instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(current))) }
                    val actual = state.render(240, 48)
                    instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(picture.copy(
                        text = prefixes[visible - 1], visibleCharacters = Int.MAX_VALUE, textTiming = null)))) }
                    val reference = state.render(240, 48)
                    try {
                        assertArrayEquals("style/image reveal$visible", pixels(reference), pixels(actual))
                        assertEquals("future icon must stay hidden", visible >= 3, pixels(actual).any { it == Color.BLUE })
                        assertEquals("future image must stay hidden", visible >= 4, pixels(actual).any { it == Color.YELLOW })
                    } finally { actual.recycle(); reference.recycle() }
                }
            }
        } finally { icon.recycle(); image.recycle() }
    }

    @Test fun literalTextPictureModeKeepsControlsAsGlyphsAndSeparatesItsLayoutCache() {
        val source = "<C>\\img[missing.png]日\\c[9]Z\\\\!"
        val program = WolfTextPictureTiming.compile(source, Charsets.UTF_8, 369, convertSpecial = false)
        val rawClock = WolfTextPictureTiming.initial(program, 240).copy(progress = 10000.0)
        val picture = NativePicture(1, text = source, kind = 2, fontSize = 20f,
            visibleCharacters = program.glyphCount, textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, false, rawClock))
        val frame = NativeFrame(640, 48, null, listOf(picture), emptyList(), null, 1)
        fixture(null, emptyMap(), frame, onRead = { error("literal text must not request $it") }).use { state ->
            val actual = state.render(640, 48)
            val reference = plainTextReference(source, 640, 48, 20f)
            try { assertArrayEquals(pixels(reference), pixels(actual)) }
            finally { actual.recycle(); reference.recycle() }

            val sameSource = "\\c[1]A"
            val specialProgram = WolfTextPictureTiming.compile(sameSource, Charsets.UTF_8, 369)
            val special = picture.copy(text = sameSource, visibleCharacters = 1,
                textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true,
                    WolfTextPictureTiming.initial(specialProgram, 240).copy(progress = 10000.0)))
            var specialSize: Pair<Int, Int>? = null
            instrumentation.runOnMainSync {
                state.view.configure(WolfNativeView.Config(smooth = false, showFps = false, textColors = mapOf(1 to Color.RED)))
                state.view.submitFrame(frame.copy(pictures = listOf(special)))
                specialSize = state.view.measurePicture(special, 5)
            }
            val styled = state.render(640, 48)
            val styledReference = plainTextReference("A", 640, 48, 20f, Color.RED)
            try { assertArrayEquals(pixels(styledReference), pixels(styled)) }
            finally { styled.recycle(); styledReference.recycle() }

            val literal = special.copy(visibleCharacters = sameSource.codePointCount(0, sameSource.length),
                textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, false, rawClock))
            instrumentation.runOnMainSync {
                state.view.submitFrame(frame.copy(pictures = listOf(literal)))
                assertTrue(state.view.measurePicture(literal, 5)!!.first > specialSize!!.first)
            }
            val switched = state.render(640, 48)
            val switchedReference = plainTextReference(sameSource, 640, 48, 20f)
            try { assertArrayEquals("the conversion mode must be part of the cache key", pixels(switchedReference), pixels(switched)) }
            finally { switched.recycle(); switchedReference.recycle() }
        }
    }

    @Test fun escapedTextPictureControlsNeverPrefetchAnImageOrSubFont() {
        val source = "\\\\img[missing.png]\\\\font[9]\\\\c[9]"
        val program = WolfTextPictureTiming.compile(source, Charsets.UTF_8, 369)
        val clock = WolfTextPictureTiming.initial(program, 240).copy(progress = 10000.0)
        val layout = WolfTextPictureTiming.layout(program, clock, 240)
        val picture = NativePicture(1, text = source, kind = 2, fontSize = 20f, visibleCharacters = layout.visibleGlyphCount,
            textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, layout.state))
        val frame = NativeFrame(640, 48, null, listOf(picture), emptyList(), null, 1)
        fixture(null, emptyMap(), frame, onRead = { error("escaped text must not request $it") }).use { state ->
            val actual = state.render(640, 48)
            val reference = plainTextReference("\\img[missing.png]\\font[9]\\c[9]", 640, 48, 20f)
            try { assertArrayEquals(pixels(reference), pixels(actual)) }
            finally { actual.recycle(); reference.recycle() }
        }
    }

    @Test fun literalDialogAndChoicesKeepTheirControlCodesWithoutResourceReads() {
        val dialog = NativeDialog("\\img[missing.png]日\\c[9]Z\\font[9]\\v[10]",
            choices = listOf("\\font[9]\\v[10]\\c[9]", "正常"), fontSize = 20f,
            bounds = NativeRect(0f, 0f, 640f, 48f), choiceX = 320f, choiceY = 64f,
            convertSpecial = false)
        val frame = NativeFrame(640, 240, null, emptyList(), emptyList(), dialog, 1)
        fixture(null, emptyMap(), frame, onRead = { error("literal dialog/choice must not request $it") }).use { state ->
            val actual = state.render(640, 240)
            val escaped = dialog.copy(text = dialog.text.replace("\\", "\\\\"),
                choices = dialog.choices.map { it.replace("\\", "\\\\") }, convertSpecial = true)
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(dialog = escaped)) }
            val reference = state.render(640, 240)
            try { assertArrayEquals("literal messages and choices must retain the same rendered glyphs", pixels(reference), pixels(actual)) }
            finally { actual.recycle(); reference.recycle() }
        }
    }

    @Test fun pictureInstantAndSixteenTickPauseFollowOnlyTheOwnerClock() {
        val instantSource = "\\>A日\\<B"
        val instantProgram = WolfTextPictureTiming.compile(instantSource, Charsets.UTF_8, 369)
        val instant = WolfTextPictureTiming.layout(instantProgram, WolfTextPictureTiming.initial(instantProgram, 0), 0)
        assertEquals(3, instant.visibleGlyphCount)
        val picture = NativePicture(1, text = instantSource, kind = 2, fontSize = 24f, width = 240f,
            visibleCharacters = instant.visibleGlyphCount,
            textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, instant.state))
        val frame = NativeFrame(240, 48, null, listOf(picture), emptyList(), null, 1)
        fixture(null, emptyMap(), frame).use { state ->
            val initial = state.render(240, 48)
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(picture.copy(
                text = "A日B", visibleCharacters = Int.MAX_VALUE, textTiming = null)))) }
            val reference = state.render(240, 48)
            try { assertArrayEquals(pixels(reference), pixels(initial)) }
            finally { reference.recycle(); initial.recycle() }

            val source = "A\\.\\c[1]日"
            val program = WolfTextPictureTiming.compile(source, Charsets.UTF_8, 369)
            var layout = WolfTextPictureTiming.advance(program, WolfTextPictureTiming.initial(program, 240), 240, 1)
            assertEquals(1, layout.visibleGlyphCount); assertEquals(16, layout.state.waitTicks)
            val paused = picture.copy(text = source, visibleCharacters = 1,
                textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, layout.state))
            instrumentation.runOnMainSync {
                state.view.configure(WolfNativeView.Config(smooth = false, showFps = false, textColors = mapOf(1 to Color.RED)))
                state.view.submitFrame(frame.copy(pictures = listOf(paused)))
            }
            val frozen = state.render(240, 48)
            try {
                repeat(3) {
                    SystemClock.sleep(25)
                    val unchanged = state.render(240, 48)
                    try { assertArrayEquals("Android redraws must not advance or rearm a native pause", pixels(frozen), pixels(unchanged)) }
                    finally { unchanged.recycle() }
                }
                for (tick in 1..16) {
                    layout = WolfTextPictureTiming.advance(program, layout.state, 240, 1)
                    val next = paused.copy(visibleCharacters = layout.visibleGlyphCount,
                        textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, layout.state))
                    instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(next), tick = tick.toLong())) }
                    val current = state.render(240, 48)
                    try {
                        if (tick < 16) assertArrayEquals("pause tick$tick", pixels(frozen), pixels(current))
                        else {
                            assertEquals(0, layout.state.waitTicks); assertEquals(2, layout.visibleGlyphCount)
                            assertTrue("the next Japanese glyph must appear after the wait", pixels(current).any { Color.red(it) > 100 && Color.green(it) < 50 })
                        }
                    } finally { current.recycle() }
                }
            } finally { frozen.recycle() }
        }
    }

    @Test fun aProgressingPictureKeepsItsCapturedFamilyAcrossPauseAndFrameFontChanges() {
        val source = "i\\.M"
        val program = WolfTextPictureTiming.compile(source, Charsets.UTF_8, 369)
        var layout = WolfTextPictureTiming.advance(program, WolfTextPictureTiming.initial(program, 240), 240, 1)
        val picture = NativePicture(1, text = source, kind = 2, fontSize = 24f, width = 240f, fontOverrides = mapOf(0 to "monospace"),
            visibleCharacters = layout.visibleGlyphCount, textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, layout.state))
        val frame = NativeFrame(240, 48, null, listOf(picture), emptyList(), null, 1)
        fixture(null, emptyMap(), frame).use { state ->
            assertTrue(resolveFont(state.resources, "monospace")); assertTrue(resolveFont(state.resources, "serif"))
            val first = state.render(240, 48)
            var originalSize: Pair<Int, Int>? = null
            instrumentation.runOnMainSync {
                originalSize = state.view.measurePicture(picture, 5)
                state.view.submitFrame(frame.copy(fontOverrides = mapOf(0 to "serif")))
                assertEquals(originalSize, state.view.measurePicture(picture, 5))
            }
            val switched = state.render(240, 48)
            try { assertArrayEquals("current-frame fonts must not change the paused prefix", pixels(first), pixels(switched)) }
            finally { first.recycle(); switched.recycle() }
            repeat(16) { layout = WolfTextPictureTiming.advance(program, layout.state, 240, 1) }
            assertEquals(2, layout.visibleGlyphCount)
            val completed = picture.copy(visibleCharacters = layout.visibleGlyphCount,
                textTiming = NativeTextPictureTiming(WolfEncoding.UTF8, 369, true, layout.state))
            instrumentation.runOnMainSync {
                state.view.submitFrame(frame.copy(pictures = listOf(completed), fontOverrides = mapOf(0 to "serif")))
                assertEquals(originalSize, state.view.measurePicture(completed, 5))
            }
            val actual = state.render(240, 48)
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(pictures = listOf(picture.copy(text = "iM",
                visibleCharacters = Int.MAX_VALUE, textTiming = null)), fontOverrides = mapOf(0 to "serif"))) }
            val reference = state.render(240, 48)
            try { assertArrayEquals("the newly revealed suffix must also use the captured Typeface", pixels(reference), pixels(actual)) }
            finally { actual.recycle(); reference.recycle() }
        }
    }

    @Test fun pauseCursorUsesItsVerticalFrameAndCoordinatesRelativeToTheText() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)
        val atlas = Bitmap.createBitmap(17, 90, Bitmap.Config.ARGB_8888)
        Canvas(atlas).let { canvas -> val paint = Paint(); colors.forEachIndexed { index, color ->
            paint.color = color; canvas.drawRect(0f, index * 15f, 17f, (index + 1) * 15f, paint)
        } }
        val dialog = NativeDialog("A", fontSize = 8f, bounds = NativeRect(4f, 7f, 40f, 16f), visibleCharacters = 0,
            pausePath = "SystemFile/pause.png", pauseFrames = 6, pauseFrame = 3, pauseX = 24f, pauseY = 20f)
        val scene = NativeFrame(48, 48, null, emptyList(), emptyList(), dialog, 1)
        fixture(null, mapOf("Data/SystemFile/pause.png" to png(atlas)), scene).use { state ->
            state.render(48, 48).let { bitmap ->
                assertEquals(Color.YELLOW, bitmap.getPixel(28, 27))
                assertEquals(Color.YELLOW, bitmap.getPixel(28, 40))
                assertEquals(Color.BLACK, bitmap.getPixel(28, 42))
                assertEquals(Color.BLACK, bitmap.getPixel(18, 30))
                bitmap.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun choiceUsesTopCenterCoordinatesAndCanHideOnlyItsWindow() {
        val cursor = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val dialog = NativeDialog("", listOf("A"), fontSize = 8f, choiceSkinPath = "intentionally-not-loaded.png", drawChoiceWindow = false,
            choiceCursorPath = "SystemFile/cursor.png", choiceX = 36f, choiceY = 8f, marginX = 8f, marginY = 8f)
        val scene = NativeFrame(72, 64, null, emptyList(), emptyList(), dialog, 1)
        fixture(null, mapOf("Data/SystemFile/cursor.png" to png(cursor)), scene).use { state ->
            val image = state.render(72, 64)
            var bounds = android.graphics.RectF()
            instrumentation.runOnMainSync {
                val field = WolfNativeView::class.java.getDeclaredField("choiceRects").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val rects = field.get(state.view) as List<android.graphics.RectF>
                bounds = android.graphics.RectF(rects.single())
            }
            assertEquals(36f, bounds.centerX(), .01f); assertEquals(8f, bounds.top, .01f)
            assertEquals(Color.RED, image.getPixel(bounds.left.toInt() + 2, bounds.top.toInt() + 2))
            image.recycle()
        }
        cursor.recycle()
    }

    @Test fun aTextPictureDoesNotExposeControlsUntilTheFirstCharacterIsVisible() {
        val first = CountDownLatch(1)
        val picture = NativePicture(1, text = "A", x = 2f, y = 2f, fontSize = 12f, visibleCharacters = 0)
        val scene = NativeFrame(32, 32, null, listOf(picture), emptyList(), null, 1)
        fixture(null, emptyMap(), scene, firstFrame = { first.countDown() }).use { state ->
            state.render(32, 32).recycle(); instrumentation.waitForIdleSync()
            assertEquals(1L, first.count)
            instrumentation.runOnMainSync { state.view.submitFrame(scene.copy(pictures = listOf(picture.copy(visibleCharacters = 1)))) }
            state.render(32, 32).recycle()
            assertTrue(first.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun wrappedChoicesDoNotOverlapAndScrolledChoicesKeepTheirOriginalIndex() {
        val chosen = mutableListOf<Int>()
        val frame = NativeFrame(160, 160, null, emptyList(), emptyList(),
            NativeDialog("", listOf("Un choix assez long pour occuper plusieurs lignes", "Court")), 1)
        fixture(null, emptyMap(), frame, choose = chosen::add).use { state ->
            state.render(160, 160).recycle()
            val field = WolfNativeView::class.java.getDeclaredField("choiceRects").apply { isAccessible = true }
            fun rows(): List<android.graphics.RectF> {
                var result = emptyList<android.graphics.RectF>()
                instrumentation.runOnMainSync {
                    @Suppress("UNCHECKED_CAST") val bounds = field.get(state.view) as List<android.graphics.RectF>
                    result = bounds.map { android.graphics.RectF(it) }
                }
                return result
            }
            val wrapped = rows(); assertEquals(2, wrapped.size)
            assertTrue(wrapped[0].height() > wrapped[1].height())
            assertTrue(wrapped[0].bottom <= wrapped[1].top && wrapped[1].bottom <= 160)
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(dialog = NativeDialog("", List(12) { "Choix $it" }, selectedChoice = 11))) }
            state.render(160, 160).recycle()
            val visible = rows(); assertTrue(visible.size in 1..11)
            visible.zipWithNext().forEach { (a, b) -> assertTrue(a.bottom <= b.top) }
            val target = visible.last(); assertTrue(target.top >= 0 && target.bottom <= 160)
            instrumentation.runOnMainSync {
                val time = SystemClock.uptimeMillis()
                for ((action, delay) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 50L)) {
                    MotionEvent.obtain(time, time + delay, action, target.centerX(), target.centerY(), 0).let { state.view.onTouchEvent(it); it.recycle() }
                }
            }
            assertEquals(listOf(11), chosen)
        }
    }

    @Test fun zeroBasedAutotileQuarterModesUseTheFiveRealAtlasRows() {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.WHITE)
        val atlas = Bitmap.createBitmap(16, 80, Bitmap.Config.ARGB_8888)
        Canvas(atlas).let { canvas -> val paint = Paint(); colors.forEachIndexed { i, color ->
            paint.color = color; canvas.drawRect(0f, i * 16f, 16f, (i + 1) * 16f, paint)
        } }
        val fixture = fixture(map(intArrayOf(200_123)), assets = mapOf("Data/MapChip/auto.png" to png(atlas)))
        fixture.use { state ->
            val image = state.render(16, 16)
            assertEquals(Color.RED, image.getPixel(2, 2))
            assertEquals(Color.GREEN, image.getPixel(13, 2))
            assertEquals(Color.BLUE, image.getPixel(2, 13))
            assertEquals(Color.YELLOW, image.getPixel(13, 13))
        }
    }

    @Test fun fileLayersAreDrawnBottomToTopAndBaseTilesHaveZeroBasedWireIndices() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888)
        Canvas(atlas).let { canvas -> val paint = Paint(); intArrayOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { i, color ->
            paint.color = color; canvas.drawRect(i * 16f, 0f, (i + 1) * 16f, 16f, paint)
        } }
        val scene = map(intArrayOf(0, 1, 2), layers = 3)
        fixture(scene, assets = mapOf("Data/MapChip/base.png" to png(atlas))).use { state ->
            assertEquals(Color.RED, state.render(16, 16).getPixel(8, 8))
        }
    }

    @Test fun transparentWireSlotSkipsEveryQuarterStateAndLastAutotileSlotsRemainReachable() {
        val atlas = Bitmap.createBitmap(16, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        for (autoCount in listOf(15, 31)) {
            val base = map(intArrayOf(100_000, 104_444, (autoCount + 1) * 100_000 + 4_444))
            val tileset = base.tileset.copy(autoImages = List(autoCount) { if (it == autoCount - 1) "MapChip/last.png" else "" },
                passage = IntArray(autoCount + 1 + 8))
            val scene = base.copy(map = base.map.copy(width = 3), tileset = tileset)
            val frame = NativeFrame(48, 16, scene, emptyList(), emptyList(), null, 1)
            fixture(scene, mapOf("Data/MapChip/last.png" to png(atlas)), frame).use { state ->
                state.render(48, 16).let { image ->
                    assertEquals("transparent slot quarter 0000, autos=$autoCount", Color.BLACK, image.getPixel(8, 8))
                    assertEquals("transparent slot quarter 4444, autos=$autoCount", Color.BLACK, image.getPixel(24, 8))
                    assertEquals("last declared autotile, autos=$autoCount", Color.MAGENTA, image.getPixel(40, 8))
                    image.recycle()
                }
            }
        }
        atlas.recycle()
    }

    @Test fun officialDungeonWireReferencesKeepTheOriginalBaseAndLastWolf2AutotilePixels() {
        val root = InstrumentationRegistry.getArguments().getString("nativeOfficialRoot")
        assumeNotNull(root)
        data class TileReference(val raw: Int, val path: String, val x: Int, val y: Int)
        for (version in listOf("22961", "3729")) {
            val directory = File(root!!, "$version/Data")
            val map = WolfFormats.parseMap(File(directory, "MapData/Dungeon.mps").readBytes(), "Dungeon.mps")
            val tileset = WolfFormats.parseTilesets(File(directory, "BasicData/TileSetData.dat").readBytes())[map.tilesetId]
            assertTrue("Official normal base index is absent", 7 in map.tiles)
            assertTrue("Official fifteenth autotile is absent", 1_604_444 in map.tiles)
            for ((raw, path, atlasX, atlasY) in listOf(
                TileReference(7, tileset.baseImage, 7 * 16, 0),
                TileReference(1_604_444, tileset.autoImages[14], 0, 4 * 16),
            )) {
                val bytes = File(directory, path).readBytes()
                val atlas = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val scene = NativeMapScene(map.copy(width = 1, height = 1, layerCount = 1, tiles = intArrayOf(raw), events = emptyList()),
                    tileset, emptyList(), 0f, 0f, 16)
                fixture(scene, mapOf("Data/$path" to bytes)).use { state ->
                    val expected = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    Canvas(expected).apply {
                        drawColor(Color.BLACK)
                        drawBitmap(atlas, android.graphics.Rect(atlasX, atlasY, atlasX + 16, atlasY + 16),
                            android.graphics.Rect(0, 0, 16, 16), Paint())
                    }
                    val actual = state.render(16, 16)
                    val expectedPixels = IntArray(256); val actualPixels = IntArray(256)
                    expected.getPixels(expectedPixels, 0, 16, 0, 0, 16, 16)
                    actual.getPixels(actualPixels, 0, 16, 0, 0, 16, 16)
                    assertArrayEquals("Original texture reference, WOLF $version wire=$raw", expectedPixels, actualPixels)
                    expected.recycle(); actual.recycle()
                }
                atlas.recycle()
            }
        }
    }

    @Test fun aDeclaredAutotileWithoutAnImageRemainsTransparent() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val base = map(intArrayOf(200_000, 0))
        val scene = base.copy(map = base.map.copy(width = 2), tileset = base.tileset.copy(autoImages = List(15) { "" }))
        fixture(scene, mapOf("Data/MapChip/base.png" to png(atlas)),
            NativeFrame(32, 16, scene, emptyList(), emptyList(), null, 1)).use { state ->
            state.render(32, 16).let { image ->
                assertEquals(Color.BLACK, image.getPixel(8, 8))
                assertEquals(Color.BLUE, image.getPixel(24, 8))
                image.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun tileGraphicEventsCanUseTheFirstZeroBasedBaseTile() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888)
        Canvas(atlas).drawRect(0f, 0f, 16f, 16f, Paint().apply { color = Color.CYAN })
        val base = map(intArrayOf(100_000))
        val scene = base.copy(characters = listOf(NativeCharacter(1, "", 8f, 16f, 0f, 0f, tileId = 0)))
        fixture(scene, mapOf("Data/MapChip/base.png" to png(atlas))).use { state ->
            state.render(16, 16).let { image -> assertEquals(Color.CYAN, image.getPixel(8, 8)); image.recycle() }
        }
        atlas.recycle()
    }

    @Test fun triangleTilesUseCharacterDepthAndHigherLayersInheritTheirPriority() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888)
        Canvas(atlas).apply {
            drawRect(0f, 0f, 16f, 16f, Paint().apply { color = Color.RED })
            drawRect(16f, 0f, 32f, 16f, Paint().apply { color = Color.GREEN })
        }
        val sprite = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val base = map(intArrayOf(1, 100_000, 0, 100_000), layers = 2)
        val flags = base.tileset.passage.copyOf().apply { this[base.tileset.passageIndex(0)] = 0x100 }
        val actor = NativeCharacter(1, "CharaChip/hero$.png", 8f, 16f, 0f, 0f)
        val scene = base.copy(map = base.map.copy(height = 2), tileset = base.tileset.copy(passage = flags), characters = listOf(actor))
        val frame = NativeFrame(16, 32, scene, emptyList(), emptyList(), null, 1)
        fixture(scene, mapOf("Data/MapChip/base.png" to png(atlas), "Data/CharaChip/hero$.png" to png(sprite)), frame).use { state ->
            fun assertDepth(character: NativeCharacter, expected: Int, target: NativeMapScene = scene) {
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = target.copy(characters = listOf(character)))) }
                state.render(16, 32).let { image -> assertEquals(expected, image.getPixel(8, 8)); image.recycle() }
            }
            assertDepth(actor, Color.GREEN) // Same row: both layers cover the sprite.
            assertDepth(actor.copy(y = 32f), Color.BLUE) // Front row: sprite covers both layers.
            assertDepth(actor.copy(y = 32f, through = true), Color.GREEN)
            assertDepth(actor.copy(onTop = true), Color.BLUE)
            val stars = flags.copyOf().apply { this[base.tileset.passageIndex(0)] = 0x10 }
            assertDepth(actor.copy(y = 32f, onTop = true), Color.GREEN, scene.copy(tileset = scene.tileset.copy(passage = stars)))
        }
        atlas.recycle(); sprite.recycle()
    }

    @Test fun characterShadowUsesItsOwnAtlasAndIsDrawnBelowTheSpriteAtItsFeet() {
        val sprite = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888)
        Canvas(sprite).drawRect(6f, 0f, 10f, 28f, Paint().apply { color = Color.BLUE })
        val shadow = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888)
        Canvas(shadow).drawRect(0f, 28f, 16f, 32f, Paint().apply { color = Color.RED })
        val character = NativeCharacter(1, "CharaChip/hero$.png", 8f, 32f, 0f, 0f, shadowPath = "SystemFile/shadow$.png")
        val base = map(intArrayOf(100_000, 100_000)); val scene = base.copy(map = base.map.copy(height = 2), characters = listOf(character))
        val frame = NativeFrame(16, 32, scene, emptyList(), emptyList(), null, 1)
        fixture(scene, mapOf("Data/CharaChip/hero$.png" to png(sprite), "Data/SystemFile/shadow$.png" to png(shadow)), frame).use { state ->
            state.render(16, 32).let { image ->
                assertEquals(Color.BLUE, image.getPixel(8, 8)); assertEquals(Color.RED, image.getPixel(2, 30)); image.recycle()
            }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = scene.copy(characters = listOf(character.copy(heightOffset = 8f))))) }
            state.render(16, 32).let { image ->
                assertEquals(Color.BLUE, image.getPixel(8, 16))
                assertEquals(Color.BLACK, image.getPixel(8, 24))
                assertEquals("The shadow must remain on the ground during a jump", Color.RED, image.getPixel(2, 30))
                image.recycle()
            }
        }
        sprite.recycle(); shadow.recycle()
    }

    @Test fun missingOrUndecodableShadowPreservesTheActualSpriteAndFirstFrame() {
        val sprite = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888)
        Canvas(sprite).drawRect(6f, 0f, 10f, 28f, Paint().apply { color = Color.BLUE })
        val character = NativeCharacter(1, "CharaChip/hero$.png", 8f, 32f, 0f, 0f, shadowPath = "SystemFile/shadow$.png")
        val base = map(intArrayOf(100_000, 100_000))
        val scene = base.copy(map = base.map.copy(height = 2), characters = listOf(character))
        val frame = NativeFrame(16, 32, scene, emptyList(), emptyList(), null, 1)
        try {
            for (badShadow in listOf<ByteArray?>(null, byteArrayOf(1, 2, 3))) {
                val notices = mutableListOf<String>(); val firstFrame = CountDownLatch(1)
                val assets = mutableMapOf("Data/CharaChip/hero$.png" to png(sprite))
                badShadow?.let { assets["Data/SystemFile/shadow$.png"] = it }
                fixture(scene, assets, frame, firstFrame = { firstFrame.countDown() }, notice = { notices += it }).use { state ->
                    repeat(2) {
                        state.render(16, 32).also { image ->
                            assertEquals(Color.BLUE, image.getPixel(8, 8))
                            assertEquals("No fabricated shadow may appear", Color.BLACK, image.getPixel(2, 30))
                            image.recycle()
                        }
                    }
                    assertTrue("A missing shadow must not hold the first frame", firstFrame.await(5, TimeUnit.SECONDS))
                    assertTrue(state.resources.isOptionalMissing("SystemFile/shadow$.png"))
                    assertFalse(state.resources.failure("SystemFile/shadow$.png"))
                    assertEquals(1, notices.size)
                    assertTrue(notices.single().contains("shadow$.png"))
                    assertTrue(notices.single().contains(if (badShadow == null) "introuvable" else "format"))
                }
            }
        } finally { sprite.recycle() }
    }

    @Test fun aToleratedShadowFailureStillFailsWhenTheSamePathBecomesRequired() {
        for (badShadow in listOf<ByteArray?>(null, byteArrayOf(1, 2, 3))) {
            val completed = CountDownLatch(1); val failed = CountDownLatch(1)
            val errors = mutableListOf<String>()
            val source = object : WolfAssetSource {
                override fun read(path: String): ByteArray {
                    assertFalse(Looper.myLooper() == Looper.getMainLooper())
                    return badShadow ?: error("missing shadow must not be read")
                }
                override fun exists(path: String) = badShadow != null
                override fun list(path: String) = emptyList<String>()
            }
            val resources = WolfNativeResources(source, { completed.countDown() }, { errors += it; failed.countDown() })
            try {
                instrumentation.runOnMainSync { resources.request("SystemFile/shadow$.png", optional = true, tolerateFailure = true) }
                assertTrue(completed.await(5, TimeUnit.SECONDS))
                assertTrue(resources.isReady(listOf("SystemFile/shadow$.png")))
                assertTrue(errors.isEmpty())
                instrumentation.runOnMainSync { resources.request("SystemFile/shadow$.png") }
                assertTrue("A required picture must not inherit optional shadow tolerance", failed.await(5, TimeUnit.SECONDS))
                assertTrue(resources.failure("SystemFile/shadow$.png"))
                assertFalse(resources.isReady(listOf("SystemFile/shadow$.png")))
                assertEquals(1, errors.size)
                assertTrue(errors.single().contains("shadow$.png"))
            } finally { instrumentation.runOnMainSync { resources.close() } }
        }
    }

    @Test fun bushCharactersReduceOnlyTheBottomHalfTileAndKeepTheOriginalBlend() {
        val sprite = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0, 0, 120)) }
        val actor = NativeCharacter(1, "CharaChip/hero$.png", 8f, 32f, 0f, 0f, bush = true)
        val base = map(intArrayOf(100_000, 100_000))
        val scene = base.copy(map = base.map.copy(height = 2), characters = listOf(actor))
        val frame = NativeFrame(16, 32, scene,
            listOf(NativePicture(-400_000, width = 16f, height = 32f, color = Color.rgb(30, 40, 200))), emptyList(), null, 1)
        val upperColors = intArrayOf(Color.rgb(0, 0, 120), Color.rgb(30, 40, 255), Color.rgb(30, 40, 80), Color.rgb(0, 0, 94))
        val lowerColors = intArrayOf(Color.rgb(20, 27, 173), Color.rgb(30, 40, 240), Color.rgb(30, 40, 160), Color.rgb(20, 27, 165))
        fun assertColor(expected: Int, actual: Int) {
            assertTrue("expected=${Integer.toHexString(expected)}, actual=${Integer.toHexString(actual)}",
                kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 1 &&
                    kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 1 &&
                    kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 1)
        }
        fixture(scene, mapOf("Data/CharaChip/hero$.png" to png(sprite)), frame).use { state ->
            for (blend in 0..3) {
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = scene.copy(characters = listOf(actor.copy(blend = blend))))) }
                state.render(16, 32).let { image ->
                    assertColor(upperColors[blend], image.getPixel(8, 23))
                    assertColor(lowerColors[blend], image.getPixel(8, 24))
                    assertColor(lowerColors[blend], image.getPixel(8, 31))
                    image.recycle()
                }
            }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = scene.copy(characters = listOf(actor.copy(opacity = 120))))) }
            state.render(16, 32).let { image ->
                assertColor(Color.rgb(16, 21, 162), image.getPixel(8, 23))
                assertColor(Color.rgb(25, 34, 187), image.getPixel(8, 24))
                image.recycle()
            }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = scene.copy(characters = listOf(actor.copy(heightOffset = 8f))))) }
            state.render(16, 32).let { image ->
                assertColor(Color.rgb(0, 0, 120), image.getPixel(8, 20))
                assertColor(Color.rgb(30, 40, 200), image.getPixel(8, 30))
                image.recycle()
            }
        }
        sprite.recycle()
    }

    @Test fun bushTileGraphicEventsAlsoSplitTheirBottomHalfTile() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888)
        Canvas(atlas).drawRect(0f, 0f, 16f, 16f, Paint().apply { color = Color.BLUE })
        val scene = map(intArrayOf(100_000)).copy(characters = listOf(
            NativeCharacter(1, "", 8f, 16f, 0f, 0f, tileId = 0, bush = true)))
        fixture(scene, mapOf("Data/MapChip/base.png" to png(atlas))).use { state ->
            state.render(16, 16).let { image ->
                assertEquals(Color.BLUE, image.getPixel(8, 7))
                assertEquals(Color.rgb(0, 0, 85), image.getPixel(8, 8))
                image.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun characterToneUsesTwoTexturePassesAndKeepsTheNativeOpacityRulesForEachBlend() {
        val sprite = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 60, 80)) }
        val actor = NativeCharacter(1, "CharaChip/hero$.png", 8f, 16f, 0f, 0f, opacity = 128, tone = NativeTone(127, 127, 127))
        val scene = map(intArrayOf(100_000)).copy(characters = listOf(actor))
        val frame = NativeFrame(16, 16, scene, listOf(NativePicture(-400_000, width = 16f, height = 16f, color = Color.rgb(100, 120, 140))), emptyList(), null, 1)
        val expected = arrayOf(intArrayOf(90, 120, 150), intArrayOf(140, 180, 220), intArrayOf(100, 120, 140), intArrayOf(78, 104, 132))
        fixture(scene, mapOf("Data/CharaChip/hero$.png" to png(sprite)), frame).use { state ->
            for (blend in 0..3) {
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = scene.copy(characters = listOf(actor.copy(blend = blend))))) }
                state.render(16, 16).let { image ->
                    assertRgbNear("character effect after blend $blend", expected[blend], image.getPixel(8, 8)); image.recycle()
                }
            }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = scene.copy(characters = listOf(actor.copy(blend = 3, opacity = 0))))) }
            state.render(16, 16).let { image ->
                assertEquals("zero character opacity suppresses every blend and effect", Color.rgb(100, 120, 140), image.getPixel(8, 8)); image.recycle()
            }
        }
        sprite.recycle()
    }

    @Test fun bushCharacterToneSplitsBothPassesAndJumpingRestoresTheFullSpriteEffect() {
        val sprite = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        val actor = NativeCharacter(1, "CharaChip/hero$.png", 8f, 32f, 0f, 0f, opacity = 128, bush = true, tone = NativeTone(127, -127, 0))
        val base = map(intArrayOf(100_000, 100_000))
        val scene = base.copy(map = base.map.copy(height = 2), characters = listOf(actor))
        val frame = NativeFrame(16, 32, scene, emptyList(), emptyList(), null, 1)
        fixture(scene, mapOf("Data/CharaChip/hero$.png" to png(sprite)), frame).use { state ->
            state.render(16, 32).let { image ->
                assertRgbNear("bush top retains opacity on both passes", intArrayOf(75, 25, 50), image.getPixel(8, 23))
                assertRgbNear("bush feet use opacity/3 on both passes", intArrayOf(25, 8, 16), image.getPixel(8, 24)); image.recycle()
            }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(map = scene.copy(characters = listOf(actor.copy(heightOffset = 8f))))) }
            state.render(16, 32).let { image ->
                for (y in listOf(15, 23)) assertRgbNear("jump removes bush and restores full ADD alpha", intArrayOf(100, 25, 50), image.getPixel(8, y))
                assertEquals(Color.BLACK, image.getPixel(8, 30)); image.recycle()
            }
        }
        sprite.recycle()
    }

    @Test fun tileGraphicCharacterToneAlsoKeepsTheWholeTileDuringBothBushPasses() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        val scene = map(intArrayOf(100_000)).copy(characters = listOf(
            NativeCharacter(1, "", 8f, 16f, 0f, 0f, tileId = 0, opacity = 128, bush = true, tone = NativeTone(127, -127, 0))))
        fixture(scene, mapOf("Data/MapChip/base.png" to png(atlas))).use { state ->
            state.render(16, 16).let { image ->
                assertRgbNear("top tile effect", intArrayOf(75, 25, 50), image.getPixel(3, 7))
                assertRgbNear("opposite top tile corner", intArrayOf(75, 25, 50), image.getPixel(12, 7))
                assertRgbNear("bottom tile effect", intArrayOf(25, 8, 16), image.getPixel(3, 8))
                assertRgbNear("opposite bottom tile corner", intArrayOf(25, 8, 16), image.getPixel(12, 8)); image.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun characterTonePreservesTransparentAndBlackTexelsAndLeavesTheShadowColorIndependent() {
        val sprite = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888)
        Canvas(sprite).apply {
            drawRect(4f, 0f, 8f, 32f, Paint().apply { color = Color.BLACK })
            drawRect(8f, 0f, 16f, 32f, Paint().apply { color = Color.rgb(100, 100, 100) })
        }
        val shadow = Bitmap.createBitmap(16, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        val actor = NativeCharacter(1, "CharaChip/hero$.png", 8f, 32f, 0f, 0f, shadowPath = "CharaChip/shadow$.png", tone = NativeTone(127, -127, 0))
        val base = map(intArrayOf(100_000, 100_000))
        val scene = base.copy(map = base.map.copy(height = 2), characters = listOf(actor))
        val frame = NativeFrame(16, 32, scene, emptyList(), emptyList(), null, 1)
        fixture(scene, mapOf("Data/CharaChip/hero$.png" to png(sprite), "Data/CharaChip/shadow$.png" to png(shadow)), frame).use { state ->
            state.render(16, 32).let { image ->
                assertRgbNear("sprite effect", intArrayOf(150, 50, 100), image.getPixel(12, 10))
                assertEquals("positive brightness preserves a black sprite texel", Color.BLACK, image.getPixel(6, 10))
                assertEquals("transparent sprite preserves the shadow's own RGB", Color.rgb(100, 100, 100), image.getPixel(2, 30)); image.recycle()
            }
            val additive = frame.copy(pictures = listOf(NativePicture(-400_000, width = 16f, height = 32f, color = Color.rgb(40, 60, 80))),
                map = scene.copy(characters = listOf(actor.copy(blend = 1, opacity = 128))))
            instrumentation.runOnMainSync { state.view.submitFrame(additive) }
            state.render(16, 32).let { image ->
                assertRgbNear("shadow inherits the original character ADD blend", intArrayOf(90, 110, 130), image.getPixel(2, 30)); image.recycle()
            }
            instrumentation.runOnMainSync { state.view.submitFrame(additive.copy(map = scene.copy(characters = listOf(actor.copy(blend = 1, opacity = 128, bush = true))))) }
            state.render(16, 32).let { image ->
                assertRgbNear("bush attenuates the whole shadow before the sprite effect", intArrayOf(56, 76, 96), image.getPixel(2, 30)); image.recycle()
            }
        }
        sprite.recycle(); shadow.recycle()
    }

    @Test fun mapZoomTransformsTilesButKeepsBothPictureTiersAtTheirOwnLogicalCoordinates() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888)
        Canvas(atlas).drawRect(0f, 0f, 16f, 16f, Paint().apply { color = Color.GREEN })
        val base = map(intArrayOf(100_000, 100_000, 100_000, 0))
        val scene = base.copy(map = base.map.copy(width = 2, height = 2), zoom = 2f, zoomCenterX = 16f, zoomCenterY = 16f)
        val frame = NativeFrame(32, 32, scene, listOf(
            NativePicture(-200_000, x = 12f, y = 12f, width = 4f, height = 4f, color = Color.RED),
            NativePicture(0, x = 24f, y = 24f, width = 4f, height = 4f, color = Color.BLUE)), emptyList(), null, 1)
        fixture(scene, mapOf("Data/MapChip/base.png" to png(atlas)), frame).use { state ->
            state.render(32, 32).let { image ->
                assertEquals(Color.BLACK, image.getPixel(9, 9)); assertEquals(Color.RED, image.getPixel(15, 15))
                assertEquals(Color.GREEN, image.getPixel(20, 20))
                assertEquals(Color.BLACK, image.getPixel(7, 9)); assertEquals(Color.BLUE, image.getPixel(25, 25))
                assertEquals(Color.GREEN, image.getPixel(23, 25)); image.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun globalToneChangesTilesAndNegativePicturesButLeavesInterfacePicturesUntinted() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888)
        Canvas(atlas).drawRect(0f, 0f, 16f, 16f, Paint().apply { color = Color.rgb(120, 80, 60) })
        val base = map(intArrayOf(0, 0))
        val scene = base.copy(map = base.map.copy(width = 2))
        val frame = NativeFrame(32, 16, scene, listOf(
            NativePicture(-1, x = 16f, width = 8f, height = 16f, color = Color.rgb(100, 200, 150)),
            NativePicture(0, x = 24f, width = 8f, height = 16f, color = Color.rgb(100, 200, 150))),
            emptyList(), null, 1, tone = NativeTone(-127, 51, -255))
        fixture(scene, mapOf("Data/MapChip/base.png" to png(atlas)), frame).use { state ->
            repeat(3) {
                state.render(32, 16).let { image ->
                    assertRgbNear("toned base tile", intArrayOf(60, 131, 0), image.getPixel(4, 8))
                    assertRgbNear("toned negative picture", intArrayOf(50, 251, 0), image.getPixel(20, 8))
                    assertEquals("interface picture must keep its own color", Color.rgb(100, 200, 150), image.getPixel(28, 8))
                    image.recycle()
                }
            }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(tone = NativeTone())) }
            state.render(32, 16).let { image ->
                assertEquals(Color.rgb(120, 80, 60), image.getPixel(4, 8))
                assertEquals(Color.rgb(100, 200, 150), image.getPixel(20, 8))
                image.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun negativeToneDimsAdditiveSourcesBeforeTheySaturate() {
        val scene = map(intArrayOf(100_000))
        val frame = NativeFrame(16, 16, scene, listOf(
            NativePicture(-200_002, width = 16f, height = 16f, color = Color.rgb(200, 200, 200)),
            NativePicture(-200_001, width = 16f, height = 16f, color = Color.rgb(100, 100, 100), blend = 1)),
            emptyList(), null, 1, tone = NativeTone(-127, -127, -127))
        fixture(scene, emptyMap(), frame).use { state ->
            state.render(16, 16).let { image ->
                assertRgbNear("brightness must precede additive saturation", intArrayOf(150, 150, 150), image.getPixel(8, 8))
                image.recycle()
            }
        }
    }

    @Test fun globalFlashUsesNormalAlphaAboveInterfaceAndBelowTheFrozenTransition() {
        val previous = NativeFrame(16, 16, null, listOf(NativePicture(0, width = 16f, height = 16f, color = Color.GREEN)), emptyList(), null, 0)
        val frame = NativeFrame(16, 16, null, listOf(NativePicture(100_000, width = 16f, height = 16f, color = Color.rgb(0, 0, 200))),
            emptyList(), null, 1, flash = NativeFlash(255, 0, 0, 128))
        fixture(null, emptyMap(), frame).use { state ->
            state.render(16, 16).let { image ->
                assertRgbNear("NORMAL flash must reduce the blue destination", intArrayOf(128, 0, 100), image.getPixel(8, 8))
                image.recycle()
            }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(transition = NativeTransition(previous, 0f))) }
            state.render(16, 16).let { image -> assertEquals(Color.GREEN, image.getPixel(8, 8)); image.recycle() }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(transition = NativeTransition(previous, .5f))) }
            state.render(16, 16).let { image ->
                assertRgbNear("frozen frame must composite after the flash", intArrayOf(64, 127, 50), image.getPixel(8, 8))
                image.recycle()
            }
        }
    }

    @Test fun zOrderReordersPicturesOnlyInsideTheirFixedIdGroup() {
        val scene = map(intArrayOf(100_000))
        val frame = NativeFrame(16, 16, scene, listOf(
            NativePicture(-400_000, width = 16f, height = 16f, color = Color.RED, z = 2_000_000_000),
            NativePicture(-399_999, width = 16f, height = 16f, color = Color.BLUE, z = -2_000_000_000)), emptyList(), null, 1)
        fixture(scene, emptyMap(), frame).use { state ->
            state.render(16, 16).let { image -> assertEquals("Z cannot cross the -400000 boundary", Color.BLUE, image.getPixel(8, 8)); image.recycle() }
            val interfaceFrame = frame.copy(pictures = frame.pictures + NativePicture(0, width = 16f, height = 16f, color = Color.GREEN, z = -2_000_000_000))
            instrumentation.runOnMainSync { state.view.submitFrame(interfaceFrame) }
            state.render(16, 16).let { image -> assertEquals(Color.GREEN, image.getPixel(8, 8)); image.recycle() }
            val sameGroup = frame.copy(pictures = listOf(
                NativePicture(1, width = 16f, height = 16f, color = Color.RED, z = 1),
                NativePicture(2, width = 16f, height = 16f, color = Color.BLUE, z = 0)))
            instrumentation.runOnMainSync { state.view.submitFrame(sameGroup) }
            state.render(16, 16).let { image -> assertEquals("higher Z must beat higher ID in one group", Color.RED, image.getPixel(8, 8)); image.recycle() }
        }
    }

    @Test fun systemFontWeightChangesTheActualTextPixelsAndInvalidatesItsLayout() {
        val picture = NativePicture(0, text = "MMMMMMMM", fontSize = 32f)
        val frame = NativeFrame(320, 64, null, listOf(picture), emptyList(), null, 1, fontWeight = 1)
        fixture(null, emptyMap(), frame).use { state ->
            fun ink(): Int = state.render(320, 64).let { image ->
                val pixels = IntArray(320 * 64); image.getPixels(pixels, 0, 320, 0, 0, 320, 64); image.recycle()
                pixels.count { Color.red(it) > 64 }
            }
            val thin = ink()
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(fontWeight = 9)) }
            val thick = ink()
            assertTrue("Typeface weight had no observable effect: thin=$thin thick=$thick", thick > thin)
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(fontWeight = 1)) }
            assertEquals(thin, ink())
        }
    }

    @Test fun eightDirectionSpriteUsesFourRowsAndTheSecondColumnGroup() {
        val atlas = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        Canvas(atlas).let { canvas -> val paint = Paint(); for (row in 0..3) for (column in 0..5) {
            paint.color = Color.rgb(20 + column * 30, 20 + row * 50, 40)
            canvas.drawRect(column * 16f, row * 16f, (column + 1) * 16f, (row + 1) * 16f, paint)
        } }
        val character = NativeCharacter(1, "CharaChip/hero.png", 8f, 16f, 0f, 0f, direction = 9, pattern = 1)
        val scene = map(intArrayOf(100_000)).copy(characters = listOf(character), characterDirections = 8)
        fixture(scene, assets = mapOf("Data/CharaChip/hero.png" to png(atlas))).use { state ->
            assertEquals(Color.rgb(140, 170, 40), state.render(16, 16).getPixel(8, 8))
            instrumentation.runOnMainSync { state.view.submitFrame(NativeFrame(16, 16, scene.copy(characters = listOf(character.copy(direction = 3))), emptyList(), emptyList(), null, 1)) }
            assertEquals(Color.rgb(140, 70, 40), state.render(16, 16).getPixel(8, 8))
            instrumentation.runOnMainSync { state.view.submitFrame(NativeFrame(16, 16, scene.copy(characters = listOf(character.copy(direction = 7))), emptyList(), emptyList(), null, 1)) }
            assertEquals(Color.rgb(140, 120, 40), state.render(16, 16).getPixel(8, 8))
        }
    }

    @Test fun twoFingerBackDoesNotLeakTheInitialOneFingerClick() {
        val keys = mutableListOf<Pair<NativeKey, Boolean>>()
        val clicks = mutableListOf<Boolean>()
        val scene = NativeFrame(100, 100, null, listOf(NativePicture(1, x = 0f, y = 0f, width = 100f, height = 100f, color = Color.BLUE)), emptyList(), null, 1)
        fixture(null, emptyMap(), scene, key = { key, down -> keys += key to down }, pointer = { _, _, down -> clicks += down }).use { state ->
            state.render(100, 100)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val now = SystemClock.uptimeMillis()
                val first = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val second = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
                fun coordinate(x: Float, y: Float) = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
                fun dispatch(time: Long, action: Int, two: Boolean) {
                    val event = MotionEvent.obtain(now, now + time, action, if (two) 2 else 1,
                        if (two) arrayOf(first, second) else arrayOf(first),
                        if (two) arrayOf(coordinate(30f, 40f), coordinate(70f, 40f)) else arrayOf(coordinate(30f, 40f)),
                        0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    state.view.onTouchEvent(event); event.recycle()
                }
                dispatch(0, MotionEvent.ACTION_DOWN, false)
                dispatch(35, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), true)
                dispatch(90, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), true)
                dispatch(120, MotionEvent.ACTION_UP, false)
                state.view.configure(WolfNativeView.Config(showFps = false, backKey = NativeKey.X))
                dispatch(400, MotionEvent.ACTION_DOWN, false)
                dispatch(435, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), true)
                dispatch(490, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), true)
                dispatch(520, MotionEvent.ACTION_UP, false)
            }
            assertTrue(keys.contains(NativeKey.BACK to true))
            assertTrue(keys.contains(NativeKey.X to true))
            assertFalse(clicks.contains(true))
        }
    }

    @Test fun controlsWaitUntilEveryReferencedPictureHasDecoded() {
        val blocked = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        val firstFrames = CountDownLatch(1)
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = png(image)
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray {
                if (path.endsWith("slow.png")) { loaded.countDown(); assertTrue(blocked.await(5, TimeUnit.SECONDS)) }
                return bytes
            }
            override fun exists(path: String) = path == "Data/fast.png" || path == "Data/slow.png"
            override fun list(path: String) = emptyList<String>()
        }
        val frame = NativeFrame(16, 16, null,
            listOf(NativePicture(1, "fast.png"), NativePicture(2, "slow.png", x = 8f)), emptyList(), null, 0)
        val changed = CountDownLatch(1)
        lateinit var resources: WolfNativeResources
        lateinit var view: WolfNativeView
        instrumentation.runOnMainSync {
            resources = WolfNativeResources(source, { changed.countDown() }, { throw AssertionError(it) })
            view = WolfNativeView(context, resources, WolfNativeView.Callbacks({ _, _ -> }, { _, _, _ -> }, firstFrame = { firstFrames.countDown() }, error = { throw AssertionError(it) }))
            view.configure(WolfNativeView.Config(showFps = false)); view.layout(0, 0, 16, 16); view.submitFrame(frame)
        }
        try {
            assertTrue(loaded.await(5, TimeUnit.SECONDS)); assertTrue(changed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { view.draw(Canvas(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))) }
            instrumentation.waitForIdleSync()
            assertEquals(1L, firstFrames.count)
            blocked.countDown()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!resources.isReady(listOf("fast.png", "slow.png")) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
            assertTrue(resources.isReady(listOf("fast.png", "slow.png")))
            instrumentation.runOnMainSync { view.draw(Canvas(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))) }
            assertTrue(firstFrames.await(5, TimeUnit.SECONDS))
        } finally {
            blocked.countDown()
            instrumentation.runOnMainSync { view.close(); resources.close() }
        }
    }

    private fun map(tiles: IntArray, layers: Int = 1): NativeMapScene {
        val map = WolfMap("fixture", "fixture", 100, WolfEncoding.CP932, 0, 1, 1, layers, tiles, emptyList(), 0x65, null)
        val tileset = WolfTileset(0, "fixture", "MapChip/base.png", listOf("MapChip/auto.png") + List(14) { "" }, byteArrayOf(), IntArray(24))
        return NativeMapScene(map, tileset, emptyList(), 0f, 0f, 16)
    }

    @Test fun textPictureMeasurementsUseTheSameLogicalLayoutAndRemainIndependentOfZoom() {
        val picture = NativePicture(1, text = "Wolf\\f[24] RPG\nAndroid", fontSize = 16f)
        val scene = NativeFrame(320, 240, null, listOf(picture), emptyList(), null, 1)
        fixture(null, emptyMap(), scene).use { state ->
            var ordinary: Pair<Int, Int>? = null
            var wrapped: Pair<Int, Int>? = null
            instrumentation.runOnMainSync {
                ordinary = state.view.measurePicture(picture, 5)
                wrapped = state.view.measurePicture(picture.copy(width = 45f), 5)
                state.view.configure(WolfNativeView.Config(mode = WolfNativeView.DisplayMode.STRETCH, pinchZoom = true, showFps = false))
                state.view.layout(0, 0, 1080, 1920)
                assertEquals(ordinary, state.view.measurePicture(picture.copy(scaleX = 3f, scaleY = .5f, rotation = 45f), 5))
            }
            assertTrue(ordinary!!.first > 45)
            assertTrue(ordinary!!.second > 32)
            assertTrue(wrapped!!.first <= 45)
            assertTrue(wrapped!!.second > ordinary!!.second)
        }
    }

    @Test fun officialMenuAbsoluteCursorReservesWidthAndRightAlignsNumbersOnTheLabelRow() {
        // Unmodified W3 official menu NativeFrame: picture 10213.
        val picture = NativePicture(10213, text = "\\f[8]HP\\ax[50]\n<R>\\ay[0]50\\f[6]/50", x = 2f, y = 2f, fontSize = 12f)
        val scene = NativeFrame(80, 24, null, listOf(picture), emptyList(), null, 1)
        fixture(null, emptyMap(), scene).use { state ->
            lateinit var expected: NativeFrame
            instrumentation.runOnMainSync {
                assertEquals(50, state.view.measurePicture(picture, 5)!!.first)
                val value = picture.copy(id = 10214, text = "\\f[8]50\\f[6]/50")
                val valueWidth = state.view.measurePicture(value, 5)!!.first
                expected = scene.copy(pictures = listOf(picture.copy(text = "\\f[8]HP"), value.copy(x = 52f - valueWidth)))
            }
            val actual = state.render(80, 24)
            instrumentation.runOnMainSync { state.view.submitFrame(expected) }
            val reference = state.render(80, 24)
            try {
                // The exact fractional Android advance may differ from its
                // integer measurement by <1px; compare occupied regions.
                fun occupied(image: Bitmap, from: Int, until: Int): Pair<Int, Int> {
                    val points = (0 until image.height).flatMap { y -> (from until until).filter { x -> image.getPixel(x, y) != Color.BLACK }.map { y } }
                    assertTrue(points.isNotEmpty()); return points.min() to points.max()
                }
                assertEquals(occupied(reference, 2, 15), occupied(actual, 2, 15))
                assertEquals(occupied(reference, 30, 54), occupied(actual, 30, 54))
                assertTrue((0 until actual.height).all { y -> (16 until 28).all { x -> actual.getPixel(x, y) == Color.BLACK } })
                assertTrue((0 until actual.height).all { y -> (53 until 80).all { x -> actual.getPixel(x, y) == Color.BLACK } })
            } finally { actual.recycle(); reference.recycle() }
        }
    }

    @Test fun loopingMapWrapsNegativeTilesAndProjectsCharactersAtTheCameraBoundary() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        Canvas(atlas).let { canvas -> val paint = Paint(); colors.forEachIndexed { i, color ->
            paint.color = color; canvas.drawRect(i * 16f, 0f, (i + 1) * 16f, 16f, paint)
        } }
        val hero = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val base = map(intArrayOf(0))
        val loop = base.copy(map = base.map.copy(width = 2, height = 2, tiles = intArrayOf(0, 1, 2, 3), loopX = true, loopY = true),
            cameraX = -16f, cameraY = -16f)
        val frame = NativeFrame(48, 48, loop, emptyList(), emptyList(), null, 1)
        fixture(loop, mapOf("Data/MapChip/base.png" to png(atlas), "Data/CharaChip/hero$.png" to png(hero)),
            frame.copy(map = loop.copy(characters = listOf(NativeCharacter(1, "CharaChip/hero$.png", 8f, 16f, 0f, 0f))))).use { state ->
            val image = state.render(48, 48)
            try {
                for (y in 0..2) for (x in 0..2) {
                    if (x == 1 && y == 1) continue
                    assertEquals("wrapped tile $x,$y", colors[Math.floorMod(y - 1, 2) * 2 + Math.floorMod(x - 1, 2)], image.getPixel(x * 16 + 4, y * 16 + 4))
                }
            } finally { image.recycle() }
            instrumentation.runOnMainSync {
                state.view.submitFrame(frame.copy(map = loop.copy(cameraX = 24f, cameraY = 24f,
                    characters = listOf(NativeCharacter(1, "CharaChip/hero$.png", 8f, 16f, 0f, 0f)))))
            }
            val wrapped = state.render(48, 48)
            try { assertEquals(Color.MAGENTA, wrapped.getPixel(16, 20)) } finally { wrapped.recycle() }
        }
        atlas.recycle(); hero.recycle()
    }

    @Test fun visibilityMaskUsesZeroEqualityForEveryImageBlendWithoutTouchingTheBackground() {
        val texture = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60, 60, 60)) }
        val background = NativePicture(0, width = 16f, height = 16f, color = Color.rgb(100, 100, 100))
        val picture = NativePicture(1, path = "mask-source.png", maskEnabled = true)
        val bytes = ByteArray(16 * 16) { if (it % 16 < 8) 0 else when (it / 16 % 3) { 0 -> 1; 1 -> 128.toByte(); else -> -1 } }
        val scene = NativeFrame(16, 16, null, listOf(background, picture), emptyList(), null, 1,
            visibilityMask = NativeVisibilityMask(16, 16, bytes))
        fixture(null, mapOf("Data/mask-source.png" to png(texture)), scene).use { state ->
            val results = intArrayOf(60, 160, 40, 24)
            for (blend in 0..3) {
                instrumentation.runOnMainSync { state.view.submitFrame(scene.copy(pictures = listOf(background, picture.copy(blend = blend)))) }
                val image = state.render(16, 16)
                try {
                    assertRgbNear("visible blend$blend", IntArray(3) { results[blend] }, image.getPixel(3, 3))
                    for (y in 0 until 16) assertEquals("nonzero is binary, blend$blend row$y", Color.rgb(100, 100, 100), image.getPixel(12, y))
                } finally { image.recycle() }
            }
        }
        texture.recycle()
    }

    @Test fun visibilityMaskStaysOnTheScreenUnderMapZoomAndIndependentlyCoversCharacterShadow() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(180, 180, 180)) }
        val hero = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val shadow = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(80, 80, 80)) }
        val character = NativeCharacter(1, "CharaChip/hero$.png", 12f, 8f, 0f, 0f, shadowPath = "CharaChip/shadow$.png")
        val map = map(intArrayOf(0)).copy(characters = listOf(character), zoom = 2f, maskEnabled = true)
        val mask = NativeVisibilityMask(64, 32, ByteArray(64 * 32) { if (it % 64 < 32) 0 else -1 })
        val scene = NativeFrame(32, 16, map, emptyList(), emptyList(), null, 1, visibilityMask = mask)
        fixture(map, mapOf("Data/MapChip/base.png" to png(atlas), "Data/CharaChip/hero$.png" to png(hero),
            "Data/CharaChip/shadow$.png" to png(shadow)), scene).use { state ->
            val unmasked = state.render(32, 16)
            try {
                assertEquals(Color.rgb(180, 180, 180), unmasked.getPixel(8, 4))
                assertEquals(Color.BLACK, unmasked.getPixel(30, 4))
                assertEquals(Color.RED, unmasked.getPixel(24, 12))
                assertEquals(Color.rgb(80, 80, 80), unmasked.getPixel(18, 12))
            } finally { unmasked.recycle() }
            instrumentation.runOnMainSync { state.view.submitFrame(scene.copy(map = map.copy(characters = listOf(character.copy(maskEnabled = true))))) }
            val masked = state.render(32, 16)
            try {
                assertEquals(Color.BLACK, masked.getPixel(24, 12))
                assertEquals(Color.BLACK, masked.getPixel(18, 12))
                assertEquals(Color.rgb(180, 180, 180), masked.getPixel(8, 4))
            } finally { masked.recycle() }
        }
        atlas.recycle(); hero.recycle(); shadow.recycle()
    }

    @Test fun visibilityMaskClipsTextWindowsAndRetainsThePreviousTransitionCanvas() {
        val skin = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val mask = NativeVisibilityMask(32, 24, ByteArray(32 * 24) { if (it % 32 < 16) 0 else -1 })
        val pictures = listOf(NativePicture(0, text = "MMMM", fontSize = 20f, fontColor = Color.RED, maskEnabled = true),
            NativePicture(1, path = "window.png", kind = 3, width = 32f, height = 8f, y = 16f, maskEnabled = true))
        val old = NativeFrame(32, 24, null, pictures, emptyList(), null, 1, visibilityMask = mask)
        fixture(null, mapOf("Data/window.png" to png(skin)), old).use { state ->
            val image = state.render(32, 24)
            try {
                assertTrue((0 until 16).any { y -> (0 until 16).any { x -> Color.red(image.getPixel(x, y)) > 0 } })
                assertEquals(Color.BLUE, image.getPixel(4, 20))
                for (y in 0 until 24) for (x in 16 until 32) assertEquals(Color.BLACK, image.getPixel(x, y))
            } finally { image.recycle() }
            val nextMask = mask.copy(pixels = ByteArray(32 * 24) { if (it % 32 < 16) -1 else 0 })
            val next = old.copy(pictures = listOf(NativePicture(0, width = 32f, height = 24f, color = Color.GREEN, maskEnabled = true)),
                visibilityMask = nextMask, transition = NativeTransition(old, 0f))
            instrumentation.runOnMainSync { state.view.submitFrame(next) }
            state.awaitVisibility()
            val captured = state.render(32, 24)
            try { assertEquals(Color.BLUE, captured.getPixel(4, 20)); assertEquals(Color.BLACK, captured.getPixel(20, 20)) }
            finally { captured.recycle() }
            instrumentation.runOnMainSync { state.view.submitFrame(next.copy(transition = next.transition!!.copy(progress = 1f))) }
            val current = state.render(32, 24)
            try { assertEquals(Color.BLACK, current.getPixel(4, 20)); assertEquals(Color.GREEN, current.getPixel(20, 20)) }
            finally { current.recycle() }
        }
        skin.recycle()
    }

    @Test fun visibilityImageImportUsesBlueIgnoringRedGreenAndTransparentPngAlphaOffTheUiThread() {
        val sourcePixels = intArrayOf(Color.argb(0, 255, 127, 0), Color.argb(0, 0, 255, 17), Color.argb(255, 240, 50, 128), Color.argb(4, 1, 2, 255))
        val done = CountDownLatch(1)
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray {
                assertEquals("Data/visibility.png", path)
                assertTrue(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                return rgbaPng(4, 1, sourcePixels)
            }
            override fun exists(path: String) = path == "Data/visibility.png"
            override fun list(path: String) = emptyList<String>()
        }
        lateinit var resources: WolfNativeResources
        var result: Result<NativeVisibilityMask>? = null
        try {
            instrumentation.runOnMainSync {
                resources = WolfNativeResources(source, {}, { throw AssertionError(it) })
                resources.loadVisibilityImage("visibility.png") {
                    assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper()); result = it; done.countDown()
                }
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val image = result!!.getOrThrow()
            assertEquals(4, image.width); assertEquals(1, image.height)
            assertArrayEquals(byteArrayOf(0, 17, 128.toByte(), -1), image.pixels)
        } finally { instrumentation.runOnMainSync { resources.close() } }
    }

    @Test fun textPictureMeasurementWaitsForTheRealInlineTextureWithoutReadingOnTheUiThread() {
        val atlas = Bitmap.createBitmap(48, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = png(atlas); atlas.recycle()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray {
                assertEquals("Data/inline.png", path)
                assertTrue(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return bytes
            }
            override fun exists(path: String) = path == "Data/inline.png"
            override fun list(path: String) = emptyList<String>()
        }
        lateinit var resources: WolfNativeResources
        lateinit var view: WolfNativeView
        var resourcesCreated = false
        var viewCreated = false
        val picture = NativePicture(1, text = "\\img[inline.png]", fontSize = 16f)
        try {
            instrumentation.runOnMainSync {
                resources = WolfNativeResources(source, { changed.countDown() }, { throw AssertionError(it) })
                resourcesCreated = true
                view = WolfNativeView(context, resources, WolfNativeView.Callbacks({ _, _ -> }, { _, _, _ -> }, error = { throw AssertionError(it) }))
                viewCreated = true
                view.configure(WolfNativeView.Config(smooth = false, showFps = false))
                assertTrue(view.measurePicture(picture, 5) == null)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS)); release.countDown(); assertTrue(changed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val measured = view.measurePicture(picture, 5)!!
                assertEquals(48, measured.first)
                assertTrue(measured.second >= 16)
                view.layout(0, 0, 64, 48)
                view.submitFrame(NativeFrame(64, 48, null, listOf(picture), emptyList(), null, 1))
                val rendered = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(rendered))
                assertEquals(Color.RED, rendered.getPixel(47, 5))
                assertEquals(Color.BLACK, rendered.getPixel(48, 5))
                rendered.recycle()
            }
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { if (viewCreated) view.close(); if (resourcesCreated) resources.close() }
        }
    }
    @Test fun pictureConversionsAreCachedIndependentlyAndRestoreTheOriginalTexture() {
        val atlas = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        Canvas(atlas).apply {
            drawRect(0f, 0f, 4f, 4f, Paint().apply { color = Color.RED })
            drawRect(4f, 0f, 8f, 4f, Paint().apply { color = Color.BLUE })
        }
        val reads = AtomicInteger()
        val original = NativePicture(1, "atlas.png")
        val inverse = NativePicture(2, "atlas.png", x = 8f, imageFilters = listOf(NativeImageFilter(24)))
        val green = NativePicture(3, "atlas.png", x = 16f, imageFilters = listOf(NativeImageFilter(21, 120)))
        val frame = NativeFrame(24, 4, null, listOf(original, inverse, green), emptyList(), null, 1)
        fixture(null, mapOf("Data/atlas.png" to png(atlas)), frame, onRead = {
            assertFalse("conversion read the PNG on Android's main thread", android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            reads.incrementAndGet()
        }).use { state ->
            state.render(24, 4).also { image ->
                assertEquals(Color.RED, image.getPixel(1, 1)); assertEquals(Color.BLUE, image.getPixel(6, 1))
                assertEquals(Color.CYAN, image.getPixel(9, 1)); assertEquals(Color.YELLOW, image.getPixel(14, 1))
                assertEquals(Color.GREEN, image.getPixel(17, 1)); assertEquals(Color.RED, image.getPixel(22, 1))
                image.recycle()
            }
            repeat(5) { index ->
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(tick = index + 2L)) }
                state.render(24, 4).recycle()
            }
            instrumentation.runOnMainSync {
                state.view.submitFrame(frame.copy(pictures = listOf(original, inverse.copy(imageFilters = emptyList())), tick = 8))
            }
            state.render(24, 4).also { image ->
                assertEquals(Color.RED, image.getPixel(9, 1)); assertEquals(Color.BLUE, image.getPixel(14, 1)); image.recycle()
            }
            assertEquals("three pictures and repeated frames must share one original decode", 1, reads.get())
        }
        atlas.recycle()
    }

    @Test fun orderedPictureConversionsProduceDifferentPixelsFromTheSameAsset() {
        val atlas = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(64, 128, 192)) }
        val brighten = NativeImageFilter(21, 0, 0, 128)
        val inverse = NativeImageFilter(24)
        val frame = NativeFrame(8, 4, null, listOf(
            NativePicture(1, "ordered.png", imageFilters = listOf(brighten, inverse)),
            NativePicture(2, "ordered.png", x = 4f, imageFilters = listOf(inverse, brighten))), emptyList(), null, 1)
        fixture(null, mapOf("Data/ordered.png" to png(atlas)), frame).use { state ->
            state.render(8, 4).also { image ->
                assertRgbNear("brighten then inverse", intArrayOf(95, 63, 31), image.getPixel(1, 1))
                assertRgbNear("inverse then brighten", intArrayOf(223, 191, 159), image.getPixel(5, 1))
                image.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun blurConvertsTheWholeAtlasBeforeSelectingAPattern() {
        val atlas = Bitmap.createBitmap(16, 4, Bitmap.Config.ARGB_8888)
        Canvas(atlas).apply {
            drawRect(0f, 0f, 8f, 4f, Paint().apply { color = Color.RED })
            drawRect(8f, 0f, 16f, 4f, Paint().apply { color = Color.BLUE })
        }
        val frame = NativeFrame(16, 4, null, listOf(
            NativePicture(1, "patterns.png", divisionsX = 2, pattern = 1, imageFilters = listOf(NativeImageFilter(23, 1))),
            NativePicture(2, "patterns.png", x = 8f, divisionsX = 2, pattern = 2)), emptyList(), null, 1)
        fixture(null, mapOf("Data/patterns.png" to png(atlas)), frame).use { state ->
            state.render(16, 4).also { image ->
                val edge = image.getPixel(7, 1)
                assertTrue("the adjacent blue pattern must contribute before atlas slicing", Color.blue(edge) > 0 && Color.red(edge) < 255)
                assertEquals(255, Color.alpha(edge)); assertEquals(Color.BLUE, image.getPixel(9, 1)); image.recycle()
            }
        }
        atlas.recycle()
    }

    @Test fun windowConversionsPreserveTheirNinePatchGeometryAndVisibilityClip() {
        val skin = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val mask = NativeVisibilityMask(32, 12, ByteArray(32 * 12) { if (it % 32 in 8..15) 1 else 0 })
        val frame = NativeFrame(32, 12, null, listOf(
            NativePicture(1, "skin.png", kind = 3, width = 16f, height = 12f, maskEnabled = true, imageFilters = listOf(NativeImageFilter(24))),
            NativePicture(2, "skin.png", kind = 3, x = 16f, width = 16f, height = 12f)), emptyList(), null, 1, visibilityMask = mask)
        fixture(null, mapOf("Data/skin.png" to png(skin)), frame).use { state ->
            state.render(32, 12).also { image ->
                assertEquals(Color.CYAN, image.getPixel(1, 1)); assertEquals(Color.CYAN, image.getPixel(7, 10))
                assertEquals(Color.BLACK, image.getPixel(8, 1)); assertEquals(Color.BLACK, image.getPixel(15, 10))
                assertEquals(Color.RED, image.getPixel(17, 1)); assertEquals(Color.RED, image.getPixel(30, 10)); image.recycle()
            }
        }
        skin.recycle()
    }

    @Test fun invisiblePortraitMeasurementKeepsItsIntrinsicSizeBeforeTheFinalZoom() {
        val portrait = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val invisible = NativePicture(10202, "portrait.png", x = 24f, y = 24f, scaleX = 0f, scaleY = 0f, opacity = 0, anchor = 1)
        val frame = NativeFrame(64, 64, null, listOf(invisible), emptyList(), null, 1)
        fixture(null, mapOf("Data/portrait.png" to png(portrait)), frame).use { state ->
            instrumentation.runOnMainSync {
                assertEquals(24 to 24, state.view.measurePicture(invisible, 5))
                state.view.submitFrame(frame.copy(pictures = listOf(invisible.copy(scaleX = 2f, scaleY = 2f, opacity = 255))))
            }
            state.render(64, 64).also { image ->
                assertEquals(Color.GREEN, image.getPixel(0, 0)); assertEquals(Color.GREEN, image.getPixel(47, 47))
                assertEquals(Color.BLACK, image.getPixel(48, 24)); image.recycle()
            }
        }
        portrait.recycle()
    }

    @Test fun dataRelativeParentPathsStayInsideTheGameAndRemainStableAcrossCacheLookups() {
        for ((raw, root) in listOf(
            "../Save/foo.png" to "Save/foo.png",
            "..\\Save\\screens\\..\\foo.png" to "Save/foo.png",
            "Data/MapChip/../tile.png" to "Data/tile.png",
            "MapChip/./tile.png" to "Data/MapChip/tile.png")) {
            val key = WolfNativeResources.logicalPath(raw)
            assertEquals(root, WolfParser.rootAssetPath(key))
            assertEquals("cache normalization must be idempotent: $raw", key, WolfNativeResources.logicalPath(key))
        }
        for (bad in listOf("../../outside.png", "Data/../../outside.png", "/outside.png", "C:\\outside.png", "../Save/a\u0000.png",
            WolfParser.DATA_ROOT_PREFIX + "../outside.png")) {
            assertTrue("unconfined resource: $bad", runCatching { WolfNativeResources.logicalPath(bad) }.isFailure)
        }
    }

    @Test fun screenshotsOutsideDataRenderWithFiltersAndShareTheSameBoundedResource() {
        val saved = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val tile = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val reads = mutableListOf<String>()
        val frame = NativeFrame(12, 4, null, listOf(
            NativePicture(1, "../Save/screenshot.png", imageFilters = listOf(NativeImageFilter(24))),
            NativePicture(2, WolfParser.DATA_ROOT_PREFIX + "Save/screenshot.png", x = 4f),
            NativePicture(3, "MapChip/tile.png", x = 8f)), emptyList(), null, 1)
        fixture(null, mapOf("Save/screenshot.png" to png(saved), "Data/MapChip/tile.png" to png(tile)), frame,
            onRead = { synchronized(reads) { reads += it } }).use { state ->
            state.render(12, 4).also { image ->
                assertEquals(Color.MAGENTA, image.getPixel(1, 1))
                assertEquals(Color.GREEN, image.getPixel(5, 1))
                assertEquals(Color.BLUE, image.getPixel(9, 1)); image.recycle()
            }
            assertEquals(listOf("Data/MapChip/tile.png", "Save/screenshot.png"), synchronized(reads) { reads.sorted() })
        }
        saved.recycle(); tile.recycle()
    }

    @Test fun systemFontResolutionRejectsSilentAndroidFallbacksAndNeverReadsFromTheUiThread() {
        val calls = mutableListOf<String>()
        val source = object : WolfAssetSource {
            fun checkWork(operation: String) {
                assertFalse("font discovery on main: $operation", Looper.myLooper() == Looper.getMainLooper())
                synchronized(calls) { calls += operation }
            }
            override fun read(path: String): ByteArray { checkWork("read:$path"); error("no game fonts") }
            override fun exists(path: String): Boolean { checkWork("exists:$path"); return false }
            override fun list(path: String): List<String> { checkWork("list:$path"); return emptyList() }
        }
        val resources = fontResources(source)
        try {
            assertTrue(resolveFont(resources, "monospace"))
            assertTrue(resources.namedTypeface("monospace") != null)
            assertFalse(resolveFont(resources, "Wolf-Family-That-Does-Not-Exist-638072"))
            assertTrue(resources.namedTypeface("Wolf-Family-That-Does-Not-Exist-638072") == null)
            val missing = WolfNativeResources.fontOverrideReference("<<?>>Wolf-Family-That-Does-Not-Exist-638072")!!
            assertTrue("an unavailable optional override must preserve initial fonts without stalling", resources.areFontsReady(listOf(missing)))
            assertTrue(resources.fontUnavailableReason("Wolf-Family-That-Does-Not-Exist-638072")!!.isNotBlank())
            val count = synchronized(calls) { calls.size }
            assertFalse(resolveFont(resources, "Wolf-Family-That-Does-Not-Exist-638072"))
            assertEquals("resolved names must not repeat discovery every frame", count, synchronized(calls) { calls.size })
            assertTrue(resolveFont(resources, ""))
        } finally { resources.close() }
    }

    @Test fun namedGameFontUsesItsActualOpenTypeFamilyInsteadOfItsFileName() {
        val bytes = context.assets.open("windows/ipaexg.ttf").use { it.readBytes() }
        assertTrue(WolfNativeFontInfo.faces(bytes).single().names.any { it == "IPAexGothic" })
        val assets = mapOf("Data/Fonts/arbitrary-file-name.ttf" to bytes)
        val reads = AtomicInteger()
        val resources = fontResources(fontSource(assets) { reads.incrementAndGet() })
        try {
            assertTrue(resolveFont(resources, "IPAexGothic"))
            val loaded = resources.namedTypeface("IPAexGothic")!!
            assertTrue("family validation must precede Android parsing", loaded != Typeface.DEFAULT)
            val paint = Paint().apply { typeface = loaded; textSize = 24f }
            assertTrue(paint.hasGlyph("日")); assertTrue(paint.measureText("Astra日本語") > 0f)
            assertEquals(1, reads.get())
            assertTrue(resolveFont(resources, "ipaexgothic"))
            assertEquals(1, reads.get())
        } finally { resources.close() }
    }

    @Test fun anInitialConfiguredFamilyLoadsItsGameFontBeforeMeasurementAndRendering() {
        val font = context.assets.open("windows/ipaexg.ttf").use { it.readBytes() }
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val source = fontSource(mapOf("Data/Fonts/arbitrary.ttf" to font)) {
            assertFalse("initial font file read on UI thread", Looper.myLooper() == Looper.getMainLooper())
            started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
        }
        val resources = fontResources(source)
        lateinit var view: WolfNativeView
        val picture = NativePicture(1, text = "日本語ABCiii", fontSize = 24f)
        val frame = NativeFrame(240, 48, null, listOf(picture), emptyList(), null, 1)
        val initial = WolfNativeView.Config(smooth = false, showFps = false,
            fontPaths = mapOf(0 to WolfNativeResources.fontOverrideReference("IPAexGothic")!!))
        try {
            instrumentation.runOnMainSync {
                view = WolfNativeView(context, resources, WolfNativeView.Callbacks({ _, _ -> }, { _, _, _ -> }, error = { throw AssertionError(it) }))
                view.configure(initial); view.layout(0, 0, 240, 48); view.submitFrame(frame)
                assertTrue("measurement must wait for the configured family", view.measurePicture(picture, 5) == null)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS)); release.countDown()
            assertTrue(resolveFont(resources, "IPAexGothic"))
            instrumentation.runOnMainSync {
                val configuredSize = view.measurePicture(picture, 5)!!
                val configured = Bitmap.createBitmap(240, 48, Bitmap.Config.ARGB_8888)
                val reference = Bitmap.createBitmap(240, 48, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(configured))
                    view.configure(initial.copy(fontPaths = emptyMap(), typeface = resources.namedTypeface("IPAexGothic")!!))
                    assertEquals(configuredSize, view.measurePicture(picture, 5))
                    view.draw(Canvas(reference))
                    assertArrayEquals("initial families and explicit real typefaces must compose identically", pixels(reference), pixels(configured))
                } finally { configured.recycle(); reference.recycle() }
            }
        } finally { release.countDown(); instrumentation.runOnMainSync { view.close(); resources.close() } }
    }

    @Test fun openTypeNameTablesRejectOutOfBoundsRecordsAndKeepTtcFaceIndexes() {
        val real = context.assets.open("windows/ipaexg.ttf").use { it.readBytes() }
        val malformed = real.copyOf().apply {
            // The name-table offset is absolute, including in collections.
            for (record in 12 until 12 + ((this[4].toInt() and 255) * 256 + (this[5].toInt() and 255)) * 16 step 16) {
                if (String(this, record, 4, Charsets.US_ASCII) == "name") {
                    for (i in record + 8 until record + 12) this[i] = 0x7f
                }
            }
        }
        assertTrue(runCatching { WolfNativeFontInfo.faces(malformed) }.isFailure)
        assertTrue(runCatching { WolfNativeFontInfo.faces(byteArrayOf()) }.isFailure)
        val names = listOf("Native Test Family One", "Native Test Family Two")
        val ttc = tinyFontCollection(names)
        assertEquals(listOf(0, 1), WolfNativeFontInfo.faces(ttc).map { it.index })
        assertEquals(names, WolfNativeFontInfo.faces(ttc).map { it.names.single() })
    }

    @Test fun pictureAndDialogFontsRemainCapturedWhenTheCurrentFamilyChanges() {
        val picture = NativePicture(1, text = "iiiiMMMM", fontSize = 20f)
        val frame = NativeFrame(240, 100, null, listOf(picture), emptyList(),
            NativeDialog("iiiiMMMM", waiting = false, visibleCharacters = 8, fontSize = 16f), 1)
        fixture(null, emptyMap(), frame).use { state ->
            val old = state.render(240, 100)
            var initial: Pair<Int, Int>? = null
            instrumentation.runOnMainSync { initial = state.view.measurePicture(picture, 5) }
            assertTrue(resolveFont(state.resources, "monospace"))
            val switched = frame.copy(fontOverrides = mapOf(0 to "monospace"))
            instrumentation.runOnMainSync { state.view.submitFrame(switched) }
            val existing = state.render(240, 100)
            assertArrayEquals("new frame font must not rebuild existing picture/dialog fonts", pixels(old), pixels(existing))
            val replacement = picture.copy(fontOverrides = mapOf(0 to "monospace"))
            var measured: Pair<Int, Int>? = null
            instrumentation.runOnMainSync {
                assertEquals(initial, state.view.measurePicture(picture, 5))
                measured = state.view.measurePicture(replacement, 5)
                // The captured MeasurePicture arguments are authoritative even before submitFrame.
                assertEquals(measured, state.view.measurePicture(picture, 5, mapOf(0 to "monospace")))
                state.view.submitFrame(switched.copy(pictures = listOf(replacement),
                    dialog = frame.dialog!!.copy(fontOverrides = mapOf(0 to "monospace"))))
            }
            assertTrue("real family metrics should change", initial!!.first != measured!!.first)
            val changed = state.render(240, 100)
            assertFalse(pixels(old).contentEquals(pixels(changed)))
            old.recycle(); existing.recycle(); changed.recycle()
        }
    }

    @Test fun capturedSubFontAndKeyboardFamilyRemainSeparate() {
        val initial = NativePicture(1, text = "iiiiMMMM", fontSize = 24f)
        val sub = initial.copy(text = "\\font[1]iiiiMMMM", fontOverrides = mapOf(1 to "monospace", 5 to "serif"))
        val frame = NativeFrame(240, 50, null, listOf(initial), emptyList(), null, 1)
        fixture(null, emptyMap(), frame).use { state ->
            assertTrue(resolveFont(state.resources, "monospace"))
            var measured: Pair<Int, Int>? = null
            instrumentation.runOnMainSync {
                measured = state.view.measurePicture(sub, 5)
                assertEquals(state.view.measurePicture(initial.copy(fontOverrides = mapOf(0 to "monospace")), 5), measured)
                assertEquals(state.view.measurePicture(initial, 5), state.view.measurePicture(initial.copy(fontOverrides = mapOf(5 to "monospace")), 5))
                assertTrue("keyboard font must not become a text sub-font", runCatching {
                    state.view.measurePicture(sub.copy(text = "\\font[5]iiiiMMMM"), 5)
                }.isFailure)
            }
            assertTrue(measured != null)
        }
    }

    @Test fun thirdCorpusEncryptedFontsLoadAsRealAndroidTypefaces() {
        val root = InstrumentationRegistry.getArguments().getString("nativeThirdRoot")
        assumeNotNull(root)
        val directory = File(root!!, "crookedman")
        val hashes = linkedMapOf(
            "Aozora Mincho Regular" to "27c0eaab87e78b66afc85a8308bfe27d1c29a9a23d324dbc659d7093daacf694",
            "NanumMyeongjo" to "a21faae83d2d16d11c639f04f25fbe410eda905969ad75eff50d63ca31569f25",
            "Noto Naskh Arabic Regular" to "8cba766b19c98c66cba88c39cedb310a6713c493d08e510d35ae0064f22eac8c",
            "Source Han Serif TW ExtraLight" to "860c41ed033cc89f1f51878cfeca4eb1f38eb592a671a4b87363de069c33a348",
            "Spectral ExtraLight" to "ba6dd643c52c354c0cf7c14308924588414472d2c5487c671f80a8cacac7c5e9")
        val source = object : WolfAssetSource {
            override fun read(path: String) = File(directory, path).readBytes()
            override fun exists(path: String) = File(directory, path).isFile
            override fun list(path: String) = File(directory, path).list()?.toList().orEmpty()
        }
        val resources = fontResources(source)
        try {
            for ((family, expected) in hashes) {
                val bytes = File(directory, "$family.ttf.wolfx").readBytes()
                assertTrue(WolfXResource.isEncrypted(bytes))
                val payload = WolfXResource.decode(bytes)
                val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
                assertEquals("independent upstream TTF digest: $family", expected, digest)
                assertTrue("matching OpenType family required: $family", WolfNativeFontInfo.faces(payload).any { face -> face.names.any { it == family } })
                assertTrue("Android family unavailable: $family, ${resources.fontUnavailableReason(family)}", resolveFont(resources, family))
                assertTrue(resources.namedTypeface(family) != null)
            }
        } finally { resources.close() }
    }

    @Test fun systemWindowFilterMatchesOneTimesPixelsAndReusesItsLogicalBitmap() {
        val frame = filteredWindowFixture()
        fixture(null, emptyMap(), frame).use { state ->
            val original = state.render(64, 48)
            for (smooth in listOf(false, true)) {
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(windowSmooth = smooth)) }
                val filtered = state.render(64, 48)
                assertArrayEquals("Sys140 must keep logical 1x pixels for text and shapes", pixels(original), pixels(filtered))
                filtered.recycle()
            }
            repeat(60) { state.render(160, 120).recycle() }
            assertEquals("one allocation per logical resolution, not per frame", 1, state.view.viewportAllocations)
            original.recycle()
        }
    }

    @Test fun systemWindowFilterAppliesToShapesAndTextAtTwoAndAHalfTimesScale() {
        val frame = filteredWindowFixture().copy(windowSmooth = false)
        fixture(null, emptyMap(), frame).use { state ->
            val logical = state.render(64, 48)
            for (smooth in listOf(false, true)) {
                instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(windowSmooth = smooth)) }
                val image = state.render(160, 120)
                val reference = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
                Canvas(reference).apply {
                    scale(2.5f, 2.5f)
                    drawBitmap(logical, 0f, 0f, Paint().apply { isFilterBitmap = smooth })
                }
                assertArrayEquals("finished window filter parity (text + geometry): $smooth", pixels(reference), pixels(image))
                image.recycle(); reference.recycle()
            }
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(windowSmooth = true)) }
            val smooth = state.render(160, 120)
            instrumentation.runOnMainSync { state.view.submitFrame(frame.copy(windowSmooth = false)) }
            val nearest = state.render(160, 120)
            assertFalse("nearest/smooth must differ at a fractional physical scale", pixels(smooth).contentEquals(pixels(nearest)))
            logical.recycle(); smooth.recycle(); nearest.recycle()
        }
    }

    private fun filteredWindowFixture() = NativeFrame(64, 48, null, listOf(
        NativePicture(1, x = 3f, y = 2f, width = 12f, height = 7f, color = Color.GREEN),
        NativePicture(2, text = "Ab", x = 17f, y = 14f, fontSize = 18f),
        NativePicture(3, x = 36f, y = 3f, width = 13f, height = 9f, rotation = 22f, color = Color.RED)), emptyList(), null, 1)

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun textCharset(encoding: WolfEncoding): Charset =
        if (encoding == WolfEncoding.UTF8) Charsets.UTF_8 else Charset.forName("windows-31j")

    /** Direct Android text oracle, without Wolf parsing or resource discovery. */
    private fun plainTextReference(text: String, width: Int, height: Int, size: Float, color: Int = Color.WHITE): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            textSize = size; isAntiAlias = true; this.color = color
            typeface = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false)
                else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        Canvas(bitmap).apply { drawColor(Color.BLACK); drawText(text, 0f, maxOf(size, -paint.fontMetrics.ascent), paint) }
        return bitmap
    }

    private fun fontResources(source: WolfAssetSource): WolfNativeResources = WolfNativeResources(source, {},
        { throw AssertionError(it) }, fontCacheDirectory = File(context.cacheDir, "wolf-font-sdk"))

    private fun fontSource(assets: Map<String, ByteArray>, read: (String) -> Unit = {}) = object : WolfAssetSource {
        override fun read(path: String): ByteArray { read(path); return assets[path] ?: error("font fixture absent: $path") }
        override fun exists(path: String) = path in assets
        override fun list(path: String): List<String> {
            val prefix = if (path.isEmpty()) "" else "$path/"
            return assets.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        }
    }

    private fun resolveFont(resources: WolfNativeResources, name: String): Boolean {
        val completed = CountDownLatch(1); var available = false
        instrumentation.runOnMainSync { resources.resolveFontOverride(name) { result ->
            assertTrue("font result must return on main", Looper.myLooper() == Looper.getMainLooper())
            available = result; completed.countDown()
        } }
        assertTrue("font resolution timeout: $name", completed.await(10, TimeUnit.SECONDS))
        return available
    }

    /** Name-only TTC oracle: the second face's name offset is relative to the whole file. */
    private fun tinyFontCollection(names: List<String>): ByteArray {
        val count = names.size; val faceBytes = 28; val header = 12 + count * 4
        val records = names.map { it.toByteArray(Charsets.UTF_16BE) }
        val total = header + count * faceBytes + records.sumOf { 18 + it.size }
        val data = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.BIG_ENDIAN)
        data.putInt(0x74746366); data.putInt(0x10000); data.putInt(count)
        repeat(count) { data.putInt(header + it * faceBytes) }
        var at = header + count * faceBytes
        records.forEachIndexed { index, bytes ->
            data.position(header + index * faceBytes)
            data.putInt(0x10000); data.putShort(1); data.putShort(0); data.putShort(0); data.putShort(0)
            data.putInt(0x6e616d65); data.putInt(0); data.putInt(at); data.putInt(18 + bytes.size)
            data.position(at)
            data.putShort(0); data.putShort(1); data.putShort(18)
            data.putShort(3); data.putShort(1); data.putShort(0x409); data.putShort(1); data.putShort(bytes.size.toShort()); data.putShort(0)
            data.put(bytes); at += 18 + bytes.size
        }
        return data.array()
    }

    private fun assertRgbNear(label: String, expected: IntArray, color: Int) {
        val channels = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
        channels.forEachIndexed { i, channel -> assertTrue("$label: expected ${expected.toList()}, got ${channels.toList()}", kotlin.math.abs(channel - expected[i]) <= 1) }
    }

    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }

    /** Minimal valid RGBA PNG, independent of Android's premultiplied bitmap encoder. */
    private fun rgbaPng(width: Int, height: Int, pixels: IntArray): ByteArray {
        val scanlines = ByteArrayOutputStream().apply {
            for (y in 0 until height) {
                write(0)
                for (x in 0 until width) {
                    val color = pixels[y * width + x]
                    write(Color.red(color)); write(Color.green(color)); write(Color.blue(color)); write(Color.alpha(color))
                }
            }
        }.toByteArray()
        val compressed = ByteArrayOutputStream().apply { DeflaterOutputStream(this).use { it.write(scanlines) } }.toByteArray()
        val header = ByteArrayOutputStream().apply { DataOutputStream(this).use {
            it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 6, 0, 0, 0))
        } }.toByteArray()
        return ByteArrayOutputStream().apply {
            DataOutputStream(this).use { output ->
                output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
                for ((name, data) in listOf("IHDR" to header, "IDAT" to compressed, "IEND" to byteArrayOf())) {
                    val type = name.toByteArray(Charsets.US_ASCII)
                    val crc = CRC32().apply { update(type); update(data) }
                    output.writeInt(data.size); output.write(type); output.write(data); output.writeInt(crc.value.toInt())
                }
            }
        }.toByteArray()
    }

    private fun fixture(map: NativeMapScene?, assets: Map<String, ByteArray>,
        frame: NativeFrame = NativeFrame(16, 16, map, emptyList(), emptyList(), null, 1),
        key: (NativeKey, Boolean) -> Unit = { _, _ -> }, pointer: (Float, Float, Boolean) -> Unit = { _, _, _ -> }, choose: (Int) -> Unit = {}, firstFrame: () -> Unit = {}, onRead: (String) -> Unit = {}, notice: (String) -> Unit = {},
        config: WolfNativeView.Config = WolfNativeView.Config(smooth = false, showFps = false)): Fixture {
        val changed = CountDownLatch(if (assets.isEmpty()) 0 else assets.size)
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray { onRead(path); return assets[path] ?: error("fixture image absent: $path") }
            override fun exists(path: String) = path in assets
            override fun list(path: String) = emptyList<String>()
        }
        lateinit var state: Fixture
        instrumentation.runOnMainSync {
            val resources = WolfNativeResources(source, { changed.countDown() }, { throw AssertionError(it) })
            val view = WolfNativeView(context, resources, WolfNativeView.Callbacks(key, pointer, choose, firstFrame, error = { throw AssertionError(it) }, notice = notice))
            view.configure(config)
            view.layout(0, 0, frame.logicalWidth, frame.logicalHeight); view.submitFrame(frame)
            state = Fixture(view, resources)
        }
        assertTrue(changed.await(5, TimeUnit.SECONDS))
        state.awaitImages()
        state.awaitVisibility()
        state.awaitVariants()
        return state
    }
    private inner class Fixture(val view: WolfNativeView, val resources: WolfNativeResources) : AutoCloseable {
        fun awaitImages() {
            val deadline = SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = view.areImageResourcesReady() }
                if (!ready) SystemClock.sleep(10)
            }
            assertTrue("required image resources did not prepare", ready)
        }
        fun awaitVariants() {
            val deadline = SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = view.areImageVariantsReady() }
                if (!ready) SystemClock.sleep(10)
            }
            assertTrue("picture conversion texture did not prepare", ready)
        }
        fun awaitVisibility() {
            val deadline = SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = view.areVisibilityMasksReady() }
                if (!ready) SystemClock.sleep(10)
            }
            assertTrue("visibility canvas clip did not prepare", ready)
        }
        fun render(width: Int, height: Int): Bitmap {
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { view.layout(0, 0, width, height); view.draw(Canvas(image)) }
            return image
        }
        override fun close() { instrumentation.runOnMainSync { view.close(); resources.close() } }
    }
}
