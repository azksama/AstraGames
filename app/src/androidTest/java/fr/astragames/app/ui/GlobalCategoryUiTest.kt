package fr.astragames.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalCategoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoryNameAndFourActionsRemainReadableOnCompactPhoneWithLargeText() {
        val actions = mutableListOf<String>()
        val category = TagCategoryEntity("global-ui-category", "Aventures narratives", "aventures narratives")
        val previous = AppLocalizer.language
        AppLocalizer.language = AppLanguage.FRENCH
        try {
            compose.setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH,
                    LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    AstraTheme {
                        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(320.dp)) {
                            TagCategoryCard(category, 12, { actions += "up" }, { actions += "down" },
                                { actions += "edit" }, { actions += "delete" })
                        }
                        } }
                    }
                }
            }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(category.name).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("The category label must not collapse into a one-character column", layouts.single().lineCount <= 3)
            listOf("Monter", "Descendre", "Renommer", "Supprimer").forEach {
                compose.onNodeWithContentDescription(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            }
            compose.runOnIdle { assertEquals(listOf("up", "down", "edit", "delete"), actions) }
            android.os.SystemClock.sleep(400)
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "global-review-ui").apply { mkdirs() }
            File(directory, "category-320-large-text.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { AppLocalizer.language = previous }
    }
}
