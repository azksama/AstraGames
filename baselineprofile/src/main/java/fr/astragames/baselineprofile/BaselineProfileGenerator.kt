package fr.astragames.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateStartupProfile() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        waitForEntryScreen(device)
    }

    @Test
    fun generateLibraryProfile() = rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = false) {
        pressHome()
        startActivityAndWait()
        waitForEntryScreen(device)
        if (device.hasObject(onboarding)) {
            val next = By.res(Pattern.compile(NEXT_BUTTON))
            repeat(5) {
                if (!device.hasObject(onboarding)) return@repeat
                if (!device.hasObject(next)) {
                    UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().resourceIdMatches(NEXT_BUTTON))
                }
                val button = checkNotNull(device.findObject(next)) { "Étape de configuration introuvable" }
                val step = button.resourceName
                button.click()
                check(device.wait(Until.gone(By.res(step)), TIMEOUT)) { "Configuration bloquée sur $step" }
            }
        }
        checkNotNull(device.wait(Until.findObject(libraryTab), TIMEOUT)) { "Onglet Jeux introuvable" }.click()
        check(device.wait(Until.hasObject(By.res("astra_game_library")), TIMEOUT)) { "La bibliothèque ne s’est pas ouverte" }
        Log.i("AstraBaseline", "Bibliothèque affichée")
    }

    private fun waitForEntryScreen(device: UiDevice) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.hasObject(libraryTab) || device.hasObject(onboarding)) return
            SystemClock.sleep(100)
        }
        error("L’accueil ou la configuration initiale d’Astra ne s’est pas affiché")
    }

    companion object {
        private const val PACKAGE_NAME = "fr.astragames.app"
        private const val TIMEOUT = 10_000L
        private const val NEXT_BUTTON = "astra_setup_next_[0-4]"
        private val libraryTab = By.res("astra_nav_library")
        private val onboarding = By.res("astra_onboarding")
    }
}
