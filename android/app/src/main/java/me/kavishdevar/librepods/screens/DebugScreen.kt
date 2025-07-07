/*
 * LibrePods - AirPods liberated from Apple’s ecosystem
 *
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

@file:OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.CupertinoMaterials
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.BatteryStatus
import me.kavishdevar.librepods.utils.isHeadTrackingData
import kotlin.io.encoding.ExperimentalEncodingApi

data class PacketInfo(
    val type: String,
    val description: String,
    val rawData: String,
    val parsedData: Map<String, String> = emptyMap(),
    val isUnknown: Boolean = false
)

fun parsePacket(message: String): PacketInfo {
    return try {
        val rawData = if (message.startsWith("Sent")) message.substring(5) else message.substring(9)
        val bytes = rawData.split(" ").mapNotNull {
            it.takeIf { it.isNotEmpty() }?.toIntOrNull(16)?.toByte()
        }.toByteArray()

        val airPodsService = ServiceManager.getService()
        if (airPodsService != null) {
            return when {
                message.startsWith("Sent") -> parseOutgoingPacket(bytes, rawData)
                airPodsService.batteryNotification.isBatteryData(bytes) -> {
                val batteryInfo = mutableMapOf<String, String>()
                airPodsService.batteryNotification.setBattery(bytes)
                val batteries = airPodsService.batteryNotification.getBattery()
                val batteryInfoString = batteries.joinToString(", ") { battery ->
                    "${battery.getComponentName() ?: "Unknown"}: ${battery.level}% ${if (battery.status == BatteryStatus.CHARGING) "(Charging)" else ""}"
                }
                batteries.forEach { battery ->
                    if (battery.status != BatteryStatus.DISCONNECTED) {
                        batteryInfo[battery.getComponentName() ?: "Unknown"] =
                            "${battery.level}% ${if (battery.status == BatteryStatus.CHARGING) "(Charging)" else ""}"
                    }
                }

                PacketInfo(
                    "Battery",
                    batteryInfoString,
                    rawData,
                    batteryInfo
                )
            }
            airPodsService.ancNotification.isANCData(bytes) -> {
                airPodsService.ancNotification.setStatus(bytes)
                val mode = when (airPodsService.ancNotification.status) {
                    1 -> "Off"
                    2 -> "Noise Cancellation"
                    3 -> "Transparency"
                    4 -> "Adaptive"
                    else -> "Unknown"
                }

                PacketInfo(
                    "Noise Control",
                    "Mode: $mode",
                    rawData,
                    mapOf("Mode" to mode)
                )
            }
            airPodsService.earDetectionNotification.isEarDetectionData(bytes) -> {
                airPodsService.earDetectionNotification.setStatus(bytes)
                val status = airPodsService.earDetectionNotification.status
                val primaryStatus = if (status[0] == 0.toByte()) "In ear" else "Out of ear"
                val secondaryStatus = if (status[1] == 0.toByte()) "In ear" else "Out of ear"

                PacketInfo(
                    "Ear Detection",
                    "Primary: $primaryStatus, Secondary: $secondaryStatus",
                    rawData,
                    mapOf("Primary" to primaryStatus, "Secondary" to secondaryStatus)
                )
            }
            airPodsService.conversationAwarenessNotification.isConversationalAwarenessData(bytes) -> {
                airPodsService.conversationAwarenessNotification.setData(bytes)
                val statusMap = mapOf(
                    1.toByte() to "Started speaking",
                    2.toByte() to "Speaking",
                    8.toByte() to "Stopped speaking",
                    9.toByte() to "Not speaking"
                )
                val status = statusMap[airPodsService.conversationAwarenessNotification.status] ?:
                    "Unknown (${airPodsService.conversationAwarenessNotification.status})"

                PacketInfo(
                    "Conversation Awareness",
                    "Status: $status",
                    rawData,
                    mapOf("Status" to status)
                )
            }
            isHeadTrackingData(bytes) -> {
                val horizontal = if (bytes.size >= 53)
                    "${bytes[51].toInt() and 0xFF or (bytes[52].toInt() shl 8)}" else "Unknown"
                val vertical = if (bytes.size >= 55)
                    "${bytes[53].toInt() and 0xFF or (bytes[54].toInt() shl 8)}" else "Unknown"

                PacketInfo(
                    "Head Tracking",
                    "Position data",
                    rawData,
                    mapOf("Horizontal" to horizontal, "Vertical" to vertical)
                )
            }
            else -> PacketInfo("Unknown", "Unknown packet format", rawData, emptyMap(), true)
        }
    } else {
        return if (message.startsWith("Sent")) {
            parseOutgoingPacket(bytes, rawData)
        } else {
            PacketInfo("Unknown", "Unknown packet format", rawData, emptyMap(), true)
        }
    }
    } catch (e: Exception) {
        e.printStackTrace()
        return PacketInfo("Error", "Failed to parse packet: ${e.localizedMessage}", message.take(100), emptyMap(), true)
    }
}

fun parseOutgoingPacket(bytes: ByteArray, rawData: String): PacketInfo {
    if (bytes.size < 7) {
        return PacketInfo("Unknown", "Unknown outgoing packet (insufficient data)", rawData, emptyMap(), true)
    }

    return try {
        when {
        bytes.size >= 16 &&
        bytes[0] == 0x00.toByte() &&
        bytes[1] == 0x00.toByte() &&
        bytes[2] == 0x04.toByte() &&
        bytes[3] == 0x00.toByte() -> {
            PacketInfo("Handshake", "Initial handshake with AirPods", rawData)
        }

        bytes.size >= 11 &&
        bytes[0] == 0x04.toByte() &&
        bytes[1] == 0x00.toByte() &&
        bytes[2] == 0x04.toByte() &&
        bytes[3] == 0x00.toByte() &&
        bytes[4] == 0x09.toByte() &&
        bytes[5] == 0x00.toByte() &&
        bytes[6] == 0x0d.toByte() -> {
            val mode = when (bytes[7].toInt()) {
                1 -> "Off"
                2 -> "Noise Cancellation"
                3 -> "Transparency"
                4 -> "Adaptive"
                else -> "Unknown"
            }
            PacketInfo("Noise Control", "Set mode to $mode", rawData, mapOf("Mode" to mode))
        }

        bytes.size >= 11 &&
        bytes[0] == 0x04.toByte() &&
        bytes[1] == 0x00.toByte() &&
        bytes[2] == 0x04.toByte() &&
        bytes[3] == 0x00.toByte() &&
        bytes[4] == 0x09.toByte() &&
        bytes[5] == 0x00.toByte() &&
        bytes[6] == 0x28.toByte() -> {
            val mode = if (bytes[7].toInt() == 1) "On" else "Off"
            PacketInfo("Conversation Awareness", "Set mode to $mode", rawData, mapOf("Mode" to mode))
        }

        bytes.size > 10 &&
        bytes[0] == 0x04.toByte() &&
        bytes[1] == 0x00.toByte() &&
        bytes[2] == 0x04.toByte() &&
        bytes[3] == 0x00.toByte() &&
        bytes[4] == 0x17.toByte() -> {
            val action = if (bytes.joinToString(" ") { "%02X".format(it) }.contains("A1 02")) "Start" else "Stop"
            PacketInfo("Head Tracking", "$action head tracking", rawData)
        }

        bytes.size >= 11 &&
        bytes[0] == 0x04.toByte() &&
        bytes[1] == 0x00.toByte() &&
        bytes[2] == 0x04.toByte() &&
        bytes[3] == 0x00.toByte() &&
        bytes[4] == 0x09.toByte() &&
        bytes[5] == 0x00.toByte() &&
        bytes[6] == 0x1A.toByte() -> {
            PacketInfo("Long Press Config", "Change long press modes", rawData)
        }

        bytes.size >= 9 &&
        bytes[0] == 0x04.toByte() &&
        bytes[1] == 0x00.toByte() &&
        bytes[2] == 0x04.toByte() &&
        bytes[3] == 0x00.toByte() &&
        bytes[4] == 0x4d.toByte() -> {
            PacketInfo("Feature Request", "Set specific features", rawData)
        }

        bytes.size >= 9 &&
        bytes[0] == 0x04.toByte() &&
        bytes[1] == 0x00.toByte() &&
        bytes[2] == 0x04.toByte() &&
        bytes[3] == 0x00.toByte() &&
        bytes[4] == 0x0f.toByte() -> {
            PacketInfo("Notifications", "Request notifications", rawData)
        }

        else -> PacketInfo("Unknown", "Unknown outgoing packet", rawData, emptyMap(), true)
    }
    } catch (e: Exception) {
        e.printStackTrace()
        return PacketInfo("Error", "Failed to parse outgoing packet: ${e.localizedMessage}", rawData.take(100), emptyMap(), true)
    }
}

@Composable
fun IOSCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Checked",
                tint = if (isSystemInDarkTheme()) Color(0xFF007AFF) else Color(0xFF3C6DF5),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter", "UnspecifiedRegisterReceiverFlag")
@Composable
fun DebugScreen(navController: NavController) {
    val hazeState = remember { HazeState() }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scrollOffset by remember { derivedStateOf { listState.firstVisibleItemScrollOffset } }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val showMenu = remember { mutableStateOf(false) }

    val airPodsService = remember { ServiceManager.getService() }
    val packetLogs = airPodsService?.packetLogsFlow?.collectAsState(emptySet())?.value ?: emptySet()
    val shouldScrollToBottom = remember { mutableStateOf(true) }

    val refreshTrigger = remember { mutableStateOf(0) }
    LaunchedEffect(refreshTrigger.value) {
        while(true) {
            delay(1000)
            refreshTrigger.value = refreshTrigger.value + 1
        }
    }

    val expandedItems = remember { mutableStateOf(setOf<Int>()) }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Packet Data", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Packet copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(packetLogs.size, refreshTrigger.value) {
        if (shouldScrollToBottom.value && packetLogs.isNotEmpty()) {
            try {
                listState.animateScrollToItem((packetLogs.size - 1).coerceAtLeast(0))
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    listState.scrollToItem((packetLogs.size - 1).coerceAtLeast(0))
                } catch (scrollException: Exception) {
                    scrollException.printStackTrace()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Debug") },
                navigationIcon = {
                    TextButton(
                        onClick = { navController.popBackStack() },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back",
                            tint = if (isSystemInDarkTheme()) Color(0xFF007AFF) else Color(0xFF3C6DF5),
                            modifier = Modifier.scale(1.5f)
                        )
                        Text(
                            sharedPreferences.getString("name", "AirPods")!!,
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isSystemInDarkTheme()) Color(0xFF007AFF) else Color(0xFF3C6DF5),
                                fontFamily = FontFamily(Font(R.font.sf_pro))
                            ),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu.value = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options",
                                tint = if (isSystemInDarkTheme()) Color.White else Color.Black
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu.value,
                            onDismissRequest = { showMenu.value = false },
                            modifier = Modifier
                                .width(250.dp)
                                .background(
                                    if (isSystemInDarkTheme()) Color(0xFF1C1B20) else Color(0xFFF2F2F7)
                                )
                                .padding(vertical = 4.dp)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "Auto-scroll",
                                            style = TextStyle(
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Normal
                                            )
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        IOSCheckbox(
                                            checked = shouldScrollToBottom.value,
                                            onCheckedChange = { shouldScrollToBottom.value = it }
                                        )
                                    }
                                },
                                onClick = {
                                    shouldScrollToBottom.value = !shouldScrollToBottom.value
                                    showMenu.value = false
                                }
                            )

                            HorizontalDivider(
                                color = if (isSystemInDarkTheme()) Color(0xFF3A3A3C) else Color(0xFFE5E5EA),
                                thickness = 0.5.dp
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "Clear logs",
                                            style = TextStyle(
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Normal
                                            )
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Clear logs",
                                            tint = if (isSystemInDarkTheme()) Color(0xFF007AFF) else Color(0xFF3C6DF5)
                                        )
                                    }
                                },
                                onClick = {
                                    ServiceManager.getService()?.clearLogs()
                                    expandedItems.value = emptySet()
                                    showMenu.value = false
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.hazeEffect(
                    state = hazeState,
                    style = CupertinoMaterials.thick(),
                    block = fun HazeEffectScope.() {
                        alpha = if (scrollOffset > 0) 1f else 0f
                    }),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = if (isSystemInDarkTheme()) Color(0xFF000000) else Color(0xFFF2F2F7),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .padding(top = paddingValues.calculateTopPadding())
                .navigationBarsPadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                content = {
                    items(packetLogs.size) { index ->
                        if (index < packetLogs.size) {
                            val message = packetLogs.elementAtOrNull(index) ?: return@items
                            val isSent = message.startsWith("Sent")
                            val isExpanded = expandedItems.value.contains(index)
                            val packetInfo = try {
                                parsePacket(message)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                PacketInfo("Error", "Failed to parse packet", message, emptyMap(), true)
                            }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp, horizontal = 4.dp)
                                .combinedClickable(
                                    onClick = {
                                        expandedItems.value = if (isExpanded) {
                                            expandedItems.value - index
                                        } else {
                                            expandedItems.value + index
                                        }
                                    },
                                    onLongClick = {
                                        copyToClipboard(packetInfo.rawData)
                                    }
                                ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSystemInDarkTheme()) Color(0xFF1C1B20) else Color(0xFFF2F2F7),
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isSent) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = if (isSent) Color.Green else Color.Red,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            text = if (packetInfo.isUnknown) {
                                                val shortenedData = packetInfo.rawData.take(60) +
                                                    (if (packetInfo.rawData.length > 60) "..." else "")
                                                shortenedData
                                            } else {
                                                "${packetInfo.type}: ${packetInfo.description}"
                                            },
                                            style = TextStyle(
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = FontFamily(Font(R.font.hack))
                                            )
                                        )
                                        if (isExpanded) {
                                            Spacer(modifier = Modifier.height(4.dp))

                                            if (packetInfo.parsedData.isNotEmpty()) {
                                                packetInfo.parsedData.forEach { (key, value) ->
                                                    Row {
                                                        Text(
                                                            text = "$key: ",
                                                            style = TextStyle(
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                fontFamily = FontFamily(Font(R.font.hack))
                                                            ),
                                                            color = Color.Gray
                                                        )
                                                        Text(
                                                            text = value,
                                                            style = TextStyle(
                                                                fontSize = 12.sp,
                                                                fontFamily = FontFamily(Font(R.font.hack))
                                                            ),
                                                            color = Color.Gray
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                            }

                                            Text(
                                                text = "Raw: ${packetInfo.rawData}",
                                                style = TextStyle(
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily(Font(R.font.hack))
                                                ),
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            val airPodsService = ServiceManager.getService()?.let { mutableStateOf(it) }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isSystemInDarkTheme()) Color(0xFF1C1B20) else Color(0xFFF2F2F7)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val packet = remember { mutableStateOf(TextFieldValue("")) }
                TextField(
                    value = packet.value,
                    onValueChange = { packet.value = it },
                    label = { Text("Packet") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 5.dp),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (packet.value.text.isNotBlank()) {
                                    try {
                                        val bytes = packet.value.text
                                            .split(" ")
                                            .map { it.toInt(16).toByte() }
                                            .toByteArray()
                                        airPodsService?.value?.aacpManager?.sendPacket(bytes)
                                        packet.value = TextFieldValue("")
                                        focusManager.clearFocus()

                                        if (shouldScrollToBottom.value && packetLogs.isNotEmpty()) {
                                            coroutineScope.launch {
                                                try {
                                                    delay(100)
                                                    listState.animateScrollToItem(
                                                        index = (packetLogs.size - 1).coerceAtLeast(0),
                                                        scrollOffset = 0
                                                    )
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                    try {
                                                        listState.scrollToItem(
                                                            index = (packetLogs.size - 1).coerceAtLeast(0)
                                                        )
                                                    } catch (scrollException: Exception) {
                                                        scrollException.printStackTrace()
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: NumberFormatException) {
                                        Toast.makeText(context, "Invalid packet format", Toast.LENGTH_SHORT).show()
                                        e.printStackTrace()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error sending packet", Toast.LENGTH_SHORT).show()
                                        e.printStackTrace()
                                    }
                                }
                            }
                        ) {
                            @Suppress("DEPRECATION")
                            Icon(Icons.Filled.Send, contentDescription = "Send")
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = if (isSystemInDarkTheme()) Color(0xFF1C1B20) else Color(0xFFF2F2F7),
                        unfocusedContainerColor = if (isSystemInDarkTheme()) Color(0xFF1C1B20) else Color(0xFFF2F2F7),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor =  if (isSystemInDarkTheme()) Color.White else Color.Black,
                        unfocusedTextColor =  if (isSystemInDarkTheme()) Color.White else Color.Black.copy(alpha = 0.6f),
                        focusedLabelColor =  if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.6f) else Color.Black,
                        unfocusedLabelColor =  if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f),
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}
