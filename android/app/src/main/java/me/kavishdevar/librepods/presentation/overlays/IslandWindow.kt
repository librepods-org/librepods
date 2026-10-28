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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.services.ServiceManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class IslandType {
    CONNECTED,
    TAKING_OVER,
    MOVED_TO_REMOTE,
    MOVED_TO_OTHER_DEVICE,
}

/**
 * Dynamic Island-style card modeled on the iOS AirPods connection activity: a black pill grows
 * out from below the camera, shows the device and its battery for a few seconds, and shrinks
 * back. Swipe up to dismiss it early; tap it or pull it down to open the app.
 */
class IslandWindow(context: Context) {
    private val appContext = context.applicationContext
    private val windowContext = appContext.createWindowContext(
        appContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        null
    )
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val resources = windowContext.resources
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("InflateParams")
    private val islandView = LayoutInflater.from(windowContext).inflate(R.layout.island_window, null) as IslandView
    private val statusText: TextView = islandView.findViewById(R.id.island_status_text)
    private val deviceNameText: TextView = islandView.findViewById(R.id.island_device_name)
    private val videoView: LoopingVideoView = islandView.findViewById(R.id.island_video_view)
    private val batteryRing: BatteryRingView = islandView.findViewById(R.id.island_battery_ring)
    private val actionButton: ImageButton = islandView.findViewById(R.id.island_action_button)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "LibrePods island"
    }
    private var restingY = 0

    private val expansionSpring = SpringAnimation(islandView, IslandView.EXPANSION).apply {
        spring = SpringForce()
        setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        addEndListener { _, canceled, _, _ -> if (isClosing && !canceled) removeWindow() }
    }

    // Vertical drag offset of the window, in pixels from its resting position.
    private var dragOffset = 0f
    private val dragSpring = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce(0f)
            .setStiffness(SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
        addUpdateListener { _, value, _ -> applyDragOffset(value) }
    }
    private var velocityTracker: VelocityTracker? = null
    private var touchDownY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(windowContext).scaledTouchSlop
    private val minFlingVelocity = 800 * resources.displayMetrics.density
    private val dragLimit = 40 * resources.displayMetrics.density

    private val autoClose = Runnable { close() }
    private var isClosing = false
    private var receiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AirPodsNotifications.BATTERY_DATA -> {
                    val batteries = intent.getParcelableArrayListExtra("data", Battery::class.java)
                    budLevel(batteries)?.let { batteryRing.setLevel(it, animate = true) }
                }
                AirPodsNotifications.DISCONNECT_RECEIVERS -> unregisterBatteryReceiver()
            }
        }
    }

    val isVisible: Boolean
        get() = islandView.parent != null && !isClosing

    fun show(
        name: String,
        batteryPercentage: Int,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null
    ) {
        val service = ServiceManager.getService()
        if (service?.islandOpen == true || islandView.parent != null) return
        service?.islandOpen = true

        deviceNameText.text = name
        statusText.text = statusText(type, reversed, otherDeviceName)

        val offersUndo = type == IslandType.MOVED_TO_OTHER_DEVICE && !reversed
        actionButton.isVisible = offersUndo
        batteryRing.isVisible = !offersUndo
        actionButton.setOnClickListener {
            ServiceManager.getService()?.takeOver("reverse")
            close()
        }
        batteryRing.setLevel(
            budLevel(service?.getBattery()) ?: batteryPercentage.takeIf { it > 0 },
            animate = true
        )

        updateWindowMetrics()
        islandView.setOnTouchListener { _, event -> onTouch(event) }
        try {
            windowManager.addView(islandView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Could not add island window", e)
            service?.islandOpen = false
            return
        }

        registerBatteryReceiver()
        service?.sendBatteryBroadcast()
        videoView.play(R.raw.island)

        islandView.expansion = 0f
        expansionSpring.spring.setStiffness(OPEN_STIFFNESS).setDampingRatio(OPEN_DAMPING)
        expansionSpring.animateToFinalPosition(1f)
        scheduleAutoClose()
    }

    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { close() }
            return
        }
        if (isClosing || islandView.parent == null) return
        isClosing = true

        ServiceManager.getService()?.islandOpen = false
        mainHandler.removeCallbacks(autoClose)
        unregisterBatteryReceiver()
        islandView.setOnTouchListener(null)

        // Collapse wherever the island is, so a swipe up shrinks it away instead of pulling it back.
        dragSpring.cancel()
        expansionSpring.spring.setStiffness(CLOSE_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
        expansionSpring.animateToFinalPosition(0f)
    }

    private fun updateWindowMetrics() {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets
        val bleed = resources.getDimensionPixelSize(R.dimen.island_bleed)
        val margin = resources.getDimensionPixelSize(R.dimen.island_horizontal_margin)
        val islandWidth = min(metrics.bounds.width() - 2 * margin, resources.getDimensionPixelSize(R.dimen.island_max_width))

        params.width = islandWidth + 2 * bleed
        restingY = insets.getInsets(WindowInsets.Type.statusBars()).top +
            resources.getDimensionPixelSize(R.dimen.island_top_gap) - bleed
        params.y = restingY
        dragOffset = 0f

        // Start from a pill as wide as the camera cutout, so the island appears to come out of it.
        val cutoutWidth = insets.displayCutout?.boundingRectTop?.width() ?: 0
        islandView.collapsedWidth = cutoutWidth.toFloat().coerceIn(
            resources.getDimension(R.dimen.island_collapsed_min_width),
            resources.getDimension(R.dimen.island_collapsed_max_width)
        )
        islandView.collapsedHeight = resources.getDimension(R.dimen.island_collapsed_height)
    }

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mainHandler.removeCallbacks(autoClose)
                dragSpring.cancel()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                touchDownY = event.rawY - rubberBandInverse(dragOffset)
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val distance = event.rawY - touchDownY
                if (!isDragging && abs(distance) > touchSlop) isDragging = true
                if (isDragging) applyDragOffset(rubberBand(distance))
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.apply {
                    addMovement(event)
                    computeCurrentVelocity(1000)
                }
                val velocity = velocityTracker?.yVelocity ?: 0f
                when {
                    !isDragging -> openApp()
                    dragOffset < -dragLimit / 2 || velocity < -minFlingVelocity -> close()
                    dragOffset > dragLimit / 2 || velocity > minFlingVelocity -> openApp()
                    else -> settleDrag(velocity)
                }
                recycleVelocityTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                settleDrag(0f)
                recycleVelocityTracker()
            }
        }
        return true
    }

    // Upward drags follow the finger; downward drags resist like iOS rubber-banding.
    private fun rubberBand(distance: Float): Float =
        if (distance <= 0f) distance else dragLimit * (1f - 1f / (distance * RUBBER_BAND / dragLimit + 1f))

    private fun rubberBandInverse(offset: Float): Float =
        if (offset <= 0f) offset else offset * dragLimit / (RUBBER_BAND * (dragLimit - offset))

    private fun applyDragOffset(offset: Float) {
        dragOffset = offset
        val stretch = 1f + max(offset, 0f) / dragLimit * 0.04f
        islandView.scaleX = stretch
        islandView.scaleY = stretch
        if (islandView.parent == null) return
        params.y = restingY + offset.roundToInt()
        windowManager.updateViewLayout(islandView, params)
    }

    private fun settleDrag(velocity: Float) {
        dragSpring.setStartValue(dragOffset)
        dragSpring.setStartVelocity(velocity)
        dragSpring.animateToFinalPosition(0f)
        scheduleAutoClose()
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun openApp() {
        ServiceManager.getService()?.startMainActivity()
        close()
    }

    private fun scheduleAutoClose() {
        mainHandler.removeCallbacks(autoClose)
        mainHandler.postDelayed(autoClose, AUTO_CLOSE_DELAY_MS)
    }

    private fun removeWindow() {
        videoView.release()
        dragSpring.cancel()
        try {
            windowManager.removeView(islandView)
        } catch (e: Exception) {
            Log.e(TAG, "Could not remove island window", e)
        }
        isClosing = false
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(AirPodsNotifications.BATTERY_DATA).apply {
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
        }
        appContext.registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterBatteryReceiver() {
        if (!receiverRegistered) return
        appContext.unregisterReceiver(batteryReceiver)
        receiverRegistered = false
    }

    private fun statusText(type: IslandType, reversed: Boolean, otherDeviceName: String?): String =
        when (type) {
            IslandType.CONNECTED -> windowContext.getString(R.string.island_connected_text)
            IslandType.TAKING_OVER -> windowContext.getString(R.string.island_taking_over_text)
            IslandType.MOVED_TO_REMOTE -> windowContext.getString(R.string.island_moved_to_remote_text)
            IslandType.MOVED_TO_OTHER_DEVICE ->
                if (reversed) {
                    windowContext.getString(R.string.island_moved_to_other_device_reversed_text)
                } else {
                    if (otherDeviceName.isNullOrEmpty()) Log.w(TAG, "No device name for MOVED_TO_OTHER_DEVICE")
                    windowContext.getString(R.string.island_moved_to_other_device_text, otherDeviceName.orEmpty())
                }
        }

    // The lower of the two buds, ignoring any that report no charge.
    private fun budLevel(batteries: List<Battery>?): Int? =
        batteries.orEmpty()
            .filter { it.component == BatteryComponent.LEFT || it.component == BatteryComponent.RIGHT }
            .map { it.level }
            .filter { it > 0 }
            .minOrNull()

    private companion object {
        const val TAG = "IslandWindow"
        const val AUTO_CLOSE_DELAY_MS = 4500L
        const val OPEN_STIFFNESS = 280f
        const val OPEN_DAMPING = 0.72f
        const val CLOSE_STIFFNESS = 420f
        const val RUBBER_BAND = 0.55f
    }
}
