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

package me.kavishdevar.librepods.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.StemAction

@Composable
fun PressAndHoldSettings(
    leftAction: StemAction,
    rightAction: StemAction,
    navigateToLeftLongPress: () -> Unit,
    navigateToRightLongPress: () -> Unit
) {
    val leftActionText = when (leftAction) {
        StemAction.CYCLE_NOISE_CONTROL_MODES -> stringResource(R.string.noise_control)
        StemAction.DIGITAL_ASSISTANT -> "Digital Assistant"
        else -> "INVALID!!"
    }

    val rightActionText = when (rightAction) {
        StemAction.CYCLE_NOISE_CONTROL_MODES -> stringResource(R.string.noise_control)
        StemAction.DIGITAL_ASSISTANT -> "Digital Assistant"
        else -> "INVALID!!"
    }

    StyledList(
        title = stringResource(R.string.press_and_hold_airpods)
    ) {
        StyledListItem(
            name = stringResource(R.string.left),
            description = leftActionText,
            onClick = navigateToLeftLongPress
        )
        StyledListItem(
            name = stringResource(R.string.right),
            description = rightActionText,
            onClick = navigateToRightLongPress,
        )
    }
}
