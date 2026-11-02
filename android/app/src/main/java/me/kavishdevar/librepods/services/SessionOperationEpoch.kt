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

internal data class SessionOperation<Session : Any>(
    val epoch: Long,
    val session: Session,
)

internal class SessionOperationEpoch<Session : Any> {
    private var epoch = 0L
    private var current: SessionOperation<Session>? = null

    @Synchronized
    fun begin(session: Session): SessionOperation<Session> {
        val operation = SessionOperation(++epoch, session)
        current = operation
        return operation
    }

    @Synchronized
    fun isCurrent(operation: SessionOperation<Session>): Boolean =
        current === operation

    @Synchronized
    fun runIfCurrent(
        operation: SessionOperation<Session>,
        action: () -> Unit,
    ): Boolean {
        if (current !== operation) return false
        action()
        return true
    }

    @Synchronized
    fun invalidate() {
        epoch++
        current = null
    }
}
