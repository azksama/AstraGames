package fr.astragames.app.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class F95ZoneProviderTest {
    @Test fun extractsTagsAndLazyImages() {
        val metadata = F95ZoneProvider().parseHtml(
            """
            <html><body>
              <span class="js-tagList">
                <a href="/tags/2d-game/" class="tagItem">2d game</a>
                <a href="/tags/renpy/" class="tagItem">Ren'Py</a>
              </span>
              <div class="message-inner"><p>First description</p>
                <img src="thumb.jpg" data-src="/attachments/cover.webp" class="bbImage lazyloaded" alt="Cover">
              </div>
              <div class="message-inner">Second message</div>
            </body></html>
            """.trimIndent(),
            "https://f95zone.to/threads/example.123/"
        )

        assertEquals(listOf("2d game", "Ren'Py"), metadata.tags)
        assertEquals("https://f95zone.to/attachments/cover.webp", metadata.images.single().imageUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLinksOutsideF95Threads() {
        F95ZoneProvider().parseHtml("<div class=message-inner>x</div>", "https://example.com/threads/x")
    }

    @Test fun prefersLinkedOriginalAttachmentOverPreviewAttributes() {
        val metadata = F95ZoneProvider().parseHtml(
            """
            <div class="message-inner">
              <a href="/attachments/original-cover-jpg.98765/">
                <img class="bbImage" data-url="/data/attachments/preview.jpg" src="thumb.jpg">
              </a>
            </div>
            """.trimIndent(),
            "https://f95zone.to/threads/example.123/"
        )

        assertEquals(
            "https://f95zone.to/attachments/original-cover-jpg.98765/",
            metadata.images.single().imageUrl
        )
    }

    @Test fun picksLargestSrcSetWhenNoOriginalLinkExists() {
        val metadata = F95ZoneProvider().parseHtml(
            """
            <div class="message-inner">
              <img class="bbImage" src="thumb.jpg"
                   srcset="/images/cover-640.jpg 640w, /images/cover-1920.jpg 1920w">
            </div>
            """.trimIndent(),
            "https://f95zone.to/threads/example.123/"
        )

        assertEquals("https://f95zone.to/images/cover-1920.jpg", metadata.images.single().imageUrl)
    }

    @Test fun extractsOnlyF95ThreadsFromSearchResultsAndUnwrapsRedirects() {
        val results = F95ZoneProvider().parseSearchHtml(
            """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Ff95zone.to%2Fthreads%2Fwind-waiting-island.123%2F">Wind Waiting Island [v1.2]</a>
              <a class="result__snippet">Game thread on F95Zone.</a>
            </div>
            <div class="result"><a class="result__a" href="https://example.com/threads/nope">Other site</a></div>
            """.trimIndent()
        )

        assertEquals(1, results.size)
        assertEquals("Wind Waiting Island [v1.2]", results.single().title)
        assertEquals("https://f95zone.to/threads/wind-waiting-island.123/", results.single().url)
    }
}
