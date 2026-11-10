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

package me.kavishdevar.librepods.presentation.overlays

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import me.kavishdevar.librepods.data.app.Design
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.data.app.NightTheme
import me.kavishdevar.librepods.services.LibrePodsAccessibilityService
import kotlin.math.roundToInt

enum class IslandType {
    CONNECTED,
    TAKING_OVER,
    MOVED_TO_REMOTE,
    MOVED_TO_OTHER_DEVICE,
}

enum class IslandState {
    COMPACT,
    EXPANDED,
    CLOSE
}

@Suppress("unused")
class IslandWindow(
    private var context: Context,
    private var appSettings: AppSettingsEntity
) {
    private var windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private var lifecycleOwner: IslandLifecycleOwner? = null

    private var autoCloseHandler: Handler? = null
    private var autoCloseRunnable: Runnable? = null

    private var currentAppSettings by mutableStateOf(appSettings)

    private var name by mutableStateOf("[Test] AirPods Pro")
    private var type by mutableStateOf(IslandType.CONNECTED)
    private var batteryLevel by mutableIntStateOf(70)
    private var reversed by mutableStateOf(false)
    private var otherDeviceName by mutableStateOf(null as String?)

    private var showOffListeningMode by mutableStateOf(false)
    private var noiseControlModeValue by mutableIntStateOf(4)
    private var adaptiveStrength by mutableIntStateOf(30)

    private var onNoiseControlModeChanged: (Int) -> Unit = { noiseControlModeValue = it }
    private var onAdaptiveStrengthChanged: (Int) -> Unit = { adaptiveStrength = it }

    private var openApp: () -> Unit = {}

    var usingAccessibilityService by mutableStateOf(false)
        private set

    init {
        usingAccessibilityService = context is LibrePodsAccessibilityService
    }

    var islandState by mutableStateOf(IslandState.CLOSE)
        private set

    val isVisible: Boolean
        get() = composeView?.parent != null && composeView?.visibility == View.VISIBLE

    fun updateContext(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateContext(context) }
            return
        }

        this.context = context
        this.usingAccessibilityService = context is LibrePodsAccessibilityService
        this.windowManager = context.getSystemService(WindowManager::class.java)

        updateWindowParams(islandState)

        composeView?.let { view ->
            if (view.parent != null) {
                windowManager.removeView(view)
            }
        }

        setupComposeView()

        if (composeView?.parent == null) {
            val params = createLayoutParams(islandState)
            try {
                lifecycleOwner?.start()
                windowManager.addView(composeView, params)
                composeView?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("IslandWindow", "Error adding overlay view: $e")
            }
        }
    }

    fun updateSettings(appSettings: AppSettingsEntity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateSettings(appSettings) }
            return
        }

        this.appSettings = appSettings
        this.currentAppSettings = appSettings

        updateWindowParams(islandState)
    }

    fun updateBattery(batteryPercentage: Int) {
        this.batteryLevel = batteryPercentage
    }

    fun updateNoiseControl(mode: Int, strength: Int, showOff: Boolean = showOffListeningMode) {
        this.noiseControlModeValue = mode
        this.adaptiveStrength = strength
        this.showOffListeningMode = showOff
    }

    fun changeState(state: IslandState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { changeState(state) }
            return
        }

        islandState = state
        updateWindowParams(state)
    }

    fun show(
        name: String = this.name,
        batteryLevel: Int = this.batteryLevel,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = this.reversed,
        otherDeviceName: String? = this.otherDeviceName,
        onReverseAction: () -> Unit = {},

        openApp: () -> Unit = this.openApp,

        showOffListeningMode: Boolean = this.showOffListeningMode,
        noiseControlModeValue: Int = this.noiseControlModeValue,
        onNoiseControlModeChanged: (Int) -> Unit = this.onNoiseControlModeChanged,
        adaptiveStrength: Int = this.adaptiveStrength,
        onAdaptiveStrengthChanged: (Int) -> Unit = this.onAdaptiveStrengthChanged,

        forcedState: IslandState? = null,
        keepOpen: Boolean = false
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                show(name, batteryLevel, type, reversed, otherDeviceName, onReverseAction,
                    openApp, showOffListeningMode, noiseControlModeValue, onNoiseControlModeChanged,
                    adaptiveStrength, onAdaptiveStrengthChanged, forcedState, keepOpen)
            }
            return
        }

        Log.d("IslandWindow", "Showing island window: accessibility = $usingAccessibilityService")

        this.batteryLevel = batteryLevel

        this.name = name
        this.type = type
        this.reversed = reversed
        this.otherDeviceName = otherDeviceName

        this.openApp = openApp

        this.showOffListeningMode = showOffListeningMode
        this.noiseControlModeValue = noiseControlModeValue
        this.adaptiveStrength = adaptiveStrength

        this.onNoiseControlModeChanged = {
            this.noiseControlModeValue = it
            onNoiseControlModeChanged(it)
        }

        this.onAdaptiveStrengthChanged = {
            this.adaptiveStrength = it
            onAdaptiveStrengthChanged(it)
        }

        if (composeView == null) {
            setupComposeView()
        }

        // Set the state first so the window is laid out at the expanded width; a wrap_content
        // window is first measured at the platform's preferred dialog width and would clip the island.
        islandState = forcedState?: if (appSettings.islandSettings.enableExpanded) IslandState.EXPANDED else IslandState.COMPACT

        if (composeView?.parent == null) {
            val params = createLayoutParams(islandState)
            try {
                lifecycleOwner?.start()
                windowManager.addView(composeView, params)
                composeView?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("IslandWindow", "Error adding overlay view: $e")
            }
        } else {
            updateWindowParams(islandState)
        }

        if (!keepOpen) resetTimer()
    }

    private fun updateWindowParams(state: IslandState) {
        if (composeView?.parent != null) {
            val params = createLayoutParams(state)
            windowManager.updateViewLayout(composeView, params)
        }
    }

    private fun createLayoutParams(state: IslandState = islandState): WindowManager.LayoutParams {
        val windowType = if (context is android.accessibilityservice.AccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return WindowManager.LayoutParams(
            if (state == IslandState.EXPANDED) appSettings.islandSettings.expandedWidth.toInt()
            else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = appSettings.islandSettings.offsetX.roundToInt()
            // Accessibility overlays draw above the status bar; app overlays draw below it, so keep them clear of it.
            val offsetY = appSettings.islandSettings.offsetY.roundToInt()
            y = if (usingAccessibilityService) offsetY else maxOf(offsetY, statusBarHeight())

            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun statusBarHeight(): Int =
        windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top

    private fun setupComposeView() {
        lifecycleOwner = IslandLifecycleOwner()

        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                val darkTheme = when (currentAppSettings.nightMode) {
                    NightTheme.Dark -> true
                    NightTheme.Light -> false
                    NightTheme.System -> isSystemInDarkTheme()
                }

                LibrePodsTheme(
                    design = Design(
                        system = currentAppSettings.designSystem,
                        colorStyle = currentAppSettings.colorStyle,
                        overrideMaterialColor = currentAppSettings.overrideMaterialColor,
                        contrastLevel = currentAppSettings.contrastLevel
                    ),
                    accessibilitySettings = currentAppSettings.accessibilitySettings,
                    fontSettings = currentAppSettings.fontSettings,
                    darkTheme = darkTheme
                ) {
                    IslandContent(
                        name = name,
                        type = type,
                        batteryLevel = batteryLevel,
                        reversed = reversed,
                        otherDeviceName = otherDeviceName,

                        islandSettings = currentAppSettings.islandSettings,
                        islandState = islandState,

                        openApp = { openApp() },
                        changeState = { state, updateBefore ->
                            if (updateBefore) updateWindowParams(state)
                            Log.d("Island", "Changing state: $islandState -> $state")
                            islandState = state
                            if (!updateBefore) updateWindowParams(state)
                        },
                        onClosed = { cleanupAndRemoveView() },
                        resetTimer = { resetTimer() },

                        showOffListeningMode = showOffListeningMode,
                        noiseControlModeValue = noiseControlModeValue,
                        onNoiseControlModeChanged = this@IslandWindow.onNoiseControlModeChanged,
                        adaptiveStrength = this@IslandWindow.adaptiveStrength,
                        onAdaptiveStrengthChanged = this@IslandWindow.onAdaptiveStrengthChanged
                    )
                }
            }
        }
    }

    private fun resetTimer() {
        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
        autoCloseHandler = Handler(Looper.getMainLooper())
        autoCloseRunnable = Runnable {
            if (appSettings.islandSettings.keepCompactVisible) {
                islandState = IslandState.COMPACT
                updateWindowParams(islandState)
            } else {
                close()
            }
        }
        autoCloseHandler?.postDelayed(autoCloseRunnable!!, 4500)
    }

    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { close() }
            return
        }

        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
        islandState = IslandState.CLOSE
        updateWindowParams(islandState)
    }

    private fun cleanupAndRemoveView() {
        try {
            composeView?.let { view ->
                if (view.parent != null) {
                    windowManager.removeView(view)
                }
            }
            lifecycleOwner?.stop()
        } catch (e: Exception) {
            Log.e("IslandWindow", "Error removing view: $e")
        } finally {
            composeView = null
            lifecycleOwner = null
            islandState = IslandState.CLOSE
        }
    }
}

private class IslandLifecycleOwner: LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        field = LifecycleRegistry(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() {
        savedStateRegistryController.performRestore(null)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
