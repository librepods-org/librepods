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

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.presentation.viewmodel.PRESS_ACTION_DEFAULTS

private val pressActionChoices = listOf(
    StemAction.PLAY_PAUSE,
    StemAction.NEXT_TRACK,
    StemAction.PREVIOUS_TRACK,
    StemAction.VOLUME_UP,
    StemAction.VOLUME_DOWN,
    StemAction.CYCLE_NOISE_CONTROL_MODES,
    StemAction.DIGITAL_ASSISTANT,
)

@Composable
fun stemActionLabel(action: StemAction): String = when (action) {
    StemAction.PLAY_PAUSE -> stringResource(R.string.stem_action_play_pause)
    StemAction.NEXT_TRACK -> stringResource(R.string.stem_action_next_track)
    StemAction.PREVIOUS_TRACK -> stringResource(R.string.stem_action_previous_track)
    StemAction.VOLUME_UP -> stringResource(R.string.stem_action_volume_up)
    StemAction.VOLUME_DOWN -> stringResource(R.string.stem_action_volume_down)
    StemAction.CYCLE_NOISE_CONTROL_MODES -> stringResource(R.string.noise_control)
    StemAction.DIGITAL_ASSISTANT -> stringResource(R.string.digital_assistant)
}

@Composable
fun StemPressSettings(
    pressActions: Map<String, StemAction>,
    onSelect: (prefKey: String, action: StemAction) -> Unit,
) {
    val rows = listOf(
        Triple("left_double_press_action", R.string.double_press, R.string.left),
        Triple("right_double_press_action", R.string.double_press, R.string.right),
        Triple("left_triple_press_action", R.string.triple_press, R.string.left),
        Triple("right_triple_press_action", R.string.triple_press, R.string.right),
    )
    StyledList(title = stringResource(R.string.stem_presses_title)) {
        rows.forEach { (prefKey, pressRes, sideRes) ->
            key(prefKey) {
                val current = pressActions[prefKey] ?: PRESS_ACTION_DEFAULTS.getValue(prefKey)
                var expanded by remember { mutableStateOf(false) }
                // StyledList registers items and draws them later, so the menu must live inside the item.
                StyledListItem(
                    name = stringResource(pressRes) + " – " + stringResource(sideRes),
                    description = stemActionLabel(current),
                    onClick = { expanded = true },
                    trailingContent = {
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            pressActionChoices.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(stemActionLabel(choice)) },
                                    onClick = {
                                        expanded = false
                                        onSelect(prefKey, choice)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}
