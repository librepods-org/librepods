package me.kavishdevar.librepods.presentation.navigation

import android.util.Log
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kyant.backdrop.backdrops.LayerBackdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.MaterialIcons
import me.kavishdevar.librepods.presentation.components.StyledIconButton
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@Composable
fun NavigationRoot(
    showReleaseNotes: Boolean = false,
    updatesShown: () -> Unit = {},
    showOnboarding: Boolean = false,
    onboardingComplete: () -> Unit = {},
    airPodsViewModel: AirPodsViewModel
) {
    val backStack = remember {
        mutableStateListOf(
            when {
                showOnboarding -> Screen.Onboarding
                showReleaseNotes -> Screen.ReleaseNotes
                else -> Screen.AirPodsSettings
            }
        )
    }

    val currentScreen = backStack.last()

    val state by airPodsViewModel.uiState.collectAsState()

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material

    val title = when (currentScreen) {
        Screen.Onboarding -> ""
        Screen.AirPodsSettings -> if (state.isLocallyConnected) state.deviceName else stringResource(R.string.app_name)
        Screen.Accessibility -> stringResource(R.string.accessibility)
        Screen.AdaptiveStrength -> stringResource(R.string.customize_adaptive_audio)
        Screen.AppSettings -> stringResource(R.string.settings)
//        Screen.CameraControl -> stringResource(R.string.camera_control)
        Screen.Equalizer -> stringResource(R.string.equalizer)
        Screen.HeadTracking -> stringResource(R.string.head_tracking)
        Screen.HearingAid -> stringResource(R.string.hearing_aid)
        Screen.HearingAidAdjustments -> stringResource(R.string.adjustments)
        Screen.HearingProtection -> stringResource(R.string.hearing_protection)
        is Screen.LongPress -> currentScreen.bud
        Screen.OpenSourceLicenses -> stringResource(R.string.open_source_licenses)
        Screen.Purchase -> stringResource(R.string.unlock_advanced_features)
        Screen.Rename -> stringResource(R.string.name)
        Screen.TransparencyCustomization -> stringResource(R.string.customize_transparency_mode)
        Screen.Troubleshooting -> stringResource(R.string.troubleshooting)
        Screen.UpdateHearingTest -> stringResource(R.string.update_hearing_test)
        Screen.VersionInfo -> stringResource(R.string.version)
        is Screen.CallControl -> currentScreen.action
        Screen.MicrophoneSettings -> stringResource(R.string.microphone_mode)
        Screen.ReleaseNotes -> ""
    }

    // is this a bad idea? probably. I can't think of a better way without having to pass around a shouldShowBackButton to each screen to pass to each scaffold
    val actionButtons = when (currentScreen) {
        Screen.AirPodsSettings -> listOf<@Composable (backdrop: LayerBackdrop) -> Unit>(
                { scaffoldBackdrop ->
                    if (m3eEnabled) {
                        FilledTonalIconButton(
                            onClick = { backStack.add(Screen.AppSettings) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Uniform)),

                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "settings",
                                modifier = Modifier.size(IconButtonDefaults.mediumIconSize)
                            )
                        }
                    } else {
                        StyledIconButton(
                            onClick = { backStack.add(Screen.AppSettings) },
                            icon = "􀍟",
                            backdrop = scaffoldBackdrop
                        )
                    }
                }
            )
        Screen.HeadTracking -> listOf<@Composable (backdrop: LayerBackdrop) -> Unit>(
            { scaffoldBackdrop ->
                if (m3eEnabled) {
                    FilledTonalIconToggleButton(
                        checked = state.headTrackingActive,
                        onCheckedChange = { if (it) airPodsViewModel.startHeadTracking() else airPodsViewModel.stopHeadTracking() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Uniform)),
                        shape = IconButtonDefaults.mediumRoundShape
                    ) {
                        Icon(
                            imageVector = if (state.headTrackingActive) MaterialIcons.pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(IconButtonDefaults.mediumIconSize)
                        )
                    }
                } else {
                    StyledIconButton(
                        onClick = {
                            if (!state.headTrackingActive) {
                                airPodsViewModel.startHeadTracking()
                                Log.d("HeadTrackingScreen", "Head tracking started")
                            } else {
                                airPodsViewModel.stopHeadTracking()
                                Log.d("HeadTrackingScreen", "Head tracking stopped")
                            }
                        },
                        icon = if (state.headTrackingActive) "􀊅" else "􀊃",
                        backdrop = scaffoldBackdrop
                    )
                }
            }
        )
        else -> listOf()
    }

    StyledScaffold(
        visible = currentScreen.showTopBar,
        title = title,
        showBackButton = backStack.size > 1,
        onNavigateBack = { backStack.removeAt(backStack.lastIndex) },
        actionButtons = actionButtons
    ) {
        AppNavGraph(
            showReleaseNotes = showReleaseNotes,
            updatesShown = updatesShown,
            showOnboarding = showOnboarding,
            onboardingComplete = onboardingComplete,
            backStack = backStack,
            airPodsViewModel = airPodsViewModel,
        )
    }
}
