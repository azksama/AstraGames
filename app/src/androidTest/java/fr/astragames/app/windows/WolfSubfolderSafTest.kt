package fr.astragames.app.windows

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.saves.documentDir
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WolfSubfolderSafTest {
    @Test fun scannedGameDocumentUsesItsOwnFilesWithinTheOriginalTreeGrant() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = Uri.parse("content://fr.astragames.app.test.storage")
        val id = "wolf-subfolder-${UUID.randomUUID()}"
        val tree = Uri.parse(context.contentResolver.call(provider, "fixture", id, null)!!.getString("uri"))
        val root = File(context.filesDir, "fixtures/$id")
        File(root, "Game.exe").writeText("parent executable must not be selected")
        File(root, "Save").mkdirs()
        File(root, "Save/parent.sav").writeText("parent save must not be selected")
        File(root, "game/Game.exe").writeText("selected game")
        File(root, "game/save/selected.sav").writeText("selected game save")
        val selected = documentDir(context, tree)!!.findFile("game")!!.uri
        val storage = WolfGameStorage(context, id, selected.toString())
        try {
            val stages = mutableListOf<String>()
            storage.prepare("Game.exe", stages::add)
            assertTrue("Unexpected fallback: ${stages.joinToString(" | ")}", storage.isSaf)
            val saves = storage.saveFiles().filter { it.path.endsWith(".sav") }
            assertEquals(listOf("save/selected.sav"), saves.map { it.path })
            assertEquals("selected game save", saves.single().file.readText())
            assertEquals("parent save must not be selected", File(root, "Save/parent.sav").readText())
        } finally {
            storage.closeMount()
            runCatching { storage.restoreSourceConfiguration() }
            storage.directory.deleteRecursively()
            context.contentResolver.call(provider, "removeFixture", id, null)
        }
    }
}
