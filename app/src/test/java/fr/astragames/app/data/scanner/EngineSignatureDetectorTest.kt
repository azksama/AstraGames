package fr.astragames.app.data.scanner

import fr.astragames.app.core.model.GameEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSignatureDetectorTest {
    @Test fun detectsPackedAndUnpackedWolfButNotAnArbitraryExe() {
        assertEquals(GameEngine.WOLF_RPG, EngineSignatureDetector.detect(setOf("Game.exe", "Data.wolf"))?.engine)
        assertEquals(GameEngine.WOLF_RPG, EngineSignatureDetector.detect(setOf("GAME.EXE", "Data/BasicData/Game.dat"))?.engine)
        assertEquals(null, EngineSignatureDetector.detect(setOf("Game.exe", "Game.ini")))
        assertEquals(null, EngineSignatureDetector.detect(setOf("Data.wolf")))
    }
    @Test fun detectsRpgMakerMv() {
        val result = EngineSignatureDetector.detect(setOf("Game.exe", "www/js/rpg_core.js", "www/index.html"))
        assertEquals(GameEngine.RPG_MAKER_MV, result?.engine)
        assertTrue(result!!.confidence > .95f)
    }

    @Test fun mzWinsOverGenericHtml() {
        val result = EngineSignatureDetector.detect(setOf("Game.exe", "index.html", "www/js/rmmz_core.js"))
        assertEquals(GameEngine.RPG_MAKER_MZ, result?.engine)
    }

    @Test fun detectsRenPyScripts() {
        val result = EngineSignatureDetector.detect(setOf("game/script.rpyc", "renpy", "MyGame.exe"))
        assertEquals(GameEngine.RENPY, result?.engine)
    }

    @Test fun detectsRenPyScriptInDeepSubfolder() {
        val result = EngineSignatureDetector.detect(
            setOf("MyGame.exe", "game/scripts/chapters/prologue/scene_01.rpyc")
        )
        assertEquals(GameEngine.RENPY, result?.engine)
    }

    @Test fun detectsRpgMakerVxAceData() {
        val result = EngineSignatureDetector.detect(setOf("Game.exe", "Data/Actors.rvdata2"))
        assertEquals(GameEngine.RPG_MAKER_VX_ACE, result?.engine)
    }

    @Test fun detectsRpgMakerXpData() {
        val result = EngineSignatureDetector.detect(setOf("Game.exe", "Data/Actors.rxdata"))
        assertEquals(GameEngine.RPG_MAKER_XP, result?.engine)
    }
}
