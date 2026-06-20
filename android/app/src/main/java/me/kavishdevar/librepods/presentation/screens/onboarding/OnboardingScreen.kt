package me.kavishdevar.librepods.presentation.screens.onboarding

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.utils.XposedState
import me.kavishdevar.librepods.utils.bypassDeviceCheck
import me.kavishdevar.librepods.utils.isSupported

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
) {
    val sharedPreferences = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val isSupported = isSupported(sharedPreferences) || XposedState.bluetoothScopeEnabled

    val state = rememberCarouselState(
        initialItem = 0,
        itemCount = { 4 }
    )

    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val titles = listOf(
        null,
        stringResource(R.string.privacy_policy),
        stringResource(R.string.not_supported),
        stringResource(R.string.permissions),
    )

    val animationScope = rememberCoroutineScope()

    BackHandler {
        animationScope.launch {
            if (state.canScrollBackward) {
                val targetItem = if (isSupported && state.currentItem == 3) 1 else state.currentItem - 1
                state.animateScrollToItem(targetItem)
            }
        }
    }

    LibrePodsTheme(
        m3eEnabled = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topPadding))
            HorizontalUncontainedCarousel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(12.dp),
                state = state,
                itemWidth = LocalWindowInfo.current.containerDpSize.width - 24.dp,
                userScrollEnabled = false
            ) { index ->
                val shape = rememberMaskShape(RoundedCornerShape(52.dp))
                Surface(
                    shape = shape,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(52.dp))
                ) {
                    Column(
                        modifier = Modifier,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        titles[index]?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.displayMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                        when (index) {
                            0 -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Welcome to",
                                            style = MaterialTheme.typography.displayLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            textAlign = TextAlign.Center,
                                        )
                                        Text(
                                            text = stringResource(R.string.app_name),
                                            style = MaterialTheme.typography.displayLargeEmphasized,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            textAlign = TextAlign.Center,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = stringResource(R.string.app_description),
                                            style = MaterialTheme.typography.bodyMediumEmphasized,
                                            color = MaterialTheme.colorScheme.primary,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(64.dp))
                                        FilledTonalIconButton(
                                            onClick = {
                                                animationScope.launch {
                                                    state.animateScrollToItem(1)
                                                }
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .size(
                                                    IconButtonDefaults.largeContainerSize(
                                                        IconButtonDefaults.IconButtonWidthOption.Wide
                                                    )
                                                ),
                                            shape = IconButtonDefaults.largeRoundShape
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Default.ArrowForward,
                                                contentDescription = "forward",
                                                modifier = Modifier.size(IconButtonDefaults.largeIconSize),
                                            )
                                        }
                                    }
                                }
                            }
                            1 -> {
                                PrivacyPolicyPage(
                                    onForward = {
                                        animationScope.launch {
                                            if (isSupported) state.animateScrollToItem(3) else state.animateScrollToItem(2)
                                        }
                                    }
                                )
                            }
                            2 -> {
                                NotSupportedPage(
                                    bypassCompatibilityCheck = {
                                        bypassDeviceCheck(sharedPreferences)
                                    }
                                )
                            }
                            3 -> {
                                PermissionsPage(
                                    onBackward = {
                                        animationScope.launch {
                                            if (state.canScrollBackward) state.animateScrollToItem(if (isSupported) 1 else 2)
                                        }
                                    },
                                    onForward = onOnboardingComplete
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Preview
@Composable
fun OnboardingScreenPreview(){
    OnboardingScreen {}
}
