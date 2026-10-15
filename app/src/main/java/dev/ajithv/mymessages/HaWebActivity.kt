package dev.ajithv.mymessages

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Frame-less, full-screen WebView of the Home Assistant frontend (DEF_HA_URL).
 * Only reachable from the Home Assistant button on the main screen or in the notification
 * (not via MQTT). Opens over the lock screen when triggered there.
 *
 * Auth persists across launches with zero extra work: HA stores its long-lived token in the
 * page's localStorage (not a cookie), and WebView persists localStorage to disk when DOM
 * storage is enabled — so after the first sign-in the session is restored automatically.
 * Cookies are persisted too (CookieManager, flushed on pause).
 */
class HaWebActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        goImmersive()

        web = WebView(this)
        setContentView(web)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // HA keeps its auth token here -> persists login
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        // Keep all navigation inside this WebView, and drop the login page from history once
        // we land on an authenticated page — so Back can never return to the login screen.
        // (Login only appears when actually unauthenticated; the token in localStorage persists.)
        web.webViewClient = object : WebViewClient() {
            private var wasOnAuth = false
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val onAuth = url?.contains("/auth/") == true
                if (wasOnAuth && !onAuth) view.clearHistory()   // login -> app: forget the login page
                wasOnAuth = onAuth
            }
        }
        web.webChromeClient = WebChromeClient()

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(BuildConfig.DEF_HA_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()   // persist cookies to disk
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        goImmersive()
    }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onDestroy() {
        try {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
