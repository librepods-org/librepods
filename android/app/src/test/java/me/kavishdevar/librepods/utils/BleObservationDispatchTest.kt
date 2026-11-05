package me.kavishdevar.librepods.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class BleObservationDispatchTest {
    private data class Observation(
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val lidOpen: Boolean,
    )

    private class RecordingListener {
        val exposedBattery = mutableMapOf<String, Int>()
        val lidEvents = mutableListOf<Boolean>()
        var newAddressNotifications = 0

        fun observe(
            observation: Observation,
            previous: Observation?,
            currentGlobalLidState: Boolean?,
        ): Boolean? {
            val dispatch = planBleObservationDispatch(
                previousLidOpen = previous?.lidOpen,
                currentGlobalLidState = currentGlobalLidState,
                observedLidOpen = observation.lidOpen,
                statusChanged = observation != previous,
            )

            if (dispatch.notifyNewAddress) newAddressNotifications++
            if (dispatch.deliverStatusObservation) {
                observation.leftBattery?.let { exposedBattery["left"] = it }
                observation.rightBattery?.let { exposedBattery["right"] = it }
                observation.caseBattery?.let { exposedBattery["case"] = it }
            }
            dispatch.lidStateChange?.let(lidEvents::add)
            return dispatch.lidStateChange ?: currentGlobalLidState
        }
    }

    @Test
    fun firstPartialObservationAfterDisappearIsDeliveredWhenLidIsUnchanged() {
        val listener = RecordingListener()
        val full = Observation(
            leftBattery = 58,
            rightBattery = 77,
            caseBattery = 86,
            lidOpen = false,
        )
        var globalLidState = listener.observe(
            observation = full,
            previous = null,
            currentGlobalLidState = null,
        )

        listener.exposedBattery.clear() // Device disappearance resets the battery session.
        val partial = Observation(leftBattery = 57, lidOpen = false)
        globalLidState = listener.observe(
            observation = partial,
            previous = null,
            currentGlobalLidState = globalLidState,
        )

        assertEquals(mapOf("left" to 57), listener.exposedBattery)
        assertEquals(2, listener.newAddressNotifications)
        assertEquals(listOf(false), listener.lidEvents)
        assertEquals(false, globalLidState)
    }
}
