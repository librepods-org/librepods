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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.utils

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.services.ServiceManager
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class GestureDetector(
    private val airPodsService: AirPodsService
) {
    companion object {
        private const val TAG = "GestureDetector"

        private const val IMMEDIATE_FEEDBACK_THRESHOLD = 600
        private const val DIRECTION_CHANGE_SENSITIVITY = 150

        private const val FAST_MOVEMENT_THRESHOLD = 300.0
        private const val MIN_REQUIRED_EXTREMES = 3
        private const val MAX_REQUIRED_EXTREMES = 4

        private const val MAX_VALID_ORIENTATION_VALUE = 6000
    }

    val audio = GestureFeedback(ServiceManager.getService()?.baseContext!!)

    private val horizontalBuffer = Collections.synchronizedList(ArrayList<Double>())
    private val verticalBuffer = Collections.synchronizedList(ArrayList<Double>())

    private val horizontalAvgBuffer = Collections.synchronizedList(ArrayList<Double>())
    private val verticalAvgBuffer = Collections.synchronizedList(ArrayList<Double>())

    private var prevHorizontal: Double = 0.0
    private var prevVertical: Double = 0.0

    private val horizontalPeaks = CopyOnWriteArrayList<Triple<Int, Double, Long>>()
    private val horizontalTroughs = CopyOnWriteArrayList<Triple<Int, Double, Long>>()
    private val verticalPeaks = CopyOnWriteArrayList<Triple<Int, Double, Long>>()
    private val verticalTroughs = CopyOnWriteArrayList<Triple<Int, Double, Long>>()

    private var lastPeakTime: Long = 0
    private val peakIntervals = Collections.synchronizedList(ArrayList<Double>())

    private val movementSpeedIntervals = Collections.synchronizedList(ArrayList<Long>())

    private val peakThreshold = 400
    private val directionChangeThreshold = DIRECTION_CHANGE_SENSITIVITY
    private val rhythmConsistencyThreshold = 0.5

    private var horizontalIncreasing: Boolean? = null
    private var verticalIncreasing: Boolean? = null

    private val minConfidenceThreshold = 0.7

    private var isRunning = false
    private var detectionJob: Job? = null
    private var gestureDetectedCallback: ((Boolean) -> Unit)? = null

    private var significantMotion = false
    private var lastSignificantMotionTime = 0L

    init {
        while (horizontalAvgBuffer.size < 3) horizontalAvgBuffer.add(0.0)
        while (verticalAvgBuffer.size < 3) verticalAvgBuffer.add(0.0)
    }

fun startDetection(doNotStop: Boolean = false, onGestureDetected: (Boolean) -> Unit) {
        if (isRunning) return

        Log.d(TAG, "Starting gesture detection...")
        isRunning = true
        gestureDetectedCallback = onGestureDetected

        Log.d(TAG, "started: ${airPodsService.startHeadTracking()}")

        clearData()

        prevHorizontal = 0.0
        prevVertical = 0.0

        detectionJob = CoroutineScope(Dispatchers.Default).launch {
            while (isRunning) {
                delay(50)

                val gesture = detectGestures()
                if (gesture != null) {
                    withContext(Dispatchers.Main) {
                        audio.playConfirmation(gesture)

                        gestureDetectedCallback?.invoke(gesture)
                        stopDetection(doNotStop)
                    }
                    break
                }
            }
        }
    }
    fun stopDetection(doNotStop: Boolean = false) {
        if (!isRunning) return

        Log.d(TAG, "Stopping gesture detection")
        isRunning = false

        if (!doNotStop) airPodsService.stopHeadTracking()

        detectionJob?.cancel()
        detectionJob = null
        gestureDetectedCallback = null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun processHeadOrientation(horizontal: Int, vertical: Int) {
        if (!isRunning) return

        if (abs(horizontal) > MAX_VALID_ORIENTATION_VALUE || abs(vertical) > MAX_VALID_ORIENTATION_VALUE) {
            Log.d(TAG, "Ignoring likely calibration data: h=$horizontal, v=$vertical")
            return
        }

        val horizontalDelta = horizontal - prevHorizontal
        val verticalDelta = vertical - prevVertical

        val significantHorizontal = abs(horizontalDelta) > IMMEDIATE_FEEDBACK_THRESHOLD
        val significantVertical = abs(verticalDelta) > IMMEDIATE_FEEDBACK_THRESHOLD

        if (significantHorizontal && (!significantVertical || abs(horizontalDelta) > abs(verticalDelta))) {
            CoroutineScope(Dispatchers.Main).launch {
                audio.playDirectional(isVertical = false, value = horizontalDelta)
            }
            significantMotion = true
            lastSignificantMotionTime = System.currentTimeMillis()
            Log.d(TAG, "Significant HORIZONTAL movement: $horizontalDelta")
        }
        else if (significantVertical) {
            CoroutineScope(Dispatchers.Main).launch {
                audio.playDirectional(isVertical = true, value = verticalDelta)
            }
            significantMotion = true
            lastSignificantMotionTime = System.currentTimeMillis()
            Log.d(TAG, "Significant VERTICAL movement: $verticalDelta")
        }
        else if (significantMotion &&
                 (System.currentTimeMillis() - lastSignificantMotionTime) > 300) {
            significantMotion = false
        }

        prevHorizontal = horizontal.toDouble()
        prevVertical = vertical.toDouble()

        val smoothHorizontal = applySmoothing(horizontal.toDouble(), horizontalAvgBuffer)
        val smoothVertical = applySmoothing(vertical.toDouble(), verticalAvgBuffer)

        synchronized(horizontalBuffer) {
            horizontalBuffer.add(smoothHorizontal)
            if (horizontalBuffer.size > 100) horizontalBuffer.removeAt(0)
        }

        synchronized(verticalBuffer) {
            verticalBuffer.add(smoothVertical)
            if (verticalBuffer.size > 100) verticalBuffer.removeAt(0)
        }

        detectPeaksAndTroughs()
    }

    private fun applySmoothing(newValue: Double, buffer: MutableList<Double>): Double {
        synchronized(buffer) {
            buffer.add(newValue)
            if (buffer.size > 3) buffer.removeAt(0)
            return buffer.average()
        }
    }


    private fun detectPeaksAndTroughs() {
        if (horizontalBuffer.size < 4 || verticalBuffer.size < 4) return

        val hValues = horizontalBuffer.takeLast(4)
        val vValues = verticalBuffer.takeLast(4)
        val hVariance = calculateVariance(hValues)
        val vVariance = calculateVariance(vValues)

        processDirectionChanges(
            horizontalBuffer,
            horizontalIncreasing,
            hVariance,
            horizontalPeaks,
            horizontalTroughs
        )?.let { horizontalIncreasing = it }

        processDirectionChanges(
            verticalBuffer,
            verticalIncreasing,
            vVariance,
            verticalPeaks,
            verticalTroughs
        )?.let { verticalIncreasing = it }
    }

    private fun processDirectionChanges(
        buffer: List<Double>,
        isIncreasing: Boolean?,
        variance: Double,
        peaks: MutableList<Triple<Int, Double, Long>>,
        troughs: MutableList<Triple<Int, Double, Long>>
    ): Boolean? {
        if (buffer.size < 2) return isIncreasing

        val current = buffer.last()
        val prev = buffer[buffer.size - 2]
        var increasing = isIncreasing ?: (current > prev)

        val dynamicThreshold = max(50.0, min(directionChangeThreshold.toDouble(), variance / 3))

        val now = System.currentTimeMillis()

        if (increasing && current < prev - dynamicThreshold) {
            if (abs(prev) > peakThreshold) {
                peaks.add(Triple(buffer.size - 1, prev, now))
                if (lastPeakTime > 0) {
                    val interval = (now - lastPeakTime) / 1000.0
                    val timeDiff = now - lastPeakTime

                    synchronized(peakIntervals) {
                        peakIntervals.add(interval)
                        if (peakIntervals.size > 5) peakIntervals.removeAt(0)
                    }

                    synchronized(movementSpeedIntervals) {
                        movementSpeedIntervals.add(timeDiff)
                        if (movementSpeedIntervals.size > 5) movementSpeedIntervals.removeAt(0)
                    }
                }
                lastPeakTime = now
            }
            increasing = false
        } else if (!increasing && current > prev + dynamicThreshold) {
            if (abs(prev) > peakThreshold) {
                troughs.add(Triple(buffer.size - 1, prev, now))

                if (lastPeakTime > 0) {
                    val interval = (now - lastPeakTime) / 1000.0
                    val timeDiff = now - lastPeakTime

                    synchronized(peakIntervals) {
                        peakIntervals.add(interval)
                        if (peakIntervals.size > 5) peakIntervals.removeAt(0)
                    }

                    synchronized(movementSpeedIntervals) {
                        movementSpeedIntervals.add(timeDiff)
                        if (movementSpeedIntervals.size > 5) movementSpeedIntervals.removeAt(0)
                    }
                }
                lastPeakTime = now
            }
            increasing = true
        }

        return increasing
    }

    private fun calculateVariance(values: List<Double>): Double {
        if (values.size <= 1) return 0.0

        val mean = values.average()
        val squaredDiffs = values.map { (it - mean) * (it - mean) }
        return squaredDiffs.average()
    }


    private fun calculateRhythmConsistency(): Double {
        if (peakIntervals.size < 2) return 0.0

        val meanInterval = peakIntervals.average()
        if (meanInterval == 0.0) return 0.0

        val variances = peakIntervals.map { (it / meanInterval - 1.0).pow(2) }
        val consistency = 1.0 - min(1.0, variances.average() / rhythmConsistencyThreshold)
        return max(0.0, consistency)
    }


    private fun calculateConfidenceScore(extremes: List<Triple<Int, Double, Long>>, isVertical: Boolean): Double {
        if (extremes.size < getRequiredExtremes()) return 0.0

        val sortedExtremes = extremes.sortedBy { it.first }

        val recent = sortedExtremes.takeLast(getRequiredExtremes())

        val avgAmplitude = recent.map { abs(it.second) }.average()
        val amplitudeFactor = min(1.0, avgAmplitude / 600)

        val rhythmFactor = calculateRhythmConsistency()

        val signs = recent.map { if (it.second > 0) 1 else -1 }
        val alternating = (1 until signs.size).all { signs[it] != signs[it - 1] }
        val alternationFactor = if (alternating) 1.0 else 0.5

        val isolationFactor = if (isVertical) {
            val vertAmplitude = recent.map { abs(it.second) }.average()
            val horizVals = horizontalBuffer.takeLast(recent.size * 2)
            val horizAmplitude = horizVals.map { abs(it) }.average()
            min(1.0, vertAmplitude / (horizAmplitude + 0.1) * 1.2)
        } else {
            val horizAmplitude = recent.map { abs(it.second) }.average()
            val vertVals = verticalBuffer.takeLast(recent.size * 2)
            val vertAmplitude = vertVals.map { abs(it) }.average()
            min(1.0, horizAmplitude / (vertAmplitude + 0.1) * 1.2)
        }

        return (
            amplitudeFactor * 0.4 +
            rhythmFactor * 0.2 +
            alternationFactor * 0.2 +
            isolationFactor * 0.2
        )
    }

    private fun getRequiredExtremes(): Int {
        if (movementSpeedIntervals.isEmpty()) return MIN_REQUIRED_EXTREMES

        val avgInterval = movementSpeedIntervals.average()
        Log.d(TAG, "Average movement interval: $avgInterval ms")

        return if (avgInterval < FAST_MOVEMENT_THRESHOLD) {
            MAX_REQUIRED_EXTREMES
        } else {
            MIN_REQUIRED_EXTREMES
        }
    }

    private fun detectGestures(): Boolean? {
        val requiredExtremes = getRequiredExtremes()
        Log.d(TAG, "Current required extremes: $requiredExtremes")

        if (verticalPeaks.size + verticalTroughs.size >= requiredExtremes) {
            val allExtremes = (verticalPeaks + verticalTroughs).sortedBy { it.first }

            val confidence = calculateConfidenceScore(allExtremes, isVertical = true)

            Log.d(TAG, "Vertical motion confidence: $confidence (need $minConfidenceThreshold)")

            if (confidence >= minConfidenceThreshold) {
                Log.d(TAG, "\"Yes\" Gesture Detected (confidence: $confidence, extremes: ${allExtremes.size}/$requiredExtremes)")
                return true
            }
        }

        if (horizontalPeaks.size + horizontalTroughs.size >= requiredExtremes) {
            val allExtremes = (horizontalPeaks + horizontalTroughs).sortedBy { it.first }

            val confidence = calculateConfidenceScore(allExtremes, isVertical = false)

            Log.d(TAG, "Horizontal motion confidence: $confidence (need $minConfidenceThreshold)")

            if (confidence >= minConfidenceThreshold) {
                Log.d(TAG, "\"No\" Gesture Detected (confidence: $confidence, extremes: ${allExtremes.size}/$requiredExtremes)")
                return false
            }
        }

        return null
    }

    private fun clearData() {
        horizontalBuffer.clear()
        verticalBuffer.clear()
        horizontalPeaks.clear()
        horizontalTroughs.clear()
        verticalPeaks.clear()
        verticalTroughs.clear()
        peakIntervals.clear()
        movementSpeedIntervals.clear()
        horizontalIncreasing = null
        verticalIncreasing = null
        lastPeakTime = 0
        significantMotion = false
        lastSignificantMotionTime = 0L
    }

    private fun Double.pow(exponent: Int): Double = this.pow(exponent.toDouble())
}
