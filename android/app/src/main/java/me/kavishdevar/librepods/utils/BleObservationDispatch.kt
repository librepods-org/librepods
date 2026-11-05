/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 *
 * Copyright (C) 2025 LibrePods Contributors
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

package me.kavishdevar.librepods.utils

internal data class BleObservationDispatch(
    val deliverStatusObservation: Boolean,
    val notifyNewAddress: Boolean,
    val lidStateChange: Boolean?,
)

internal fun planBleObservationDispatch(
    previousLidOpen: Boolean?,
    currentGlobalLidState: Boolean?,
    observedLidOpen: Boolean,
    statusChanged: Boolean,
): BleObservationDispatch {
    val isNewAddress = previousLidOpen == null
    val lidStateChange = when {
        isNewAddress && currentGlobalLidState != observedLidOpen -> observedLidOpen
        !isNewAddress && previousLidOpen != observedLidOpen &&
            currentGlobalLidState != observedLidOpen -> observedLidOpen
        else -> null
    }

    return BleObservationDispatch(
        deliverStatusObservation = isNewAddress || statusChanged,
        notifyNewAddress = isNewAddress,
        lidStateChange = lidStateChange,
    )
}
