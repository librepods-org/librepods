package me.kavishdevar.librepods.presentation.navigation

import androidx.activity.BackEventCompat.Companion.EDGE_LEFT
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.data.updates.updates
import me.kavishdevar.librepods.presentation.screens.AccessibilitySettingsScreen
import me.kavishdevar.librepods.presentation.screens.AdaptiveStrengthScreen
import me.kavishdevar.librepods.presentation.screens.AirPodsSettingsRoute
import me.kavishdevar.librepods.presentation.screens.AppSettingsScreen
import me.kavishdevar.librepods.presentation.screens.CallControlScreen
import me.kavishdevar.librepods.presentation.screens.EqualizerRoute
import me.kavishdevar.librepods.presentation.screens.HeadTrackingScreen
import me.kavishdevar.librepods.presentation.screens.HearingAidAdjustmentsScreen
import me.kavishdevar.librepods.presentation.screens.HearingAidScreen
import me.kavishdevar.librepods.presentation.screens.HearingProtectionScreen
import me.kavishdevar.librepods.presentation.screens.LoadingScreen
import me.kavishdevar.librepods.presentation.screens.LongPress
import me.kavishdevar.librepods.presentation.screens.MicrophoneSettingsRoute
import me.kavishdevar.librepods.presentation.screens.OpenSourceLicensesScreen
import me.kavishdevar.librepods.presentation.screens.PurchaseScreen
import me.kavishdevar.librepods.presentation.screens.ReleaseNotesScreen
import me.kavishdevar.librepods.presentation.screens.RenameScreen
import me.kavishdevar.librepods.presentation.screens.TransparencySettingsScreen
import me.kavishdevar.librepods.presentation.screens.TroubleshootingScreen
import me.kavishdevar.librepods.presentation.screens.UpdateHearingTestRoute
import me.kavishdevar.librepods.presentation.screens.VersionScreen
import me.kavishdevar.librepods.presentation.screens.onboarding.OnboardingScreen
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.PurchaseViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppNavGraph(
    showReleaseNotes: Boolean = false,
    updatesShown: () -> Unit = {},
    showOnboarding: Boolean = false,
    onboardingComplete: () -> Unit = {},
    backStack: SnapshotStateList<Screen>,
    airPodsViewModel: AirPodsViewModel,
) {
    val navigate: (Screen) -> Unit = { screen ->
        backStack.add(screen)
    }

    fun navigateToPurchase() {
        navigate(Screen.Purchase)
    }

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material

    SharedTransitionLayout {
        NavDisplay(
            sharedTransitionScope = this,
            backStack = backStack,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.lastIndex)
                }
            },
            entryProvider = { screen ->
                when (screen) {
                    Screen.Onboarding ->
                        NavEntry(screen) {
                            OnboardingScreen {
                                onboardingComplete()
                                if (showReleaseNotes) navigate(Screen.ReleaseNotes) else navigate(Screen.AirPodsSettings)
                                backStack.remove(screen)
                            }
                        }
                    Screen.AirPodsSettings ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            AirPodsSettingsRoute(
                                viewModel = airPodsViewModel,
                                navigateToRename = { navigate(Screen.Rename) },
                                navigateToHearingProtection = { navigate(Screen.HearingProtection) },
                                navigateToHearingAid = { navigate(Screen.HearingAid) },
                                navigateToLeftLongPress = {
                                    navigate(
                                        Screen.LongPress("Left")
                                    )
                                },
                                navigateToRightLongPress = {
                                    navigate(
                                        Screen.LongPress("Right")
                                    )
                                },
                                navigateToPurchase = { navigate(Screen.Purchase) },
                                navigateToAdaptiveStrength = { navigate(Screen.AdaptiveStrength) },
                                navigateToEqualizer = { navigate(Screen.Equalizer) },
                                navigateToHeadTracking = { navigate(Screen.HeadTracking) },
                                navigateToAccessibility = { navigate(Screen.Accessibility) },
                                navigateToVersion = { navigate(Screen.VersionInfo) },
                                navigateToTroubleshooting = { navigate(Screen.Troubleshooting) },
                                navigateToCallControlScreen = { navigate(Screen.CallControl(it)) },
                                navigateToMicrophoneSettings = { navigate(Screen.MicrophoneSettings) },
                            )
                        }

                    Screen.Rename ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            RenameScreen(airPodsViewModel)
                        }

                    Screen.AppSettings ->
                        NavEntry(screen) {
                            val vm: AppSettingsViewModel = viewModel()
                            AppSettingsScreen(
                                viewModel = vm,
                                navigateToPurchase = ::navigateToPurchase,
                                navigateToTroubleshooting = { navigate(Screen.Troubleshooting) },
                                navigateToOpenSourceLicenses = { navigate(Screen.OpenSourceLicenses) },
                                navigateToReleaseNotesScreen = { navigate(Screen.ReleaseNotes) }
                            )
                        }

                    Screen.Troubleshooting ->
                        NavEntry(screen) {
                            TroubleshootingScreen()
                        }

                    Screen.HeadTracking ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            HeadTrackingScreen(airPodsViewModel, ::navigateToPurchase)
                        }

                    Screen.Accessibility ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            AccessibilitySettingsScreen(
                                viewModel = airPodsViewModel,
                                navigateToPurchase = ::navigateToPurchase,
                                navigateToTransparencyCustomization = { navigate(Screen.TransparencyCustomization) }
                            )
                        }

                    Screen.TransparencyCustomization ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            TransparencySettingsScreen(airPodsViewModel)
                        }

                    Screen.HearingAid ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            HearingAidScreen(
                                viewModel = airPodsViewModel,
                                onNavigateHearingAidAdjustments = { navigate(Screen.HearingAidAdjustments) },
                                onNavigateHearingTest = { navigate(Screen.UpdateHearingTest) },
                            )
                        }

                    Screen.HearingAidAdjustments ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            HearingAidAdjustmentsScreen(airPodsViewModel)
                        }

                    Screen.AdaptiveStrength ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            AdaptiveStrengthScreen(airPodsViewModel, ::navigateToPurchase)
                        }

//                Screen.CameraControl ->
//                    NavEntry(screen) {
//                        CameraControlScreen(airPodsViewModel)
//                    }

                    Screen.OpenSourceLicenses ->
                        NavEntry(screen) {
                            OpenSourceLicensesScreen()
                        }

                    Screen.UpdateHearingTest ->
                        NavEntry(screen) {
                            UpdateHearingTestRoute(airPodsViewModel)
                        }

                    Screen.VersionInfo ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            VersionScreen(airPodsViewModel)
                        }

                    Screen.HearingProtection ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            HearingProtectionScreen(
                                viewModel = airPodsViewModel,
                                navigateToPurchase = ::navigateToPurchase
                            )
                        }

                    Screen.Purchase ->
                        NavEntry(screen) {
                            val vm: PurchaseViewModel = viewModel()
                            PurchaseScreen(vm, backStack)
                        }

                    Screen.Equalizer ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            EqualizerRoute(airPodsViewModel)
                        }

                    is Screen.LongPress ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            LongPress(
                                viewModel = airPodsViewModel,
                                name = screen.bud,
                                navigateToPurchase = ::navigateToPurchase
                            )
                        }

                    is Screen.CallControl ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            CallControlScreen(
                                viewModel = airPodsViewModel,
                                action = screen.action,
                                onCallControlValueChanged = { flipped ->
                                    airPodsViewModel.setControlCommandValue(
                                        AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
                                        if (flipped) byteArrayOf(0x00, 0x02) else byteArrayOf(
                                            0x00,
                                            0x03
                                        )
                                    )
                                }
                            )
                        }

                    is Screen.MicrophoneSettings ->
                        NavEntry(screen) {
                            if (!airPodsViewModel.isReady) LoadingScreen()
                            MicrophoneSettingsRoute(viewModel = airPodsViewModel)
                        }

                    is Screen.ReleaseNotes ->
                        NavEntry(screen) {
                            ReleaseNotesScreen(
                                updates = updates,
                                releaseNotesShown = {
                                    if (showReleaseNotes) {
                                        navigate(Screen.AirPodsSettings)
                                        backStack.remove(screen)
                                        updatesShown()
                                    } else {
                                        backStack.removeAt(backStack.lastIndex)
                                    }
                                }
                            )
                        }
                }
            },
            transitionSpec = {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 4 }
            },
            popTransitionSpec = {
                slideInHorizontally { -it / 4 } togetherWith slideOutHorizontally { it }
            },
            predictivePopTransitionSpec = { swipeEdge ->
                if (m3eEnabled) {
                    val enterOffset: (Int) -> Int =
                        if (swipeEdge == EDGE_LEFT) {
                            { -it / 6 }
                        } else {
                            { it / 6 }
                        }

                    val exitOffset: (Int) -> Int =
                        if (swipeEdge == EDGE_LEFT) {
                            { it / 8 }
                        } else {
                            { -it / 8 }
                        }

                    fadeIn(
                        animationSpec = tween(250)
                    ) +
                        slideInHorizontally(
                            initialOffsetX = enterOffset,
                            animationSpec = tween(250)
                        ) togetherWith
                        fadeOut(
                            targetAlpha = 0.75f,
                            animationSpec = tween(250)
                        ) +
                        scaleOut(
                            targetScale = 0.85f,
                            animationSpec = tween(250)
                        ) +
                        slideOutHorizontally(
                            targetOffsetX = exitOffset,
                            animationSpec = tween(250)
                        )
                } else {
                    slideInHorizontally { -it / 4 } togetherWith slideOutHorizontally { it }
                }
            },
        )
    }
}
