package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.data.AirPods4
import me.kavishdevar.librepods.data.AirPods4ANC
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsPro1
import me.kavishdevar.librepods.data.AirPodsPro2Lightning
import me.kavishdevar.librepods.data.AirPodsPro2USBC
import me.kavishdevar.librepods.data.AirPodsPro3
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttConnectionPolicyTest {
    @Test fun modelsWithoutHearingFeaturesDoNotOpenUnusedChannel() {
        assertFalse(shouldConnectAtt(AirPods4(), true))
        assertFalse(shouldConnectAtt(AirPods4ANC(), true))
    }

    @Test fun hearingModelsRetainAtt() {
        assertTrue(shouldConnectAtt(AirPodsPro1(), true))
        assertTrue(shouldConnectAtt(AirPodsPro2Lightning(), true))
        assertTrue(shouldConnectAtt(AirPodsPro2USBC(), true))
        assertTrue(shouldConnectAtt(AirPodsPro3(), true))
    }

    @Test fun disabledVendorHookNeverOpensAtt() {
        AirPodsModels.models.forEach { assertFalse(shouldConnectAtt(it, false)) }
        assertFalse(shouldConnectAtt(null, false))
    }

    @Test fun unknownModelPreservesExistingDiscoveryBehavior() {
        assertTrue(shouldConnectAtt(null, true))
    }
}
