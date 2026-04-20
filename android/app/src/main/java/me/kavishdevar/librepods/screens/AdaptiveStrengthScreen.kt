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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.StyledIconButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.composables.StyledSlider
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.AACPManager
import kotlin.io.encoding.ExperimentalEncodingApi

private var debounceJob: Job? = null

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun AdaptiveStrengthScreen(navController: NavController) {
    val isDarkTheme = isSystemInDarkTheme()

    val sliderValue = remember { mutableFloatStateOf(0f) }
    val service = ServiceManager.getService()!!

    LaunchedEffect(sliderValue) {
        val sliderValueFromAACP = service.aacpManager.controlCommandStatusList.find {
            it.identifier == AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH
        }?.value?.takeIf { it.isNotEmpty() }?.get(0)
        sliderValueFromAACP?.toFloat()?.let { sliderValue.floatValue = (100 - it) }
    }

    val listener = remember {
        object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                if (controlCommand.identifier == AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH.value) {
                    controlCommand.value.takeIf { it.isNotEmpty() }?.get(0)?.toFloat()?.let {
                        sliderValue.floatValue = (100 - it)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        service.aacpManager.registerControlCommandListener(
            AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
            listener
        )
        onDispose {
            service.aacpManager.unregisterControlCommandListener(
                AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
                listener
            )
        }
    }

    val backdrop = rememberLayerBackdrop()

    StyledScaffold(
        title = stringResource(R.string.customize_adaptive_audio)
    ) { spacerHeight ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(spacerHeight))
            StyledSlider(
                label = stringResource(R.string.customize_adaptive_audio),
                mutableFloatState = sliderValue,
                onValueChange = {
                    sliderValue.floatValue = it
                    debounceJob?.cancel()
                    debounceJob = CoroutineScope(Dispatchers.Default).launch {
                        delay(300)
                        service.aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH.value,
                            (100 - it).toInt()
                        )
                    }
                },
                valueRange = 0f..100f,
                snapPoints = listOf(0f, 50f, 100f),
                startIcon = "􀊥",
                endIcon = "􀊩",
                independent = true,
                description = stringResource(R.string.adaptive_audio_description)
            )
        }
    }
}
