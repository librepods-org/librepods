package me.kavishdevar.librepods.presentation.components.primitives

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField


@Composable
fun StyledInputField(
    inputState: TextFieldState,
    focusRequester: FocusRequester,
    placeholder: String = "",
    singleLine: Boolean = true,
    forceApple: Boolean = false,
    colors: ListItemColors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
) {
    if (LocalDesignSystem.current == DesignSystem.Miuix && !forceApple) {
        MiuixTextField(
            state = inputState,
            label = placeholder,
            // The Miuix label floats above the text by default; what is wanted here is a
            // placeholder, so it has to be told that explicitly.
            useLabelAsPlaceholder = true,
            lineLimits = if (singleLine) {
                TextFieldLineLimits.SingleLine
            } else {
                TextFieldLineLimits.Default
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        )
        return
    }



    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material && !forceApple

    if(m3eEnabled) {
        TextField(
            state = inputState,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                )
            },
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
    }
    else {
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
            if (it) with(density) { (-48).sp.toDp() } else 0.dp
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
                        colors.containerColor,
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
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
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
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
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
                                    Icon(
                                        imageVector = LocalIcons.current.CloseCircle,
                                        contentDescription = "Clear text",
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
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
}
