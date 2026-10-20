package me.kavishdevar.librepods.presentation.screens.apple

import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RecordingScreenRoute(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()

    RecordingScreen(
        uiState = uiState,
        navigateBack = navigateBack,
        recordings = viewModel.recordings(),
        startRecording = viewModel::startRecording,
        stopRecording = viewModel::stopRecording,
    )
}

@Composable
fun RecordingScreen(
    uiState: AppleUiState,
    navigateBack: (() -> Unit)?,
    recordings: List<Recording>,
    startRecording: () -> Unit,
    stopRecording: () -> Unit,
) {
    val state = uiState.state

    val locale = LocalLocale.current.platformLocale
    val datePattern = DateFormat.getBestDateTimePattern(
        locale,
        "yMMMd"
    )

    val timePattern = DateFormat.getBestDateTimePattern(
        locale,
        "jms"
    )

    val formatter = DateTimeFormatter.ofPattern(
        "$datePattern - $timePattern"
    )

    val context = LocalContext.current

    val history = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) {
        mutableStateListOf<Float>()
    }

    LaunchedEffect(state.microphoneState.level) {
        if (!state.recordingState.isRecording) {
            history.clear()
            return@LaunchedEffect
        }

        history += state.microphoneState.level

        while (history.size > 240) {
            history.removeAt(0)
        }
    }

    StyledScaffold(
        title = stringResource(R.string.recorder),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            AnimatedContent(
                targetState = state.recordingState.isRecording,
                label = "recording",
                modifier = Modifier.padding(vertical = 24.dp).weight(1f)
            ) { recording ->
                if (recording) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // AI-generated
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxSize()
                                .weight(1f)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    RoundedCornerShape(28.dp)
                                )
                                .padding(vertical = 24.dp)
                        ) {
                            if (history.isEmpty()) return@Canvas

                            val spacing = 2.dp.toPx()
                            val width = 3.dp.toPx()

                            val visible = ((size.width + spacing) / (width + spacing)).toInt()

                            val start = (history.size - visible).coerceAtLeast(0)

                            val centerY = size.height / 2f

                            var x = size.width - width

                            for (i in history.lastIndex downTo start) {
                                val h = history[i]
                                    .coerceIn(0f, 1f)
                                    .let { 6.dp.toPx() + it * (size.height * 0.45f) }

                                drawRoundRect(
                                    color = Color(0xFFFF4D4D),
                                    topLeft = Offset(
                                        x,
                                        centerY - h
                                    ),
                                    size = Size(
                                        width,
                                        h * 2
                                    ),
                                    cornerRadius = CornerRadius(
                                        width / 2,
                                        width / 2
                                    )
                                )

                                x -= width + spacing

                                if (x < 0f)
                                    break
                            }
                        }

                        Spacer(Modifier.height(24.dp))

//                        todo: duration is wrong, don't do +30ms
//                        Text(
//                            text = buildString {
//                                val total = state.microphoneState.durationMs
//
//                                append((total / 60000).toString().padStart(2, '0'))
//                                append(':')
//
//                                append(((total / 1000) % 60).toString().padStart(2, '0'))
//                            },
//                            modifier = Modifier.fillMaxWidth(),
//                            textAlign = TextAlign.Center,
//                            style = MaterialTheme.typography.displaySmall
//                        )
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier.verticalScroll(scrollState)
                    ) {
                        if (recordings.isNotEmpty()) {
                            StyledList(title = stringResource(R.string.recordings)) {
                                recordings.forEach {
                                    val text = formatter.format(
                                        Instant.ofEpochMilli(it.createdAt.toEpochMilliseconds())
                                            .atZone(ZoneId.systemDefault())
                                    )
                                    styledListItem(
                                        contentText = text,
                                        supportingText = it.uuid.toString(),
                                        orientation = StyledListItemOrientation.Vertical,
                                        onClick = {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${BuildConfig.APPLICATION_ID}.provider",
                                                it.file
                                            )

                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "audio/wav")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }

                                            context.startActivity(
                                                Intent.createChooser(intent, null)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            StyledButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = if (state.recordingState.isRecording) {
                    stopRecording
                } else {
                    startRecording
                }
            ) {
                Text(
                    text = if (state.recordingState.isRecording) {
                        stringResource(R.string.stop_recording)
                    } else stringResource(R.string.start_recording),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}
