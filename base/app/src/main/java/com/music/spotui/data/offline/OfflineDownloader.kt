package com.music.spotui.data.offline

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import com.music.spotui.video.AudioAlign
import com.music.spotui.video.InterruptibleHttp
import com.music.spotui.video.MusicVideoController
import com.music.spotui.video.RangeAudioDownload
import com.music.spotui.video.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads songs for offline listening. Spotify only gives the track (title, artist,
 * duration, ISRC); the audio comes from YouTube Music, the same way music videos are
 * found:
 *
 * 1. the YouTube Music "song" of the track (the label's "Artist - Topic" upload of the
 *    same master), else a plain "official audio" upload, duration within 3 s;
 * 2. the best audio stream as YouTube serves it (Opus or AAC, never re-encoded);
 * 3. a check against the recording's official 30 s preview (Deezer, looked up by ISRC):
 *    the two audios are aligned like a clip's soundtrack, which rejects a live take, a
 *    remix or a cover. Without a preview, only the "Topic" upload is trusted.
 *
 * Nothing that fails the check is kept: the song is marked unavailable instead.
 */
object OfflineDownloader {
    private const val TAG = "Offline"
    private const val WORKERS = 2
    private const val MIN_SCORE = 0.5

    data class Request(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: String,
        val durationMs: Long,
        val coverUrl: String,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", trackId).put("title", title).put("artist", artist)
            .put("album", album).put("albumId", albumId).put("duration", durationMs).put("cover", coverUrl)

        companion object {
            fun of(s: SongsModel) = Request(
                trackId = s.spotifyTrackId, title = s.title, artist = s.singer, album = s.album,
                albumId = s.albumId, durationMs = s.durationMs.toLong(), coverUrl = s.coverUri,
            )
            fun fromJson(o: JSONObject) = Request(
                o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optString("albumId"), o.optLong("duration"), o.optString("cover"),
            )
        }
    }

    private class Unavailable(message: String) : Exception(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Songs queued, downloading, or whose download did not succeed (for the Downloaded list). */
    val requests: SnapshotStateMap<String, Request> = mutableStateMapOf()
    private val queue = Channel<Request>(Channel.UNLIMITED)
    /** Requests not finished yet, in order (persisted: a restart resumes them). */
    private val pending = LinkedHashMap<String, Request>()
    private val running = ConcurrentHashMap<String, Job>()
    // One decode + alignment at a time: they are the CPU- and memory-heavy part.
    private val checkLock = Mutex()
    @Volatile private var started = false

    private fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            repeat(WORKERS) { scope.launch { for (r in queue) work(r) } }
        }
    }

    /** Re-queues what was waiting when the app last stopped. */
    fun resumePending() {
        val saved = runCatching {
            val a = JSONArray(OfflineStore.pendingFile.readText())
            (0 until a.length()).map { Request.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) enqueueAll(saved)
    }

    fun enqueue(songs: List<SongsModel>) = enqueueAll(songs.filter { it.spotifyTrackId.isNotBlank() }.map(Request::of))

    private fun enqueueAll(list: List<Request>) {
        val added = synchronized(pending) {
            list.filter { r ->
                !OfflineStore.isDownloaded(r.trackId) && pending.putIfAbsent(r.trackId, r) == null
            }.also { if (it.isNotEmpty()) savePending() }
        }
        if (added.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            added.forEach { requests[it.trackId] = it; OfflineStore.status[it.trackId] = OfflineStatus.Queued }
        }
        start()
        added.forEach { queue.trySend(it) }
    }

    /** Stops a queued or running download (and forgets a failed one). */
    fun cancel(trackId: String) {
        synchronized(pending) { if (pending.remove(trackId) != null) savePending() }
        running.remove(trackId)?.cancel()
        OfflineStore.status.remove(trackId)
        requests.remove(trackId)
    }

    /** Tries a failed song again. */
    fun retry(trackId: String) {
        val r = requests[trackId] ?: return
        enqueueAll(listOf(r))
    }

    /** Removes the song: the file if downloaded, else its download. */
    fun remove(trackId: String) {
        cancel(trackId)
        OfflineStore.remove(trackId)
    }

    fun removeAll() {
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        ids.forEach { running.remove(it)?.cancel() }
        running.values.forEach { it.cancel() }
        requests.clear()
        OfflineCollections.removeAll()
        OfflineStore.removeAll()
    }

    private fun savePending() {
        runCatching {
            val a = JSONArray()
            pending.values.forEach { a.put(it.toJson()) }
            OfflineStore.pendingFile.writeText(a.toString())
        }
    }

    private suspend fun setStatus(id: String, s: OfflineStatus?) = withContext(Dispatchers.Main) {
        if (s == null) OfflineStore.status.remove(id) else OfflineStore.status[id] = s
    }

    private suspend fun work(r: Request) {
        // Cancelled while it waited in the queue.
        if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        // Data saver on mobile data: downloads wait for Wi-Fi.
        while (com.music.spotui.data.DataSaver.active) {
            kotlinx.coroutines.delay(5_000)
            if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        }
        val job = scope.launch {
            val outcome: OfflineStatus? = try {
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val t = download(r)
                withContext(Dispatchers.Main) { OfflineStore.add(t); requests.remove(r.trackId) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unavailable) {
                Log.i(TAG, "${r.title}: ${e.message}")
                OfflineStatus.Unavailable
            } catch (t: Throwable) {
                Log.w(TAG, "${r.title} failed", t)
                OfflineStatus.Failed
            }
            synchronized(pending) { pending.remove(r.trackId); savePending() }
            if (outcome != null) setStatus(r.trackId, outcome)
        }
        running[r.trackId] = job
        job.join()
        running.remove(r.trackId, job)
    }

    private suspend fun download(r: Request): OfflineTrack {
        val tmp = OfflineStore.tmpDir
        val audioTmp = File(tmp, "${r.trackId}.part")
        val previewTmp = File(tmp, "${r.trackId}.preview")
        try {
            val meta = spotifyMeta(r)
            val previewLink = previewUrl(meta)
            val preview = previewLink?.let { url ->
                runCatching { httpGet(url, previewTmp); previewTmp }
                    .onFailure { Log.w(TAG, "${meta.title}: preview download failed: $it") }.getOrNull()
            }
            val previewFeatures = preview?.let { f ->
                checkLock.withLock {
                    runCatching { runInterruptible(Dispatchers.Default) { AudioAlign.features(AudioAlign.decode(f)) } }
                        .onFailure { Log.w(TAG, "${meta.title}: preview decode failed: $it") }.getOrNull()
                }
            }
            Log.i(TAG, "${meta.title}: isrc=${meta.isrc ?: "-"} preview=${if (previewFeatures != null) "ok" else if (previewLink != null) "unusable" else "none"}")
            // An empty search (or YouTube's "results may be inappropriate" page) is no match, not an error.
            fun search(what: String, block: () -> YouTubeSource.Hit?): YouTubeSource.Hit? = try {
                block()
            } catch (e: org.schabi.newpipe.extractor.search.SearchExtractor.NothingFoundException) {
                Log.i(TAG, "${meta.title}: $what: nothing found"); null
            }
            val candidates = buildList {
                runInterruptible { search("song") { YouTubeSource.findSong(meta.title, meta.artist, meta.durationMs) } }?.let { add(it to true) }
                runInterruptible { search("audio") { YouTubeSource.findAudio(meta.title, meta.artist, meta.durationMs) } }?.let { add(it to false) }
            }.distinctBy { it.first.url }
            if (candidates.isEmpty()) throw Unavailable("no YouTube match")
            for ((hit, topic) in candidates) {
                currentCoroutineContext().ensureActive()
                // No preview to compare with: only the label's own upload is trusted.
                if (previewFeatures == null && !topic) continue
                val ext = fetchAudio(r.trackId, hit.url, audioTmp)
                val verified = previewFeatures != null
                if (!verified) Log.i(TAG, "${meta.title}: ${hit.name} (${hit.uploader}) kept unverified (Topic upload)")
                if (verified) {
                    val score = checkLock.withLock {
                        val full = runInterruptible(Dispatchers.Default) { AudioAlign.features(AudioAlign.decode(audioTmp)) }
                        val map = AudioAlign.align(previewFeatures!!, full)
                        if (map.segments.isEmpty()) 0.0 else map.score
                    }
                    Log.i(TAG, "${meta.title}: ${hit.name} (${hit.uploader}) score ${"%.2f".format(score)}")
                    if (score < MIN_SCORE) { audioTmp.delete(); continue }
                }
                val file = File(OfflineStore.audioDir, "${r.trackId}.$ext")
                if (!audioTmp.renameTo(file)) throw IOException("could not move the file")
                val cover = runCatching {
                    val c = File(OfflineStore.coverDir, "${r.trackId}.jpg")
                    httpGet(meta.coverUrl, c); c.name
                }.getOrDefault("")
                return OfflineTrack(
                    trackId = r.trackId, title = meta.title, artist = meta.artist, album = meta.album,
                    albumId = meta.albumId, durationMs = meta.durationMs, coverUrl = meta.coverUrl,
                    file = file.name, cover = cover, source = hit.url, verified = verified,
                    bytes = file.length(), addedAt = System.currentTimeMillis(),
                )
            }
            throw Unavailable(if (previewFeatures == null) "no preview and no Topic upload" else "no candidate matched the preview")
        } finally {
            audioTmp.delete(); previewTmp.delete()
        }
    }

    private data class Meta(
        val title: String, val artist: String, val album: String, val albumId: String,
        val durationMs: Long, val coverUrl: String, val isrc: String?,
    )

    /** The request, completed by Spotify's track (ISRC, and anything the list lacked). */
    private suspend fun spotifyMeta(r: Request): Meta {
        val api = app.myspoti.host.android.MyspotiRuntime.api
        val uri = "spotify:track:${r.trackId}"
        val t = runCatching { api.catalog.track(uri) }.onFailure { Log.w(TAG, "${r.title}: Spotify track: $it") }.getOrNull()
        val isrc = runCatching { api.catalog.isrc(uri) }.onFailure { Log.w(TAG, "${r.title}: ISRC: $it") }.getOrNull()
        val cover = r.coverUrl.ifBlank { t?.album?.images?.maxByOrNull { it.width ?: 0 }?.url.orEmpty() }
        return Meta(
            title = r.title.ifBlank { t?.name.orEmpty() },
            artist = r.artist.ifBlank { t?.artists?.joinToString(", ") { it.name }.orEmpty() },
            album = r.album.ifBlank { t?.album?.name.orEmpty() },
            albumId = r.albumId.ifBlank { t?.album?.uri?.substringAfterLast(':').orEmpty() },
            durationMs = r.durationMs.takeIf { it > 0 } ?: t?.durationMs ?: 0L,
            coverUrl = cover,
            isrc = isrc?.let(::normalizeIsrc),
        ).also { if (it.title.isBlank() || it.artist.isBlank()) throw Unavailable("no track metadata") }
    }

    /** The official 30 s preview of the recording: by ISRC, else by artist + title + duration. */
    private suspend fun previewUrl(m: Meta): String? = runInterruptible {
        runCatching {
            if (m.isrc != null) {
                val o = JSONObject(httpText("https://api.deezer.com/track/isrc:${m.isrc}"))
                o.optString("preview").takeIf { !o.has("error") && it.startsWith("http") }?.let { return@runCatching it }
            }
            val main = m.artist.substringBefore(",").trim()
            val url = "https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", "$main ${m.title}").build().toString()
            val data = JSONObject(httpText(url)).optJSONArray("data") ?: JSONArray()
            pickPreview((0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.let {
                    PreviewHit(it.optString("preview"), it.optString("title"), it.optJSONObject("artist")?.optString("name").orEmpty(), it.optLong("duration", -1))
                }
            }, m.title, main, m.durationMs)
        }.getOrNull()
    }

    private fun httpText(url: String): String =
        InterruptibleHttp.read(YouTubeSource.http.newCall(okhttp3.Request.Builder().url(url).build())) {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body?.string().orEmpty()
        }

    private suspend fun httpGet(url: String, out: File) = runInterruptible {
        InterruptibleHttp.read(YouTubeSource.http.newCall(okhttp3.Request.Builder().url(url).build())) { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty body")
            out.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    /** Downloads the best audio stream of [url] into [out]; returns the file extension. */
    private suspend fun fetchAudio(trackId: String, url: String, out: File): String {
        var info = runInterruptible { YouTubeSource.info(url) }
        var last: Throwable? = null
        for (attempt in 0..1) {
            val streams = rankAudio(info.audioStreams.mapNotNull { a ->
                val ext = when (a.format) { MediaFormat.WEBMA_OPUS -> "webm"; MediaFormat.M4A -> "m4a"; else -> null }
                ext?.let { AudioChoice(it, a.averageBitrate, a.content) }
            })
            if (streams.isEmpty()) throw Unavailable("no audio stream")
            for (a in streams.take(2)) {
                try {
                    fetchWithProgress(trackId, a.url, out)
                    val expected = if (a.bitrate > 0 && info.duration > 0) a.bitrate * 1000L / 8 * info.duration else 0L
                    if (out.length() < 50_000 || (expected > 0 && out.length() < expected * 0.6))
                        throw IOException("${out.length() / 1024} KB, expected ~${expected / 1024} KB")
                    return a.ext
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive()
                    last = t
                }
            }
            // Stream URLs expire or get refused (403): resolve them again once.
            info = runInterruptible { YouTubeSource.info(url, fresh = true) }
        }
        throw last ?: IOException("download failed")
    }

    private suspend fun fetchWithProgress(trackId: String, url: String, out: File) {
        val total = runCatching { url.toHttpUrl().queryParameter("clen")?.toLongOrNull() }.getOrNull()
        val watcher = if (total != null && total > 0) scope.launch {
            while (isActive) {
                setStatus(trackId, OfflineStatus.Downloading((out.length().toFloat() / total).coerceIn(0f, 1f)))
                delay(400)
            }
        } else null
        try {
            RangeAudioDownload.fetch(YouTubeSource.http, url, out, MusicVideoController.streamHeaders(url))
        } finally {
            watcher?.cancel()
        }
        setStatus(trackId, OfflineStatus.Downloading(-1f))
    }

    // ── Pure helpers (unit-tested) ──

    data class AudioChoice(val ext: String, val bitrate: Int, val url: String)

    /** Highest bitrate first; at equal bitrate Opus (better quality per bit) first. */
    fun rankAudio(streams: List<AudioChoice>): List<AudioChoice> =
        streams.sortedWith(compareByDescending<AudioChoice> { it.bitrate }.thenBy { if (it.ext == "webm") 0 else 1 })

    /** "US-UM7-17-03861" / "usum71703861" → "USUM71703861"; null when not an ISRC. */
    fun normalizeIsrc(raw: String): String? =
        raw.uppercase().filter { it.isLetterOrDigit() }.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}").matches(it) }

    data class PreviewHit(val url: String, val title: String, val artist: String, val durationSec: Long)

    private fun folded(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").lowercase()

    /** Title without "(feat. …)", "[Remastered]", " - Radio Edit"…, letters and digits only. */
    internal fun titleKey(s: String) = folded(s)
        .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .replace(Regex("""\s+-\s+.*$"""), " ")
        .replace(Regex("""\b(?:feat|ft)\.?\s.*$"""), " ")
        .filter { it.isLetterOrDigit() }

    /** The search result that is the same song: title, main artist, and duration within 3 s. */
    fun pickPreview(results: List<PreviewHit>, title: String, artist: String, durationMs: Long): String? {
        if (durationMs <= 0) return null
        val t = titleKey(title)
        val a = folded(artist).filter { it.isLetterOrDigit() }
        return results.firstOrNull { h ->
            h.url.startsWith("http") && h.durationSec > 0 &&
                kotlin.math.abs(h.durationSec * 1000 - durationMs) <= 3000 &&
                titleKey(h.title) == t && folded(h.artist).filter { it.isLetterOrDigit() } == a
        }?.url
    }
}
