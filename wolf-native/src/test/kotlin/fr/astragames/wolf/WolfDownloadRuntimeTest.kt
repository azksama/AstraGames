package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfDownloadRuntimeTest {
    @Test fun downloadCountersUseTheActiveWorkerAndAbandonedCallbacksCannotChangeThem() {
        val readers = (43..47).map { WolfCommand(121, intArrayOf(2_000_000 + it - 43, 9_000_000 + it, 0, 0), 0, emptyList()) }
        val vm = wolfHostRuntimeFixture(listOf(request()) + readers + end(), "download")
        vm.tick(); val active = vm.drainEffects().filterIsInstance<NativeEffect.Download>().single()
        vm.downloadProgress(active.requestId, WolfDownloadProgress(1024, 2048, 512, 2, 2))
        vm.downloadProgress(active.requestId + 1, WolfDownloadProgress(999, 999))
        vm.completeDownload(active.requestId, Result.success("complete")); vm.tick()
        assertEquals(listOf(1024, 2048, 512, 2, 2), (0..4).map { vm.snapshotState().numbers[it] })
        val before = vm.snapshotState(); vm.downloadProgress(active.requestId, WolfDownloadProgress(0, 0))
        assertEquals(before, vm.snapshotState())
    }
    private fun request(flags: Int = 3, target: Int = 3_000_000) = WolfCommand(260,
        intArrayOf(0, flags, target, 0x100), 0, listOf("https://example.org/result.txt", "Save/result.txt"))
    private fun assign(value: Int) = WolfCommand(121, intArrayOf(2_000_000, value, 0, 0), 0, emptyList())
    private fun end() = WolfCommand(173, intArrayOf(), 0, emptyList())

    @Test fun waitedReceptionOnlyResumesAfterHostCompletionAndFailurePreservesPreviousText() {
        val vm = wolfHostRuntimeFixture(listOf(WolfCommand(122, intArrayOf(3_000_000, 0), 0, listOf("previous")),
            request(), assign(77), end()), "download")
        vm.tick()
        val effect = vm.drainEffects().filterIsInstance<NativeEffect.Download>().single()
        assertEquals(0, vm.snapshotState().system[48]); assertNull(vm.snapshotState().numbers[0])
        repeat(3) { vm.tick() }
        assertNull(vm.snapshotState().numbers[0])
        assertTrue(vm.completeDownload(effect.requestId, Result.failure(java.io.IOException("HTTP404"))))
        assertEquals("previous", vm.snapshotState().strings[0]); assertEquals(-1, vm.snapshotState().system[48])
        vm.tick(); assertEquals(77, vm.snapshotState().numbers[0])
        assertFalse(vm.completeDownload(effect.requestId, Result.success("duplicate")))
        assertEquals("previous", vm.snapshotState().strings[0])
    }

    @Test fun nonwaitingReceptionContinuesButNeverReplacesAnActiveRequestOrWritesAfterRestore() {
        val vm = wolfHostRuntimeFixture(listOf(request(1), request(3, 3_000_001), assign(33), end()), "download")
        val clean = vm.snapshot()
        vm.tick()
        val effects = vm.drainEffects()
        val first = effects.filterIsInstance<NativeEffect.Download>().single()
        assertEquals(33, vm.snapshotState().numbers[0]); assertEquals(0, vm.snapshotState().system[48])
        assertTrue(effects.filterIsInstance<NativeEffect.Diagnostic>().any { "déjà en cours" in it.message })
        assertTrue(vm.completeDownload(first.requestId, Result.success("日本語")))
        assertEquals("日本語", vm.snapshotState().strings[0]); assertNull(vm.snapshotState().strings[1])
        assertEquals(1, vm.snapshotState().system[48])
        vm.restore(clean); vm.tick()
        val abandoned = vm.drainEffects().filterIsInstance<NativeEffect.Download>().single()
        vm.restore(clean)
        assertEquals(abandoned.requestId, vm.drainEffects().filterIsInstance<NativeEffect.CancelDownload>().single().requestId)
        assertFalse(vm.completeDownload(abandoned.requestId, Result.success("stale")))
        assertNull(vm.snapshotState().strings[0])
    }

    @Test fun restoringAWorldNeverRestoresAReceptionWithoutItsWorker() {
        val vm = wolfHostRuntimeFixture(listOf(request(1), end()), "download")
        vm.tick()
        val request = vm.drainEffects().filterIsInstance<NativeEffect.Download>().single()
        val savedDuringReception = vm.snapshot()
        vm.restore(savedDuringReception)
        assertEquals(-1, vm.snapshotState().system[48])
        assertEquals(request.requestId, vm.drainEffects().filterIsInstance<NativeEffect.CancelDownload>().single().requestId)
        assertFalse(vm.completeDownload(request.requestId, Result.success("too late")))
    }
}
