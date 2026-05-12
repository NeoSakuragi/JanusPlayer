package com.videoplayer

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

object PlayerManager {

    private const val TAG = "PlayerManager"
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var currentToken: String? = null
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null
    var authToken: String? = null
    private var attachedView: PlayerView? = null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun getPlayer(context: Context): ExoPlayer {
        // Recreate if token changed
        if (player != null && currentToken != authToken) {
            Log.d(TAG, "Token changed, recreating player")
            detachView()
            player?.release()
            player = null
        }
        if (player == null) {
            val factory = DefaultHttpDataSource.Factory()
            if (authToken != null) {
                factory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $authToken"))
            }
            httpDataSourceFactory = factory
            currentToken = authToken
            player = ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build().apply {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
            Log.d(TAG, "Player created (auth: ${authToken != null})")
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
