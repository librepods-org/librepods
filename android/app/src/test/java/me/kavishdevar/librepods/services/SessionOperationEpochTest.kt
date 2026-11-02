package me.kavishdevar.librepods.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionOperationEpochTest {
    private class FakeSession

    @Test
    fun staleProfileCallbackIsRejectedAfterNewerOperation() {
        val operations = SessionOperationEpoch<FakeSession>()
        val session = FakeSession()
        val staleCallback = operations.begin(session)
        val currentCallback = operations.begin(session)

        assertFalse(operations.isCurrent(staleCallback))
        assertTrue(operations.isCurrent(currentCallback))
    }

    @Test
    fun delayedEffectIsRejectedAfterSessionInvalidationAndReplacement() {
        val effects = SessionOperationEpoch<FakeSession>()
        val oldSession = FakeSession()
        val delayedEffect = effects.begin(oldSession)

        effects.invalidate()
        val replacementEffect = effects.begin(FakeSession())

        assertFalse(effects.isCurrent(delayedEffect))
        assertTrue(effects.isCurrent(replacementEffect))
    }

    @Test
    fun invalidationWaitsForAcceptedOperationAndThenRejectsIt() {
        val operations = SessionOperationEpoch<FakeSession>()
        val operation = operations.begin(FakeSession())
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val invalidationFinished = CountDownLatch(1)

        val actionThread = Thread {
            assertTrue(operations.runIfCurrent(operation) {
                actionStarted.countDown()
                releaseAction.await()
            })
        }
        val invalidationThread = Thread {
            operations.invalidate()
            invalidationFinished.countDown()
        }

        try {
            actionThread.start()
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS))
            invalidationThread.start()
            assertFalse(invalidationFinished.await(50, TimeUnit.MILLISECONDS))
            releaseAction.countDown()
            assertTrue(invalidationFinished.await(1, TimeUnit.SECONDS))
            assertFalse(operations.runIfCurrent(operation) {})
        } finally {
            releaseAction.countDown()
            actionThread.join(1_000)
            invalidationThread.join(1_000)
        }
    }
}
