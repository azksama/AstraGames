package fr.astragames.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoiPlayCatalogProviderTest {
    @Test fun parsesOfficialCatalogFields() {
        val entries = JoiPlayCatalogProvider().parse(
            """[{"id":3,"title":"Ren'Py 8.5 Plugin","version":"1.01.00","description":"Runtime","link":"https://example.com/runtime.apk","date":"2026-02-14","badge":"Ren'Py"}]"""
        )
        assertEquals("Ren'Py 8.5 Plugin", entries.single().title)
        assertEquals("1.01.00", entries.single().version)
        assertEquals("https://example.com/runtime.apk", entries.single().downloadUrl)
    }

    @Test fun comparesPatreonVersionsNumerically() {
        val manager = JoiPlayRuntimeManager()
        assertTrue(manager.isNewer("1.22.00", "1.20.60-patreon"))
        assertFalse(manager.isNewer("1.21.000", "1.21.000-patreon"))
        assertFalse(manager.isNewer("1.00.60", "1.01.00-patreon"))
    }
}
