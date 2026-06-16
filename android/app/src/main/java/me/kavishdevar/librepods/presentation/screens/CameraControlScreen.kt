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

package me.kavishdevar.librepods.presentation.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.SelectItem
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledSelectList
import me.kavishdevar.librepods.services.AppListenerService
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@Composable
fun CameraControlScreen(viewModel: AirPodsViewModel) {
    val context = LocalContext.current
    val currentCameraAction by viewModel.cameraAction.collectAsState()

    fun isAppListenerServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val serviceComponent = ComponentName(context, AppListenerService::class.java)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == serviceComponent.packageName &&
                it.resolveInfo.serviceInfo.name == serviceComponent.className
        }
    }

    fun handleSelection(action: StemPressType?) {
        if (action != null && !isAppListenerServiceEnabled(context)) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            viewModel.setCameraAction(action)
        }
    }

    val cameraOptions = remember(currentCameraAction) {
        listOf(
            SelectItem(
                name = "Off",
                selected = currentCameraAction == null,
                onClick = { handleSelection(null) }
            ),
            SelectItem(
                name = "Press once",
                selected = currentCameraAction == StemPressType.SINGLE_PRESS,
                onClick = { handleSelection(StemPressType.SINGLE_PRESS) }
            ),
            SelectItem(
                name = "Press and hold AirPods",
                selected = currentCameraAction == StemPressType.LONG_PRESS,
                onClick = { handleSelection(StemPressType.LONG_PRESS) }
            )
        )
    }

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
