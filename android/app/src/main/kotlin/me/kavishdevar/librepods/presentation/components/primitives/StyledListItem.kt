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

package me.kavishdevar.librepods.presentation.components.primitives

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.icons.richText
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.RadioButton as MiuixRadioButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import me.kavishdevar.librepods.presentation.theme.sectionHeader

@Composable
fun StyledListItem(
    modifier: Modifier = Modifier,
    title: String? = null,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    height: Dp = 58.dp,
    enabled: Boolean = true,
    orientation: StyledListItemOrientation = StyledListItemOrientation.Horizontal,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    index: Int = 0,
    count: Int = 1,
    colors: ListItemColors = if (onClick == null) {
        ListItemDefaults.segmentedColors().run {
            copy(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = contentColor,
                disabledSupportingContentColor = supportingContentColor,
                disabledTrailingContentColor = trailingContentColor
            )
        }
    } else ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
) {
    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    Column {
        title?.let {
            Box(
                modifier = Modifier
                    .background(if (m3eEnabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = if (m3eEnabled) 8.dp else 4.dp)
            ) {
                Text(
                    text = it,
                    color = if (m3eEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
                    style = MaterialTheme.typography.labelSmallEmphasized
                )
            }
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(
                    when (LocalDesignSystem.current) {
                        DesignSystem.Material -> Color.Transparent
                        // Under Miuix, Material's surface role carries the Miuix card fill and
                        // surfaceContainer the page canvas - the two are crossed in
                        // LibrePodsTheme, so a card painted with surfaceContainer would vanish
                        // into the page.
                        DesignSystem.Miuix -> MaterialTheme.colorScheme.surface
                        DesignSystem.Apple -> MaterialTheme.colorScheme.surfaceContainer
                    },
                    RoundedCornerShape(if (LocalDesignSystem.current == DesignSystem.Apple) 28.dp else 16.dp)
                )
                .clip(RoundedCornerShape(if (LocalDesignSystem.current == DesignSystem.Apple) 28.dp else 16.dp))
        ) {
            StyledListItemContent(
                onClick = onClick,
                content = content,
                supportingContent = supportingContent,
                height = height,
                enabled = enabled,
                index = index,
                count = count,
                orientation = orientation,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors
            )
        }
    }
}

@Composable
fun StyledListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    title: String? = null,
    contentText: String,
    height: Dp = 58.dp,
    enabled: Boolean = true,
    orientation: StyledListItemOrientation = StyledListItemOrientation.Horizontal,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    index: Int = 0,
    count: Int = 1,
    colors: ListItemColors = if (onClick == null) {
        ListItemDefaults.segmentedColors().run {
            copy(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = contentColor,
                disabledSupportingContentColor = supportingContentColor,
                disabledTrailingContentColor = trailingContentColor
            )
        }
    } else ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
) {
    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    Column {
        title?.let {
            Box(
                modifier = Modifier
                    .background(if (m3eEnabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = if (m3eEnabled) 8.dp else 4.dp)
            ) {
                Text(
                    text = it,
                    color = if (m3eEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
                    style = MaterialTheme.typography.labelSmallEmphasized
                )
            }
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(
                    when (LocalDesignSystem.current) {
                        DesignSystem.Material -> Color.Transparent
                        // Under Miuix, Material's surface role carries the Miuix card fill and
                        // surfaceContainer the page canvas - the two are crossed in
                        // LibrePodsTheme, so a card painted with surfaceContainer would vanish
                        // into the page.
                        DesignSystem.Miuix -> MaterialTheme.colorScheme.surface
                        DesignSystem.Apple -> MaterialTheme.colorScheme.surfaceContainer
                    },
                    RoundedCornerShape(if (LocalDesignSystem.current == DesignSystem.Apple) 28.dp else 16.dp)
                )
                .clip(RoundedCornerShape(if (LocalDesignSystem.current == DesignSystem.Apple) 28.dp else 16.dp))
        ) {
            StyledListItemContent(
                onClick = onClick,
                contentText = contentText,
                content = {
                    when (LocalDesignSystem.current) {
                        DesignSystem.Apple -> {
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DesignSystem.Material, DesignSystem.Miuix -> {
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.labelMediumEmphasized,
                            )
                        }
                    }
                },
                supportingContent = null,
                height = height,
                enabled = enabled,
                index = index,
                count = count,
                orientation = orientation,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors
            )
        }
    }
}

@Composable
fun StyledListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    title: String? = null,
    contentText: String,
    supportingContent: @Composable () -> Unit,
    height: Dp = 58.dp,
    enabled: Boolean = true,
    orientation: StyledListItemOrientation = StyledListItemOrientation.Horizontal,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    index: Int = 0,
    count: Int = 1,
    colors: ListItemColors = if (onClick == null) {
        ListItemDefaults.segmentedColors().run {
            copy(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = contentColor,
                disabledSupportingContentColor = supportingContentColor,
                disabledTrailingContentColor = trailingContentColor
            )
        }
    } else ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
) {
    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    Column {
        title?.let {
            Box(
                modifier = Modifier
                    .background(if (m3eEnabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = if (m3eEnabled) 8.dp else 4.dp)
            ) {
                Text(
                    text = it,
                    color = if (m3eEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
                    style = MaterialTheme.typography.labelSmallEmphasized
                )
            }
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(
                    when (LocalDesignSystem.current) {
                        DesignSystem.Material -> Color.Transparent
                        // Under Miuix, Material's surface role carries the Miuix card fill and
                        // surfaceContainer the page canvas - the two are crossed in
                        // LibrePodsTheme, so a card painted with surfaceContainer would vanish
                        // into the page.
                        DesignSystem.Miuix -> MaterialTheme.colorScheme.surface
                        DesignSystem.Apple -> MaterialTheme.colorScheme.surfaceContainer
                    },
                    RoundedCornerShape(if (LocalDesignSystem.current == DesignSystem.Apple) 28.dp else 16.dp)
                )
                .clip(RoundedCornerShape(if (LocalDesignSystem.current == DesignSystem.Apple) 28.dp else 16.dp))
        ) {
            StyledListItemContent(
                onClick = onClick,
                contentText = contentText,
                content = {
                    when (LocalDesignSystem.current) {
                        DesignSystem.Apple -> {
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DesignSystem.Material, DesignSystem.Miuix -> {
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.labelMediumEmphasized,
                            )
                        }
                    }
                },
                supportingContent = supportingContent,
                height = height,
                enabled = enabled,
                index = index,
                count = count,
                orientation = orientation,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors
            )
        }
    }
}

@Composable
fun StyledListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    title: String? = null,
    contentText: String,
    supportingText: String? = null,
    height: Dp = 58.dp,
    enabled: Boolean = true,
    orientation: StyledListItemOrientation = StyledListItemOrientation.Horizontal,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    index: Int = 0,
    count: Int = 1,
    colors: ListItemColors = if (onClick == null) {
        ListItemDefaults.segmentedColors().run {
            copy(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = contentColor,
                disabledSupportingContentColor = supportingContentColor,
                disabledTrailingContentColor = trailingContentColor
            )
        }
    } else ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
) {
    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    Column {
        title?.let {
            Box(
                modifier = Modifier
                    .background(if (m3eEnabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = if (m3eEnabled) 8.dp else 4.dp)
            ) {
                Text(
                    text = it,
                    color = if (m3eEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
                    style = MaterialTheme.typography.labelSmallEmphasized
                )
            }
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(
                    when (LocalDesignSystem.current) {
                        DesignSystem.Material -> Color.Transparent
                        // Under Miuix, Material's surface role carries the Miuix card fill and
                        // surfaceContainer the page canvas - the two are crossed in
                        // LibrePodsTheme, so a card painted with surfaceContainer would vanish
                        // into the page.
                        DesignSystem.Miuix -> MaterialTheme.colorScheme.surface
                        DesignSystem.Apple -> MaterialTheme.colorScheme.surfaceContainer
                    },
                    RoundedCornerShape(if (LocalDesignSystem.current == DesignSystem.Apple) 28.dp else 16.dp)
                )
                .clip(RoundedCornerShape(if (LocalDesignSystem.current == DesignSystem.Apple) 28.dp else 16.dp))
        ) {
            StyledListItemContent(
                onClick = onClick,
                contentText = contentText,
                content = {
                    when (LocalDesignSystem.current) {
                        DesignSystem.Apple -> {
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DesignSystem.Material, DesignSystem.Miuix -> {
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.labelMediumEmphasized,
                            )
                        }
                    }
                },
                supportingText = supportingText,
                supportingContent = if (supportingText != null && (LocalDesignSystem.current == DesignSystem.Material || orientation == StyledListItemOrientation.Horizontal)) {
                    @Composable {
                        Text(
                            text = supportingText,
                            style = if (LocalDesignSystem.current == DesignSystem.Apple && orientation == StyledListItemOrientation.Horizontal) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.7f),
                        )
                    }
                } else null,
                height = height,
                enabled = enabled,
                index = index,
                count = count,
                orientation = orientation,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors
            )
        }
        if (supportingText != null && LocalDesignSystem.current == DesignSystem.Apple && orientation == StyledListItemOrientation.Vertical) {
            Box(
                modifier = Modifier
                    .background(if (m3eEnabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = if (m3eEnabled) 8.dp else 4.dp)
            ) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.7f),
                )
            }
        }
    }
}

@Composable
fun StyledListScope.StyledListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentText: String,
    enabled: Boolean = onClick != null,
    orientation: StyledListItemOrientation = StyledListItemOrientation.Horizontal,
    selected: Boolean? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = if (onClick == null) {
        ListItemDefaults.segmentedColors().run {
            copy(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = contentColor,
                disabledSupportingContentColor = supportingContentColor,
                disabledTrailingContentColor = trailingContentColor
            )
        }
    } else ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
) {
    item { index, count ->
        StyledListItemContent(
            onClick = onClick,
            contentText = contentText,
            content = {
                when (LocalDesignSystem.current) {
                    DesignSystem.Apple -> {
                        Text(
                            text = contentText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    DesignSystem.Material, DesignSystem.Miuix -> {
                        Text(
                            text = contentText,
                            style = MaterialTheme.typography.labelMediumEmphasized,
                        )
                    }
                }
            },
            enabled = enabled,
            index = index,
            count = count,
            orientation = orientation,
            modifier = modifier,
            selected = selected,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            colors = colors
        )
    }
}

@Composable
fun StyledListScope.StyledListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = onClick != null,
    orientation: StyledListItemOrientation = StyledListItemOrientation.Horizontal,
    selected: Boolean? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = if (onClick == null) {
        ListItemDefaults.segmentedColors().run {
            copy(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = contentColor,
                disabledSupportingContentColor = supportingContentColor,
                disabledTrailingContentColor = trailingContentColor
            )
        }
    } else ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
) {
    item { index, count ->
        StyledListItemContent(
            onClick = onClick,
            content = content,
            supportingContent = supportingContent,
            enabled = enabled,
            index = index,
            count = count,
            orientation = orientation,
            modifier = modifier,
            selected = selected,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            colors = colors
        )
    }
}

@Composable
fun StyledListScope.StyledListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentText: String,
    supportingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = onClick != null,
    orientation: StyledListItemOrientation = StyledListItemOrientation.Horizontal,
    selected: Boolean? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = if (onClick == null) {
        ListItemDefaults.segmentedColors().run {
            copy(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = contentColor,
                disabledSupportingContentColor = supportingContentColor,
                disabledTrailingContentColor = trailingContentColor
            )
        }
    } else ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
) {
    item { index, count ->
        StyledListItemContent(
            onClick = onClick,
            contentText = contentText,
            content = {
                when (LocalDesignSystem.current) {
                    DesignSystem.Apple -> {
                        Text(
                            text = contentText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    DesignSystem.Material, DesignSystem.Miuix -> {
                        Text(
                            text = contentText,
                            style = MaterialTheme.typography.labelMediumEmphasized,
                        )
                    }
                }
            },
            supportingContent = supportingContent,
            enabled = enabled,
            index = index,
            count = count,
            orientation = orientation,
            modifier = modifier,
            selected = selected,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            colors = colors
        )
    }
}

@Composable
fun StyledListScope.StyledListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentText: String,
    supportingText: String? = null,
    enabled: Boolean = onClick != null,
    orientation: StyledListItemOrientation = StyledListItemOrientation.Horizontal,
    selected: Boolean? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = if (onClick == null) {
        ListItemDefaults.segmentedColors().run {
            copy(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = contentColor,
                disabledSupportingContentColor = supportingContentColor,
                disabledTrailingContentColor = trailingContentColor
            )
        }
    } else ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
) {
    item { index, count ->
        StyledListItemContent(
            onClick = onClick,
            contentText = contentText,
            content = {
                when (LocalDesignSystem.current) {
                    DesignSystem.Apple -> {
                        Text(
                            text = contentText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    DesignSystem.Material, DesignSystem.Miuix -> {
                        Text(
                            text = contentText,
                            style = MaterialTheme.typography.labelMediumEmphasized,
                        )
                    }
                }
            },
            supportingText = supportingText,
            supportingContent = if (supportingText != null) {
                   @Composable {
                        Text(
                            text = supportingText,
                            style = if (LocalDesignSystem.current == DesignSystem.Apple && orientation == StyledListItemOrientation.Horizontal) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                            color = if (selected == true && LocalDesignSystem.current == DesignSystem.Material) MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f) else MaterialTheme.colorScheme.onSurface.copy(0.7f), // TODO: move to color scheme
                        )
                    }
                } else null,
            enabled = enabled,
            index = index,
            count = count,
            orientation = orientation,
            modifier = modifier,
            selected = selected,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            colors = colors
        )
    }
}

enum class StyledListItemOrientation{
    Horizontal,
    Vertical
}

@Composable
private fun StyledListItemContent(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    // Plain-text forms of content / supportingContent. Miuix's own row components take
    // strings rather than slots, and they carry MIUI's metrics — row height, insets, the
    // chevron — that a hand-rolled row does not match, so the text is passed alongside the
    // composables and the Miuix branch below prefers it when the caller had it to give.
    contentText: String? = null,
    supportingText: String? = null,
    height: Dp = 58.dp,
    enabled: Boolean = true,
    index: Int,
    count: Int,
    orientation: StyledListItemOrientation = StyledListItemOrientation.Horizontal,
    selected: Boolean? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = if (onClick == null) {
        ListItemDefaults.segmentedColors().run {
            copy(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = contentColor,
                disabledSupportingContentColor = supportingContentColor,
                disabledTrailingContentColor = trailingContentColor
            )
        }
    } else ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    when (LocalDesignSystem.current) {
        DesignSystem.Miuix -> {
            val endActions: @Composable RowScope.() -> Unit = {
                when {
                    trailingContent != null -> trailingContent()
                    selected != null -> MiuixRadioButton(
                        selected = selected,
                        onClick = onClick,
                        enabled = enabled
                    )
                }
            }
            when {
                // A row that navigates gets ArrowPreference, so the chevron is drawn by Miuix
                // to MIUI's spec. Rows that carry a control of their own, or none at all, get
                // the plain component: a chevron there would promise a screen that isn't there.
                contentText != null && onClick != null && selected == null && trailingContent == null ->
                    ArrowPreference(
                        modifier = modifier,
                        title = contentText,
                        summary = supportingText,
                        enabled = enabled,
                        onClick = onClick,
                        // holdDownState stays false: it is a permanently pressed background,
                        // meant for a row that keeps its press while its dialog is open.
                        startAction = leadingContent
                    )

                contentText != null -> BasicComponent(
                    modifier = modifier,
                    title = contentText,
                    summary = supportingText,
                    enabled = enabled,
                    onClick = onClick,
                    startAction = leadingContent,
                    endActions = endActions
                )

                // Slot-only callers (the debug packet list, the heart-rate readout) have no
                // text to hand over, so the row is laid out here with Miuix's own insets.
                else -> Row(
                    modifier = modifier
                        .fillMaxWidth()
                        .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    leadingContent?.invoke()
                    Column(modifier = Modifier.weight(1f)) {
                        content()
                        supportingContent?.invoke()
                    }
                    endActions()
                }
            }
        }
        DesignSystem.Apple -> {
            val surfaceColor = colors.containerColor
            val pressedColor = MaterialTheme.colorScheme.surfaceContainerLow

            var backgroundColor by remember(surfaceColor) { mutableStateOf(surfaceColor) }
            val animatedBackgroundColor by animateColorAsState(targetValue = backgroundColor, animationSpec = tween(durationMillis = 500))

            val trailingContentDefault: @Composable () -> Unit = {
                if (trailingContent == null) {
                    if (onClick != null) {
                        if (selected != null) {
                            val floatAnimateState by animateFloatAsState(
                                targetValue = if (selected) 1f else 0f,
                                animationSpec = tween(durationMillis = 300)
                            )

                            val color = MaterialTheme.colorScheme.primary.copy(alpha = floatAnimateState)

                            val richText = richText(
                                source = "\\icon{Check,${String.format("#%08X", color.toArgb())}}",
                            )
                            Text(
                                text = richText.text,
                                inlineContent = richText.inlineContent,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = if (supportingContent != null) 6.dp else 0.dp)
                            )
                        } else {
                            val color = MaterialTheme.colorScheme.onSurface.copy(0.7f)

                            val richText = richText(
                                source = "\\icon{ChevronRight,#${String.format("%08X", color.toArgb())}}",
                            )
                            Text(
                                text = richText.text,
                                inlineContent = richText.inlineContent,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = if (supportingContent != null) 6.dp else 0.dp)
                            )
                        }
                    }
                } else {
                    trailingContent()
                }
            }

            Column (
                modifier = Modifier
                    .background(
                        animatedBackgroundColor,
                        when {
                            (index == 0 && count == 1) -> {
                                RoundedCornerShape(28.dp)
                            }

                            (index == 0) -> {
                                RoundedCornerShape(
                                    topStart = 28.dp,
                                    topEnd = 28.dp,
                                    bottomStart = 0.dp,
                                    bottomEnd = 0.dp
                                )
                            }

                            (index + 1 == count) -> {
                                RoundedCornerShape(
                                    topStart = 0.dp,
                                    topEnd = 0.dp,
                                    bottomStart = 28.dp,
                                    bottomEnd = 28.dp
                                )
                            }

                            else -> {
                                RectangleShape
                            }
                        }
                    )
                    .pointerInput(Unit) {
                        if (onClick != null) {
                            detectTapGestures(
                                onPress = {
                                    if (enabled) {
                                        backgroundColor = pressedColor
                                        tryAwaitRelease()
                                        backgroundColor = surfaceColor
                                    }
                                },
                                onTap = {
                                    if (enabled) {
                                        scope.launch {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.ContextClick
                                            )
                                        }
                                        onClick.invoke()
                                    }
                                }
                            )
                        }
                    }
                    .heightIn(min = height)
                    .padding(horizontal = 16.dp)
            ) {
                val density = LocalDensity.current

                val leadingContentWidth = remember { mutableStateOf(0.dp) }
                val trailingContentWidth = remember { mutableStateOf(0.dp) }

                Row(
                    modifier = Modifier
                        .heightIn(min = height)
                        .padding(vertical = if (orientation == StyledListItemOrientation.Vertical) 12.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingContent != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                with(density) {
                                    leadingContentWidth.value = coordinates.size.width.toDp()
                                }
                            }
                        ) {
                            leadingContent()
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f)
                    ) {
                        content()
                        supportingContent?.let {
                            if (orientation == StyledListItemOrientation.Vertical) {
                                Spacer(modifier = Modifier.height(8.dp))
                                it()
                            }
                        }
                    }

                    supportingContent?.let {
                        if (orientation == StyledListItemOrientation.Horizontal) {
                            it()
                        }
                    }

                    Box(
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            with (density) {
                                if (trailingContent != null) trailingContentWidth.value = coordinates.size.width.toDp()
                            }
                        }
                    ) {
                        trailingContentDefault()
                    }
                }
                if (index+1 != count) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier.padding(start = leadingContentWidth.value, end = trailingContentWidth.value)
                    )
                }
            }
        }

        DesignSystem.Material -> {
            val defaultShape = when {
                count == 1 -> RoundedCornerShape(24.dp)

                index == 0 -> RoundedCornerShape(
                    topStart = 24.dp,
                    topEnd = 24.dp,
                    bottomStart = 8.dp,
                    bottomEnd = 8.dp
                )

                index == count - 1 -> RoundedCornerShape(
                    topStart = 8.dp,
                    topEnd = 8.dp,
                    bottomStart = 24.dp,
                    bottomEnd = 24.dp
                )

                else -> RoundedCornerShape(8.dp)
            }
            Column {
                SegmentedListItem(
                    modifier = modifier.heightIn(min = 64.dp),
                    shapes = ListItemDefaults.shapes().copy(
                        shape = defaultShape,
                        pressedShape = RoundedCornerShape(24.dp),
                        selectedShape = RoundedCornerShape(24.dp),
                        hoveredShape = RoundedCornerShape(24.dp),
                    ),
                    onClick = onClick ?: {},
                    leadingContent = leadingContent,
                    trailingContent = {
                        if (trailingContent == null) {
                            if (onClick != null) {
                                if (selected == true) {
                                    Icon(
                                        imageVector = LocalIcons.current.Check,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(24.dp)
                                    )
                                } else if (selected == null) {
                                    Icon(
                                        imageVector = LocalIcons.current.ChevronRight,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .padding(start = if (supportingContent != null && orientation == StyledListItemOrientation.Horizontal) 6.dp else 0.dp)
                                    )
                                }
                            }
                        } else {
                            trailingContent()
                        }
                    },
                    supportingContent = supportingContent,
                    content = content,
                    verticalAlignment = Alignment.CenterVertically,
                    colors = colors,
                    enabled = onClick != null && enabled,
                    selected = selected ?: false,
                )
                if (index+1 != count) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}
