package fr.astragames.app.core.filesystem

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConfinedPathTest {
    @Test fun acceptsChildAndRootButRejectsTraversal() {
        val root = File("test-root").canonicalFile
        assertEquals(root.path, confinedPath(root, ""))
        assertEquals(File(root, "games/title").path, confinedPath(root, "games/title"))
        assertNull(confinedPath(root, "../test-root-other"))
        assertNull(confinedPath(root, "games/../../other"))
        assertNull(confinedPath(root, File(root.parentFile, "outside").absolutePath))
    }
}
