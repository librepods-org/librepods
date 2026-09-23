package me.kavishdevar.librepods.data.updates

import androidx.compose.runtime.Composable
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.screens.apple.AppleSettingsScreenPreviewMaterial
import me.kavishdevar.librepods.presentation.screens.apple.EqualizerScreenPreviewApple
import me.kavishdevar.librepods.presentation.screens.apple.EqualizerScreenPreviewMaterial
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem

val update1_0_0 = listOf(
        UpdateItem(
            titleRes = R.string.material3e,
            descriptionRes = R.string.update_m3e_description,
            demoComposeable = @Composable {
                AppleSettingsScreenPreviewMaterial()
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
                    DesignSystem.Material, DesignSystem.Miuix -> {
                        EqualizerScreenPreviewMaterial()
                    }
                }
            }
        ),
    )

val updates = update1_0_0
