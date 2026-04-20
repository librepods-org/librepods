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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.composables.AdaptiveRainbowBrush
import me.kavishdevar.librepods.composables.ControlCenterNoiseControlSegmentedButton
import me.kavishdevar.librepods.composables.IconAreaSize
import me.kavishdevar.librepods.composables.VerticalVolumeSlider
import me.kavishdevar.librepods.constants.AirPodsNotifications
import me.kavishdevar.librepods.constants.NoiseControlMode
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.ui.theme.LibrePodsTheme
import me.kavishdevar.librepods.utils.AACPManager
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs

class QuickSettingsDialogActivity : ComponentActivity() {

    private var airPodsService: AirPodsService? = null
    private var isBound = false

    private var isNoiseControlExpandedState by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AirPodsService.LocalBinder
            airPodsService = binder.getService()
            isBound = true
            Log.d("QSActivity", "Service bound")
            setContent {
                LibrePodsTheme {
                    DraggableDismissBox(
                        onDismiss = { finish() },
                        onlyCollapseWhenClicked = {
                            if (isNoiseControlExpandedState) {
                                isNoiseControlExpandedState = false
                                true
                            } else {
                                false
                            }
                        }
                    ) {
                        if (isBound && airPodsService != null) {
                            NewControlCenterDialogContent(
                                service = airPodsService,
                                isNoiseControlExpanded = isNoiseControlExpandedState,
                                onNoiseControlExpandedChange = { isNoiseControlExpandedState = it }
                            )
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            airPodsService = null
            Log.d("QSActivity", "Service unbound")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.setGravity(Gravity.BOTTOM)

        Intent(this, AirPodsService::class.java).also { intent ->
            bindService(intent, connection, BIND_AUTO_CREATE)
        }

        setContent {
            LibrePodsTheme {
                DraggableDismissBox(
                    onDismiss = { finish() },
                    onlyCollapseWhenClicked = {
                        if (isNoiseControlExpandedState) {
                            isNoiseControlExpandedState = false
                            true
                        } else {
                            false
                        }
                    }
                ) {
                    if (isBound && airPodsService != null) {
                        NewControlCenterDialogContent(
                            service = airPodsService,
                            isNoiseControlExpanded = isNoiseControlExpandedState,
                            onNoiseControlExpandedChange = { isNoiseControlExpandedState = it }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

@Composable
fun DraggableDismissBox(
    onDismiss: () -> Unit,
    onlyCollapseWhenClicked: () -> Boolean,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val dismissThreshold = 400f

    val animatedOffset = remember { Animatable(0f) }
    val animatedScale = remember { Animatable(1f) }
    val animatedAlpha = remember { Animatable(1f) }

    val backgroundAlpha by animateFloatAsState(
        targetValue = if (isDragging) {
            val dragProgress = (abs(dragOffset) / 800f).coerceIn(0f, 0.8f)
            1f - dragProgress
        } else 1f,
        label = "BackgroundFade"
    )

    LaunchedEffect(isDragging) {
        if (!isDragging) {
            if (abs(dragOffset) < dismissThreshold) {
                val springSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessHigh,
                    visibilityThreshold = 0.1f
                )
                launch { animatedOffset.animateTo(0f, springSpec) }
                launch { animatedScale.animateTo(1f, springSpec) }
                launch { animatedAlpha.animateTo(1f, tween(100)) }
                dragOffset = 0f
            }
        }
    }

    LaunchedEffect(dragOffset, isDragging) {
        if (isDragging) {
            val dragProgress = (abs(dragOffset) / 1000f).coerceIn(0f, 0.5f)

            animatedOffset.snapTo(dragOffset)
            animatedScale.snapTo(1f - dragProgress * 0.3f)
            animatedAlpha.snapTo(1f - dragProgress * 0.7f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f * backgroundAlpha))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        if (abs(dragOffset) > dismissThreshold) {
                            coroutineScope.launch {
                                val direction = if (dragOffset > 0) 1f else -1f

                                launch {
                                    animatedOffset.animateTo(
                                        direction * 1500f,
                                        tween(350, easing = FastOutSlowInEasing)
                                    )
                                }
                                launch { animatedScale.animateTo(0.7f, tween(350)) }
                                launch { animatedAlpha.animateTo(0f, tween(250)) }

                                kotlinx.coroutines.delay(350)
                                onDismiss()
                            }
                        }
                    },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                    }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onlyCollapseWhenClicked()
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(
                    translationY = animatedOffset.value,
                    scaleX = animatedScale.value,
                    scaleY = animatedScale.value,
                    alpha = animatedAlpha.value
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            content()
        }
    }
}

@SuppressLint("UnspecifiedRegisterReceiverFlag")
@Composable
fun NewControlCenterDialogContent(
    service: AirPodsService?,
    isNoiseControlExpanded: Boolean,
    onNoiseControlExpandedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val textColor = Color.White

    var currentAncMode by remember { mutableStateOf(NoiseControlMode.TRANSPARENCY) }
    var isConvAwarenessEnabled by remember { mutableStateOf(false) }

    val isOffModeEnabled = remember { sharedPreferences.getBoolean("off_listening_mode", true) }
    val availableModes = remember(isOffModeEnabled) {
        mutableListOf(
            NoiseControlMode.TRANSPARENCY,
            NoiseControlMode.ADAPTIVE,
            NoiseControlMode.NOISE_CANCELLATION
        ).apply {
            if (isOffModeEnabled) {
                add(0, NoiseControlMode.OFF)
            }
        }
    }

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolumeInt by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val animatedVolumeFraction by animateFloatAsState(
        targetValue = currentVolumeInt.toFloat() / maxVolume.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "VolumeAnimation"
    )
    var liveDragFraction by remember { mutableFloatStateOf(animatedVolumeFraction) }
    var isDraggingVolume by remember { mutableStateOf(false) }
    LaunchedEffect(animatedVolumeFraction, isDraggingVolume) {
        if (!isDraggingVolume) {
            liveDragFraction = animatedVolumeFraction
        }
    }

    DisposableEffect(service, availableModes) {
        val ancReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AirPodsNotifications.ANC_DATA && service != null) {
                    val newModeOrdinal = intent.getIntExtra("data", NoiseControlMode.TRANSPARENCY.ordinal + 1) - 1
                    val newMode = NoiseControlMode.entries.getOrElse(newModeOrdinal) { NoiseControlMode.TRANSPARENCY }
                    if (availableModes.contains(newMode)) {
                         currentAncMode = newMode
                    } else if (newMode == NoiseControlMode.OFF && !isOffModeEnabled) {
                        currentAncMode = NoiseControlMode.TRANSPARENCY
                    }
                    Log.d("QSActivity", "ANC Receiver updated mode to: $currentAncMode (available: ${availableModes.joinToString()})")
                }
            }
        }
        val filter = IntentFilter(AirPodsNotifications.ANC_DATA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(ancReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(ancReceiver, filter)
        }

        service?.let {
            val initialModeOrdinal = it.getANC().minus(1)
            var initialMode = NoiseControlMode.entries.getOrElse(initialModeOrdinal) { NoiseControlMode.TRANSPARENCY }
            if (!availableModes.contains(initialMode)) {
                initialMode = NoiseControlMode.TRANSPARENCY
            }
            currentAncMode = initialMode
            isConvAwarenessEnabled = sharedPreferences.getBoolean("conversational_awareness", true)
            Log.d("QSActivity", "Initial ANC: $currentAncMode, ConvAware: $isConvAwarenessEnabled")
        }

        onDispose {
            context.unregisterReceiver(ancReceiver)
        }
    }

    DisposableEffect(Unit) {
        val volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val newVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (newVolume != currentVolumeInt) {
                        currentVolumeInt = newVolume
                        Log.d("QSActivity", "Volume Receiver updated volume to: $currentVolumeInt")
                    }
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        context.registerReceiver(volumeReceiver, filter)
        onDispose {
            context.unregisterReceiver(volumeReceiver)
        }
    }

    val deviceName = remember { sharedPreferences.getString("name", "AirPods") ?: "AirPods" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(horizontal = 24.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (service != null) {
            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.airpods),
                    contentDescription = "Device Icon",
                    tint = textColor.copy(alpha = 0.8f),
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = deviceName,
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(32.dp))

                VerticalVolumeSlider(
                    displayFraction = animatedVolumeFraction,
                    maxVolume = maxVolume,
                    onVolumeChange = { newVolume ->
                        currentVolumeInt = newVolume
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                        } catch (e: Exception) { Log.e("QSActivity", "Failed to set volume", e) }
                    },
                    initialFraction = animatedVolumeFraction,
                    onDragStateChange = { dragging -> isDraggingVolume = dragging },
                    baseSliderHeight = 400.dp,
                    baseSliderWidth = 145.dp,
                    baseCornerRadius = 48.dp,
                    maxStretchFactor = 1.15f,
                    minCompressionFactor = 0.875f,
                    stretchSensitivity = 0.3f,
                    compressionSensitivity = 0.3f,
                    cornerRadiusChangeFactor = -0.5f,
                    directionalStretchRatio = 0.75f,
                    modifier = Modifier
                        .width(145.dp)
                        .padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 72.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(
                    targetState = isNoiseControlExpanded,
                    animationSpec = tween(durationMillis = 300),
                    label = "NoiseControlCrossfade"
                ) { expanded ->
                    if (expanded) {
                        ControlCenterNoiseControlSegmentedButton(
                            availableModes = availableModes,
                            selectedMode = currentAncMode,
                            onModeSelected = { newMode ->
                                service.aacpManager.sendControlCommand(
                                    identifier = AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                                    value = newMode.ordinal + 1
                                )
                                currentAncMode = newMode
                            },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(0.85f),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            val noiseControlButtonBrush = if (currentAncMode == NoiseControlMode.ADAPTIVE) {
                                AdaptiveRainbowBrush
                            } else {
                                null
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(IconAreaSize)
                                        .clip(CircleShape)
                                        .background(
                                            brush = noiseControlButtonBrush ?:
                                                Brush.linearGradient(colors = listOf(Color(0xFF0A84FF), Color(0xFF0A84FF)))
                                        )
                                        .clickable(
                                            onClick = { onNoiseControlExpandedChange(true) },
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = getModeIconRes(currentAncMode)),
                                        contentDescription = getModeLabel(currentAncMode),
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = getModeLabel(currentAncMode),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.width(24.dp))

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(IconAreaSize)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    if (isConvAwarenessEnabled) Color(0xFF0A84FF) else Color(0x593C3C3E),
                                                    if (isConvAwarenessEnabled) Color(0xFF0A84FF) else Color(0x593C3C3E)
                                                )
                                            )
                                        )
                                        .clickable(
                                            onClick = {
                                                val newState = !isConvAwarenessEnabled
                                                service.aacpManager.sendControlCommand(
                                                    identifier = AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                                                    value = newState
                                                )
                                                isConvAwarenessEnabled = newState
                                            },
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.airpods),
                                        contentDescription = "Conversational Awareness",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "Conversational\nAwareness",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

        } else {
            Spacer(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading...", color = textColor)
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

private fun getModeIconRes(mode: NoiseControlMode): Int {
    return when (mode) {
        NoiseControlMode.OFF -> R.drawable.noise_cancellation
        NoiseControlMode.TRANSPARENCY -> R.drawable.transparency
        NoiseControlMode.ADAPTIVE -> R.drawable.adaptive
        NoiseControlMode.NOISE_CANCELLATION -> R.drawable.noise_cancellation
    }
}

private fun getModeLabel(mode: NoiseControlMode): String {
    return when (mode) {
        NoiseControlMode.OFF -> "Off"
        NoiseControlMode.TRANSPARENCY -> "Transparency"
        NoiseControlMode.ADAPTIVE -> "Adaptive"
        NoiseControlMode.NOISE_CANCELLATION -> "Noise Cancel"
    }
}
