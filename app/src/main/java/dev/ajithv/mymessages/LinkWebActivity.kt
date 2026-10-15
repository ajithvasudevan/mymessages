package dev.ajithv.mymessages

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * A plain in-app WebView used to open a link found in a notification (see [NotifActivity]) — instead of
 * handing the URL to the system browser. Opens over the lock screen (the notification overlay it's
 * launched from is itself shown there) and is its own task, so closing it never reveals another
 * LockDash screen. Navigation stays inside the WebView; only http/https are loaded here (other schemes
 * — mailto:, tel:, intent: — are handed to the system). A ✕ chip (or Back) closes it.
 */
class LinkWebActivity : Activity() {

    companion object { const val EXTRA_URL = "url" }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        goImmersive()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Close chip (the notification overlay uses the same ✕-only-close convention).
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(0x66000000)   // faint scrim so it's legible over any page
            setOnClickListener { finish() }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )
        setContentView(root)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url ?: return false
                val scheme = u.scheme?.lowercase()
                // http/https stay in-app; anything else (mailto/tel/intent/market/...) goes to the OS.
                if (scheme == "http" || scheme == "https") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                } catch (_: Exception) { true }
            }
        }
        web.webChromeClient = WebChromeClient()

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
            web.loadUrl(if (url.isNotEmpty()) url else "about:blank")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isNotEmpty()) web.loadUrl(url)
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
        CookieManager.getInstance().flush()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        goImmersive()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
