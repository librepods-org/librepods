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

internal data class SessionOperation<Session : Any>(
    val epoch: Long,
    val session: Session,
)

internal class SessionOperationEpoch<Session : Any> {
    private data class OperationState<Session : Any>(
        val operation: SessionOperation<Session>,
        var accepting: Boolean = true,
        var inFlight: Int = 0,
        val drain: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private var epoch = 0L
    private var current: OperationState<Session>? = null
    private val actionLock = Any()

    @Synchronized
    fun begin(session: Session): SessionOperation<Session> {
        closeCurrent()
        val operation = SessionOperation(++epoch, session)
        current = OperationState(operation)
        return operation
    }

    @Synchronized
    fun isCurrent(operation: SessionOperation<Session>): Boolean =
        current?.let { it.accepting && it.operation === operation } == true

    fun runIfCurrent(
        operation: SessionOperation<Session>,
        action: () -> Unit,
    ): Boolean {
        val state = synchronized(this) {
            val candidate = current
            if (candidate == null || !candidate.accepting || candidate.operation !== operation) {
                return false
            }
            candidate.inFlight++
            candidate
        }

        var performed = false
        try {
            synchronized(actionLock) {
                if (isCurrent(operation)) {
                    action()
                    performed = true
                }
            }
        } finally {
            release(state)
        }
        return performed
    }

    @Synchronized
    fun invalidate(): Deferred<Unit> = closeCurrent()

    @Synchronized
    private fun closeCurrent(): Deferred<Unit> {
        val state = current ?: return completedDrain()
        current = null
        state.accepting = false
        if (state.inFlight == 0) state.drain.complete(Unit)
        return state.drain
    }

    private fun release(state: OperationState<Session>) {
        val drained = synchronized(this) {
            check(state.inFlight > 0)
            state.inFlight--
            !state.accepting && state.inFlight == 0
        }
        if (drained) state.drain.complete(Unit)
    }

    private fun completedDrain(): Deferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }
}

internal fun <Socket : Any> runSessionOperationIfCurrent(
    lifecycle: AacpSessionLifecycle<Socket>,
    operations: SessionOperationEpoch<AacpSession<Socket>>,
    operation: SessionOperation<AacpSession<Socket>>,
    action: () -> Unit,
): Boolean {
    var performed = false
    operations.runIfCurrent(operation) {
        val lease = lifecycle.acquireOperation(operation.session) ?: return@runIfCurrent
        lease.run {
            action()
            performed = true
        }
    }
    return performed
}
