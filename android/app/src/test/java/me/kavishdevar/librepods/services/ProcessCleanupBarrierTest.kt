package me.kavishdevar.librepods.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessCleanupBarrierTest {
    @Test
    fun replacementInstanceStaysBlockedUntilEveryRegisteredCleanupCompletes() {
        val barrier = ProcessCleanupBarrier()
        val firstOldInstance = barrier.registerCleanup()
        val secondOldInstance = barrier.registerCleanup()
        val replacementInstanceGate = barrier.openSignal()

        assertFalse(replacementInstanceGate.isCompleted)

        firstOldInstance.complete()
        assertFalse(replacementInstanceGate.isCompleted)

        secondOldInstance.complete()
        assertTrue(replacementInstanceGate.isCompleted)
        assertTrue(barrier.openSignal().isCompleted)
    }

    @Test
    fun cleanupRegistrationCompletionIsIdempotent() {
        val barrier = ProcessCleanupBarrier()
        val cleanup = barrier.registerCleanup()
        val replacementInstanceGate = barrier.openSignal()

        cleanup.complete()
        cleanup.complete()

        assertTrue(replacementInstanceGate.isCompleted)
        assertTrue(barrier.openSignal().isCompleted)
    }
}
