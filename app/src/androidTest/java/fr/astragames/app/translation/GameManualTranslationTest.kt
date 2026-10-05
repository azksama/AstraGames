package fr.astragames.app.translation

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.documentDir
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android ContentResolver/DocumentFile I/O through the debug DocumentsProvider, without ML Kit. */
@RunWith(AndroidJUnit4::class)
class GameManualTranslationTest {
    private lateinit var context: Context
    private lateinit var privateRoot: File
    private lateinit var fixtureId: String
    private lateinit var documents: DocumentFile
    private lateinit var data: DocumentFile
    private lateinit var save: DocumentFile
    private lateinit var savedBytes: ByteArray
    private lateinit var game: GameEntity
    private lateinit var manager: GameTranslationManager
    private val sourceFiles = linkedMapOf<String, DocumentFile>()
    private val originals = linkedMapOf(
        "Actors.json" to """[null, {"id":1,"name":"Hero","nickname":"Guide","profile":"A hero"}, {"id":2,"name":"Companion"}]""".toByteArray(),
        "System.json" to """{"gameTitle":"Example","currencyUnit":"Gold","terms":{"commands":["New Game",null,""],"messages":{"victory":"%1 wins!"}},"variables":["Technical variable"],"title1Name":"TitleImage"}""".toByteArray(),
        "Map001.json" to ("""{
          "displayName":"Village","note":"<Technical:Hello>",
          "events":[null,{"name":"Technical event","pages":[{"list":[
            {"code":101,"indent":0,"parameters":["FaceImage",0,0,2,"Narrator"]},
            {"code":401,"indent":0,"parameters":["Hello \\C[\\V[1]]traveler\\C[0]!\fNext page \\Custom_Name[nested[2]]"]},
            {"code":102,"indent":0,"parameters":[["Yes","No"],-2,0,2,0]},
            {"code":402,"indent":0,"parameters":[0,"Yes"]},
            {"code":320,"indent":0,"parameters":[1,"Hero"]},
            {"code":111,"indent":0,"parameters":[4,1,1,"Hero"]},
            {"code":355,"indent":0,"parameters":["showText('Hello')"]},
            {"code":357,"indent":0,"parameters":["Plugin","Command","Label",{"text":"Hello"}]},
            {"code":0,"indent":0,"parameters":[]}
          ]}]}]
        }""" + "\r\n").toByteArray()
    )
    private val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")

    @Before fun setup() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        privateRoot = File(application.cacheDir, "manual-translation-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context = object : ContextWrapper(application) {
            override fun getNoBackupFilesDir() = File(privateRoot, "private").apply { mkdirs() }
        }
        fixtureId = UUID.randomUUID().toString()
        val tree = Uri.parse(requireNotNull(context.contentResolver.call(provider, "fixture", fixtureId, null)?.getString("uri")))
        documents = requireNotNull(documentDir(context, tree))
        val gameDirectory = requireNotNull(documents.findFile("game"))
        data = requireNotNull(requireNotNull(gameDirectory.createDirectory("www")).createDirectory("data"))
        originals.forEach { (name, bytes) ->
            sourceFiles[name] = requireNotNull(data.createFile("application/json", name)).also { write(it, bytes) }
        }
        save = requireNotNull(gameDirectory.findFile("save")?.findFile("file1.rpgsave"))
        // An existing opaque player save is never edited by translation; its actor name stays original.
        savedBytes = """{"party":{"gold":120},"actors":[null,{"name":"Hero"}]}""".toByteArray()
        write(save, savedBytes)
        game = GameEntity(
            id = "manual-$fixtureId", title = "Manual translation fixture", documentUri = gameDirectory.uri.toString(),
            physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_MZ", launcher = "JOIPLAY",
            sourceId = "test", dateAdded = 1, lastModified = 1, fingerprint = "fixture"
        )
        manager = GameTranslationManager(context) { _, _ -> error("Manual exchange must not create a translation model") }
    }

    @After fun cleanup() {
        if (::fixtureId.isInitialized && ::context.isInitialized) context.contentResolver.call(provider, "removeFixture", fixtureId, null)
        if (::privateRoot.isInitialized) privateRoot.deleteRecursively()
    }

    @Test fun manualSafRoundTripPreservesCommandsLogicalNamesAndRestoresExactBytes() = runBlocking<Unit> {
        withTimeout(30_000) {
            val exchange = newExchange()
            val exported = manager.exportManual(game, "en", "fr", exchange.uri)
            assertEquals(3, exported.files)
            assertFalse(exported.installed)
            assertFalse(manager.hasBackup(game))
            assertOriginals()

            val bundle = readJson(exchange)
            assertEquals("astra-rpgm-translation", bundle.getString("format"))
            assertEquals(1, bundle.getInt("version"))
            assertEquals(3, bundle.getJSONArray("files").length())
            val entries = rows(bundle)
            val protectedNames = entries.filter { it.optBoolean("preserveOriginal") }
            assertEquals(3, protectedNames.size) // Actors.name, change-name command and name condition.
            assertEquals(1, protectedNames.map { it.getString("identityGroup") }.distinct().size)
            assertTrue(protectedNames.all { it.getString("source") == "Hero" && it.getString("readOnlyReason").isNotBlank() })
            val dialogue = entries.single { it.getString("path").endsWith("/list/1/parameters/0") }
            val tokens = dialogue.getJSONArray("protectedTokens")
            val values = (0 until tokens.length()).map { tokens.getJSONObject(it).getString("value") }
            assertEquals(listOf("\\C[\\V[1]]", "\\C[0]", "\u000c", "\\Custom_Name[nested[2]]"), values)

            fillTranslations(bundle)
            write(exchange, bundle.toString(2).toByteArray())
            val imported = manager.importManual(game, exchange.uri)
            assertTrue(imported.installed)
            assertEquals(0, imported.preservedFragments)
            assertTrue(manager.hasBackup(game))
            assertTrue(manager.isLaunchSafe(game))
            assertArrayEquals(savedBytes, read(save))

            val actors = JSONArray(read(sourceFiles.getValue("Actors.json")).toString(Charsets.UTF_8))
            assertEquals("Hero", actors.getJSONObject(1).getString("name"))
            assertEquals("Un héros", actors.getJSONObject(1).getString("profile"))
            assertEquals("Compagnon", actors.getJSONObject(2).getString("name"))
            val system = readJson(sourceFiles.getValue("System.json"))
            assertEquals("Or", system.getString("currencyUnit"))
            assertEquals("Nouvelle partie", system.getJSONObject("terms").getJSONArray("commands").getString(0))
            assertTrue(system.getJSONObject("terms").getJSONArray("commands").isNull(1))
            assertEquals("%1 gagne!", system.getJSONObject("terms").getJSONObject("messages").getString("victory"))
            assertEquals("Technical variable", system.getJSONArray("variables").getString(0))
            assertEquals("TitleImage", system.getString("title1Name"))
            val map = readJson(sourceFiles.getValue("Map001.json"))
            assertEquals("Bourg", map.getString("displayName"))
            assertEquals("<Technical:Hello>", map.getString("note"))
            val commands = commands(map)
            assertEquals("Narrateur", commands.getJSONObject(0).getJSONArray("parameters").getString(4))
            assertEquals("FaceImage", commands.getJSONObject(0).getJSONArray("parameters").getString(0))
            assertEquals("Bonjour \\C[\\V[1]]voyageur\\C[0]!\u000cPage suivante \\Custom_Name[nested[2]]",
                commands.getJSONObject(1).getJSONArray("parameters").getString(0))
            assertEquals("Oui", commands.getJSONObject(2).getJSONArray("parameters").getJSONArray(0).getString(0))
            assertEquals("Non", commands.getJSONObject(2).getJSONArray("parameters").getJSONArray(0).getString(1))
            assertEquals(0, commands.getJSONObject(3).getJSONArray("parameters").getInt(0))
            assertEquals("Oui", commands.getJSONObject(3).getJSONArray("parameters").getString(1))
            val savedName = JSONObject(savedBytes.toString(Charsets.UTF_8)).getJSONArray("actors").getJSONObject(1).getString("name")
            assertEquals(savedName, commands.getJSONObject(4).getJSONArray("parameters").getString(1))
            assertEquals(savedName, commands.getJSONObject(5).getJSONArray("parameters").getString(3))
            assertEquals("showText('Hello')", commands.getJSONObject(6).getJSONArray("parameters").getString(0))
            assertEquals("Hello", commands.getJSONObject(7).getJSONArray("parameters").getJSONObject(3).getString("text"))

            manager.restore(game)
            assertOriginals()
            assertArrayEquals(savedBytes, read(save))
            assertFalse(manager.hasBackup(game))
            assertTrue(manager.isLaunchSafe(game))
        }
    }

    @Test fun invalidManualSafImportsLeaveEverySourceAndSaveUntouched() = runBlocking<Unit> {
        withTimeout(30_000) {
            val exchange = newExchange()
            manager.exportManual(game, "en", "fr", exchange.uri)
            val exported = readJson(exchange)
            val variants = listOf<(JSONObject) -> Unit>(
                { bundle ->
                    val row = rows(bundle).single { it.getString("path").endsWith("/list/1/parameters/0") }
                    val marker = row.getJSONArray("protectedTokens").getJSONObject(0).getString("marker")
                    row.put("translation", row.getString("translation").replace(marker, ""))
                },
                { bundle -> rows(bundle).first { it.optBoolean("preserveOriginal") }.put("translation", "Héros") },
                { bundle -> bundle.put("gameId", "other-game") },
                { bundle -> bundle.getJSONArray("entries").remove(0) }
            )
            variants.forEach { damage ->
                val candidate = JSONObject(exported.toString())
                fillTranslations(candidate)
                damage(candidate)
                write(exchange, candidate.toString().toByteArray())
                expectRejected { manager.importManual(game, exchange.uri) }
                assertOriginals()
                assertArrayEquals(savedBytes, read(save))
                assertFalse(manager.hasBackup(game))
                assertTrue(manager.isLaunchSafe(game))
                assertNull(data.findFile(TranslationPatch.BACKUP))
            }
        }
    }

    @Test fun manualSafImportRejectsGameRevisionChangedAfterExport() = runBlocking<Unit> {
        withTimeout(30_000) {
            val exchange = newExchange()
            manager.exportManual(game, "en", "fr", exchange.uri)
            val bundle = readJson(exchange).also(::fillTranslations)
            write(exchange, bundle.toString().toByteArray())
            val externallyModified = originals.getValue("Map001.json").toString(Charsets.UTF_8).replace("Village", "Updated village").toByteArray()
            write(sourceFiles.getValue("Map001.json"), externallyModified)
            val failure = expectRejected { manager.importManual(game, exchange.uri) }
            assertTrue(failure.message.orEmpty().contains("changé depuis l’export"))
            originals.filterKeys { it != "Map001.json" }.forEach { (name, bytes) -> assertArrayEquals(bytes, read(sourceFiles.getValue(name))) }
            assertArrayEquals(externallyModified, read(sourceFiles.getValue("Map001.json")))
            assertArrayEquals(savedBytes, read(save))
            assertFalse(manager.hasBackup(game))
            assertNull(data.findFile(TranslationPatch.BACKUP))
        }
    }

    @Test fun manualSafExportRejectsOccupiedAndGameOwnedDestinationsWithoutTruncation() = runBlocking<Unit> {
        withTimeout(30_000) {
            val exchange = newExchange()
            manager.exportManual(game, "en", "fr", exchange.uri)
            val previousExport = read(exchange)
            expectRejected { manager.exportManual(game, "en", "fr", exchange.uri) }
            assertArrayEquals(previousExport, read(exchange))
            expectRejected { manager.exportManual(game, "en", "fr", save.uri) }
            assertArrayEquals(savedBytes, read(save))
            val source = sourceFiles.getValue("Map001.json")
            val singleDocumentAlias = DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY, DocumentsContract.getDocumentId(source.uri))
            expectRejected { manager.exportManual(game, "en", "fr", singleDocumentAlias) }
            val emptyGameFile = requireNotNull(data.createFile("application/octet-stream", "reserved.bin"))
            expectRejected { manager.exportManual(game, "en", "fr", emptyGameFile.uri) }
            assertEquals(0, read(emptyGameFile).size)
            assertOriginals()
            assertFalse(manager.hasBackup(game))
            assertNull(data.findFile(TranslationPatch.BACKUP))
        }
    }

    private fun newExchange() = requireNotNull(documents.createFile("application/json", "translation-${UUID.randomUUID()}.json"))
    private fun read(file: DocumentFile) = requireNotNull(context.contentResolver.openInputStream(file.uri)).use { it.readBytes() }
    private fun write(file: DocumentFile, bytes: ByteArray) = requireNotNull(context.contentResolver.openOutputStream(file.uri, "wt")).use { it.write(bytes) }
    private fun readJson(file: DocumentFile) = JSONObject(read(file).toString(Charsets.UTF_8))
    private fun commands(map: JSONObject) = map.getJSONArray("events").getJSONObject(1).getJSONArray("pages").getJSONObject(0).getJSONArray("list")
    private fun rows(bundle: JSONObject): List<JSONObject> = bundle.getJSONArray("entries").let { array -> (0 until array.length()).map(array::getJSONObject) }
    private fun assertOriginals() = originals.forEach { (name, bytes) -> assertArrayEquals(name, bytes, read(sourceFiles.getValue(name))) }
    private fun fillTranslations(bundle: JSONObject) {
        val translations = linkedMapOf("Example" to "Exemple", "Village" to "Bourg", "Hello" to "Bonjour", "traveler" to "voyageur",
            "Next page" to "Page suivante", "Gold" to "Or", "New Game" to "Nouvelle partie", "wins" to "gagne", "Narrator" to "Narrateur",
            "Yes" to "Oui", "No" to "Non", "A hero" to "Un héros", "Companion" to "Compagnon")
        // Replace source spans once: never translate "No" again inside the generated "Nouvelle partie".
        val sourcePhrases = Regex(translations.keys.joinToString("|") { Regex.escape(it) })
        rows(bundle).forEach { row ->
            val source = row.getString("source")
            row.put("translation", if (row.optBoolean("preserveOriginal")) source else sourcePhrases.replace(source) { translations.getValue(it.value) })
        }
    }
    private suspend fun expectRejected(operation: suspend () -> Unit): Throwable {
        val failure = runCatching { operation() }.exceptionOrNull()
        assertNotNull("The invalid exchange must be rejected", failure)
        assertTrue("Expected a validation failure, got $failure", failure is IllegalArgumentException || failure is IllegalStateException)
        return requireNotNull(failure)
    }
}
