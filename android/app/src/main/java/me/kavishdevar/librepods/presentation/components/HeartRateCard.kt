/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.bluetooth.HeartRateSample
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.services.HeartRateMonitoringState
import me.kavishdevar.librepods.services.HeartRateMonitoringStatus

@Composable
fun HeartRateCard(
    state: HeartRateMonitoringState,
    onMonitoringChanged: (Boolean) -> Unit,
    onReconnectAacp: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sampleIsDisplayable = rememberHeartRateSampleIsDisplayable(
        sample = state.latestSample,
        monitoringStatus = state.status
    )
    val displayedBpm = state.latestSample
        ?.takeIf { sampleIsDisplayable }
        ?.bpm
        ?.toString()
        ?: EM_DASH
    val graphValues = remember(state.samples) {
        normalizedRecentHeartRates(state.samples)
    }
    val canReconnectAacp = state.status == HeartRateMonitoringStatus.COULDNT_START

    when (LocalDesignSystem.current) {
        DesignSystem.Material -> MaterialHeartRateCard(
            displayedBpm = displayedBpm,
            graphValues = graphValues,
            state = state,
            canReconnectAacp = canReconnectAacp,
            onMonitoringChanged = onMonitoringChanged,
            onReconnectAacp = onReconnectAacp,
            onOpenDetails = onOpenDetails,
            modifier = modifier
        )

        DesignSystem.Apple -> AppleHeartRateCard(
            displayedBpm = displayedBpm,
            graphValues = graphValues,
            state = state,
            canReconnectAacp = canReconnectAacp,
            onMonitoringChanged = onMonitoringChanged,
            onReconnectAacp = onReconnectAacp,
            onOpenDetails = onOpenDetails,
            modifier = modifier
        )
    }
}

@Composable
private fun MaterialHeartRateCard(
    displayedBpm: String,
    graphValues: List<Float>,
    state: HeartRateMonitoringState,
    canReconnectAacp: Boolean,
    onMonitoringChanged: (Boolean) -> Unit,
    onReconnectAacp: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeartRateMiniGraph(
                values = graphValues,
                width = MATERIAL_GRAPH_WIDTH,
                height = MATERIAL_GRAPH_HEIGHT
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Heart rate",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                HeartRateStatusChip(
                    status = state.status,
                    onRetry = onReconnectAacp.takeIf { canReconnectAacp },
                    compact = true
                )
            }

            if (!canReconnectAacp) {
                Spacer(modifier = Modifier.width(10.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = displayedBpm,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "BPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = state.enabled,
                onCheckedChange = onMonitoringChanged,
                modifier = Modifier.scale(MATERIAL_SWITCH_SCALE)
            )
        }
    }
}

@Composable
private fun AppleHeartRateCard(
    displayedBpm: String,
    graphValues: List<Float>,
    state: HeartRateMonitoringState,
    canReconnectAacp: Boolean,
    onMonitoringChanged: (Boolean) -> Unit,
    onReconnectAacp: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeartRateMiniGraph(values = graphValues)

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Heart rate",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                HeartRateStatusChip(
                    status = state.status,
                    onRetry = onReconnectAacp.takeIf { canReconnectAacp }
                )
            }

            if (!canReconnectAacp) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = displayedBpm,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "BPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))
            }

            StyledSwitch(
                checked = state.enabled,
                onCheckedChange = onMonitoringChanged
            )
        }
    }
}

@Composable
private fun HeartRateMiniGraph(
    values: List<Float>,
    modifier: Modifier = Modifier,
    width: Dp = GRAPH_WIDTH,
    height: Dp = GRAPH_HEIGHT
) {
    val graphColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    Canvas(
        modifier = modifier
            .width(width)
            .height(height)
    ) {
        val horizontalPadding = 2.dp.toPx()
        val verticalPadding = 4.dp.toPx()
        val left = horizontalPadding
        val right = size.width - horizontalPadding
        val top = verticalPadding
        val bottom = size.height - verticalPadding

        if (values.isEmpty()) {
            val middleY = (top + bottom) / 2f
            drawLine(
                color = guideColor,
                start = Offset(left, top),
                end = Offset(right, top),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = guideColor,
                start = Offset(left, middleY),
                end = Offset(right, middleY),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = guideColor,
                start = Offset(left, bottom),
                end = Offset(right, bottom),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )
            return@Canvas
        }

        drawLine(
            color = guideColor,
            start = Offset(left, bottom),
            end = Offset(right, bottom),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )

        val availableWidth = right - left
        val availableHeight = bottom - top
        val xStep = if (values.size > 1) availableWidth / values.lastIndex else 0f
        val path = Path()

        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) size.width / 2f else left + (index * xStep)
            val y = bottom - (value * availableHeight)

            drawLine(
                color = graphColor.copy(alpha = 0.18f),
                start = Offset(x, bottom),
                end = Offset(x, y),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        if (values.size == 1) {
            drawCircle(
                color = graphColor,
                radius = 2.dp.toPx(),
                center = Offset(size.width / 2f, bottom - (values.single() * availableHeight))
            )
        } else {
            drawPath(
                path = path,
                color = graphColor,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            val lastY = bottom - (values.last() * availableHeight)
            drawCircle(
                color = graphColor,
                radius = 2.dp.toPx(),
                center = Offset(right, lastY)
            )
        }
    }
}

private fun normalizedRecentHeartRates(samples: List<HeartRateSample>): List<Float> {
    val recentBpms = samples
        .takeLast(MAX_GRAPH_SAMPLES)
        .map { it.bpm.toFloat() }

    if (recentBpms.isEmpty()) return emptyList()

    val observedMin = recentBpms.minOrNull() ?: return emptyList()
    val observedMax = recentBpms.maxOrNull() ?: return emptyList()
    val center = (observedMin + observedMax) / 2f
    val span = maxOf(observedMax - observedMin, MIN_GRAPH_BPM_SPAN)
    val lowerBound = center - (span / 2f)

    return recentBpms.map { bpm ->
        ((bpm - lowerBound) / span).coerceIn(0f, 1f)
    }
}

private val GRAPH_WIDTH = 60.dp
private val GRAPH_HEIGHT = 44.dp
private val MATERIAL_GRAPH_WIDTH = 48.dp
private val MATERIAL_GRAPH_HEIGHT = 36.dp
private const val MATERIAL_SWITCH_SCALE = 0.82f
private const val MAX_GRAPH_SAMPLES = 24
private const val MIN_GRAPH_BPM_SPAN = 20f
private const val EM_DASH = "—"
