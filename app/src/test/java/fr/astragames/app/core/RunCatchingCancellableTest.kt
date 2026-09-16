package fr.astragames.app.core

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RunCatchingCancellableTest {
    @Test fun cancellationPropagatesWithoutBecomingAnOrdinaryFailure() {
        val cancellation = CancellationException("cancelled")
        try {
            runCatchingCancellable<Unit> { throw cancellation }
            fail("Cancellation was swallowed")
        } catch (error: CancellationException) { assertSame(cancellation, error) }
    }

    @Test fun ordinaryFailuresRemainRecoverable() {
        assertEquals("failed", runCatchingCancellable<Unit> { error("failed") }.exceptionOrNull()?.message)
    }
}
