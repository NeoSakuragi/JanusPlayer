package com.videoplayer

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

object PlayerManager {

    private const val TAG = "PlayerManager"
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var attachedView: PlayerView? = null

    fun getPlayer(context: Context): ExoPlayer {
        if (player == null) {
            player = ExoPlayer.Builder(context.applicationContext).build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            Log.d(TAG, "Player created")
        }
        return player!!
    }

    fun attachView(view: PlayerView) {
        detachView()
        view.player = player
        attachedView = view
    }

    fun detachView() {
        attachedView?.player = null
        attachedView = null
    }

    fun preview(context: Context, url: String, seekMs: Long) {
        val p = getPlayer(context)
        if (currentUrl != url) {
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            currentUrl = url
        }
        p.seekTo(seekMs)
        p.volume = 1f
        p.play()
    }

    fun play(context: Context, url: String, seekMs: Long) {
        val p = getPlayer(context)
        if (currentUrl != url) {
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            currentUrl = url
        }
        p.seekTo(seekMs)
        p.volume = 1f
        p.play()
    }

    fun stop() {
        player?.stop()
        currentUrl = null
    }

    fun pause() {
        player?.pause()
    }

    fun release() {
        detachView()
        player?.release()
        player = null
        currentUrl = null
        Log.d(TAG, "Player released")
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun currentPosition(): Long = player?.currentPosition ?: 0
    fun duration(): Long = player?.duration?.coerceAtLeast(0) ?: 0
}
