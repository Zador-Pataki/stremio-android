package com.stremio.mobile.cast

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.stremio.mobile.player.ExternalSubtitle
import com.stremio.mobile.player.Player
import com.stremio.mobile.player.PlayerEngine
import com.stremio.mobile.player.PlayerResizeMode
import com.stremio.mobile.player.PlayerRuntimeState
import com.stremio.mobile.player.PlayerSubtitleStyle
import kotlinx.coroutines.flow.StateFlow

class CastPlayerAdapter(private val controller: CastPlaybackController) : Player {
    override val engine = PlayerEngine.EXO
    override val runtimeState: StateFlow<PlayerRuntimeState> = controller.runtimeState

    override fun createView(context: Context): View = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(TextView(context).apply {
            text = controller.state.value.deviceName?.let { "Casting to $it" } ?: "Casting"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun load(uri: Uri, startPositionMs: Long, subtitles: List<ExternalSubtitle>, preferredSubtitleLang: String?, settings: com.stremio.core.types.profile.Profile.Settings?) = Unit
    override fun retry() = Unit
    override fun play() = controller.play()
    override fun pause() = controller.pause()
    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    override fun setPlaybackSpeed(speed: Float) = controller.setPlaybackSpeed(speed)
    override fun setResizeMode(mode: PlayerResizeMode) = Unit
    override fun selectAudioTrack(id: String) = Unit
    override fun selectSubtitleTrack(id: String) = Unit
    override fun disableSubtitles() = Unit
    override fun setSubtitleStyle(style: PlayerSubtitleStyle) = Unit
    override fun addExternalSubtitleTracks(tracks: List<ExternalSubtitle>) = Unit
    override fun addLocalSubtitle(track: ExternalSubtitle) = Unit
    override fun release() = Unit
}
