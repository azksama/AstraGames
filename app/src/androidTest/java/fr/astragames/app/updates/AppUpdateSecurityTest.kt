package fr.astragames.app.updates

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppUpdateSecurityTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val id = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "update-security-$id").apply { mkdirs() }
    private val context = object : ContextWrapper(app) {
        override fun getCacheDir() = root
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("update-test-$id", mode)
    }
    @After fun cleanup() { root.deleteRecursively(); app.deleteSharedPreferences("update-test-$id") }

    @Test fun installedVersionAndUnrelatedPackageCannotBeInstalledAsAnUpdate() {
        val manager = AppUpdateManager(context)
        val installed = File(app.applicationInfo.sourceDir)
        val release = GitHubRelease(BuildConfig.VERSION_NAME, 42, "Astra.apk", installed.length(), fileDigest(installed), "")
        assertTrue(runCatching { manager.verifyPackage(installed, release) }.exceptionOrNull() is IllegalArgumentException)
        val other = File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        assertTrue(runCatching { manager.verifyPackage(other, release.copy(version = "99.0.0")) }.isFailure)
    }

    @Test fun alteredCachedApkIsRejectedBeforeFilePermissionIsGrantedAndCanBeDownloadedAgain() = runBlocking {
        val apk = File(root, "app-updates/update.apk").apply { parentFile!!.mkdirs(); writeText("expected payload") }
        val release = GitHubRelease("99.0.0", 42, "Astra-99.0.0-debug.apk", apk.length(), fileDigest(apk), "")
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit()
            .putString("release", release.json().toString()).putString("ready", release.sha256).commit()
        val manager = AppUpdateManager(context)
        assertTrue(manager.state.value.ready)
        apk.writeText("modified payload")
        assertTrue(runCatching { manager.installerIntent() }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(manager.state.value.ready)
        assertFalse(AppUpdateManager(context).state.value.ready)
    }

    @Test fun betaOptInPersistsAndOptOutInvalidatesDownloadedPreview() = runBlocking {
        val manager = AppUpdateManager(context)
        assertFalse(manager.state.value.includePrereleases)
        manager.setIncludePrereleases(true)
        val apk = File(root, "app-updates/update.apk").apply { parentFile!!.mkdirs(); writeText("preview") }
        val beta = GitHubRelease("99.0.0-beta.1", 42, "Astra.apk", apk.length(), fileDigest(apk), "", true)
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit()
            .putString("release", beta.json().toString()).putString("ready", beta.sha256).commit()
        val restored = AppUpdateManager(context)
        assertTrue(restored.state.value.includePrereleases)
        assertTrue(restored.state.value.ready)
        restored.setIncludePrereleases(false)
        assertFalse(restored.state.value.ready)
        assertNull(restored.state.value.release)
        assertFalse(apk.exists())
        assertTrue(runCatching { restored.installerIntent() }.isFailure)
        assertFalse(AppUpdateManager(context).state.value.includePrereleases)
        assertNull(AppUpdateManager(context).state.value.release)
    }

    @Test fun stableChannelIgnoresPreviewCacheAndAutomaticCheckUsesSelectedChannel() = runBlocking {
        val beta = GitHubRelease("99.0.0-beta.1", 42, "Astra.apk", 100, "a".repeat(64), "", true)
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putString("release", beta.json().toString()).commit()
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("download", false).commit()
        val requestedChannels = mutableListOf<Boolean>()
        val manager = AppUpdateManager(context) { _, previews ->
            requestedChannels += previews
            if (previews) beta else beta.copy(version = "98.0.0", prerelease = false)
        }
        assertNull(manager.state.value.release)
        // Keep this automatic-check test offline: it checks selection, not transport.
        manager.check(downloadAutomatically = true)
        assertEquals("98.0.0", manager.state.value.release?.version)
        manager.setIncludePrereleases(true)
        manager.check(downloadAutomatically = true)
        assertEquals(listOf(false, true), requestedChannels)
        assertEquals(beta, manager.state.value.release)
    }
}
