package fr.astragames.app.windows

import android.opengl.EGL14
import android.opengl.GLES20
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.xserver.Drawable
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** Real GLES/JNI readback: catch missing pixels and stale regions as well as transfer cost. */
class WolfTextureTransferTest {
    private fun withGl(block: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
        val config = requireNotNull(configs[0])
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        try { block() } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
        }
    }

    private fun verify(drawable: Drawable, expected: ByteBuffer = requireNotNull(drawable.data), update: Boolean = true) {
        val texture = drawable.texture
        if (update) texture.updateFromDrawable()
        val framebuffer = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffer, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture.textureId, 0)
        assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE, GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER))
        val pixels = ByteBuffer.allocateDirect(drawable.width * drawable.height * 4)
        GLES20.glReadPixels(0, 0, drawable.width.toInt(), drawable.height.toInt(), GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        val data = expected
        for (i in 0 until pixels.capacity() step 4) {
            assertEquals("red at $i", data.get(i + 2), pixels.get(i))
            assertEquals("green at $i", data.get(i + 1), pixels.get(i + 1))
            assertEquals("blue at $i", data.get(i), pixels.get(i + 2))
            assertEquals("alpha at $i", data.get(i + 3), pixels.get(i + 3))
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, framebuffer, 0)
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
    }

    @Test fun capturedPixelsStayConsistentWhileWinePreparesTheNextFrame() = withGl {
        val drawable = Drawable(9010, 63, 47, null)
        fun snapshot(): ByteBuffer = ByteBuffer.allocateDirect(63 * 47 * 4).apply {
            put(requireNotNull(drawable.data).duplicate().apply { rewind() }); rewind()
        }
        val texture = drawable.texture
        try {
            drawable.fillColor(0x102030)
            var expected = snapshot()
            texture.captureUpdate()
            drawable.fillRect(7, 11, 8, 6, 0xE01020)
            assertTrue(texture.isNeedsUpdate)
            texture.uploadCapturedUpdate()
            verify(drawable, expected, update = false)
            assertTrue("A newer update must survive the old frame upload", texture.isNeedsUpdate)
            val bytes = texture.uploadedBytes
            expected = snapshot()
            texture.captureUpdate()
            drawable.fillRect(41, 35, 6, 5, 0x1234AB)
            texture.uploadCapturedUpdate()
            verify(drawable, expected, update = false)
            assertEquals(8L * 6 * 4, texture.uploadedBytes - bytes)
            texture.captureUpdate(); texture.uploadCapturedUpdate()
            verify(drawable, update = false)
            val unchanged = texture.uploadedBytes
            texture.captureUpdate(); texture.uploadCapturedUpdate()
            assertEquals(unchanged, texture.uploadedBytes)
            drawable.fillRect(2, 3, 2, 3, 0x456789)
            drawable.fillRect(60, 44, 30, 30, 0xFFAA22)
            texture.captureUpdate(); texture.uploadCapturedUpdate()
            verify(drawable, update = false)
            texture.destroy()
            texture.captureUpdate(); texture.uploadCapturedUpdate()
            verify(drawable, update = false)
        } finally { texture.destroy() }
    }

    @Test fun measureSynchronizedCopyCostAgainstDriverWaits() = withGl {
        val drawable = Drawable(9011, 1280, 960, null)
        try {
            drawable.fillColor(0); drawable.texture.updateFromDrawable(); GLES20.glFinish()
            val output = StringBuilder("Android Emulator x86_64; 200 frames per case; critical section excludes lock acquisition\n")
            for ((width, height) in listOf(256 to 32, 1280 to 960)) {
                for (capture in listOf(false, true)) {
                    val locked = mutableListOf<Long>(); val totals = mutableListOf<Long>()
                    val bytes = drawable.texture.uploadedBytes
                    repeat(200) { i ->
                        drawable.fillRect(0, 0, width, height, (i + 1) * 837)
                        val start = System.nanoTime()
                        if (capture) {
                            drawable.texture.captureUpdate()
                            locked += System.nanoTime() - start
                            drawable.texture.uploadCapturedUpdate()
                        } else {
                            drawable.texture.updateFromDrawable()
                            locked += System.nanoTime() - start
                        }
                        GLES20.glFinish()
                        totals += System.nanoTime() - start
                    }
                    locked.sort(); totals.sort()
                    assertEquals(width.toLong() * height * 4 * 200, drawable.texture.uploadedBytes - bytes)
                    output.append("${width}x$height capture=$capture: lock p50Ms=${locked[100] / 1e6}, p95Ms=${locked[190] / 1e6}; total p50Ms=${totals[100] / 1e6}, p95Ms=${totals[190] / 1e6}\n")
                    verify(drawable, update = false)
                }
            }
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "wolf-lock-benchmark.txt").writeText(output.toString())
        } finally { drawable.texture.destroy() }
    }

    @Test fun updatesPreserveEveryPixelAcrossClippingCoalescingAndCopies() = withGl {
        val drawable = Drawable(9001, 63, 47, null)
        val source = Drawable(9002, 19, 17, null)
        try {
            drawable.fillColor(0x102030); verify(drawable)
            drawable.fillRect(7, 11, 8, 6, 0xE01020); verify(drawable)
            drawable.fillRect(2, 3, 2, 3, 0x456789)
            drawable.fillRect(41, 35, 6, 5, 0x1234AB); verify(drawable)
            drawable.fillRect(60, 44, 30, 30, 0xFFAA22); verify(drawable)
            source.fillColor(0x229944)
            drawable.copyArea(1, 2, 12, 23, 11, 7, source); verify(drawable)
            drawable.drawImage(3, 4, 25, 6, 9, 5, 32, requireNotNull(source.data), source.width, source.height); verify(drawable)
            drawable.drawLine(0, 0, 40, 30, 0x00FFFF, 2); verify(drawable)
            drawable.fillColor(0xAA1177); verify(drawable)
            drawable.texture.destroy(); verify(drawable)
        } finally { drawable.texture.destroy(); source.texture.destroy() }
    }

    @Test fun measureTextAndImageTransfers() = withGl {
        val drawable = Drawable(9003, 1280, 960, null)
        try {
            drawable.fillColor(0); drawable.texture.updateFromDrawable(); GLES20.glFinish()
            val output = StringBuilder("Real GLES/JNI on Android Emulator x86_64; 200 updates per case\n")
            for ((label, rectWidth, rectHeight) in listOf(Triple("text-full-reference", 256, 32), Triple("text-partial", 256, 32), Triple("image", 1280, 960))) {
                val times = mutableListOf<Long>()
                val byteStart = drawable.texture.uploadedBytes
                val begin = System.nanoTime()
                repeat(200) { i ->
                    val tick = System.nanoTime()
                    drawable.fillRect(0, 0, rectWidth, rectHeight, i * 837)
                    if (label == "text-full-reference") drawable.forceUpdate()
                    drawable.texture.updateFromDrawable(); GLES20.glFinish()
                    times += System.nanoTime() - tick
                }
                times.sort()
                val bytes = drawable.texture.uploadedBytes - byteStart
                output.append("$label: totalMs=${(System.nanoTime() - begin) / 1e6}, p50Ms=${times[100] / 1e6}, p95Ms=${times[190] / 1e6}, uploadBytes=$bytes\n")
                assertEquals(if (label == "text-partial") 256L * 32 * 4 * 200 else 1280L * 960 * 4 * 200, bytes)
            }
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "wolf-transfer-benchmark.txt").writeText(output.toString())
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        } finally { drawable.texture.destroy() }
    }

    @Test fun completeFramesSkipIdenticalPixelsAndKeepCorrectColors() = withGl {
        val drawable = Drawable(9004, 63, 47, null)
        drawable.setOnDrawListener {}
        val source = Drawable(9005, 63, 47, null)
        try {
            drawable.fillColor(0x111111); source.fillColor(0x111111); verify(drawable)
            val bytes = drawable.texture.uploadedBytes
            source.fillRect(7, 13, 19, 6, 0xABCDEF)
            drawable.copyArea(0, 0, 0, 0, 63, 47, source); verify(drawable)
            assertEquals(19L * 6 * 4, drawable.texture.uploadedBytes - bytes)
            val unchanged = drawable.texture.uploadedBytes
            drawable.copyArea(0, 0, 0, 0, 63, 47, source); verify(drawable)
            assertEquals(unchanged, drawable.texture.uploadedBytes)
            source.fillRect(62, 46, 1, 1, 0x778899)
            drawable.drawImage(0, 0, 0, 0, 63, 47, 32, requireNotNull(source.data), 63, 47); verify(drawable)
            assertEquals(4L, drawable.texture.uploadedBytes - unchanged)
            val random = java.util.Random(417)
            repeat(35) {
                source.fillRect(random.nextInt(63), random.nextInt(47), 1 + random.nextInt(20), 1 + random.nextInt(12), random.nextInt(0xffffff))
                drawable.copyArea(0, 0, 0, 0, 63, 47, source); verify(drawable)
                assertEquals(source.data, drawable.data)
            }
        } finally { drawable.texture.destroy(); source.texture.destroy() }
    }

    @Test fun measureCompleteSoftwareFrames() = withGl {
        val drawable = Drawable(9006, 1280, 960, null)
        val source = Drawable(9007, 1280, 960, null)
        try {
            val output = StringBuilder("Full software frame copy plus GLES completion; Android Emulator x86_64; 200 frames per case\n")
            for (label in listOf("full-copy-reference", "text-diff", "unchanged", "image-diff")) {
                drawable.fillColor(0x151515); source.fillColor(0x151515)
                drawable.texture.updateFromDrawable(); GLES20.glFinish()
                // Reference uses the original memcpy plus full texture upload; diff applies to a displayed window.
                drawable.setOnDrawListener(if (label == "full-copy-reference") null else Runnable {})
                val byteStart = drawable.texture.uploadedBytes
                val times = mutableListOf<Long>()
                repeat(200) { i ->
                    if (label != "unchanged") source.fillRect(0, 0, if (label == "image-diff") 1280 else 256,
                        if (label == "image-diff") 960 else 32, (i + 1) * 837)
                    val tick = System.nanoTime()
                    drawable.drawImage(0, 0, 0, 0, 1280, 960, 32, requireNotNull(source.data), 1280, 960)
                    drawable.texture.updateFromDrawable(); GLES20.glFinish()
                    times += System.nanoTime() - tick
                }
                times.sort()
                val bytes = drawable.texture.uploadedBytes - byteStart
                output.append("$label: p50Ms=${times[100] / 1e6}, p95Ms=${times[190] / 1e6}, uploadBytes=$bytes\n")
                assertEquals(when (label) { "unchanged" -> 0L; "text-diff" -> 256L * 32 * 4 * 200
                    else -> 1280L * 960 * 4 * 200 }, bytes)
                assertEquals(source.data, drawable.data)
            }
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "wolf-software-benchmark.txt").writeText(output.toString())
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        } finally { drawable.texture.destroy(); source.texture.destroy() }
    }
}
