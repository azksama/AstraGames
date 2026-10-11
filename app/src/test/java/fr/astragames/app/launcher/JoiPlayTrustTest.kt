package fr.astragames.app.launcher

import org.junit.Assert.*
import org.junit.Test

class JoiPlayTrustTest {
    @Test fun versionedRenpyRequiresVerifiedOfficialSignerAndExactVersionShape() {
        val official = setOf(JoiPlayRuntimeTrust.JOIPLAY_CERTIFICATE)
        assertTrue(JoiPlayRuntimeTrust.trusted("RENPY", "cyou.joiplay.runtime.renpy.v8d4d1", official))
        assertTrue(JoiPlayRuntimeTrust.trusted("RENPY", "cyou.joiplay.runtime.renpy.v7d7d1", official))
        assertFalse(JoiPlayRuntimeTrust.trusted("RENPY", "cyou.joiplay.runtime.renpy.v8d4d1", setOf("attacker")))
        assertFalse(JoiPlayRuntimeTrust.trusted("RENPY", "cyou.joiplay.runtime.renpy.v8d4d1.attacker", official))
        assertFalse(JoiPlayRuntimeTrust.trusted("RENPY", "cyou.joiplay.runtime.renpy.attacker", official))
        assertFalse(JoiPlayRuntimeTrust.trusted("RPG_MAKER_MZ", "cyou.joiplay.runtime.renpy.v8d4d1", official))
    }

    @Test fun runtimeVersionsSortNumerically() {
        assertTrue(JoiPlayRuntimeTrust.versionRank("cyou.joiplay.runtime.renpy.v8d10d0") >
            JoiPlayRuntimeTrust.versionRank("cyou.joiplay.runtime.renpy.v8d9d1"))
        assertTrue(JoiPlayRuntimeTrust.versionRank("cyou.joiplay.runtime.renpy.v8d0d0") >
            JoiPlayRuntimeTrust.versionRank("cyou.joiplay.runtime.renpy.v7d7d1"))
    }

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
