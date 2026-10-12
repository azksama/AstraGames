package fr.astragames.app.translation

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: official editor data stays outside the source tree and test APK. */
class WolfOfficialCorpusTest {
    @Test fun officialWolfTwoAndThreeMessagesRoundTripWithStableIdsAndOperands() {
        val corpus = System.getenv("ASTRA_WOLF_CORPUS")
        assumeTrue("External official corpus required", !corpus.isNullOrBlank())
        val root = File(requireNotNull(corpus))
        val proof = JSONArray()
        for ((sample, editor) in listOf("sample22961" to "WOLF_RPG_Editor2", "sample3729" to "WOLF_RPG_Editor3")) {
            val data = File(root, "$sample/$editor/Data")
            val files = listOf(File(data, "BasicData/CommonEvent.dat")) + File(data, "MapData").listFiles().orEmpty().filter { it.extension == "mps" }
            assertEquals(5, files.size)
            for (file in files) {
                val path = file.relativeTo(data).invariantSeparatorsPath
                val original = file.readBytes()
                val parsed = GameTextDocuments.read("WOLF_RPG", path, original)
                assertArrayEquals(original, parsed.translatedEntries(emptyMap()))
                val first = parsed.entries.firstOrNull()
                if (first != null) {
                    val changed = parsed.translatedEntries(mapOf(first.path to "${first.text} ASTRA"))
                    val verified = GameTextDocuments.read("WOLF_RPG", path, changed).entries
                    assertEquals(parsed.entries.map { it.path }, verified.map { it.path })
                    assertEquals("${first.text} ASTRA", verified.first { it.path == first.path }.text)
                    assertEquals(parsed.entries.filter { it.path != first.path }, verified.filter { it.path != first.path })
                }
                proof.put(JSONObject().put("version", sample).put("file", path).put("bytes", original.size)
                    .put("entries", parsed.entries.size).put("patched", first != null))
            }
        }
        File("build/verification/wolf-official-text-corpus.json").apply { parentFile!!.mkdirs(); writeText(proof.toString(2)) }
    }
}
