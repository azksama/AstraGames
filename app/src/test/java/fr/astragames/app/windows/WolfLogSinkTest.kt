package fr.astragames.app.windows

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WolfLogSinkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun writesAppendAndRotateWithoutLosingTheLastCrash() {
        val file = File(temporary.root, "runtime.log")
        val sink = WolfLogSink(file, 20)
        sink.write("Box64 starts\n".toByteArray())
        sink.write("ok\n".toByteArray())
        assertEquals("Box64 starts\nok\n", file.readText())
        sink.write("SIGSEGV\n".toByteArray())
        assertEquals("SIGSEGV\n", file.readText())
        assertEquals("Box64 starts\nok\n", File(temporary.root, "runtime.previous.log").readText())
        repeat(100) { sink.write("0123456789\n".toByteArray()) }
        assertTrue(temporary.root.listFiles()!!.sumOf { it.length() } <= 40)
    }

    @Test fun oversizedWritesKeepOnlyTheBoundedTail() {
        val file = File(temporary.root, "runtime.log")
        WolfLogSink(file, 10).write(("a".repeat(100) + "fatal\n").toByteArray())
        assertEquals(10L, file.length())
        assertTrue(file.readText().endsWith("fatal\n"))
    }

    @Test fun exitCodeIsAnIndicationRatherThanAnInventedCause() {
        assertTrue(WolfDiagnosticSession.exitDescription(139).contains("SIGSEGV"))
        assertTrue(WolfDiagnosticSession.exitDescription(139).contains("à confirmer"))
        assertEquals("code de sortie 1", WolfDiagnosticSession.exitDescription(1))
    }
}
