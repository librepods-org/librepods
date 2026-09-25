package me.kavishdevar.librepods.presentation.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.finder.NearbyFinderState
import me.kavishdevar.librepods.finder.NearbyFinderStatus
import me.kavishdevar.librepods.finder.ProximityBucket
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import kotlin.math.roundToInt

@Composable
fun NearbyAirPodsFinderScreen(viewModel: AirPodsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val finder = uiState.nearbyFinder
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshNearbyFinderPrerequisites()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopNearbyFinder() }
    }

    val materialDesign = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (materialDesign) {
        16.dp
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    }
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(topPadding))
        Text(
            text = "Find your AirPods",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Move slowly while the signal settles. Distance is only an estimate.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 20.dp, end = 20.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))
        FinderIndicator(finder)
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = finder.signal.proximity.label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = statusLine(finder),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        finder.signal.approximateDistanceMeters?.let { distance ->
            Text(
                text = "Approx. ${formatDistance(distance)}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        FinderControls(
            finder = finder,
            onStart = viewModel::startNearbyFinder,
            onStop = viewModel::stopNearbyFinder,
            onRequestPermissions = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            }
        )

        Spacer(modifier = Modifier.height(18.dp))
        SignalDebugCard(finder)
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

@Composable
private fun FinderIndicator(state: NearbyFinderState) {
    val proximityLevel = when (state.signal.proximity) {
        ProximityBucket.VERY_CLOSE -> 1f
        ProximityBucket.CLOSE -> 0.82f
        ProximityBucket.NEARBY -> 0.62f
        ProximityBucket.FAR -> 0.42f
        ProximityBucket.SIGNAL_LOST -> 0.25f
    }
    val level by animateFloatAsState(targetValue = proximityLevel, animationSpec = tween(450), label = "finder-level")
    val infinite = rememberInfiniteTransition(label = "finder-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "finder-pulse-phase"
    )
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val base = size.minDimension / 2f
            repeat(3) { index ->
                val phase = (pulse + index / 3f) % 1f
                val radius = base * (0.34f + (0.58f * phase))
                val alpha = (1f - phase) * 0.16f * level
                drawCircle(color = primary.copy(alpha = alpha), radius = radius)
            }
            drawCircle(
                color = if (state.signal.proximity == ProximityBucket.SIGNAL_LOST) muted.copy(alpha = 0.14f) else primary.copy(alpha = 0.16f),
                radius = base * (0.28f + 0.08f * level)
            )
            drawCircle(
                color = if (state.signal.proximity == ProximityBucket.SIGNAL_LOST) muted.copy(alpha = 0.55f) else primary,
                radius = base * (0.12f + 0.035f * level)
            )
        }
    }
}

@Composable
private fun FinderControls(
    finder: NearbyFinderState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    when (finder.status) {
        NearbyFinderStatus.PERMISSION_REQUIRED -> Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Allow Nearby devices & location") }

        NearbyFinderStatus.BLUETOOTH_OFF -> {
            Text(
                "Turn on Bluetooth, then try again.",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }

        NearbyFinderStatus.NO_SELECTED_DEVICE -> {
            Text(
                "Connect or select your AirPods first.",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        else -> if (finder.running) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop finding") }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start finding") }
        }
    }
}

@Composable
private fun SignalDebugCard(state: NearbyFinderState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("Signal details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            DebugRow("Raw RSSI", state.signal.rawRssi?.let { "$it dBm" } ?: "—")
            DebugRow("Smoothed", state.signal.smoothedRssi?.let { "${it.roundToInt()} dBm" } ?: "—")
            DebugRow("Age", state.signal.sampleAgeMillis?.let(::formatAge) ?: "No sample")
            if (state.signal.stale && state.signal.rawRssi != null) {
                Text(
                    "Signal is stale; hold position or move back into range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun statusLine(state: NearbyFinderState): String = when (state.status) {
    NearbyFinderStatus.STOPPED -> "Ready"
    NearbyFinderStatus.WAITING_FOR_SIGNAL -> if (state.signal.rawRssi == null) {
        "Waiting for an AirPods proximity broadcast…"
    } else {
        "Signal lost — move back into range"
    }
    NearbyFinderStatus.ACTIVE -> state.signal.trend.label
    NearbyFinderStatus.PERMISSION_REQUIRED -> "Nearby devices and location permission required"
    NearbyFinderStatus.BLUETOOTH_OFF -> "Bluetooth is off"
    NearbyFinderStatus.NO_SELECTED_DEVICE -> "No AirPods selected"
    NearbyFinderStatus.ERROR -> state.errorMessage ?: "Finder unavailable"
}

private fun formatDistance(meters: Double): String = if (meters < 1.0) {
    "<1 m"
} else {
    if (meters % 1.0 == 0.0) "${meters.roundToInt()} m" else "$meters m"
}

private fun formatAge(ageMillis: Long): String = when {
    ageMillis < 1_000L -> "<1 s"
    ageMillis < 10_000L -> "${ageMillis / 1_000L} s"
    else -> "${ageMillis / 1_000L} s (stale)"
}
