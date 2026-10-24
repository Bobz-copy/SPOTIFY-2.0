package app.myspoti.host.android

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The engine's own log, kept in memory so a tester can hand it back as a file.
 *
 * The WebView bridge is invisible on a phone: the only evidence of a refused playback
 * command (`402 COMMAND_FAILED`) or of a sign-in that stalls is these lines, and until now
 * they were reachable only through `adb logcat` ([WebPlayerHost.LOG]) — which the person
 * reporting the bug cannot run. Every line is mirrored to Logcat with the very same text
 * ([log]) and kept in a bounded ring buffer that [export] writes to a shareable file, so
 * the report a tester sends is the app's own log and nothing else has to change.
 */
object Logbook {
    /**
     * Only the tail is kept: a report is read from the end, and an unbounded buffer would
     * grow for the whole life of the process (the page can run for hours).
     */
    private const val MAX_LINES = 6000

    /** Guarded by its own monitor: [log] is called from the main thread, OkHttp threads and coroutines. */
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * Records [line] and mirrors it to Logcat with the same message, so the exported report
     * and `adb logcat -s Myspoti` show the same thing. Safe from any thread.
     */
    fun log(line: String) {
        android.util.Log.d(WebPlayerHost.LOG, line)
        synchronized(lines) {
            lines.addLast("${stamp.format(Date())} $line")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /**
     * What the report's header says of this session, kept apart from the log (which may have cut
     * the line long ago): the account, the network, the devices, the registration... Set from
     * where it is known, the latest value wins.
     */
    private val facts = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun fact(key: String, value: String) { facts[key] = value }

    /** More header lines the app knows of (its extensions), asked when the report is written. */
    @Volatile var headerExtras: (() -> List<Pair<String, String>>)? = null

    /** The buffered lines, oldest first. */
    fun recent(): String = synchronized(lines) { lines.joinToString("\n") }

    /**
     * Writes the report (device and version header, then [recent]) to the app's own external
     * files directory, so it can be handed to the share sheet through the FileProvider.
     * Returns the file for the caller to share.
     */
    fun export(context: Context): File {
        // The external dir is what a FileProvider can expose; the internal one is the
        // fallback for a device with no external storage.
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "diagnostic-${fileStamp.format(Date())}.txt")
        file.writeText(report(context))
        return file
    }

    private val FACT_ORDER = listOf("account", "network", "registration", "devices", "relay route")

    private fun report(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = info?.versionName ?: "?"
        val versionCode = info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: 0L
        // The player's page URL tells which Spotify screen the app was on; read only when the
        // runtime started, or the not-yet-initialized host would crash the export.
        val pageUrl = if (MyspotiRuntime.isStarted) runCatching { MyspotiRuntime.host.url.value }.getOrNull() else null
        val tail = synchronized(lines) { lines.toList() }
        val webView = runCatching { android.webkit.WebView.getCurrentWebViewPackage() }.getOrNull()
        val widevine = if (MyspotiRuntime.isStarted) runCatching {
            if (MyspotiRuntime.spotify.player.drmMissing.value) "missing (nothing Spotify streams can play)" else null
        }.getOrNull() else null
        val header = buildList {
            add("app" to "$versionName ($versionCode)")
            add("android" to "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            add("device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            add("webview" to (webView?.let { "${it.packageName} ${it.versionName}" } ?: "?"))
            add("locale" to Locale.getDefault().toLanguageTag())
            if (!pageUrl.isNullOrEmpty()) add("page" to pageUrl)
            for (key in FACT_ORDER) facts[key]?.let { add(key to it) }
            facts.keys.filter { it !in FACT_ORDER }.sorted().forEach { add(it to facts.getValue(it)) }
            add("widevine" to (widevine ?: facts["widevine"] ?: "not reported missing"))
            runCatching { headerExtras?.invoke() }.getOrNull()?.let { addAll(it) }
        }
        // Masked and cut down (DiagnosticDigest): small enough to paste, nothing that opens an account.
        return buildString {
            appendLine("Myspoti diagnostic report")
            header.forEach { (k, v) -> appendLine("${k.padEnd(12)}: ${app.myspoti.spotify.report.DiagnosticDigest.mask(v)}") }
            appendLine()
            appendLine("--- log ---")
            append(app.myspoti.spotify.report.DiagnosticDigest.digest(tail))
        }
    }
}
