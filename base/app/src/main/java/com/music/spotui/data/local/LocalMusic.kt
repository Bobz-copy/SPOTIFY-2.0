package com.music.spotui.data.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An audio file on this device (MediaStore), played by [com.music.spotui.di.LocalPlayer]. */
data class LocalTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: Uri,
)

/** The device's music files, read through MediaStore (no file access beyond audio). */
object LocalMusic {
    /** Audio read permission for this Android version. */
    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Volatile private var cache: List<LocalTrack>? = null

    suspend fun tracks(context: Context, refresh: Boolean = false): List<LocalTrack> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()
        cache?.takeIf { !refresh }?.let { return@withContext it }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID,
        )
        val out = ArrayList<LocalTrack>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000",
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val artist = c.getString(iArtist)?.takeIf { it != MediaStore.UNKNOWN_STRING }.orEmpty()
                out += LocalTrack(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(iTitle).orEmpty(),
                    artist = artist,
                    album = c.getString(iAlbum).orEmpty(),
                    durationMs = c.getLong(iDur),
                    artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(iAlbumId)),
                )
            }
        }
        cache = out
        out
    }
}
