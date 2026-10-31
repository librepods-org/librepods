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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.core.data.apple.AppleMotionReport
import kotlin.math.atan2
import kotlin.math.sqrt

object HeadTracking {
    private const val TAG = "AppleMotionReport"
    private const val SERVICE_HEADER_SIZE = 9
    private const val LEGACY_VERSION = 3
    private const val LEGACY_REPORT_SIZE = 49
    private const val COMPACT_REPORT_SIZE = 47
    private const val DROP_FLAG = 0x0080
    private const val GYRO_BIAS_FLAG = 0x0200

    private val _motionReport = MutableStateFlow<AppleMotionReport?>(null)
    val motionReport = _motionReport.asStateFlow()

    private val _referenceQuaternion = MutableStateFlow<FloatArray?>(null)
    val referenceQuaternion = _referenceQuaternion.asStateFlow()

    private val _headQuaternion = MutableStateFlow(floatArrayOf(0f, 0f, 0f, 1f))
    val headQuaternion = _headQuaternion.asStateFlow()

    fun recenter() {
        _motionReport.value?.quaternion?.let { rawQ ->
            if (!(rawQ[0] == 0f && rawQ[1] == 0f && rawQ[2] == 0f && rawQ[3] == 1f)) {
                _referenceQuaternion.value = rawQ.toFloatArray()
            }
        }
    }

    fun updateFromPayload(payload: ByteArray): Boolean {
        if (payload.size < SERVICE_HEADER_SIZE) {
            Log.w(TAG, "Motion payload too short for service header: ${payload.size} bytes")
            return false
        }

        val report = payload.copyOfRange(SERVICE_HEADER_SIZE, payload.size)
        if (report.isEmpty()) return false
        val version = report[0].toInt() and 0xFF
        val validSize = (version == LEGACY_VERSION && report.size == LEGACY_REPORT_SIZE) ||
            (version >= 4 && report.size == COMPACT_REPORT_SIZE)
        if (!validSize) {
            Log.w(TAG, "Unknown motion report layout: version=$version, size=${report.size}, payload=${payload.toHexString()}")
            return false
        }

        fun u8(offset: Int) = report[offset].toInt() and 0xFF
        fun u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
        fun s16(offset: Int): Int = u16(offset).let { if (it and 0x8000 != 0) it - 0x10000 else it }
        fun u32(offset: Int): UInt = u16(offset).toUInt() or (u16(offset + 2).toUInt() shl 16)
        fun s8(offset: Int): Int = report[offset].toInt()
        fun vector16(offset: Int, scale: Float): List<Float> =
            (0..2).map { s16(offset + it * 2) * scale }

        val flags = u16(1)
        val timestamp = u32(3).toULong() or (u32(7).toULong() shl 32)
        val sequence = u16(45)
        val dropped = flags and DROP_FLAG != 0

        val qx = s16(11) / 32767f
        val qy = s16(13) / 32767f
        val qz = s16(15) / 32767f
        val qw = sqrt((1f - qx * qx - qy * qy - qz * qz).coerceAtLeast(0f))
        val norm = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        val q = if (norm == 0f) listOf(0f, 0f, 0f, 1f)
        else listOf(qx / norm, qy / norm, qz / norm, qw / norm)
        val (x, y, z, w) = q

        val pitch = Math.toDegrees(atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y))).toFloat()
        val yaw = Math.toDegrees(atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))).toFloat()

        val covariance = if (version < 5) {
            (0..2).map { u16(23 + it * 2) * (1f / 8388608f) }
        } else {
            val value = u16(23) * (1f / 8388608f)
            listOf(value, value, value)
        }
        val hasAuxiliaryStatus = version > 5
        val auxiliaryStatus = if (hasAuxiliaryStatus) u8(25) else null
        val rawGyroBias = listOf(s8(26), s8(27), s8(28))
        val hasGyroBias = version >= 5 && rawGyroBias.any { it != 0 } && flags and GYRO_BIAS_FLAG != 0
        val gyroBias = if (version >= 5) rawGyroBias.map { it / 127f } else null
        val noise = Float.fromBits(u32(41).toInt())
        val quiescentMode = when {
            flags and 0x4000 != 0 -> 1
            flags and 0x8000 != 0 -> 2
            else -> 0
        }

        val parsed = AppleMotionReport(
            version = version,
            flags = flags,
            sensorTimestampUs = timestamp,
            quaternion = q,
            rotationRate = vector16(17, 1f / 512f),
            biasCovariance = covariance,
            auxiliaryStatus = auxiliaryStatus,
            gyroBias = if (hasGyroBias) gyroBias else null,
            userAcceleration = vector16(29, 1f / 1024f),
            filteredAcceleration = vector16(35, 1f / 1024f),
            quiescentNoise = noise,
            quiescentMode = quiescentMode,
            sequence = sequence,
            pitchDegrees = pitch,
            yawDegrees = yaw,
            dropped = dropped
        )

        val serviceHeader = payload.copyOfRange(0, SERVICE_HEADER_SIZE)
        Log.d(TAG, "serviceHeader=${serviceHeader.toHexString()} ${parsed.toDebugString()}")
        if (dropped) return false
        _motionReport.value = parsed

        val rawQ = parsed.quaternion
        val isIdentity = rawQ.size == 4 && rawQ[0] == 0f && rawQ[1] == 0f && rawQ[2] == 0f && rawQ[3] == 1f

        if (!isIdentity) {
            if (_referenceQuaternion.value == null) {
                _referenceQuaternion.value = rawQ.toFloatArray()
            }
            val r = _referenceQuaternion.value!!
            val rel = multiplyQuaternions(
                listOf(-r[0], -r[1], -r[2], r[3]),
                rawQ
            )
            val sx = rel[0]
            val sy = rel[1]
            val sz = rel[2]
            val sw = rel[3]

            val hx = -sz
            val hy = -(sx * 0.78f + sy * 0.62f)
            val hz = (-sx * 0.62f + sy * 0.78f)

            _headQuaternion.value = floatArrayOf(hx, hy, hz, sw)
        }

        PostureTracker.evaluatePosture(parsed)

        return true
    }

    fun reset() {
        _motionReport.value = null
    }
}


private fun multiplyQuaternions(q1: List<Float>, q2: List<Float>): List<Float> {
    val (x1, y1, z1, w1) = q1
    val (x2, y2, z2, w2) = q2
    return listOf(
        w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
        w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
        w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
        w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2
    )
}
