package fr.astragames.app.core.metadata

import org.junit.Assert.*
import org.junit.Test

class RyuugamesProviderTest {
    @Test fun metadataIsReadFromTheArticleWithoutAdsOrDownloadLinks() {
        val value = RyuugamesProvider().parseHtml("""
            <meta property="og:image" content="https://www.ryuugames.com/cover.jpg">
            <h1 class="entry-title">Example (V1.2)</h1>
            <div class="advertisement"><img src="https://ads.example/ad.jpg"></div>
            <div class="td-post-content tagdiv-type">
            <h4>INFO</h4><p>Title : Example<br>Original Title : 原題<br>
            Language : English (Official)<br>Developer : Example studio</p>
            <h4>DESCRIPTION</h4><p>A short mystery.</p><p><img src="/screen.jpg"></p>
            <h4>LINK DOWNLOAD</h4><p>Download links must not become the description.</p></div>
            <ul class="td-tags"><li><a>Adventure</a></li><li><a>adventure</a></li></ul>
        """.trimIndent(), "https://ryuugames.com/example/?tracking=true")
        assertEquals("https://www.ryuugames.com/example/", value.sourceUrl)
        assertEquals("1.2", value.version)
        assertEquals("English (Official)", value.language)
        assertEquals("Example studio", value.developer)
        assertEquals("原題", value.originalTitle)
        assertEquals("A short mystery.", value.description)
        assertEquals(listOf("Adventure"), value.tags)
        assertEquals(2, value.images.size)
        assertTrue(value.images.none { "ads.example" in it.imageUrl })
    }
    @Test fun onlyArticleUrlsOnTheExactHttpsHostAreAccepted() {
        listOf("https://ryuugames.com/", "https://ryuugames.com/category/games/", "http://ryuugames.com/example/", "https://ryuugames.com.evil.test/example/", "https://secret@ryuugames.com/example/", "https://ryuugames.com:444/example/", "https://ryuugames.com/../private/", "https://ryuugames.com/%2e%2e/", "https://ryuugames.com/example%2fprivate/", "https://ryuugames.com/%252e/").forEach {
            assertNull(it, canonicalRyuugamesUrl(it))
        }
        assertEquals("https://www.ryuugames.com/example/", canonicalRyuugamesUrl("https://google.com/url?q=https%3A%2F%2Fwww.ryuugames.com%2Fexample%2F"))
    }
    @Test fun challengePagesAndIndexesAreNotImported() {
        assertTrue(runCatching { RyuugamesProvider().parseHtml("<title>Please wait</title>", "https://www.ryuugames.com/example/") }.isFailure)
    }
}
