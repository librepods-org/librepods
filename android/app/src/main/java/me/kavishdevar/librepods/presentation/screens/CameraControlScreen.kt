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

//@Composable
//fun CameraControlScreen(viewModel: AirPodsViewModel) {
//    val context = LocalContext.current
//    val currentCameraAction by viewModel.cameraAction.collectAsState()
//
//    fun isAppListenerServiceEnabled(context: Context): Boolean {
//        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
//        val enabledServices =
//            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
//        val serviceComponent = ComponentName(context, AppListenerService::class.java)
//        return enabledServices.any {
//            it.resolveInfo.serviceInfo.packageName == serviceComponent.packageName &&
//                it.resolveInfo.serviceInfo.name == serviceComponent.className
//        }
//    }
//
//    fun handleSelection(action: StemPressType?) {
//        if (action != null && !isAppListenerServiceEnabled(context)) {
//            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
//        } else {
//            viewModel.setCameraAction(action)
//        }
//    }
//
//    val cameraOptions = remember(currentCameraAction) {
//        listOf(
//            SelectItem(
//                name = "Off",
//                selected = currentCameraAction == null,
//                onClick = { handleSelection(null) }
//            ),
//            SelectItem(
//                name = "Press once",
//                selected = currentCameraAction == StemPressType.SINGLE_PRESS,
//                onClick = { handleSelection(StemPressType.SINGLE_PRESS) }
//            ),
//            SelectItem(
//                name = "Press and hold AirPods",
//                selected = currentCameraAction == StemPressType.LONG_PRESS,
//                onClick = { handleSelection(StemPressType.LONG_PRESS) }
//            )
//        )
//    }
//
//    val backdrop = rememberLayerBackdrop()
//
//    StyledScaffold(
//        titleRes = stringResource(R.string.camera_control)
//    ) { spacerHeight ->
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .layerBackdrop(backdrop)
//                .padding(horizontal = 16.dp),
//            verticalArrangement = Arrangement.spacedBy(16.dp)
//        ) {
//            Spacer(modifier = Modifier.height(spacerHeight))
//            StyledSelectList(items = cameraOptions)
//        }
//    }
//}
