package me.kavishdevar.librepods.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class TeardownMediaPauseTest {
    @Test
    fun connectedProfilePausesBeforeCleanupFinishes() = runBlocking {
        var pauseCount = 0

        drainTeardownMediaPause(
            registerProfileCallback = { callback ->
                callback(true)
                true
            },
            pauseMedia = { pauseCount++ },
            timeoutMillis = 1_000,
        )

        assertEquals(1, pauseCount)
    }

    @Test
    fun timedOutCleanupRejectsDelayedProfileCallback() = runBlocking {
        val delayedCallback = AtomicReference<(Boolean) -> Unit>()
        var pauseCount = 0

        drainTeardownMediaPause(
            registerProfileCallback = { callback ->
                delayedCallback.set(callback)
                true
            },
            pauseMedia = { pauseCount++ },
            timeoutMillis = 1,
        )

        delayedCallback.get().invoke(true)

        assertEquals(0, pauseCount)
    }
}
