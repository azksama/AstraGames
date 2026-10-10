package fr.astragames.app.wolfnative

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.wolf.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A binary-file → VM → Android Canvas → touch → native-save journey, using authored data. */
class WolfNativeGameInstrumentedTest {
    @Test fun binaryGameRunsChoicesMovementDialogAndSaveRestoreOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = "native-journey-${UUID.randomUUID()}"
        val root = File(context.cacheDir, id).apply { mkdirs() }
        NativeGameFixture.write(root)
        val original = File(root, "Save/original.sav").readBytes()
        val source = AndroidWolfAssetSource(context, Uri.fromFile(root))
        val vm = WolfRuntime.load(source)
        val saves = WolfNativeSaveStore(context, id, source.metadataFingerprint)
        val input = ConcurrentLinkedQueue<(WolfRuntime) -> Unit>()
        val firstFrame = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        lateinit var textures: WolfNativeResources
        lateinit var view: WolfNativeView
        instrumentation.runOnMainSync {
            textures = WolfNativeResources(source, {}, errors::offer)
            view = WolfNativeView(context, textures, WolfNativeView.Callbacks(
                key = { key, down -> input.offer { it.key(key, down) } },
                pointer = { x, y, down -> input.offer { it.pointer(x, y, down) } },
                choose = { row -> input.offer { it.choose(row) } }, firstFrame = { firstFrame.countDown() }, error = { errors.offer(it) },
            ))
            view.configure(WolfNativeView.Config(showFps = false, smooth = false)); view.layout(0, 0, 96, 96)
        }
        fun draw(): Bitmap {
            val image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { view.submitFrame(vm.frame()); view.draw(Canvas(image)) }
            return image
        }
        fun drain() { while (true) (input.poll() ?: break)(vm) }
        fun tap(x: Float, y: Float) {
            instrumentation.runOnMainSync {
                val time = SystemClock.uptimeMillis()
                for ((action, elapsed) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 50L)) {
                    MotionEvent.obtain(time, time + elapsed, action, x, y, 0).let { event -> view.onTouchEvent(event); event.recycle() }
                }
            }
            drain()
        }
        fun hero() = vm.frame().map!!.characters.single { it.id == -2 }
        try {
            vm.tick(); assertEquals(listOf("Nouvelle partie", "Quitter"), vm.frame().dialog!!.choices)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (firstFrame.count > 0 && errors.isEmpty() && SystemClock.elapsedRealtime() < deadline) { draw().recycle(); SystemClock.sleep(30) }
            assertTrue("No first image: ${errors.joinToString()}", firstFrame.await(1, TimeUnit.SECONDS))
            val title = draw(); assertEquals(Color.rgb(96, 32, 160), title.getPixel(4, 4)); title.recycle()
            tap(65f, 45f); vm.tick()
            assertEquals(1, vm.mapId); assertEquals("Bienvenue !", vm.frame().dialog!!.text)
            // The pointer uses inverse viewport coordinates and the game's Sys73 message option.
            draw().recycle(); tap(20f, 80f); vm.tick(); assertNull(vm.frame().dialog)
            val savedPosition = hero().x to hero().y
            saves.write(0, vm.snapshot())
            draw().recycle(); tap(56f, 24f)
            repeat(40) { if (vm.frame().dialog == null) vm.tick() }
            assertTrue(hero().x > savedPosition.first)
            assertEquals("Dialogue tactile", vm.frame().dialog!!.text)
            vm.restore(saves.read(0))
            assertNull(vm.frame().dialog); assertEquals(1, vm.mapId)
            assertEquals(savedPosition.first, hero().x, .001f); assertEquals(savedPosition.second, hero().y, .001f)
            assertArrayEquals(original, File(root, "Save/original.sav").readBytes())
            assertTrue("Metadata and a few textures must not become a full import", source.filesRead < 40)
            assertTrue(errors.joinToString(), errors.isEmpty())
        } finally {
            instrumentation.runOnMainSync { view.close(); textures.close() }; source.close()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
            check(saves.directory.canonicalFile.parentFile!!.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            saves.directory.parentFile!!.deleteRecursively()
        }
    }
}
