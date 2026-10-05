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
}
