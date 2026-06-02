package me.kavishdevar.librepods.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyledBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: LayerBackdrop,
    content: @Composable (innerBackdrop: LayerBackdrop, progress: Float) -> Unit
) {
    if (!visible) return

    val isDarkTheme = isSystemInDarkTheme()
    val sheetState = rememberModalBottomSheetState(false) // move this to parent composable

    val isExpanded =  sheetState.targetValue == SheetValue.Expanded

    val progress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        label = "sheetProgress"
    )

    val animatedCorner = lerp(48.dp, 42.dp, progress)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = { },
        shape = RoundedCornerShape(animatedCorner),
        scrimColor = Color.Transparent,
        modifier = Modifier.padding(4.dp)
    ) {
        val innerBackdrop = rememberLayerBackdrop()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(animatedCorner))
                .drawBackdrop(
                    backdrop = backdrop,
                    exportedBackdrop = innerBackdrop,
                    shape = { RoundedCornerShape(animatedCorner) },
                    effects = {
                        vibrancy()
                        blur(4f.dp.toPx())
                        lens(12f.dp.toPx(), 48f.dp.toPx(), true)
                    },
                    onDrawSurface = {
                        drawRect(
                            if (isDarkTheme) Color.DarkGray.copy(alpha = 0.3f) else Color(
                                0xFFE0E0E0
                            ).copy(alpha = 0.45f)
                        )
                    }
                )
                .padding(top = 24.dp)
                .padding(horizontal = 16.dp)
        ) {
            content(innerBackdrop, progress)
        }
    }
}
