package me.kavishdevar.librepods.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AacpCleanupEffectsTest {
    @Test
    fun overlappingCleanupCannotLaunchEffectIntoReplacementGeneration() {
        val lifecycle = AacpSessionLifecycle<Any>()
        val owner = lifecycle.disconnect()
        val overlapping = lifecycle.disconnect()
        var launchedEffects = 0

        val ownerEffect = launchOwnedAacpCleanupEffect(owner.cleanupToken) {
            launchedEffects++
            Any()
        }
        val overlappingEffect = launchOwnedAacpCleanupEffect(overlapping.cleanupToken) {
            launchedEffects++
            Any()
        }

        assertNotNull(ownerEffect)
        assertNull(overlappingEffect)
        assertEquals(1, launchedEffects)
    }
}
