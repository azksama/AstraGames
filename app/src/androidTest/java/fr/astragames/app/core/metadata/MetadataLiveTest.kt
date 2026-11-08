package fr.astragames.app.core.metadata

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.ui.AstraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network checks, executed through ADB on the Android emulator. */
@RunWith(AndroidJUnit4::class)
class MetadataLiveTest {
    @Test fun liveF95ReportsGuestAttachmentsAndReadsCurrentFields() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("metadataLive") == "true")
        val metadata = F95ZoneProvider().fetch("https://f95zone.to/threads/yabunuma-the-complete-edition-v1-0-n-r-d-works.321695/")
        assertFalse(metadata.version.isNullOrBlank())
        assertFalse(metadata.developer.isNullOrBlank())
        assertTrue(metadata.imagesRequireLogin || metadata.images.isNotEmpty())
        Log.i("AstraMetadataLive", "F95 HTTP parsed: version=${metadata.version}, loginRequired=${metadata.imagesRequireLogin}, images=${metadata.images.size}")
    }

    @Test fun liveAutomaticEnrichmentRetrievesVndbFieldsAndDecodableCover() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("metadataLive") == "true")
        val app = ApplicationProvider.getApplicationContext<AstraApplication>()
        val previous = app.container.settings.settings.first()
        val id = "metadata-live-vndb-fixture"
        app.container.settings.setScanOnLaunch(false)
        val game = GameEntity(id, "Doki Doki Literature Club!", documentUri = "content://metadata/live", physicalPath = null,
            executableName = "index.html", engine = "RENPY", launcher = "JOIPLAY", sourceId = "metadata-live-test",
            dateAdded = 1, lastModified = 1, fingerprint = id)
        app.container.dao.upsertGame(game)
        try {
            val vm = withContext(Dispatchers.Main) { AstraViewModel(app) }
            assertTrue(vm.enrichGame(id))
            val saved = app.container.dao.getGame(id)!!
            assertFalse(saved.developer.isNullOrBlank())
            assertFalse(saved.description.isNullOrBlank())
            assertNotNull(saved.coverUri)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(android.net.Uri.parse(saved.coverUri).path, bounds)
            assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
            Log.i("AstraMetadataLive", "Automatic enrichment: developer=true, description=true, cover=${bounds.outWidth}x${bounds.outHeight}")
        } finally {
            app.container.repository.removeCover(id)
            app.container.dao.deleteGameCompletely(id)
            app.container.settings.setScanOnLaunch(previous.scanOnLaunch)
        }
    }
}
