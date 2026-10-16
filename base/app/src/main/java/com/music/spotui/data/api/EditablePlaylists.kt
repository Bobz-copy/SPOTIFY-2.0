package com.music.spotui.data.api

import app.myspoti.api.best
import app.myspoti.host.android.MyspotiRuntime

/** A playlist the user can add tracks to, for the "Add to playlist" pickers. */
data class EditablePlaylist(val id: String, val name: String, val trackCount: Int, val imageUrl: String?)

object EditablePlaylists {
    /** The user's own and collaborative playlists, in library order. */
    suspend fun load(): List<EditablePlaylist> =
        runCatching { MyspotiRuntime.api.playlists.editable() }.getOrDefault(emptyList()).map {
            EditablePlaylist(it.uri.substringAfterLast(':'), it.name, it.trackCount, it.images.best(160)?.url)
        }
}
