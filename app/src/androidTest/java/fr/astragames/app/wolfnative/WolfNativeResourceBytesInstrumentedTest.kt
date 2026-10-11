package fr.astragames.app.wolfnative

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.wolf.WolfAssetSource
import fr.astragames.wolf.NativeEffect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/** Authored fixtures, validated by pinned decryptFull; no claim about every Pro key. */
@RunWith(AndroidJUnit4::class)
class WolfNativeResourceBytesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String) = instrumentation.context.assets.open("wolf-native/wolfx/$name").use { it.readBytes() }

    @Test fun concurrentAliasAndExplicitRequestsShareOneDecodeWithinABoundedCache() {
        val plain = fixture("quadrants.png")
        val wrapped = fixture("quadrants.png.wolfx")
        val reads = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = object : WolfAssetSource {
            override fun exists(path: String): Boolean {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                return path in setOf("Data/a.png", "Data/a.png.wolfx", "Data/b.png.wolfx")
            }
            override fun read(path: String): ByteArray {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                assertTrue("the clear file must never supersede its protected alias", path.endsWith(".wolfx"))
                if (reads.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
                return wrapped
            }
            override fun list(path: String) = emptyList<String>()
        }
        val pool = Executors.newFixedThreadPool(8)
        WolfNativeResourceBytes(source, budgetBytes = plain.size.toLong(), maxEntries = 2).use { reader ->
            try {
                val jobs = (0 until 8).map { index -> pool.submit<ByteArray> { reader.read(if (index % 2 == 0) "Data/a.png" else "Data/a.png.wolfx") } }
                assertTrue(entered.await(5, TimeUnit.SECONDS)); release.countDown()
                jobs.forEach { assertArrayEquals(plain, it.get(5, TimeUnit.SECONDS)) }
                assertEquals(1, reads.get()); assertEquals(plain.size.toLong(), reader.residentBytes)
                assertArrayEquals(plain, reader.read("Data/b.png"))
                assertEquals("two payloads must not exceed the one-payload byte budget", 1, reader.residentEntries)
                assertTrue(reader.residentBytes <= plain.size)
                assertArrayEquals(plain, reader.read("Data/a.png"))
                assertEquals("an evicted payload is re-read rather than retaining unbounded bytes", 3, reads.get())
                assertThrows(IllegalArgumentException::class.java) { reader.read("Data/a.png", maxBytes = plain.size - 1) }
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun unavailableKeysAndAProtectedHeaderErrorNeverFallbackToTheClearFile() {
        for (name in listOf("quadrants.png.string-key.wolfx", "quadrants.png.number-key.wolfx", "quadrants.png.folder-key.wolfx", "quadrants.png")) {
            val reads = mutableListOf<String>()
            val source = object : WolfAssetSource {
                override fun read(path: String): ByteArray { reads += path; return fixture(if (path.endsWith(".wolfx")) name else "quadrants.png") }
                override fun exists(path: String) = path == "Data/protected.png" || path == "Data/protected.png.wolfx"
                override fun list(path: String) = emptyList<String>()
            }
            WolfNativeResourceBytes(source).use { reader ->
                val error = assertThrows("$name must remain an explicit failure", IllegalArgumentException::class.java) { reader.read("Data/protected.png") }
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("WOLFX"))
                assertEquals(listOf("Data/protected.png.wolfx"), reads)
                assertEquals(0L, reader.residentBytes)
            }
        }
    }

    @Test fun closingAReadUnblocksConsumersAndTheUiGuardRunsBeforeSourceIo() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val reads = AtomicInteger()
        val source = object : WolfAssetSource {
            override fun exists(path: String): Boolean { assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); return path.endsWith(".wolfx") }
            override fun read(path: String): ByteArray { reads.incrementAndGet(); entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return fixture("quadrants.png.wolfx") }
            override fun list(path: String) = emptyList<String>()
        }
        val reader = WolfNativeResourceBytes(source)
        val pool = Executors.newFixedThreadPool(2)
        try {
            instrumentation.runOnMainSync {
                assertThrows(IllegalStateException::class.java) { reader.read("Data/slow.png") }
            }
            assertEquals(0, reads.get())
            val first = pool.submit<ByteArray> { reader.read("Data/slow.png") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<ByteArray> { reader.read("Data/slow.png.wolfx") }
            reader.close(); release.countDown()
            for (job in listOf(first, second)) {
                val error = assertThrows(ExecutionException::class.java) { job.get(5, TimeUnit.SECONDS) }
                assertTrue(error.cause?.message.orEmpty(), error.cause?.message.orEmpty().contains("fermée"))
            }
            assertEquals(1, reads.get()); assertEquals(0L, reader.residentBytes)
        } finally { release.countDown(); reader.close(); pool.shutdownNow() }
    }

    @Test fun soundDurationProbesTheDecodedPayloadForImplicitAndExplicitProtectedNames() {
        val wrapped = fixture("sine.wav.wolfx")
        val reads = mutableListOf<String>()
        val source = object : WolfAssetSource {
            override fun read(path: String): ByteArray {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                reads += path; assertEquals("Data/sine.wav.wolfx", path); return wrapped
            }
            override fun exists(path: String) = path == "Data/sine.wav" || path == "Data/sine.wav.wolfx"
            override fun list(path: String) = emptyList<String>()
        }
        WolfNativeFiles(instrumentation.targetContext, "wolfx-duration-${UUID.randomUUID()}", source).use { files ->
            for ((index, path) in listOf("Data/sine.wav", "Data/sine.wav.wolfx").withIndex()) {
                assertEquals("real Android metadata from our 48 kHz/3 s PCM", "3000",
                    files.perform(NativeEffect.FileOperation(index.toLong(), 9, path)))
            }
            assertEquals(listOf("Data/sine.wav.wolfx", "Data/sine.wav.wolfx"), reads)
        }
    }
}
