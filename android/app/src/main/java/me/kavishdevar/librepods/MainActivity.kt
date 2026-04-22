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

// import me.kavishdevar.librepods.screens.Onboarding
// import me.kavishdevar.librepods.utils.RadareOffsetFinder
//import dagger.hilt.android.AndroidEntryPoint
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.billing.BillingProviderFactory
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.presentation.components.ConfirmationDialog
import me.kavishdevar.librepods.presentation.components.StyledIconButton
import me.kavishdevar.librepods.presentation.screens.AccessibilitySettingsScreen
import me.kavishdevar.librepods.presentation.screens.AdaptiveStrengthScreen
import me.kavishdevar.librepods.presentation.screens.AirPodsSettingsScreen
import me.kavishdevar.librepods.presentation.screens.AppSettingsScreen
import me.kavishdevar.librepods.presentation.screens.CameraControlScreen
import me.kavishdevar.librepods.presentation.screens.DebugScreen
import me.kavishdevar.librepods.presentation.screens.HeadTrackingScreen
import me.kavishdevar.librepods.presentation.screens.HearingAidAdjustmentsScreen
import me.kavishdevar.librepods.presentation.screens.HearingAidScreen
import me.kavishdevar.librepods.presentation.screens.HearingProtectionScreen
import me.kavishdevar.librepods.presentation.screens.LongPress
import me.kavishdevar.librepods.presentation.screens.OpenSourceLicensesScreen
import me.kavishdevar.librepods.presentation.screens.PurchaseScreen
import me.kavishdevar.librepods.presentation.screens.RenameScreen
import me.kavishdevar.librepods.presentation.screens.TransparencySettingsScreen
import me.kavishdevar.librepods.presentation.screens.TroubleshootingScreen
import me.kavishdevar.librepods.presentation.screens.UpdateHearingTestScreen
import me.kavishdevar.librepods.presentation.screens.VersionScreen
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.PurchaseViewModel
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.utils.isSupported
import kotlin.io.encoding.ExperimentalEncodingApi

lateinit var serviceConnection: ServiceConnection
lateinit var connectionStatusReceiver: BroadcastReceiver

//@AndroidEntryPoint
@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    companion object {
        init {
            if (BuildConfig.FLAVOR == "xposed") {
                System.loadLibrary("l2c_fcr_hook")
            }
        }
    }

    @ExperimentalHazeMaterialsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            _root_ide_package_.me.kavishdevar.librepods.presentation.theme.LibrePodsTheme {
                Main()
            }
        }
    }

    override fun onDestroy() {
        try {
            unbindService(serviceConnection)
            Log.d("MainActivity", "Unbound service")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unbinding service: $e")
        }
        try {
            unregisterReceiver(connectionStatusReceiver)
            Log.d("MainActivity", "Unregistered receiver")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unregistering receiver: $e")
        }
        sendBroadcast(Intent(AirPodsNotifications.DISCONNECT_RECEIVERS))
        super.onDestroy()
    }

    override fun onStop() {
        try {
            unbindService(serviceConnection)
            Log.d("MainActivity", "Unbound service")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unbinding service: $e")
        }
        try {
            unregisterReceiver(connectionStatusReceiver)
            Log.d("MainActivity", "Unregistered receiver")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unregistering receiver: $e")
        }
        super.onStop()
    }
}

@ExperimentalHazeMaterialsApi
@SuppressLint("MissingPermission", "InlinedApi", "UnspecifiedRegisterReceiverFlag")
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Main() {
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE)
    if (!isSupported(sharedPreferences)) {
        val showDialog = remember { mutableStateOf(false) }
        val blockTouches = remember { mutableStateOf(false) }
        val tapCount = remember { mutableIntStateOf(0) }
        val lastTapTime = remember { mutableLongStateOf(0L) }

        val hazeState = rememberHazeState()

        LaunchedEffect(blockTouches) {
            if (blockTouches.value) {
                delay(500)
                blockTouches.value = false
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .background(if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)),
            contentAlignment = Alignment.Center
        ) {
            Box (
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (blockTouches.value)
                        {
                            Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                        else Modifier
                    )
            )
            Column (
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Not supported",
                    style = TextStyle(
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSystemInDarkTheme()) Color.White else Color.Black,
                        fontSize = 20.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Row (
                    modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val now = System.currentTimeMillis()

                                if (now - lastTapTime.longValue > 400) {
                                    tapCount.intValue = 0
                                }

                                tapCount.intValue++
                                lastTapTime.longValue = now

                                if (tapCount.intValue >= 7) {
                                    showDialog.value = true
                                    blockTouches.value = true
                                }
                            }
                        )
                    },
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Device Info:",
                        style = TextStyle(
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.Medium,
                            color = if (isSystemInDarkTheme()) Color.White else Color.Black,
                            fontSize = 16.sp
                        ),
                        textAlign = TextAlign.End,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text =
                            "MANUFACTURER=${Build.MANUFACTURER}\n" +
                            "MODEL=${Build.MODEL}\n" +
                            "BUILD_ID=${Build.ID}\n" +
                            "SDK_INT_FULL= ${Build.VERSION.SDK_INT_FULL}\n",
                        style = TextStyle(
                            fontFamily = FontFamily(Font(R.font.hack)),
                            fontWeight = FontWeight.Medium,
                            color = if (isSystemInDarkTheme()) Color.White else Color.Black,
                            fontSize = 16.sp
                        ),
                        textAlign = TextAlign.Start,
                    )
                }
                Text(
                    text = "Check the repository for more info.",
                    style = TextStyle(
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        fontWeight = FontWeight.Medium,
                        color = if (isSystemInDarkTheme()) Color.White else Color.Black,
                        fontSize = 18.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        ConfirmationDialog(
            showDialog = showDialog,
            title = "Confirm device check bypass?",
            message = "Are you sure your device is supported with LibrePods?",
            confirmText = "Yes",
            dismissText = "No",
            onConfirm = {
                showDialog.value = false
                sharedPreferences.edit {
                    tapCount.intValue = 0
                    putBoolean("bypass_device_check", true)
                    val intent = Intent(context, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(intent)
                }
            },
            onDismiss = {
                showDialog.value = false
            },
            hazeState = hazeState
        )

        return
    }

    val isConnected = remember { mutableStateOf(false) }

    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val overlaySkipped = remember {
        mutableStateOf(
            context.getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("overlay_permission_skipped", false)
        )
    }

    BillingManager.provider = BillingProviderFactory.create(context)

    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH_ADVERTISE"
        )
    } else {
        listOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.ACCESS_FINE_LOCATION"
        )
    }
    val otherPermissions = listOf(
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.ANSWER_PHONE_CALLS"
    )
    val allPermissions = bluetoothPermissions + otherPermissions

    val permissionState = rememberMultiplePermissionsState(
        permissions = allPermissions
    )

    val airPodsService = remember { mutableStateOf<AirPodsService?>(null) }

    val airPodsViewModel = remember(airPodsService.value) {
        airPodsService.value?.let { service ->
            AirPodsViewModel(
                service = service,
                sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE),
                controlRepo = ControlCommandRepository(service.aacpManager),
                appContext = context.applicationContext
            )
        }
    }

    LaunchedEffect(Unit) {
        canDrawOverlays = Settings.canDrawOverlays(context)
    }

    if (permissionState.allPermissionsGranted && (canDrawOverlays || overlaySkipped.value)) {

        val navController = rememberNavController()

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            val backButtonBackdrop = rememberLayerBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7))
                    .layerBackdrop(backButtonBackdrop)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "settings",
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { it }, animationSpec = tween(durationMillis = 300)
                        )
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 }, animationSpec = tween(durationMillis = 300)
                        )
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(durationMillis = 300)
                        )
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it }, animationSpec = tween(durationMillis = 300)
                        )
                    }) {
                    composable("settings") {
                        if (airPodsViewModel != null) AirPodsSettingsScreen(airPodsViewModel, navController)
                    }
                    composable("debug") {
                        DebugScreen(navController = navController)
                    }
                    composable("long_press/{bud}") { navBackStackEntry ->
                        if (airPodsViewModel != null) LongPress(
                            viewModel = airPodsViewModel,
                            name = navBackStackEntry.arguments?.getString("bud")!!,
                            navController = navController
                        )
                    }
                    composable("rename") {
                        if (airPodsViewModel != null) RenameScreen(airPodsViewModel)
                    }
                    composable("app_settings") {
                        val appSettingsViewModel: AppSettingsViewModel = viewModel()
                        AppSettingsScreen(navController, appSettingsViewModel)
                    }
                    composable("troubleshooting") {
                        TroubleshootingScreen(navController)
                    }
                    composable("head_tracking") {
                        if (airPodsViewModel != null) HeadTrackingScreen(airPodsViewModel, navController)
                    }
                    composable("accessibility") {
                        if (airPodsViewModel != null) AccessibilitySettingsScreen(airPodsViewModel, navController)
                    }
                    composable("transparency_customization") {
                        if (airPodsViewModel != null) TransparencySettingsScreen(airPodsViewModel)
                    }
                    composable("hearing_aid") {
                        if (airPodsViewModel != null) HearingAidScreen(airPodsViewModel, navController)
                    }
                    composable("hearing_aid_adjustments") {
                        if (airPodsViewModel != null) HearingAidAdjustmentsScreen(airPodsViewModel)
                    }
                    composable("adaptive_strength") {
                        if (airPodsViewModel != null) AdaptiveStrengthScreen(airPodsViewModel, navController)
                    }
                    composable("camera_control") {
                        if (airPodsViewModel != null) CameraControlScreen(airPodsViewModel)
                    }
                    composable("open_source_licenses") {
                        OpenSourceLicensesScreen(navController)
                    }
                    composable("update_hearing_test") {
                        if (airPodsViewModel != null) UpdateHearingTestScreen()
                    }
                    composable("version_info") {
                        if (airPodsViewModel != null) VersionScreen(airPodsViewModel)
                    }
                    composable("hearing_protection") {
                        if (airPodsViewModel != null) HearingProtectionScreen(airPodsViewModel)
                    }
                    composable("purchase_screen") {
                        val purchaseViewModel: PurchaseViewModel = viewModel()
                        PurchaseScreen(purchaseViewModel, navController)
                    }
                }
            }

            val showBackButton = remember { mutableStateOf(false) }

            LaunchedEffect(navController) {
                navController.addOnDestinationChangedListener { _, destination, _ ->
                    showBackButton.value =
                        destination.route != "settings" // && destination.route != "onboarding"
                    Log.d(
                        "MainActivity",
                        "Navigated to ${destination.route}, showBackButton: ${showBackButton.value}"
                    )
                }
            }

            AnimatedVisibility(
                visible = showBackButton.value,
                enter = fadeIn(animationSpec = tween()) + scaleIn(
                    initialScale = 0f,
                    animationSpec = tween()
                ),
                exit = fadeOut(animationSpec = tween()) + scaleOut(
                    targetScale = 0.5f,
                    animationSpec = tween(100)
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = 8.dp, top = (LocalWindowInfo.current.containerSize.width * 0.05f).dp
                    )
            ) {
                StyledIconButton(
                    onClick = { navController.popBackStack() },
                    icon = "􀯶",
                    backdrop = backButtonBackdrop
                )
            }
        }

        context.startForegroundService(Intent(context, AirPodsService::class.java))

        serviceConnection = remember {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as AirPodsService.LocalBinder
                    airPodsService.value = binder.getService()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    airPodsService.value = null
                }
            }
        }

        context.bindService(
            Intent(context, AirPodsService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        if (airPodsService.value?.isConnected() == true) {
            isConnected.value = true
        }
    } else {
        PermissionsScreen(
            permissionState = permissionState,
            canDrawOverlays = canDrawOverlays,
            onOverlaySettingsReturn = { canDrawOverlays = Settings.canDrawOverlays(context) })
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    permissionState: MultiplePermissionsState,
    canDrawOverlays: Boolean,
    onOverlaySettingsReturn: () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val accentColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5)

    val scrollState = rememberScrollState()

    val basicPermissionsGranted = permissionState.permissions.all { it.status.isGranted }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.05f, animationSpec = infiniteRepeatable(
            animation = tween(1000), repeatMode = RepeatMode.Reverse
        ), label = "pulse scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDarkTheme) Color.Black else Color(0xFFF2F2F7))
            .padding(16.dp)
            .verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp), contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\uDBC2\uDEB7", style = TextStyle(
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                    color = textColor,
                    textAlign = TextAlign.Center
                )
            )
            Canvas(
                modifier = Modifier
                    .size(120.dp)
                    .scale(pulseScale)
            ) {
                val radius = size.minDimension / 2.2f
                val centerX = size.width / 2
                val centerY = size.height / 2

                rotate(degrees = 45f) {
                    drawCircle(
                        color = accentColor.copy(alpha = 0.1f),
                        radius = radius * 1.3f,
                        center = Offset(centerX, centerY)
                    )

                    drawCircle(
                        color = accentColor.copy(alpha = 0.2f),
                        radius = radius * 1.1f,
                        center = Offset(centerX, centerY)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Permission Required", style = TextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily(Font(R.font.sf_pro)),
                color = textColor,
                textAlign = TextAlign.Center
            ), modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.permissions_required), style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily(Font(R.font.sf_pro)),
                color = textColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            ), modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        PermissionCard(
            title = "Bluetooth Permissions",
            description = "Required to communicate with your AirPods",
            icon = ImageVector.vectorResource(id = R.drawable.ic_bluetooth),
            isGranted = permissionState.permissions.filter {
                it.permission.contains("BLUETOOTH")
            }.all { it.status.isGranted },
            backgroundColor = backgroundColor,
            textColor = textColor,
            accentColor = accentColor
        )

        PermissionCard(
            title = "Notification Permission",
            description = "To show battery status",
            icon = Icons.Default.Notifications,
            isGranted = permissionState.permissions.find {
                it.permission == "android.permission.POST_NOTIFICATIONS"
            }?.status?.isGranted == true,
            backgroundColor = backgroundColor,
            textColor = textColor,
            accentColor = accentColor
        )

        PermissionCard(
            title = "Phone Permissions",
            description = "For answering calls with Head Gestures",
            icon = Icons.Default.Phone,
            isGranted = permissionState.permissions.filter {
                it.permission.contains("PHONE") || it.permission.contains("CALLS")
            }.all { it.status.isGranted },
            backgroundColor = backgroundColor,
            textColor = textColor,
            accentColor = accentColor
        )

        PermissionCard(
            title = "Display Over Other Apps",
            description = "For popup animations when AirPods connect",
            icon = ImageVector.vectorResource(id = R.drawable.ic_layers),
            isGranted = canDrawOverlays,
            backgroundColor = backgroundColor,
            textColor = textColor,
            accentColor = accentColor
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { permissionState.launchMultiplePermissionRequest() },
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                "Ask for regular permissions",
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                    color = Color.White
                ),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
                onOverlaySettingsReturn()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (canDrawOverlays) Color.Gray else accentColor
            ),
            enabled = !canDrawOverlays,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                if (canDrawOverlays) "Overlay Permission Granted" else "Grant Overlay Permission",
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                    color = Color.White
                ),
            )
        }

        if (!canDrawOverlays && basicPermissionsGranted) {
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    context.getSharedPreferences("settings", MODE_PRIVATE).edit {
                        putBoolean("overlay_permission_skipped", true)
                    }

                    val intent = Intent(context, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(55.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF757575)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "Continue without overlay",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = Color.White
                    ),
                )
            }
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isGranted) accentColor.copy(alpha = 0.15f) else Color.Gray.copy(
                            alpha = 0.15f
                        )
                    ), contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isGranted) accentColor else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = title, style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor
                    )
                )

                Text(
                    text = description, style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor.copy(alpha = 0.6f)
                    )
                )
            }

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isGranted) Color(0xFF4CAF50) else Color.Gray),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isGranted) "✓" else "!", style = TextStyle(
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                )
            }
        }
    }
}
