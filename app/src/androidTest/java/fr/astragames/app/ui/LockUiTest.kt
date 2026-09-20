package fr.astragames.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LockUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lockKeepsBiometricAndPinActionsAndMasksTheCode() {
        var biometricCalls = 0
        val attempts = mutableListOf<String>()
        compose.setContent { AstraTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
            LockContent(true, true, { biometricCalls++ }) { pin, result -> attempts += pin; result(false) }
        } } }
        compose.onNodeWithContentDescription("Astra").assertIsDisplayed()
        compose.onNodeWithText("Déverrouiller par biométrie").performScrollTo().performClick()
        assertEquals(1, biometricCalls)
        val app = ApplicationProvider.getApplicationContext<AstraApplication>()
        File(app.cacheDir, "ui-review").apply { mkdirs() }.resolve("lock.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Code").performScrollTo().performTextInput("123")
        compose.onNodeWithText("Déverrouiller").assertIsNotEnabled()
        compose.onNodeWithText("Code").performTextInput("4")
        compose.onNodeWithText("Code").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password)).assertTextContains("••••")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Déverrouiller").performScrollTo().performClick()
        compose.onNodeWithText("Code incorrect").assertExists()
        assertEquals(listOf("1234"), attempts)
        compose.onNodeWithText("Déverrouiller").assertIsNotEnabled()
    }
}
