package me.kavishdevar.librepods.presentation.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.utils.XposedState

@Composable
fun DeviceInfoCard() {
    StyledList(title = stringResource(R.string.device_info)) {
        StyledListItem(
            name = stringResource(R.string.manufacturer),
            description = Build.MANUFACTURER,
            enabled = false
        )

        StyledListItem(
            name = stringResource(R.string.model_number),
            description = Build.MODEL,
            enabled = false
        )

        StyledListItem(
            name = stringResource(R.string.build_id),
            description = Build.DISPLAY,
            enabled = false
        )

        StyledListItem(
            name = stringResource(R.string.version),
            description = "${Build.ID} (${Build.VERSION.SDK_INT_FULL})",
            enabled = false
        )

        StyledListItem(
            name = stringResource(R.string.xposed_available),
            description = if (XposedState.isAvailable) {
                stringResource(R.string.yes)
            } else {
                stringResource(R.string.no)
            },
            enabled = false
        )

        StyledListItem(
            name = stringResource(R.string.app_enabled_in_xposed),
            description = if (XposedState.bluetoothScopeEnabled) {
                stringResource(R.string.yes)
            } else {
                stringResource(R.string.no)
            },
            enabled = false
        )
    }
}
