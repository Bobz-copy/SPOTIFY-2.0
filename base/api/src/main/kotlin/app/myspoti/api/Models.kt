package app.myspoti.api

/** A Spotify-style uri such as `spotify:track:{id}`. Kept as a string so every kind fits. */
typealias Uri = String

data class Image(val url: String, val width: Int? = null, val height: Int? = null)

/** Picks the smallest image at least [minPx] wide, or the largest one. */
fun List<Image>.best(minPx: Int): Image? =
    filter { (it.width ?: 0) >= minPx }.minByOrNull { it.width ?: 0 } ?: maxByOrNull { it.width ?: 0 }

data class ArtistRef(val uri: Uri, val name: String)

data class AlbumRef(val uri: Uri, val name: String, val images: List<Image> = emptyList())

data class Track(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val artists: List<ArtistRef>,
    val album: AlbumRef?,
    val explicit: Boolean = false,
    val playable: Boolean = true,
    /** Position in a playlist; needed to remove or move this exact row. */
    val uid: String? = null,
)

data class Album(
    val uri: Uri,
    val name: String,
    val artists: List<ArtistRef>,
    val images: List<Image>,
    val type: String,
    val releaseDate: String?,
    val trackCount: Int,
    val label: String? = null,
)

data class Artist(
    val uri: Uri,
    val name: String,
    val images: List<Image>,
    val followers: Long? = null,
    val monthlyListeners: Long? = null,
    val verified: Boolean = false,
)

data class Playlist(
    val uri: Uri,
    val name: String,
    val description: String?,
    val owner: String?,
    val images: List<Image>,
    val trackCount: Int,
    val editable: Boolean = false,
    val ownerUri: Uri? = null,
    val ownerImages: List<Image> = emptyList(),
    val followers: Long? = null,
)

/** One page of a longer list. [nextOffset] is null on the last page. */
data class Page<T>(val items: List<T>, val offset: Int, val total: Int, val nextOffset: Int?)

data class SearchResults(
    val tracks: Page<Track>,
    val artists: Page<Artist>,
    val albums: Page<Album>,
    val playlists: Page<Playlist>,
)

enum class Repeat { OFF, CONTEXT, TRACK }

enum class ItemKind { TRACK, EPISODE, AD }

/** What the player is on now. Ads never reach the UI as the current item. */
data class NowPlaying(
    val kind: ItemKind,
    val uri: Uri,
    val uid: String?,
    val name: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef?,
    val imageUrl: String?,
    val durationMs: Long,
    val explicit: Boolean = false,
)

data class PlayerRestrictions(
    val canPause: Boolean = true,
    val canResume: Boolean = true,
    val canSeek: Boolean = true,
    val canSkipNext: Boolean = true,
    val canSkipPrevious: Boolean = true,
)

data class PlayerState(
    val item: NowPlaying?,
    val contextUri: Uri?,
    /** "Liked Songs", the playlist or album name... */
    val contextName: String? = null,
    val previous: NowPlaying? = null,
    val next: NowPlaying? = null,
    val isPaused: Boolean,
    val isBuffering: Boolean,
    /** Position at [timestampMs] (epoch). Use [positionAt] for a live value. */
    val positionMs: Long,
    val timestampMs: Long,
    val speed: Double,
    val shuffle: Boolean,
    val repeat: Repeat,
    val restrictions: PlayerRestrictions,
) {
    fun positionAt(nowMs: Long): Long =
        if (isPaused || item == null) positionMs
        else (positionMs + (nowMs - timestampMs) * speed).toLong().coerceIn(0, item.durationMs)
}

data class Queue(val current: NowPlaying?, val queued: List<NowPlaying>, val nextUp: List<NowPlaying>)

data class TopTrack(val track: Track, val playcount: Long?)

/** Everything the artist screen shows, in one call. */
data class ArtistPage(
    val artist: Artist,
    val biography: String?,
    val headerImages: List<Image>,
    val topTracks: List<TopTrack>,
    val popularReleases: List<Album>,
    val appearsOn: List<Album>,
    val related: List<Artist>,
)
