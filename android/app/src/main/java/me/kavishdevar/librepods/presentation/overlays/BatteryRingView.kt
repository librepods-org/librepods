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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import me.kavishdevar.librepods.R
import java.text.NumberFormat

/** Battery level drawn the way iOS shows it in the Dynamic Island: a ring around the bare number. */
class BatteryRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var level: Int? = null
    private var sweepFraction = 0f
    private var sweepAnimator: ValueAnimator? = null

    private val strokeWidth = resources.getDimension(R.dimen.island_battery_ring_stroke)
    private val ringBounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@BatteryRingView.strokeWidth
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@BatteryRingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        typeface = Typeface.create(ResourcesCompat.getFont(context, R.font.sf_pro), 600, false)
    }

    init {
        applyColor()
    }

    fun setLevel(newLevel: Int?, animate: Boolean) {
        if (newLevel == level && sweepAnimator?.isRunning != true) return
        level = newLevel
        contentDescription = newLevel?.let { NumberFormat.getPercentInstance().format(it / 100.0) }
        applyColor()

        val target = (newLevel ?: 0) / 100f
        sweepAnimator?.cancel()
        if (animate) {
            sweepAnimator = ValueAnimator.ofFloat(sweepFraction, target).apply {
                duration = 700
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    sweepFraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            sweepFraction = target
            invalidate()
        }
    }

    private fun applyColor() {
        val color = context.getColor(
            if ((level ?: 100) <= LOW_BATTERY_LEVEL) R.color.island_battery_low else R.color.island_battery_normal
        )
        progressPaint.color = color
        textPaint.color = color
        trackPaint.color = ColorUtils.setAlphaComponent(color, 0x4D)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidth / 2f
        ringBounds.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawOval(ringBounds, trackPaint)
        if (sweepFraction > 0f) {
            canvas.drawArc(ringBounds, -90f, 360f * sweepFraction, false, progressPaint)
        }
        level?.let {
            val baseline = ringBounds.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(it.toString(), ringBounds.centerX(), baseline, textPaint)
        }
    }

    override fun onDetachedFromWindow() {
        sweepAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LOW_BATTERY_LEVEL = 20
    }
}
