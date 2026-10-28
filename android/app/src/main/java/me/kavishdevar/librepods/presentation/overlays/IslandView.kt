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
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatPropertyCompat
import me.kavishdevar.librepods.R

/**
 * The black stadium of the Dynamic Island. It morphs between a small pill below the camera
 * ([expansion] 0) and its full size ([expansion] 1). Springs may overshoot past 1, which grows
 * the shape into the transparent bleed around it.
 */
class IslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var collapsedWidth = 0f
    var collapsedHeight = 0f

    var expansion = 0f
        set(value) {
            field = value
            applyExpansion()
        }

    private val bleed = resources.getDimension(R.dimen.island_bleed)
    private val maxContentBlur = 12 * resources.displayMetrics.density
    private val shape = RectF()
    private lateinit var content: View

    init {
        setBackgroundColor(Color.BLACK)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    shape.left.toInt(),
                    shape.top.toInt(),
                    shape.right.toInt(),
                    shape.bottom.toInt(),
                    shape.height() / 2f
                )
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = findViewById(R.id.island_content)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        content.pivotX = content.width / 2f
        content.pivotY = 0f
        applyExpansion()
    }

    private fun applyExpansion() {
        if (width == 0) return

        val shapeWidth = lerp(collapsedWidth, width - 2 * bleed, expansion).coerceIn(0f, width.toFloat())
        val shapeHeight = lerp(collapsedHeight, height - 2 * bleed, expansion).coerceIn(0f, height - bleed)
        val left = (width - shapeWidth) / 2f
        shape.set(left, bleed, left + shapeWidth, bleed + shapeHeight)
        invalidateOutline()

        // The pill fades in as it leaves the camera; the content follows once there is room for it.
        alpha = (expansion / 0.2f).coerceIn(0f, 1f)
        val reveal = ((expansion - 0.4f) / 0.6f).coerceIn(0f, 1f)
        content.alpha = reveal
        content.scaleX = lerp(0.85f, 1f, reveal)
        content.scaleY = content.scaleX
        val blur = (1f - reveal) * maxContentBlur
        content.setRenderEffect(
            if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) else null
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

    companion object {
        val EXPANSION = object : FloatPropertyCompat<IslandView>("expansion") {
            override fun getValue(view: IslandView) = view.expansion
            override fun setValue(view: IslandView, value: Float) {
                view.expansion = value
            }
        }
    }
}
