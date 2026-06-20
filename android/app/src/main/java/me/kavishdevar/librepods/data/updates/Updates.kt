package me.kavishdevar.librepods.data.updates

import androidx.compose.runtime.Composable
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.screens.AirPodsSettingsScreenPreviewMaterial
import me.kavishdevar.librepods.presentation.screens.EqualizerScreenPreviewApple
import me.kavishdevar.librepods.presentation.screens.EqualizerScreenPreviewMaterial
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem

val update0_3_1 = listOf(
        UpdateItem(
            titleRes = R.string.material3e,
            descriptionRes = R.string.update_m3e_description,
            demoComposeable = @Composable {
                AirPodsSettingsScreenPreviewMaterial()
            }
        ),
        UpdateItem(
            titleRes = R.string.equalizer,
            descriptionRes = R.string.update_equalizer_description,
            demoComposeable = @Composable {
                when (LocalDesignSystem.current) {
                    DesignSystem.Apple -> {
                        EqualizerScreenPreviewApple()
                    }
                    DesignSystem.Material -> {
                        EqualizerScreenPreviewMaterial()
                    }
                }
            }
        ),
    )

val updates = update0_3_1
