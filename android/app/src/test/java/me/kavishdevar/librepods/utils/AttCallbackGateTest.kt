package me.kavishdevar.librepods.utils

import kotlinx.coroutines.Deferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AttCallbackGateTest {
    @Test
    fun closeReturnsImmediatelyAndTracksInFlightCallbackDrain() {
        val gate = AttCallbackGate()
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val callbackAccepted = AtomicBoolean(false)
        val callbackCount = AtomicInteger(0)
        val drain = AtomicReference<Deferred<Unit>>()

        val callbackThread = Thread {
            callbackAccepted.set(gate.runIfOpen {
                callbackStarted.countDown()
                releaseCallback.await()
                callbackCount.incrementAndGet()
            })
        }
        val closeThread = Thread {
            drain.set(gate.closeAndDrain())
            closeReturned.countDown()
        }

        try {
            callbackThread.start()
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))
            closeThread.start()

            assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
            assertFalse(drain.get().isCompleted)
            assertFalse(gate.runIfOpen { callbackCount.incrementAndGet() })

            releaseCallback.countDown()
            callbackThread.join(1_000)
            closeThread.join(1_000)
            assertFalse(callbackThread.isAlive)
            assertFalse(closeThread.isAlive)
            assertTrue(callbackAccepted.get())
            assertTrue(drain.get().isCompleted)
            assertEquals(1, callbackCount.get())
        } finally {
            releaseCallback.countDown()
            callbackThread.join(1_000)
            closeThread.join(1_000)
        }
    }
}
