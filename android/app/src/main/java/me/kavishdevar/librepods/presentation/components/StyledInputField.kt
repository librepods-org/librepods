package me.kavishdevar.librepods.presentation.components

import android.R.attr.singleLine
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R


@Composable
fun StyledInputField(
    inputState: TextFieldState,
    focusRequester: FocusRequester,
    placeholder: String = "",
    singleLine: Boolean = true
){
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val minHeight = if (singleLine) 58.dp else 120.dp
    val verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
    val hasText = inputState.text.isNotEmpty()
    val density = LocalDensity.current
    val spacerHeight by animateDpAsState(
        targetValue = if (hasText) with(density) { 32.sp.toDp() } else 0.dp,
        label = "labelSpacer"
    )

    val transition = updateTransition(hasText, label = "floating")
    val yOffset by transition.animateDp(label = "y") {
        if (it) with (density) { (-48).sp.toDp() } else 0.dp
    }

    Spacer(modifier = Modifier.height(spacerHeight))

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = verticalAlignment,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(
                    backgroundColor,
                    RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusRequester.requestFocus()
                    }
                }
        ) {
            BasicTextField(
                state = inputState,
                lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    color = textColor,
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                cursorBrush = SolidColor(textColor),
                decorator = { innerTextField ->
                    Row(
                        modifier = Modifier.padding(top = if (singleLine) 0.dp else 16.dp),
                        verticalAlignment = verticalAlignment,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f),
                                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
                            ) {
                                Text(
                                    text = placeholder,
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Light,
                                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                                        color = textColor.copy(alpha = 0.8f)
                                    ),
                                    modifier = Modifier
                                        .offset(y = yOffset)
                                )

                                innerTextField()
                            }
                        }
                        if (singleLine && !inputState.text.isEmpty()) {
                            IconButton(
                                onClick = {
                                    inputState.clearText()
                                }
                            ) {
                                Text(
                                    text = "􀁡",
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                                        color = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color.Black.copy(
                                            alpha = 0.6f
                                        )
                                    ),
                                )
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp)
                    .focusRequester(focusRequester)
            )
        }
    }
}
