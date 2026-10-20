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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.icons.MaterialIcons

@Composable
fun PermissionsPage(
    onBackward: () -> Unit,
    onForward: () -> Unit
) {

    var grantingAll = false

    val context = LocalContext.current
    val canDrawOverlays = remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val healthPermissions = rememberPermissionState(
        HealthPermission.getWritePermission(HeartRateRecord::class)
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

    val phonePermissionState = rememberMultiplePermissionsState(
        listOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.ANSWER_PHONE_CALLS"
        )
    ) {
        if (grantingAll) {
            if (!healthPermissions.status.isGranted) healthPermissions.launchPermissionRequest()
            else if (!canDrawOverlays.value) canDrawOverlays.value = Settings.canDrawOverlays(context)
        }
    }


    val notificationPermissionState = rememberPermissionState("android.permission.POST_NOTIFICATIONS") {
        if (grantingAll) {
            if (!phonePermissionState.allPermissionsGranted) phonePermissionState.launchMultiplePermissionRequest()
            else if (!healthPermissions.status.isGranted) healthPermissions.launchPermissionRequest()
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
            else if (!healthPermissions.status.isGranted) healthPermissions.launchPermissionRequest()
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
            color = MaterialTheme.colorScheme.surfaceContainerLow,
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
            StyledList(title = stringResource(R.string.required_permissions)) {
                val animatedBluetoothIconColor by animateColorAsState(if (bluetoothPermissionsState.allPermissionsGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                val animatedBluetoothContainerColor by animateColorAsState(
                    if (bluetoothPermissionsState.allPermissionsGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )

                styledListItem(
                    contentText = stringResource(R.string.bluetooth),
                    onClick = if (!bluetoothPermissionsState.allPermissionsGranted) {
                        {
                            grantingAll = false
                            bluetoothPermissionsState.launchMultiplePermissionRequest()
                        }
                    } else null,
                    supportingText = stringResource(R.string.permission_description_bluetooth),
                    orientation = StyledListItemOrientation.Vertical,
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
                                imageVector = MaterialIcons.Bluetooth,
                                contentDescription = stringResource(R.string.bluetooth),
                                modifier = Modifier.size(24.dp),
                                tint = animatedBluetoothIconColor
                            )
                        }
                    },
                )
            }
            StyledList(title = stringResource(R.string.optional_permissions)) {
                val animatedNotificationsIconColor by animateColorAsState(
                    if (notificationPermissionState.status.isGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                val animatedNotificationsContainerColor by animateColorAsState(
                    if (notificationPermissionState.status.isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
                val animatedPhoneIconColor by animateColorAsState(if (phonePermissionState.allPermissionsGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                val animatedPhoneContainerColor by animateColorAsState(
                    if (phonePermissionState.allPermissionsGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )

                styledListItem(
                    contentText = stringResource(R.string.notifications),
                    onClick = if (!notificationPermissionState.status.isGranted) {
                        {
                            grantingAll = false
                            notificationPermissionState.launchPermissionRequest()
                        }
                    } else null,
                    supportingText = stringResource(R.string.permission_description_notification),
                    orientation = StyledListItemOrientation.Vertical,
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
                                imageVector = MaterialIcons.Notifications,
                                contentDescription = stringResource(R.string.notifications),
                                modifier = Modifier.size(24.dp),
                                tint = animatedNotificationsIconColor
                            )
                        }
                    },
                )
                styledListItem(
                    contentText = stringResource(R.string.phone),
                    onClick = if (!phonePermissionState.allPermissionsGranted) {
                        {
                            grantingAll = false
                            phonePermissionState.launchMultiplePermissionRequest()
                        }
                    } else null,
                    supportingText = stringResource(R.string.permission_description_phone),
                    orientation = StyledListItemOrientation.Vertical,
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
                                imageVector = MaterialIcons.Call,
                                contentDescription = stringResource(R.string.phone),
                                modifier = Modifier.size(24.dp),
                                tint = animatedPhoneIconColor
                            )
                        }
                    },
                )
            }

            val animatedHealthConnectIconColor by animateColorAsState(if (healthPermissions.status.isGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            val animatedHealthConnectContainerColor by animateColorAsState(if (healthPermissions.status.isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)

            StyledListItem(
                contentText = stringResource(R.string.permission_healthconnect),
                onClick = if (!healthPermissions.status.isGranted) {
                    {
                        grantingAll = false
                        healthPermissions.launchPermissionRequest()
                    }
                } else null,
                supportingText = stringResource(R.string.permission_description_healthconnect),
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                animatedHealthConnectContainerColor,
                                MaterialShapes.SoftBurst.normalized()
                                    .toShape()
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MaterialIcons.VitalSigns,
                            contentDescription = stringResource(R.string.heart_rate),
                            modifier = Modifier.size(24.dp),
                            tint = animatedHealthConnectIconColor
                        )
                    }
                }
            )

            val animatedOverlayIconColor by animateColorAsState(if (canDrawOverlays.value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            val animatedOverlayContainerColor by animateColorAsState(if (canDrawOverlays.value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)

            StyledListItem(
                contentText = stringResource(R.string.permission_overlay),
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
                supportingText = stringResource(R.string.permission_description_overlay),
                orientation = StyledListItemOrientation.Vertical,
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
                            imageVector = MaterialIcons.Overlay,
                            contentDescription = stringResource(R.string.permission_overlay),
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
                        contentDescription = stringResource(R.string.back),
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
                        text = stringResource(R.string.grant_all),
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
                        contentDescription = stringResource(R.string.next),
                        modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                    )
                }
            }
        }
    }
}
