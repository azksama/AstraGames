package fr.astragames.app.launcher

import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoiPlayPayloadBuilderTest {
    @Test fun buildsValidatedMvPayload() {
        val json = JSONObject(JoiPlayPayloadBuilder.build(game())!!)
        assertEquals("Astra Quest", json.getString("title"))
        assertEquals("/storage/emulated/0/Games/AstraQuest", json.getString("folder"))
        assertEquals("rpgmmv", json.getString("type"))
        assertEquals("cyou.joiplay.runtime.rpgmmv.run", JoiPlayPayloadBuilder.actionFor("RPG_MAKER_MV"))
        assertEquals("cyou.joiplay.runtime.rpgmmz.run", JoiPlayPayloadBuilder.actionFor("RPG_MAKER_MZ"))
    }

    @Test fun refusesUnsupportedEngine() {
        assertNull(JoiPlayPayloadBuilder.build(game().copy(engine = "UNKNOWN")))
    }

    @Test fun exposesBothRenPyRuntimeActions() {
        assertEquals(
            listOf("cyou.joiplay.runtime.renpy8.run", "cyou.joiplay.runtime.renpy.run"),
            JoiPlayPayloadBuilder.actionsFor("RENPY")
        )
    }

    @Test fun mapsLegacyRpgMakerRuntimes() {
        assertEquals("cyou.joiplay.runtime.rpgmvxace.run", JoiPlayPayloadBuilder.actionFor("RPG_MAKER_VX_ACE"))
        assertEquals("cyou.joiplay.runtime.rpgmvx.run", JoiPlayPayloadBuilder.actionFor("RPG_MAKER_VX"))
        assertEquals("cyou.joiplay.runtime.rpgmxp.run", JoiPlayPayloadBuilder.actionFor("RPG_MAKER_XP"))
    }

    @Test fun launchProfileOverridesDetectedValues() {
        val profile = LaunchProfileEntity(
            gameId = "game-1", engineOverride = "RPG_MAKER_MZ",
            executableName = "Custom.exe", physicalPath = "/custom/folder",
            arguments = "--debug"
        )
        val json = JSONObject(JoiPlayPayloadBuilder.build(game(), profile)!!)

        assertEquals("rpgmmz", json.getString("type"))
        assertEquals("/custom/folder", json.getString("folder"))
        assertEquals("Custom.exe", json.getString("execFile"))
        assertEquals("astragame1", json.getString("id"))
    }

    @Test fun blankProfileValuesKeepAutomaticDetection() {
        val profile = LaunchProfileEntity(gameId = "game-1", executableName = "", physicalPath = "")
        assertEquals("RPG_MAKER_MV", LaunchProfileResolver.engine(game(), profile))
        assertEquals("Game.exe", LaunchProfileResolver.executable(game(), profile))
        assertEquals("/storage/emulated/0/Games/AstraQuest", LaunchProfileResolver.physicalPath(game(), profile))
    }

    private fun game() = GameEntity(
        id = "game-1", title = "Astra Quest", documentUri = "content://game",
        physicalPath = "/storage/emulated/0/Games/AstraQuest", executableName = "Game.exe",
        engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "source-1",
        dateAdded = 1L, lastModified = 1L, fingerprint = "abc"
    )
}
