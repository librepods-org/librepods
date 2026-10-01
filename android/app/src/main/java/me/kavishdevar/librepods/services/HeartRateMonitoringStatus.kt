/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.services

import me.kavishdevar.librepods.bluetooth.HeartRateSample

enum class HeartRateMonitoringStatus {
    OFF,
    WAITING_FOR_AIRPODS,
    WAITING_TO_BE_WORN,
    STARTING,
    CALIBRATING,
    LIVE,
    RECONNECTING,
    COULDNT_START
}

data class HeartRateMonitoringState(
    val enabled: Boolean = false,
    val status: HeartRateMonitoringStatus = HeartRateMonitoringStatus.OFF,
    val samples: List<HeartRateSample> = emptyList()
) {
    val latestSample: HeartRateSample?
        get() = samples.lastOrNull()
}
