package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.data.AirPodsBase
import me.kavishdevar.librepods.data.AirPodsPro1
import me.kavishdevar.librepods.data.Capability

/** ATT is used for hearing features, not ordinary AACP controls. */
internal fun shouldConnectAtt(model: AirPodsBase?, vendorIdHookEnabled: Boolean): Boolean {
    if (!vendorIdHookEnabled) return false
    // Preserve discovery for an unknown model. Known models without ATT features
    // should not open an unused hearing-feature channel (including AirPods 5).
    // Pro 1 uses ATT for custom transparency even without hearing-aid capabilities.
    return model == null || model is AirPodsPro1 || model.capabilities.any {
        it == Capability.HEARING_AID || it == Capability.LOUD_SOUND_REDUCTION
    }
}
