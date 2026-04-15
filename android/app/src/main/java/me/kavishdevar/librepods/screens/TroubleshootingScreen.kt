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

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.utils.LogCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CustomIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun TroubleshootingScreen(navController: NavController) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val logCollector = remember { LogCollector(context) }
    val savedLogs = remember { mutableStateListOf<File>() }

    var isCollectingLogs by remember { mutableStateOf(false) }
    var showTroubleshootingSteps by remember { mutableStateOf(false) }
    var currentStep by remember { mutableIntStateOf(0) }
    var logContent by remember { mutableStateOf("") }
    var selectedLogFile by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var isLoadingLogContent by remember { mutableStateOf(false) }
    var logContentLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(isCollectingLogs) {
        while (isCollectingLogs) {
            delay(250)
            delay(250)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showBottomSheet by remember { mutableStateOf(false) }

    val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isSystemInDarkTheme()) Color.White else Color.Black
    val accentColor = if (isSystemInDarkTheme()) Color(0xFF007AFF) else Color(0xFF3C6DF5)
    val buttonBgColor = if (isSystemInDarkTheme()) Color(0xFF333333) else Color(0xFFDDDDDD)

    var instructionText by remember { mutableStateOf("") }
    val isDarkTheme = isSystemInDarkTheme()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val logsDir = File(context.filesDir, "logs")
            if (logsDir.exists()) {
                savedLogs.clear()
                savedLogs.addAll(logsDir.listFiles()?.filter { it.name.endsWith(".txt") }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList())
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(logContent.toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Log saved successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Failed to save log: ${e.localizedMessage}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(currentStep) {
        instructionText = when (currentStep) {
            0 -> "First, let's ensure Xposed module is properly configured. Tap the button below to check Xposed scope settings."
            1 -> "Please put your AirPods in the case and close it, so they disconnect completely."
            2 -> "Preparing to collect logs... Please wait."
            3 -> "Now, open the AirPods case and connect your AirPods. Logs are being collected. Connection will be detected automatically, or you can manually stop logging when you're done."
            4 -> "Log collection complete! You can now save or share the logs."
            else -> ""
        }
    }

    fun openLogBottomSheet(file: File) {
        selectedLogFile = file
        logContent = ""
        isLoadingLogContent = false
        logContentLoaded = false
        showBottomSheet = true
    }

    val backdrop = rememberLayerBackdrop()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        StyledScaffold(
            title = stringResource(R.string.troubleshooting)
        ){ spacerHeight, hazeState ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .hazeSource(state = hazeState)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(spacerHeight))

                Text(
                    text = stringResource(R.string.saved_logs),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    ),
                    modifier = Modifier.padding(16.dp, bottom = 4.dp, top = 8.dp)
                )

                Spacer(modifier = Modifier.height(2.dp))

                if (savedLogs.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                backgroundColor,
                                RoundedCornerShape(28.dp)
                            )
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.no_logs_found),
                            fontSize = 16.sp,
                            color = textColor
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                backgroundColor,
                                RoundedCornerShape(28.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total Logs: ${savedLogs.size}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = textColor
                            )

                            if (savedLogs.size > 1) {
                                TextButton(
                                    onClick = { showDeleteAllDialog = true },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Delete All")
                                }
                            }
                        }

                        savedLogs.forEach { logFile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        openLogBottomSheet(logFile)
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = logFile.name,
                                        fontSize = 16.sp,
                                        color = textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Text(
                                        text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                                            .format(Date(logFile.lastModified())),
                                        fontSize = 14.sp,
                                        color = textColor.copy(alpha = 0.6f)
                                    )
                                }

                                CustomIconButton(
                                    onClick = {
                                        selectedLogFile = logFile
                                        showDeleteDialog = true
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = !showTroubleshootingSteps,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    Button(
                        onClick = { showTroubleshootingSteps = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonBgColor,
                            contentColor = textColor
                        ),
                        enabled = !isCollectingLogs
                    ) {
                        Text(stringResource(R.string.collect_logs))
                    }
                }

                AnimatedVisibility(
                    visible = showTroubleshootingSteps,
                    enter = fadeIn(animationSpec = tween(300)) +
                        slideInVertically(animationSpec = tween(300)) { it / 2 },
                    exit = fadeOut(animationSpec = tween(300)) +
                        slideOutVertically(animationSpec = tween(300)) { it / 2 }
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.troubleshooting_steps),
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Light,
                                color = textColor.copy(alpha = 0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro))
                            ),
                            modifier = Modifier.padding(16.dp, bottom = 2.dp, top = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    backgroundColor,
                                    RoundedCornerShape(28.dp)
                                )
                                .padding(16.dp)
                        ) {
                            val textAlpha = animateFloatAsState(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 300),
                                label = "textAlpha"
                            )

                            Text(
                                text = instructionText,
                                fontSize = 16.sp,
                                color = textColor.copy(alpha = textAlpha.value),
                                lineHeight = 22.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            when (currentStep) {
                                0 -> {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                logCollector.openXposedSettings(context)
                                                delay(2000)
                                                currentStep = 1
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = buttonBgColor,
                                            contentColor = textColor
                                        )
                                    ) {
                                        Text("Open Xposed Settings")
                                    }
                                }

                                1 -> {
                                    Button(
                                        onClick = {
                                            currentStep = 2
                                            isCollectingLogs = true

                                            coroutineScope.launch {
                                                try {
                                                    logCollector.clearLogs()

                                                    logCollector.addLogMarker(LogCollector.LogMarkerType.START)

                                                    logCollector.killBluetoothService()

                                                    withContext(Dispatchers.Main) {
                                                        delay(500)
                                                        currentStep = 3
                                                    }

                                                    val timestamp = SimpleDateFormat(
                                                        "yyyyMMdd_HHmmss",
                                                        Locale.US
                                                    ).format(Date())

                                                    logContent =
                                                        logCollector.startLogCollection(
                                                            listener = { /* Removed live log display */ },
                                                            connectionDetectedCallback = {
                                                                launch {
                                                                    delay(5000)
                                                                    withContext(Dispatchers.Main) {
                                                                        if (isCollectingLogs) {
                                                                            logCollector.stopLogCollection()
                                                                            currentStep = 4
                                                                            isCollectingLogs =
                                                                                false
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        )

                                                    val logFile =
                                                        logCollector.saveLogToInternalStorage(
                                                            "airpods_log_$timestamp.txt",
                                                            logContent
                                                        )
                                                    logFile?.let {
                                                        withContext(Dispatchers.Main) {
                                                            savedLogs.add(0, it)
                                                            selectedLogFile = it
                                                            Toast.makeText(
                                                                context,
                                                                "Log saved: ${it.name}",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                            context,
                                                            "Error collecting logs: ${e.message}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                        isCollectingLogs = false
                                                        currentStep = 0
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = buttonBgColor,
                                            contentColor = textColor
                                        )
                                    ) {
                                        Text("Continue")
                                    }
                                }

                                2, 3 -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(
                                            color = accentColor
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = if (currentStep == 2) "Preparing..." else "Collecting logs...",
                                            fontSize = 14.sp,
                                            color = textColor
                                        )

                                        if (currentStep == 3) {
                                            Spacer(modifier = Modifier.height(16.dp))

                                            Button(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        logCollector.addLogMarker(
                                                            LogCollector.LogMarkerType.CUSTOM,
                                                            "Manual stop requested by user"
                                                        )
                                                        delay(1000)
                                                        logCollector.stopLogCollection()
                                                        delay(500)

                                                        withContext(Dispatchers.Main) {
                                                            currentStep = 4
                                                            isCollectingLogs = false
                                                            Toast.makeText(
                                                                context,
                                                                "Log collection stopped",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = buttonBgColor,
                                                    contentColor = textColor
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                            ) {
                                                Text("Stop Collection")
                                            }
                                        }
                                    }
                                }

                                4 -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Button(
                                            onClick = {
                                                selectedLogFile?.let { file ->
                                                    val fileUri = FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.provider",
                                                        file
                                                    )
                                                    val shareIntent =
                                                        Intent(Intent.ACTION_SEND).apply {
                                                            type = "text/plain"
                                                            putExtra(
                                                                Intent.EXTRA_STREAM,
                                                                fileUri
                                                            )
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                    context.startActivity(
                                                        Intent.createChooser(
                                                            shareIntent,
                                                            "Share log file"
                                                        )
                                                    )
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = buttonBgColor,
                                                contentColor = textColor
                                            ),
                                            modifier = Modifier.width(150.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Share"
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Share")
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Button(
                                            onClick = {
                                                selectedLogFile?.let { file ->
                                                    saveLauncher.launch(
                                                        file.absolutePath
                                                    )
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = buttonBgColor,
                                                contentColor = textColor
                                            ),
                                            modifier = Modifier.width(150.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_save),
                                                contentDescription = "Save"
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Save")
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            currentStep = 0
                                            showTroubleshootingSteps = false
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = buttonBgColor,
                                            contentColor = textColor
                                        )
                                    ) {
                                        Text("Done")
                                    }
                                }
                            }
                        }
                    }
                }

                if (showDeleteDialog && selectedLogFile != null) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text("Delete Log File") },
                        text = {
                            Text("Are you sure you want to delete this log file? This action cannot be undone.")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    selectedLogFile?.let { file ->
                                        if (file.delete()) {
                                            savedLogs.remove(file)
                                            Toast.makeText(
                                                context,
                                                "Log file deleted",
                                                Toast.LENGTH_SHORT
                                            )
                                                .show()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Failed to delete log file",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    showDeleteDialog = false
                                }
                            ) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                if (showDeleteAllDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteAllDialog = false },
                        title = { Text("Delete All Logs") },
                        text = {
                            Text("Are you sure you want to delete all log files? This action cannot be undone and will remove ${savedLogs.size} log files.")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        var deletedCount = 0
                                        savedLogs.forEach { file ->
                                            if (file.delete()) {
                                                deletedCount++
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            if (deletedCount > 0) {
                                                savedLogs.clear()
                                                Toast.makeText(
                                                    context,
                                                    "Deleted $deletedCount log files",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Failed to delete log files",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                    showDeleteAllDialog = false
                                }
                            ) {
                                Text("Delete All", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteAllDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
                containerColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 8.dp
            ) {
                LaunchedEffect(selectedLogFile) {
                    if (!logContentLoaded) {
                        delay(300)
                        withContext(Dispatchers.IO) {
                            isLoadingLogContent = true
                            logContent = try {
                                selectedLogFile?.readText() ?: ""
                            } catch (e: Exception) {
                                "Error loading log content: ${e.message}"
                            }
                            isLoadingLogContent = false
                            logContentLoaded = true
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = selectedLogFile?.name ?: "Log Content",
                            style = TextStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                fontFamily = FontFamily(Font(R.font.sf_pro))
                            ),
                            color = textColor
                        )
                        Text(
                            text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                                .format(Date(selectedLogFile?.lastModified() ?: 0)),
                            fontSize = 14.sp,
                            color = textColor.copy(alpha = 0.7f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    }

                    if (isLoadingLogContent) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = accentColor)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .background(
                                    color = Color.Black,
                                    shape = RoundedCornerShape(8.dp)
                                )
                        ) {
                            val horizontalScrollState = rememberScrollState()
                            val verticalScrollState = rememberScrollState()

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .horizontalScroll(horizontalScrollState)
                                    .verticalScroll(verticalScrollState)
                            ) {
                                Text(
                                    text = logContent,
                                    fontSize = 14.sp,
                                    color = Color.LightGray,
                                    lineHeight = 20.sp,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                selectedLogFile?.let { file ->
                                    val fileUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.provider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, fileUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(
                                            shareIntent,
                                            "Share log file"
                                        )
                                    )
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonBgColor,
                                contentColor = textColor
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share")
                        }

                        Button(
                            onClick = {
                                selectedLogFile?.let { file ->
                                    saveLauncher.launch(file.absolutePath)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonBgColor,
                                contentColor = textColor
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_save),
                                contentDescription = "Save"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            logCollector.stopLogCollection()
        }
    }
}
