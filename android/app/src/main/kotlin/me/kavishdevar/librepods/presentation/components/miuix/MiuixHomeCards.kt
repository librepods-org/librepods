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

package me.kavishdevar.librepods.presentation.components.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.devices.Battery
import me.kavishdevar.librepods.devices.BatteryComponent
import me.kavishdevar.librepods.devices.BatteryStatus
import me.kavishdevar.librepods.devices.NoiseControlMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalDivider
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The unselected circle, measured off the headset page HyperOS ships. The Miuix palette
 * has no equivalent token, so the values live here: #454545 dark, #E8E8E8 light.
 */
private val UnselectedCircleDark = Color(0xFF454545)
private val UnselectedCircleLight = Color(0xFFE8E8E8)

/** Icon colour inside an unselected circle: onPrimary in dark, a dark grey in light. */
private val UnselectedIconLight = Color(0xFF636363)

/** The system page draws these at 160px on a 480dpi screen; Miuix has no token for it. */
private val CircleSize = 53.dp

/** Battery outline plus terminal, filled to the level. There is no icon resource for it. */
@Composable
private fun BatteryGlyph(level: Int) {
    val outline = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 24.dp, height = 13.dp)
                .border(1.5.dp, outline, RoundedCornerShape(4.dp))
                .padding(2.dp)
        ) {
            if (level > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(level.coerceIn(0, 100) / 100f)
                        .background(outline, RoundedCornerShape(1.5.dp))
                )
            }
        }
        Spacer(modifier = Modifier.width(1.5.dp))
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 5.dp)
                .background(outline, RoundedCornerShape(1.dp))
        )
    }
}

/**
 * The header image. The HyperOS page puts the case under the title and above the battery
 * card rather than mixing it into the battery row. This only displays; it has no interaction.
 */
@Composable
fun MiuixDeviceImage(
    budsRes: Int,
    caseRes: Int,
    modifier: Modifier = Modifier
) {
    // The system page uses a single composed "buds in the case" image; LibrePods only has
    // the two separately, so they sit side by side: buds on the left, case on the right.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(budsRes),
            contentDescription = null,
            modifier = Modifier.size(150.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Image(
            painter = painterResource(caseRes),
            contentDescription = null,
            modifier = Modifier.size(150.dp)
        )
    }
}

/**
 * Battery card: left / right / case in equal thirds with a divider between them, after the
 * headset settings page HyperOS ships - label on top, value centred, glyph underneath.
 * A component that is not connected shows a dash rather than 0%, as the system does.
 */
@Composable
fun MiuixBatteryCard(
    batteryList: Collection<Battery>,
    modifier: Modifier = Modifier
) {
    val entries = listOf(
        R.string.left to batteryList.find { it.component == BatteryComponent.LEFT },
        R.string.right to batteryList.find { it.component == BatteryComponent.RIGHT },
        R.string.case_alt to batteryList.find { it.component == BatteryComponent.CASE }
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            entries.forEachIndexed { index, (labelRes, battery) ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(labelRes),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        // The level means nothing while the component is disconnected, and
                        // the system shows a dash there.
                        text = if (battery == null || battery.status == BatteryStatus.DISCONNECTED) {
                            "-"
                        } else {
                            "${battery.level}%"
                        },
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    BatteryGlyph(
                        level = if (battery == null ||
                            battery.status == BatteryStatus.DISCONNECTED
                        ) 0 else battery.level
                    )
                }
                if (index < entries.lastIndex) {
                    VerticalDivider(
                        modifier = Modifier.height(56.dp),
                        color = MiuixTheme.colorScheme.dividerLine
                    )
                }
            }
        }
    }
}

/**
 * Listening mode picker: a row of circular icon buttons with the selected one filled in the
 * accent. This is the shape of the control on the HyperOS headset page, not a Miuix TabRow.
 */
@Composable
fun MiuixNoiseControlCard(
    showOffOption: Boolean,
    currentMode: NoiseControlMode,
    onModeChange: (NoiseControlMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val unselectedCircle = if (isDark) UnselectedCircleDark else UnselectedCircleLight
    val unselectedIcon = if (isDark) MiuixTheme.colorScheme.onPrimary else UnselectedIconLight
    val modes = buildList {
        add(Triple(NoiseControlMode.TRANSPARENCY, R.string.transparency, R.drawable.ic_transparency))
        add(Triple(NoiseControlMode.ADAPTIVE, R.string.adaptive, R.drawable.ic_adaptive))
        add(
            Triple(
                NoiseControlMode.NOISE_CANCELLATION,
                R.string.noise_cancellation,
                R.drawable.ic_noise_cancellation
            )
        )
        if (showOffOption) {
            add(Triple(NoiseControlMode.OFF, R.string.off, R.drawable.ic_noise_cancellation))
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEach { (mode, labelRes, iconRes) ->
                val selected = mode == currentMode
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        // No ripple: the Column is an unclipped rectangle, so the default
                        // indication paints a square over the round button. The system page
                        // has none here either - the feedback is the selection itself.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onModeChange(mode) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(CircleSize)
                            .background(
                                // Measured off the system page: unselected is a constant
                                // grey, and selecting swaps the whole circle to the accent
                                // rather than lightening that grey.
                                if (selected) MiuixTheme.colorScheme.primary else unselectedCircle,
                                CircleShape
                            )
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = if (selected) MiuixTheme.colorScheme.onPrimary else unselectedIcon,
                            modifier = Modifier.size(27.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(labelRes),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}
