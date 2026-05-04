package me.kavishdevar.librepods.presentation.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.utils.XposedState

@Composable
fun DeviceInfoCard() {
    val isDarkTheme = isSystemInDarkTheme()

    val textColor = if (isDarkTheme) Color.White else Color.Black
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    val rowHeight = remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Column (
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .background(if (isDarkTheme) Color.Black else Color(0xFFF2F2F7))
                .padding(start = 16.dp, top = 24.dp, end = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.device_info), style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                )
            )
        }
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .fillMaxWidth()
                .background(backgroundColor, RoundedCornerShape(28.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .onGloballyPositioned { coordinates ->
                        rowHeight.value = with(density) { coordinates.size.height.toDp() }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.manufacturer), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = Build.MANUFACTURER, style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.model_number), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = Build.MODEL, style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.build_id), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = Build.DISPLAY, style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.version), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = Build.ID + " (${Build.VERSION.SDK_INT_FULL})",
                    style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.xposed_available), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = if (XposedState.isAvailable) stringResource(R.string.yes) else stringResource(R.string.no), style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.app_enabled_in_xposed), style = TextStyle(
                        fontSize = 16.sp,
                        color = textColor,
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                Text(
                    text = if (XposedState.bluetoothScopeEnabled) stringResource(R.string.yes) else stringResource(R.string.no), style = TextStyle(
                        fontSize = 16.sp,
                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                            alpha = 0.8f
                        ),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }
        }
    }
}
