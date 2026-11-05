/*
 * LibrePods - AirPods liberated from Apple’s ecosystem
 *
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package me.kavishdevar.librepods.constants

internal enum class BatteryMetadataField {
    CASE_LEVEL,
    CASE_CHARGING,
    LEFT_LEVEL,
    LEFT_CHARGING,
    RIGHT_LEVEL,
    RIGHT_CHARGING,
}

internal data class BatteryMetadataPayload(
    val field: BatteryMetadataField,
    val value: String,
)

internal fun planBatteryMetadata(batteries: List<Battery>): List<BatteryMetadataPayload> {
    val presentation = planBatteryPresentation(batteries)
    val payloads = mutableListOf<BatteryMetadataPayload>()

    fun addReading(
        reading: BatteryReadingPresentation?,
        levelField: BatteryMetadataField,
        chargingField: BatteryMetadataField,
    ) {
        reading ?: return
        payloads += BatteryMetadataPayload(levelField, reading.level.toString())
        payloads += BatteryMetadataPayload(chargingField, if (reading.charging) "1" else "0")
    }

    addReading(
        presentation.case,
        BatteryMetadataField.CASE_LEVEL,
        BatteryMetadataField.CASE_CHARGING,
    )
    addReading(
        presentation.left,
        BatteryMetadataField.LEFT_LEVEL,
        BatteryMetadataField.LEFT_CHARGING,
    )
    addReading(
        presentation.right,
        BatteryMetadataField.RIGHT_LEVEL,
        BatteryMetadataField.RIGHT_CHARGING,
    )
    return payloads
}
