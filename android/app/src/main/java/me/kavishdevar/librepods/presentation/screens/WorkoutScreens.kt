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

package me.kavishdevar.librepods.presentation.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.data.workout.HealthConnectSessionExportState
import me.kavishdevar.librepods.data.workout.HeartRateZone
import me.kavishdevar.librepods.data.workout.HeartRateZones
import me.kavishdevar.librepods.data.workout.WorkoutDetail
import me.kavishdevar.librepods.data.workout.WorkoutSampleEntity
import me.kavishdevar.librepods.data.workout.WorkoutSummary
import me.kavishdevar.librepods.export.workout.WorkoutFileExporter
import me.kavishdevar.librepods.health.workout.AndroidWorkoutHealthConnectExporter
import me.kavishdevar.librepods.presentation.components.HeartRateStatusChip
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.services.HeartRateMonitoringStatus
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun WorkoutScreen(
    viewModel: AirPodsViewModel,
    navigateToHistory: () -> Unit,
    navigateToSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LibrePodsApplication
    val repository = app.workoutRepository
    val workout by repository.activeWorkout.collectAsState(initial = null)
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(workout?.summary?.id) {
        while (workout != null) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    WorkoutPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = navigateToHistory, modifier = Modifier.weight(1f)) {
                Text("History")
            }
            OutlinedButton(onClick = navigateToSettings, modifier = Modifier.weight(1f)) {
                Text("Zone settings")
            }
        }

        Spacer(Modifier.height(16.dp))

        if (workout == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No active workout", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Starting creates the local session immediately. Heart-rate monitoring is enabled so validated AirPods samples can be persisted to it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HeartRateStatusChip(
                        status = state.heartRate.status,
                        onRetry = if (state.heartRate.status == HeartRateMonitoringStatus.COULDNT_START)
                            viewModel::reconnectAacpForHeartRate else null,
                        compact = true,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                repository.startWorkout()
                                viewModel.setHeartRateMonitoringEnabled(true)
                            }
                        },
                    ) { Text("Start workout") }
                }
            }
        } else {
            val detail = workout!!
            WorkoutSummaryCard(detail, now)
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Heart-rate source", fontWeight = FontWeight.SemiBold)
                        HeartRateStatusChip(
                            status = state.heartRate.status,
                            onRetry = if (state.heartRate.status == HeartRateMonitoringStatus.COULDNT_START)
                                viewModel::reconnectAacpForHeartRate else null,
                            compact = true,
                        )
                    }
                    if (detail.samples.isEmpty()) {
                        Text(
                            "No validated samples yet. The workout is still saved locally even while AirPods are disconnected.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            WorkoutChartCard(
                samples = detail.samples,
                startTimeMillis = detail.summary.startTimeEpochMillis,
                endTimeMillis = now,
            )
            Spacer(Modifier.height(12.dp))
            ZoneCard(detail.zones, detail.summary.maxHeartRateBpm)
            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { repository.finishWorkout(detail.summary.id) } },
            ) { Text("Finish workout") }
        }
    }
}

@Composable
fun WorkoutHistoryScreen(navigateToDetail: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LibrePodsApplication
    val repository = app.workoutRepository
    val history by repository.history.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var workoutPendingDelete by remember { mutableStateOf<WorkoutSummary?>(null) }

    fun exportWorkout(sessionId: String, format: WorkoutExportFormat) {
        scope.launch {
            val workout = repository.snapshot(sessionId)
            if (workout == null) {
                Toast.makeText(context, "Workout no longer exists", Toast.LENGTH_SHORT).show()
            } else {
                exportAndShare(context, workout, format)
            }
        }
    }

    WorkoutPage {
        if (history.isEmpty()) {
            Text(
                "Finished workouts will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        history.forEach { session ->
            HistoryCard(
                session = session,
                onClick = { navigateToDetail(session.id) },
                onExportCsv = { exportWorkout(session.id, WorkoutExportFormat.CSV) },
                onExportFit = { exportWorkout(session.id, WorkoutExportFormat.FIT) },
                onDelete = { workoutPendingDelete = session },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
    workoutPendingDelete?.let { session ->
        DeleteWorkoutDialog(
            onDismissRequest = { workoutPendingDelete = null },
            onConfirm = {
                workoutPendingDelete = null
                scope.launch { repository.deleteWorkout(session.id) }
            },
        )
    }
}

@Composable
fun WorkoutDetailScreen(sessionId: String, onDeleted: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as LibrePodsApplication
    val repository = app.workoutRepository
    val detail by repository.workout(sessionId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (AndroidWorkoutHealthConnectExporter.WRITE_EXERCISE_PERMISSION in granted) {
            scope.launch { repository.retryHealthConnectExport(sessionId) }
        }
    }

    WorkoutPage {
        val workout = detail
        if (workout == null) {
            Text("Workout not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@WorkoutPage
        }
        WorkoutSummaryCard(workout, workout.summary.endTimeEpochMillis ?: System.currentTimeMillis())
        Spacer(Modifier.height(12.dp))
        WorkoutChartCard(
            samples = workout.samples,
            startTimeMillis = workout.summary.startTimeEpochMillis,
            endTimeMillis = workout.summary.endTimeEpochMillis,
        )
        Spacer(Modifier.height(12.dp))
        ZoneCard(workout.zones, workout.summary.maxHeartRateBpm)
        Spacer(Modifier.height(12.dp))
        HealthConnectSessionCard(
            workout = workout,
            onRequestPermission = {
                permissionLauncher.launch(AndroidWorkoutHealthConnectExporter.REQUIRED_PERMISSIONS)
            },
            onRetry = { scope.launch { repository.retryHealthConnectExport(sessionId) } },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { scope.launch { exportAndShare(context, workout, WorkoutExportFormat.CSV) } },
            ) { Text("Export CSV") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { scope.launch { exportAndShare(context, workout, WorkoutExportFormat.FIT) } },
            ) { Text("Export FIT") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDeleteConfirmation = true },
        ) { Text("Delete workout") }
        if (showDeleteConfirmation) {
            DeleteWorkoutDialog(
                onDismissRequest = { showDeleteConfirmation = false },
                onConfirm = {
                    showDeleteConfirmation = false
                    scope.launch {
                        if (repository.deleteWorkout(sessionId)) onDeleted()
                    }
                },
            )
        }
    }
}

@Composable
fun WorkoutSettingsScreen() {
    val app = LocalContext.current.applicationContext as LibrePodsApplication
    val preferences = app.workoutPreferences
    val keyboardController = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf(preferences.maxHeartRateBpm.toString()) }
    var savedValue by remember { mutableStateOf(preferences.maxHeartRateBpm) }

    WorkoutPage {
        Text("Heart-rate zones", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { value -> text = value.filter(Char::isDigit).take(3) },
            label = { Text("Maximum heart rate (BPM)") },
            supportingText = { Text("Allowed ${HeartRateZones.MIN_CONFIGURABLE_MAX_HEART_RATE_BPM}–${HeartRateZones.MAX_CONFIGURABLE_MAX_HEART_RATE_BPM} BPM") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val value = text.toIntOrNull() ?: HeartRateZones.DEFAULT_MAX_HEART_RATE_BPM
                preferences.maxHeartRateBpm = value
                savedValue = preferences.maxHeartRateBpm
                text = savedValue.toString()
                keyboardController?.hide()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save for new workouts") }
        Text("Current saved max HR: $savedValue BPM", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WorkoutPage(content: @Composable ColumnScope.() -> Unit) {
    val material = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (material) 16.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(topPadding))
        content()
        Spacer(Modifier.height(bottomPadding))
    }
}

@Composable
private fun WorkoutSummaryCard(detail: WorkoutDetail, nowMillis: Long) {
    val summary = detail.summary
    val end = summary.endTimeEpochMillis ?: nowMillis
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(summary.latestBpm?.toString() ?: "—", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                    Text("Latest BPM", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatDuration((end - summary.startTimeEpochMillis).coerceAtLeast(0L)), style = MaterialTheme.typography.titleLarge)
                    Text("Duration", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Min", summary.minBpm?.toString() ?: "—")
                Stat("Avg", summary.avgBpm?.roundToInt()?.toString() ?: "—")
                Stat("Max", summary.maxBpm?.toString() ?: "—")
                Stat("Samples", summary.sampleCount.toString())
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkoutChartCard(
    samples: List<WorkoutSampleEntity>,
    startTimeMillis: Long? = null,
    endTimeMillis: Long? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Heart rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (samples.size < 2) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text("The graph appears after two validated samples.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                HeartRateCanvas(
                    samples = samples,
                    startTimeMillis = startTimeMillis,
                    endTimeMillis = endTimeMillis,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            }
        }
    }
}

@Composable
private fun HeartRateCanvas(
    samples: List<WorkoutSampleEntity>,
    startTimeMillis: Long? = null,
    endTimeMillis: Long? = null,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val ordered = samples.sortedBy { it.timestampEpochMillis }
        val minTime = minOf(
            ordered.first().timestampEpochMillis,
            startTimeMillis ?: ordered.first().timestampEpochMillis,
        )
        val maxTime = maxOf(
            ordered.last().timestampEpochMillis,
            endTimeMillis ?: ordered.last().timestampEpochMillis,
        ).coerceAtLeast(minTime + 1L)
        val minBpm = (ordered.minOf { it.bpm } - 5).coerceAtLeast(20)
        val maxBpm = (ordered.maxOf { it.bpm } + 5).coerceAtLeast(minBpm + 1)
        repeat(4) { row ->
            val y = size.height * row / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val path = Path()
        var previousTimestamp: Long? = null
        ordered.forEach { sample ->
            val x = ((sample.timestampEpochMillis - minTime).toFloat() / (maxTime - minTime).toFloat()) * size.width
            val y = size.height - ((sample.bpm - minBpm).toFloat() / (maxBpm - minBpm).toFloat()) * size.height
            val previous = previousTimestamp
            if (previous == null || sample.timestampEpochMillis - previous > HEART_RATE_GRAPH_GAP_MILLIS) {
                // A long interval means no validated HR data was written. Start a new segment
                // after the gap instead of inventing a slope across the disconnection.
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
            previousTimestamp = sample.timestampEpochMillis
        }
        drawPath(path, lineColor, style = Stroke(width = 4f))
    }
}

private const val HEART_RATE_GRAPH_GAP_MILLIS = 4_000L

@Composable
private fun ZoneCard(zones: List<HeartRateZone>, maxHeartRateBpm: Int) {
    val total = zones.sumOf { it.sampleCount }.coerceAtLeast(1)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Heart-rate zones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Max HR $maxHeartRateBpm BPM", color = MaterialTheme.colorScheme.onSurfaceVariant)
            zones.forEach { zone ->
                val range = zoneBpmRange(zone, maxHeartRateBpm)
                val sharePercent = (zone.sampleCount.toDouble() / total.toDouble() * 100.0).roundToInt()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${zone.label}  $range")
                    Text("$sharePercent%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(
                    progress = { zone.sampleCount.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    session: WorkoutSummary,
    onClick: () -> Unit,
    onExportCsv: () -> Unit,
    onExportFit: () -> Unit,
    onDelete: () -> Unit,
) {
    val duration = (session.endTimeEpochMillis ?: session.startTimeEpochMillis) - session.startTimeEpochMillis
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.startTimeEpochMillis)), fontWeight = FontWeight.SemiBold)
            Text("${formatDuration(duration)} • ${session.sampleCount} samples • avg ${session.avgBpm?.roundToInt() ?: "—"} BPM")
            Text("Health Connect: ${healthStateLabel(session.healthConnectExportState)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onExportCsv) { Text("CSV") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onExportFit) { Text("FIT") }
            }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete workout") }
        }
    }
}

private fun zoneBpmRange(zone: HeartRateZone, maxHeartRateBpm: Int): String {
    fun firstBpmAtPercent(percent: Int): Int = ceil(maxHeartRateBpm * percent / 100.0).toInt()
    return when {
        zone.minimumPercent == null -> "<${firstBpmAtPercent(zone.maximumPercentExclusive!!)} BPM"
        zone.maximumPercentExclusive == null -> "≥${firstBpmAtPercent(zone.minimumPercent)} BPM"
        else -> {
            val start = firstBpmAtPercent(zone.minimumPercent)
            val end = firstBpmAtPercent(zone.maximumPercentExclusive) - 1
            "$start–$end BPM"
        }
    }
}

@Composable
private fun DeleteWorkoutDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Delete workout?") },
        text = { Text("This removes the workout from LibrePods history. Health Connect data is unchanged.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text("Cancel") } },
    )
}

@Composable
private fun HealthConnectSessionCard(
    workout: WorkoutDetail,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    val state = workout.summary.healthConnectExportState
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Health Connect session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(healthStateLabel(state), color = MaterialTheme.colorScheme.onSurfaceVariant)
            workout.summary.healthConnectExportMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            when (state) {
                HealthConnectSessionExportState.PERMISSION_REQUIRED -> OutlinedButton(onClick = onRequestPermission) { Text("Grant permission and retry") }
                HealthConnectSessionExportState.UNAVAILABLE,
                HealthConnectSessionExportState.ERROR,
                HealthConnectSessionExportState.PENDING -> OutlinedButton(onClick = onRetry) { Text("Retry Health Connect export") }
                else -> Unit
            }
        }
    }
}

private enum class WorkoutExportFormat { CSV, FIT }

private suspend fun exportAndShare(
    context: Context,
    workout: WorkoutDetail,
    format: WorkoutExportFormat,
) {
    try {
        val exported = withContext(Dispatchers.IO) {
            when (format) {
                WorkoutExportFormat.CSV -> WorkoutFileExporter.exportCsv(context, workout)
                WorkoutExportFormat.FIT -> WorkoutFileExporter.exportFit(context, workout)
            }
        }
        WorkoutFileExporter.share(context, exported)
    } catch (error: Exception) {
        Toast.makeText(
            context,
            "${format.name} export failed: ${error.message ?: error.javaClass.simpleName}",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

private fun healthStateLabel(state: HealthConnectSessionExportState): String = when (state) {
    HealthConnectSessionExportState.NOT_FINISHED -> "Not finished"
    HealthConnectSessionExportState.PENDING -> "Pending export"
    HealthConnectSessionExportState.EXPORTED -> "Exported"
    HealthConnectSessionExportState.PERMISSION_REQUIRED -> "Exercise permission required; local workout is safe"
    HealthConnectSessionExportState.UNAVAILABLE -> "Unavailable; local workout is safe"
    HealthConnectSessionExportState.ERROR -> "Export failed; local workout is safe"
}
