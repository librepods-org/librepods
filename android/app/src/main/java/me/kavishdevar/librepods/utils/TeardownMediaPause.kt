/*
 * LibrePods - AirPods liberated from Apple’s ecosystem
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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun drainTeardownMediaPause(
    registerProfileCallback: (onProfileConnected: (Boolean) -> Unit) -> Boolean,
    pauseMedia: () -> Unit,
    timeoutMillis: Long,
) {
    val callbackGate = AttCallbackGate()
    val callbackFinished = CompletableDeferred<Unit>()
    try {
        val registered = registerProfileCallback { hasConnectedDevices ->
            callbackGate.runIfOpen {
                if (hasConnectedDevices) pauseMedia()
            }
            callbackFinished.complete(Unit)
        }

        if (registered) {
            withTimeoutOrNull(timeoutMillis) {
                callbackFinished.await()
            }
        }
    } finally {
        withContext(NonCancellable) {
            callbackGate.closeAndDrain().await()
        }
    }
}
