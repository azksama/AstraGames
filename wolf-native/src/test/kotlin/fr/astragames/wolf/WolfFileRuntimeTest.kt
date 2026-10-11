package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfFileRuntimeTest {
    @Test fun writeReadsTheLeftVariableAndSuspendsFollowingCommandsUntilTheHostCommits() {
        val runtime = fixture(listOf(string(3_000_000, 0, "日本語\nprogress"), string(3_000_000, 0x600, "Save/progress.txt"),
            WolfCommand(121, intArrayOf(2_000_000, 7, 0, 0), 0, emptyList()), WolfCommand(173, intArrayOf(), 0, emptyList())))
        runtime.tick()
        val write = runtime.drainEffects().filterIsInstance<NativeEffect.FileOperation>().single()
        assertEquals(6, write.operation); assertEquals("Save/progress.txt", write.path)
        assertEquals("日本語\nprogress", write.text); assertTrue(write.modernText)
        assertNull(runtime.snapshotState().numbers[0])
        runtime.completeFileOperation(write.requestId, "")
        runtime.tick()
        assertEquals(7, runtime.snapshotState().numbers[0])
        assertEquals("日本語\nprogress", runtime.snapshotState().strings[0])
        runtime.completeFileOperation(write.requestId, "late duplicate")
        assertEquals("日本語\nprogress", runtime.snapshotState().strings[0])
    }

    @Test fun fileReadsListsAndHiddenNumericProbesResumeInOrderAndIgnoreOldCallbacks() {
        val runtime = fixture(listOf(string(3_000_000, 0x500, "missing.txt"), string(3_000_001, 0x700, "Save"),
            string(2_000_000, 0x40200, "<<GET_FILE_EXIST>>Save/progress.txt"),
            string(2_000_001, 0x500, "<<GET_SOUNDTIME>>Data/music.wav"), WolfCommand(173, intArrayOf(), 0, emptyList())))
        for ((operation, response) in listOf(5 to "<NoFile>", 7 to "a.txt\nb.txt", 8 to "1", 9 to "1234")) {
            runtime.tick()
            val request = runtime.drainEffects().filterIsInstance<NativeEffect.FileOperation>().single()
            assertEquals(operation, request.operation)
            runtime.completeFileOperation(request.requestId, response)
        }
        runtime.tick()
        val state = runtime.snapshotState()
        assertEquals("<NoFile>", state.strings[0]); assertEquals("a.txt\nb.txt", state.strings[1])
        assertEquals(1, state.numbers[0]); assertEquals(1234, state.numbers[1])

        val reset = fixture(listOf(string(3_000_000, 0x500, "Data/name.txt")))
        val fresh = reset.snapshot()
        reset.tick()
        val abandoned = reset.drainEffects().filterIsInstance<NativeEffect.FileOperation>().single()
        reset.restore(fresh); reset.completeFileOperation(abandoned.requestId, "stale")
        assertNull(reset.snapshotState().strings[0])
    }

    private fun string(destination: Int, flags: Int, value: String) = WolfCommand(122,
        intArrayOf(destination, flags, 0, 0, 0), 0, listOf(value))
    private fun fixture(commands: List<WolfCommand>): WolfRuntime {
        return wolfHostRuntimeFixture(commands, "files")
    }
}
