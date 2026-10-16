package com.music.spotui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own Logcat lines of the last minutes, for beta testers to paste in a bug report.
 * An app can always read its own process's logs; nothing leaves the phone unless the user
 * copies or shares the text. Tokens, e-mails and account ids are masked.
 */
object BetaLogs {
    const val MINUTES = 3
    /** Clipboard and share sheets refuse very large texts. */
    private const val MAX_CHARS = 400_000

    suspend fun recent(minutes: Int = MINUTES): String = withContext(Dispatchers.IO) {
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(System.currentTimeMillis() - minutes * 60_000L))
        val text = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}", "-T", since)
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
        }.getOrElse { "logcat unavailable: ${it.message}" }
        val header = "Myspoti ${app.myspoti.engine.BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) · " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · last $minutes min\n\n"
        header + sanitize(text).takeLast(MAX_CHARS)
    }

    private val rules = listOf(
        Regex("Bearer\\s+[A-Za-z0-9._\\-]+") to "Bearer <hidden>",
        Regex("\"(accessToken|access_token|clientToken|client_token|token|refresh_token)\"\\s*:\\s*\"[^\"]*\"") to "\"$1\":\"<hidden>\"",
        Regex("(access_token|token)=[^&\\s\"]+") to "$1=<hidden>",
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "<email>",
        Regex("\"(email|username|userName|birthdate|country)\"\\s*:\\s*\"[^\"]*\"") to "\"$1\":\"<hidden>\"",
        Regex("spotify:user:[^\\s\"',)]+") to "spotify:user:<user>",
        Regex("\\b([0-9a-f]{6})[0-9a-f]{14,}\\b") to "$1…",
        Regex("[A-Za-z0-9+/_\\-]{80,}={0,2}") to "<long value>",
    )

    fun sanitize(text: String): String = rules.fold(text) { t, (r, s) -> r.replace(t, s) }
}
