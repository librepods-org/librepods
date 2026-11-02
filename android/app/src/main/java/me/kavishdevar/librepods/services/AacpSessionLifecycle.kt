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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class AacpConnectionAttempt(val generation: Long)

internal data class AacpSession<Socket : Any>(
    val attempt: AacpConnectionAttempt,
    val socket: Socket,
)

internal class AacpOperationLease internal constructor(
    private val release: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun <Result> run(action: () -> Result): Result = try {
        action()
    } finally {
        close()
    }

    fun close() {
        if (released.compareAndSet(false, true)) {
            release()
        }
    }
}

internal data class DisconnectedAacpLifecycle<Socket : Any>(
    val pendingSocket: Socket?,
    val activeSocket: Socket?,
    val cleanupToken: Long?,
    private val cleanupSignal: CountDownLatch? = null,
) {
    val endedActiveSession: Boolean
        get() = activeSocket != null

    fun awaitExistingCleanup(timeoutMillis: Long): Boolean {
        val signal = cleanupSignal ?: return true
        return try {
            signal.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

internal class AacpSessionLifecycle<Socket : Any> {
    private data class Pending<Socket : Any>(
        val attempt: AacpConnectionAttempt,
        var socket: Socket? = null,
    )

    private data class Active<Socket : Any>(
        val attempt: AacpConnectionAttempt,
        val socket: Socket,
    )

    private var generation = 0L
    private var cleanupGeneration: Long? = null
    private var cleanupLatch: CountDownLatch? = null
    private var cleanupCompletionRequested = false
    private var inFlightOperations = 0
    private var operationDrain = completedDrain()
    private var pending: Pending<Socket>? = null
    private var active: Active<Socket>? = null

    private fun beginCleanup(generation: Long) {
        check(cleanupGeneration == null)
        cleanupGeneration = generation
        cleanupLatch = CountDownLatch(1)
        cleanupCompletionRequested = false
        operationDrain = CompletableDeferred<Unit>().also { drain ->
            if (inFlightOperations == 0) drain.complete(Unit)
        }
    }

    private fun completedDrain(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }

    private fun finishCleanupIfDrained() {
        if (!cleanupCompletionRequested || inFlightOperations != 0) return

        val completedLatch = cleanupLatch
        cleanupGeneration = null
        cleanupLatch = null
        cleanupCompletionRequested = false
        completedLatch?.countDown()
    }

    @Synchronized
    fun beginAttempt(): AacpConnectionAttempt? {
        if (pending != null || active != null || cleanupGeneration != null) return null

        val attempt = AacpConnectionAttempt(++generation)
        pending = Pending(attempt)
        return attempt
    }

    @Synchronized
    fun attachSocket(attempt: AacpConnectionAttempt, socket: Socket): Boolean {
        val current = pending ?: return false
        if (current.attempt != attempt || current.socket != null) return false

        current.socket = socket
        return true
    }

    @Synchronized
    fun promote(attempt: AacpConnectionAttempt, socket: Socket): Boolean {
        val current = pending ?: return false
        if (current.attempt != attempt || current.socket !== socket || active != null) return false

        active = Active(attempt, socket)
        pending = null
        return true
    }

    @Synchronized
    fun failAttempt(attempt: AacpConnectionAttempt, socket: Socket? = null): Boolean {
        val current = pending ?: return false
        if (current.attempt != attempt || current.socket !== socket) return false

        pending = null
        beginCleanup(attempt.generation)
        return true
    }

    @Synchronized
    fun endSession(attempt: AacpConnectionAttempt, socket: Socket): Boolean {
        val current = active ?: return false
        if (current.attempt != attempt || current.socket !== socket) return false

        active = null
        beginCleanup(attempt.generation)
        return true
    }

    @Synchronized
    fun disconnect(): DisconnectedAacpLifecycle<Socket> {
        if (cleanupGeneration != null) {
            return DisconnectedAacpLifecycle(
                pendingSocket = null,
                activeSocket = null,
                cleanupToken = null,
                cleanupSignal = cleanupLatch,
            )
        }

        generation++
        beginCleanup(generation)
        val disconnected = DisconnectedAacpLifecycle(
            pendingSocket = pending?.socket,
            activeSocket = active?.socket,
            cleanupToken = generation,
            cleanupSignal = cleanupLatch,
        )
        pending = null
        active = null
        return disconnected
    }

    @Synchronized
    fun operationDrainSignal(cleanupToken: Long?): Deferred<Unit>? {
        if (cleanupToken == null || cleanupGeneration != cleanupToken) return null
        return operationDrain
    }

    @Synchronized
    fun completeCleanup(cleanupToken: Long?): Boolean {
        if (cleanupToken == null || cleanupGeneration != cleanupToken) return false

        cleanupCompletionRequested = true
        finishCleanupIfDrained()
        return true
    }

    @Synchronized
    fun acquireOperation(session: AacpSession<Socket>): AacpOperationLease? {
        val current = active ?: return null
        if (current.attempt != session.attempt || current.socket !== session.socket) return null

        inFlightOperations++
        return AacpOperationLease(::releaseOperation)
    }

    @Synchronized
    private fun releaseOperation() {
        check(inFlightOperations > 0)
        inFlightOperations--
        if (cleanupGeneration != null && inFlightOperations == 0) {
            operationDrain.complete(Unit)
        }
        finishCleanupIfDrained()
    }

    @Synchronized
    fun isPending(attempt: AacpConnectionAttempt, socket: Socket): Boolean =
        pending?.let { it.attempt == attempt && it.socket === socket } == true

    @Synchronized
    fun isActive(attempt: AacpConnectionAttempt, socket: Socket): Boolean =
        active?.let { it.attempt == attempt && it.socket === socket } == true

    @Synchronized
    fun isActive(session: AacpSession<Socket>): Boolean =
        active?.let { it.attempt == session.attempt && it.socket === session.socket } == true

    @Synchronized
    fun activeSession(): AacpSession<Socket>? = active?.let {
        AacpSession(it.attempt, it.socket)
    }

    @Synchronized
    fun activeSocket(): Socket? = active?.socket

    @Synchronized
    fun hasPendingAttempt(): Boolean = pending != null

    @get:Synchronized
    val isConnected: Boolean
        get() = active != null
}
