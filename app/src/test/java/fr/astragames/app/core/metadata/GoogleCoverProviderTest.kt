package fr.astragames.app.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleCoverProviderTest {
    @Test fun extractsOriginalImageFromGoogleImageRedirect() {
        val results = GoogleCoverProvider().parseHtml(
            """
            <a href="/imgres?imgurl=https%3A%2F%2Fcdn.example.com%2Fcovers%2Fgame.jpg&amp;imgrefurl=https%3A%2F%2Fexample.com%2Fgame">
              <img src="https://encrypted-tbn0.gstatic.com/images?q=tbn:preview" alt="Game cover">
            </a>
            """.trimIndent()
        )

        assertEquals("https://cdn.example.com/covers/game.jpg", results.single().imageUrl)
        assertEquals("https://encrypted-tbn0.gstatic.com/images?q=tbn:preview", results.single().thumbnailUrl)
        assertEquals("https://example.com/game", results.single().contextUrl)
    }

    @Test fun extractsEscapedOriginalUrlsFromEmbeddedGoogleData() {
        val results = GoogleCoverProvider().parseHtml(
            """<script>window.data=[\"https:\/\/images.example.org\/cover.webp\",1200,1800]</script>"""
        )

        assertEquals("https://images.example.org/cover.webp", results.single().imageUrl)
    }
}
