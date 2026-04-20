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

package me.kavishdevar.librepods.composables

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.constants.NoiseControlMode

private val ContainerColor = Color(0x593C3C3E)
private val SelectedIndicatorColorGray = Color(0xFF6C6C6E)
private val SelectedIndicatorColorBlue = Color(0xFF0A84FF)
private val TextColor = Color.White
private val IconTintUnselected = Color.White
private val IconTintSelected = Color.White

internal val AdaptiveRainbowBrush = Brush.sweepGradient(
    colors = listOf(
        Color(0xFFB03A2F), Color(0xFFB07A2F), Color(0xFFB0A22F), Color(0xFF6AB02F),
        Color(0xFF2FAAB0), Color(0xFF2F5EB0), Color(0xFF7D2FB0), Color(0xFFB02F7D),
        Color(0xFFB03A2F)
    )
)

internal val IconAreaSize = 72.dp
private val IconSize = 42.dp
private val IconRowHeight = IconAreaSize + 12.dp
private val TextRowHeight = 24.dp
private val TextSize = 12.sp

@Composable
fun ControlCenterNoiseControlSegmentedButton(
    modifier: Modifier = Modifier,
    availableModes: List<NoiseControlMode>,
    selectedMode: NoiseControlMode,
    onModeSelected: (NoiseControlMode) -> Unit
) {
    val selectedIndex = availableModes.indexOf(selectedMode).coerceAtLeast(0)
    val density = LocalDensity.current
    var iconRowWidthPx by remember { mutableFloatStateOf(0f) }
    val itemCount = availableModes.size

    val itemSlotWidthPx = remember(iconRowWidthPx, itemCount) {
        if (itemCount > 0 && iconRowWidthPx > 0) {
            iconRowWidthPx / itemCount
        } else {
            0f
        }
    }
    val itemSlotWidthDp = remember(itemSlotWidthPx) { with(density) { itemSlotWidthPx.toDp() } }
    val iconAreaSizePx = remember { with(density) { IconAreaSize.toPx() } }

    val targetIndicatorStartPx = remember(selectedIndex, itemSlotWidthPx, iconAreaSizePx) {
        if (itemSlotWidthPx > 0) {
            val slotCenterPx = (selectedIndex + 0.5f) * itemSlotWidthPx
            slotCenterPx - (iconAreaSizePx / 2f)
        } else {
            0f
        }
    }

    val indicatorOffset: Dp by animateDpAsState(
        targetValue = with(density) { targetIndicatorStartPx.toDp() },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "IndicatorOffset"
    )

    val indicatorBackground = remember(selectedMode) {
        when (selectedMode) {
            NoiseControlMode.ADAPTIVE -> AdaptiveRainbowBrush
            NoiseControlMode.OFF -> Brush.linearGradient(colors=listOf(SelectedIndicatorColorGray, SelectedIndicatorColorGray))
            NoiseControlMode.TRANSPARENCY,
            NoiseControlMode.NOISE_CANCELLATION -> Brush.linearGradient(colors=listOf(SelectedIndicatorColorBlue, SelectedIndicatorColorBlue))
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IconRowHeight)
                .clip(CircleShape)
                .background(ContainerColor)
                .onSizeChanged { iconRowWidthPx = it.width.toFloat() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = indicatorOffset)
                    .size(IconAreaSize)
                    .clip(CircleShape)
                    .background(indicatorBackground)
            )

            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                availableModes.forEach { mode ->
                    val isSelected = selectedMode == mode
                    NoiseControlIconItem(
                        modifier = Modifier.size(IconAreaSize),
                        mode = mode,
                        isSelected = isSelected,
                        onClick = { onModeSelected(mode) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TextRowHeight),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            availableModes.forEach { mode ->
                val isSelected = selectedMode == mode
                Text(
                    text = getModeLabel(mode),
                    color = TextColor,
                    fontSize = TextSize,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(itemSlotWidthDp.coerceAtLeast(1.dp))
                )
            }
        }
    }
}

@Composable
private fun NoiseControlIconItem(
    modifier: Modifier = Modifier,
    mode: NoiseControlMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val iconRes = remember(mode) { getModeIconRes(mode) }

    val tint = IconTintUnselected

    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = getModeLabel(mode),
            tint = if (isSelected && mode == NoiseControlMode.ADAPTIVE) IconTintSelected else tint,
            modifier = Modifier.size(IconSize)
        )
    }
}


private fun getModeIconRes(mode: NoiseControlMode): Int {
    return when (mode) {
        NoiseControlMode.OFF -> R.drawable.noise_cancellation
        NoiseControlMode.TRANSPARENCY -> R.drawable.transparency
        NoiseControlMode.ADAPTIVE -> R.drawable.adaptive
        NoiseControlMode.NOISE_CANCELLATION -> R.drawable.noise_cancellation
    }
}

private fun getModeLabel(mode: NoiseControlMode): String {
    return when (mode) {
        NoiseControlMode.OFF -> "Off"
        NoiseControlMode.TRANSPARENCY -> "Transparency"
        NoiseControlMode.ADAPTIVE -> "Adaptive"
        NoiseControlMode.NOISE_CANCELLATION -> "Noise Cancellation"
    }
}

