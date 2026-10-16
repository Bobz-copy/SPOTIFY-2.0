package com.music.spotui.di

import app.myspoti.host.android.MyspotiRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Session-wide actions the screens trigger. */
object SessionActions {
    private val _signedOut = MutableStateFlow(false)
    /** True from a logout until the next sign-in: the activity shows the login page, then starts over. */
    val signedOut: StateFlow<Boolean> = _signedOut

    /** Signs out of Spotify in the Web Player; the login page shows next. */
    fun logout() {
        _signedOut.value = true
        MyspotiRuntime.host.logout()
    }

    /** The new account is signed in. */
    fun signedIn() { _signedOut.value = false }
}
