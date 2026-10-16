package com.music.spotui.di

import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.spotui.data.local.LocalTrack

/**
 * Plays the device's own audio files (Local Files). While it plays, the Spotify engine is
 * paused and SongPlayer routes the controls here (player, mini player, notification);
 * playing anything from Spotify again hands back to the engine.
 */
object LocalPlayer {
    /** Local playback is the current source. */
    val active = mutableStateOf(false)
    private var tracks: List<LocalTrack> = emptyList()
    /** Where the queue comes from (Local Files, Downloaded…), shown in the player. */
    var contextName: String = ""
        private set
    private var exo: ExoPlayer? = null

    private fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(Graph.app)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
            })
            exo = p
        }

    fun play(list: List<LocalTrack>, index: Int, shuffle: Boolean = false, context: String? = null) {
        if (list.isEmpty()) return
        SongPlayer.pauseEngineForLocal()
        tracks = list
        contextName = context ?: Graph.app.getString(app.myspoti.engine.R.string.local_files_title)
        active.value = true
        val p = player()
        p.setMediaItems(list.map { t ->
            MediaItem.Builder().setUri(t.uri).setMediaId(t.id.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build()).build()
        }, index.coerceIn(0, list.lastIndex), 0L)
        p.shuffleModeEnabled = shuffle
        p.prepare()
        p.play()
    }

    /** Back to Spotify: stop and forget the local queue. */
    fun stop() {
        if (!active.value) return
        active.value = false
        exo?.let { it.stop(); it.clearMediaItems() }
        // Whatever this run played gets closed honestly (Wrapped reporting).
        com.music.spotui.data.offline.WrappedSync.finish()
    }

    fun resume() { exo?.play() }
    fun pause() { exo?.pause() }
    fun next() { exo?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun previous() { exo?.let { if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seekTo(ms: Long) { exo?.seekTo(ms) }
    fun setShuffle(on: Boolean) { exo?.shuffleModeEnabled = on }
    fun setRepeatMode(mode: Int) {
        exo?.repeatMode = when (mode) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    fun isPlaying() = exo?.isPlaying == true
    fun position() = exo?.currentPosition ?: 0L
    fun duration() = exo?.duration?.takeIf { it > 0 } ?: current()?.durationMs ?: 0L
    fun current(): LocalTrack? = exo?.currentMediaItemIndex?.let { tracks.getOrNull(it) }

    private fun publish() {
        if (!active.value) return
        val t = current() ?: return
        SongPlayer.onLocalState(t, exo?.playWhenReady == true, position(), duration())
    }
}
