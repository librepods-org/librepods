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

package me.kavishdevar.librepods.screens

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.StyledIconButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.utils.RadareOffsetFinder

@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Onboarding(navController: NavController, activityContext: Context) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val accentColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5)

    val radareOffsetFinder = remember { RadareOffsetFinder(activityContext) }
    val progressState by radareOffsetFinder.progressState.collectAsState()
    var isComplete by remember { mutableStateOf(false) }
    var hasStarted by remember { mutableStateOf(false) }
    var rootCheckPassed by remember { mutableStateOf(false) }
    var checkingRoot by remember { mutableStateOf(false) }
    var rootCheckFailed by remember { mutableStateOf(false) }
    var moduleEnabled by remember { mutableStateOf(false) }
    var bluetoothToggled by remember { mutableStateOf(false) }

    var showSkipDialog by remember { mutableStateOf(false) }

    fun checkRootAccess() {
        checkingRoot = true
        rootCheckFailed = false
        kotlinx.coroutines.MainScope().launch {
            withContext(Dispatchers.IO) {
                try {
                    val process = Runtime.getRuntime().exec("su -c id")
                    val exitValue = process.waitFor() // no idea why i have this, probably don't need to do this
                    withContext(Dispatchers.Main) {
                        rootCheckPassed = (exitValue == 0)
                        rootCheckFailed = (exitValue != 0)
                        checkingRoot = false
                    }
                } catch (e: Exception) {
                    Log.e("Onboarding", "Root check failed", e)
                    withContext(Dispatchers.Main) {
                        rootCheckPassed = false
                        rootCheckFailed = true
                        checkingRoot = false
                    }
                }
            }
        }
    }

    LaunchedEffect(hasStarted) {
        if (hasStarted && rootCheckPassed) {
            Log.d("Onboarding", "Checking if hook offset is available...")
            val isHookReady = radareOffsetFinder.isHookOffsetAvailable()
            Log.d("Onboarding", "Hook offset ready: $isHookReady")

            if (isHookReady) {
                Log.d("Onboarding", "Hook is ready")
                isComplete = true
            } else {
                Log.d("Onboarding", "Hook not ready, starting setup process...")
                withContext(Dispatchers.IO) {
                    radareOffsetFinder.setupAndFindOffset()
                }
            }
        }
    }

    LaunchedEffect(progressState) {
        if (progressState is RadareOffsetFinder.ProgressState.Success) {
            isComplete = true
        }
    }
    val backdrop = rememberLayerBackdrop()
    StyledScaffold(
        title = "Setting Up",
        actionButtons = listOf(
            {scaffoldBackdrop ->
                StyledIconButton(
                    onClick = {
                        showSkipDialog = true
                    },
                    icon = "􀊋",
                    darkMode = isDarkTheme,
                    backdrop = scaffoldBackdrop
                )
            }
        )
    ) { spacerHeight ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(spacerHeight))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = backgroundColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!rootCheckPassed && !hasStarted) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Root Access",
                            tint = accentColor,
                            modifier = Modifier.size(50.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = stringResource(R.string.root_access_required),
                            style = TextStyle(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.this_app_needs_root_access_to_hook_onto_the_bluetooth_library),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor.copy(alpha = 0.7f)
                            )
                        )

                        if (rootCheckFailed) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.root_access_denied),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                    color = Color(0xFFFF453A)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { checkRootAccess() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor
                            ),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !checkingRoot
                        ) {
                            if (checkingRoot) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    "Check Root Access",
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily(Font(R.font.sf_pro))
                                    ),
                                )
                            }
                        }
                    } else {
                        StatusIcon(if (hasStarted) progressState else RadareOffsetFinder.ProgressState.Idle, isDarkTheme)

                        Spacer(modifier = Modifier.height(24.dp))

                        AnimatedContent(
                            targetState = if (hasStarted) getStatusTitle(progressState,
                                moduleEnabled, bluetoothToggled) else "Setup Required",
                            transitionSpec = { fadeIn() togetherWith fadeOut() }
                        ) { text ->
                            Text(
                                text = text,
                                style = TextStyle(
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                    color = textColor
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        AnimatedContent(
                            targetState = if (hasStarted)
                                getStatusDescription(progressState, moduleEnabled, bluetoothToggled)
                            else
                                "AirPods functionality requires one-time setup for hooking into Bluetooth library",
                            transitionSpec = { fadeIn() togetherWith fadeOut() }
                        ) { text ->
                            Text(
                                text = text,
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                    color = textColor.copy(alpha = 0.7f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        if (!hasStarted) {
                            Button(
                                onClick = { hasStarted = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "Start Setup",
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily(Font(R.font.sf_pro))
                                    ),
                                )
                            }
                        } else {
                            when (progressState) {
                                is RadareOffsetFinder.ProgressState.DownloadProgress -> {
                                    val progress = (progressState as RadareOffsetFinder.ProgressState.DownloadProgress).progress
                                    val animatedProgress by animateFloatAsState(
                                        targetValue = progress,
                                        label = "Download Progress"
                                    )
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        LinearProgressIndicator(
                                            progress = { animatedProgress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp),
                                            strokeCap = StrokeCap.Round,
                                            color = accentColor
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = "${(progress * 100).toInt()}%",
                                            style = TextStyle(
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                                color = textColor.copy(alpha = 0.6f)
                                            )
                                        )
                                    }
                                }
                                is RadareOffsetFinder.ProgressState.Success -> {
                                    if (!moduleEnabled) {
                                        Button(
                                            onClick = { moduleEnabled = true },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(50.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = accentColor
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                "I've Enabled/Reactivated the Module",
                                                style = TextStyle(
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    fontFamily = FontFamily(Font(R.font.sf_pro))
                                                ),
                                            )
                                        }
                                    } else if (!bluetoothToggled) {
                                        Button(
                                            onClick = { bluetoothToggled = true },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(50.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = accentColor
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                "I've Toggled Bluetooth",
                                                style = TextStyle(
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    fontFamily = FontFamily(Font(R.font.sf_pro))
                                                ),
                                            )
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                navController.navigate("settings") {
                                                    popUpTo("onboarding") { inclusive = true }
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(50.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = accentColor
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                "Continue to Settings",
                                                style = TextStyle(
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    fontFamily = FontFamily(Font(R.font.sf_pro))
                                                ),
                                            )
                                        }
                                    }
                                }
                                is RadareOffsetFinder.ProgressState.Idle,
                                is RadareOffsetFinder.ProgressState.Error -> {
                                    // No specific UI for these states
                                }
                                else -> {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp),
                                        strokeCap = StrokeCap.Round,
                                        color = accentColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (progressState is RadareOffsetFinder.ProgressState.Error && !isComplete && hasStarted) {
                Button(
                    onClick = {
                        Log.d("Onboarding", "Trying to find offset again...")
                        kotlinx.coroutines.MainScope().launch {
                            withContext(Dispatchers.IO) {
                                radareOffsetFinder.setupAndFindOffset()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(55.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Try Again",
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        ),
                    )
                }
            }
        }

        if (showSkipDialog) {
            AlertDialog(
                onDismissRequest = { showSkipDialog = false },
                title = { Text("Skip Setup") },
                text = {
                    Text(
                        "Have you installed the root module that patches the Bluetooth library directly? This option is for users who have manually patched their system instead of using the dynamic hook.",
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                },
                confirmButton = {
                    val sharedPreferences = activityContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    TextButton(
                        onClick = {
                            showSkipDialog = false
                            RadareOffsetFinder.clearHookOffsets()
                            sharedPreferences.edit { putBoolean("skip_setup", true) }
                            navController.navigate("settings") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        }
                    ) {
                        Text(
                            "Yes, Skip Setup",
                            color = accentColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showSkipDialog = false }
                    ) {
                        Text("Cancel")
                    }
                },
                containerColor = backgroundColor,
                textContentColor = textColor,
                titleContentColor = textColor
            )
        }
    }
}

@Composable
private fun StatusIcon(
    progressState: RadareOffsetFinder.ProgressState,
    isDarkTheme: Boolean
) {
    val accentColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5)
    val errorColor = if (isDarkTheme) Color(0xFFFF453A) else Color(0xFFFF3B30)
    val successColor = if (isDarkTheme) Color(0xFF30D158) else Color(0xFF34C759)

    Box(
        modifier = Modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        when (progressState) {
            is RadareOffsetFinder.ProgressState.Error -> {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Error",
                    tint = errorColor,
                    modifier = Modifier.size(50.dp)
                )
            }
            is RadareOffsetFinder.ProgressState.Success -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Success",
                    tint = successColor,
                    modifier = Modifier.size(50.dp)
                )
            }
            is RadareOffsetFinder.ProgressState.Idle -> {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = accentColor,
                    modifier = Modifier.size(50.dp)
                )
            }
            else -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(50.dp),
                    color = accentColor,
                    strokeWidth = 4.dp
                )
            }
        }
    }
}

private fun getStatusTitle(
    state: RadareOffsetFinder.ProgressState,
    moduleEnabled: Boolean,
    bluetoothToggled: Boolean
): String {
    return when (state) {
        is RadareOffsetFinder.ProgressState.Success -> {
            when {
                !moduleEnabled -> "Enable Xposed Module"
                !bluetoothToggled -> "Toggle Bluetooth"
                else -> "Setup Complete"
            }
        }
        is RadareOffsetFinder.ProgressState.Idle -> "Getting Ready"
        is RadareOffsetFinder.ProgressState.CheckingExisting -> "Checking if radare2 already downloaded"
        is RadareOffsetFinder.ProgressState.Downloading -> "Downloading radare2"
        is RadareOffsetFinder.ProgressState.DownloadProgress -> "Downloading radare2"
        is RadareOffsetFinder.ProgressState.Extracting -> "Extracting radare2"
        is RadareOffsetFinder.ProgressState.MakingExecutable -> "Setting executable permissions"
        is RadareOffsetFinder.ProgressState.FindingOffset -> "Finding function offset"
        is RadareOffsetFinder.ProgressState.SavingOffset -> "Saving offset"
        is RadareOffsetFinder.ProgressState.Cleaning -> "Cleaning Up"
        is RadareOffsetFinder.ProgressState.Error -> "Setup Failed"
    }
}

private fun getStatusDescription(
    state: RadareOffsetFinder.ProgressState,
    moduleEnabled: Boolean,
    bluetoothToggled: Boolean
): String {
    return when (state) {
        is RadareOffsetFinder.ProgressState.Success -> {
            when {
                !moduleEnabled -> "Please enable the LibrePods Xposed module in your Xposed manager (e.g. LSPosed). If already enabled, disable and re-enable it."
                !bluetoothToggled -> "Please turn off and then turn on Bluetooth to apply the changes."
                else -> "All set! You can now use your AirPods with enhanced functionality."
            }
        }
        is RadareOffsetFinder.ProgressState.Idle -> "Preparing"
        is RadareOffsetFinder.ProgressState.CheckingExisting -> "Checking if radare2 are already installed"
        is RadareOffsetFinder.ProgressState.Downloading -> "Starting radare2 download"
        is RadareOffsetFinder.ProgressState.DownloadProgress -> "Downloading radare2"
        is RadareOffsetFinder.ProgressState.Extracting -> "Extracting radare2"
        is RadareOffsetFinder.ProgressState.MakingExecutable -> "Setting executable permissions on radare2 binaries"
        is RadareOffsetFinder.ProgressState.FindingOffset -> "Looking for the required Bluetooth function in system libraries"
        is RadareOffsetFinder.ProgressState.SavingOffset -> "Saving the function offset"
        is RadareOffsetFinder.ProgressState.Cleaning -> "Removing temporary extracted files"
        is RadareOffsetFinder.ProgressState.Error -> state.message
    }
}

@ExperimentalHazeMaterialsApi
@Preview
@Composable
fun OnboardingPreview() {
    Onboarding(navController = NavController(LocalContext.current), activityContext = LocalContext.current)
}

