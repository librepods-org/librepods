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

package me.kavishdevar.librepods.composables

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.constants.AirPodsNotifications
import me.kavishdevar.librepods.constants.Battery
import me.kavishdevar.librepods.constants.BatteryComponent
import me.kavishdevar.librepods.constants.BatteryStatus
import me.kavishdevar.librepods.services.AirPodsService
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun BatteryView(service: AirPodsService, preview: Boolean = false) {
    val batteryStatus = remember { mutableStateOf<List<Battery>>(listOf()) }

    val previousBatteryStatus = remember { mutableStateOf<List<Battery>>(listOf()) }

    @Suppress("DEPRECATION") val batteryReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AirPodsNotifications.BATTERY_DATA) {
                    batteryStatus.value =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableArrayListExtra("data", Battery::class.java)
                        } else {
                            intent.getParcelableArrayListExtra("data")
                        }?.toList() ?: listOf()
                }
                else if (intent.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                    try {
                        context.unregisterReceiver(this)
                    }
                    catch (_: IllegalArgumentException) {
                        Log.wtf("BatteryReceiver", "Receiver already unregistered")
                    }
                }
            }
        }
    }
    val context = LocalContext.current

    LaunchedEffect(context) {
        val batteryIntentFilter = IntentFilter()
            .apply {
                addAction(AirPodsNotifications.BATTERY_DATA)
                addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                batteryReceiver,
                batteryIntentFilter,
                Context.RECEIVER_EXPORTED
            )
        }
    }

    previousBatteryStatus.value = batteryStatus.value
    batteryStatus.value = service.getBattery()

    if (preview) {
        batteryStatus.value = listOf(
            Battery(BatteryComponent.LEFT, 100, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 94, BatteryStatus.CHARGING),
            Battery(BatteryComponent.CASE, 40, BatteryStatus.CHARGING)
        )
        previousBatteryStatus.value = batteryStatus.value
    }

    val left = batteryStatus.value.find { it.component == BatteryComponent.LEFT }
    val right = batteryStatus.value.find { it.component == BatteryComponent.RIGHT }
    val case = batteryStatus.value.find { it.component == BatteryComponent.CASE }
    val leftLevel = left?.level ?: 0
    val rightLevel = right?.level ?: 0
    val caseLevel = case?.level ?: 0
    val leftCharging = left?.status == BatteryStatus.CHARGING
    val rightCharging = right?.status == BatteryStatus.CHARGING
    val caseCharging = case?.status == BatteryStatus.CHARGING

    val prevLeft = previousBatteryStatus.value.find { it.component == BatteryComponent.LEFT }
    val prevRight = previousBatteryStatus.value.find { it.component == BatteryComponent.RIGHT }
    val prevCase = previousBatteryStatus.value.find { it.component == BatteryComponent.CASE }
    val prevLeftCharging = prevLeft?.status == BatteryStatus.CHARGING
    val prevRightCharging = prevRight?.status == BatteryStatus.CHARGING
    val prevCaseCharging = prevCase?.status == BatteryStatus.CHARGING

    val singleDisplayed = remember { mutableStateOf(false) }

    val airpodsInstance = service.airpodsInstance
    if (airpodsInstance == null) {
        return
    }
    val budsRes = airpodsInstance.model.budsRes
    val caseRes = airpodsInstance.model.caseRes

    Row {
        Column (
            modifier = Modifier
                .fillMaxWidth(0.5f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image (
                bitmap = ImageBitmap.imageResource(budsRes),
                contentDescription = stringResource(R.string.buds),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
            if (
                leftCharging == rightCharging &&
                (leftLevel - rightLevel) in -3..3
            )
            {
                BatteryIndicator(
                    leftLevel.coerceAtMost(rightLevel),
                    leftCharging,
                    previousCharging = (prevLeftCharging && prevRightCharging)
                )
                singleDisplayed.value = true
            }
            else {
                singleDisplayed.value = false
                Row (
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (leftLevel > 0 || left?.status != BatteryStatus.DISCONNECTED) {
                        BatteryIndicator(
                            leftLevel,
                            leftCharging,
                            "\uDBC6\uDCE5",
                            previousCharging = prevLeftCharging
                        )
                    }
                    if (leftLevel > 0 && rightLevel > 0)
                    {
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    if (rightLevel > 0 || right?.status != BatteryStatus.DISCONNECTED)
                    {
                        BatteryIndicator(
                            rightLevel,
                            rightCharging,
                            "\uDBC6\uDCE8",
                            previousCharging = prevRightCharging
                        )
                    }
                }
            }
        }

        Column (
            modifier = Modifier
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                bitmap = ImageBitmap.imageResource(caseRes),
                contentDescription = stringResource(R.string.case_alt),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
                if (caseLevel > 0 || case?.status != BatteryStatus.DISCONNECTED) {
                    BatteryIndicator(
                        caseLevel,
                        caseCharging,
                        prefix = if (!singleDisplayed.value) "\uDBC3\uDE6C" else "",
                        previousCharging = prevCaseCharging
                    )
                }
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BatteryViewPreview() {
    val bg = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    Box(
        modifier = Modifier.background(bg)
    ) {
        BatteryView(AirPodsService(), preview = true)
    }
}
