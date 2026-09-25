package me.kavishdevar.librepods.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPodsModelsTest {
    private val commonCapabilities = setOf(
        Capability.SLEEP_DETECTION,
        Capability.CUSTOM_EQ,
        Capability.LISTENING_MODE,
        Capability.CONVERSATION_AWARENESS,
        Capability.HEAD_GESTURES,
        Capability.ADAPTIVE_AUDIO,
        Capability.ADAPTIVE_VOLUME,
        Capability.STEM_CONFIG
    )

    @Test
    fun standardModelNumbersResolveToAirPods5() {
        val registered = AirPodsModels.models.single { it is AirPods5 }
        for (number in listOf("A3531", "A3532", "A3533")) {
            assertSame(number, registered, AirPodsModels.getModelByModelNumber(number))
        }
    }

    @Test
    fun wirelessModelNumbersResolveToAirPods5Wireless() {
        val registered = AirPodsModels.models.single { it is AirPods5Wireless }
        for (number in listOf("A3439", "A3440", "A3441")) {
            assertSame(number, registered, AirPodsModels.getModelByModelNumber(number))
        }
    }

    @Test
    fun registeredClassesAndModelNumbersAreUnique() {
        val models = AirPodsModels.models
        assertEquals(models.size, models.map { it.javaClass }.toSet().size)
        val numbers = models.flatMap { it.modelNumber }
        assertEquals(numbers.size, numbers.toSet().size)
        models.forEach {
            assertTrue(it.modelNumber.isNotEmpty())
            assertTrue(it.name.isNotBlank())
            assertTrue(it.modelNumber.all { number -> number.matches(Regex("A[0-9]{4}")) })
        }
    }

    @Test
    fun existingModelsStillResolveToTheirOriginalClasses() {
        val existingModels = listOf(
            AirPods(), AirPods2(), AirPods3(), AirPods4(), AirPods4ANC(),
            AirPodsPro1(), AirPodsPro2Lightning(), AirPodsPro2USBC(), AirPodsPro3()
        )
        for (expected in existingModels) {
            for (number in expected.modelNumber) {
                val actual = AirPodsModels.getModelByModelNumber(number)
                assertNotNull(number, actual)
                assertEquals(number, expected.javaClass, actual!!.javaClass)
                assertEquals(number, expected.capabilities, actual.capabilities)
            }
        }
    }

    @Test
    fun caseNumbersAndUnknownNumbersAreNotRegisteredAsEarbuds() {
        for (number in listOf("A3530", "A3529", "A0000", "", "AirPods 5")) {
            assertNull(number, AirPodsModels.getModelByModelNumber(number))
        }
    }

    @Test
    fun standardCapabilitiesStaySeparateFromTheArtworkDonor() {
        assertEquals(commonCapabilities, AirPods5().capabilities)
        assertFalse(AirPods5().capabilities.contains(Capability.SWIPE_FOR_VOLUME))
    }

    @Test
    fun wirelessVariantAddsOnlyVolumeSwipe() {
        assertEquals(commonCapabilities + Capability.SWIPE_FOR_VOLUME, AirPods5Wireless().capabilities)
    }

    @Test
    fun proOnlyCapabilitiesAreNotEnabledBySharingArtwork() {
        val excluded = setOf(Capability.HRM, Capability.HEARING_AID, Capability.PPE,
            Capability.LOUD_SOUND_REDUCTION, Capability.CUSTOM_TRANSPARENCY)
        for (model in listOf(AirPods5(), AirPods5Wireless())) {
            assertTrue(model.capabilities.intersect(excluded).isEmpty())
        }
    }

    @Test
    fun airPods5UsesAllFiveAirPods4ArtworkReferences() {
        assertSameArtwork(AirPods4(), AirPods5())
    }

    @Test
    fun airPods5WirelessUsesAllFiveAirPods4ArtworkReferences() {
        assertSameArtwork(AirPods4(), AirPods5Wireless())
    }

    private fun assertSameArtwork(expected: AirPodsBase, actual: AirPodsBase) {
        assertEquals(expected.connectionArtworkRes, actual.connectionArtworkRes)
        assertEquals(expected.budCaseRes, actual.budCaseRes)
        assertEquals(expected.budsRes, actual.budsRes)
        assertEquals(expected.leftBudsRes, actual.leftBudsRes)
        assertEquals(expected.rightBudsRes, actual.rightBudsRes)
        assertEquals(expected.caseRes, actual.caseRes)
    }
}
