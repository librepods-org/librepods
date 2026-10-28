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
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes

/**
 * Plays a silent raw video on loop. Unlike VideoView, a TextureView follows the alpha, scale,
 * clipping and blur of its parents, which the island's morph animation relies on. MediaPlayer
 * does not request audio focus, so music keeps playing.
 */
class LoopingVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    @RawRes
    private var videoRes = 0
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun play(@RawRes resId: Int) {
        videoRes = resId
        surfaceTexture?.let(::startPlayer)
    }

    fun release() {
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    private fun startPlayer(texture: SurfaceTexture) {
        release()
        if (videoRes == 0) return
        val videoSurface = Surface(texture).also { surface = it }
        player = try {
            MediaPlayer().apply {
                resources.openRawResourceFd(videoRes).use { setDataSource(it) }
                setSurface(videoSurface)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not play video", e)
            null
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        startPlayer(texture)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private companion object {
        const val TAG = "LoopingVideoView"
    }
}
