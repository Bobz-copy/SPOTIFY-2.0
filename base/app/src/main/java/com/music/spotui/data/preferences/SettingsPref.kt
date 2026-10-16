package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// Trimmed from Spotui: stream quality, crossfade, YouTube and web-playback settings
// are gone (the Web Player engine streams Spotify's own audio).

private const val PREF = "settings_prefs"
private const val KEY_LIBRARY_GRID = "library_grid_view"
private const val KEY_LYRIC_TRANSLATE_LANG = "lyric_translate_lang"
private const val KEY_LYRICS_EXTRA = "lyrics_extra_sources"

/** Word-synced lyrics from third-party Apple Music mirrors (LyricsPlus). Off by default. */
fun isLyricsExtraSources(c: Context): Boolean = prefs(c).getBoolean(KEY_LYRICS_EXTRA, false)
fun setLyricsExtraSources(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LYRICS_EXTRA, v).apply()

private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

/** The bottom bar: solid black (the default), or fading into the list. Read by the bar as state. */
object SolidNavBar {
    private const val KEY = "solid_nav_bar"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** Library layout: false = rows (default), true = Spotify-style 3-column grid. */
fun isLibraryGridView(c: Context): Boolean = prefs(c).getBoolean(KEY_LIBRARY_GRID, false)
fun setLibraryGridView(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LIBRARY_GRID, v).apply()

fun getLyricTranslateLang(c: Context): String =
    prefs(c).getString(KEY_LYRIC_TRANSLATE_LANG, "").orEmpty()

fun setLyricTranslateLang(c: Context, code: String) =
    prefs(c).edit().putString(KEY_LYRIC_TRANSLATE_LANG, code.trim()).apply()
