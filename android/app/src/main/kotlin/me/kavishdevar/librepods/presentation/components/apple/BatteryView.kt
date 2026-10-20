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

package me.kavishdevar.librepods.presentation.components.apple

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.core.R as coreR

@Composable
fun BatteryView(
    batteryList: Set<Battery>,
    @DrawableRes primaryImageRes: Int,
    @DrawableRes caseImageRes: Int?
) {
    Row(
        modifier = Modifier
            .widthIn(max = 350.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val headsetBattery = batteryList.find { it.component == BatteryComponent.HEADSET }
        if (headsetBattery != null) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    bitmap = ImageBitmap.imageResource(primaryImageRes),
                    contentDescription = stringResource(R.string.headset),
                    modifier = Modifier.fillMaxWidth()
                )

                BatteryIndicator(
                    headsetBattery.level,
                    headsetBattery.status,
                )
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = ImageBitmap.imageResource(primaryImageRes),
                        contentDescription = stringResource(R.string.buds),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
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
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = ImageBitmap.imageResource(caseImageRes?: coreR.drawable.img_airpods_pro_3_case),
                        contentDescription = stringResource(R.string.case_alt),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
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


@Preview
@Composable
fun BatteryViewPreview() {
    val fakeBattery = setOf(
        Battery(BatteryComponent.LEFT, 85, BatteryStatus.CHARGING),
        Battery(BatteryComponent.RIGHT, 40, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.CASE, 60, BatteryStatus.NOT_CHARGING)
    )

    LibrePodsTheme(
        designSystem = DesignSystem.Apple
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp)
        ) {
            BatteryView(
                batteryList = fakeBattery,
                primaryImageRes = coreR.drawable.img_airpods_pro_2_buds,
                caseImageRes = coreR.drawable.img_airpods_pro_2_case
            )
        }
    }
}

@Preview
@Composable
fun BatteryViewHeadsetPreview() {
    LibrePodsTheme(
        designSystem = DesignSystem.Apple
    ) {
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
                primaryImageRes = coreR.drawable.img_airpods_max_2_black,
                caseImageRes = coreR.drawable.img_airpods_pro_2_case
            )
        }
    }
}
