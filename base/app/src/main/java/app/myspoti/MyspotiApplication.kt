package app.myspoti

import android.app.Application
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import app.myspoti.host.android.MediaLibrary
import app.myspoti.host.android.PlaybackService
import com.music.spotui.di.AutoBrowser
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starts the Spotify session, the UI singletons and the playback service bindings. */
class MyspotiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        app.myspoti.power.EnergySettings.init(this)
        // Spotify session (hidden Web Player + API), caches, downloads, data saver.
        Graph.init(this)
        SongPlayer.attachEngine(Graph.currentSongState)
        // Headphones or Bluetooth gone: pause rather than play on the speaker.
        com.music.spotui.di.NoisyAudio.register(this)
        // Offline listens kept for Wrapped reporting are replayed once signed in.
        com.music.spotui.data.offline.WrappedSync.resumePending()
        PlaybackService.library = object : MediaLibrary {
            override suspend fun children(context: Context, parentId: String): List<MediaBrowserCompat.MediaItem> =
                AutoBrowser.children(context, parentId)
            override suspend fun search(query: String): List<MediaBrowserCompat.MediaItem> = AutoBrowser.search(query)
        }
        prefetch()
    }

    /**
     * Once the Web Player is signed in, the library and Liked Songs load in the background
     * (the home feed is loaded by the first screen): the tabs open on fresh content.
     */
    private fun prefetch() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            app.myspoti.host.android.MyspotiRuntime.api.ready.first { it }
            if (!getSharedPreferences("myspoti_prefs", MODE_PRIVATE).getBoolean("LoggedIn", false)) return@launch
            val api = com.music.spotui.data.api.Api()
            launch { runCatching { api.getLibraryRoot().collect {} } }
            launch { runCatching { api.getLikedPage().collect {} } }
        }
    }
}
