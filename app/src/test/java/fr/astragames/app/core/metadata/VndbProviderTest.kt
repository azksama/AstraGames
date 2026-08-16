package fr.astragames.app.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VndbProviderTest {
    @Test fun parsesMatchingMetadataWithoutTagsAndCanonicalizesF95Link() {
        val result = VndbProvider().parseResponse(
            """
            {"results":[{
              "id":"v42","title":"Astra Story","alttitle":"アストラ",
              "aliases":["Astra Story v1.0"],
              "description":"[b]Une histoire[/b]\nhttps://f95zone.to/threads/astra-story.456/page-3",
              "image":{"url":"https://t.vndb.org/cv/42/42.jpg"},
              "developers":[{"name":"Studio Nova"}],
              "extlinks":[]
            }]}
            """.trimIndent(),
            "Astra Story [v1.0]"
        )

        assertEquals("v42", result?.id)
        assertEquals("Une histoire\nhttps://f95zone.to/threads/astra-story.456/page-3", result?.description)
        assertEquals(listOf("Studio Nova"), result?.developers)
        assertEquals("https://t.vndb.org/cv/42/42.jpg", result?.coverUrl)
        assertEquals("https://f95zone.to/threads/astra-story.456/", result?.f95Url)
    }

    @Test fun rejectsUnrelatedTopResult() {
        val result = VndbProvider().parseResponse(
            """{"results":[{"id":"v1","title":"Completely Different","aliases":[],"developers":[],"extlinks":[]}]}""",
            "Astra Story"
        )
        assertNull(result)
    }
}
