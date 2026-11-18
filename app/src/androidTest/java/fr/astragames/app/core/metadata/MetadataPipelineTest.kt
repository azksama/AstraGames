package fr.astragames.app.core.metadata

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.repository.GameEdits
import fr.astragames.app.data.repository.GameRepository
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class MetadataPipelineTest {
    private val url = "https://f95zone.to/threads/example.123/"

    @Test fun currentF95GuestMarkupRequiresLoginInsteadOfReportingNoImages() {
        // Structure observed on the live site on 2026-11-08; content is synthetic.
        val metadata = F95ZoneProvider().parseHtml("""
            <html data-logged-in="false"><h1 class="p-title-value"><span class="label">Ren'Py</span> Example [v1.2] [Studio]</h1>
            <article class="message--post"><div class="message-inner"><div class="bbWrapper">
              <div class="messageHide messageHide--attach"><a href="/login/">Log in or register to see attachments</a></div>
              <b>Overview:</b><br>A journey through the forest.<br>
              <div class="bbCodeSpoiler">You don't have permission to view the spoiler content.</div>
              <b>Developer:</b> Studio<br><b>Version:</b> 1.2<br><b>Language:</b> English<br>
            </div></div></article></html>
        """.trimIndent(), url)
        assertTrue(metadata.imagesRequireLogin)
        assertTrue(metadata.images.isEmpty())
        assertEquals("1.2", metadata.version)
        assertEquals("Studio", metadata.developer)
        assertEquals("English", metadata.language)
        assertEquals("A journey through the forest.", metadata.description)
        assertEquals("Example [v1.2] [Studio]", metadata.title)
    }

    @Test fun attachmentOriginalHasWorkingPreviewFallbackAndRepliesAreExcluded() {
        val metadata = F95ZoneProvider().parseHtml("""
            <article class="message--post"><div class="bbWrapper">
              <a href="/attachments/cover-png.42/"><img class="bbImage" data-src="https://f95zone.to/data/attachments/cover.png" src="data:image/gif;base64,AA"></a>
              <blockquote class="bbCodeBlock--quote"><img src="https://example.com/quoted.png"></blockquote>
              <img class="smilie" src="https://example.com/emoji.png">
            </div></article>
            <article class="message--post"><div class="bbWrapper"><img src="https://example.com/reply.png"></div></article>
        """.trimIndent(), url)
        val image = metadata.images.single()
        assertEquals("https://f95zone.to/attachments/cover-png.42/", image.imageUrl)
        assertEquals("https://f95zone.to/data/attachments/cover.png", image.thumbnailUrl)
        assertEquals(listOf(image.thumbnailUrl), image.fallbackUrls)
        assertFalse(metadata.imagesRequireLogin)
    }

    @Test fun rememberedSessionIsMarkedExpiredWhenF95ReturnsGuestHtml() {
        val provider = F95ZoneProvider()
        provider.setSession(F95Session("Member", "remembered", "expired"))
        val metadata = provider.parseHtml("""<html data-logged-in="false"><div class="message-inner"><div class="bbWrapper"><div class="messageHide--attach">Log in or register to see attachments</div></div></div></html>""", url)
        assertTrue(metadata.sessionExpired)
        assertTrue(metadata.imagesRequireLogin)
        val member = provider.parseHtml("""<html data-logged-in="true"><div class="message-inner"><div class="bbWrapper"><img src="/data/attachments/cover.png"></div></div></html>""", url)
        assertFalse(member.sessionExpired)
        assertEquals(1, member.images.size)
    }

    @Test fun imageProxyIsPreservedAndInvalidLazyAttributeFallsBackToHttpsImage() {
        val metadata = F95ZoneProvider().parseHtml("""
            <div class="message-inner"><div class="bbWrapper">
              <img src="/proxy.php?image=https%3A%2F%2Fexample.com%2Fcover.png&amp;hash=123">
              <img data-url="javascript:invalid" data-src="data:invalid" src="/images/real.png">
            </div></div>
        """.trimIndent(), url)
        assertTrue(metadata.images.first().imageUrl.startsWith("https://f95zone.to/proxy.php?"))
        assertEquals("https://f95zone.to/images/real.png", metadata.images.last().imageUrl)
    }

    @Test fun lazyPictureUsesLargestSourceAndRetainsPlainImageFallback() {
        val metadata = F95ZoneProvider().parseHtml("""
            <div class="bbWrapper"><picture>
              <source data-srcset="/images/cover-600.webp 600w, /images/cover-1200.webp 1200w">
              <img src="/images/cover.jpg">
            </picture></div>
        """.trimIndent(), url)
        assertEquals("https://f95zone.to/images/cover-1200.webp", metadata.images.single().imageUrl)
        assertEquals(listOf("https://f95zone.to/images/cover.jpg"), metadata.images.single().fallbackUrls)
    }

    @Test fun invalidImageResponseLeavesNoPartialDownload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<AstraApplication>()
        val directory = java.io.File(context.cacheDir, "cover-imports")
        val before = directory.list().orEmpty().toSet()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("not an image".toResponseBody("image/png".toMediaType())).build()
        }.build()
        try {
            SearchCoverProvider(context, httpClient = client).download(CoverCandidate("https://example.com/broken", source = "test", matchedTitle = null, confidence = 1f))
            fail("An undecodable response must fail")
        } catch (_: IllegalStateException) { }
        assertEquals(before, directory.list().orEmpty().toSet())
    }

    @Test fun sessionOnlyLoginAndBrowserCookiesAreUsedOnlyOnF95HttpsHosts() {
        val provider = F95ZoneProvider { "xf_session=new-session; cf_clearance=clearance; unrelated=ignore" }
        provider.setSession(F95Session(null, "", "old-session"))
        val headers = provider.requestHeaders(url)
        assertEquals("xf_session=new-session; cf_clearance=clearance", headers["Cookie"])
        for (other in listOf("https://example.com/image.png", "https://f95zone.to.evil.test/image.png", "http://f95zone.to/image.png", "https://user@f95zone.to/image.png")) {
            assertFalse(provider.requestHeaders(other).containsKey("Cookie"))
        }
        assertFalse(provider.requestHeaders("https://attachments.f95zone.to/cover.png")["Cookie"].isNullOrBlank())
        assertNull(canonicalF95ThreadUrl("https://user@f95zone.to/threads/example.123/"))
    }

    @Test fun automaticMatchingRejectsSequelsAndUnrelatedShortTitles() {
        assertTrue(matchesMetadataTitle("Example Game v1.2", "Example Game [v1.2] [Studio]"))
        assertFalse(matchesMetadataTitle("Example Game", "Example Game 2 [v1.2]"))
        val json = """{"results":[{"id":"v1","title":"I","description":"Wrong game"}]}"""
        assertNull(VndbProvider().parseResponse(json, "Island Adventure"))
    }

    @Test fun missingVersionOrLanguageStillQualifiesForAutomaticRefresh() {
        val complete = fixture().copy(coverUri = "file:///cover.png", developer = "Studio", description = "Description", version = "1.0", language = "English")
        assertFalse(complete.needsMetadataEnrichment())
        assertTrue(complete.copy(version = null).needsMetadataEnrichment())
        assertTrue(complete.copy(language = "  ").needsMetadataEnrichment())
    }

    @Test fun automaticMergePreservesEditsAndSearchIndexAndFillsWhitespaceFields() = runBlocking {
        withRepository { repository, database ->
            val game = fixture().copy(developer = "My studio", description = "My notes", language = "   ")
            database.dao().upsertGame(game)
            assertTrue(repository.applyAutomaticMetadata(game.id, "Original", "Remote studio", "Remote text", url, "2.0", "English"))
            val saved = database.dao().getGame(game.id)!!
            assertEquals("My studio", saved.developer)
            assertEquals("My notes", saved.description)
            assertEquals("English", saved.language)
            assertEquals("2.0", saved.version)
            assertEquals(game.title, saved.title)
            assertFalse(repository.applyAutomaticMetadata(game.id, "Other", "Other", "Other", url, "3.0", "French"))
            database.openHelper.readableDatabase.query("SELECT originalTitle FROM game_search WHERE gameId = 'metadata-fixture'").use {
                assertTrue(it.moveToFirst()); assertEquals("Original", it.getString(0))
            }
        }
    }

    @Test fun explicitImportOnlyChangesSelectedFieldsAndPreservesExistingTags() = runBlocking {
        withRepository { repository, database ->
            val game = fixture().copy(developer = "Local studio", version = "1.0")
            database.dao().upsertGame(game)
            repository.importTextTags(game.id, "Existing")
            val metadata = F95ZoneMetadata(url, listOf("Adventure"), emptyList(), version = "2.0", developer = "Remote studio")
            repository.importMetadata(game.id, metadata, MetadataSource.F95ZONE,
                MetadataImportSelection(setOf(MetadataField.VERSION), setOf("Adventure"), null, includeSourceLink = false))
            val saved = database.dao().getGame(game.id)!!
            assertEquals("2.0", saved.version)
            assertEquals("Local studio", saved.developer)
            assertNull(saved.f95Url)
            assertEquals(2, database.dao().getTagIdsForGame(game.id).size)
        }
    }

    @Test fun invalidLinkDoesNotPartiallySaveEditorChanges() = runBlocking {
        withRepository { repository, database ->
            val game = fixture()
            database.dao().upsertGame(game)
            try {
                repository.updateGame(game.id, GameEdits("Changed", null, null, null, null, null, null, "https://example.com/"))
                fail("An invalid F95 link must fail before writing any fields")
            } catch (_: IllegalArgumentException) { }
            assertEquals(game, database.dao().getGame(game.id))
        }
    }

    @Test fun failedEditedTagsKeepThePreviousTitleSearchIndexAndSelection() = runBlocking {
        withRepository { repository, database ->
            val dao = database.dao()
            val game = fixture().copy(favorite = true)
            dao.upsertGame(game)
            repository.importTextTags(game.id, "Existing")
            val originalTag = dao.getTags().single()
            val edits = GameEdits("Edited", null, null, null, null, null, null, null)
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER refuse_tag BEFORE INSERT ON game_tags BEGIN SELECT RAISE(ABORT, 'tag write refused'); END")
            try {
                repository.updateGame(game.id, edits, "Mystère", selectedTags = emptySet())
                fail("The edit should fail")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(game, dao.getGame(game.id))
            assertEquals(listOf(originalTag), dao.observeTagsForGame(game.id).first())
            assertEquals(listOf(originalTag), dao.getTags())
            assertEquals(game.id, dao.searchGames("forest*").first().single().id)
            assertTrue(dao.searchGames("edited*").first().isEmpty())
            sql.execSQL("DROP TRIGGER refuse_tag")
            repeat(2) { repository.updateGame(game.id, edits, "Mystère", selectedTags = emptySet()) }
            assertEquals("Mystère", dao.observeTagsForGame(game.id).first().single().name)
            assertTrue(dao.searchGames("edited*").first().single().favorite)
            assertTrue(dao.searchGames("forest*").first().isEmpty())
            repository.updateGame(game.id, edits, "Second")
            assertEquals(2, dao.getTagIdsForGame(game.id).size)
            assertTrue(runCatching { repository.updateGame("missing", edits, "Absent") }.isFailure)
            assertTrue(runCatching { repository.importTextTags("missing", "Absent") }.isFailure)
            assertTrue(dao.getTags().none { it.name == "Absent" })
        }
    }

    @Test fun failedTagWriteRollsBackImportedFieldsIndexAndCategoryBeforeRetry() = runBlocking {
        withRepository { repository, database ->
            val dao = database.dao()
            val game = fixture().copy(developer = "LocalStudio")
            dao.upsertGame(game)
            repository.importTextTags(game.id, "Existing")
            val previousTags = dao.getTags()
            val previousIds = dao.getTagIdsForGame(game.id)
            val metadata = F95ZoneMetadata(url, listOf("Adventure"), emptyList(), developer = "NewStudio")
            val selection = MetadataImportSelection(setOf(MetadataField.DEVELOPER), setOf("Adventure"), null, includeSourceLink = true)
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER refuse_tag BEFORE INSERT ON game_tags BEGIN SELECT RAISE(ABORT, 'tag write refused'); END")
            try {
                repository.importMetadata(game.id, metadata, MetadataSource.F95ZONE, selection)
                fail("The write should fail")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(game, dao.getGame(game.id))
            assertEquals(previousTags, dao.getTags())
            assertEquals(previousIds, dao.getTagIdsForGame(game.id))
            assertTrue(dao.getTagCategories().isEmpty())
            sql.query("SELECT developer FROM game_search").use { assertTrue(it.moveToFirst()); assertEquals("LocalStudio", it.getString(0)) }

            sql.execSQL("DROP TRIGGER refuse_tag")
            repeat(2) { repository.importMetadata(game.id, metadata, MetadataSource.F95ZONE, selection) }
            assertEquals("NewStudio", dao.getGame(game.id)!!.developer)
            assertEquals(url, dao.getGame(game.id)!!.f95Url)
            assertEquals(2, dao.getTagIdsForGame(game.id).size)
        }
    }

    @Test fun importingTagsOnlyAddsNewLinksAndReusesEquivalentNames() = runBlocking {
        withRepository { repository, database ->
            val game = fixture()
            database.dao().upsertGame(game)
            repository.importTextTags(game.id, "Existing")
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE tag_removals (tagId TEXT)")
            sql.execSQL("CREATE TRIGGER count_removed AFTER DELETE ON game_tags BEGIN INSERT INTO tag_removals VALUES (OLD.tagId); END")
            repeat(2) {
                assertEquals(2, repository.importSourceTags(game.id, listOf("Aventures", "aventure", "Forêt", "foret"), "F95Zone"))
            }
            assertEquals(3, database.dao().getTagIdsForGame(game.id).size)
            sql.query("SELECT COUNT(*) FROM tag_removals").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun failedFileImportRollsBackNewCategoriesBeforeRetry() = runBlocking {
        withRepository { repository, database ->
            val context = ApplicationProvider.getApplicationContext<AstraApplication>()
            val source = File.createTempFile("import-tags-", ".csv", context.cacheDir)
            val dao = database.dao()
            try {
                source.writeText("nom,categorie\nAventure,Genres\nEnquête,Genres\n")
                val sql = database.openHelper.writableDatabase
                sql.execSQL("CREATE TRIGGER refuse_tag BEFORE INSERT ON tags BEGIN SELECT RAISE(ABORT, 'tag write refused'); END")
                try {
                    repository.importTags(Uri.fromFile(source))
                    fail("The file import should fail")
                } catch (_: android.database.sqlite.SQLiteException) { }
                assertTrue(dao.getTagCategories().isEmpty())
                assertTrue(dao.getTags().isEmpty())
                sql.execSQL("DROP TRIGGER refuse_tag")
                assertEquals(2, repository.importTags(Uri.fromFile(source)))
                assertEquals(0, repository.importTags(Uri.fromFile(source)))
                assertEquals("Genres", dao.getTagCategories().single().name)
                assertTrue(dao.getTags().all { it.groupName == "Genres" })
            } finally { source.delete() }
        }
    }

    @Test fun renamingATagToAnExistingNameKeepsBothGamesAssociations() = runBlocking {
        withRepository { repository, database ->
            val dao = database.dao()
            val game = fixture()
            val other = game.copy(id = "other", documentUri = "content://metadata/other")
            dao.upsertGame(game)
            dao.upsertGame(other)
            repository.importTextTags(game.id, "Puzzle")
            repository.importTextTags(other.id, "Aventure")
            val tags = dao.getTags()
            val puzzle = tags.single { it.name == "Puzzle" }
            val result = runCatching { repository.editTag(puzzle, "aventure", null) }
            assertEquals(tags, dao.getTags())
            assertTrue(result.isFailure)
            assertEquals("Aventure", dao.observeTagsForGame(other.id).first().single().name)
            repository.editTag(puzzle, "Énigme", null)
            assertEquals(puzzle.id, dao.observeTagsForGame(game.id).first().single().id)
            assertEquals("Énigme", dao.observeTagsForGame(game.id).first().single().name)
        }
    }

    @Test fun reusingACategoryNameKeepsItsIdentityOrderAndTags() = runBlocking {
        withRepository { repository, database ->
            val dao = database.dao()
            val game = fixture()
            dao.upsertGame(game)
            repository.importSourceTags(game.id, listOf("Aventure"), "Genres")
            repository.importSourceTags(game.id, listOf("Français"), "Langues")
            val categories = dao.getTagCategories()
            val tags = dao.getTags()
            repository.createTagCategory("genres")
            assertEquals(categories, dao.getTagCategories())
            val genres = categories.single { it.name == "Genres" }
            assertTrue(runCatching { repository.renameTagCategory(genres, "langues") }.isFailure)
            assertEquals(categories, dao.getTagCategories())
            assertEquals(tags, dao.getTags())
            repository.renameTagCategory(genres, "Styles")
            assertEquals(genres.id, dao.getTagCategories().single { it.name == "Styles" }.id)
            assertEquals("Styles", dao.getTags().single { it.name == "Aventure" }.groupName)
        }
    }

    @Test fun coverFileSurvivesARefusedRemovalAndCanBeRemovedOnRetry() = runBlocking {
        withRepository { repository, database ->
            val context = ApplicationProvider.getApplicationContext<AstraApplication>()
            val file = File.createTempFile("retained-", ".jpg", File(context.filesDir, "covers").apply { mkdirs() })
            try {
                file.writeText("my cover")
                val game = fixture().copy(coverUri = Uri.fromFile(file).toString())
                database.dao().upsertGame(game)
                val sql = database.openHelper.writableDatabase
                sql.execSQL("CREATE TRIGGER refuse_cover BEFORE UPDATE OF coverUri ON games BEGIN SELECT RAISE(ABORT, 'cover write refused'); END")
                try {
                    repository.removeCover(game.id)
                    fail("The write should fail")
                } catch (_: android.database.sqlite.SQLiteException) { }
                assertEquals(game.coverUri, database.dao().getGame(game.id)!!.coverUri)
                assertTrue("The referenced cover must remain readable", file.isFile)
                assertEquals("my cover", file.readText())
                sql.execSQL("DROP TRIGGER refuse_cover")
                repository.removeCover(game.id)
                assertNull(database.dao().getGame(game.id)!!.coverUri)
                assertFalse(file.exists())
            } finally { file.delete() }
        }
    }

    @Test fun replacingACoverKeepsItAvailableToOtherGamesAndTheirArtwork() = runBlocking {
        withRepository { repository, database ->
            val context = ApplicationProvider.getApplicationContext<AstraApplication>()
            val directory = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File.createTempFile("shared-", ".jpg", directory)
            val replacement = File.createTempFile("new-", ".jpg", context.cacheDir)
            val game = fixture().copy(coverUri = Uri.fromFile(file).toString())
            val other = game.copy(id = "other", documentUri = "content://metadata/other", bannerUri = game.coverUri)
            var stored: File? = null
            try {
                file.writeText("shared")
                replacement.writeText("new")
                database.dao().upsertGame(game)
                database.dao().upsertGame(other)
                assertTrue(repository.setCover(game.id, Uri.fromFile(replacement)))
                stored = File(Uri.parse(database.dao().getGame(game.id)!!.coverUri).path!!)
                assertEquals("new", stored.readText())
                assertTrue("The second game still uses this file", file.isFile)
                repository.removeCover(other.id)
                assertEquals("shared", file.readText())
                repository.deleteGame(other.id, false)
                assertFalse("The last artwork reference was removed", file.exists())
                assertTrue(stored.isFile)
            } finally { file.delete(); stored?.delete(); replacement.delete() }
        }
    }

    @Test fun deletingAGameWhileItsImageIsBeingCopiedDiscardsTheStagedFile() = runBlocking {
        withRepository { repository, database ->
            val context = ApplicationProvider.getApplicationContext<AstraApplication>()
            val directory = File(context.filesDir, "covers").apply { mkdirs() }
            val before = directory.list().orEmpty().toSet()
            val game = fixture()
            database.dao().upsertGame(game)
            val (reader, writer) = ParcelFileDescriptor.createPipe()
            reader.use {
                val input = Uri.fromFile(File("/proc/self/fd", reader.fd.toString()))
                coroutineScope {
                    val saving = async { repository.setCover(game.id, input) }
                    ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                        output.write(byteArrayOf(1, 2, 3))
                        withTimeout(10_000) {
                            while (directory.listFiles().orEmpty().none { it.name !in before && it.length() > 0 }) delay(10)
                        }
                        database.dao().deleteGameCompletely(game.id)
                    }
                    assertFalse(saving.await())
                }
            }
            assertEquals(before, directory.list().orEmpty().toSet())
        }
    }

    @Test fun parallelCoverChangesOnlyKeepThePublishedImage() = runBlocking {
        withRepository { repository, database ->
            val context = ApplicationProvider.getApplicationContext<AstraApplication>()
            val directory = File(context.filesDir, "covers").apply { mkdirs() }
            val before = directory.list().orEmpty().toSet()
            val game = fixture()
            database.dao().upsertGame(game)
            val input = File.createTempFile("parallel-", ".jpg", context.cacheDir)
            try {
                input.writeText("image")
                val uri = Uri.fromFile(input)
                val automatic = coroutineScope { List(6) { async { repository.setCover(game.id, uri, onlyIfMissing = true) } }.awaitAll() }
                assertEquals(1, automatic.count { it })
                coroutineScope { List(6) { async { assertTrue(repository.setCover(game.id, uri)) } }.awaitAll() }
                val published = File(Uri.parse(database.dao().getGame(game.id)!!.coverUri).path!!)
                assertEquals("image", published.readText())
                assertEquals(setOf(published.name), directory.list().orEmpty().toSet() - before)
                repository.removeCover(game.id)
                assertEquals(before, directory.list().orEmpty().toSet())
            } finally { input.delete() }
        }
    }

    @Test fun automaticCoverNeverOverwritesManualCoverAndDoesNotLeaveUnusedFiles() = runBlocking {
        withRepository { repository, database ->
            val game = fixture().copy(coverUri = "content://manual/cover")
            database.dao().upsertGame(game)
            assertFalse(repository.setCover(game.id, android.net.Uri.parse("content://unreadable/unused"), onlyIfMissing = true))
            assertEquals("content://manual/cover", database.dao().getGame(game.id)!!.coverUri)
            assertEquals(0, database.dao().setCoverIfMissing(game.id, "content://automatic/cover"))
        }
    }

    @Test fun downloadFallsBackFromDeniedOriginalToPreviewAndDecodesOnAndroid() = runBlocking {
        val bytes = ByteArrayOutputStream().also { stream ->
            Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, stream); recycle()
            }
        }.toByteArray()
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request().url.encodedPath == "/original"
            requests += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (original) 403 else 200)
                .message(if (original) "Forbidden" else "OK")
                .body((if (original) byteArrayOf() else bytes).toResponseBody("image/png".toMediaType())).build()
        }.build()
        val context = ApplicationProvider.getApplicationContext<AstraApplication>()
        val provider = SearchCoverProvider(context, httpClient = client)
        val uri = provider.download(CoverCandidate("https://example.com/original", source = "test", matchedTitle = null, confidence = 1f, fallbackUrls = listOf("https://example.com/preview")))
        try {
            assertEquals(listOf("/original", "/preview"), requests)
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
        } finally { provider.discardDownload(uri) }
    }

    private suspend fun withRepository(block: suspend (GameRepository, AstraDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<AstraApplication>()
        val database = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java).build()
        val repository = GameRepository(context, database, RecursiveSourceScanner(context, database.dao(), FileAccessResolver(context)), BackupManager(context, database))
        try { block(repository, database) } finally { database.close() }
    }

    private fun fixture() = GameEntity("metadata-fixture", "Forest Adventure", documentUri = "content://metadata/fixture", physicalPath = null,
        executableName = "index.html", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "metadata-test", dateAdded = 1, lastModified = 1, fingerprint = "metadata-test")
}
