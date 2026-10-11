package fr.astragames.app.windows

import android.opengl.GLES20
import android.opengl.EGL14
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.renderer.GLRenderer
import com.winlator.widget.XServerView
import com.winlator.xserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WolfRendererOcclusionTest {
    private fun await(condition: () -> Boolean) {
        for (i in 0 until 60) { if (condition()) return; Thread.sleep(50) }
        assertTrue("Renderer state did not settle", condition())
    }
    private fun onGl(view: XServerView, block: () -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        // GLSurfaceView can drain queued events before creating its first EGL surface.
        val deadline = android.os.SystemClock.elapsedRealtime() + 40_000
        val action = object : Runnable {
            override fun run() {
                if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT ||
                    EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == EGL14.EGL_NO_SURFACE ||
                    view.renderer.viewTransformation.viewWidth <= 0) {
                    if (android.os.SystemClock.elapsedRealtime() < deadline) {
                        view.requestRender(); view.postDelayed({ view.queueEvent(this) }, 20)
                    } else { failure = AssertionError("No active EGL surface"); latch.countDown() }
                    return
                }
                try { block() } catch (error: Throwable) { failure = error } finally { latch.countDown() }
            }
        }
        view.queueEvent(action)
        assertTrue("GL thread stalled", latch.await(45, TimeUnit.SECONDS))
        failure?.let { throw it }
    }
    private fun pixel(view: XServerView): Int {
        var color = 0
        onGl(view) {
            view.renderer.onDrawFrame(null); GLES20.glFinish()
            val bytes = ByteBuffer.allocateDirect(4)
            GLES20.glReadPixels(view.width / 2, view.height / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            color = ((bytes.get(0).toInt() and 255) shl 16) or ((bytes.get(1).toInt() and 255) shl 8) or (bytes.get(2).toInt() and 255)
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        }
        return color
    }

    @Test fun hiddenWindowsKeepPendingPixelsAndReappearWithoutLosingMenusOrTransparency() {
        ActivityScenario.launch(fr.astragames.app.MainActivity::class.java).use { scenario ->
            lateinit var server: XServer; lateinit var view: XServerView
            lateinit var desktop: Window; lateinit var game: Window; lateinit var menu: Window
            scenario.onActivity { activity ->
                server = XServer(activity, ScreenInfo("800x600")); view = XServerView(activity, server)
                server.renderer = view.renderer; view.renderer.setCursorVisible(false)
                activity.setContentView(view)
                server.lockAll().use {
                    val root = server.windowManager.rootWindow
                    // Wine's desktop is a child window; the X root itself is never drawn.
                    desktop = server.windowManager.createWindow(0x700, root, 0, 0, 800, 600,
                        WindowAttributes.WindowClass.INPUT_OUTPUT, null, 0, null)
                    desktop.content.fillColor(0xA02030); server.windowManager.mapWindow(desktop)
                    game = server.windowManager.createWindow(0x701, root, 0, 0, 800, 600,
                        WindowAttributes.WindowClass.INPUT_OUTPUT, null, 0, null)
                    game.content.fillColor(0x2040B0)
                    menu = server.windowManager.createWindow(0x702, root, 380, 280, 40, 40,
                        WindowAttributes.WindowClass.INPUT_OUTPUT, null, 0, null)
                    menu.content.fillColor(0x20A040)
                    server.windowManager.mapWindow(game)
                }
            }
            await { view.renderer.culledWindowCount == 1 && view.renderer.visibleWindowCount == 1 }
            assertEquals(0x2040B0, pixel(view))
            scenario.onActivity { server.lockAll().use { desktop.content.fillColor(0xD05020) } }
            onGl(view) { assertTrue("Hidden damage must not be consumed", desktop.content.texture.isNeedsUpdate) }
            scenario.onActivity { server.lockAll().use { server.windowManager.mapWindow(menu) } }
            await { view.renderer.visibleWindowCount == 2 }
            assertEquals(0x20A040, pixel(view))
            scenario.onActivity { server.lockAll().use { server.windowManager.unmapWindow(menu); server.windowManager.unmapWindow(game) } }
            await { view.renderer.culledWindowCount == 0 && view.renderer.visibleWindowCount == 1 }
            assertEquals(0xD05020, pixel(view))
            scenario.onActivity { server.lockAll().use {
                game.attributes.setTransparent(true)
                // Use a transparent full-sized window; it cannot hide the desktop.
                requireNotNull(game.content.data).apply { rewind(); while (remaining() > 0) putInt(0x80F00000.toInt()); rewind() }
                game.content.forceUpdate(); server.windowManager.mapWindow(game)
            } }
            await { view.renderer.visibleWindowCount == 2 && view.renderer.culledWindowCount == 0 }
            val blend = pixel(view)
            assertNotEquals(0xD05020, blend)
            assertNotEquals(0xF00000, blend)
            assertTrue((blend shr 16 and 255) > 200)
            assertTrue((blend shr 8 and 255) in 30..50)
        }
    }

    @Test fun benchmarkFullFramesRetainIdenticalPixelsWhileSkippingTheHiddenDesktop() {
        ActivityScenario.launch(fr.astragames.app.MainActivity::class.java).use { scenario ->
            lateinit var server: XServer; lateinit var view: XServerView; lateinit var desktop: Window; lateinit var game: Window
            scenario.onActivity { activity ->
                server = XServer(activity, ScreenInfo("1280x960")); view = XServerView(activity, server)
                server.renderer = view.renderer; view.renderer.setCursorVisible(false); view.renderer.setSmoothScaling(true)
                activity.setContentView(view)
                server.lockAll().use {
                    desktop = server.windowManager.createWindow(0x700, server.windowManager.rootWindow, 0, 0, 1280, 960,
                        WindowAttributes.WindowClass.INPUT_OUTPUT, null, 0, null)
                    desktop.content.fillColor(0xA02030); server.windowManager.mapWindow(desktop)
                    game = server.windowManager.createWindow(0x703, server.windowManager.rootWindow, 0, 0, 1280, 960,
                        WindowAttributes.WindowClass.INPUT_OUTPUT, null, 0, null)
                    game.content.fillColor(0x102050); server.windowManager.mapWindow(game)
                }
            }
            await { view.renderer.culledWindowCount == 1 }
            val proof = StringBuilder("Android SDK emulator x86_64; GLES/JNI; 1280x960 software frames; cubic scaling; identical image definition\n")
            for (desktopChanges in listOf(false, true)) {
            val measurements = mutableMapOf(false to mutableListOf<Long>(), true to mutableListOf())
            onGl(view) {
                @Suppress("UNCHECKED_CAST")
                val windows = GLRenderer::class.java.getDeclaredField("renderableWindows").apply { isAccessible = true }.get(view.renderer) as List<Any>
                val hidden = windows.first()
                val occluded = hidden.javaClass.getDeclaredField("occluded").apply { isAccessible = true }
                val root = desktop.content
                // ABBA controls warmup/order effects. Driver completion is included; no claim about game FPS.
                for (optimized in listOf(false, true, true, false)) {
                    occluded.setBoolean(hidden, optimized)
                    val bytes = root.texture.uploadedBytes
                    repeat(100) { i ->
                        server.lockAll().use {
                            if (desktopChanges) root.fillColor(i * 777)
                            game.content.fillColor(0x102050 + i * 13)
                        }
                        val start = System.nanoTime()
                        view.renderer.onDrawFrame(null); GLES20.glFinish()
                        if (i >= 20) measurements.getValue(optimized) += System.nanoTime() - start
                    }
                    val transferred = root.texture.uploadedBytes - bytes
                    if (optimized) assertEquals(0, transferred)
                    else if (desktopChanges) assertTrue(transferred >= 1280L * 960 * 4 * 99)
                    proof.append("desktopChanges=$desktopChanges optimized=$optimized hiddenWindowUploadBytes=$transferred\n")
                    assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                }
                occluded.setBoolean(hidden, true)
            }
            for ((optimized, samples) in measurements) {
                samples.sort()
                proof.append("desktopChanges=$desktopChanges optimized=$optimized frames=${samples.size} renderP50Ms=${samples[samples.size / 2] / 1e6} renderP95Ms=${samples[samples.size * 95 / 100] / 1e6}\n")
            }
            }
            val colors = mutableListOf<Int>()
            onGl(view) {
                @Suppress("UNCHECKED_CAST")
                val windows = GLRenderer::class.java.getDeclaredField("renderableWindows").apply { isAccessible = true }.get(view.renderer) as List<Any>
                val field = windows.first().javaClass.getDeclaredField("occluded").apply { isAccessible = true }
                field.setBoolean(windows.first(), false)
            }
            colors += pixel(view)
            onGl(view) {
                @Suppress("UNCHECKED_CAST")
                val windows = GLRenderer::class.java.getDeclaredField("renderableWindows").apply { isAccessible = true }.get(view.renderer) as List<Any>
                windows.first().javaClass.getDeclaredField("occluded").apply { isAccessible = true }.setBoolean(windows.first(), true)
            }
            colors += pixel(view)
            assertEquals(colors.first(), colors.last())
            proof.append("pixelsIdentical=true\nSubmission plus glFinish excludes game execution and display swap; does not establish Samsung/Box64 FPS.\n")
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "wolf-occlusion-benchmark.txt").writeText(proof.toString())
        }
    }
}
