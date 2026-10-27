package me.kavishdevar.librepods.presentation.screens.apple

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.flow.debounce
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.presentation.components.primitives.StyledIconButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesign
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun HeartRateRoute(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()

    HeartRateScreen(
        uiState = uiState,
        navigateBack = navigateBack,
        startHr = viewModel::startHr,
        stopHr = viewModel::stopHr,
        setHrRange = viewModel::setHrRange,
        updateSettings = viewModel::updateSettings
    )
}

@Composable
fun HeartRateScreen(
    uiState: AppleUiState,
    navigateBack: (() -> Unit)?,
    startHr: () -> Unit,
    stopHr: () -> Unit,
    setHrRange: (ClosedRange<Long>) -> Unit,
    updateSettings: (transform: (AppleSettings) -> AppleSettings) -> Unit,
) {
    val design = LocalDesign.current

    val state = uiState.state
    val settings = uiState.settings

    val scrollState = rememberScrollState()

    StyledScaffold(
        title = stringResource(R.string.heart_rate),
        navigateBack = navigateBack,
        actionButtons = listOf(
            { hazeInput ->
                val icon = when (state.hrmState) {
                    BuddyState.INACTIVE -> LocalIcons.current.Play
                    BuddyState.WAITING -> LocalIcons.current.Circle
                    BuddyState.ACTIVE -> LocalIcons.current.Pause
                }
                if (design.system == DesignSystem.Material) {
                    FilledTonalIconToggleButton(
                        checked = state.hrmState == BuddyState.ACTIVE,
                        onCheckedChange = { if (it) startHr() else stopHr() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Uniform)),
                        shape = IconButtonDefaults.mediumRoundShape,
                    ) {
                        AnimatedContent(
                            icon
                        ) {
                            Icon(
                                imageVector = it,
                                contentDescription = stringResource(R.string.start_stop),
                                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                            )
                        }
                    }
                } else {
                    StyledIconButton(
                        onClick = { if (state.hrmState == BuddyState.INACTIVE) startHr() else stopHr() },
                        hazeInput = hazeInput,
                    ) {
                        AnimatedContent(
                            icon
                        ) {
                            Icon(
                                imageVector = it,
                                contentDescription = stringResource(R.string.start_stop),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        )
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            val healthPermissions = rememberPermissionState(HealthPermission.getWritePermission(HeartRateRecord::class))

            val defaultSpatialSpecFloat = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

            val fastSpatialSpecFloat = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
            val fastSpatialSpecIntOffset = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()

            AnimatedContent(
                targetState = state.hrmState,
                transitionSpec = { fadeIn(defaultSpatialSpecFloat) togetherWith fadeOut(defaultSpatialSpecFloat) },
                label = "hrm_state"
            ) { buddyState ->
                when (buddyState) {
                    BuddyState.INACTIVE -> {}
                    BuddyState.WAITING -> {
                        Text(
                            text = stringResource(R.string.waiting_ellipsis),
                            style = MaterialTheme.typography.labelSmallEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    BuddyState.ACTIVE -> {
                        StyledListItem(
                            onClick = null,
                            content = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val bpm = state.currentHeartRate?.bpm ?: 0

                                    // make this reusable
                                    bpm.toString().forEachIndexed { index, digit ->
                                        AnimatedContent(
                                            targetState = digit,
                                            transitionSpec = {
                                                (
                                                    slideInVertically(
                                                        animationSpec = fastSpatialSpecIntOffset,
                                                        initialOffsetY = { it }
                                                    ) + fadeIn(fastSpatialSpecFloat)
                                                ) togetherWith
                                                 (
                                                     slideOutVertically(
                                                        animationSpec = fastSpatialSpecIntOffset,
                                                        targetOffsetY = { -it }
                                                    ) + fadeOut(fastSpatialSpecFloat)
                                                 )
                                            },
                                            label = "bpm_digit_$index"
                                        ) { value ->
                                            Text(
                                                text = value.toString(),
                                                style = MaterialTheme.typography.headlineMediumEmphasized
                                            )
                                        }
                                    }

                                    Text(
                                        text = " bpm",
                                        style = MaterialTheme.typography.headlineMediumEmphasized
                                    )
                                }
                            },
                            supportingContent = {
                                val locale = LocalLocale.current.platformLocale
                                val timePattern = DateFormat.getBestDateTimePattern(
                                    locale,
                                    "jms"
                                )
                                val formatter = DateTimeFormatter.ofPattern(timePattern)

                                val timeString = formatter.format(
                                    Instant.ofEpochMilli(
                                        state.currentHeartRate?.timestamp?.toEpochMilliseconds()
                                            ?: 0
                                    )
                                        .atZone(ZoneId.systemDefault())
                                )

                                Text(
                                    text = timeString,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialShapes.SoftBurst.normalized()
                                                .toShape()
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = LocalIcons.current.VitalSigns,
                                        contentDescription = stringResource(R.string.heart_rate),
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            },
                            orientation = StyledListItemOrientation.Vertical
                        )
                    }
                }
            }

            AnimatedVisibility(visible = !healthPermissions.status.isGranted) {
                StyledListItem(
                    contentText = stringResource(R.string.permission_healthconnect),
                    onClick = { healthPermissions.launchPermissionRequest() },
                    supportingText = stringResource(R.string.permission_description_healthconnect),
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                    MaterialShapes.SoftBurst.normalized()
                                        .toShape()
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = LocalIcons.current.VitalSigns,
                                contentDescription = stringResource(R.string.heart_rate),
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    orientation = StyledListItemOrientation.Vertical
                )
            }

            StyledToggle(
                label = stringResource(R.string.heart_rate_alert),
                description = stringResource(R.string.hrm_alert_description),
                checked = settings.hrmAlertEnabled,
                onCheckedChange = { enabled ->
                    updateSettings {
                        it.copy(hrmAlertEnabled = enabled)
                    }
                    if (enabled && state.hrmState == BuddyState.INACTIVE) {
                        startHr()
                    }
                },
            )

            AnimatedVisibility(
                visible = settings.hrmAlertEnabled && state.hrmState == BuddyState.INACTIVE
            ) {
                Text(
                    text = stringResource(R.string.heart_rate_alerts_disabled_warning),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            val sliderValue = remember { mutableFloatStateOf(settings.hrmAlertThreshold.toFloat()) }

            LaunchedEffect(sliderValue) {
                snapshotFlow { sliderValue.floatValue }
                    .debounce(250.milliseconds)
                    .collect { value ->
                        updateSettings {
                            it.copy(hrmAlertThreshold = value.toInt())
                        }
                    }
            }

            StyledSlider(
                label = stringResource(R.string.heart_rate_alert_threshold),
                value = sliderValue.floatValue,
                onValueChange = { sliderValue.floatValue = it },
                valueRange = 120f..200f,
                description = "${sliderValue.floatValue.roundToInt()} bpm",
                independent = true
            )

            Text(
                text = stringResource(R.string.hrm_alert_warning),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // TODO: graph or something
//            Column(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .heightIn(min = 350.dp)
//                    .background(
//                        MaterialTheme.colorScheme.surfaceContainerHigh,
//                        RoundedCornerShape(28.dp)
//                    )
//            ) { }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}
