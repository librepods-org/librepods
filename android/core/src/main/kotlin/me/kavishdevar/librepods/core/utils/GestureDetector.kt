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

package me.kavishdevar.librepods.core.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.core.data.apple.AppleMotionReport
import kotlin.math.abs

class GestureDetector {
    companion object {
        private const val TAG = "GestureDetector"

        private const val PEAK_VELOCITY_THRESHOLD = 0.85f
        private const val FEEDBACK_VELOCITY_THRESHOLD = 1.2f
        private const val DOMINANCE_RATIO = 1.35f

        private const val MIN_HALF_CYCLE_MS = 80L
        private const val MAX_HALF_CYCLE_MS = 600L
        private const val GESTURE_TIMEOUT_MS = 800L

        private const val REQUIRED_ALTERNATIONS = 4
    }

    private data class Extreme(
        val isVertical: Boolean,
        val sign: Int,
        val peakValue: Float,
        val timestampMs: Long
    )

    private val extremes = ArrayList<Extreme>()
    private var currentVertPeak = 0f
    private var currentHorizPeak = 0f
    private var lastFeedbackTimeMs = 0L

    private var isRunning = false
    private var processingJob: Job? = null

    private var wasVertAboveThreshold = false
    private var wasHorizAboveThreshold = false

    fun startDetection(
        motionReports: StateFlow<AppleMotionReport?>,
        callback: (Boolean) -> Unit
    ): Boolean {
        if (isRunning) return false

        Log.d(TAG, "Starting gesture detection...")
        isRunning = true
        resetState()

        processingJob = CoroutineScope(Dispatchers.Default).launch {
            motionReports.filterNotNull().collect { report ->
                val result = processReport(report)
                if (result != null) {
                    withContext(Dispatchers.Main) {
                        GestureFeedback.playConfirmation(result)
                        callback(result)
                        stopDetection()
                    }
                }
            }
        }
        return true
    }

    fun stopDetection() {
        if (!isRunning) return
        Log.d(TAG, "Stopping gesture detection")
        isRunning = false
        processingJob?.cancel()
        processingJob = null
    }

    private fun processReport(report: AppleMotionReport): Boolean? {
        if (!isRunning || report.dropped) return null

        val q = report.quaternion
        if (q[0] == 0f && q[1] == 0f && q[2] == 0f && q[3] == 1f) return null

        val now = report.sensorTimestampUs.toLong() / 1000L
        val (rx, ry, rz) = report.rotationRate

        val vertVel = rz
        val horizVel = (rx * 0.78f) + (ry * 0.62f)

        if (extremes.isNotEmpty() && (now - extremes.last().timestampMs) > GESTURE_TIMEOUT_MS) {
            extremes.clear()
            currentVertPeak = 0f
            currentHorizPeak = 0f
        }

        triggerImmediateFeedback(vertVel, horizVel, now)

        if (abs(vertVel) > abs(currentVertPeak)) currentVertPeak = vertVel
        if (abs(horizVel) > abs(currentHorizPeak)) currentHorizPeak = horizVel

        val vertLobeEnded = abs(currentVertPeak) >= PEAK_VELOCITY_THRESHOLD &&
            (vertVel * currentVertPeak <= 0f || abs(vertVel) < abs(currentVertPeak) * 0.4f)

        val horizLobeEnded = abs(currentHorizPeak) >= PEAK_VELOCITY_THRESHOLD &&
            (horizVel * currentHorizPeak <= 0f || abs(horizVel) < abs(currentHorizPeak) * 0.4f)

        if (vertLobeEnded && abs(currentVertPeak) > abs(currentHorizPeak) * DOMINANCE_RATIO) {
            recordExtreme(
                isVertical = true,
                sign = if (currentVertPeak > 0) 1 else -1,
                peak = abs(currentVertPeak),
                now = now
            )
            currentVertPeak = 0f
            currentHorizPeak = 0f
        } else if (horizLobeEnded && abs(currentHorizPeak) > abs(currentVertPeak) * DOMINANCE_RATIO) {
            recordExtreme(
                isVertical = false,
                sign = if (currentHorizPeak > 0) 1 else -1,
                peak = abs(currentHorizPeak),
                now = now
            )
            currentHorizPeak = 0f
            currentVertPeak = 0f
        }

        return evaluateExtremes()
    }

    private fun recordExtreme(isVertical: Boolean, sign: Int, peak: Float, now: Long) {
        val last = extremes.lastOrNull()

        if (last != null) {
            val dt = now - last.timestampMs
            if (last.isVertical != isVertical || dt > MAX_HALF_CYCLE_MS) {
                extremes.clear()
            } else if (last.sign == sign) {
                if (peak > last.peakValue) {
                    extremes[extremes.lastIndex] = Extreme(isVertical, sign, peak, now)
                }
                return
            } else if (dt < MIN_HALF_CYCLE_MS) {
                return
            }
        }

        extremes.add(Extreme(isVertical, sign, peak, now))
        Log.d(TAG, "Recorded extreme: vertical=$isVertical, sign=$sign, peak=$peak, count=${extremes.size}")
    }

    private fun evaluateExtremes(): Boolean? {
        if (extremes.size < REQUIRED_ALTERNATIONS) return null

        val isVertical = extremes.last().isVertical
        Log.d(TAG, if (isVertical) "\"Yes\" Gesture Detected" else "\"No\" Gesture Detected")
        return isVertical
    }

    private fun triggerImmediateFeedback(vertVel: Float, horizVel: Float, now: Long) {
        val vertAboveThreshold =
            abs(vertVel) > FEEDBACK_VELOCITY_THRESHOLD &&
                abs(vertVel) > abs(horizVel) * DOMINANCE_RATIO

        val horizAboveThreshold =
            abs(horizVel) > FEEDBACK_VELOCITY_THRESHOLD &&
                abs(horizVel) > abs(vertVel) * DOMINANCE_RATIO

        if (now - lastFeedbackTimeMs >= 200L) {
            if (vertAboveThreshold && !wasVertAboveThreshold) {
                lastFeedbackTimeMs = now
                CoroutineScope(Dispatchers.Main).launch {
                    GestureFeedback.playDirectional(
                        isVertical = true,
                        value = vertVel * 512.0
                    )
                }
            } else if (horizAboveThreshold && !wasHorizAboveThreshold) {
                lastFeedbackTimeMs = now
                CoroutineScope(Dispatchers.Main).launch {
                    GestureFeedback.playDirectional(
                        isVertical = false,
                        value = horizVel * 512.0
                    )
                }
            }
        }

        wasVertAboveThreshold = vertAboveThreshold
        wasHorizAboveThreshold = horizAboveThreshold
    }

    private fun resetState() {
        extremes.clear()
        currentVertPeak = 0f
        currentHorizPeak = 0f
        lastFeedbackTimeMs = 0L
        wasVertAboveThreshold = false
        wasHorizAboveThreshold = false
    }
}
