package fr.astragames.app.windows

import org.junit.Assert.*
import org.junit.Test

class WolfProcessLivenessTest {
    @Test fun launcherCanExitAndChildCanRestartWithoutKillingTheSession() {
        val state = WolfProcessLiveness(5000)
        assertTrue(state.alive(true, false, 0))
        assertTrue(state.alive(false, false, 10))
        assertTrue(state.alive(false, true, 3000))
        assertTrue(state.alive(false, false, 4000))
        assertTrue(state.alive(false, true, 8000))
        assertTrue(state.alive(false, false, 9000))
        assertFalse(state.alive(false, false, 14000))
    }

    @Test fun onlyTheTaggedGameAndItsChildrenBelongToTheLaunch() {
        val env = mapOf("ASTRA_GAME_SESSION" to "unique", "WINEPREFIX" to "/own/prefix", "ASTRA_RUNTIME_ROOT" to "/own/runtime")
        fun belongs(command: String, values: Map<String,String> = env) =
            WolfGameProcessTracker.belongsToGame(values, command, "unique", "/own/prefix", "/own/runtime")
        assertTrue(belongs("wine\u0000G:\\日本語\\Game.exe\u0000"))
        assertTrue(belongs("G:\\Game.exe\u0000-restart\u0000"))
        assertTrue(belongs("\"g:\\My Game.exe\" -wsmute=never\u0000"))
        assertFalse(belongs("C:\\windows\\system32\\services.exe --argument\u0000"))
        assertFalse(belongs("wine\u0000C:\\windows\\system32\\services.exe\u0000"))
        assertFalse(belongs("wine\u0000winedevice.exe\u0000"))
        assertFalse(belongs("wine\u0000G:\\Game.exe", env - "ASTRA_GAME_SESSION"))
        assertFalse(belongs("wine\u0000G:\\Game.exe", env + ("WINEPREFIX" to "/other/prefix")))
        assertFalse(belongs("wine\u0000G:\\Game.exe", env + ("ASTRA_GAME_SESSION" to "other")))
    }
}
