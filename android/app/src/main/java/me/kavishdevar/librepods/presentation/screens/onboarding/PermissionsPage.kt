package me.kavishdevar.librepods.presentation.screens.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import me.kavishdevar.librepods.presentation.MaterialIcons
import me.kavishdevar.librepods.presentation.components.ListItemOrientation
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsPage(
    onBackward: () -> Unit,
    onForward: () -> Unit
) {

    var grantingAll = false

    val context = LocalContext.current
    val canDrawOverlays = remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val phonePermissionState = rememberMultiplePermissionsState(
        listOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.ANSWER_PHONE_CALLS"
        )
    ) {
        if (grantingAll) {
            if (!canDrawOverlays.value) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
            }
        }
    }


    val notificationPermissionState = rememberPermissionState("android.permission.POST_NOTIFICATIONS") {
        if (grantingAll) {
            if (!phonePermissionState.allPermissionsGranted) phonePermissionState.launchMultiplePermissionRequest()
            else if (!canDrawOverlays.value) canDrawOverlays.value = Settings.canDrawOverlays(context)
        }
    }


    val bluetoothPermissionsState = rememberMultiplePermissionsState(
        listOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH_ADVERTISE"
        )
    ) {
        if (grantingAll) {
            if (!notificationPermissionState.status.isGranted) notificationPermissionState.launchPermissionRequest()
            else if (!phonePermissionState.allPermissionsGranted) phonePermissionState.launchMultiplePermissionRequest()
            else if (!canDrawOverlays.value) canDrawOverlays.value = Settings.canDrawOverlays(context)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays.value = Settings.canDrawOverlays(context)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier.background(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(42.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StyledList(title = "Required Permissions") {
                val animatedBluetoothIconColor by animateColorAsState(if (bluetoothPermissionsState.allPermissionsGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                val animatedBluetoothContainerColor by animateColorAsState(
                    if (bluetoothPermissionsState.allPermissionsGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                )

                StyledListItem(
                    name = "Bluetooth",
                    onClick = if (!bluetoothPermissionsState.allPermissionsGranted) {
                        {
                            grantingAll = false
                            bluetoothPermissionsState.launchMultiplePermissionRequest()
                        }
                    } else null,
                    description = "Required to communicate with AirPods",
                    orientation = ListItemOrientation.Vertical,
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    animatedBluetoothContainerColor,
                                    MaterialShapes.SoftBurst.normalized()
                                        .toShape()
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MaterialIcons.bluetooth,
                                contentDescription = "bluetooth",
                                modifier = Modifier.size(24.dp),
                                tint = animatedBluetoothIconColor
                            )
                        }
                    },
                )
            }
            StyledList(title = "Optional Permissions") {
                val animatedNotificationsIconColor by animateColorAsState(
                    if (notificationPermissionState.status.isGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                val animatedNotificationsContainerColor by animateColorAsState(
                    if (notificationPermissionState.status.isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                )
                val animatedPhoneIconColor by animateColorAsState(if (phonePermissionState.allPermissionsGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                val animatedPhoneContainerColor by animateColorAsState(
                    if (phonePermissionState.allPermissionsGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                )

                StyledListItem(
                    name = "Notifications",
                    onClick = if (!notificationPermissionState.status.isGranted) {
                        {
                            grantingAll = false
                            notificationPermissionState.launchPermissionRequest()
                        }
                    } else null,
                    description = "Show battery status",
                    orientation = ListItemOrientation.Vertical,
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    animatedNotificationsContainerColor,
                                    MaterialShapes.SoftBurst.normalized()
                                        .toShape()
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MaterialIcons.notifications,
                                contentDescription = "notifications",
                                modifier = Modifier.size(24.dp),
                                tint = animatedNotificationsIconColor
                            )
                        }
                    },
                )
                StyledListItem(
                    name = "Phone",
                    onClick = if (!phonePermissionState.allPermissionsGranted) {
                        {
                            grantingAll = false
                            phonePermissionState.launchMultiplePermissionRequest()
                        }
                    } else null,
                    description = "Respond to phone calls with head gestures",
                    orientation = ListItemOrientation.Vertical,
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    animatedPhoneContainerColor,
                                    MaterialShapes.SoftBurst.normalized()
                                        .toShape()
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MaterialIcons.call,
                                contentDescription = "bluetooth",
                                modifier = Modifier.size(24.dp),
                                tint = animatedPhoneIconColor
                            )
                        }
                    },
                )
            }

            val animatedOverlayIconColor by animateColorAsState(if (canDrawOverlays.value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            val animatedOverlayContainerColor by animateColorAsState(if (canDrawOverlays.value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)

            StyledListItem(
                name = "Display over other apps",
                onClick = if (!canDrawOverlays.value) {
                    {
                        grantingAll = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri()
                        )
                        context.startActivity(intent)
                    }
                } else null,
                description = "Show popups when AirPods are nearby or audio switches to them.",
                orientation = ListItemOrientation.Vertical,
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                animatedOverlayContainerColor,
                                MaterialShapes.SoftBurst.normalized()
                                    .toShape()
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MaterialIcons.stack,
                            contentDescription = "bluetooth",
                            modifier = Modifier.size(24.dp),
                            tint = animatedOverlayIconColor
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledIconButton(
                    onClick = onBackward,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow)),
                    shape = IconButtonDefaults.mediumRoundShape
                ) {
                    Icon(
                        Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = "backward",
                        modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                    )
                }
                Button(
                    onClick = {
                        grantingAll = true
                        if (!bluetoothPermissionsState.allPermissionsGranted) bluetoothPermissionsState.launchMultiplePermissionRequest()
                        else if (!notificationPermissionState.status.isGranted) notificationPermissionState.launchPermissionRequest()
                        else if (!phonePermissionState.allPermissionsGranted) phonePermissionState.launchMultiplePermissionRequest()
                        else if (!canDrawOverlays.value) canDrawOverlays.value =
                            Settings.canDrawOverlays(context)
                    },
                    modifier = Modifier
                        .height(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow).height)
                        .weight(1f),
                    enabled = !bluetoothPermissionsState.allPermissionsGranted || !notificationPermissionState.status.isGranted || !phonePermissionState.allPermissionsGranted || !canDrawOverlays.value
                ) {
                    Text(
                        text = "Grant all",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                FilledIconButton(
                    onClick = onForward,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(
                            IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow)
                        ),
                    shape = IconButtonDefaults.mediumRoundShape,
                    enabled = bluetoothPermissionsState.allPermissionsGranted
                ) {
                    Icon(
                        Icons.AutoMirrored.Default.ArrowForward,
                        contentDescription = "forward",
                        modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                    )
                }
            }
        }
    }
}
