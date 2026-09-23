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

package me.kavishdevar.librepods.presentation.components.apple

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.components.miuix.MiuixBatteryCard
import me.kavishdevar.librepods.presentation.components.miuix.MiuixDeviceImage
import me.kavishdevar.librepods.devices.Battery
import me.kavishdevar.librepods.devices.BatteryComponent
import me.kavishdevar.librepods.devices.BatteryStatus
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun BatteryView(
    batteryList: Set<Battery>,
    primaryImageRes: Int,
    caseImageRes: Int
) {
    if (LocalDesignSystem.current == DesignSystem.Miuix) {
        // HyperOS shows the hardware above a three-column battery card rather than a
        // battery reading per image, so the Miuix header is a different composition
        // rather than a restyling of the one below.
        Column {
            MiuixDeviceImage(budsRes = primaryImageRes, caseRes = caseImageRes)
            MiuixBatteryCard(batteryList = batteryList)
        }
        return
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.widthIn(max = 500.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            val headsetBattery = batteryList.find { it.component == BatteryComponent.HEADSET }
            if (headsetBattery != null) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = ImageBitmap.imageResource(primaryImageRes),
                        contentDescription = "Headset",
                        modifier = Modifier
                            .widthIn(max = 200.dp)
                            .fillMaxWidth()
                            .padding(8.dp)
                    )

                    if (headsetBattery.level > 0 || headsetBattery.status != BatteryStatus.DISCONNECTED) {
                        BatteryIndicator(
                            headsetBattery.level,
                            headsetBattery.status,
                        )
                    }
                }
            } else {

                val left = batteryList.find { it.component == BatteryComponent.LEFT }
                val right = batteryList.find { it.component == BatteryComponent.RIGHT }
                val case = batteryList.find { it.component == BatteryComponent.CASE }

                val leftLevel = left?.level ?: 0
                val rightLevel = right?.level ?: 0
                val caseLevel = case?.level ?: 0

                val singleDisplayed = remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = ImageBitmap.imageResource(primaryImageRes),
                        contentDescription = stringResource(R.string.buds),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )

                    if (
                        left?.status == right?.status &&
                        (leftLevel - rightLevel) in -3..3
                    ) {
                        BatteryIndicator(
                            leftLevel.coerceAtMost(rightLevel),
                            left?.status ?: BatteryStatus.NOT_CHARGING
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
                                    left?.status ?: BatteryStatus.NOT_CHARGING,
                                    "LeftCircleFill"
                                )
                            }

                            if (leftLevel > 0 && rightLevel > 0) {
                                Spacer(modifier = Modifier.width(16.dp))
                            }

                            if (rightLevel > 0 || right?.status != BatteryStatus.DISCONNECTED) {
                                BatteryIndicator(
                                    rightLevel,
                                    right?.status ?: BatteryStatus.NOT_CHARGING,
                                    "RightCircleFill"
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = ImageBitmap.imageResource(caseImageRes),
                        contentDescription = stringResource(R.string.case_alt),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )

                    if (caseLevel > 0 || case?.status != BatteryStatus.DISCONNECTED) {
                        BatteryIndicator(
                            caseLevel,
                            case?.status ?: BatteryStatus.NOT_CHARGING,
                            if (!singleDisplayed.value) "AirPodsPro3CaseFill" else null
                        )
                    }
                }
            }
        }
    }
}


@Preview
@Composable
fun BatteryViewPreview() {
    val fakeBattery = setOf(
        Battery(BatteryComponent.LEFT, 85, BatteryStatus.CHARGING),
        Battery(BatteryComponent.RIGHT, 40, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.CASE, 60, BatteryStatus.NOT_CHARGING)
    )

    Column {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp)
        ) {
            BatteryView(
                batteryList = fakeBattery,
                primaryImageRes = R.drawable.img_airpods_pro_2_buds,
                caseImageRes = R.drawable.img_airpods_pro_2_case
            )
        }

        val fakeBatteryHeadset = setOf(
            Battery(BatteryComponent.HEADSET, 50, BatteryStatus.CHARGING),
        )

        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp)
        ) {
            BatteryView(
                batteryList = fakeBatteryHeadset,
                primaryImageRes = R.drawable.img_airpods_max,
                caseImageRes = R.drawable.img_airpods_pro_2_case
            )
        }
    }
}
