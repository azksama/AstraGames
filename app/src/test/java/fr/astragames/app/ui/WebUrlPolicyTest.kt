package fr.astragames.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlPolicyTest {
    @Test fun permitsHttpsSearchRedirects() {
        assertTrue(isAllowedWebUrl("https://www.google.com/search?q=game"))
        assertTrue(isAllowedWebUrl("https://f95zone.to/threads/example.123/", true))
        assertTrue(isAllowedWebUrl("https://www.f95zone.to/login", true))
    }

    @Test fun rejectsLocalFilesInsecureSchemesAndDeceptiveLoginOrigins() {
        listOf("file:///data/user/0/fr.astragames.app/databases/astra.db", "content://private/1",
            "javascript:alert(1)", "intent://open", "http://f95zone.to/login",
            "https://f95zone.to:8443/login", "https://user:password@f95zone.to/login", "not a URL")
            .forEach { assertFalse(it, isAllowedWebUrl(it)) }
        listOf("https://f95zone.to.attacker.example", "https://fakef95zone.to", "https://f95zone.to@attacker.example",
            "https://attacker.example/?next=https://f95zone.to").forEach { assertFalse(it, isAllowedWebUrl(it, true)) }
        assertTrue(isAllowedWebUrl("https://attacker.example", false)) // Search navigation is intentionally cross-site.
    }
}
