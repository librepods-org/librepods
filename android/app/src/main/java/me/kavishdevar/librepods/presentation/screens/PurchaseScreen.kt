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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.viewmodel.PurchaseViewModel

@Composable
fun PurchaseScreen(
    viewModel: PurchaseViewModel = viewModel(),
    navController: NavController
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val state by viewModel.uiState.collectAsState()

    val backdrop = rememberLayerBackdrop()

    StyledScaffold(
        title = stringResource(R.string.unlock_advanced_features)
    ) { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .hazeSource(state = hazeState)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            val isDarkTheme = isSystemInDarkTheme()
            val backgroundColor = if (isDarkTheme) Color(0xFF000000) else Color(0xFFF2F2F7)
            val cardBackgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
            val textColor = if (isDarkTheme) Color.White else Color.Black
            LaunchedEffect(state.isPremium) {
                if (state.isPremium) {
                    navController.popBackStack()
                }
            }
            if (!state.isPremium) {
                Box(
                    modifier = Modifier
                        .background(backgroundColor)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.free_features),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = 0.6f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBackgroundColor, RoundedCornerShape(28.dp))
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ear_detection),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.ear_detection_description),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.battery),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.battery_description),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.noise_control),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.noise_control_description),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                    if (BuildConfig.FLAVOR == "xposed") {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = Color(0x40888888),
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.hearing_aid) + " (" + stringResource(
                                    R.string.requires_xposed
                                ) + ")",
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal,
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                    color = textColor
                                )
                            )
                            Text(
                                text = stringResource(R.string.hearing_aid_description).split("\n\n")[0],
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = textColor.copy(0.6f),
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .background(backgroundColor)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.advanced_features),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = 0.6f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBackgroundColor, RoundedCornerShape(28.dp))
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.conversational_awareness),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.conversational_awareness_description),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.digital_assistant_on_long_press),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.digital_assistant_on_long_press_description),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.head_gestures),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.head_gestures_details),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.advanced_device_settings),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.advanced_device_settings_description),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.automatic_connection),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.automatic_connection_description),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.customizations),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.customizations_description),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.support_the_development),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = textColor
                            )
                        )
                        Text(
                            text = stringResource(R.string.support_development_description),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = textColor.copy(0.6f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.feature_availability_disclaimer),
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    ),
                )


                Spacer(modifier = Modifier.height(24.dp))

                StyledButton(
                    onClick = {
                        viewModel.purchase(context)
                    },
                    backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.fillMaxWidth(),
                    maxScale = 0.05f,
                    surfaceColor = if (isSystemInDarkTheme()) Color(0xFF0091FF)
                    else  Color(0xFF0088FF) // if (isSystemInDarkTheme()) Color(0xFF916100) else Color(0xFFE59900)
                ) {
                    Text(
                        stringResource(R.string.buy_price, state.price),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = Color.White
                        ),
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
                    isInteractive = false
                ) {
                    Text(
                        stringResource(R.string.restore_purchases),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = textColor
                        ),
                    )
                }
            }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}
