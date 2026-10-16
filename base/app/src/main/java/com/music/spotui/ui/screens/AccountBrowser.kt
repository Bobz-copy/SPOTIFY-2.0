package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity

/**
 * Spotify's account pages (plan, privacy, apps, devices) in a browser that shares the
 * app's sign-in: WebViews of one app share cookies, so no second login.
 */
class AccountBrowser : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
            loadUrl(intent.getStringExtra(EXTRA_URL) ?: ACCOUNT_URL)
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACCOUNT_URL = "https://www.spotify.com/account/overview/"
        private const val EXTRA_URL = "url"

        fun open(context: Context, url: String) {
            context.startActivity(Intent(context, AccountBrowser::class.java)
                .putExtra(EXTRA_URL, url)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }
}
