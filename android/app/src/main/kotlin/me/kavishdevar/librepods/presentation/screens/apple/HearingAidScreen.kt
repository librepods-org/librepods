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

package me.kavishdevar.librepods.presentation.screens.apple

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendTransparencySettings
import me.kavishdevar.librepods.presentation.components.primitives.StyledConfirmationDialog
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

private const val TAG = "HearingAidScreen"

@SuppressLint("DefaultLocale")
@Composable
fun HearingAidScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?,
    navigateToHearingAidAdjustments: () -> Unit,
    navigateToHearingTest: () -> Unit
) {
    val verticalScrollState  = rememberScrollState()
    val backdrop = rememberLayerBackdrop()

    val showDialog = remember { mutableStateOf(false) }
    val initialLoad = remember { mutableStateOf(true) }

    val uiState by viewModel.uiState.collectAsState()

    val state = uiState.state

    val hearingAidEnabled = remember {
        val aidStatus = state.controlStates[ControlCommandIdentifier.HEARING_AID]
        val assistStatus = state.controlStates[ControlCommandIdentifier.HEARING_ASSIST_CONFIG]
        mutableStateOf((aidStatus?.getOrNull(1) == 0x01.toByte()) && (assistStatus?.getOrNull(0) == 0x01.toByte()))
    }

    StyledScaffold(
        title = stringResource(R.string.hearing_aid),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

//            val mediaAssistEnabled = remember { mutableStateOf(false) }
//            val adjustMediaEnabled = remember { mutableStateOf(false) }
//            val adjustPhoneEnabled = remember { mutableStateOf(false) }

            LaunchedEffect(hearingAidEnabled.value) {
                if (hearingAidEnabled.value && !initialLoad.value) {
                    showDialog.value = true
                } else if (!hearingAidEnabled.value && !initialLoad.value) {
                    viewModel.setControlCommand(ControlCommandIdentifier.HEARING_AID, byteArrayOf(0x01, 0x02))
                    viewModel.setControlCommand(ControlCommandIdentifier.HEARING_ASSIST_CONFIG, 0x02.toByte())
                    hearingAidEnabled.value = false
                }
                initialLoad.value = false
            }

//            fun onAdjustPhoneChange(value: Boolean) {
//                // TODO
//            }

//            fun onAdjustMediaChange(value: Boolean) {
//                // TODO
//            }

            StyledList (title = stringResource(R.string.hearing_aid)) {
                styledToggle(
                    label = stringResource(R.string.hearing_aid),
                    checked = hearingAidEnabled.value,
                    onCheckedChange = { hearingAidEnabled.value = it },
                )
                styledListItem(
                    contentText = stringResource(R.string.adjustments),
                    onClick = navigateToHearingAidAdjustments,
                )
            }

            Text(
                text = stringResource(R.string.hearing_aid_description),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Light),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))

            StyledListItem(
                contentText = stringResource(R.string.update_hearing_test),
                onClick = navigateToHearingTest,
            )

            // not implemented yet

            // StyledToggle(
            //     titleRes = stringResource(R.string.media_assist),
            //     label = stringResource(R.string.media_assist),
            //     checkedState = mediaAssistEnabled,
            //     independent = true,
            //     descriptionRes = stringResource(R.string.media_assist_description)
            // )

            // Spacer(modifier = Modifier.height(8.dp))

            // Column (
            //     modifier = Modifier
            //         .fillMaxWidth()
            //         .background(backgroundColor, RoundedCornerShape(28.dp))
            // ) {
            //     StyledToggle(
            //         label = stringResource(R.string.adjust_media),
            //         checkedState = adjustMediaEnabled,
            //         onCheckedChange = { onAdjustMediaChange(it) },
            //         independent = false
            //     )
            //     HorizontalDivider(
            //         thickness = 1.dp,
            //         color = Color(0x40888888),
            //         modifier = Modifier
            //             .padding(horizontal = 12.dp)
            //     )

            //     StyledToggle(
            //         label = stringResource(R.string.adjust_calls),
            //         checkedState = adjustPhoneEnabled,
            //         onCheckedChange = { onAdjustPhoneChange(it) },
            //         independent = false
            //     )
            // }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }

    StyledConfirmationDialog(
        showDialog = showDialog,
        title = stringResource(R.string.enable_hearing_aid),
        message = stringResource(R.string.enable_hearing_aid_description),
        confirmText = stringResource(R.string.enable),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDialog.value = false
            val enrolled = state.controlStates[ControlCommandIdentifier.HEARING_AID]?.getOrNull(0) == 0x01.toByte()
            if (!enrolled) {
                viewModel.setControlCommand(ControlCommandIdentifier.HEARING_AID, byteArrayOf(0x01, 0x01))
            } else {
                viewModel.setControlCommand(ControlCommandIdentifier.HEARING_AID, byteArrayOf(0x01, 0x01))
            }
            viewModel.setControlCommand(ControlCommandIdentifier.HEARING_ASSIST_CONFIG, 0x01.toByte())
            hearingAidEnabled.value = true
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (state.hearingAidData.isEmpty()) {
                        Log.w(TAG, "read failed")
                        return@launch
                    }
                    val parsed = parseTransparencySettingsResponse(state.hearingAidData)
                    if (parsed == null) {
                        Log.w(TAG, "hearingaid parse failed")
                        return@launch
                    }
                    val disabledSettings = parsed.copy(enabled = false)
                    sendTransparencySettings(viewModel::writeATTCharacteristic, disabledSettings)
                } catch (e: Exception) {
                    Log.e(TAG, "Error disabling hearingaid: ${e.message}")
                }
            }
        },
        onDismiss = {
            hearingAidEnabled.value = false
            showDialog.value = false
        },
         backdrop = backdrop
    )
}
