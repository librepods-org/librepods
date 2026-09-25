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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import me.kavishdevar.librepods.health.HealthConnectExportMode
import me.kavishdevar.librepods.health.HealthConnectExportState
import me.kavishdevar.librepods.health.MAX_HEART_RATE_EXPORT_INTERVAL_SECONDS
import me.kavishdevar.librepods.health.MIN_HEART_RATE_EXPORT_INTERVAL_SECONDS
import me.kavishdevar.librepods.health.normalizeHeartRateExportIntervalSeconds
import me.kavishdevar.librepods.presentation.components.ListItemOrientation
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@Composable
fun HealthConnectSettingsScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val healthConnect = state.healthConnect
    val materialDesign = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (materialDesign) {
        0.dp
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    }
    val bottomPadding = if (materialDesign) {
        0.dp
    } else {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        StyledList(
            title = "Storage mode",
            description = "Heart-rate monitoring affects AirPods battery life equally in every mode."
        ) {
            StyledListItem(
                name = "Every second",
                description = "Store readings immediately for live data, using more phone battery.",
                selected = healthConnect.mode == HealthConnectExportMode.EVERY_SECOND,
                onClick = {
                    viewModel.setHealthConnectExportMode(HealthConnectExportMode.EVERY_SECOND)
                },
                orientation = ListItemOrientation.Vertical
            )

            StyledListItem(
                name = "Batched",
                description = "Keep every reading and write them together to save phone battery.",
                selected = healthConnect.mode == HealthConnectExportMode.BATCHED,
                onClick = {
                    viewModel.setHealthConnectExportMode(HealthConnectExportMode.BATCHED)
                },
                orientation = ListItemOrientation.Vertical
            )

            StyledListItem(
                name = "Average",
                description = "Write one average value for each interval.",
                selected = healthConnect.mode == HealthConnectExportMode.AVERAGED,
                onClick = {
                    viewModel.setHealthConnectExportMode(HealthConnectExportMode.AVERAGED)
                },
                orientation = ListItemOrientation.Vertical
            )
        }

        when (healthConnect.mode) {
            HealthConnectExportMode.EVERY_SECOND -> Unit

            HealthConnectExportMode.BATCHED -> {
                Spacer(modifier = Modifier.height(24.dp))
                HealthConnectIntervalSlider(
                    label = "Batch interval",
                    description = "How often detailed readings are written together.",
                    intervalSeconds = healthConnect.batchIntervalSeconds,
                    snapPoints = listOf(
                        MIN_HEART_RATE_EXPORT_INTERVAL_SECONDS,
                        5 * 60,
                        MAX_HEART_RATE_EXPORT_INTERVAL_SECONDS
                    ),
                    onIntervalChanged = viewModel::setHealthConnectBatchIntervalSeconds
                )
            }

            HealthConnectExportMode.AVERAGED -> {
                Spacer(modifier = Modifier.height(24.dp))
                HealthConnectIntervalSlider(
                    label = "Average interval",
                    description = "How much time is included in each average.",
                    intervalSeconds = healthConnect.averageIntervalSeconds,
                    snapPoints = listOf(
                        MIN_HEART_RATE_EXPORT_INTERVAL_SECONDS,
                        60,
                        5 * 60,
                        MAX_HEART_RATE_EXPORT_INTERVAL_SECONDS
                    ),
                    onIntervalChanged = viewModel::setHealthConnectAverageIntervalSeconds
                )
            }
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

@Composable
private fun HealthConnectIntervalSlider(
    label: String,
    description: String,
    intervalSeconds: Int,
    snapPoints: List<Int>,
    onIntervalChanged: (Int) -> Unit
) {
    var sliderValue by remember(intervalSeconds) {
        mutableFloatStateOf(intervalSeconds.toFloat())
    }

    StyledSlider(
        label = "$label · ${formatHealthConnectInterval(sliderValue.roundToInt())}",
        description = description,
        value = sliderValue,
        onValueChange = { value ->
            sliderValue = normalizeHeartRateExportIntervalSeconds(value.roundToInt()).toFloat()
        },
        onValueChangeFinished = {
            onIntervalChanged(sliderValue.roundToInt())
        },
        valueRange = MIN_HEART_RATE_EXPORT_INTERVAL_SECONDS.toFloat()..
            MAX_HEART_RATE_EXPORT_INTERVAL_SECONDS.toFloat(),
        snapPoints = snapPoints.map(Int::toFloat),
        snapThreshold = 1f,
        startLabel = "30 sec",
        endLabel = "15 min",
        independent = true
    )
}

internal fun healthConnectExportSummary(state: HealthConnectExportState): String =
    when (state.mode) {
        HealthConnectExportMode.EVERY_SECOND -> "Every second"
        HealthConnectExportMode.BATCHED ->
            "Batched every ${formatHealthConnectInterval(state.batchIntervalSeconds)}"

        HealthConnectExportMode.AVERAGED ->
            "${formatHealthConnectInterval(state.averageIntervalSeconds)} average"
    }

internal fun formatHealthConnectInterval(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return when {
        minutes == 0 -> "$remainingSeconds sec"
        remainingSeconds == 0 -> "$minutes min"
        else -> "$minutes min $remainingSeconds sec"
    }
}
