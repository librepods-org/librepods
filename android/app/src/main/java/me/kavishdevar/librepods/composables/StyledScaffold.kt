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

package me.kavishdevar.librepods.composables

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import me.kavishdevar.librepods.R

@ExperimentalHazeMaterialsApi
@Composable
fun StyledScaffold(
    title: String,
    actionButtons: List<@Composable (backdrop: LayerBackdrop) -> Unit> = emptyList(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (spacerValue: Dp, hazeState: HazeState) -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val hazeState = rememberHazeState(blurEnabled = true)

    Scaffold(
        containerColor = if (isDarkTheme) Color(0xFF000000) else Color(0xFFF2F2F7),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier
            .then(if (!isDarkTheme) Modifier.shadow(elevation = 36.dp, shape = RoundedCornerShape(52.dp), ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(RoundedCornerShape(52.dp))
    ) { paddingValues ->
        val topPadding = paddingValues.calculateTopPadding()
        val bottomPadding = paddingValues.calculateBottomPadding()
        val startPadding = paddingValues.calculateLeftPadding(LocalLayoutDirection.current)
        val endPadding = paddingValues.calculateRightPadding(LocalLayoutDirection.current)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = startPadding, end = endPadding, bottom = bottomPadding)
        ) {
            val backdrop = rememberLayerBackdrop()
            Box(
                modifier = Modifier
                    .zIndex(2f)
                    .height(64.dp + topPadding)
                    .fillMaxWidth()
                    .layerBackdrop(backdrop)
                    .hazeEffect(state = hazeState) {
                        tints = listOf(HazeTint(color = if (isDarkTheme) Color.Black else Color.White))
                        progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
                    }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.height(topPadding + 12.dp))
                    Text(
                            text = title,
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDarkTheme) Color.White else Color.Black,
                                fontFamily = FontFamily(Font(R.font.sf_pro))
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                }
            }
            Row(
                modifier = Modifier
                    .zIndex(3f)
                    .padding(top = topPadding, end = 8.dp)
                    .align(Alignment.TopEnd)
            ) {
                actionButtons.forEach { actionButton ->
                    actionButton(backdrop)
                }
            }

            content(topPadding + 64.dp, hazeState)
        }
    }
}


@ExperimentalHazeMaterialsApi
@Composable
fun StyledScaffold(
    title: String,
    actionButtons: List<@Composable (backdrop: LayerBackdrop) -> Unit> = emptyList(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable () -> Unit
) {
    StyledScaffold(
        title = title,
        actionButtons = actionButtons,
        snackbarHostState = snackbarHostState,
    ) { _, _ ->
        content()
    }
}

@ExperimentalHazeMaterialsApi
@Composable
fun StyledScaffold(
    title: String,
    actionButtons: List<@Composable (backdrop: LayerBackdrop) -> Unit> = emptyList(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (spacerValue: Dp) -> Unit
) {
    StyledScaffold(
        title = title,
        actionButtons = actionButtons,
        snackbarHostState = snackbarHostState,
    ) { spacerValue, _ ->
        content(spacerValue)
    }
}
