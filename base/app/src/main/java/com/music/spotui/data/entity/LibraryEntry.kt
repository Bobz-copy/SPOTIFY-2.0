package com.music.spotui.data.entity

/**
 * A single row in "Your Library": either a saved album or a saved/followed
 * playlist. [spotifyId] is the Spotify string id used to open the detail screen
 * (playlistRoute for playlists, name-based albumRoute for albums).
 */
@kotlinx.serialization.Serializable
data class LibraryEntry(
    val spotifyId: String,
    val name: String,
    val subtitle: String,
    val coverUri: String,
    val isPlaylist: Boolean,
    val artists: String = "",
    /** A Spotify library folder (its full `spotify:user:…:folder:…` uri); empty otherwise. */
    val folderUri: String = "",
    /** Playlists and folders inside a folder. */
    val childCount: Int = 0,
) {
    val isFolder: Boolean get() = folderUri.isNotBlank()
}
