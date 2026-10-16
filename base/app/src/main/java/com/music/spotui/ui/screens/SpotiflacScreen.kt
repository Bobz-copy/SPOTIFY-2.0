package com.music.spotui.ui.screens

/*
 * Ported from Spotui's SpotiflacVerifyScreen (https://github.com/Spotui/Spotui), GPL-3.0,
 * and adapted to Myspoti (own theme, strings, navigation).
 */

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.myspoti.engine.R
import com.music.spotui.lossless.LosslessRegistry
import com.music.spotui.ui.theme.AppBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * SpotiFLAC verification, the way the official client does it: bootstrap → the
 * service's own hosted challenge page (the user solves the Turnstile themselves)
 * → redirect with a grant → exchange the grant for a signing session.
 *
 * The app only loads the page and catches the redirect. The session is what gives
 * the lossless (FLAC) downloads their most reliable source. With a session the
 * screen shows the status; without it the WebView verification runs.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotiflacScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var verified by remember { mutableStateOf(com.music.spotui.data.preferences.hasSpotiflacSession(context)) }
    var message by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    val leave: () -> Unit = onClose

    fun res(id: Int): String = context.getString(id)

    // Reused so a retry keeps the same install; the service binds the session to it.
    val installId = remember {
        com.music.spotui.data.preferences.getSpotiflacSession(context)?.installId?.takeIf { it.isNotBlank() }
            ?: java.security.SecureRandom().let { r -> (1..16).joinToString("") { "%02x".format(r.nextInt(256)) } }
    }
    val state = remember(attempt) {
        java.security.SecureRandom().let { r -> (1..16).joinToString("") { "%02x".format(r.nextInt(256)) } }
    }

    fun finish(grant: String) = scope.launch {
        message = res(R.string.spotiflac_session_creating)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("grant", grant).put("install_id", installId)
                    .put("app_version", APP_VERSION).put("platform", PLATFORM)
                val o = JSONObject(post("${LosslessRegistry.communityVerifyUrl()}/session/exchange", body.toString()))
                val id = o.optString("session_id")
                val secret = o.optString("session_secret")
                require(id.isNotBlank() && secret.isNotBlank())
                com.music.spotui.data.preferences.saveSpotiflacSession(context, id, secret, installId, o.optString("expires_at"))
            }.isSuccess
        }
        if (ok) {
            verified = true
            done = true
            android.widget.Toast.makeText(context, R.string.spotiflac_session_ok, android.widget.Toast.LENGTH_LONG).show()
        } else {
            message = res(R.string.spotiflac_session_failed)
        }
    }

    LaunchedEffect(attempt) {
        if (verified) return@LaunchedEffect
        message = ""
        val challenge = withContext(Dispatchers.IO) {
            runCatching {
                val base = LosslessRegistry.communityVerifyUrl()
                val url = "$base/bootstrap?install_id=$installId&app_version=$APP_VERSION&platform=$PLATFORM"
                JSONObject(get(url)).optString("challenge_url").takeIf { it.startsWith("https://") }
            }.getOrNull()
        }
        if (challenge == null) {
            message = res(R.string.spotiflac_verify_start_failed)
            return@LaunchedEffect
        }
        message = ""
        webView?.loadUrl("$challenge&cb=" + URLEncoder.encode("$CALLBACK?state=$state", "UTF-8"))
        // Never leave the user on a page that silently stalled.
        delay(180_000)
        if (!done && !verified) message = res(R.string.spotiflac_verify_timeout)
    }

    if (verified) {
        SpotiflacStatusScreen(leave)
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(top = 40.dp, bottom = 56.dp),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    webView = this
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (!uri.toString().startsWith(CALLBACK)) return false
                            val grant = uri.getQueryParameter("grant").orEmpty()
                            if (uri.getQueryParameter("state") != state || grant.isBlank()) {
                                message = res(R.string.spotiflac_verify_rejected)
                            } else {
                                finish(grant)
                            }
                            return true
                        }
                    }
                }
            },
        )

        if (message.isNotEmpty()) {
            Text(
                message,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().height(56.dp).background(Color.Black),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry), color = Color.White) }
            TextButton(onClick = leave) { Text(stringResource(R.string.spotiflac_skip), color = Color.White) }
        }

        Box(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(40.dp).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.spotiflac_verify_title),
                color = Color(0xFF00C7B7),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
    }
}

/** The status page when a session exists: state, expiry, forget. */
@Composable
private fun SpotiflacStatusScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.spotiflac_status_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.spotiflac_status_verified), color = Color(0xFFB3B3B3), fontSize = 14.sp)
        val session = com.music.spotui.data.preferences.getSpotiflacSession(context)
        Text(
            stringResource(R.string.spotiflac_session_expires, session?.expiresAt.orEmpty().take(19)),
            color = Color(0xFF00C7B7), fontSize = 13.sp,
        )
        Button(
            onClick = {
                com.music.spotui.data.preferences.clearSpotiflacSession(context)
                onClose()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E), contentColor = Color.White),
            shape = RoundedCornerShape(24.dp),
        ) { Text(stringResource(R.string.spotiflac_forget)) }
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(stringResource(R.string.setup_continue), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
}

// Screen constants; APP_VERSION must stay in step with SpotiflacGated (same signing).
private const val CALLBACK = "spotiflac://session-grant"
private const val APP_VERSION = "4.8.5"
private const val PLATFORM = "android"

// ── HTTP helpers (same shape as Spotui's) ────────────────────────────────────

private fun get(url: String): String = request(url, null)

private fun post(url: String, json: String): String = request(url, json)

private fun request(url: String, json: String?): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 20_000
        setRequestProperty("Accept", "application/json")
        if (json != null) {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
    }
    try {
        if (json != null) conn.outputStream.use { it.write(json.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return text
    } finally {
        conn.disconnect()
    }
}
