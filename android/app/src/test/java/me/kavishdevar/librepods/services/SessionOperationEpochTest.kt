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
    fun invalidationReturnsImmediatelyAndExposesAcceptedOperationDrain() {
        val operations = SessionOperationEpoch<FakeSession>()
        val operation = operations.begin(FakeSession())
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)

        val actionThread = Thread {
            assertTrue(operations.runIfCurrent(operation) {
                actionStarted.countDown()
                releaseAction.await()
            })
        }

        try {
            actionThread.start()
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS))
            val drain = operations.invalidate()
            assertFalse(drain.isCompleted)
            assertFalse(operations.runIfCurrent(operation) {})
            releaseAction.countDown()
            actionThread.join(1_000)
            assertFalse(actionThread.isAlive)
            assertTrue(drain.isCompleted)
        } finally {
            releaseAction.countDown()
            actionThread.join(1_000)
        }
    }
}
