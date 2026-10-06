package me.kavishdevar.librepods.presentation.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

// mostly AI generated

private enum class Direction {
    Forward,
    Back,
    None
}

class SwipeBackSceneStrategy<T : Any>(
    private val enabled: Boolean,
    private val backRequests: Channel<CompletableDeferred<Unit>>,
    private val onDismiss: () -> Unit
) : SceneStrategy<T> {

    private var previousEntries: List<NavEntry<T>> = emptyList()

    override fun SceneStrategyScope<T>.calculateScene(
        entries: List<NavEntry<T>>
    ): Scene<T>? {
        if (entries.isEmpty()) return null

        val currentEntry = entries.last()
        val previousEntry = entries.getOrNull(entries.lastIndex - 1)

        val direction = when {
            previousEntries.isEmpty() -> Direction.None
            currentEntry in previousEntries -> Direction.Back
            else -> Direction.Forward
        }

        previousEntries = entries

        return object : Scene<T> {
            override val key: Any
                get() = "${currentEntry.contentKey}_${currentEntry.hashCode()}"

            override val entries: List<NavEntry<T>>
                get() = entries

            override val previousEntries: List<NavEntry<T>>
                get() = listOfNotNull(previousEntry)

            override val content: @Composable () -> Unit
                get() = {
                    SwipeBackSceneContent(
                        previousEntry = previousEntry,
                        currentEntry = currentEntry,
                        direction = direction,
                        swipeEnabled = enabled,
                        backRequests = backRequests,
                        onDismiss = onDismiss
                    )
                }
        }
    }
}

@Composable
private fun <T : Any> SwipeBackSceneContent(
    previousEntry: NavEntry<T>?,
    currentEntry: NavEntry<T>,
    direction: Direction,
    swipeEnabled: Boolean,
    backRequests: Channel<CompletableDeferred<Unit>>,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalWindowInfo.current.containerDpSize.width.toPx()
    }

    val animatedOffset = remember(
        "${currentEntry.contentKey}_${currentEntry.hashCode()}"
    ) {
        Animatable(
            if (direction == Direction.Forward) {
                screenWidthPx
            } else {
                0f
            }
        )
    }

    var transitionProgress by remember {
        mutableFloatStateOf(
            if (direction == Direction.Forward) 1f else 0f
        )
    }

    var pushCompleted by remember {
        mutableStateOf(direction != Direction.Forward)
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(
        "${currentEntry.contentKey}_${currentEntry.hashCode()}",
        direction
    ) {
        if (direction == Direction.Forward) {
            transitionProgress = 1f

            animatedOffset.animateTo(
                0f,
                tween(220)
            )

            transitionProgress = 0f
            pushCompleted = true
        }
    }

    LaunchedEffect(Unit) {
        for (completed in backRequests) {
            animatedOffset.animateTo(
                screenWidthPx,
                tween(150)
            )

            transitionProgress = -1f
            onDismiss()
            completed.complete(Unit)
        }
    }

    // only intercept back when there is a screen to go back to, otherwise let the activity handle it (exit the app)
    PredictiveBackHandler(enabled = previousEntry != null) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                if (previousEntry == null) return@collect
                val progress = backEvent.progress

                transitionProgress = -progress

                animatedOffset.snapTo(
                    progress * screenWidthPx
                )
            }

            animatedOffset.animateTo(
                screenWidthPx,
                tween(150)
            )

            transitionProgress = -1f
            onDismiss()
        } catch (_: CancellationException) {
            animatedOffset.animateTo(
                0f,
                tween(150)
            )

            transitionProgress = 0f
        }
    }

    val draggableState = rememberDraggableState { delta ->
        scope.launch {
            val offset =
                (animatedOffset.value + delta)
                    .coerceAtLeast(0f)

            animatedOffset.snapTo(offset)

            transitionProgress =
                -(offset / screenWidthPx)
                    .coerceIn(0f, 1f)
        }
    }

    CompositionLocalProvider(
        LocalTransitionProgress provides transitionProgress
    ) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalIsCurrentEntry provides false
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (direction == Direction.Forward && !pushCompleted) {
                                translationX = 0f
                            } else {
                                val progress = (animatedOffset.value / screenWidthPx).coerceIn(0f, 1f)
                                translationX = -(screenWidthPx / 3f) * (1f - progress)
                            }
                        }
                ) {
                    previousEntry?.Content()
                }
            }

            CompositionLocalProvider(
                LocalIsCurrentEntry provides true
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = animatedOffset.value
                        }
                        .draggable(
                            enabled = swipeEnabled && previousEntry != null,
                            state = draggableState,
                            orientation = Orientation.Horizontal,
                            onDragStopped = { velocity ->
                                val currentOffset = animatedOffset.value
                                val shouldDismiss = currentOffset > screenWidthPx * 0.35f || velocity > 1000f

                                scope.launch {
                                    if (shouldDismiss) {
                                        animatedOffset.animateTo(
                                            targetValue = screenWidthPx,
                                            animationSpec = tween(150),
                                            initialVelocity = velocity
                                        ) {
                                            transitionProgress = -(value / screenWidthPx).coerceIn(0f, 1f)
                                        }
                                        transitionProgress = -1f
                                        onDismiss()
                                    } else {
                                        animatedOffset.animateTo(
                                            targetValue = 0f,
                                            animationSpec = tween(150),
                                            initialVelocity = velocity
                                        ) {
                                            transitionProgress = -(value / screenWidthPx).coerceIn(0f, 1f)
                                        }
                                        transitionProgress = 0f
                                    }
                                }
                            }
                        )
                ) {
                    currentEntry.Content()
                }
            }
        }
    }
}
