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

package me.kavishdevar.librepods.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R
import kotlin.io.encoding.ExperimentalEncodingApi

@ExperimentalHazeMaterialsApi
@Composable
fun CallControlSettings(
    flipped: Boolean,
    navigateToCallControlScreen: (action: String) -> Unit,
) {
    val pressOnceText = stringResource(R.string.press_once)
    val pressTwiceText = stringResource(R.string.press_twice)

    var singlePressAction by remember { mutableStateOf(if (flipped) pressTwiceText else pressOnceText) }
    var doublePressAction by remember { mutableStateOf(if (flipped) pressOnceText else pressTwiceText) }

    val muteUnmuteText = stringResource(R.string.mute_unmute)
    val hangUpText = stringResource(R.string.hang_up)

    StyledList(title = stringResource(R.string.call_controls)) {
        StyledListItem(
            name = stringResource(R.string.answer_call),
            description = stringResource(R.string.press_once),
            enabled = false
        )

        StyledListItem(
            name = muteUnmuteText,
            description = singlePressAction,
            onClick = { navigateToCallControlScreen(muteUnmuteText) } ,
        )

        StyledListItem(
            name = hangUpText,
            description = doublePressAction,
            onClick = { navigateToCallControlScreen(hangUpText) }
        )

//        StyledListItem(
//            name = pressOnceText,
//            selected = doublePressAction == pressOnceText,
//            onClick = {
//                doublePressAction = pressOnceText
//                singlePressAction = pressTwiceText
//
//                onCallControlValueChanged(true)
//            }
//        )
//
//        StyledListItem(
//            name = pressTwiceText,
//            selected = doublePressAction == pressTwiceText,
//            onClick = {
//                doublePressAction = pressTwiceText
//                singlePressAction = pressOnceText
//
//                onCallControlValueChanged(false)
//            }
//        )
    }
}
