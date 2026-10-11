package fr.astragames.app.wolfnative

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.wolf.NativeEffect
import fr.astragames.wolf.WolfDownloadProfile
import fr.astragames.wolf.WolfDownloadRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WolfNativeDownloadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class HttpFixture(private val status: Int = 200, private val response: ByteArray = byteArrayOf(),
        private val hold: CountDownLatch? = null) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/result.txt"
        @Volatile var requestLine = ""
        @Volatile var headers = emptyMap<String, String>()
        @Volatile var body = byteArrayOf()
        @Volatile var failure: Throwable? = null
        val accepted = CountDownLatch(1)
        val finished = CountDownLatch(1)
        private val thread = Thread {
            try { server.accept().use { socket ->
                socket.soTimeout = 10_000
                val input = socket.getInputStream()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read(); if (byte < 0 || byte == 10) break
                        if (byte != 13) bytes.write(byte)
                        check(bytes.size() < 8192)
                    }
                    return bytes.toString("ISO-8859-1")
                }
                requestLine = line()
                headers = buildMap { while (true) {
                    val value = line(); if (value.isEmpty()) break
                    put(value.substringBefore(':').lowercase(), value.substringAfter(':').trim())
                } }
                val length = headers["content-length"]?.toInt() ?: 0
                check(length in 0..1_048_576)
                body = ByteArray(length)
                var read = 0
                while (read < length) { val count = input.read(body, read, length - read); check(count > 0); read += count }
                accepted.countDown(); hold?.await(10, TimeUnit.SECONDS)
                socket.getOutputStream().use { output ->
                    output.write("HTTP/1.1 $status Test\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(response); output.flush()
                }
            } } catch (error: Exception) { failure = error; accepted.countDown() }
            finally { finished.countDown() }
        }.apply { name = "wolf-test-http"; start() }
        override fun close() { hold?.countDown(); server.close(); thread.join(2000) }
    }
    private fun storage(block: (WolfNativeFiles, WolfNativeDownloads, File) -> Unit) {
        val root = File(context.cacheDir, "native-network-${UUID.randomUUID()}").apply { mkdirs() }
        File(root, "Save").mkdirs(); File(root, "Save/result.txt").writeText("original")
        val source = AndroidWolfAssetSource(context, Uri.fromFile(root))
        val files = WolfNativeFiles(context, "download-${UUID.randomUUID()}", source)
        val network = WolfNativeDownloads(files)
        try { block(files, network, root) } finally {
            network.close(); files.close(); source.close()
            val privateRoot = files.directory.parentFile!!
            check(privateRoot.canonicalFile.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            privateRoot.deleteRecursively()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }
    private fun receive(network: WolfNativeDownloads, request: WolfDownloadRequest): Result<String> {
        val done = CountDownLatch(1)
        var result: Result<String>? = null
        var owner: Thread? = null
        network.execute(NativeEffect.Download(1, request)) { value -> result = value; owner = Thread.currentThread(); done.countDown() }
        assertTrue("Actual HTTP reception timed out", done.await(15, TimeUnit.SECONDS))
        assertNotEquals(android.os.Looper.getMainLooper().thread, owner)
        return requireNotNull(result)
    }

    @Test fun actualHttpRetainsCp932UrlBytesAndCommitsFileAndStringOnItsWorker() = storage { files, network, originalRoot ->
        val cp932 = Charset.forName("windows-31j")
        val payload = "日本語\r\nresponse".toByteArray(cp932)
        HttpFixture(response = payload).use { server ->
            val request = WolfDownloadRequest.parse(intArrayOf(0, 2, 3_000_000),
                listOf(server.url + "?text=日本語", "Save/result.txt"), WolfDownloadProfile.W2)
            assertEquals("日本語\r\nresponse", receive(network, request).getOrThrow())
            assertTrue(server.finished.await(2, TimeUnit.SECONDS)); assertNull(server.failure)
            val raw = "日本語".toByteArray(cp932).joinToString("") { byte -> "%" + "%02X".format(byte.toInt() and 255) }
            assertEquals("GET /result.txt?text=$raw HTTP/1.1", server.requestLine)
            assertArrayEquals(payload, files.read("save/RESULT.TXT"))
            assertEquals("original", File(originalRoot, "Save/result.txt").readText())
        }
    }

    @Test fun actualPostAndHttpFailureLeaveThePriorGameFileIntact() = storage { files, network, _ ->
        files.write("Save/result.txt", "prior".toByteArray())
        HttpFixture(response = "成功".toByteArray()).use { server ->
            val request = WolfDownloadRequest.parse(intArrayOf(0, 3, 3_000_000, 0x132),
                listOf(server.url, "Save/result.txt", "<<CONTENT>>text/plain<<CONTENT_END>>日本語"), WolfDownloadProfile.W3)
            assertEquals("成功", receive(network, request).getOrThrow())
            assertEquals("POST /result.txt HTTP/1.1", server.requestLine)
            assertEquals("text/plain", server.headers["content-type"])
            assertArrayEquals("日本語".toByteArray(), server.body)
            assertEquals("prior", files.read("Save/result.txt").toString(Charsets.UTF_8)) // string-only mode
        }
        HttpFixture(404, "error body".toByteArray()).use { server ->
            val request = WolfDownloadRequest.parse(intArrayOf(0, 2, 3_000_000),
                listOf(server.url, "Save/result.txt"), WolfDownloadProfile.W2)
            assertTrue(receive(network, request).exceptionOrNull()?.message?.contains("HTTP 404") == true)
            assertEquals("prior", files.read("Save/result.txt").toString(Charsets.UTF_8))
        }
    }

    @Test fun closingAnActiveReceptionPreventsItsLateWriteAndCallback() = storage { files, network, _ ->
        val release = CountDownLatch(1)
        val callback = CountDownLatch(1)
        HttpFixture(response = "late".toByteArray(), hold = release).use { server ->
            val request = WolfDownloadRequest.parse(intArrayOf(0, 2, 0), listOf(server.url, "Save/result.txt"), WolfDownloadProfile.W2)
            network.execute(NativeEffect.Download(3, request)) { callback.countDown() }
            assertTrue(server.accepted.await(5, TimeUnit.SECONDS))
            network.close(); release.countDown()
            assertTrue(server.finished.await(3, TimeUnit.SECONDS))
            assertFalse(callback.await(300, TimeUnit.MILLISECONDS))
            assertEquals("original", files.read("Save/result.txt").toString(Charsets.UTF_8))
        }
    }

    @Test fun cancellationAbandonsTheOldReceptionAndAcceptsANewOne() = storage { files, network, _ ->
        val release = CountDownLatch(1)
        val callback = CountDownLatch(1)
        HttpFixture(response = "late".toByteArray(), hold = release).use { first ->
            val request = WolfDownloadRequest.parse(intArrayOf(0, 2, 0), listOf(first.url, "Save/result.txt"), WolfDownloadProfile.W2)
            network.execute(NativeEffect.Download(7, request)) { callback.countDown() }
            assertTrue(first.accepted.await(5, TimeUnit.SECONDS))
            assertFalse(network.cancel(8)) // A stale world cannot cancel a newer request.
            assertTrue(network.cancel(7)); assertFalse(network.cancel(7))
            release.countDown()
            assertTrue(first.finished.await(3, TimeUnit.SECONDS))
            assertFalse(callback.await(300, TimeUnit.MILLISECONDS))
            assertEquals("original", files.read("Save/result.txt").toString(Charsets.UTF_8))
            HttpFixture(response = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "new".toByteArray()).use { second ->
                val next = WolfDownloadRequest.parse(intArrayOf(0, 2, 3_000_000, 0x100),
                    listOf(second.url, "Save/result.txt"), WolfDownloadProfile.W3)
                assertEquals("new", receive(network, next).getOrThrow())
                assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "new".toByteArray(), files.read("Save/result.txt"))
            }
        }
    }

    @Test fun httpsAlwaysRejectsAnUntrustedCertificateEvenWithoutTheNativeTlsMarker() = storage { files, network, _ ->
        val password = "wolf-fixture".toCharArray()
        val store = java.security.KeyStore.getInstance("PKCS12")
        InstrumentationRegistry.getInstrumentation().context.assets.open("wolf-network-selfsigned.p12").use { store.load(it, password) }
        val keys = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val server = tls.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as javax.net.ssl.SSLServerSocket
        val finished = CountDownLatch(1)
        val thread = Thread {
            try { (server.accept() as javax.net.ssl.SSLSocket).use { socket -> socket.soTimeout = 10_000; socket.startHandshake() } }
            catch (_: java.io.IOException) {} finally { finished.countDown() }
        }.apply { name = "wolf-test-tls"; start() }
        try {
            val request = WolfDownloadRequest.parse(intArrayOf(0, 2, 0),
                listOf("https://127.0.0.1:${server.localPort}/result.txt", "Save/result.txt"), WolfDownloadProfile.W3)
            assertFalse(request.verifyTls) // Original flag off must still use Android's certificate checks.
            val error = receive(network, request).exceptionOrNull()
            assertTrue("Untrusted HTTPS was accepted: $error", error is javax.net.ssl.SSLException)
            assertEquals("original", files.read("Save/result.txt").toString(Charsets.UTF_8))
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        } finally { server.close(); thread.join(2000) }
    }
}
