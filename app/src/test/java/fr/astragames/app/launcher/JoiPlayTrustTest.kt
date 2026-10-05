package fr.astragames.app.launcher

import org.junit.Assert.*
import org.junit.Test

class JoiPlayTrustTest {
    @Test fun onlyKnownRuntimePackagesAreAccepted() {
        val rpg = JoiPlayPayloadBuilder.trustedPackagesFor("RPG_MAKER_MZ")
        assertTrue("cyou.joiplay.runtime.rpgmaker" in rpg)
        assertFalse("com.attacker.runtime" in rpg)
        val renpy = JoiPlayPayloadBuilder.trustedPackagesFor("RENPY")
        assertTrue("cyou.joiplay.runtime.renpy8" in renpy)
        assertFalse("cyou.joiplay.runtime.renpy.attacker" in renpy)
        assertTrue(JoiPlayPayloadBuilder.trustedPackagesFor("UNKNOWN").isEmpty())
    }
}
