package fr.astragames.app.wolfnative

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.wolf.NativeCharacter
import fr.astragames.wolf.NativeDialog
import fr.astragames.wolf.NativeFrame
import fr.astragames.wolf.NativeKey
import fr.astragames.wolf.NativeMapScene
import fr.astragames.wolf.NativePicture
import fr.astragames.wolf.WolfAssetSource
import fr.astragames.wolf.WolfEncoding
import fr.astragames.wolf.WolfMap
import fr.astragames.wolf.WolfTileset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android Canvas/BitmapFactory/Input proof, independent of Wine and synthetic VM semantics. */
@RunWith(AndroidJUnit4::class)
class WolfNativeRenderingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

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
        val fixture = fixture(map(intArrayOf(100_123)), assets = mapOf("Data/MapChip/auto.png" to png(atlas)))
        fixture.use { state ->
            val image = state.render(16, 16)
            assertEquals(Color.RED, image.getPixel(2, 2))
            assertEquals(Color.GREEN, image.getPixel(13, 2))
            assertEquals(Color.BLUE, image.getPixel(2, 13))
            assertEquals(Color.YELLOW, image.getPixel(13, 13))
        }
    }

    @Test fun fileLayersAreDrawnBottomToTopAndBaseTilesStartAt32() {
        val atlas = Bitmap.createBitmap(128, 16, Bitmap.Config.ARGB_8888)
        Canvas(atlas).let { canvas -> val paint = Paint(); intArrayOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { i, color ->
            paint.color = color; canvas.drawRect(i * 16f, 0f, (i + 1) * 16f, 16f, paint)
        } }
        val scene = map(intArrayOf(32, 33, 34), layers = 3)
        fixture(scene, assets = mapOf("Data/MapChip/base.png" to png(atlas))).use { state ->
            assertEquals(Color.RED, state.render(16, 16).getPixel(8, 8))
        }
    }

    @Test fun eightDirectionSpriteUsesFourRowsAndTheSecondColumnGroup() {
        val atlas = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        Canvas(atlas).let { canvas -> val paint = Paint(); for (row in 0..3) for (column in 0..5) {
            paint.color = Color.rgb(20 + column * 30, 20 + row * 50, 40)
            canvas.drawRect(column * 16f, row * 16f, (column + 1) * 16f, (row + 1) * 16f, paint)
        } }
        val character = NativeCharacter(1, "CharaChip/hero.png", 8f, 16f, 0f, 0f, direction = 9, pattern = 1)
        val scene = map(intArrayOf(0)).copy(characters = listOf(character), characterDirections = 8)
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
            override fun exists(path: String) = true
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
        val tileset = WolfTileset(0, "fixture", "MapChip/base.png", listOf("MapChip/auto.png"), byteArrayOf(), IntArray(10))
        return NativeMapScene(map, tileset, emptyList(), 0f, 0f, 16)
    }
    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }

    private fun fixture(map: NativeMapScene?, assets: Map<String, ByteArray>,
        frame: NativeFrame = NativeFrame(16, 16, map, emptyList(), emptyList(), null, 1),
        key: (NativeKey, Boolean) -> Unit = { _, _ -> }, pointer: (Float, Float, Boolean) -> Unit = { _, _, _ -> }, choose: (Int) -> Unit = {}): Fixture {
        val changed = CountDownLatch(if (assets.isEmpty()) 0 else assets.size)
        val source = object : WolfAssetSource {
            override fun read(path: String) = assets[path] ?: error("fixture image absent: $path")
            override fun exists(path: String) = path in assets
            override fun list(path: String) = emptyList<String>()
        }
        lateinit var state: Fixture
        instrumentation.runOnMainSync {
            val resources = WolfNativeResources(source, { changed.countDown() }, { throw AssertionError(it) })
            val view = WolfNativeView(context, resources, WolfNativeView.Callbacks(key, pointer, choose, error = { throw AssertionError(it) }))
            view.configure(WolfNativeView.Config(smooth = false, showFps = false))
            view.layout(0, 0, frame.logicalWidth, frame.logicalHeight); view.submitFrame(frame)
            state = Fixture(view, resources)
        }
        assertTrue(changed.await(5, TimeUnit.SECONDS))
        return state
    }
    private inner class Fixture(val view: WolfNativeView, val resources: WolfNativeResources) : AutoCloseable {
        fun render(width: Int, height: Int): Bitmap {
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { view.layout(0, 0, width, height); view.draw(Canvas(image)) }
            return image
        }
        override fun close() { instrumentation.runOnMainSync { view.close(); resources.close() } }
    }
}
