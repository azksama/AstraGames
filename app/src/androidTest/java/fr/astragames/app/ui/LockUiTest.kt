package fr.astragames.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
    @Test fun unlockingReturnsToTheSamePageAndDraftWithoutExposingItWhileLocked() {
        val locked = mutableStateOf(false)
        compose.setContent { AstraTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
            val nav = rememberNavController()
            AppLockBoundary(locked.value, lockedContent = {
                LockContent(false, true, {}) { pin, result ->
                    val valid = pin == "4826"
                    result(valid)
                    if (valid) locked.value = false
                }
            }) {
                NavHost(nav, startDestination = "home") {
                    composable("home") { Button(onClick = { nav.navigate("game/fixture") }) { Text("Ouvrir le jeu test") } }
                    composable("game/fixture") {
                        var draft by rememberSaveable { mutableStateOf("") }
                        Column {
                            Text("Fiche privée du jeu test")
                            OutlinedTextField(draft, { draft = it }, label = { Text("Note de partie") })
                            Button(onClick = { nav.popBackStack() }) { Text("Revenir à la bibliothèque") }
                        }
                    }
                }
            }
        } } }
        compose.onNodeWithText("Ouvrir le jeu test").performClick()
        compose.onNodeWithText("Note de partie").performTextInput("Progression à conserver")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithText("Fiche privée du jeu test").assertDoesNotExist()
        compose.onNodeWithText("Progression à conserver").assertDoesNotExist()
        compose.onNodeWithText("Code").performScrollTo().performTextInput("1234")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Déverrouiller").performScrollTo().performClick()
        compose.onNodeWithText("Code incorrect").assertExists()
        compose.onNodeWithText("Fiche privée du jeu test").assertDoesNotExist()
        compose.onNodeWithText("Code").performTextInput("4826")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Déverrouiller").performScrollTo().performClick()
        compose.onNodeWithText("Fiche privée du jeu test").assertIsDisplayed()
        compose.onNodeWithText("Progression à conserver").assertIsDisplayed()
        compose.onNodeWithText("Revenir à la bibliothèque").performClick()
        compose.onNodeWithText("Ouvrir le jeu test").assertIsDisplayed()
    }

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
