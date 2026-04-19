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

import android.content.res.Configuration
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.constants.Battery
import me.kavishdevar.librepods.constants.BatteryComponent
import me.kavishdevar.librepods.constants.BatteryStatus
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun BatteryView(
    batteryList: List<Battery>,
    budsRes: Int,
    caseRes: Int
) {
    val left = batteryList.find { it.component == BatteryComponent.LEFT }
    val right = batteryList.find { it.component == BatteryComponent.RIGHT }
    val case = batteryList.find { it.component == BatteryComponent.CASE }

    val leftLevel = left?.level ?: 0
    val rightLevel = right?.level ?: 0
    val caseLevel = case?.level ?: 0

    val leftCharging = left?.status == BatteryStatus.CHARGING ||
        left?.status == BatteryStatus.OPTIMIZED_CHARGING

    val rightCharging = right?.status == BatteryStatus.CHARGING ||
        right?.status == BatteryStatus.OPTIMIZED_CHARGING

    val caseCharging = case?.status == BatteryStatus.CHARGING ||
        case?.status == BatteryStatus.OPTIMIZED_CHARGING

    val singleDisplayed = remember { mutableStateOf(false) }

    Row {
        Column(
            modifier = Modifier.fillMaxWidth(0.5f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                bitmap = ImageBitmap.imageResource(budsRes),
                contentDescription = stringResource(R.string.buds),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )

            if (
                leftCharging == rightCharging &&
                (leftLevel - rightLevel) in -3..3
            ) {
                BatteryIndicator(
                    leftLevel.coerceAtMost(rightLevel),
                    leftCharging
                )
                singleDisplayed.value = true
            } else {
                singleDisplayed.value = false

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (leftLevel > 0 || left?.status != BatteryStatus.DISCONNECTED) {
                        BatteryIndicator(
                            leftLevel,
                            leftCharging,
                            "\uDBC6\uDCE5"
                        )
                    }

                    if (leftLevel > 0 && rightLevel > 0) {
                        Spacer(modifier = Modifier.width(16.dp))
                    }

                    if (rightLevel > 0 || right?.status != BatteryStatus.DISCONNECTED) {
                        BatteryIndicator(
                            rightLevel,
                            rightCharging,
                            "\uDBC6\uDCE8"
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
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
                    prefix = if (!singleDisplayed.value) "\uDBC3\uDE6C" else ""
                )
            }
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BatteryViewPreview() {
    val fakeBattery = listOf(
        Battery(BatteryComponent.LEFT, 85, BatteryStatus.CHARGING),
        Battery(BatteryComponent.RIGHT, 40, BatteryStatus.CHARGING),
        Battery(BatteryComponent.CASE, 60, BatteryStatus.NOT_CHARGING)
    )

    val bg = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)

    Box(
        modifier = Modifier
            .background(bg)
            .padding(16.dp)
    ) {
        BatteryView(
            batteryList = fakeBattery,
            budsRes = R.drawable.airpods_pro_2_buds,
            caseRes = R.drawable.airpods_pro_2_case
        )
    }
}
