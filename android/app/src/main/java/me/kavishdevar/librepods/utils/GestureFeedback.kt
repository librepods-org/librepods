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

@file:Suppress("PrivatePropertyName")

package me.kavishdevar.librepods.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import me.kavishdevar.librepods.R
import java.util.concurrent.atomic.AtomicBoolean

class GestureFeedback(context: Context) {

    private val TAG = "GestureFeedback"

    private val soundsLoaded = AtomicBoolean(false)

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()
        )
        .build()


    private var soundId = 0
    private var confirmYesId = 0
    private var confirmNoId = 0

    private var lastHorizontalTime = 0L
    private var lastLeftTime = 0L
    private var lastRightTime = 0L

    private var lastVerticalTime = 0L
    private var lastUpTime = 0L
    private var lastDownTime = 0L

    private val MIN_TIME_BETWEEN_SOUNDS = 150L
    private val MIN_TIME_BETWEEN_DIRECTION = 200L

    private var currentHorizontalStreamId = 0
    private var currentVerticalStreamId = 0


    private val LEFT_VOLUME = Pair(1.0f, 0.0f)
    private val RIGHT_VOLUME = Pair(0.0f, 1.0f)
    private val VERTICAL_VOLUME = Pair(1.0f, 1.0f)

    init {
        soundId = soundPool.load(context, R.raw.blip_no, 1)
        confirmYesId = soundPool.load(context, R.raw.confirm_yes, 1)
        confirmNoId = soundPool.load(context, R.raw.confirm_no, 1)

        soundPool.setOnLoadCompleteListener { _, _, _ ->
            Log.d(TAG, "Sounds loaded")
            soundsLoaded.set(true)

            soundPool.play(soundId, 0.0f, 0.0f, 1, 0, 1.0f)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun playDirectional(isVertical: Boolean, value: Double) {
        if (!soundsLoaded.get()) {
            Log.d(TAG, "Sounds not yet loaded, skipping playback")
            return
        }

        val now = SystemClock.uptimeMillis()

        if (isVertical) {
            val isUp = value > 0

            if (now - lastVerticalTime < MIN_TIME_BETWEEN_SOUNDS) {
                Log.d(TAG, "Skipping vertical sound due to general vertical debounce")
                return
            }

            if (isUp && now - lastUpTime < MIN_TIME_BETWEEN_DIRECTION) {
                Log.d(TAG, "Skipping UP sound due to direction debounce")
                return
            }

            if (!isUp && now - lastDownTime < MIN_TIME_BETWEEN_DIRECTION) {
                Log.d(TAG, "Skipping DOWN sound due to direction debounce")
                return
            }

            if (currentVerticalStreamId > 0) {
                soundPool.stop(currentVerticalStreamId)
            }

            val (leftVol, rightVol) = VERTICAL_VOLUME

            currentVerticalStreamId = soundPool.play(soundId, leftVol, rightVol, 1, 0, 1.0f)
            Log.d(TAG, "Playing VERTICAL sound: ${if (isUp) "UP" else "DOWN"} - streamID=$currentVerticalStreamId")

            lastVerticalTime = now
            if (isUp) {
                lastUpTime = now
            } else {
                lastDownTime = now
            }
        } else {
            if (now - lastHorizontalTime < MIN_TIME_BETWEEN_SOUNDS) {
                Log.d(TAG, "Skipping horizontal sound due to general horizontal debounce")
                return
            }

            val isRight = value > 0

            if (isRight && now - lastRightTime < MIN_TIME_BETWEEN_DIRECTION) {
                Log.d(TAG, "Skipping RIGHT sound due to direction debounce")
                return
            }

            if (!isRight && now - lastLeftTime < MIN_TIME_BETWEEN_DIRECTION) {
                Log.d(TAG, "Skipping LEFT sound due to direction debounce")
                return
            }

            if (currentHorizontalStreamId > 0) {
                soundPool.stop(currentHorizontalStreamId)
            }

            val (leftVol, rightVol) = if (isRight) RIGHT_VOLUME else LEFT_VOLUME

            currentHorizontalStreamId = soundPool.play(soundId, leftVol, rightVol, 1, 0, 1.0f)
            Log.d(TAG, "Playing HORIZONTAL sound: ${if (isRight) "RIGHT" else "LEFT"} - streamID=$currentHorizontalStreamId")

            lastHorizontalTime = now
            if (isRight) {
                lastRightTime = now
            } else {
                lastLeftTime = now
            }
        }
    }

    fun playConfirmation(isYes: Boolean) {
        if (currentHorizontalStreamId > 0) {
            soundPool.stop(currentHorizontalStreamId)
        }
        if (currentVerticalStreamId > 0) {
            soundPool.stop(currentVerticalStreamId)
        }

        val soundId = if (isYes) confirmYesId else confirmNoId
        if (soundId != 0 && soundsLoaded.get()) {
            val streamId = soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
            Log.d(TAG, "Playing ${if (isYes) "YES" else "NO"} confirmation - streamID=$streamId")
        }
    }
}
