/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.presentation.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import me.kavishdevar.librepods.bluetooth.HeartRateBlePeripheralState
import me.kavishdevar.librepods.bluetooth.HeartRateBlePeripheralStatus
import me.kavishdevar.librepods.bluetooth.HeartRateSample
import me.kavishdevar.librepods.health.HealthConnectExportState
import me.kavishdevar.librepods.health.HealthConnectExportStatus
import me.kavishdevar.librepods.health.HealthConnectHeartRateExporter
import me.kavishdevar.librepods.presentation.components.HeartRateStatusChip
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledSwitch
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.components.rememberHeartRateSampleIsDisplayable
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.services.HeartRateMonitoringState
import me.kavishdevar.librepods.services.HeartRateMonitoringStatus
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.delay

@Composable
fun HeartRateTestScreen(
    viewModel: AirPodsViewModel,
    navigateToWorkout: () -> Unit,
    navigateToHealthConnectSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var graphNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions: Set<String> ->
        if (HealthConnectHeartRateExporter.WRITE_HEART_RATE_PERMISSION in grantedPermissions) {
            viewModel.setHealthConnectExportEnabled(true)
        } else {
            viewModel.markHealthConnectPermissionDenied()
        }
    }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshHeartRateBlePeripheral()
    }

    fun enableOrRetryBlePeripheral() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        viewModel.setHeartRateBlePeripheralEnabled(true)
        val missing = permissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            blePermissionLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.refreshHeartRateBlePeripheral()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshHealthConnectExportState()
        viewModel.refreshHeartRateBlePeripheral()
    }

    // Keep the live chart's right edge moving while the stream is quiet. This makes a
    // lost signal visible as blank time instead of leaving the last sample at the edge.
    LaunchedEffect(Unit) {
        while (true) {
            graphNowMillis = System.currentTimeMillis()
            delay(LIVE_HEART_RATE_GRAPH_TICK_MILLIS)
        }
    }

    val materialDesign = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (materialDesign) {
        16.dp
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    }
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp

    val heartRate = state.heartRate
    val healthConnect = state.healthConnect
    val sampleIsDisplayable = rememberHeartRateSampleIsDisplayable(
        sample = heartRate.latestSample,
        monitoringStatus = heartRate.status
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        HeartRateSummaryCard(
            state = heartRate,
            sampleIsDisplayable = sampleIsDisplayable,
            onReconnectAacp = viewModel::reconnectAacpForHeartRate,
            onMonitoringChanged = viewModel::setHeartRateMonitoringEnabled
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = navigateToWorkout,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Workouts & session history")
        }

        Spacer(modifier = Modifier.height(16.dp))

        HealthConnectControls(
            state = healthConnect,
            onExportChanged = { enabled ->
                when {
                    !enabled -> viewModel.setHealthConnectExportEnabled(false)
                    healthConnect.status.canEnableExport ->
                        viewModel.setHealthConnectExportEnabled(true)

                    healthConnect.status.requiresPermissionRequest ->
                        healthConnectPermissionLauncher.launch(
                            HealthConnectHeartRateExporter.REQUIRED_PERMISSIONS
                        )
                }
            },
            onOpenSettings = navigateToHealthConnectSettings
        )

        Spacer(modifier = Modifier.height(12.dp))

        BleHeartRatePeripheralControls(
            state = state.heartRateBlePeripheral,
            onEnabledChanged = { enabled ->
                if (enabled) enableOrRetryBlePeripheral()
                else viewModel.setHeartRateBlePeripheralEnabled(false)
            },
            onRetry = ::enableOrRetryBlePeripheral
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Recent samples",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        Text(
            text = formatGraphSummary(heartRate.samples),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        HeartRateGraph(samples = heartRate.samples, nowMillis = graphNowMillis)

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

@Composable
private fun HeartRateSummaryCard(
    state: HeartRateMonitoringState,
    sampleIsDisplayable: Boolean,
    onReconnectAacp: () -> Unit,
    onMonitoringChanged: (Boolean) -> Unit
) {
    val materialDesign = LocalDesignSystem.current == DesignSystem.Material
    val displayBpm = state.latestSample?.takeIf { sampleIsDisplayable }?.bpm?.toString() ?: EM_DASH
    val reconnectAction = onReconnectAacp.takeIf {
        state.status == HeartRateMonitoringStatus.COULDNT_START
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (materialDesign) 24.dp else 28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(if (materialDesign) 12.dp else 14.dp)
        ) {
            when (LocalDesignSystem.current) {
                DesignSystem.Material -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = displayBpm,
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "BPM",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = onMonitoringChanged,
                            modifier = Modifier.scale(MATERIAL_SWITCH_SCALE)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatLastReading(state.latestSample),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        HeartRateStatusChip(
                            status = state.status,
                            onRetry = reconnectAction,
                            compact = true
                        )
                    }
                }

                DesignSystem.Apple -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = displayBpm,
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "BPM",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Box(modifier = Modifier.padding(end = 4.dp, bottom = 8.dp)) {
                                StyledSwitch(
                                    checked = state.enabled,
                                    onCheckedChange = onMonitoringChanged
                                )
                            }
                            HeartRateStatusChip(
                                status = state.status,
                                onRetry = reconnectAction
                            )
                        }
                    }

                    Text(
                        text = formatLastReading(state.latestSample),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthConnectControls(
    state: HealthConnectExportState,
    onExportChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    val available = state.status.isAvailable

    StyledList(title = "Health Connect") {
        StyledToggle(
            label = "Save heart-rate data",
            description = healthConnectDescription(state.status),
            checked = state.enabled,
            enabled = available,
            onCheckedChange = onExportChanged
        )

        StyledListItem(
            name = "Storage mode",
            description = healthConnectExportSummary(state),
            enabled = available && state.enabled,
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun BleHeartRatePeripheralControls(
    state: HeartRateBlePeripheralState,
    onEnabledChanged: (Boolean) -> Unit,
    onRetry: () -> Unit
) {
    StyledToggle(
        title = "Bluetooth heart-rate sharing",
        label = "Share as a BLE heart-rate sensor",
        description = blePeripheralDescription(state),
        checked = state.enabled,
        onCheckedChange = onEnabledChanged
    )

    if (state.enabled && state.status in setOf(
            HeartRateBlePeripheralStatus.ERROR,
            HeartRateBlePeripheralStatus.PERMISSION_REQUIRED,
            HeartRateBlePeripheralStatus.BLUETOOTH_OFF
        )
    ) {
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.status == HeartRateBlePeripheralStatus.PERMISSION_REQUIRED) "Allow & retry" else "Retry")
        }
    }
}

private fun blePeripheralDescription(state: HeartRateBlePeripheralState): String {
    val privacy = "Off by default. Shares only validated LibrePods heart-rate samples while enabled."
    return when (state.status) {
        HeartRateBlePeripheralStatus.DISABLED -> privacy
        HeartRateBlePeripheralStatus.STARTING -> "Starting the standard Heart Rate Service (0x180D). $privacy"
        HeartRateBlePeripheralStatus.ADVERTISING ->
            "Advertising · ${state.connectedDeviceCount} connected · ${state.subscribedDeviceCount} subscribed. $privacy"
        HeartRateBlePeripheralStatus.PERMISSION_REQUIRED ->
            "Nearby devices permission is required to advertise and accept GATT connections. $privacy"
        HeartRateBlePeripheralStatus.BLUETOOTH_OFF -> "Bluetooth is off. $privacy"
        HeartRateBlePeripheralStatus.UNSUPPORTED ->
            "BLE peripheral advertising is not supported by this adapter. $privacy"
        HeartRateBlePeripheralStatus.ERROR ->
            "${state.lastError ?: "BLE heart-rate sharing failed."} $privacy"
    }
}

private val HealthConnectExportStatus.isAvailable: Boolean
    get() = this != HealthConnectExportStatus.UNAVAILABLE &&
        this != HealthConnectExportStatus.UPDATE_REQUIRED

private val HealthConnectExportStatus.canEnableExport: Boolean
    get() = this == HealthConnectExportStatus.READY ||
        this == HealthConnectExportStatus.ENABLED

private val HealthConnectExportStatus.requiresPermissionRequest: Boolean
    get() = this == HealthConnectExportStatus.PERMISSION_REQUIRED ||
        this == HealthConnectExportStatus.PERMISSION_DENIED ||
        this == HealthConnectExportStatus.ERROR

private fun healthConnectDescription(status: HealthConnectExportStatus): String = when (status) {
    HealthConnectExportStatus.UNAVAILABLE ->
        "Health Connect is not available on this device."

    HealthConnectExportStatus.UPDATE_REQUIRED ->
        "Install or update Health Connect to save heart-rate samples."

    HealthConnectExportStatus.PERMISSION_REQUIRED ->
        "Allow Health Connect access to save heart-rate data."

    HealthConnectExportStatus.PERMISSION_DENIED ->
        "Health Connect access was denied. Turn this on to try again."

    HealthConnectExportStatus.READY,
    HealthConnectExportStatus.ENABLED ->
        "Save validated heart-rate readings to Health Connect."

    HealthConnectExportStatus.ERROR ->
        "Health Connect could not be accessed. Buffered data will be retried."
}

@Composable
private fun HeartRateGraph(samples: List<HeartRateSample>, nowMillis: Long) {
    val orderedSamples = remember(samples) {
        samples.sortedBy { it.receivedAtMillis }
    }
    val minTime = maxOf(
        orderedSamples.firstOrNull()?.receivedAtMillis ?: nowMillis,
        nowMillis - LIVE_HEART_RATE_GRAPH_WINDOW_MILLIS,
    )
    val maxTime = maxOf(
        orderedSamples.lastOrNull()?.receivedAtMillis ?: nowMillis,
        nowMillis,
    ).coerceAtLeast(minTime + 1L)
    val visibleSamples = orderedSamples.filter {
        it.receivedAtMillis in minTime..maxTime
    }
    val chartScale = remember(visibleSamples) {
        calculateHeartRateChartScale(visibleSamples.map { it.bpm.toFloat() })
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    val axisLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    val pointColor = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val axisLabelPaint = remember(axisColor, density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = axisColor.toArgb()
            textSize = with(density) { 11.sp.toPx() }
            textAlign = Paint.Align.RIGHT
        }
    }
    val axisTitlePaint = remember(axisColor, density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = axisColor.toArgb()
            textSize = with(density) { 9.sp.toPx() }
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val plotLeft = CHART_AXIS_WIDTH.toPx()
                val plotRight = size.width
                val plotTop = CHART_TOP_INSET.toPx()
                val plotBottom = size.height - CHART_BOTTOM_INSET.toPx()
                val plotWidth = (plotRight - plotLeft).coerceAtLeast(0f)
                val plotHeight = (plotBottom - plotTop).coerceAtLeast(0f)
                val labelX = plotLeft - CHART_AXIS_LABEL_GAP.toPx()
                val labelMetrics = axisLabelPaint.fontMetrics
                val labelBaselineOffset = -(labelMetrics.ascent + labelMetrics.descent) / 2f
                val titleMetrics = axisTitlePaint.fontMetrics

                drawContext.canvas.nativeCanvas.drawText(
                    "BPM",
                    plotLeft / 2f,
                    -titleMetrics.ascent,
                    axisTitlePaint
                )

                drawLine(
                    color = axisLineColor,
                    start = Offset(plotLeft, plotTop),
                    end = Offset(plotLeft, plotBottom),
                    strokeWidth = 1.dp.toPx()
                )

                chartScale.gridLines.forEach { bpm ->
                    val normalized =
                        (bpm - chartScale.minBpm) / chartScale.spanBpm
                    val y = plotBottom - normalized * plotHeight

                    drawLine(
                        color = gridColor,
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        bpm.toInt().toString(),
                        labelX,
                        y + labelBaselineOffset,
                        axisLabelPaint
                    )
                }

                if (visibleSamples.isNotEmpty()) {
                    val path = Path()
                    var previousTimestamp: Long? = null
                    visibleSamples.forEachIndexed { index, sample ->
                        val x = plotLeft +
                            (sample.receivedAtMillis - minTime).toFloat() /
                                (maxTime - minTime).toFloat() * plotWidth
                        val y = chartScale.bpmY(
                            bpm = sample.bpm.toFloat(),
                            plotBottom = plotBottom,
                            plotHeight = plotHeight
                        )

                        val previous = previousTimestamp
                        if (
                            previous == null ||
                            sample.receivedAtMillis - previous > LIVE_HEART_RATE_GRAPH_GAP_MILLIS
                        ) {
                            // Do not invent a slope through a period with no validated data.
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                        previousTimestamp = sample.receivedAtMillis
                        if (index == visibleSamples.lastIndex) {
                            drawCircle(
                                color = pointColor,
                                radius = 4.dp.toPx(),
                                center = Offset(x, y)
                            )
                        }
                    }
                    if (visibleSamples.size > 1) {
                        drawPath(
                            path = path,
                            color = lineColor,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
            }

            if (visibleSamples.isEmpty()) {
                Text(
                    text = "Waiting for recent validated heart-rate samples",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = CHART_AXIS_WIDTH)
                )
            }
        }
    }
}

private fun formatGraphSummary(samples: List<HeartRateSample>): String {
    if (samples.isEmpty()) return "Min $EM_DASH · Avg $EM_DASH · Max $EM_DASH BPM"

    val min = samples.minOf { it.bpm }
    val max = samples.maxOf { it.bpm }
    val average = samples.sumOf { it.bpm }.toFloat() / samples.size
    return "Min $min · Avg ${average.toInt()} · Max $max BPM"
}

private data class HeartRateChartScale(
    val minBpm: Float,
    val maxBpm: Float,
    val gridLines: List<Float>
) {
    val spanBpm: Float
        get() = maxBpm - minBpm

    fun bpmY(bpm: Float, plotBottom: Float, plotHeight: Float): Float {
        val normalized = ((bpm - minBpm) / spanBpm).coerceIn(0f, 1f)
        return plotBottom - normalized * plotHeight
    }
}

private fun calculateHeartRateChartScale(bpms: List<Float>): HeartRateChartScale {
    if (bpms.isEmpty()) {
        return HeartRateChartScale(
            minBpm = CHART_DEFAULT_MIN_BPM,
            maxBpm = CHART_DEFAULT_MAX_BPM,
            gridLines = listOf(60f, 70f, 80f, 90f, 100f)
        )
    }

    val dataMin = bpms.minOrNull() ?: CHART_DEFAULT_MIN_BPM
    val dataMax = bpms.maxOrNull() ?: CHART_DEFAULT_MAX_BPM
    val paddedMin = dataMin - CHART_MARGIN_BPM
    val paddedMax = dataMax + CHART_MARGIN_BPM
    val requestedSpan = maxOf(paddedMax - paddedMin, CHART_MIN_SPAN_BPM)
    val center = (paddedMin + paddedMax) / 2f
    val roughMin = center - requestedSpan / 2f
    val roughMax = center + requestedSpan / 2f
    val tickStep = niceTickStep(requestedSpan / CHART_TARGET_GRID_INTERVALS)

    var minBpm = floor(roughMin / tickStep) * tickStep
    var maxBpm = ceil(roughMax / tickStep) * tickStep
    if (minBpm < CHART_OUTER_MIN_BPM) {
        maxBpm -= minBpm - CHART_OUTER_MIN_BPM
        minBpm = CHART_OUTER_MIN_BPM
    }
    if (maxBpm > CHART_OUTER_MAX_BPM) {
        minBpm -= maxBpm - CHART_OUTER_MAX_BPM
        maxBpm = CHART_OUTER_MAX_BPM
    }

    val gridLines = buildList {
        var value = minBpm
        while (value <= maxBpm + 0.01f) {
            add(value)
            value += tickStep
        }
    }
    return HeartRateChartScale(minBpm, maxBpm, gridLines)
}

private fun niceTickStep(rawStep: Float): Float =
    CHART_TICK_STEPS.firstOrNull { it >= rawStep } ?: CHART_TICK_STEPS.last()

private fun formatLastReading(sample: HeartRateSample?): String {
    if (sample == null) return "Last reading: $EM_DASH"
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        .format(Date(sample.receivedAtMillis))
    return "Last reading: ${sample.bpm} BPM at $time"
}

private const val EM_DASH = "—"
private const val MATERIAL_SWITCH_SCALE = 0.82f
private const val CHART_DEFAULT_MIN_BPM = 60f
private const val CHART_DEFAULT_MAX_BPM = 100f
private const val CHART_MIN_SPAN_BPM = 40f
private const val CHART_MARGIN_BPM = 5f
private const val CHART_OUTER_MIN_BPM = 0f
private const val CHART_OUTER_MAX_BPM = 260f
private const val CHART_TARGET_GRID_INTERVALS = 5f
private const val LIVE_HEART_RATE_GRAPH_GAP_MILLIS = 4_000L
private const val LIVE_HEART_RATE_GRAPH_WINDOW_MILLIS = 60_000L
private const val LIVE_HEART_RATE_GRAPH_TICK_MILLIS = 1_000L
private val CHART_TICK_STEPS = listOf(5f, 10f, 20f, 25f, 50f)
private val CHART_AXIS_WIDTH = 42.dp
private val CHART_AXIS_LABEL_GAP = 8.dp
private val CHART_TOP_INSET = 20.dp
private val CHART_BOTTOM_INSET = 8.dp
