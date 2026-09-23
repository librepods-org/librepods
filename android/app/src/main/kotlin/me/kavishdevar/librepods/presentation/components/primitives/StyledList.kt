package me.kavishdevar.librepods.presentation.components.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.AndroidUiModes.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.Wallpapers.GREEN_DOMINATED_EXAMPLE
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import androidx.compose.foundation.layout.PaddingValues
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.SmallTitle as MiuixSmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import me.kavishdevar.librepods.presentation.theme.sectionHeader

@Composable
fun StyledList(
    modifier: Modifier = Modifier,
    scrollEnabled: Boolean = false,
    title: String? = null,
    description: String? = null,
    colors: ListItemColors = ListItemDefaults.segmentedColors().run {
        copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    },
    key: Any? = null,
    content: @Composable StyledListScope.() -> Unit
) {
    val scope = StyledListScope()
    key(key) {
        scope.content()
    }

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material

    if (LocalDesignSystem.current == DesignSystem.Miuix) {
        // MIUI groups are a SmallTitle over a single card, and the card is a squircle drawn
        // by Miuix rather than a RoundedCornerShape. The title inset is 16 instead of the
        // component default of 28: the secondary pages already pad their column by 12, and
        // 12 + 16 is what lines the title up with the card's own row content.
        Column(modifier = modifier) {
            title?.let {
                MiuixSmallTitle(
                    it,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            MiuixCard(insideMargin = PaddingValues(0.dp)) {
                scope.items.forEachIndexed { index, item ->
                    item(index, scope.items.size)
                }
            }
            description?.let {
                MiuixText(
                    text = it,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        return
    }

    Column (modifier = modifier) {
        title?.let {
            Box(
                modifier = Modifier
                    .background(if (m3eEnabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = if (m3eEnabled) 12.dp else 4.dp)
            ) {
                Text(
                    text = it,
                    color = if (m3eEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
                    style = MaterialTheme.typography.labelSmallEmphasized
                )
            }
        }
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (m3eEnabled) Color.Transparent else colors.containerColor, RoundedCornerShape(if (m3eEnabled) 24.dp else 28.dp))
                .clip(RoundedCornerShape(if (m3eEnabled) 24.dp else 28.dp))
                .then(if (scrollEnabled) Modifier.verticalScroll(scrollState) else Modifier)
        ) {
            if (m3eEnabled && description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(0.8f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            scope.items.forEachIndexed { index, item ->
                item(index, scope.items.size)
            }
            Spacer(modifier = Modifier.height(if(m3eEnabled) 4.dp else 0.dp))
        }
    }
    if (!m3eEnabled && description != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(0.6f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

class StyledListScope {
    internal val items =
        mutableListOf<@Composable (Int, Int) -> Unit>()

    fun item(
        content: @Composable (index: Int, count: Int) -> Unit
    ) {
        items += content
    }
}

@Preview(showBackground = true, wallpaper = GREEN_DOMINATED_EXAMPLE, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun StyledListDemo() {
    LibrePodsTheme(
        designSystem = DesignSystem.Apple,
        darkTheme = false
    ) {
        StyledScaffold(
            title = "StyledListTest",
            navigateBack = null
        ) { topPadding, bottomPadding ->
            Column (
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Spacer(modifier = Modifier.height(topPadding))
                StyledList(
                    title = "hello"
                ) {
                    for (i in 0..2) {
                        StyledListItem(
                            contentText = i.toString(),
                            onClick = {}
                        )
                    }
                    val checked = remember { mutableStateOf(false) }
                    StyledToggle(
                        label = "Test",
                        description = "Lorem ipsum dolor sit amet, consectetur adipiscing elit mollit anim id est laborum.",
                        checked = checked.value,
                        onCheckedChange = { checked.value = it },
                    )
                }
                Spacer(modifier = Modifier.height(bottomPadding))
            }
        }
    }
}
