package fr.astragames.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateStartupAndLibraryProfile() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        device.swipe(device.displayWidth * 3 / 4, device.displayHeight / 2, device.displayWidth / 4, device.displayHeight / 2, 12)
        device.waitForIdle()
    }

    companion object {
        private const val PACKAGE_NAME = "fr.astragames.app"
    }
}
