package fr.astragames.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.canhub.cropper.CropImageActivity
import fr.astragames.app.AstraApplication
import fr.astragames.app.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoverLifecycleTest {
    @Test fun internalCropReturnsUnlockedAndRealBackgroundStillLocks() {
        val app = ApplicationProvider.getApplicationContext<AstraApplication>()
        val settings = app.container.settings
        val previous = runBlocking { settings.settings.first() }
        val image = File(app.cacheDir, "cover-imports/lifecycle-fixture.png").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(180, 250, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        runBlocking { settings.setBiometricLock(true); settings.setLockOnBackground(true); settings.setScanOnLaunch(false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AstraViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[AstraViewModel::class.java] }
                await { vm.uiState.value.settingsLoaded && vm.uiState.value.settings.lockBiometricEnabled }
                // Authentication is simulated; the activity transitions and cropper are real Android components.
                scenario.onActivity {
                    vm.unlockApp()
                    vm.cropRequests.tryEmit(CropRequest("lifecycle-fixture", FileProvider.getUriForFile(app, "${app.packageName}.files", image)))
                }
                var crop: Activity? = null
                await {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        crop = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is CropImageActivity }
                    }
                    crop != null
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { crop!!.finish() }
                await { scenario.state == Lifecycle.State.RESUMED }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertFalse("Internal crop must not relock Astra", vm.isLocked.value)
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { vm.isLocked.value }
                assertTrue("Actual background must still relock", vm.isLocked.value)
            }
        } finally {
            runBlocking {
                settings.setBiometricLock(previous.lockBiometricEnabled)
                settings.setLockOnBackground(previous.lockOnBackground)
                settings.setScanOnLaunch(previous.scanOnLaunch)
            }
            image.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for Android lifecycle", condition())
    }
}
