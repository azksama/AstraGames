package fr.astragames.app.updates

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AppUpdateOpeningTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val id = "opening-update-${UUID.randomUUID()}"
    private val root = File(app.cacheDir, id).apply { mkdirs() }
    private val context = object : ContextWrapper(app) {
        override fun getCacheDir() = root
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(id, mode)
    }
    private fun release(preview: Boolean = false) = GitHubRelease(if (preview) "99.0.0-beta.2" else "98.0.0", 42,
        "Astra.apk", 100, "a".repeat(64), "https://github.com/azksama/AstraGames/releases", preview)
    @After fun cleanup() { root.deleteRecursively(); app.deleteSharedPreferences(id) }

    @Test fun openingOffersAnUpdateWithoutDownloadingAndImmediateRestartsDoNotRecheck() = runBlocking {
        var calls = 0
        val manager = AppUpdateManager(context) { _, preview -> calls++; assertFalse(preview); release() }
        manager.checkOnOpen(); manager.checkOnOpen()
        assertEquals(1, calls); assertEquals("98.0.0", manager.state.value.release?.version)
        assertFalse(manager.state.value.ready); assertFalse(manager.state.value.busy)
        assertNull(manager.state.value.error); assertFalse(File(root, "app-updates/update.apk").exists())
    }

    @Test fun disablingAutomaticChecksStopsTheOpeningRequest() = runBlocking {
        context.getSharedPreferences("app_updates", 0).edit().putBoolean("automatic", false).commit()
        val manager = AppUpdateManager(context) { _, _ -> fail("Automatic check was disabled"); release() }
        manager.checkOnOpen(); assertNull(manager.state.value.release); assertFalse(manager.state.value.busy)
    }

    @Test fun channelChangeIsAppliedToTheNextOpeningCheck() = runBlocking {
        val channels = mutableListOf<Boolean>()
        val manager = AppUpdateManager(context) { _, preview -> channels += preview; release(preview) }
        manager.checkOnOpen(); manager.setIncludePrereleases(true); manager.checkOnOpen()
        assertEquals(listOf(false, true), channels); assertTrue(manager.state.value.release!!.prerelease)
    }

    @Test fun offlineOpeningPreservesAPreviouslyKnownOfferAndAnUpToDateCheckClearsIt() = runBlocking {
        AppUpdateManager(context) { _, _ -> release() }.check()
        val offline = AppUpdateManager(context) { _, _ -> throw java.io.IOException("offline fixture") }
        offline.checkOnOpen(); assertEquals("98.0.0", offline.state.value.release?.version)
        assertNotNull(offline.state.value.error)
        val current = AppUpdateManager(context) { _, _ -> release().copy(version = "1.0.0") }
        current.checkOnOpen(); assertNull(current.state.value.release)
        assertNull(AppUpdateManager(context).state.value.release)
    }
}
