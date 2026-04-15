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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.StyledIconButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.constants.BatteryStatus
import me.kavishdevar.librepods.constants.isHeadTrackingData
import me.kavishdevar.librepods.services.ServiceManager
import kotlin.io.encoding.ExperimentalEncodingApi

data class PacketInfo(
    val type: String,
    val description: String,
    val rawData: String,
    val parsedData: Map<String, String> = emptyMap(),
    val isUnknown: Boolean = false
)

fun parsePacket(message: String): PacketInfo {
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
}

fun parseOutgoingPacket(bytes: ByteArray, rawData: String): PacketInfo {
    if (bytes.size < 7) {
        return PacketInfo("Unknown", "Unknown outgoing packet", rawData, emptyMap(), true)
    }

    return when {
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
}

@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter", "UnspecifiedRegisterReceiverFlag")
@Composable
fun DebugScreen(navController: NavController) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val airPodsService = remember { ServiceManager.getService() }
    val packetLogs = airPodsService?.packetLogsFlow?.collectAsState(emptySet())?.value ?: emptySet()

    val refreshTrigger = remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger.intValue) {
        while(true) {
            delay(1000)
            refreshTrigger.intValue += 1
        }
    }

    val expandedItems = remember { mutableStateOf(setOf<Int>()) }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Packet Data", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Packet copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(packetLogs.size, refreshTrigger.intValue) {
        if (packetLogs.isNotEmpty()) {
            listState.animateScrollToItem(packetLogs.size - 1)
        }
    }

    val isDarkTheme = isSystemInDarkTheme()
    val backdrop = rememberLayerBackdrop()
    StyledScaffold(
        title = "Debug",
        actionButtons = listOf(
            {scaffoldBackdrop ->
                StyledIconButton(
                    onClick = {
                        airPodsService?.clearLogs()
                        expandedItems.value = emptySet()
                    },
                    icon = "􀈑",
                    darkMode = isDarkTheme,
                    backdrop = scaffoldBackdrop
                )
            }
        ),
    ) { spacerHeight, hazeState ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .navigationBarsPadding()
                .layerBackdrop(backdrop)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(spacerHeight))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                content = {
                    items(packetLogs.size) { index ->
                        val message = packetLogs.elementAt(index)
                        val isSent = message.startsWith("Sent")
                        val isExpanded = expandedItems.value.contains(index)
                        val packetInfo = parsePacket(message)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isSent) "􀆉" else "􀆊",
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                                        color = if (isSent) Color(0xFF4CD964) else Color(0xFFFF3B30)
                                    ),
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
                                    airPodsService?.value?.aacpManager?.sendPacket(
                                        packet.value.text
                                            .split(" ")
                                            .map { it.toInt(16).toByte() }
                                            .toByteArray()
                                    )
                                    packet.value = TextFieldValue("")
                                    focusManager.clearFocus()

                                    if (packetLogs.isNotEmpty()) {
                                        coroutineScope.launch {
                                            try {
                                                delay(100)
                                                listState.animateScrollToItem(
                                                    index = (packetLogs.size - 1).coerceAtLeast(0),
                                                    scrollOffset = 0
                                                )
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                listState.scrollToItem(
                                                    index = (packetLogs.size - 1).coerceAtLeast(0)
                                                )
                                            }
                                        }
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
