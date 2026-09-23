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

package me.kavishdevar.librepods.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import me.kavishdevar.librepods.presentation.icons.AppleIcons
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.icons.MaterialIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

val ColorScheme.sectionHeader: Color
    get() = onBackground.copy(alpha = 0.6f)

private val AppleDarkColorScheme = darkColorScheme(
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceDim = Color(0x40888888),
    primary = Color(0xFF0091FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF003258),
    onPrimaryContainer = Color(0xFFB3D9FF),
    secondaryContainer = Color(0xFF366AA8),
    onSecondaryContainer = Color(0xFFB3D9FF),
    tertiary = Color(0xFFEA7B00),
    scrim = Color(0x8C000000),
    surfaceContainerHigh = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF2C2C2E)
)

private val AppleLightColorScheme = lightColorScheme(
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surfaceContainer = Color(0xFFF2F2F7),
    onSurface = Color(0xFF000000),
    surfaceDim = Color(0x40D9D9D9),
    primary = Color(0xFF0088FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB3D9FF),
    onPrimaryContainer = Color(0xFF003258),
    secondaryContainer = Color(0xFF6BC0FF),
    onSecondaryContainer = Color(0xFF003258),
    tertiary = Color(0xFFEA7B00),
    scrim = Color(0xD9F2F2F7),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFE7E7E7)
)

@Composable
fun LibrePodsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    designSystem: DesignSystem = DesignSystem.Material,
    overrideMaterialColor: Color? = null,
    content: @Composable () -> Unit
) {
    val miuixColors = if (darkTheme) miuixDarkColorScheme() else miuixLightColorScheme()

    val colorScheme = when(designSystem) {
        DesignSystem.Material -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // The Material scheme has to be derived from the Miuix palette here rather than
        // from dynamic color: the screens paint their canvas with
        // MaterialTheme.colorScheme.surfaceContainer, so two unrelated palettes leave the
        // page background and the Miuix cards on top of it in different colours.
        DesignSystem.Miuix -> {
            val base = if (darkTheme) darkColorScheme() else lightColorScheme()
            // Miuix and Material use the surface roles in opposite senses:
            //   miuix surface          = the page canvas (pure black in dark mode)
            //   miuix surfaceContainer = the card fill (#242424 in dark mode)
            // while the screens here paint the page with Material's surfaceContainer and
            // the cards with surface. The mapping is crossed for that reason; matching the
            // roles by name would give the page and its cards the same colour.
            base.copy(
                background = miuixColors.surface,
                onBackground = miuixColors.onSurface,
                surface = miuixColors.surfaceContainer,
                onSurface = miuixColors.onSurfaceContainer,
                surfaceContainer = miuixColors.surface,
                surfaceContainerHigh = miuixColors.surfaceContainerHigh,
                surfaceContainerHighest = miuixColors.surfaceContainerHighest,
                surfaceVariant = miuixColors.surfaceVariant,
                surfaceDim = miuixColors.windowDimming,
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                outline = miuixColors.outline,
                error = miuixColors.error,
                onError = miuixColors.onError
            )
        }
        DesignSystem.Apple -> if (darkTheme) AppleDarkColorScheme else AppleLightColorScheme
    }

    val typography = when(designSystem) {
        DesignSystem.Material -> MaterialTypography
        DesignSystem.Miuix -> MiuixTypography
        DesignSystem.Apple -> AppleTypography
    }

    CompositionLocalProvider(
        LocalDesignSystem provides designSystem,
        LocalIcons provides when (designSystem) {
            DesignSystem.Material -> MaterialIcons
            // Miuix draws its own glyphs where it needs them; everything routed through
            // LocalIcons is shared UI, so it keeps the Material set.
            DesignSystem.Miuix -> MaterialIcons
            DesignSystem.Apple -> AppleIcons
        }
    ) {
//        var colorScheme = colorScheme
//
//        if (designSystem == DesignSystem.Material) {
//
//        }

        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = typography
        ) {
            // MiuixTheme has to sit inside the Material one: the Miuix widgets read their
            // colours from it, while everything shared still reads MaterialTheme.
            if (designSystem == DesignSystem.Miuix) {
                MiuixTheme(colors = miuixColors, content = content)
            } else {
                content()
            }
        }
    }
}
