package fr.astragames.app.data.scanner

import fr.astragames.app.core.model.GameEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GameFingerprintTest {
    @Test fun fingerprintIsStableAndEngineSensitive() {
        val first = GameFingerprint.create(GameEngine.RPG_MAKER_MV, "Astra", "RJ123456", "Game.exe")
        val same = GameFingerprint.create(GameEngine.RPG_MAKER_MV, "ASTRA", "RJ123456", "game.exe")
        val other = GameFingerprint.create(GameEngine.RENPY, "Astra", "RJ123456", "Game.exe")
        assertEquals(first, same)
        assertNotEquals(first, other)
    }
}
