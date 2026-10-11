package fr.astragames.app.data.scanner

import fr.astragames.app.core.model.GameEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class GodotGameDetectorTest {
    @Test fun readsEngineGenerationFromThePackHeader() {
        assertEquals(GameEngine.GODOT_3, GodotGameDetector.packEngine(header(3)))
        assertEquals(GameEngine.GODOT_4, GodotGameDetector.packEngine(header(4)))
        assertNull(GodotGameDetector.packEngine(header(5)))
        assertNull(GodotGameDetector.packEngine(header(4).copyOf(11)))
        assertNull(GodotGameDetector.packEngine(ByteArray(20)))
    }

    @Test fun readsGodotProjectConfigurationWithoutConfusingVersionComments() {
        assertEquals(GameEngine.GODOT_3, GodotGameDetector.projectEngine("; config_version=5\nconfig_version=4\n[application]"))
        assertEquals(GameEngine.GODOT_4, GodotGameDetector.projectEngine("config_version = 5\r\n[application]"))
        assertNull(GodotGameDetector.projectEngine("config_version=6"))
        assertNull(GodotGameDetector.projectEngine("; config_version=5"))
    }

    @Test fun readsEmbeddedPackWithOnlyTwoBoundedReads() {
        val executable = ByteArray(512) + header(4) + footer(20)
        val reads = mutableListOf<Pair<Long, Int>>()
        val engine = GodotGameDetector.embeddedEngine(executable.size.toLong()) { offset, count ->
            reads += offset to count
            executable.copyOfRange(offset.toInt(), offset.toInt() + count)
        }
        assertEquals(GameEngine.GODOT_4, engine)
        assertEquals(listOf(532L to 12, 512L to 20), reads)
    }

    @Test fun rejectsMalformedEmbeddedPackSizesBeforeSeeking() {
        for (size in listOf(-1L, 0L, 19L, Long.MAX_VALUE)) {
            var reads = 0
            assertNull(GodotGameDetector.embeddedEngine(1024) { _, _ -> reads++; footer(size) })
            assertEquals(1, reads)
        }
        assertNull(GodotGameDetector.embeddedEngine(10) { _, _ -> fail("Small files must not be read"); byteArrayOf() })
    }

    private fun header(major: Int) = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0x43504447).putInt(if (major == 3) 1 else 2).putInt(major).putInt(3).putInt(0).array()

    private fun footer(size: Long) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        .putLong(size).putInt(0x43504447).array()
}
