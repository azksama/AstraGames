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
import com.canhub.cropper.CropImageView
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
        Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply {
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
                verifyPinchCrop(crop!!)
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

    private fun verifyPinchCrop(activity: Activity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun find(view: View): CropImageView? = if (view is CropImageView) view else
            (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
        lateinit var cropper: CropImageView
        var window = android.graphics.RectF()
        var initialWidth = 0
        val screen = IntArray(2)
        await {
            instrumentation.runOnMainSync {
                find(activity.window.decorView)?.let { view ->
                    cropper = view
                    window = android.graphics.RectF(view.cropWindowRect)
                    initialWidth = view.cropRect?.width() ?: 0
                    view.getLocationOnScreen(screen)
                }
            }
            initialWidth > 0 && window.width() > 0
        }
        val downTime = SystemClock.uptimeMillis()
        val cx = screen[0] + window.centerX(); val cy = screen[1] + window.centerY()
        fun send(action: Int, halfSpan: Float, count: Int = 2) {
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { index -> MotionEvent.PointerCoords().apply { x = cx + if (index == 0) -halfSpan else halfSpan; y = cy; pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        val start = window.width() * .3f
        send(MotionEvent.ACTION_DOWN, start, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start)
        repeat(10) { step -> SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, start * (1f - (step + 1) * .05f)) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start * .5f)
        send(MotionEvent.ACTION_UP, start * .5f, 1)
        await {
            var changed = false
            instrumentation.runOnMainSync { changed = (cropper.cropRect?.width() ?: initialWidth) < initialWidth * .9f }
            changed
        }
        instrumentation.runOnMainSync {
            val rect = requireNotNull(cropper.cropRect)
            assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= 600 && rect.bottom <= 900)
            val bitmap = requireNotNull(cropper.getCroppedImage(180, 250))
            try { assertTrue(bitmap.width > 0 && bitmap.height > 0) } finally { bitmap.recycle() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for Android lifecycle", condition())
    }
}
