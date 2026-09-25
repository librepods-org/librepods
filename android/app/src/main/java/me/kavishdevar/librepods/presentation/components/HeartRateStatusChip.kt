/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.presentation.components

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.bluetooth.HeartRateSample
import me.kavishdevar.librepods.services.HeartRateMonitoringStatus

@Composable
fun HeartRateStatusChip(
    status: HeartRateMonitoringStatus,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val containerColor = when (status) {
        HeartRateMonitoringStatus.LIVE -> MaterialTheme.colorScheme.primaryContainer
        HeartRateMonitoringStatus.COULDNT_START -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (status) {
        HeartRateMonitoringStatus.LIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        HeartRateMonitoringStatus.COULDNT_START -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = if (onRetry == null) modifier else modifier.clickable(onClick = onRetry),
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = if (onRetry == null) status.label else "${status.label} · Retry",
            style = if (compact) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.labelLarge
            },
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 4.dp else 7.dp
            )
        )
    }
}

@Composable
fun rememberHeartRateSampleIsDisplayable(
    sample: HeartRateSample?,
    monitoringStatus: HeartRateMonitoringStatus
): Boolean {
    val statusAllowsDisplay = monitoringStatus.allowsSampleDisplay
    var sampleIsFresh by remember(sample?.receivedAtElapsedRealtime, statusAllowsDisplay) {
        mutableStateOf(
            sample != null &&
                statusAllowsDisplay &&
                SystemClock.elapsedRealtime() - sample.receivedAtElapsedRealtime <=
                HEART_RATE_SAMPLE_STALE_AFTER_MILLIS
        )
    }

    LaunchedEffect(sample?.receivedAtElapsedRealtime, statusAllowsDisplay) {
        if (!sampleIsFresh || sample == null || !statusAllowsDisplay) return@LaunchedEffect

        val expiresAtElapsedRealtime =
            sample.receivedAtElapsedRealtime + HEART_RATE_SAMPLE_STALE_AFTER_MILLIS
        delay((expiresAtElapsedRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        sampleIsFresh = false
    }

    return sampleIsFresh
}

private const val HEART_RATE_SAMPLE_STALE_AFTER_MILLIS = 10_000L

private val HeartRateMonitoringStatus.allowsSampleDisplay: Boolean
    get() = this == HeartRateMonitoringStatus.LIVE

private val HeartRateMonitoringStatus.label: String
    get() = when (this) {
        HeartRateMonitoringStatus.OFF -> "Off"
        HeartRateMonitoringStatus.WAITING_FOR_AIRPODS -> "Waiting for AirPods"
        HeartRateMonitoringStatus.WAITING_TO_BE_WORN -> "Waiting to be worn"
        HeartRateMonitoringStatus.STARTING -> "Starting"
        HeartRateMonitoringStatus.CALIBRATING -> "Calibrating"
        HeartRateMonitoringStatus.LIVE -> "Live"
        HeartRateMonitoringStatus.RECONNECTING -> "Reconnecting"
        HeartRateMonitoringStatus.COULDNT_START -> "Couldn’t start"
    }
