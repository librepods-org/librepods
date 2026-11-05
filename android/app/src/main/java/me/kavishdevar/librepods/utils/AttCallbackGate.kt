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
import kotlinx.coroutines.Deferred

internal class AttCallbackGate {
    private val callbackLock = Any()
    private var acceptingCallbacks = true
    private var inFlightCallbacks = 0
    private val drain = CompletableDeferred<Unit>()

    fun runIfOpen(callback: () -> Unit): Boolean {
        val accepted = synchronized(callbackLock) {
            if (!acceptingCallbacks) return@synchronized false
            inFlightCallbacks++
            true
        }
        if (!accepted) return false

        try {
            callback()
        } finally {
            val drained = synchronized(callbackLock) {
                inFlightCallbacks--
                !acceptingCallbacks && inFlightCallbacks == 0
            }
            if (drained) drain.complete(Unit)
        }
        return true
    }

    fun closeForNewCallbacks(): Deferred<Unit> = closeAndDrain()

    fun closeAndDrain(): Deferred<Unit> {
        val drained = synchronized(callbackLock) {
            acceptingCallbacks = false
            inFlightCallbacks == 0
        }
        if (drained) drain.complete(Unit)
        return drain
    }
}
