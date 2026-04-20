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
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.composables.StyledIconButton
import me.kavishdevar.librepods.constants.AirPodsNotifications
import me.kavishdevar.librepods.screens.AccessibilitySettingsScreen
import me.kavishdevar.librepods.screens.AdaptiveStrengthScreen
import me.kavishdevar.librepods.screens.AirPodsSettingsScreen
import me.kavishdevar.librepods.screens.AppSettingsScreen
import me.kavishdevar.librepods.screens.CameraControlScreen
import me.kavishdevar.librepods.screens.DebugScreen
import me.kavishdevar.librepods.screens.HeadTrackingScreen
import me.kavishdevar.librepods.screens.HearingAidAdjustmentsScreen
import me.kavishdevar.librepods.screens.HearingAidScreen
import me.kavishdevar.librepods.screens.HearingProtectionScreen
import me.kavishdevar.librepods.screens.LongPress
import me.kavishdevar.librepods.screens.Onboarding
import me.kavishdevar.librepods.screens.OpenSourceLicensesScreen
import me.kavishdevar.librepods.screens.RenameScreen
import me.kavishdevar.librepods.screens.TransparencySettingsScreen
import me.kavishdevar.librepods.screens.TroubleshootingScreen
import me.kavishdevar.librepods.screens.UpdateHearingTestScreen
import me.kavishdevar.librepods.screens.VersionScreen
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.ui.theme.LibrePodsTheme
import me.kavishdevar.librepods.utils.RadareOffsetFinder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

lateinit var serviceConnection: ServiceConnection
lateinit var connectionStatusReceiver: BroadcastReceiver

@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    companion object {
        init {
            System.loadLibrary("l2c_fcr_hook")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LibrePodsTheme {
                Main()
            }
        }

        handleIncomingIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        val data: Uri? = intent.data

        if (data != null && data.scheme == "librepods") {
            when (data.host) {
                "add-magic-keys" -> {
                    val queryParams = data.queryParameterNames
                    queryParams.forEach { param ->
                        val value = data.getQueryParameter(param)
                        Log.d("LibrePods", "Parameter: $param = $value")
                    }

                    handleAddMagicKeys(data)
                }
            }
        }
    }

    private fun handleAddMagicKeys(uri: Uri) {
        val sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)

        val irkHex = uri.getQueryParameter("irk")
        val encKeyHex = uri.getQueryParameter("enc_key")

        try {
            if (irkHex != null && validateHexInput(irkHex)) {
                val irkBytes = hexStringToByteArray(irkHex)
                val irkBase64 = Base64.encode(irkBytes)
                sharedPreferences.edit {putString("IRK", irkBase64)}
            }

            if (encKeyHex != null && validateHexInput(encKeyHex)) {
                val encKeyBytes = hexStringToByteArray(encKeyHex)
                val encKeyBase64 = Base64.encode(encKeyBytes)
                sharedPreferences.edit { putString("ENC_KEY", encKeyBase64)}
            }

            Toast.makeText(this, "Magic keys added successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error processing magic keys: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun validateHexInput(input: String): Boolean {
        val hexPattern = Regex("^[0-9a-fA-F]{32}$")
        return hexPattern.matches(input)
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val result = ByteArray(16)
        for (i in 0 until 16) {
            val hexByte = hex.substring(i * 2, i * 2 + 2)
            result[i] = hexByte.toInt(16).toByte()
        }
        return result
    }
}

@ExperimentalHazeMaterialsApi
@SuppressLint("MissingPermission", "InlinedApi", "UnspecifiedRegisterReceiverFlag")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun Main() {
    val isConnected = remember { mutableStateOf(false) }
    val isRemotelyConnected = remember { mutableStateOf(false) }
    val hookAvailable = RadareOffsetFinder(LocalContext.current).isHookOffsetAvailable()
    val context = LocalContext.current
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val overlaySkipped = remember { mutableStateOf(context.getSharedPreferences("settings", MODE_PRIVATE).getBoolean("overlay_permission_skipped", false)) }

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

    LaunchedEffect(Unit) {
        canDrawOverlays = Settings.canDrawOverlays(context)
    }

    if (permissionState.allPermissionsGranted && (canDrawOverlays || overlaySkipped.value)) {
        val context = LocalContext.current

        val navController = rememberNavController()

        Box (
            modifier = Modifier
                .fillMaxSize()
        ){
            val backButtonBackdrop = rememberLayerBackdrop()
            Box (
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7))
                    .layerBackdrop(backButtonBackdrop)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = if (hookAvailable) "settings" else "onboarding",
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(durationMillis = 300)
                        ) // + fadeIn(animationSpec = tween(durationMillis = 300))
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it/4 },
                            animationSpec = tween(durationMillis = 300)
                        ) // + fadeOut(animationSpec = tween(durationMillis = 150))
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it/4 },
                            animationSpec = tween(durationMillis = 300)
                        ) // + fadeIn(animationSpec = tween(durationMillis = 300))
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(durationMillis = 300)
                        ) // + fadeOut(animationSpec = tween(durationMillis = 150))
                    }
                ) {
                    composable("settings") {
                        if (airPodsService.value != null) {
                            AirPodsSettingsScreen(
                                dev = airPodsService.value?.device,
                                service = airPodsService.value!!,
                                navController = navController,
                                isConnected = isConnected.value,
                                isRemotelyConnected = isRemotelyConnected.value
                            )
                        }
                    }
                    composable("debug") {
                        DebugScreen(navController = navController)
                    }
                    composable("long_press/{bud}") { navBackStackEntry ->
                        LongPress(
                            navController = navController,
                            name = navBackStackEntry.arguments?.getString("bud")!!
                        )
                    }
                    composable("rename") {
                        RenameScreen(navController)
                    }
                    composable("app_settings") {
                        AppSettingsScreen(navController)
                    }
                    composable("troubleshooting") {
                        TroubleshootingScreen(navController)
                    }
                    composable("head_tracking") {
                        HeadTrackingScreen()
                    }
                    composable("onboarding") {
                        Onboarding(navController, context)
                    }
                    composable("accessibility") {
                        AccessibilitySettingsScreen(navController)
                    }
                    composable("transparency_customization") {
                        TransparencySettingsScreen(navController)
                    }
                    composable("hearing_aid") {
                        HearingAidScreen(navController)
                    }
                    composable("hearing_aid_adjustments") {
                        HearingAidAdjustmentsScreen(navController)
                    }
                    composable("adaptive_strength") {
                        AdaptiveStrengthScreen(navController)
                    }
                    composable("camera_control") {
                        CameraControlScreen(navController)
                    }
                    composable("open_source_licenses") {
                        OpenSourceLicensesScreen(navController)
                    }
                    composable("update_hearing_test") {
                        UpdateHearingTestScreen(navController)
                    }
                    composable("version_info") {
                        VersionScreen(navController)
                    }
                    composable("hearing_protection") {
                        HearingProtectionScreen(navController)
                    }
                }
            }

            val showBackButton = remember{ mutableStateOf(false) }

            LaunchedEffect(navController) {
                navController.addOnDestinationChangedListener { _, destination, _ ->
                    showBackButton.value = destination.route != "settings" && destination.route != "onboarding"
                    Log.d("MainActivity", "Navigated to ${destination.route}, showBackButton: ${showBackButton.value}")
                }
            }

            AnimatedVisibility(
                visible = showBackButton.value,
                enter = fadeIn(animationSpec = tween()) + scaleIn(initialScale = 0f, animationSpec = tween()),
                exit = fadeOut(animationSpec = tween()) + scaleOut(targetScale = 0.5f, animationSpec = tween(100)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = 8.dp,
                        top = (LocalWindowInfo.current.containerSize.width * 0.05f).dp
                    )
            ) {
                StyledIconButton(
                        onClick = { navController.popBackStack() },
                        icon = "􀯶",
                        darkMode = isSystemInDarkTheme(),
                        backdrop = backButtonBackdrop
                    )
            }
        }

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

        context.bindService(Intent(context, AirPodsService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        if (airPodsService.value?.isConnectedLocally == true) {
            isConnected.value = true
        }
    } else {
        PermissionsScreen(
            permissionState = permissionState,
            canDrawOverlays = canDrawOverlays,
            onOverlaySettingsReturn = { canDrawOverlays = Settings.canDrawOverlays(context) }
        )
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
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDarkTheme) Color.Black else Color(0xFFF2F2F7))
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\uDBC2\uDEB7",
                style = TextStyle(
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
            text = "Permission Required",
            style = TextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily(Font(R.font.sf_pro)),
                color = textColor,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.permissions_required),
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily(Font(R.font.sf_pro)),
                color = textColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.fillMaxWidth()
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
                    ),
                contentAlignment = Alignment.Center
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
                    text = title,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor
                    )
                )

                Text(
                    text = description,
                    style = TextStyle(
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
                    text = if (isGranted) "✓" else "!",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
            }
        }
    }
}
