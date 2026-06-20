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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.ListItemOrientation
import me.kavishdevar.librepods.presentation.components.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.navigation.Screen
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.PurchaseViewModel
import me.kavishdevar.librepods.utils.XposedState

@Composable
fun PurchaseScreen(
    viewModel: PurchaseViewModel = viewModel(),
    backStack: SnapshotStateList<Screen>
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val state by viewModel.uiState.collectAsState()

    val backdrop = rememberLayerBackdrop()

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .layerBackdrop(backdrop)
            .verticalScroll(scrollState)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        LaunchedEffect(state.isPremium) {
            if (state.isPremium) {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.lastIndex)
                }
            }
        }
        if (!state.isPremium) {
            StyledList(title = stringResource(R.string.free_features)) {
                StyledListItem(
                    name = stringResource(R.string.ear_detection),
                    description = stringResource(R.string.ear_detection_description),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )

                StyledListItem(
                    name = stringResource(R.string.battery),
                    description = stringResource(R.string.battery_description),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )

                StyledListItem(
                    name = stringResource(R.string.noise_control),
                    description = stringResource(R.string.noise_control_description),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )

                if (XposedState.isAvailable) {
                    StyledListItem(
                        name = "${stringResource(R.string.hearing_aid)} (${stringResource(R.string.requires_xposed)})",
                        description = stringResource(R.string.hearing_aid_description)
                            .substringBefore("\n\n"),
                        enabled = false,
                        orientation = ListItemOrientation.Vertical
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            StyledList(title = stringResource(R.string.advanced_features), description = stringResource(R.string.feature_availability_disclaimer)) {
                StyledListItem(
                    name = stringResource(R.string.conversational_awareness),
                    description = stringResource(R.string.conversational_awareness_description),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )

                StyledListItem(
                    name = stringResource(R.string.digital_assistant_on_long_press),
                    description = stringResource(R.string.digital_assistant_on_long_press_description),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )

                StyledListItem(
                    name = stringResource(R.string.head_gestures),
                    description = stringResource(R.string.head_gestures_details),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )

                StyledListItem(
                    name = stringResource(R.string.advanced_device_settings),
                    description = stringResource(R.string.advanced_device_settings_description),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )

                StyledListItem(
                    name = stringResource(R.string.automatic_connection),
                    description = stringResource(R.string.automatic_connection_description),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )

                StyledListItem(
                    name = stringResource(R.string.customizations),
                    description = stringResource(R.string.customizations_description),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )

                StyledListItem(
                    name = stringResource(R.string.support_the_development),
                    description = stringResource(R.string.support_development_description),
                    enabled = false,
                    orientation = ListItemOrientation.Vertical
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            StyledButton(
                onClick = {
                    viewModel.purchase(context)
                },
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier.fillMaxWidth(),
                maxScale = 0.05f,
                surfaceColor = MaterialTheme.colorScheme.primary,
                materialButtonStyle = MaterialButtonStyle.Filled
            ) {
                Text(
                    stringResource(R.string.buy_price, state.price),
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            StyledButton(
                onClick = {
                    viewModel.restorePurchases()
                },
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier.fillMaxWidth(),
                maxScale = 0.05f,
                isInteractive = false,
                materialButtonStyle = MaterialButtonStyle.Outlined
            ) {
                Text(
                    stringResource(R.string.restore_purchases),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
