package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class WolfDownloadTest {
    private fun request(flags: Int = 0, variable: Int = 0, extended: Int = 0,
        url: String = "https://example.invalid/game.dat", path: String = "download/",
        post: String = "msg=日本語&value=a b+;", profile: WolfDownloadProfile = WolfDownloadProfile.W3,
        commonId: Int = -1, encodingMode: Int = -1) = WolfDownloadRequest.parse(
            intArrayOf(0, flags, variable, extended), listOf(url, path, post), profile, commonId, encodingMode,
        )

    @Test fun nativeCOracleCoversEveryByteAnd128EncodingFlagCombinations() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/wolf-download-native.tsv"))
            .bufferedReader().use { it.readLines() }.filter { it.isNotEmpty() && !it.startsWith('#') }
        assertEquals(384, lines.size)
        for (line in lines) {
            val fields = line.split('\t')
            when (fields[0]) {
                "PERCENT" -> assertArrayEquals(line, hex(fields[3]), WolfDownloadRequest.formEncode(hex(fields[2])))
                "REQUEST" -> {
                    val mode = fields[1].toInt(); val flags = fields[2].toInt()
                    val result = request(extended = flags, url = "https://example.invalid/run?name=日本語", encodingMode = mode)
                    assertEquals(line, fields[3], result.urlEncoding.name)
                    assertEquals(line, fields[4], result.responseEncoding.name)
                    assertEquals(line, fields[5], result.postEncoding?.name ?: "NONE")
                    assertArrayEquals(line, hex(fields[6]), result.urlBytes)
                    if (fields[7] == "NONE") assertNull(result.postBytes)
                    else assertArrayEquals(line, hex(fields[7]), result.postBytes)
                }
                else -> fail("Unknown oracle row $line")
            }
        }
    }

    @Test fun fileAndStringTargetsAreIndependentAndWaitingIsExplicit() {
        val file = request()
        assertEquals("GET", file.method); assertFalse(file.waitForCompletion)
        assertNull(file.variable); assertEquals("download/game.dat", file.filePath())
        val both = request(flags = 2, variable = 3_000_025, path = "received.txt")
        assertTrue(both.waitForCompletion); assertEquals(3_000_025, both.variable)
        assertEquals("received.txt", both.filePath())
        val text = request(flags = 3, variable = 3_000_000, path = "")
        assertTrue(text.waitForCompletion); assertEquals(3_000_000, text.variable)
        assertNull(text.filePath()); assertNull(text.destination)
        reject { request(flags = 1, variable = 0) }
    }

    @Test fun commonSelfDestinationIsPinnedToItsOriginBeforeAsyncCompletion() {
        assertEquals(15_001_902, request(variable = 1_600_002, commonId = 19).variable)
        assertEquals(15_999_999, request(variable = 1_699_999, commonId = 9999).variable)
        assertEquals(15_002_302, request(variable = 15_002_302).variable)
        reject { request(variable = 1_600_002) }
        reject { request(variable = 1_600_002, commonId = 10_000) }
        reject { request(variable = Int.MAX_VALUE) }
        reject { request(variable = 123) }
    }

    @Test fun w2AdmitsItsOrdinaryContractAndExplicitlyRejectsExtensionsAndSpecialAliases() {
        val w2 = request(flags = 3, variable = 3_000_008, profile = WolfDownloadProfile.W2)
        assertEquals(WolfEncoding.CP932, w2.urlEncoding)
        assertEquals(WolfEncoding.CP932, w2.responseEncoding)
        rejectUnsupported { request(extended = 0x100, profile = WolfDownloadProfile.W2) }
        for (profile in WolfDownloadProfile.entries) for (alias in listOf(-1, -2, -4, -5, Int.MIN_VALUE))
            rejectUnsupported { request(variable = alias, profile = profile) }
    }

    @Test fun queryEncodingUsesNativeFormRulesAndPreservesTheUrlPrefix() {
        assertEquals("https://example.invalid/a?name=%E6%97%A5%E6%9C%AC%E8%AA%9E&v=a+b%2B%3B%23x",
            request(extended = 6, url = "https://example.invalid/a?name=日本語&v=a b+;#x").url)
        assertEquals("https://example.invalid/a?name=%93%FA%96%7B%8C%EA",
            request(extended = 4, url = "https://example.invalid/a?name=日本語").url)
        assertEquals("https://example.invalid/a", request(extended = 4, url = "https://example.invalid/a").url)
        assertEquals("Az09*.-_&=+%2B%25%2F%3F%7E", String(WolfDownloadRequest.formEncode("Az09*.-_&= +%/?~".toByteArray())))
    }

    @Test fun contentMarkerIsParsedAfterEncodingAndDropsItsPrefix() {
        val raw = "discard<<CONTENT>>application/json<<CONTENT_END>>{\"name\":\"日本語\"}"
        val json = request(extended = 0x30, post = raw)
        assertEquals("POST", json.method); assertEquals("application/json", json.contentType)
        assertArrayEquals("{\"name\":\"日本語\"}".toByteArray(), json.postBytes)
        val encoded = request(extended = 0x70, post = raw)
        assertEquals("application/x-www-form-urlencoded", encoded.contentType)
        assertTrue(String(requireNotNull(encoded.postBytes)).startsWith("discard%3C%3CCONTENT%3E%3E"))
        val unfinished = request(extended = 0x10, post = "<<CONTENT>>unfinished")
        assertEquals("application/x-www-form-urlencoded", unfinished.contentType)
        assertArrayEquals("<<CONTENT>>unfinished".toByteArray(), unfinished.postBytes)
        reject { request(extended = 0x10, post = "<<CONTENT>>text/plain\r\nX:1<<CONTENT_END>>body") }
        reject { request(extended = 0x10, post = "<<CONTENT>><<CONTENT_END>>body") }
    }

    @Test fun decodingPreservesEncodingAndNativeBomAndNulTermination() {
        val legacy = "日本語".toByteArray(Charset.forName("windows-31j"))
        assertEquals("日本語", request(flags = 1, variable = 3_000_000).decodeResponse(legacy))
        val utf8 = request(flags = 1, variable = 3_000_000, extended = 0x100)
        assertEquals("日本語", utf8.decodeResponse("日本語".toByteArray()))
        assertEquals("a", utf8.decodeResponse(byteArrayOf(-17, -69, -65, 97)))
        assertEquals("", utf8.decodeResponse(byteArrayOf(-17, -69, -65)))
        assertEquals("\uFEFFa", utf8.decodeResponse(byteArrayOf(-17, -69, -65, -17, -69, -65, 97)))
        assertEquals("ok", utf8.decodeResponse(byteArrayOf(111, 107, 0, -1)))
        assertEquals("", utf8.decodeResponse(byteArrayOf()))
        reject { utf8.decodeResponse(byteArrayOf(-61)) }
        reject { WolfDownloadRequest.decode(byteArrayOf(-127), WolfEncoding.CP932) }
    }

    @Test fun validationRejectsAllNativeForbiddenDestinationsBeforeNetworkIo() {
        for (path in listOf("//a", "a\\\\b", "../a", "a/./b", "a.\\b", "a%", "a:", "a*", "a?",
            "a\"", "a<", "a>", "a|", "/a", "\\a", ".", "a/../b", "a\u0000", "a\n"))
            reject { request(path = path) }
        assertEquals("dir/sub/game.dat", request(path = "dir\\sub\\").filePath())
        assertEquals("index.htm", request(path = "", url = "https://example.invalid/").filePath())
        assertEquals("dir/index.htm", request(path = "dir/", url = "https://example.invalid/").filePath())
        assertEquals("dir/a%2Fb.dat", request(path = "dir/", url = "https://example.invalid/a%2Fb.dat").filePath())
        reject { request(path = "dir/", url = "https://example.invalid/../x/..") }
        reject { request(flags = 1, variable = 3_000_000, path = "../a") }
    }

    @Test fun tlsMarkerAndHttpScopeAreExplicitAndPayloadBuffersDoNotLeakMutation() {
        val result = request(extended = 0x10, url = "<<VERIFY_TLS>>https://example.invalid/a", post = "x=1")
        assertTrue(result.verifyTls); assertEquals("https://example.invalid/a", result.url)
        result.urlBytes.fill(0); result.postBytes!!.fill(0)
        assertEquals("https://example.invalid/a", String(result.urlBytes))
        assertEquals("x=1", String(result.postBytes!!))
        rejectUnsupported { request(url = "<<VERIFY_TLS>>https://example.invalid/a", profile = WolfDownloadProfile.W2) }
        rejectUnsupported { request(url = "file:///etc/file") }
        for (url in listOf("https:///missing", "https://u:p@example.invalid/a", "https://example.invalid/a#x", "https://example.invalid/a\r\n"))
            reject { request(url = url) }
        reject { request(url = "https://example.invalid/\u0000x") }
        reject { request(flags = 4) }; reject { request(extended = 8) }
    }

    @Test fun malformedCharsetsAndCapacityFailuresDoNotProduceSuccessfulResponses() {
        reject { request(url = "https://example.invalid/?x=😀") } // CP932 substitution is not silently accepted.
        assertEquals(WolfEncoding.UTF8, request(extended = 2, url = "https://example.invalid/?x=😀").urlEncoding)
        reject { request(path = "a".repeat(WolfDownloadRequest.MAX_PATH + 1)) }
        reject { WolfDownloadRequest.decode(ByteArray(WolfDownloadRequest.MAX_TEXT_BYTES + 1), WolfEncoding.UTF8) }
        reject { WolfDownloadRequest.decode(ByteArray(WolfDownloadRequest.MAX_TEXT_CHARACTERS + 1) { 97 }, WolfEncoding.UTF8) }
        reject { WolfDownloadRequest.parse(intArrayOf(0, 0, 0, 0x10), listOf("https://example.invalid/a", "out"), WolfDownloadProfile.W3) }
        reject { WolfDownloadRequest.parse(intArrayOf(0, 0), listOf("https://example.invalid/a", "out"), WolfDownloadProfile.W3) }
        reject { request(extended = 0x30, post = "\uD800") }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun reject(block: () -> Unit) {
        try { block(); fail("Unexpected successful260 operation") } catch (_: IllegalArgumentException) { }
    }
    private fun rejectUnsupported(block: () -> Unit) {
        try { block(); fail("Expected explicit unsupported260 operation") } catch (_: WolfDownloadUnsupported) { }
    }
}
