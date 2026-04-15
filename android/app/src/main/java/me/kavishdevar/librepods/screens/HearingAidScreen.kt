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

package me.kavishdevar.librepods.screens

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.ConfirmationDialog
import me.kavishdevar.librepods.composables.NavigationButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.composables.StyledToggle
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.AACPManager
import me.kavishdevar.librepods.utils.ATTHandles
import me.kavishdevar.librepods.utils.parseTransparencySettingsResponse
import me.kavishdevar.librepods.utils.sendTransparencySettings
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AccessibilitySettings"

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun HearingAidScreen(navController: NavController) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val verticalScrollState  = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val attManager = ServiceManager.getService()?.attManager ?: return

    val aacpManager = remember { ServiceManager.getService()?.aacpManager }

    val showDialog = remember { mutableStateOf(false) }
    val backdrop = rememberLayerBackdrop()
    val initialLoad = remember { mutableStateOf(true) }

    val hearingAidEnabled = remember {
        val aidStatus = aacpManager?.controlCommandStatusList?.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID }
        val assistStatus = aacpManager?.controlCommandStatusList?.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.HEARING_ASSIST_CONFIG }
        mutableStateOf((aidStatus?.value?.getOrNull(1) == 0x01.toByte()) && (assistStatus?.value?.getOrNull(0) == 0x01.toByte()))
    }

    val hazeStateS = remember { mutableStateOf(HazeState()) } // dont question this. i could possibly use something other than initializing it with an empty state and then replacing it with the the one provided by the scaffold

    StyledScaffold(
        title = stringResource(R.string.hearing_aid),
        snackbarHostState = snackbarHostState,
    ) { spacerHeight, hazeState ->
        Column(
            modifier = Modifier
                .layerBackdrop(backdrop)
                .hazeSource(hazeState)
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            hazeStateS.value = hazeState
            Spacer(modifier = Modifier.height(spacerHeight))

            val hearingAidListener = remember {
                object : AACPManager.ControlCommandListener {
                    override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                        if (controlCommand.identifier == AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID.value ||
                            controlCommand.identifier == AACPManager.Companion.ControlCommandIdentifiers.HEARING_ASSIST_CONFIG.value) {
                            val aidStatus = aacpManager?.controlCommandStatusList?.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID }
                            val assistStatus = aacpManager?.controlCommandStatusList?.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.HEARING_ASSIST_CONFIG }
                            hearingAidEnabled.value = (aidStatus?.value?.getOrNull(1) == 0x01.toByte()) && (assistStatus?.value?.getOrNull(0) == 0x01.toByte())
                        }
                    }
                }
            }

//            val mediaAssistEnabled = remember { mutableStateOf(false) }
//            val adjustMediaEnabled = remember { mutableStateOf(false) }
//            val adjustPhoneEnabled = remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                aacpManager?.registerControlCommandListener(AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID, hearingAidListener)
                aacpManager?.registerControlCommandListener(AACPManager.Companion.ControlCommandIdentifiers.HEARING_ASSIST_CONFIG, hearingAidListener)
            }

            DisposableEffect(Unit) {
                onDispose {
                    aacpManager?.unregisterControlCommandListener(AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID, hearingAidListener)
                    aacpManager?.unregisterControlCommandListener(AACPManager.Companion.ControlCommandIdentifiers.HEARING_ASSIST_CONFIG, hearingAidListener)
                }
            }

            LaunchedEffect(hearingAidEnabled.value) {
                if (hearingAidEnabled.value && !initialLoad.value) {
                    showDialog.value = true
                } else if (!hearingAidEnabled.value && !initialLoad.value) {
                    aacpManager?.sendControlCommand(AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID.value, byteArrayOf(0x01, 0x02))
                    aacpManager?.sendControlCommand(AACPManager.Companion.ControlCommandIdentifiers.HEARING_ASSIST_CONFIG.value, 0x02.toByte())
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

            Text(
                text = stringResource(R.string.hearing_aid),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(16.dp, bottom = 2.dp)
            )

            val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor, RoundedCornerShape(28.dp))
                    .clip(
                        RoundedCornerShape(28.dp)
                    )
            ) {
                StyledToggle(
                    label = stringResource(R.string.hearing_aid),
                    checkedState = hearingAidEnabled,
                    independent = false
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                )
                NavigationButton(
                    to = "hearing_aid_adjustments",
                    name = stringResource(R.string.adjustments),
                    navController,
                    independent = false
                )
            }
            Text(
                text = stringResource(R.string.hearing_aid_description),
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light,
                    color = (if (isSystemInDarkTheme()) Color.White else Color.Black).copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))

            NavigationButton(
                to = "update_hearing_test",
                name = stringResource(R.string.update_hearing_test),
                navController,
                independent = true
            )

            // not implemented yet

            // StyledToggle(
            //     title = stringResource(R.string.media_assist),
            //     label = stringResource(R.string.media_assist),
            //     checkedState = mediaAssistEnabled,
            //     independent = true,
            //     description = stringResource(R.string.media_assist_description)
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
        }
    }

    ConfirmationDialog(
        showDialog = showDialog,
        title = "Enable Hearing Aid",
        message = "Enabling Hearing Aid will disable Headphone Accommodation and Customized Transparency Mode.",
        confirmText = "Enable",
        dismissText = "Cancel",
        onConfirm = {
            showDialog.value = false
            val enrolled = aacpManager?.controlCommandStatusList?.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID }?.value?.getOrNull(0) == 0x01.toByte()
            if (!enrolled) {
                aacpManager?.sendControlCommand(AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID.value, byteArrayOf(0x01, 0x01))
            } else {
                aacpManager.sendControlCommand(AACPManager.Companion.ControlCommandIdentifiers.HEARING_AID.value, byteArrayOf(0x01, 0x01))
            }
            aacpManager?.sendControlCommand(AACPManager.Companion.ControlCommandIdentifiers.HEARING_ASSIST_CONFIG.value, 0x01.toByte())
            hearingAidEnabled.value = true
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val data = attManager.read(ATTHandles.TRANSPARENCY)
                    val parsed = parseTransparencySettingsResponse(data)
                    val disabledSettings = parsed.copy(enabled = false)
                    sendTransparencySettings(attManager, disabledSettings)
                } catch (e: Exception) {
                    Log.e(TAG, "Error disabling transparency: ${e.message}")
                }
            }
        },
        hazeState = hazeStateS.value,
        // backdrop = backdrop
    )
}
