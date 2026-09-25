/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.services

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.bluetooth.HeartRateSample

/**
 * Owns the heart-rate stream lifecycle and its user-visible state.
 *
 * A single coroutine performs startup, warm-up, live sample collection, and in-stream retries.
 * RTBuddy callbacks only enqueue validated samples; they do not mutate status or run watchdogs.
 */
internal class HeartRateMonitor(
    private val scope: CoroutineScope,
    initiallyEnabled: Boolean,
    private val isTransportReady: () -> Boolean,
    private val isAirPodsWorn: () -> Boolean,
    private val beforeFirstStart: suspend () -> Unit,
    private val sendConnectService0: () -> Boolean,
    private val sendCapabilitiesService0: () -> Boolean,
    private val sendConnectService4: () -> Boolean,
    private val sendCapabilitiesService4: () -> Boolean,
    private val awaitHeartRateService: suspend () -> Boolean,
    private val enableHeartRate: () -> Boolean,
    private val sendStart: () -> Boolean,
    private val sendStop: () -> Unit,
    private val requestTransportRecovery: () -> Boolean,
    private val onPublishedSample: (HeartRateSample) -> Unit
) {
    private enum class RefreshReason(val diagnosticName: String) {
        FIRST_SAMPLE_TIMEOUT("first-sample-timeout"),
        STREAM_STALLED("stream-stalled")
    }

    private data class RefreshWindow(
        val reason: RefreshReason,
        var deadlineElapsedRealtime: Long,
        var attempts: Int = 0
    )

    private val lock = Any()
    private val incomingSamples = Channel<HeartRateSample>(Channel.UNLIMITED)
    private var monitoringJob: Job? = null
    private var sessionNeedsStop = false
    private var acceptingSamples = false

    private val _state = MutableStateFlow(
        HeartRateMonitoringState(
            enabled = initiallyEnabled,
            status = if (initiallyEnabled) {
                HeartRateMonitoringStatus.WAITING_FOR_AIRPODS
            } else {
                HeartRateMonitoringStatus.OFF
            }
        )
    )
    val state: StateFlow<HeartRateMonitoringState> = _state

    fun setEnabled(enabled: Boolean) {
        val wasEnabled = state.value.enabled
        val transportReady = isTransportReady()
        val airPodsWorn = isAirPodsWorn()
        updateState {
            it.copy(
                enabled = enabled,
                status = when {
                    !enabled -> HeartRateMonitoringStatus.OFF
                    !transportReady -> HeartRateMonitoringStatus.WAITING_FOR_AIRPODS
                    !airPodsWorn -> HeartRateMonitoringStatus.WAITING_TO_BE_WORN
                    !wasEnabled -> HeartRateMonitoringStatus.STARTING
                    else -> it.status
                }
            )
        }

        if (enabled) startIfPossible() else stop(forceStop = wasEnabled)
    }

    fun startIfPossible() {
        val currentState = state.value
        if (!currentState.enabled) {
            updateStatus(HeartRateMonitoringStatus.OFF)
            return
        }
        if (!isTransportReady()) {
            updateStatus(HeartRateMonitoringStatus.WAITING_FOR_AIRPODS)
            return
        }
        if (!isAirPodsWorn()) {
            updateStatus(HeartRateMonitoringStatus.WAITING_TO_BE_WORN)
            return
        }

        val job = synchronized(lock) {
            if (monitoringJob?.isActive == true) return

            updateStatus(HeartRateMonitoringStatus.STARTING)
            scope.launch(start = CoroutineStart.LAZY) { runMonitoringLoop() }
                .also { monitoringJob = it }
        }
        job.start()
    }

    fun onValidatedSample(sample: HeartRateSample) {
        val accepted = synchronized(lock) {
            state.value.enabled && acceptingSamples && isTransportReady()
        }
        if (accepted) incomingSamples.trySend(sample)
    }

    fun markReconnecting() {
        if (state.value.enabled) updateStatus(HeartRateMonitoringStatus.RECONNECTING)
    }

    fun onWearStateChanged(isWorn: Boolean) {
        if (isWorn) {
            startIfPossible()
        } else {
            stopAndUpdateStatus(
                forceStop = false,
                sendStopFrame = true,
                enabledStatus = HeartRateMonitoringStatus.WAITING_TO_BE_WORN
            )
        }
    }

    fun stop(forceStop: Boolean = false, sendStopFrame: Boolean = true) {
        stopAndUpdateStatus(
            forceStop = forceStop,
            sendStopFrame = sendStopFrame,
            enabledStatus = HeartRateMonitoringStatus.WAITING_FOR_AIRPODS
        )
    }

    private fun stopAndUpdateStatus(
        forceStop: Boolean,
        sendStopFrame: Boolean,
        enabledStatus: HeartRateMonitoringStatus
    ) {
        synchronized(lock) {
            val jobWasActive = monitoringJob?.isActive == true
            monitoringJob?.cancel()
            monitoringJob = null
            stopSessionLocked(forceStop || jobWasActive, sendStopFrame)
            drainIncomingSamples()
        }
        updateStatus(
            if (state.value.enabled) {
                enabledStatus
            } else {
                HeartRateMonitoringStatus.OFF
            }
        )
    }

    private suspend fun runMonitoringLoop() {
        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        var refreshWindow: RefreshWindow? = null

        try {
            beforeFirstStart()

            while (canRun()) {
                updateStatus(
                    if (refreshWindow == null) {
                        HeartRateMonitoringStatus.STARTING
                    } else {
                        HeartRateMonitoringStatus.RECONNECTING
                    }
                )

                val attemptStartedAt = startStreamAttempt()
                if (!canRun()) return
                if (attemptStartedAt != null) {
                    refreshWindow?.deadlineElapsedRealtime =
                        attemptStartedAt + FIRST_SAMPLE_TIMEOUT_MILLIS
                }

                val failure = if (attemptStartedAt == null) {
                    RefreshReason.FIRST_SAMPLE_TIMEOUT
                } else {
                    awaitStreamFailure(
                        attemptStartedAt = attemptStartedAt,
                        refreshDeadline = refreshWindow?.deadlineElapsedRealtime,
                        onStreamStarted = { refreshWindow = null }
                    ) ?: return
                }

                synchronized(lock) { stopSessionLocked() }
                if (!canRun()) return
                val window = refreshWindow ?: RefreshWindow(
                    reason = failure,
                    deadlineElapsedRealtime =
                        SystemClock.elapsedRealtime() + RECONNECT_WINDOW_MILLIS
                ).also {
                    refreshWindow = it
                    Log.i(
                        TAG,
                        "RTBuddy heart-rate refresh requested " +
                            "reason=${it.reason.diagnosticName} transport=healthy"
                    )
                }

                if (!waitForRetry(window)) {
                    Log.w(
                        TAG,
                        "RTBuddy heart-rate refresh failed " +
                            "reason=${window.reason.diagnosticName} attempts=${window.attempts}"
                    )
                    updateStatus(HeartRateMonitoringStatus.RECONNECTING)
                    if (requestTransportRecovery()) {
                        Log.i(TAG, "Requesting one automatic AACP rebuild for heart-rate recovery")
                    } else if (!canRun()) {
                        return
                    } else {
                        updateStatus(HeartRateMonitoringStatus.COULDNT_START)
                        Log.i(TAG, "Automatic AACP rebuild unavailable; waiting for manual Retry")
                    }
                    return
                }
            }
        } finally {
            synchronized(lock) {
                if (monitoringJob === currentJob) {
                    stopSessionLocked()
                    monitoringJob = null
                }
            }
        }
    }

    private suspend fun startStreamAttempt(): Long? {
        drainIncomingSamples()
        if (!initializeAacpSession()) return null
        if (!awaitHeartRateService()) return null
        val enabled = synchronized(lock) {
            canRun() && enableHeartRate().also { sent ->
                if (sent) sessionNeedsStop = true
            }
        }
        if (!enabled) return null

        delay(START_COMMAND_DELAY_MILLIS)

        return synchronized(lock) {
            if (!canRun()) {
                null
            } else {
                val startedAt = SystemClock.elapsedRealtime()
                val started = sendStart()
                acceptingSamples = started
                sessionNeedsStop = sessionNeedsStop || started
                Log.d(TAG, "RTBuddy heart-rate start sent=$started")
                startedAt.takeIf { started }
            }
        }
    }

    private suspend fun initializeAacpSession(): Boolean {
        val frames = listOf(
            sendConnectService0 to 180L,
            sendCapabilitiesService0 to 220L,
            sendConnectService4 to 180L,
            sendCapabilitiesService4 to 220L
        )

        for ((sendFrame, delayAfter) in frames) {
            if (!sendIfRunning(sendFrame)) return false
            delay(delayAfter)
        }

        Log.d(TAG, "RTBuddy heart-rate AACP 1.3 session initialized")
        return canRun()
    }

    private suspend fun awaitStreamFailure(
        attemptStartedAt: Long,
        refreshDeadline: Long?,
        onStreamStarted: () -> Unit
    ): RefreshReason? {
        var warmupSamplesRemaining = WARMUP_SAMPLE_COUNT
        var streamStarted = false
        val firstSampleDeadline = refreshDeadline
            ?: (attemptStartedAt + FIRST_SAMPLE_TIMEOUT_MILLIS)

        while (canRun()) {
            val timeout = if (streamStarted) {
                STALL_TIMEOUT_MILLIS
            } else {
                (firstSampleDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            }
            if (timeout == 0L) return RefreshReason.FIRST_SAMPLE_TIMEOUT

            val sample = withTimeoutOrNull(timeout) { incomingSamples.receive() }
                ?: return if (streamStarted) {
                    RefreshReason.STREAM_STALLED
                } else {
                    RefreshReason.FIRST_SAMPLE_TIMEOUT
                }

            if (!canRun()) return null
            if (!streamStarted) {
                streamStarted = true
                if (refreshDeadline != null) {
                    Log.i(TAG, "RTBuddy heart-rate reconnect succeeded")
                }
                onStreamStarted()
            }
            if (warmupSamplesRemaining > 0) {
                warmupSamplesRemaining--
                updateStatus(HeartRateMonitoringStatus.CALIBRATING)
                continue
            }

            publish(sample)
            updateStatus(HeartRateMonitoringStatus.LIVE)
        }
        return null
    }

    private fun waitForRetry(window: RefreshWindow): Boolean {
        if (window.attempts >= MAX_RECONNECT_ATTEMPTS) return false

        window.attempts++
        updateStatus(HeartRateMonitoringStatus.RECONNECTING)
        Log.w(
            TAG,
            "RTBuddy heart-rate reconnect attempt=${window.attempts} " +
                "reason=${window.reason.diagnosticName} timeout=${FIRST_SAMPLE_TIMEOUT_MILLIS}ms"
        )
        return canRun()
    }

    private fun publish(sample: HeartRateSample) {
        updateState { current ->
            current.copy(samples = (current.samples + sample).takeLast(MAX_SAMPLES))
        }
        onPublishedSample(sample)
    }

    private fun sendIfRunning(sendFrame: () -> Boolean): Boolean = synchronized(lock) {
        canRun() && sendFrame()
    }

    private fun stopSessionLocked(
        forceStop: Boolean = false,
        sendStopFrame: Boolean = true
    ) {
        val shouldStop = forceStop || sessionNeedsStop || acceptingSamples
        sessionNeedsStop = false
        acceptingSamples = false
        if (sendStopFrame && shouldStop && isTransportReady()) sendStop()
    }

    private fun canRun(): Boolean =
        state.value.enabled && isTransportReady() && isAirPodsWorn()

    private fun updateStatus(status: HeartRateMonitoringStatus) {
        updateState { it.copy(status = status) }
    }

    private inline fun updateState(
        transform: (HeartRateMonitoringState) -> HeartRateMonitoringState
    ) {
        synchronized(lock) {
            _state.value = transform(_state.value)
        }
    }

    private fun drainIncomingSamples() {
        while (incomingSamples.tryReceive().isSuccess) Unit
    }

    private companion object {
        const val TAG = "HeartRateMonitor"
        const val MAX_SAMPLES = 60
        const val FIRST_SAMPLE_TIMEOUT_MILLIS = 8_000L
        const val RECONNECT_WINDOW_MILLIS = FIRST_SAMPLE_TIMEOUT_MILLIS
        const val STALL_TIMEOUT_MILLIS = 2_000L
        const val START_COMMAND_DELAY_MILLIS = 120L
        const val WARMUP_SAMPLE_COUNT = 4
        const val MAX_RECONNECT_ATTEMPTS = 1
    }
}
