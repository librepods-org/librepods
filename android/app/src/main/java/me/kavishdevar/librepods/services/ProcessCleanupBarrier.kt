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

package me.kavishdevar.librepods.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

internal class ProcessCleanupRegistration internal constructor(
    private val barrier: ProcessCleanupBarrier,
    private val token: Long,
) {
    fun complete() {
        barrier.complete(token)
    }
}

internal class ProcessCleanupBarrier {
    private var nextToken = 0L
    private val activeCleanups = mutableSetOf<Long>()
    private var open = completedSignal()

    @Synchronized
    fun registerCleanup(): ProcessCleanupRegistration {
        if (activeCleanups.isEmpty()) {
            open = CompletableDeferred()
        }
        val token = ++nextToken
        activeCleanups += token
        return ProcessCleanupRegistration(this, token)
    }

    @Synchronized
    fun openSignal(): Deferred<Unit> = open

    suspend fun awaitOpen() {
        while (true) {
            val signal = openSignal()
            signal.await()
            synchronized(this) {
                if (activeCleanups.isEmpty() && open === signal) return
            }
        }
    }

    internal fun complete(token: Long) {
        val completedSignal = synchronized(this) {
            if (!activeCleanups.remove(token) || activeCleanups.isNotEmpty()) {
                null
            } else {
                open
            }
        }
        completedSignal?.complete(Unit)
    }

    private fun completedSignal(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }
}

internal val airPodsProcessCleanupBarrier = ProcessCleanupBarrier()
