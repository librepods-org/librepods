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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.core.content.edit
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.composables.SelectItem
import me.kavishdevar.librepods.composables.StyledIconButton
import me.kavishdevar.librepods.composables.StyledScaffold
import me.kavishdevar.librepods.composables.StyledSelectList
import me.kavishdevar.librepods.composables.StyledSlider
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.services.AppListenerService
import me.kavishdevar.librepods.utils.AACPManager
import me.kavishdevar.librepods.utils.AACPManager.Companion.StemPressType
import kotlin.io.encoding.ExperimentalEncodingApi

private var debounceJob: Job? = null

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun CameraControlScreen(navController: NavController) {
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val service = ServiceManager.getService()!!
    var currentCameraAction by remember {
        mutableStateOf(
            sharedPreferences.getString("camera_action", null)?.let { StemPressType.valueOf(it) }
        )
    }

    fun isAppListenerServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val serviceComponent = ComponentName(context, AppListenerService::class.java)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == serviceComponent.packageName && it.resolveInfo.serviceInfo.name == serviceComponent.className }
    }

    val cameraOptions = listOf(
        SelectItem(
            name = stringResource(R.string.off),
            selected = currentCameraAction == null,
            onClick = {
                sharedPreferences.edit { remove("camera_action") }
                currentCameraAction = null
            }
        ),
        SelectItem(
            name = stringResource(R.string.press_once),
            selected = currentCameraAction == StemPressType.SINGLE_PRESS,
            onClick = {
                if (!isAppListenerServiceEnabled(context)) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    sharedPreferences.edit { putString("camera_action", StemPressType.SINGLE_PRESS.name) }
                    currentCameraAction = StemPressType.SINGLE_PRESS
                }
            }
        ),
        SelectItem(
            name = stringResource(R.string.press_and_hold_airpods),
            selected = currentCameraAction == StemPressType.LONG_PRESS,
            onClick = {
                if (!isAppListenerServiceEnabled(context)) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    sharedPreferences.edit { putString("camera_action", StemPressType.LONG_PRESS.name) }
                    currentCameraAction = StemPressType.LONG_PRESS
                }
            }
        )
    )

    val backdrop = rememberLayerBackdrop()

    StyledScaffold(
        title = stringResource(R.string.camera_control)
    ) { spacerHeight ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(spacerHeight))
            StyledSelectList(items = cameraOptions)
        }
    }
}
