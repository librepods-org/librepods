package me.kavishdevar.librepods.core.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.core.data.apple.AppleMotionReport
import me.kavishdevar.librepods.core.data.headtracking.NeckPosture
import kotlin.math.atan2
import kotlin.math.sqrt

object PostureTracker {
    private var neutralPitchDeg: Float = 0f
    private var neutralRollDeg: Float = 0f

    private val _neckPosture = MutableStateFlow(NeckPosture(0f, 0f, 0f, false))
    val neckPosture = _neckPosture.asStateFlow()

    fun calibrateUpright(report: AppleMotionReport) {
        val (pitch, roll) = computeAbsoluteGravityAngles(report.filteredAcceleration)
        neutralPitchDeg = pitch
        neutralRollDeg = roll
    }

    fun evaluatePosture(
        report: AppleMotionReport,
        slouchThresholdDeg: Int = 20
    ) {
        val (absPitch, absRoll) = computeAbsoluteGravityAngles(report.filteredAcceleration)
        val flexion = absPitch - neutralPitchDeg

        _neckPosture.value = NeckPosture(
            absolutePitchDeg = absPitch,
            absoluteRollDeg = absRoll,
            neckFlexionDeg = flexion,
            isSlouching = flexion > slouchThresholdDeg
        )
    }

    private fun computeAbsoluteGravityAngles(filteredAccel: List<Float>): Pair<Float, Float> {
        val (gx, gy, gz) = filteredAccel

        val gDown = (gx * 0.71f) + (gy * 0.70f)
        val gForward = gz
        val gSide = (-gx * 0.70f) + (gy * 0.71f)

        val absolutePitchDeg = Math.toDegrees(
            atan2(gForward.toDouble(), sqrt((gDown * gDown + gSide * gSide).toDouble()))
        ).toFloat()

        val absoluteRollDeg = Math.toDegrees(
            atan2(gSide.toDouble(), gDown.toDouble())
        ).toFloat()

        return Pair(absolutePitchDeg, absoluteRollDeg)
    }
}
