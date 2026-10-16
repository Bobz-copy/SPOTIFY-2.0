package com.music.spotui.data.offline

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.LocalTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A song kept for offline listening, in the app's private storage. */
data class OfflineTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val durationMs: Long,
    val coverUrl: String,
    /** Audio file name in [OfflineStore]'s audio folder. */
    val file: String,
    /** Cover file name in the covers folder, empty when it could not be fetched. */
    val cover: String,
    /** Where the audio came from (YouTube watch URL). */
    val source: String,
    /** The audio was checked against the recording's official preview. */
    val verified: Boolean,
    val bytes: Long,
    val addedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", trackId).put("title", title).put("artist", artist).put("album", album)
        .put("albumId", albumId).put("duration", durationMs).put("coverUrl", coverUrl)
        .put("file", file).put("cover", cover).put("source", source).put("verified", verified)
        .put("bytes", bytes).put("added", addedAt)

    companion object {
        fun fromJson(o: JSONObject) = OfflineTrack(
            trackId = o.getString("id"), title = o.optString("title"), artist = o.optString("artist"),
            album = o.optString("album"), albumId = o.optString("albumId"), durationMs = o.optLong("duration"),
            coverUrl = o.optString("coverUrl"), file = o.getString("file"), cover = o.optString("cover"),
            source = o.optString("source"), verified = o.optBoolean("verified"), bytes = o.optLong("bytes"),
            addedAt = o.optLong("added"),
        )
    }
}

/** Where a download stands, for the badges and buttons. */
sealed interface OfflineStatus {
    data object Queued : OfflineStatus
    /** [progress] 0..1, or -1 while the source is looked up / checked. */
    data class Downloading(val progress: Float) : OfflineStatus
    data object Done : OfflineStatus
    /** No recording that matches the Spotify track was found. */
    data object Unavailable : OfflineStatus
    /** Network or storage error: can be tried again. */
    data object Failed : OfflineStatus
}

/**
 * Downloaded songs. Files live in the app's private storage (filesDir/offline), so
 * only Myspoti plays them: they are not in the phone's Music folder or MediaStore.
 */
object OfflineStore {
    private lateinit var root: File
    private val lock = Any()

    /** Downloaded songs by Spotify track id; every screen reads this. */
    val tracks: SnapshotStateMap<String, OfflineTrack> = mutableStateMapOf()
    /** Songs in the download queue, or whose last attempt did not succeed. */
    val status: SnapshotStateMap<String, OfflineStatus> = mutableStateMapOf()

    fun init(context: Context) {
        if (::root.isInitialized) return
        root = File(context.filesDir, "offline")
        audioDir.mkdirs(); coverDir.mkdirs()
        // The index lists only finished files; anything else in the folders is a leftover.
        val list = readIndex()
        val kept = list.filter { File(audioDir, it.file).isFile }
        kept.forEach { tracks[it.trackId] = it }
        if (kept.size != list.size) writeIndex()
        val names = kept.map { it.file }.toSet()
        audioDir.listFiles()?.forEach { if (it.name !in names) it.delete() }
        val covers = kept.map { it.cover }.toSet()
        coverDir.listFiles()?.forEach { if (it.name !in covers) it.delete() }
    }

    val audioDir: File get() = File(root, "audio")
    val coverDir: File get() = File(root, "covers")
    private val indexFile: File get() = File(root, "index.json")
    internal val pendingFile: File get() = File(root, "pending.json")
    val tmpDir: File get() = File(root, "tmp").apply { mkdirs() }

    fun isDownloaded(trackId: String) = trackId.isNotBlank() && tracks.containsKey(trackId)

    /** What the UI shows for a track: downloaded, in the queue, failed… or nothing. */
    fun statusOf(trackId: String): OfflineStatus? =
        if (isDownloaded(trackId)) OfflineStatus.Done else status[trackId]

    fun add(t: OfflineTrack) {
        synchronized(lock) {
            tracks[t.trackId] = t
            status.remove(t.trackId)
            writeIndex()
        }
    }

    fun remove(trackId: String) {
        synchronized(lock) {
            val t = tracks.remove(trackId) ?: return
            File(audioDir, t.file).delete()
            if (t.cover.isNotBlank()) File(coverDir, t.cover).delete()
            writeIndex()
        }
    }

    fun removeAll() {
        synchronized(lock) {
            tracks.clear()
            status.clear()
            audioDir.listFiles()?.forEach { it.delete() }
            coverDir.listFiles()?.forEach { it.delete() }
            writeIndex()
        }
    }

    fun totalBytes(): Long = tracks.values.sumOf { it.bytes }

    /** A working internet connection (else downloaded songs play from the phone). */
    fun isOnline(context: Context): Boolean {
        // The data saver on mobile data: the app behaves as offline.
        if (com.music.spotui.data.DataSaver.active) return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        // Not "validated" is not offline: some VPNs, private DNS and Wi-Fi networks never pass
        // Android's check, and treating them as offline played only the downloaded songs of a
        // list, skipping all the others (issue #10).
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Downloaded songs, newest first. */
    fun list(): List<OfflineTrack> = tracks.values.sortedByDescending { it.addedAt }

    fun audioFile(t: OfflineTrack) = File(audioDir, t.file)
    fun coverFile(t: OfflineTrack): File? = t.cover.takeIf { it.isNotBlank() }?.let { File(coverDir, it) }?.takeIf { it.isFile }

    /** For the local player (player, mini player, notification…). */
    fun toLocalTrack(t: OfflineTrack) = LocalTrack(
        id = localIdOf(t.trackId),
        uri = Uri.fromFile(audioFile(t)),
        title = t.title,
        artist = t.artist,
        album = t.album,
        durationMs = t.durationMs,
        artUri = coverFile(t)?.let { Uri.fromFile(it) } ?: Uri.parse(t.coverUrl),
    )

    fun toSong(t: OfflineTrack) = SongsModel(
        id = "track:${t.trackId}".hashCode() and 0x7fffffff,
        title = t.title, album = t.album, singer = t.artist,
        coverUri = coverFile(t)?.let { Uri.fromFile(it).toString() } ?: t.coverUrl,
        url = "spotify:track:${t.trackId}", spotifyTrackId = t.trackId,
        durationMs = t.durationMs.toInt(), albumId = t.albumId,
    )

    /** Negative, so it never collides with a MediaStore id (Local Files). */
    fun localIdOf(trackId: String): Long = -1L - (trackId.hashCode().toLong() and 0x7fffffffL)

    /** The downloaded track a local id plays, or null for files outside the downloads. */
    fun byLocalId(id: Long): OfflineTrack? =
        tracks.values.firstOrNull { localIdOf(it.trackId) == id }

    private fun readIndex(): List<OfflineTrack> = runCatching {
        val a = JSONArray(indexFile.readText())
        (0 until a.length()).mapNotNull { runCatching { OfflineTrack.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun writeIndex() {
        val a = JSONArray()
        tracks.values.forEach { a.put(it.toJson()) }
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(indexFile)
    }
}
