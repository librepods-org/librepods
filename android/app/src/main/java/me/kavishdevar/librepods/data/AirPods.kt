/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.data

import me.kavishdevar.librepods.R

enum class FormFactor {
    /** Earbuds that live in a charging case, reporting a left, right and case battery. */
    IN_EAR,

    /** Over-ear headphones: a single body, a single battery and a case that does not charge. */
    OVER_EAR
}

open class AirPodsBase(
    val modelNumber: List<String>,
    val name: String,
    val displayName: String = "AirPods",
    val manufacturer: String = "Apple Inc.",
    val budCaseRes: Int,
    val budsRes: Int,
    val leftBudsRes: Int,
    val rightBudsRes: Int,
    val caseRes: Int,
    val capabilities: Set<Capability>,
    val formFactor: FormFactor = FormFactor.IN_EAR,
    /** Monochrome icon used for the notification, the QS tile and the popup. */
    val iconRes: Int = R.drawable.airpods,
    /** Proximity-pairing model ids advertised over BLE, used when the model number is unknown. */
    val bleModelIds: Set<Int> = emptySet()
) {
    /** Over-ear models have a case, but it holds no battery and is never reported. */
    val hasCase: Boolean get() = formFactor == FormFactor.IN_EAR

    /** Over-ear models are a single unit and report one battery instead of a left/right pair. */
    val isSingleBattery: Boolean get() = formFactor == FormFactor.OVER_EAR
}
enum class Capability {
    LISTENING_MODE,
    CONVERSATION_AWARENESS,
    STEM_CONFIG,
    HEAD_GESTURES,
    LOUD_SOUND_REDUCTION,
    PPE,
    SLEEP_DETECTION,
    HEARING_AID,
    ADAPTIVE_AUDIO,
    ADAPTIVE_VOLUME,
    SWIPE_FOR_VOLUME,
    HRM
}

class AirPods: AirPodsBase(
    modelNumber = listOf("A1523", "A1722"),
    name = "AirPods 1",
    // budCaseRes = R.drawable.airpods_1
    budCaseRes = R.drawable.airpods_pro_2,
    // budsRes = R.drawable.airpods_1_buds
    budsRes = R.drawable.airpods_pro_2_buds,
    // leftBudsRes = R.drawable.airpods_1_left
    leftBudsRes = R.drawable.airpods_pro_2_left,
    // rightBudsRes = R.drawable.airpods_1_right
    rightBudsRes = R.drawable.airpods_pro_2_right,
    // caseRes = R.drawable.airpods_1_case
    caseRes = R.drawable.airpods_pro_2_case,
    capabilities = emptySet(),
    bleModelIds = setOf(0x0220)
)

class AirPods2: AirPodsBase(
    modelNumber = listOf("A2032", "A2031"),
    name = "AirPods 2",
    // budCaseRes = R.drawable.airpods_2
    budCaseRes = R.drawable.airpods_pro_2,
    // budsRes = R.drawable.airpods_2_buds
    budsRes = R.drawable.airpods_pro_2_buds,
    // leftBudsRes = R.drawable.airpods_2_left
    leftBudsRes = R.drawable.airpods_pro_2_left,
    // rightBudsRes = R.drawable.airpods_2_right
    rightBudsRes = R.drawable.airpods_pro_2_right,
    // caseRes = R.drawable.airpods_2_case
    caseRes = R.drawable.airpods_pro_2_case,
    capabilities = emptySet(),
    bleModelIds = setOf(0x0F20)
)

class AirPods3: AirPodsBase(
    modelNumber = listOf("A2565", "A2564"),
    name = "AirPods 3",
    // budCaseRes = R.drawable.airpods_3
    budCaseRes = R.drawable.airpods_pro_2,
    // budsRes = R.drawable.airpods_3_buds
    budsRes = R.drawable.airpods_pro_2_buds,
    // leftBudsRes = R.drawable.airpods_3_left
    leftBudsRes = R.drawable.airpods_pro_2_left,
    // rightBudsRes = R.drawable.airpods_3_right
    rightBudsRes = R.drawable.airpods_pro_2_right,
    // caseRes = R.drawable.airpods_3_case
    caseRes = R.drawable.airpods_pro_2_case,
    capabilities = setOf(
        Capability.HEAD_GESTURES
    ),
    bleModelIds = setOf(0x1320)
)

class AirPods4: AirPodsBase(
    modelNumber = listOf("A3053", "A3050", "A3054"),
    name = "AirPods 4",
    // budCaseRes = R.drawable.airpods_4
    budCaseRes = R.drawable.airpods_pro_2,
    // budsRes = R.drawable.airpods_4_buds
    budsRes = R.drawable.airpods_pro_2_buds,
    // leftBudsRes = R.drawable.airpods_4_left
    leftBudsRes = R.drawable.airpods_pro_2_left,
    // rightBudsRes = R.drawable.airpods_4_right
    rightBudsRes = R.drawable.airpods_pro_2_right,
    // caseRes = R.drawable.airpods_4_case
    caseRes = R.drawable.airpods_pro_2_case,
    capabilities = setOf(
        Capability.HEAD_GESTURES,
        Capability.SLEEP_DETECTION,
        Capability.ADAPTIVE_VOLUME
    ),
    bleModelIds = setOf(0x1920)
)

class AirPods4ANC: AirPodsBase(
    modelNumber = listOf("A3056", "A3055", "A3057"),
    name = "AirPods 4 (ANC)",
    // budCaseRes = R.drawable.airpods_4
    budCaseRes = R.drawable.airpods_pro_2,
    // budsRes = R.drawable.airpods_4_buds
    budsRes = R.drawable.airpods_pro_2_buds,
    // leftBudsRes = R.drawable.airpods_4_left
    leftBudsRes = R.drawable.airpods_pro_2_left,
    // rightBudsRes = R.drawable.airpods_4_right
    rightBudsRes = R.drawable.airpods_pro_2_right,
    // caseRes = R.drawable.airpods_4_case
    caseRes = R.drawable.airpods_pro_2_case,
    capabilities = setOf(
        Capability.LISTENING_MODE,
        Capability.CONVERSATION_AWARENESS,
        Capability.HEAD_GESTURES,
        Capability.ADAPTIVE_AUDIO,
        Capability.SLEEP_DETECTION,
        Capability.ADAPTIVE_VOLUME,
        Capability.STEM_CONFIG
    ),
    bleModelIds = setOf(0x1B20)
)

class AirPodsPro1: AirPodsBase(
    modelNumber = listOf("A2084", "A2083"),
    name = "AirPods Pro 1",
    displayName = "AirPods Pro",
    // budCaseRes = R.drawable.airpods_pro_1
    budCaseRes = R.drawable.airpods_pro_2,
    // budsRes = R.drawable.airpods_pro_1_buds
    budsRes = R.drawable.airpods_pro_2_buds,
    // leftBudsRes = R.drawable.airpods_pro_1_left
    leftBudsRes = R.drawable.airpods_pro_2_left,
    // rightBudsRes = R.drawable.airpods_pro_1_right
    rightBudsRes = R.drawable.airpods_pro_2_right,
    // caseRes = R.drawable.airpods_pro_1_case
    caseRes = R.drawable.airpods_pro_2_case,
    capabilities = setOf(
        Capability.LISTENING_MODE
    ),
    bleModelIds = setOf(0x0E20)
)

class AirPodsPro2Lightning: AirPodsBase(
    modelNumber = listOf("A2931", "A2699", "A2698"),
    name = "AirPods Pro 2 with Magsafe Charging Case (Lightning)",
    displayName = "AirPods Pro",
    // budCaseRes = R.drawable.airpods_pro_2
    budCaseRes = R.drawable.airpods_pro_2,
    // budsRes = R.drawable.airpods_pro_2_buds
    budsRes = R.drawable.airpods_pro_2_buds,
    // leftBudsRes = R.drawable.airpods_pro_2_left
    leftBudsRes = R.drawable.airpods_pro_2_left,
    // rightBudsRes = R.drawable.airpods_pro_2_right
    rightBudsRes = R.drawable.airpods_pro_2_right,
    // caseRes = R.drawable.airpods_pro_2_case
    caseRes = R.drawable.airpods_pro_2_case,
    capabilities = setOf(
        Capability.LISTENING_MODE,
        Capability.CONVERSATION_AWARENESS,
        Capability.STEM_CONFIG,
        Capability.LOUD_SOUND_REDUCTION,
        Capability.SLEEP_DETECTION,
        Capability.HEARING_AID,
        Capability.ADAPTIVE_AUDIO,
        Capability.ADAPTIVE_VOLUME,
        Capability.SWIPE_FOR_VOLUME,
        Capability.HEAD_GESTURES
    ),
    bleModelIds = setOf(0x1420)
)

class AirPodsPro2USBC: AirPodsBase(
    modelNumber = listOf("A3047", "A3048", "A3049"),
    name = "AirPods Pro 2 with Magsafe Charging Case (USB-C)",
    displayName = "AirPods Pro",
    // budCaseRes = R.drawable.airpods_pro_2
    budCaseRes = R.drawable.airpods_pro_2,
    // budsRes = R.drawable.airpods_pro_2_buds
    budsRes = R.drawable.airpods_pro_2_buds,
    // leftBudsRes = R.drawable.airpods_pro_2_left
    leftBudsRes = R.drawable.airpods_pro_2_left,
    // rightBudsRes = R.drawable.airpods_pro_2_right
    rightBudsRes = R.drawable.airpods_pro_2_right,
    // caseRes = R.drawable.airpods_pro_2_case
    caseRes = R.drawable.airpods_pro_2_case,
    capabilities = setOf(
        Capability.LISTENING_MODE,
        Capability.CONVERSATION_AWARENESS,
        Capability.STEM_CONFIG,
        Capability.LOUD_SOUND_REDUCTION,
        Capability.SLEEP_DETECTION,
        Capability.HEARING_AID,
        Capability.ADAPTIVE_AUDIO,
        Capability.ADAPTIVE_VOLUME,
        Capability.SWIPE_FOR_VOLUME,
        Capability.HEAD_GESTURES
    ),
    bleModelIds = setOf(0x2420)
)

class AirPodsPro3: AirPodsBase(
    modelNumber = listOf("A3063", "A3064", "A3065"),
    name = "AirPods Pro 3",
    displayName = "AirPods Pro",
    // budCaseRes = R.drawable.airpods_pro_3
    budCaseRes = R.drawable.airpods_pro_2,
    // budsRes = R.drawable.airpods_pro_3_buds
    budsRes = R.drawable.airpods_pro_2_buds,
    // leftBudsRes = R.drawable.airpods_pro_3_left
    leftBudsRes = R.drawable.airpods_pro_2_left,
    // rightBudsRes = R.drawable.airpods_pro_3_right
    rightBudsRes = R.drawable.airpods_pro_2_right,
    // caseRes = R.drawable.airpods_pro_3_case
    caseRes = R.drawable.airpods_pro_2_case,
    capabilities = setOf(
        Capability.LISTENING_MODE,
        Capability.CONVERSATION_AWARENESS,
        Capability.HEAD_GESTURES,
        Capability.STEM_CONFIG,
        Capability.LOUD_SOUND_REDUCTION,
        Capability.PPE,
        Capability.SLEEP_DETECTION,
        Capability.HEARING_AID,
        Capability.ADAPTIVE_AUDIO,
        Capability.ADAPTIVE_VOLUME,
        Capability.SWIPE_FOR_VOLUME,
        Capability.HRM
    )
)

class AirPodsMax: AirPodsBase(
    modelNumber = listOf("A2096"),
    name = "AirPods Max",
    displayName = "AirPods Max",
    budCaseRes = R.drawable.airpods_max_device,
    budsRes = R.drawable.airpods_max_buds,
    leftBudsRes = R.drawable.airpods_max_left,
    rightBudsRes = R.drawable.airpods_max_right,
    // AirPods Max ship with a Smart Case that holds no battery, so this is never shown.
    caseRes = R.drawable.airpods_max_case,
    capabilities = setOf(
        Capability.LISTENING_MODE
    ),
    formFactor = FormFactor.OVER_EAR,
    iconRes = R.drawable.airpods_max_icon,
    bleModelIds = setOf(0x0A20)
)

class AirPodsMaxUSBC: AirPodsBase(
    modelNumber = listOf("A3184"),
    name = "AirPods Max (USB-C)",
    displayName = "AirPods Max",
    budCaseRes = R.drawable.airpods_max_usbc_device,
    budsRes = R.drawable.airpods_max_usbc_buds,
    leftBudsRes = R.drawable.airpods_max_usbc_left,
    rightBudsRes = R.drawable.airpods_max_usbc_right,
    caseRes = R.drawable.airpods_max_usbc_case,
    capabilities = setOf(
        Capability.LISTENING_MODE
    ),
    formFactor = FormFactor.OVER_EAR,
    iconRes = R.drawable.airpods_max_usbc_icon,
    bleModelIds = setOf(0x1F20)
)

class AirPodsMax2: AirPodsBase(
    modelNumber = listOf("A3454"),
    name = "AirPods Max 2",
    displayName = "AirPods Max",
    // No separate product render is published yet; the two generations look the same.
    budCaseRes = R.drawable.airpods_max_usbc_device,
    budsRes = R.drawable.airpods_max_usbc_buds,
    leftBudsRes = R.drawable.airpods_max_usbc_left,
    rightBudsRes = R.drawable.airpods_max_usbc_right,
    caseRes = R.drawable.airpods_max_usbc_case,
    capabilities = setOf(
        Capability.LISTENING_MODE,
        Capability.CONVERSATION_AWARENESS,
        Capability.ADAPTIVE_AUDIO,
        Capability.ADAPTIVE_VOLUME
    ),
    formFactor = FormFactor.OVER_EAR,
    iconRes = R.drawable.airpods_max_usbc_icon
)

data class AirPodsInstance(
    val name: String,
    val model: AirPodsBase,
    val actualModelNumber: String,
    val serialNumber: String?,
    val leftSerialNumber: String?,
    val rightSerialNumber: String?,
    val version1: String?,
    val version2: String?,
    val version3: String?,
)

object AirPodsModels {
    val models: List<AirPodsBase> = listOf(
        AirPods(),
        AirPods2(),
        AirPods3(),
        AirPods4(),
        AirPods4ANC(),
        AirPodsPro1(),
        AirPodsPro2Lightning(),
        AirPodsPro2USBC(),
        AirPodsPro3(),
        AirPodsMax(),
        AirPodsMaxUSBC(),
        AirPodsMax2()
    )

    fun getModelByModelNumber(modelNumber: String): AirPodsBase? {
        return models.find { modelNumber in it.modelNumber }
    }

    /** Looks up a model by the proximity-pairing id it advertises over BLE. */
    fun getModelByBleModelId(bleModelId: Int): AirPodsBase? {
        return models.find { bleModelId in it.bleModelIds }
    }

    /**
     * Last-resort match on the Bluetooth name, for firmware that reports a model number we do not
     * know yet. Only distinctive names are matched; anything ambiguous is left unresolved so the
     * caller can fall back to its own default.
     */
    fun getModelByName(name: String): AirPodsBase? {
        val n = name.lowercase()
        if (!n.contains("airpod")) return null
        return when {
            n.contains("max") -> if (n.contains("usb")) AirPodsMaxUSBC() else AirPodsMax()
            else -> null
        }
    }

    /**
     * Resolves a model from whatever identifying information is available, most reliable first.
     * Returns null when nothing matches, so callers keep control over the fallback.
     */
    fun resolveModel(
        modelNumber: String? = null,
        bleModelId: Int? = null,
        name: String? = null
    ): AirPodsBase? {
        modelNumber?.takeIf { it.isNotBlank() }?.let { getModelByModelNumber(it)?.let { m -> return m } }
        bleModelId?.let { getModelByBleModelId(it)?.let { m -> return m } }
        name?.takeIf { it.isNotBlank() }?.let { getModelByName(it)?.let { m -> return m } }
        return null
    }
}
