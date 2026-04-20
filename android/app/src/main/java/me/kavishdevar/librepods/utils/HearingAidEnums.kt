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

package me.kavishdevar.librepods.utils

import android.util.Log
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "HearingAidUtils"

data class HearingAidSettings(
    val leftEQ: FloatArray,
    val rightEQ: FloatArray,
    val leftAmplification: Float,
    val rightAmplification: Float,
    val leftTone: Float,
    val rightTone: Float,
    val leftConversationBoost: Boolean,
    val rightConversationBoost: Boolean,
    val leftAmbientNoiseReduction: Float,
    val rightAmbientNoiseReduction: Float,
    val netAmplification: Float,
    val balance: Float,
    val ownVoiceAmplification: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HearingAidSettings

        if (leftAmplification != other.leftAmplification) return false
        if (rightAmplification != other.rightAmplification) return false
        if (leftTone != other.leftTone) return false
        if (rightTone != other.rightTone) return false
        if (leftConversationBoost != other.leftConversationBoost) return false
        if (rightConversationBoost != other.rightConversationBoost) return false
        if (leftAmbientNoiseReduction != other.leftAmbientNoiseReduction) return false
        if (rightAmbientNoiseReduction != other.rightAmbientNoiseReduction) return false
        if (!leftEQ.contentEquals(other.leftEQ)) return false
        if (!rightEQ.contentEquals(other.rightEQ)) return false
        if (ownVoiceAmplification != other.ownVoiceAmplification) return false

        return true
    }

    override fun hashCode(): Int {
        var result = leftAmplification.hashCode()
        result = 31 * result + rightAmplification.hashCode()
        result = 31 * result + leftTone.hashCode()
        result = 31 * result + rightTone.hashCode()
        result = 31 * result + leftConversationBoost.hashCode()
        result = 31 * result + rightConversationBoost.hashCode()
        result = 31 * result + leftAmbientNoiseReduction.hashCode()
        result = 31 * result + rightAmbientNoiseReduction.hashCode()
        result = 31 * result + leftEQ.contentHashCode()
        result = 31 * result + rightEQ.contentHashCode()
        result = 31 * result + ownVoiceAmplification.hashCode()
        return result
    }
}

fun parseHearingAidSettingsResponse(data: ByteArray): HearingAidSettings? {
    if (data.size < 104) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    buffer.get() // skip 0x02
    buffer.get() // skip 0x02
    buffer.getShort() // skip 0x60 0x00

    val leftEQ = FloatArray(8)
    for (i in 0..7) {
        leftEQ[i] = buffer.float
    }
    val leftAmplification = buffer.float
    val leftTone = buffer.float
    val leftConvFloat = buffer.float
    val leftConversationBoost = leftConvFloat > 0.5f
    val leftAmbientNoiseReduction = buffer.float

    val rightEQ = FloatArray(8)
    for (i in 0..7) {
        rightEQ[i] = buffer.float
    }
    val rightAmplification = buffer.float
    val rightTone = buffer.float
    val rightConvFloat = buffer.float
    val rightConversationBoost = rightConvFloat > 0.5f
    val rightAmbientNoiseReduction = buffer.float

    val ownVoiceAmplification = buffer.float

    val avg = (leftAmplification + rightAmplification) / 2
    val amplification = avg.coerceIn(-1f, 1f)
    val diff = rightAmplification - leftAmplification
    val balance = diff.coerceIn(-1f, 1f)

    return HearingAidSettings(
        leftEQ = leftEQ,
        rightEQ = rightEQ,
        leftAmplification = leftAmplification,
        rightAmplification = rightAmplification,
        leftTone = leftTone,
        rightTone = rightTone,
        leftConversationBoost = leftConversationBoost,
        rightConversationBoost = rightConversationBoost,
        leftAmbientNoiseReduction = leftAmbientNoiseReduction,
        rightAmbientNoiseReduction = rightAmbientNoiseReduction,
        netAmplification = amplification,
        balance = balance,
        ownVoiceAmplification = ownVoiceAmplification
    )
}

fun sendHearingAidSettings(
    attManager: ATTManager,
    hearingAidSettings: HearingAidSettings,
    debounceJob: MutableState<Job?>
) {
    debounceJob.value?.cancel()
    debounceJob.value = CoroutineScope(Dispatchers.IO).launch {
        delay(100)
        try {
            val currentData = attManager.read(ATTHandles.HEARING_AID)
            Log.d(TAG, "Current data before update: ${currentData.joinToString(" ") { String.format("%02X", it) }}")
            if (currentData.size < 104) {
                Log.w(TAG, "Current data size ${currentData.size} too small, cannot send settings")
                return@launch
            }
            val buffer = ByteBuffer.wrap(currentData).order(ByteOrder.LITTLE_ENDIAN)

            // for some reason
            buffer.put(2, 0x64)

            // Left EQ
            for (i in 0..7) {
                buffer.putFloat(4 + i * 4, hearingAidSettings.leftEQ[i])
            }

            // Left ear adjustments
            buffer.putFloat(36, hearingAidSettings.leftAmplification)
            buffer.putFloat(40, hearingAidSettings.leftTone)
            buffer.putFloat(44, if (hearingAidSettings.leftConversationBoost) 1.0f else 0.0f)
            buffer.putFloat(48, hearingAidSettings.leftAmbientNoiseReduction)

            // Right EQ
            for (i in 0..7) {
                buffer.putFloat(52 + i * 4, hearingAidSettings.rightEQ[i])
            }

            // Right ear adjustments
            buffer.putFloat(84, hearingAidSettings.rightAmplification)
            buffer.putFloat(88, hearingAidSettings.rightTone)
            buffer.putFloat(92, if (hearingAidSettings.rightConversationBoost) 1.0f else 0.0f)
            buffer.putFloat(96, hearingAidSettings.rightAmbientNoiseReduction)

            // Own voice amplification
            buffer.putFloat(100, hearingAidSettings.ownVoiceAmplification)

            Log.d(TAG, "Sending updated settings: ${currentData.joinToString(" ") { String.format("%02X", it) }}")

            attManager.write(ATTHandles.HEARING_AID, currentData)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
