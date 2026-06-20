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


// this is absolutely unnecessary, why did I make this. a simple toggle would've sufficed

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.screens

import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.services.ServiceManager
import me.kavishdevar.librepods.utils.HeadTracking
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs

@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun HeadTrackingScreen(viewModel: AirPodsViewModel, navigateToPurchase: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    DisposableEffect(Unit) {
        viewModel.startHeadTracking()
        onDispose {
            viewModel.stopHeadTracking()
        }
    }
    val isDarkTheme = isSystemInDarkTheme()
    if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDarkTheme) Color.White else Color.Black

    val backdrop = rememberLayerBackdrop()

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    var gestureText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    var lastClickTime by remember { mutableLongStateOf(0L) }
    var shouldExplode by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        Column (
            modifier = Modifier
                .fillMaxWidth()
                .layerBackdrop(backdrop)
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp)
        ) {

            if (!state.isPremium) {
                StyledButton(
                    onClick = navigateToPurchase,
                    backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.fillMaxWidth(),
                    maxScale = 0.05f,
                    surfaceColor = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        stringResource(R.string.unlock_advanced_features),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            StyledToggle(
                label = "Head Gestures",
                checked = state.headGesturesEnabled,
                onCheckedChange = { viewModel.setHeadGesturesEnabled(it) },
                enabled = state.isPremium || state.headGesturesEnabled,
                description = stringResource(R.string.head_gestures_details),
                header = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Velocity",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 8.dp)
            )
            Plot()

            Spacer(modifier = Modifier.height(16.dp))

            LaunchedEffect(gestureText) {
                if (gestureText.isNotEmpty()) {
                    lastClickTime = System.currentTimeMillis()
                    delay(3000)
                    if (System.currentTimeMillis() - lastClickTime >= 3000) {
                        shouldExplode = true
                    }
                }
            }
        }
        val gestureTextValue = stringResource(R.string.shake_your_head_or_nod)
        StyledButton(
            onClick = {
                gestureText = gestureTextValue
                coroutineScope.launch {
                    val accepted = ServiceManager.getService()?.testHeadGestures() ?: false
                    gestureText = if (accepted) "\"Yes\" gesture detected." else "\"No\" gesture detected."
                }
            },
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            maxScale = 0.05f
        ) {
            Text(
                "Test Head Gestures",
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                ),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
        ) {
            AnimatedContent(
                targetState = gestureText,
                transitionSpec = {
                    (fadeIn(
                        animationSpec = tween(300)
                    ) + slideInVertically(
                        initialOffsetY = { 40 },
                        animationSpec = tween(300)
                    )).togetherWith(fadeOut(animationSpec = tween(150)))
                }
            ) { text ->
                if (shouldExplode) {
                    LaunchedEffect(Unit) {
                        CoroutineScope(coroutineScope.coroutineContext).launch {
                            delay(750)
                            gestureText = ""
                        }
                    }
                    Text(
                        text = text,
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            textAlign = TextAlign.Center
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                } else {
                    Text(
                        text = text,
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = textColor,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

@Composable
private fun Plot() {
    val acceleration by HeadTracking.acceleration.collectAsState()
    val maxPoints = 100
    val points = remember { mutableStateListOf<Pair<Float, Float>>() }
    val darkTheme = isSystemInDarkTheme()

    var maxAbs by remember { mutableFloatStateOf(1000f) }

    LaunchedEffect(acceleration) {
        points.add(Pair(acceleration.horizontal, acceleration.vertical))
        if (points.size > maxPoints) {
            points.removeAt(0)
        }

        val currentMax = points.maxOf { maxOf(abs(it.first), abs(it.second)) }
        maxAbs = maxOf(currentMax * 1.2f, 1000f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(28.dp)
    ) {
        val horizontalColor = MaterialTheme.colorScheme.primary
        val verticalColor = MaterialTheme.colorScheme.onPrimary

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val width = size.width
                val height = size.height
                val xScale = width / maxPoints
                val yScale = (height - 40.dp.toPx()) / (maxAbs * 2)
                val zeroY = height / 2

                val gridColor = if (darkTheme) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)

                for (i in 0..maxPoints step 10) {
                    val x = i * xScale
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, height),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val gridStep = maxAbs / 4
                for (value in (-maxAbs.toInt()..maxAbs.toInt()) step gridStep.toInt()) {
                    val y = zeroY - value * yScale
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                drawLine(
                    color = if (darkTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f),
                    start = Offset(0f, zeroY),
                    end = Offset(width, zeroY),
                    strokeWidth = 1.5f.dp.toPx()
                )

                if (points.size > 1) {
                    for (i in 0 until points.size - 1) {
                        val x1 = i * xScale
                        val x2 = (i + 1) * xScale

                        drawLine(
                            color = horizontalColor,
                            start = Offset(x1, zeroY - points[i].first * yScale),
                            end = Offset(x2, zeroY - points[i + 1].first * yScale),
                            strokeWidth = 2.dp.toPx()
                        )

                        drawLine(
                            color = verticalColor,
                            start = Offset(x1, zeroY - points[i].second * yScale),
                            end = Offset(x2, zeroY - points[i + 1].second * yScale),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.RIGHT
                    }

                    drawText("${maxAbs.toInt()}", 30.dp.toPx(), 20.dp.toPx(), paint)
                    drawText("0", 30.dp.toPx(), height/2, paint)
                    drawText("-${maxAbs.toInt()}", 30.dp.toPx(), height - 10.dp.toPx(), paint)
                }

                val legendY = 15.dp.toPx()
                val textOffsetY = legendY + 5.dp.toPx() / 2

                drawCircle(horizontalColor, 5.dp.toPx(), Offset(width - 150.dp.toPx(), legendY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.LEFT
                    }
                    drawText("Horizontal", width - 140.dp.toPx(), textOffsetY, paint)
                }

                drawCircle(verticalColor, 5.dp.toPx(), Offset(width - 70.dp.toPx(), legendY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.LEFT
                    }
                    drawText("Vertical", width - 60.dp.toPx(), textOffsetY, paint)
                }
            }
        }
    }
}
