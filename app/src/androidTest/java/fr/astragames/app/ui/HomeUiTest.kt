package fr.astragames.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.ui.theme.AstraTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val previousLanguage = AppLocalizer.language
    private val covers = mutableListOf<File>()

    @After fun cleanup() { AppLocalizer.language = previousLanguage; covers.forEach { it.delete() } }

    private fun game(id: String, title: String, time: Long, color: Int): GameEntity {
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            val paint = Paint().apply { this.color = android.graphics.Color.argb(70, 255, 255, 255); isAntiAlias = true }
            drawCircle(60f, 100f, 80f, paint)
            paint.color = android.graphics.Color.argb(80, 0, 0, 0)
            drawRect(0f, 210f, 200f, 300f, paint)
        }
        val cover = File.createTempFile("home-cover-", ".png", context.cacheDir).also { covers += it }
        cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return GameEntity(id = id, title = title, documentUri = "file:///test/$id", physicalPath = null, executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "test", dateAdded = 1, lastModified = 1, fingerprint = id, lastPlayedAt = time, coverUri = Uri.fromFile(cover).toString())
    }

    @Test fun homeShowsRecentOrderResumeActionAndFourFloatingDestinations() {
        AppLocalizer.language = AppLanguage.ENGLISH
        val resumed = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val games = listOf(
            game("second", "Northern Lights", 1726000000000, 0xff39465a.toInt()),
            game("first", "The Clockwork Garden", 1726100000000, 0xff716b82.toInt()),
            game("third", "Moonlit Harbor", 1725900000000, 0xff423956.toInt()),
            game("fourth", "Older game", 1725800000000, 0xff334455.toInt())
        )
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH, LocalPageBottomPadding provides 108.dp) {
                AstraTheme {
                    val nav = rememberNavController()
                    val entry by nav.currentBackStackEntryAsState()
                    Surface(Modifier.fillMaxSize()) {
                        Box {
                            NavHost(navController = nav, startDestination = "home") {
                                composable("home") { HomeScreen(AstraUiState(games = games), opened::add, resumed::add, { navigate(nav, "library") }, {}) }
                                composable("library") { Text("All games opened") }
                                composable("collections") { Text("Collections opened") }
                                composable("settings") { Text("Settings opened") }
                            }
                            CompactBottomNavigation(entry?.destination?.route.orEmpty(), nav, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Astra").assertIsDisplayed()
        compose.onNodeWithText("GAMES").assertIsDisplayed()
        compose.onNodeWithText("The Clockwork Garden").assertIsDisplayed()
        compose.onNodeWithText("Older game").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
        compose.onNodeWithContentDescription("Games").assertIsNotSelected()
        compose.onNodeWithContentDescription("Updates").assertDoesNotExist()
        compose.onNodeWithContentDescription("History").assertDoesNotExist()
        compose.onNodeWithText("Resume game").performScrollTo().performClick()
        assertEquals(listOf("first"), resumed)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Northern Lights"))
        compose.onNode(hasScrollToNodeAction()).performTouchInput { swipeUp() }
        compose.onNodeWithText("Northern Lights").performClick()
        assertEquals(listOf("second"), opened)
        compose.onNodeWithText("The Clockwork Garden").performScrollTo()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(context.cacheDir, "ui-review").apply { mkdirs() }.resolve("home-phone.png")
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        compose.onNodeWithText("View all").performScrollTo().performClick()
        compose.onNodeWithText("All games opened").assertIsDisplayed()
        compose.onNodeWithContentDescription("Games").assertIsSelected()
    }

    @Test fun emptyHomeOffersLibraryWithoutInventingAResumeButton() {
        AppLocalizer.language = AppLanguage.ENGLISH
        var additions = 0
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) { AstraTheme {
            HomeScreen(AstraUiState(), {}, {}, {}, { additions++ })
        } } }
        compose.onNodeWithText("Resume game").assertDoesNotExist()
        compose.onNodeWithText("Add a game folder").performClick()
        assertEquals(1, additions)
    }
}
