package me.kavishdevar.librepods.presentation.screens

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.components.primitives.StyledIconButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesignSystem
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.utils.createAirPodsBatteryRichText
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun DeviceListScreen(
    devices: Map<MacAddress, Device<*, *, *>>,
    navigateToAppSettings: () -> Unit,
    navigateToDevice: (MacAddress) -> Unit,
    navigateToHeartRateScreen: (MacAddress) -> Unit
) {
    val scrollState = rememberScrollState()

    val isPremium by BillingManager.provider.isPremium.collectAsState()

    StyledScaffold(
        title = stringResource(R.string.app_name),
        navigateBack = null,
        actionButtons = listOf(
            { scaffoldBackdrop ->
                if (LocalDesignSystem.current == DesignSystem.Material) {
                    FilledTonalIconButton(
                        onClick = navigateToAppSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Uniform)),

                        ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings),
                            modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                        )
                    }
                } else {
                    StyledIconButton(
                        onClick = navigateToAppSettings,
                        backdrop = scaffoldBackdrop
                    ) {
                        Icon(
                            imageVector = LocalIcons.current.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        )
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Log.d("DeviceListScreen", "Rendering device list with ${devices.size} devices")

            devices.forEach { (macAddress, device) ->
                val connectionState by device.connectionState.collectAsState()
                val deviceState by device.state.collectAsState()
                val deviceMetadata by device.metadata.collectAsState()

                fun ConnectionState.shape() = when (this) {
                    ConnectionState.DISCONNECTED -> MaterialShapes.Circle.normalized()
                    ConnectionState.CONNECTING -> MaterialShapes.SoftBurst.normalized()
                    ConnectionState.CONNECTED -> MaterialShapes.SoftBurst.normalized()
                    ConnectionState.DISCONNECTING -> MaterialShapes.Cookie4Sided.normalized()
                    ConnectionState.AVAILABLE -> MaterialShapes.Circle.normalized()
                }

                val connectingShapes = remember {
                    listOf(
                        MaterialShapes.Cookie4Sided.normalized(),
                        MaterialShapes.SoftBurst.normalized(),
                        MaterialShapes.Cookie9Sided.normalized(),
                        MaterialShapes.Pentagon.normalized(),
                        MaterialShapes.Pill.normalized(),
                        MaterialShapes.Sunny.normalized(),
                        MaterialShapes.Cookie4Sided.normalized(),
                        MaterialShapes.Oval.normalized(),
                    )
                }

                val connectingMorphs = remember {
                    buildList {
                        connectingShapes.zipWithNext { a, b ->
                            add(Morph(a, b))
                        }
                        add(Morph(connectingShapes.last(), connectingShapes.first()))
                    }
                }

                var previousState by remember { mutableStateOf(connectionState) }

                var pressed by remember { mutableStateOf(false) }

                val touchMorph = remember {
                    Morph(
                        if (connectionState == ConnectionState.CONNECTED) MaterialShapes.SoftBurst.normalized() else MaterialShapes.Circle.normalized(),
                        MaterialShapes.Cookie4Sided.normalized()
                    )
                }

                val touchProgress = remember { Animatable(0f) }

                LaunchedEffect(pressed) {
                    touchProgress.animateTo(
                        targetValue = if (pressed) 1f else 0f,
                        animationSpec = spring(
                            dampingRatio = 0.6f,
                            stiffness = 200f,
                            visibilityThreshold = 0.1f
                        )
                    )
                }

                var currentMorphIndex by remember { mutableIntStateOf(0) }
                var morphRotationTarget by remember { mutableFloatStateOf(90f) }

                val morphProgress = remember { Animatable(0f) }
                val globalRotation = remember { Animatable(0f) }

                LaunchedEffect(connectionState) {
                    if (connectionState == ConnectionState.CONNECTING) {
                        pressed = false
                        currentMorphIndex = 0
                        morphRotationTarget = 90f

                        morphProgress.stop()
                        morphProgress.snapTo(0f)

                        globalRotation.stop()
                        globalRotation.snapTo(0f)

                        coroutineScope {
                            launch {
                                while (isActive) {
                                    val deferred = async {
                                        morphProgress.animateTo(
                                            1f,
                                            spring(
                                                dampingRatio = 0.6f,
                                                stiffness = 200f,
                                                visibilityThreshold = 0.1f
                                            )
                                        )

                                        currentMorphIndex =
                                            (currentMorphIndex + 1) % connectingMorphs.size

                                        morphProgress.snapTo(0f)

                                        morphRotationTarget =
                                            (morphRotationTarget + 90f) % 360f
                                    }

                                    delay(650.milliseconds)
                                    deferred.await()
                                }
                            }

                            launch {
                                globalRotation.animateTo(
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        tween(4666, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart
                                    )
                                )
                            }
                        }
                    } else {
                        globalRotation.stop()
                        morphProgress.stop()

                        morphProgress.snapTo(0f)

                        morphProgress.animateTo(
                            1f,
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )

                        previousState = connectionState
                    }
                }

                val morph = remember(
                    connectionState,
                    previousState,
                    currentMorphIndex
                ) {
                    if (connectionState == ConnectionState.CONNECTING) {
                        connectingMorphs[currentMorphIndex]
                    } else {
                        Morph(previousState.shape(), connectionState.shape())
                    }
                }

                val iconBackgroundColor by animateColorAsState(
                    targetValue = when (connectionState) {
                        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                        ConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.surfaceContainer
                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceDim
                        ConnectionState.AVAILABLE -> MaterialTheme.colorScheme.surfaceBright
                    },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "iconBackgroundColor"
                )

                val iconColor by animateColorAsState(
                    targetValue = when (connectionState) {
                        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.onSecondaryContainer
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
                        ConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.onSurface
                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.contentColorFor(
                            MaterialTheme.colorScheme.surfaceDim
                        )

                        ConnectionState.AVAILABLE -> MaterialTheme.colorScheme.contentColorFor(
                            MaterialTheme.colorScheme.surfaceBright
                        )
                    },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "iconColor"
                )

                val path = remember { Path() }
                val matrix = remember { Matrix() }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StyledListItem(
                        onClick = if (connectionState == ConnectionState.CONNECTED) {
                            { navigateToDevice(macAddress) }
                        } else null,
                        contentText = deviceMetadata.name,
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onPress = {
                                                pressed = true
                                                tryAwaitRelease()
                                                pressed = false
                                            },
                                            onTap = {
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    when (connectionState) {
                                                        ConnectionState.CONNECTED -> device.disconnect()
                                                        ConnectionState.DISCONNECTED -> device.connect()
                                                        else -> {}
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    .drawBehind {
                                        val activeMorph: Morph
                                        val activeProgress: Float

                                        if (connectionState != ConnectionState.CONNECTING && touchProgress.value > 0f) {
                                            activeMorph = touchMorph
                                            activeProgress = touchProgress.value
                                        } else {
                                            activeMorph = morph
                                            activeProgress = morphProgress.value
                                        }

                                        val shapePath = activeMorph.toPath(
                                            progress = activeProgress,
                                            path = path
                                        )

                                        val bounds = shapePath.getBounds()

                                        val scale = min(
                                            size.width / bounds.width,
                                            size.height / bounds.height
                                        ) * 0.9f

                                        matrix.reset()
                                        matrix.scale(scale, scale)

                                        shapePath.transform(matrix)

                                        shapePath.translate(
                                            size.center - shapePath.getBounds().center
                                        )

                                        val rotation =
                                            if (connectionState == ConnectionState.CONNECTING) {
                                                morphProgress.value * 90f +
                                                    morphRotationTarget +
                                                    globalRotation.value
                                            } else {
                                                0f
                                            }

                                        rotate(rotation) {
                                            drawPath(
                                                path = shapePath,
                                                color = iconBackgroundColor
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = LocalIcons.current.fromName(deviceMetadata.iconName)
                                        ?: LocalIcons.current.Headphones,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = iconColor
                                )
                            }
                        },
                        supportingContent = {
                            when (connectionState) {
                                ConnectionState.AVAILABLE -> {
                                    when (deviceState) {
                                        is AppleState -> {
//                                        battery from BLE
                                        }

                                        else -> Text(
                                            text = "????",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                ConnectionState.DISCONNECTING, ConnectionState.DISCONNECTED -> {
                                    Text(
                                        text = macAddress.value,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                ConnectionState.CONNECTING -> {
                                    Text(
                                        text = stringResource(R.string.connecting),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                ConnectionState.CONNECTED -> {
                                    when (deviceState) {
                                        is AppleState -> {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                val deviceState = deviceState as AppleState
                                                val deviceMetadata = deviceMetadata as AppleMetadata

                                                val batteryRichText =
                                                    createAirPodsBatteryRichText(
                                                        battery = deviceState.battery,
                                                        airPodsSpec = AirPodsSpecs.getSpec(
                                                            deviceMetadata.model
                                                        )
                                                    )

                                                Text(
                                                    text = batteryRichText.text,
                                                    inlineContent = batteryRichText.inlineContent,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        orientation = StyledListItemOrientation.Vertical
                    )

                    if (connectionState == ConnectionState.CONNECTED) {
                        when (deviceState) {
                            is AppleState -> {
                                val deviceState = deviceState as AppleState
                                val deviceMetadata = deviceMetadata as AppleMetadata

                                val listeningModeCapability = AirPodsSpecs.getSpec(deviceMetadata.model).baseCapabilities.contains(BaseCapability.LISTENING_MODE)
                                val conversationAwarenessCapability = AirPodsSpecs.getSpec(deviceMetadata.model).baseCapabilities.contains(BaseCapability.CONVERSATION_AWARENESS)
                                val hrmCapability = AirPodsSpecs.getSpec(deviceMetadata.model).baseCapabilities.contains(BaseCapability.HRM)

                                if (conversationAwarenessCapability || hrmCapability) {
                                    StyledList {
                                        if (listeningModeCapability) {
                                            styledListItem(
                                                content = {
                                                    NoiseControlSettings(
                                                        modifier = Modifier.padding(vertical = 8.dp).height(64.dp),
                                                        showOffListeningMode = deviceState.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.get(0) == 1.toByte(),
                                                        noiseControlModeValue = deviceState.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.get(0)?.toInt() ?: 2,
                                                        onNoiseControlModeChanged = { newMode ->
                                                            CoroutineScope(Dispatchers.IO).launch {
                                                                (device as AppleDevice).setControlCommand(
                                                                    ControlCommandIdentifier.LISTENING_MODE,
                                                                    newMode.toByte()
                                                                )
                                                            }
                                                        },
                                                        adaptiveStrength = deviceState.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.get(0)?.toInt() ?: 50,
                                                        onAdaptiveStrengthChanged = {
                                                            CoroutineScope(Dispatchers.IO).launch {
                                                                (device as AppleDevice).setControlCommand(
                                                                    ControlCommandIdentifier.AUTO_ANC_STRENGTH,
                                                                    it
                                                                )
                                                            }
                                                        },
                                                        showLabels = false
                                                    )
                                                }
                                            )
                                        }
                                        if (conversationAwarenessCapability) {
                                            styledToggle(
                                                label = stringResource(R.string.conversation_awareness),
                                                checked = deviceState.controlStates[ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG]?.get(
                                                    0
                                                ) == 1.toByte(),
                                                onCheckedChange = { newMode: Boolean ->
                                                    CoroutineScope(
                                                        Dispatchers.IO
                                                    ).launch {
                                                        (device as AppleDevice).setControlCommand(
                                                            ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG,
                                                            newMode
                                                        )
                                                    }
                                                },
                                                enabled = isPremium
                                            )
                                        }

                                        if (hrmCapability) {
                                            styledListItem(
                                                contentText = stringResource(R.string.heart_rate),
                                                supportingText = deviceState.currentHeartRate?.let { "${it.bpm} bpm" },
                                                onClick = {
                                                    navigateToHeartRateScreen(
                                                        macAddress
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.padding(top = bottomPadding))
        }
    }
}
