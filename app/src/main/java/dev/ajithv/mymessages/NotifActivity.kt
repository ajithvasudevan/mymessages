package dev.ajithv.mymessages

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen "new notification" screen shown over the lock screen. Renders the sending app,
 * sender/title and body in a large, user-configurable font on user-configurable colours, and
 * plays a user-picked sound at a user-set volume that is INDEPENDENT of the system volume.
 *
 * The data comes in the [DashService.EXTRA_PAYLOAD] JSON ({"app","title","text"}), built by
 * [NotifListener]. Everything visual/audible is read from [Prefs]. Auto-closes after
 * [AUTO_CLOSE_SECS], on a tap anywhere / the ✕, or on an MQTT `lockdash/dismiss`.
 */
class NotifActivity : Activity() {

    companion object {
        const val TAG = "NotifActivity"
        const val SCREEN = "notif"
        const val COMMIT_FRACTION = 0.2f // drag >= 20% of the pane to move to the next/prev message
        const val SETTLE_MS = 200L       // spring-to-rest animation after release
        @Volatile var visible = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { Log.i(TAG, "auto-close"); finish() }

    // Interactive page-drag (finger-following) state. `capture` = this gesture has been claimed for
    // paging (the message pane follows the finger); the current pane is a live-view snapshot in
    // `dragCur`, the revealed neighbour a snapshot in `dragInc`, both inside `dragLayer`.
    private var downX = 0f; private var downY = 0f
    private var decided = false; private var capture = false
    private var dragAxis = 1        // 1 horizontal, 2 vertical (set when captured)
    private var dragLayer: FrameLayout? = null
    private var dragCur: ImageView? = null
    private var dragInc: ImageView? = null
    private var dragCurBmp: Bitmap? = null
    private val snapCache = HashMap<Int, Bitmap>()   // neighbour index -> its rendered snapshot
    private val tableScrolls = ArrayList<HorizontalScrollView>()   // wide monospace blocks that scroll H

    // Pinch-to-zoom the shared font size (same Prefs.fontSp the A−/A+ buttons + main-UI slider control).
    private lateinit var scaleDetector: ScaleGestureDetector
    private var pinchSpF = 0f
    private var pinching = false
    private lateinit var longPressDetector: GestureDetector
    private var longPressFired = false

    private var mp: MediaPlayer? = null
    private var audioMgr: AudioManager? = null
    private var savedAlarmVol = -1
    private val alarmStream = AudioManager.STREAM_ALARM

    // History paging: `items` is a filter-filtered snapshot (oldest→newest) taken per launch;
    // `idx` is the shown position. Preview mode (Test pop-up / empty history) shows one payload with
    // no paging.
    private var items: List<JSONObject> = emptyList()
    private var idx = 0
    private var previewMode = false
    // Identity of the currently-shown item, captured in renderCurrent for the long-press menu.
    private var curPkg = ""; private var curApp = ""; private var curCategory = ""
    private var curSender = ""; private var curText = ""
    private var previewItem: JSONObject? = null
    private var playOnLoad = true   // false when opened silently (the persistent-notif "view last" button)
    private var paging = false      // a page-slide animation is running (ignore new swipes/taps meanwhile)
    private val timeFmt = SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault())

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { Log.i(TAG, "dismiss -> finish"); finish() }
    }

    // A late-arriving MessagingStyle image (Telegram) was attached to a history row — refresh if shown.
    private val imgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            onImageUpdated(i?.getLongExtra(NotifListener.EXTRA_TS, 0L) ?: 0L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake + show over the (secure) lock screen without unlocking it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        setContentView(R.layout.activity_notif)
        // Only the ✕ dismisses by touch — tapping anywhere else (text, arrows area) must NOT close.
        findViewById<View>(R.id.notif_close).setOnClickListener { finish() }

        // Long-press anywhere in the message area -> filter / delete context menu (replaces the removed
        // bottom-panel checkboxes + delete button). Detected at the root in dispatchTouchEvent so it fires
        // over blank space too (a ScrollView won't deliver long-click on its empty area); swipe still
        // pages, pinch still zooms.
        longPressDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onLongPress(e: MotionEvent) {
                if (pinching || capture || previewMode || longPressFired) return
                longPressFired = true
                cancelChildren(e)          // stop the in-flight press from also firing a tap/scroll
                showFilterMenu()
            }
        })

        // Swipe to page is handled interactively (finger-following) in [dispatchTouchEvent].
        // Pinch-to-zoom adjusts the shared font size continuously (persisted; re-renders per sp step).
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pinchSpF = Prefs.fontSp(this@NotifActivity).toFloat(); return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinchSpF = (pinchSpF * d.scaleFactor)
                    .coerceIn(Prefs.FONT_MIN_SP.toFloat(), Prefs.FONT_MAX_SP.toFloat())
                val sp = Math.round(pinchSpF)
                if (sp != Prefs.fontSp(this@NotifActivity)) {
                    Prefs.setFontSp(this@NotifActivity, sp); renderCurrent(); scheduleAutoClose()
                }
                return true
            }
        })

        ContextCompat.registerReceiver(
            this, dismissReceiver, IntentFilter(DashService.ACTION_DISMISS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, imgReceiver, IntentFilter(NotifListener.ACTION_IMG_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        loadForIntent(intent)
        renderCurrent()
        if (playOnLoad) playSound()
        scheduleAutoClose()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A newer notification arrived (or the "view last" button was tapped) while we're up:
        // jump to it, re-chime only for a real notification, reset the timer.
        loadForIntent(intent)
        renderCurrent()
        if (playOnLoad) playSound()
        scheduleAutoClose()
    }

    /** Decide what to show for this launch: a Test/preview payload, or a position in the history. */
    private fun loadForIntent(intent: Intent?) {
        val payloadStr = intent?.getStringExtra(DashService.EXTRA_PAYLOAD)
        val obj = try { payloadStr?.let { JSONObject(it) } ?: JSONObject() } catch (_: Exception) { JSONObject() }

        // Chime only for a real notif / Test preview (they carry a payload without "silent"). A bare
        // launch with NO payload (the launcher "Notifications" shortcut) or an explicit "silent" (the
        // persistent-notif "view last" button) opens quietly on the last notification.
        playOnLoad = payloadStr != null && !obj.optBoolean("silent", false)

        if (obj.optBoolean("preview", false)) {
            previewMode = true; previewItem = obj; items = emptyList(); idx = 0; return
        }
        // Page through the filter-filtered history snapshot, sorted strictly by received time so
        // grouped-app notifications (whose children can arrive out of order, summary skipped) show
        // UN-grouped in chronological order.
        items = Prefs.history(this)
            .filter { !Prefs.isItemHidden(this, it.optString("pkg"), it.optString("app"), it.optString("category"), it.optString("title"), it.optString("text")) }
            .sortedBy { it.optLong("ts") }
        if (items.isEmpty()) {
            // No history yet (e.g. the "view last" button before anything arrived) — show a placeholder.
            previewMode = true
            previewItem = if (obj.optString("app").isNotEmpty() || obj.optString("title").isNotEmpty()
                || obj.optString("text").isNotEmpty()) obj
            else JSONObject().put("app", "LockDash").put("text", "No notifications yet")
            idx = 0
            return
        }
        previewMode = false; previewItem = null
        val ts = obj.optLong("ts", 0L)
        idx = if (ts > 0L) items.indexOfLast { it.optLong("ts") == ts }.let { if (it >= 0) it else items.lastIndex }
        else items.lastIndex
    }

    // Bumped on every render so an in-flight off-thread image decode from a previous item (paging is
    // fast) knows it's been superseded and must not populate the now-recycled ImageViews.
    private var imageEpoch = 0

    private fun renderCurrent() {
        val item = if (previewMode) previewItem else items.getOrNull(idx)
        if (item == null) { finish(); return }

        val app = item.optString("app"); val title = item.optString("title"); val text = item.optString("text")
        val pkg = item.optString("pkg"); val category = item.optString("category"); val ts = item.optLong("ts", 0L)
        curApp = app; curSender = title; curText = text; curPkg = pkg; curCategory = category

        val fg = Prefs.fgColor(this)
        val bg = Prefs.bgColor(this)
        val base = Prefs.fontSp(this).toFloat()

        val appV = findViewById<TextView>(R.id.notif_app)
        val timeV = findViewById<TextView>(R.id.notif_time)
        val senderV = findViewById<TextView>(R.id.notif_sender)
        val textV = findViewById<TextView>(R.id.notif_text)
        val imagesV = findViewById<LinearLayout>(R.id.notif_images)
        val closeV = findViewById<TextView>(R.id.notif_close)
        findViewById<View>(R.id.notif_root).setBackgroundColor(bg)

        // App name, with the notification category in small parentheses beside it, e.g. "OUTLOOK (Email)".
        val appText = app.uppercase()
        appV.text = if (category.isNotEmpty()) {
            val suffix = "  (${catDisplay(category)})"
            SpannableString(appText + suffix).apply {
                setSpan(RelativeSizeSpan(0.65f), appText.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else appText
        // The app-name header is deliberately NOT tappable — the deep link lives on the headline text
        // below (title/body), matching where you'd tap the real notification.
        appV.isClickable = false
        appV.setOnClickListener(null)

        timeV.text = if (ts > 0L) timeFmt.format(Date(ts)) else ""
        timeV.visibility = if (ts > 0L) View.VISIBLE else View.GONE
        senderV.text = title
        senderV.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
        // Body: plain text (links tappable + deep-link), OR detected Markdown tables / code blocks
        // rendered as aligned-monospace blocks. Falls back to plain text if nothing special parses.
        renderBody(text, fg, base, ts, pkg)

        // Tapping the headline (title OR body text) — or an image — fires the notification's OWN tap
        // intent: the exact deep link the source app opens when you tap the real notification (a news
        // headline → that article, a chat → that conversation), extras and all. Explicit URL spans in the
        // body take priority (LinkMovementMethod). If we no longer hold that intent (process restarted
        // since it arrived) we fall back to launching the app. Neither path finishes the overlay, so
        // Android Back from the opened app returns here at the same position (see fireContentIntent/openApp).
        val hasContentIntent = NotifIntents.get(ts) != null
        val openOnTap = View.OnClickListener { handleTap(ts, pkg) }
        val canOpen = pkg.isNotEmpty()
        // Body-text tap wiring lives in renderBody (it owns notif_text vs the rich notif_body segments).
        senderV.isClickable = canOpen && title.isNotEmpty()
        senderV.setOnClickListener(if (canOpen && title.isNotEmpty()) openOnTap else null)

        // Attached image(s) (Telegram photos incl. a whole album, HA Companion `image:` pushes), saved by
        // NotifListener. One ImageView per image, stacked; each downsampled to the screen width so a large
        // bitmap can't OOM the overlay.
        imagesV.removeAllViews()
        val reqW = resources.displayMetrics.widthPixels
        val shownPaths = Prefs.rowImages(item).filter { it.isNotEmpty() && File(it).exists() }
        // Create the ImageViews synchronously (preserves order + layout height), then decode the bitmaps
        // OFF the UI thread — a downsample of a large photo still stalls the overlay pop-in — and set each
        // into its view once ready, guarded by imageEpoch so a newer render can't be clobbered.
        val epoch = ++imageEpoch
        val ivs = ArrayList<ImageView>(shownPaths.size)
        for ((imgIdx, path) in shownPaths.withIndex()) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
                adjustViewBounds = true
                // As large as possible while fully visible: FIT_CENTER within the full width (images are
                // edge-to-edge — the content has no side padding around notif_images) and up to the screen
                // height. FIT keeps the image centred with zero margin in the filling direction; a rotation
                // re-runs renderCurrent (onConfigurationChanged) so this re-fits to the new orientation.
                maxHeight = resources.displayMetrics.heightPixels
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.notif_image_desc)
                // Tap an image → open it (and the rest of the album) full-screen, starting here.
                // Long-press → the app's own tap intent (deep link), same as the body.
                setOnClickListener { openImage(shownPaths, imgIdx) }
                if (hasContentIntent) setOnLongClickListener { fireContentIntent(ts) || run { openApp(pkg); true } }
            }
            ivs.add(iv)
            imagesV.addView(iv)
        }
        imagesV.visibility = if (shownPaths.isNotEmpty()) View.VISIBLE else View.GONE
        if (shownPaths.isNotEmpty()) Thread {
            val decoded = shownPaths.map { decodeScaled(it, reqW) }
            runOnUiThread {
                if (epoch != imageEpoch) return@runOnUiThread   // a newer render superseded this decode
                for (i in ivs.indices) decoded[i]?.let { ivs[i].setImageBitmap(it) } ?: run { ivs[i].visibility = View.GONE }
            }
        }.start()

        appV.setTextColor(fg); timeV.setTextColor(fg); senderV.setTextColor(fg)
        textV.setTextColor(fg); closeV.setTextColor(fg)
        appV.alpha = 1f; timeV.alpha = 0.7f   // full alpha so the app icon isn't faded
        // A single size knob drives the text trio, kept in proportion; the time line stays small.
        appV.setTextSize(TypedValue.COMPLEX_UNIT_SP, (base * 0.5f).coerceAtLeast(14f))
        senderV.setTextSize(TypedValue.COMPLEX_UNIT_SP, base * 0.8f)
        textV.setTextSize(TypedValue.COMPLEX_UNIT_SP, base)
        timeV.setTextSize(TypedValue.COMPLEX_UNIT_SP, (base * 0.32f).coerceIn(12f, 20f))

        // The app's icon to the LEFT of the app name, sized to ~1.3x the label text (scales with font).
        val icon = if (pkg.isNotEmpty()) appIcon(pkg) else null
        if (icon != null) {
            val sz = (appV.textSize * 1.3f).toInt()
            icon.setBounds(0, 0, sz, sz)
            appV.setCompoundDrawablesRelative(icon, null, null, null)
            appV.compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
        } else {
            appV.setCompoundDrawablesRelative(null, null, null, null)
        }
    }

    // ------------------------------------------------------------------ body rendering (text/table/code)

    private enum class SegKind { TEXT, TABLE, CODE }
    private class Seg(val kind: SegKind, val text: String = "", val rows: List<List<String>> = emptyList())

    /**
     * Render the notification body. If it contains a detectable Markdown pipe-table or a fenced code
     * block, split it into segments and render text proportionally (links + deep-link tap) and
     * table/code blocks as aligned MONOSPACE (auto-fit font, horizontally scrollable when too wide).
     * If nothing special parses — or parsing fails — fall back to the plain single-TextView rendering,
     * i.e. exactly the previous behaviour.
     */
    private fun renderBody(text: String, fg: Int, base: Float, ts: Long, pkg: String) {
        val textV = findViewById<TextView>(R.id.notif_text)
        val bodyV = findViewById<LinearLayout>(R.id.notif_body)
        bodyV.removeAllViews(); tableScrolls.clear()
        val canOpen = pkg.isNotEmpty()
        val openOnTap = View.OnClickListener { handleTap(ts, pkg) }

        val segs = try { parseSegments(text) } catch (_: Exception) { emptyList() }
        val hasSpecial = segs.any { it.kind != SegKind.TEXT }
        if (text.isEmpty() || !hasSpecial) {
            // FALLBACK — identical to the previous plain-text path.
            bodyV.visibility = View.GONE
            textV.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            setLinkifiedText(textV, text, fg)
            textV.setTextColor(fg)
            textV.setTextSize(TypedValue.COMPLEX_UNIT_SP, base)
            textV.isClickable = canOpen
            textV.setOnClickListener(if (canOpen) openOnTap else null)
            return
        }
        textV.visibility = View.GONE
        bodyV.visibility = View.VISIBLE
        for (s in segs) when (s.kind) {
            SegKind.TEXT -> {
                if (s.text.isBlank()) continue
                val tv = TextView(this).apply {
                    setTextColor(fg); setTextSize(TypedValue.COMPLEX_UNIT_SP, base)
                    setLineSpacing(0f, 1.1f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }
                setLinkifiedText(tv, s.text.trim('\n'), fg)
                tv.isClickable = canOpen
                tv.setOnClickListener(if (canOpen) openOnTap else null)
                bodyV.addView(tv)
            }
            SegKind.CODE -> addMonoBlock(bodyV, s.text.trim('\n'), fg, base)
            SegKind.TABLE -> addMonoBlock(bodyV, tableToAligned(s.rows), fg, base)
        }
    }

    /** Add a monospace block (table/code) at the SAME size as the regular body text (`base`). If any
     *  line is wider than the screen at that size, wrap it in a horizontally-scrollable view with a
     *  visible scrollbar (registered so a horizontal drag over it scrolls the block instead of paging
     *  the overlay — see hscrollCanScrollAt); otherwise render it plainly. */
    private fun addMonoBlock(parent: LinearLayout, content: String, fg: Int, base: Float) {
        val lines = content.split('\n')
        val usable = (resources.displayMetrics.widthPixels - 2 * dp(28) - 2 * dp(10)).toFloat()
        val paint = Paint().apply { typeface = monoFont(); textSize = base * resources.displayMetrics.scaledDensity }
        val widest = if (lines.isEmpty()) 0f else lines.maxOf { paint.measureText(it) }
        val scroll = widest > usable
        val tv = TextView(this).apply {
            typeface = monoFont()
            setTextColor(fg); setTextSize(TypedValue.COMPLEX_UNIT_SP, base)   // match regular text size
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0x22FFFFFF)
            setHorizontallyScrolling(scroll)
            text = content
        }
        if (scroll) {
            val hs = HorizontalScrollView(this).apply {
                isFillViewport = false
                isHorizontalScrollBarEnabled = true                          // show the scrollbar…
                isScrollbarFadingEnabled = false                             // …and keep it visible (overflow hint)
                scrollBarStyle = View.SCROLLBARS_OUTSIDE_INSET               // reserve a strip below the content
                scrollBarSize = dp(5)
                // The stock scrollbar is nearly invisible on the dark panel — give it a high-contrast thumb.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    horizontalScrollbarThumbDrawable = GradientDrawable().apply {
                        setColor(0xCCFFFFFF.toInt()); cornerRadius = dp(2).toFloat(); setSize(dp(48), dp(5))
                    }
                    horizontalScrollbarTrackDrawable = GradientDrawable().apply {
                        setColor(0x33FFFFFF); cornerRadius = dp(2).toFloat(); setSize(dp(10), dp(5))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            hs.addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            tableScrolls.add(hs)
            parent.addView(hs)
        } else {
            tv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            parent.addView(tv)
        }
    }

    /**
     * A genuinely fixed-width typeface. `Typeface.MONOSPACE` is unreliable on some OEM ROMs (OxygenOS
     * maps the "monospace" family to a PROPORTIONAL font, so tables don't line up), so load a real
     * monospace TTF straight from /system/fonts, trying the common ones, and fall back to MONOSPACE.
     */
    private var monoTypeface: Typeface? = null
    private fun monoFont(): Typeface {
        monoTypeface?.let { return it }
        val candidates = listOf(
            "/system/fonts/DroidSansMono.ttf", "/system/fonts/CousineRegular.ttf",
            "/system/fonts/RobotoMono-Regular.ttf", "/system/fonts/CutiveMono.ttf"
        )
        var tf: Typeface? = null
        for (p in candidates) {
            val f = File(p)
            if (f.exists()) { try { tf = Typeface.createFromFile(f) } catch (_: Exception) {} ; if (tf != null) break }
        }
        return (tf ?: Typeface.MONOSPACE).also { monoTypeface = it }
    }

    /** Lay a parsed table (rows[0] = header) out as aligned monospace text with a dashed rule. */
    private fun tableToAligned(rows: List<List<String>>): String {
        val ncol = rows.maxOf { it.size }
        val norm = rows.map { r -> (0 until ncol).map { r.getOrElse(it) { "" } } }
        val w = (0 until ncol).map { c -> norm.maxOf { it[c].length } }
        val sb = StringBuilder()
        norm.forEachIndexed { i, r ->
            sb.append((0 until ncol).joinToString(" | ") { r[it].padEnd(w[it]) }.trimEnd())
            sb.append('\n')
            if (i == 0) sb.append((0 until ncol).joinToString("-+-") { "-".repeat(w[it]) }).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    private val sepCell = Regex("^:?-{2,}:?$")
    private fun isPipeLine(l: String) = l.count { it == '|' } >= 2

    /** Split a body into ordered TEXT / TABLE / CODE segments. */
    private fun parseSegments(body: String): List<Seg> {
        val lines = body.split('\n')
        val out = ArrayList<Seg>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {                       // fenced code block
                val code = ArrayList<String>(); var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith("```")) { code.add(lines[j]); j++ }
                out.add(Seg(SegKind.CODE, code.joinToString("\n")))
                i = if (j < lines.size) j + 1 else j
            } else if (isPipeLine(line)) {                                   // candidate table
                var j = i; val block = ArrayList<String>()
                while (j < lines.size && isPipeLine(lines[j])) { block.add(lines[j]); j++ }
                val rows = tryParseTable(block)
                out.add(if (rows != null) Seg(SegKind.TABLE, rows = rows) else Seg(SegKind.TEXT, block.joinToString("\n")))
                i = j
            } else {                                                        // normal text run
                var j = i; val buf = ArrayList<String>()
                while (j < lines.size && !lines[j].trimStart().startsWith("```") && !isPipeLine(lines[j])) { buf.add(lines[j]); j++ }
                out.add(Seg(SegKind.TEXT, buf.joinToString("\n")))
                i = j
            }
        }
        return out
    }

    /**
     * Parse a block of pipe-lines into table rows, or null if it isn't a real table. Handles both proper
     * multiline Markdown tables AND the Gmail case where the row-break newlines were flattened to spaces
     * (everything on one line): flatten every non-empty cell, require a `---` separator cell (its index =
     * the column count), drop the separator cells, and chunk the rest into rows of that width.
     */
    private fun tryParseTable(block: List<String>): List<List<String>>? {
        val cells = ArrayList<String>()
        for (l in block) for (c in l.split('|')) { val t = c.trim(); if (t.isNotEmpty()) cells.add(t) }
        val sepIdx = cells.indexOfFirst { sepCell.matches(it) }
        if (sepIdx <= 0) return null                       // need >= 1 header cell before a separator
        val ncol = sepIdx
        val data = cells.filter { !sepCell.matches(it) }   // header cells + data cells
        if (data.size < ncol * 2) return null              // header + at least one data row
        val rows = ArrayList<List<String>>()
        var k = 0
        while (k < data.size) { rows.add(data.subList(k, minOf(k + ncol, data.size))); k += ncol }
        return if (rows.size >= 2) rows else null
    }

    /** True if a horizontally-scrollable table block sits under (x,y) (window coords) and can scroll
     *  `dir` — so a horizontal drag there scrolls the block instead of paging the overlay. */
    private fun hscrollCanScrollAt(x: Float, y: Float, dir: Int): Boolean {
        val loc = IntArray(2)
        for (hs in tableScrolls) {
            if (hs.visibility != View.VISIBLE || hs.width == 0) continue
            hs.getLocationInWindow(loc)
            if (x >= loc[0] && x <= loc[0] + hs.width && y >= loc[1] && y <= loc[1] + hs.height &&
                hs.canScrollHorizontally(dir)) return true
        }
        return false
    }

    /** The source app's launcher icon (badged for work-profile apps via LauncherApps), else the
     *  package's application icon, else null. */
    private fun appIcon(pkg: String): Drawable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val la = getSystemService(LauncherApps::class.java)
                for (p in (la?.profiles ?: emptyList())) {
                    val acts = try { la!!.getActivityList(pkg, p) } catch (_: Exception) { emptyList() }
                    if (acts.isNotEmpty()) return acts[0].getBadgedIcon(0)
                }
            } catch (_: Exception) {}
        }
        return try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    }

    /** Friendly label for a Notification.category string (empty -> "General"). */
    private fun catDisplay(cat: String): String = when (cat) {
        "" -> "General"
        "msg" -> "Messages"; "email" -> "Email"; "call" -> "Calls"; "social" -> "Social"
        "event" -> "Events"; "reminder" -> "Reminders"; "alarm" -> "Alarms"; "promo" -> "Promotions"
        "err" -> "Errors"; "progress" -> "Progress"; "transport" -> "Media"; "sys" -> "System"
        "service" -> "Service"; "status" -> "Status"; "recommendation" -> "Recommendations"
        "navigation" -> "Navigation"; "stopwatch" -> "Stopwatch"; "missed_call" -> "Missed calls"
        else -> cat.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /** Tap on a notification's headline/body. For our OWN SMS notifications (SmsReceiver feeds them
     *  with pkg == our package) open the SMS Conversations screen; for anything else fire the notification's
     *  own deep link, falling back to launching the source app. Closes the overlay either way. */
    private fun handleTap(ts: Long, pkg: String) {
        if (pkg == packageName) {
            startActivity(Intent(this, ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (!fireContentIntent(ts)) openApp(pkg)
    }

    /** Open the notification's source app, then close the overlay. Tries LauncherApps first so
     *  work-profile apps (not resolvable via the personal PackageManager) also open; else the plain
     *  launch intent; else a short toast. */
    private fun openApp(pkg: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val la = getSystemService(LauncherApps::class.java)
                for (p in (la?.profiles ?: emptyList())) {
                    val acts = try { la!!.getActivityList(pkg, p) } catch (_: Exception) { emptyList() }
                    if (acts.isNotEmpty()) {
                        la!!.startMainActivity(acts[0].componentName, p, null, null)
                        return   // keep the overlay alive so Back returns to it
                    }
                }
            } catch (_: Exception) {}
        }
        try {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return   // keep the overlay alive
            }
        } catch (_: Exception) {}
        Toast.makeText(this, "Can't open this app", Toast.LENGTH_SHORT).show()
    }

    /**
     * Set the body text, turning any web URL in it into a tappable span that opens the link inside
     * LockDash's own [LinkWebActivity] (over the lock screen) instead of the system browser. Links are
     * drawn in the foreground colour + underlined; non-link taps do nothing (only the ✕ dismisses).
     * Falls back to plain text if no link is present or Linkify throws.
     */
    private fun setLinkifiedText(tv: TextView, text: String, fg: Int) {
        if (text.isEmpty()) { tv.text = ""; tv.movementMethod = null; return }
        var hasLink = false
        val sp = SpannableStringBuilder(text)
        try {
            Linkify.addLinks(sp, Linkify.WEB_URLS)
            for (u in sp.getSpans(0, sp.length, URLSpan::class.java)) {
                val st = sp.getSpanStart(u); val en = sp.getSpanEnd(u); val fl = sp.getSpanFlags(u)
                val url = u.url
                sp.removeSpan(u)
                sp.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = openLink(url)
                    override fun updateDrawState(ds: TextPaint) { ds.color = fg; ds.isUnderlineText = true }
                }, st, en, fl)
                hasLink = true
            }
        } catch (_: Exception) {}
        tv.text = sp
        // Only attach the link movement method when there IS a link, so ScrollView scrolling is
        // untouched for ordinary (link-free) notifications.
        tv.movementMethod = if (hasLink) LinkMovementMethod.getInstance() else null
        tv.highlightColor = Color.TRANSPARENT
    }

    /** Open a link from a notification inside LockDash's own WebView activity (its own task, shown over
     *  the lock screen), rather than handing it to the system browser. */
    private fun openLink(url: String) {
        try {
            startActivity(
                Intent(this, LinkWebActivity::class.java)
                    .putExtra(LinkWebActivity.EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            scheduleAutoClose()   // don't let the overlay time out while the user is reading a link
        } catch (e: Exception) {
            Log.w(TAG, "open link failed: ${e.message}")
            Toast.makeText(this, "Can't open link", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Page to an adjacent message (delta -1 older / +1 newer) with a slide animation matching the swipe
     * axis (`horizontal` true for the ◀ ▶ arrows and left/right swipes, false for up/down swipes). A
     * snapshot of the current message pane slides off one edge while the freshly-rendered pane slides in
     * from the opposite edge (the ✕ chip stays put). Bounds-guarded; never re-chimes.
     */
    private fun page(delta: Int, horizontal: Boolean = true) {
        if (previewMode || paging) return
        val n = idx + delta
        if (n !in 0..items.lastIndex) return
        val content = findViewById<View>(R.id.notif_content)
        val root = findViewById<FrameLayout>(R.id.notif_root)
        val w = content.width; val h = content.height
        val snap = if (w > 0 && h > 0) try { snapshot(content) } catch (_: Exception) { null } else null
        if (snap == null) { idx = n; renderCurrent(); scheduleAutoClose(); return }   // fallback: instant

        val snapView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_XY   // snapshot is already the content's exact size
            setImageBitmap(snap)
        }
        root.addView(snapView, 1)   // above the content column, below the ✕ close chip

        // Render the target message underneath the snapshot, then slide the snapshot out and it in.
        idx = n; renderCurrent()
        val size = (if (horizontal) w else h).toFloat()
        if (horizontal) content.translationX = delta * size else content.translationY = delta * size
        paging = true
        val outEnd = -delta * size
        snapView.animate().apply { if (horizontal) translationX(outEnd) else translationY(outEnd) }
            .setDuration(240).start()
        content.animate().apply { if (horizontal) translationX(0f) else translationY(0f) }
            .setDuration(240)
            .withEndAction {
                content.translationX = 0f; content.translationY = 0f
                root.removeView(snapView)
                try { snap.recycle() } catch (_: Exception) {}
                paging = false
                scheduleAutoClose()
            }.start()
    }

    /** Long-press context menu on the message area. Reuses the SAME Prefs filter functions the old
     *  checkboxes used: filter by app (all), app+category, app+sender, or app+text (substring); plus Delete.
     *  Options are omitted when their field is empty (no category / sender / text on this notification). */
    private fun showFilterMenu() {
        if (previewMode) return
        val pkg = curPkg
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (pkg.isNotEmpty()) {
            labels.add("Filter by app")
            acts.add { addFilterAndAdvance(Prefs.filterAppKey(pkg), curApp.ifEmpty { pkg }) }
            if (curCategory.isNotEmpty()) {
                labels.add("Filter by category")
                acts.add { addFilterAndAdvance(Prefs.filterKey(pkg, curCategory), "$curApp \u00b7 ${catDisplay(curCategory)}") }
            }
            if (curSender.isNotEmpty()) {
                labels.add("Filter by sender")
                acts.add { addFilterAndAdvance(Prefs.filterSenderKey(pkg, curSender), "$curApp \u00b7 sender: $curSender") }
            }
            if (curText.isNotEmpty()) {
                labels.add("Filter by text")
                val snip = if (curText.length > 40) curText.take(40).trim() + "\u2026" else curText
                acts.add { addFilterAndAdvance(Prefs.filterTextKey(pkg, curText), "$curApp \u00b7 text: $snip") }
            }
        }
        labels.add("Delete")
        acts.add { deleteCurrent() }
        handler.removeCallbacks(autoClose)                    // pause idle auto-dismiss while the menu is up
        android.app.AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
            .setOnDismissListener { scheduleAutoClose() }
            .show()
    }

    /** Add a filter rule (same key + label scheme as the removed checkboxes), then drop every item it now
     *  hides from the overlay and show the next remaining one (closes if none are left). */
    private fun addFilterAndAdvance(key: String, label: String) {
        Prefs.addFilter(this, key, label)
        items = Prefs.history(this)
            .filter { !Prefs.isItemHidden(this, it.optString("pkg"), it.optString("app"), it.optString("category"), it.optString("title"), it.optString("text")) }
            .sortedBy { it.optLong("ts") }
        if (items.isEmpty()) { finish(); return }
        idx = idx.coerceIn(0, items.lastIndex)
        renderCurrent()
        scheduleAutoClose()
    }

    /** Delete the currently-shown notification from history, then show the PREVIOUS (older) one if there
     *  is one (else the new oldest), refreshing the index/total. Closes the overlay if none remain. */
    private fun deleteCurrent() {
        if (previewMode || paging) return
        val item = items.getOrNull(idx) ?: return
        Prefs.deleteHistory(this, item.optString("pkg"), item.optString("title"), item.optString("text"), item.optLong("ts"))
        items = Prefs.history(this)
            .filter { !Prefs.isItemHidden(this, it.optString("pkg"), it.optString("app"), it.optString("category"), it.optString("title"), it.optString("text")) }
            .sortedBy { it.optLong("ts") }
        if (items.isEmpty()) { finish(); return }
        idx = (idx - 1).coerceIn(0, items.lastIndex)   // the previous (older) message, clamped
        renderCurrent()
        scheduleAutoClose()
    }

    /** A bitmap snapshot of a view, on an opaque background colour (so transparent regions don't reveal
     *  the pane sliding underneath it during a page animation). */
    private fun snapshot(v: View): Bitmap {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Prefs.bgColor(this))
        v.draw(c)
        return bmp
    }

    /**
     * Interactive finger-following paging. A gesture is CAPTURED for paging when it moves past the slop
     * either horizontally (always) or vertically only when the body ScrollView is already at its edge in
     * that direction (so reading-scroll still wins for a long body). Once captured, the message pane
     * follows the finger via snapshots and the child that was tracking the gesture is sent ACTION_CANCEL.
     * Uncaptured gestures pass straight through, so taps / links / checkboxes / the ✕ still work.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        longPressDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) {                     // a two-finger pinch owns this gesture
            if (!pinching) {
                pinching = true
                if (capture) endOverlayDrag() else {          // abort any in-progress paging drag / press
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                }
                capture = false; decided = true               // block paging for the rest of this gesture
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; decided = false; capture = false; pinching = false
                longPressFired = false
                if (paging) return true                       // swallow while a settle animation runs
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressFired) return true
                if (paging) return true
                if (capture) { updateOverlayDrag(ev); return true }
                if (!decided && !previewMode) {
                    val tX = ev.x - downX; val tY = ev.y - downY
                    val slop = 12 * resources.displayMetrics.density
                    if (abs(tX) >= slop || abs(tY) >= slop) {
                        decided = true
                        val horizontal = abs(tX) >= abs(tY)
                        // Horizontal paging yields to a wide monospace table/code block that can scroll
                        // under the finger; vertical paging only when the body ScrollView can't scroll.
                        val canPage = if (horizontal) !hscrollCanScrollAt(downX, downY, if (tX < 0) 1 else -1)
                                      else !bodyCanScroll(if (tY < 0) 1 else -1)
                        if (canPage && startOverlayDrag(horizontal)) {
                            capture = true
                            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel); cancel.recycle()
                            updateOverlayDrag(ev)
                            return true
                        }
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressFired) { longPressFired = false; return true }
                if (capture) { finishOverlayDrag(ev); capture = false; decided = false; return true }
                decided = false; pinching = false
                return super.dispatchTouchEvent(ev)
            }
            else -> return super.dispatchTouchEvent(ev)
        }
    }

    /** Send ACTION_CANCEL to the children so an in-flight press doesn't also fire a tap/scroll
     *  after a long-press has been consumed for the context menu. */
    private fun cancelChildren(ev: MotionEvent) {
        val c = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(c); c.recycle()
    }

    private fun bodyCanScroll(dir: Int): Boolean =
        try { findViewById<ScrollView>(R.id.notif_scroll)?.canScrollVertically(dir) ?: false } catch (_: Exception) { false }

    /** Begin a page-drag: snapshot the current message pane onto an overlay layer (which then follows the
     *  finger), hide the live content, and prepare a placeholder for the neighbour. Returns false if the
     *  pane isn't laid out yet. */
    private fun startOverlayDrag(horizontal: Boolean): Boolean {
        val content = findViewById<View>(R.id.notif_content)
        val root = findViewById<FrameLayout>(R.id.notif_root)
        if (content.width == 0 || content.height == 0) return false
        dragAxis = if (horizontal) 1 else 2
        snapCache.clear()
        val curBmp = try { snapshot(content) } catch (_: Exception) { return false }
        dragCurBmp = curBmp
        val layer = FrameLayout(this)
        val cur = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(curBmp)
        }
        val inc = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_XY; visibility = View.INVISIBLE
        }
        layer.addView(cur); layer.addView(inc)
        root.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        findViewById<View>(R.id.notif_close).bringToFront()   // keep the ✕ on top of the drag layer
        content.visibility = View.INVISIBLE   // snapshots drive the visuals during the drag
        dragLayer = layer; dragCur = cur; dragInc = inc
        return true
    }

    /** During a page-drag: move the current pane by the finger offset and reveal the neighbour it
     *  uncovers (rubber-banded when there is no neighbour at that end). */
    private fun updateOverlayDrag(ev: MotionEvent) {
        val horizontal = dragAxis == 1
        val offset = if (horizontal) ev.x - downX else ev.y - downY
        val content = findViewById<View>(R.id.notif_content)
        val size = (if (horizontal) content.width else content.height).toFloat()
        if (size <= 0f) return
        val wantNext = offset < 0
        val nIdx = if (wantNext) idx + 1 else idx - 1
        val hasN = nIdx in 0..items.lastIndex
        val eff = if (hasN) offset else offset * 0.35f
        dragCur?.apply { if (horizontal) { translationX = eff; translationY = 0f } else { translationY = eff; translationX = 0f } }
        val inc = dragInc ?: return
        if (hasN) {
            val bmp = neighborSnap(nIdx)
            if (bmp != null) {
                inc.setImageBitmap(bmp); inc.visibility = View.VISIBLE
                val base = if (wantNext) size else -size
                if (horizontal) { inc.translationX = base + eff; inc.translationY = 0f } else { inc.translationY = base + eff; inc.translationX = 0f }
            } else inc.visibility = View.INVISIBLE
        } else inc.visibility = View.INVISIBLE
    }

    /** Render a neighbour message into the (hidden) live content and snapshot it, then restore the
     *  current message. Cached so a back-and-forth drag doesn't re-render. */
    private fun neighborSnap(nIdx: Int): Bitmap? {
        snapCache[nIdx]?.let { return it }
        val content = findViewById<View>(R.id.notif_content)
        val saved = idx
        idx = nIdx; renderCurrent()
        val bmp = try { snapshot(content) } catch (_: Exception) { null }
        idx = saved; renderCurrent()
        if (bmp != null) snapCache[nIdx] = bmp
        return bmp
    }

    /** On release: commit to the neighbour if dragged past COMMIT_FRACTION of the pane, else spring back
     *  to the current message. Both animate to rest. */
    private fun finishOverlayDrag(ev: MotionEvent) {
        val horizontal = dragAxis == 1
        val offset = if (horizontal) ev.x - downX else ev.y - downY
        val content = findViewById<View>(R.id.notif_content)
        val size = (if (horizontal) content.width else content.height).toFloat()
        val wantNext = offset < 0
        val nIdx = if (wantNext) idx + 1 else idx - 1
        val hasN = nIdx in 0..items.lastIndex
        val commit = hasN && abs(offset) >= size * COMMIT_FRACTION && snapCache.containsKey(nIdx)
        paging = true
        val cur = dragCur; val inc = dragInc
        if (commit && cur != null && inc != null) {
            val curTarget = if (wantNext) -size else size
            cur.animate().apply { if (horizontal) translationX(curTarget) else translationY(curTarget) }.setDuration(SETTLE_MS).start()
            inc.animate().apply { if (horizontal) translationX(0f) else translationY(0f) }.setDuration(SETTLE_MS)
                .withEndAction {
                    idx = nIdx; renderCurrent()   // live content -> committed message, revealed on removal
                    endOverlayDrag(); scheduleAutoClose()
                }.start()
        } else {
            inc?.let {
                val base = if (wantNext) size else -size
                it.animate().apply { if (horizontal) translationX(base) else translationY(base) }.setDuration(SETTLE_MS).start()
            }
            if (cur != null) cur.animate().apply { if (horizontal) translationX(0f) else translationY(0f) }
                .setDuration(SETTLE_MS).withEndAction { endOverlayDrag() }.start()
            else endOverlayDrag()
        }
    }

    private fun endOverlayDrag() {
        val content = findViewById<View>(R.id.notif_content)
        content.visibility = View.VISIBLE
        content.translationX = 0f; content.translationY = 0f
        dragLayer?.let { findViewById<FrameLayout>(R.id.notif_root).removeView(it) }
        dragLayer = null; dragCur = null; dragInc = null
        try { dragCurBmp?.recycle() } catch (_: Exception) {}
        dragCurBmp = null
        for (b in snapCache.values) try { b.recycle() } catch (_: Exception) {}
        snapCache.clear()
        paging = false
    }

    /** Open the row's image(s) full-screen (fit-to-display) in [ImageViewActivity], starting at `start`
     *  and swipeable through the rest (album). */
    private fun openImage(paths: List<String>, start: Int) {
        try {
            startActivity(
                Intent(this, ImageViewActivity::class.java)
                    .putStringArrayListExtra(ImageViewActivity.EXTRA_PATHS, ArrayList(paths))
                    .putExtra(ImageViewActivity.EXTRA_START, start)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            scheduleAutoClose()   // keep the overlay alive while viewing the image
        } catch (e: Exception) {
            Log.w(TAG, "open image failed: ${e.message}")
        }
    }

    /** Fire the notification's own tap intent (its contentIntent) if we still hold it — opening the
     *  source app's exact deep link (article, chat, …) as tapping the real notification would, extras
     *  and all. Returns false when there is none, so the caller can fall back to launching the app. */
    private fun fireContentIntent(ts: Long): Boolean {
        val pi = NotifIntents.get(ts) ?: return false
        return try {
            // Android 14+ BAL hardening blocks the contentIntent's activity launch unless the SENDER
            // opts in — and we're a visible/TOP activity, so opting in is honoured (the framework logs
            // resultIfPiSenderAllowsBal: BAL_ALLOW_VISIBLE_WINDOW). Without this, pi.send() "succeeds"
            // but the target activity never appears (observed with the HA Companion WebViewActivity PI).
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
            // Do NOT finish() — keep the overlay in the back stack so Android Back from the opened app
            // returns to it at the same position. The auto-close timer is paused while we're away
            // (onPause) and re-armed on return (onResume).
            true
        } catch (e: Exception) {
            Log.w(TAG, "contentIntent send failed: ${e.message}")
            false
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Decode a saved notification image downsampled to roughly the target width, to avoid OOM on a
     *  large bitmap. Returns null on any failure. */
    private fun decodeScaled(path: String, reqW: Int): android.graphics.Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        val target = if (reqW > 0) reqW else 1080
        while (bounds.outWidth / sample > target * 2) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }

    // Idle auto-dismiss: (re)arm the timeout, but ONLY when shown over the lock screen. Every user
    // interaction calls this via onUserInteraction(), so the timeout is measured from the last touch/key
    // (idle), not from when the overlay opened. In the unlocked state self-dismissal is disabled.
    private fun scheduleAutoClose() {
        handler.removeCallbacks(autoClose)
        if (!isLocked()) return                                     // unlocked -> never self-dismiss
        val secs = Prefs.autoCloseSecs(this)
        if (secs > 0) handler.postDelayed(autoClose, secs * 1000L)  // 0 = never auto-dismiss
    }

    private fun isLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    override fun onUserInteraction() { super.onUserInteraction(); scheduleAutoClose() }

    // The activity stays alive across rotation (manifest configChanges) so paging state is kept; re-render
    // so the image (and text) re-fit + re-centre for the new orientation — its size is width/height-based.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        renderCurrent()
    }

    /**
     * Play the picked tone at the configured volume, independent of the system volume: briefly max
     * STREAM_ALARM and scale it down with the MediaPlayer's own gain, then restore the user's alarm
     * level when the tone finishes (or the screen closes). USAGE_ALARM so it sounds even in silent
     * mode. Best-effort — never blocks the screen; if maxing the stream is denied (some DND/OEM
     * cases) it just falls back to the player gain over the current alarm volume.
     */
    private fun playSound() {
        stopSound()
        try {
            val uriStr = Prefs.soundUri(this)
            val uri: Uri = if (uriStr.isNotEmpty()) Uri.parse(uriStr)
                else RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return

            val frac = Prefs.volume(this).coerceIn(0, 100) / 100f
            if (frac <= 0f) return

            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioMgr = am
            try {
                savedAlarmVol = am.getStreamVolume(alarmStream)
                am.setStreamVolume(alarmStream, am.getStreamMaxVolume(alarmStream), 0)  // no UI flag
            } catch (e: Exception) {
                savedAlarmVol = -1
                Log.w(TAG, "alarm-volume override denied: ${e.message}")
            }

            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(this, uri)
            player.setVolume(frac, frac)
            player.setOnCompletionListener { stopSound() }
            player.setOnErrorListener { _, _, _ -> stopSound(); true }
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
            mp = player
        } catch (e: Exception) {
            Log.w(TAG, "sound failed: ${e.message}")
            restoreAlarmVol()
        }
    }

    private fun stopSound() {
        try { mp?.let { if (it.isPlaying) it.stop(); it.release() } } catch (_: Exception) {}
        mp = null
        restoreAlarmVol()
    }

    private fun restoreAlarmVol() {
        try { if (savedAlarmVol >= 0) audioMgr?.setStreamVolume(alarmStream, savedAlarmVol, 0) } catch (_: Exception) {}
        savedAlarmVol = -1
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

    // The auto-close timer runs only while the overlay is in the foreground: pause it when we leave
    // (e.g. the user tapped a headline and went to the app / a link / an image) and re-arm on return, so
    // the overlay is still here — at the same position — when Android Back brings the user back.
    override fun onResume() { super.onResume(); visible = true; goImmersive(); scheduleAutoClose() }
    override fun onPause() { super.onPause(); visible = false; handler.removeCallbacks(autoClose) }

    /**
     * A row changed: either a late image was attached, or a brand-new notification arrived (DashService
     * broadcasts this after recording a userbot push, because openActivity's onNewIntent isn't reliably
     * delivered to an already-visible singleTask overlay on rapid pops — which left the index/content
     * stale). Reload the snapshot; if `ts` is the NEWEST row, jump to it (like a fresh pop); if it's the
     * row we're currently showing, refresh in place; otherwise (an image filled on some other older row)
     * ignore. This keeps the content AND the index/total in sync on every successive message.
     */
    private fun onImageUpdated(ts: Long) {
        if (previewMode || ts <= 0L) return
        val fresh = Prefs.history(this)
            .filter { !Prefs.isItemHidden(this, it.optString("pkg"), it.optString("app"), it.optString("category"), it.optString("title"), it.optString("text")) }
            .sortedBy { it.optLong("ts") }
        if (fresh.isEmpty()) return
        val curTs = items.getOrNull(idx)?.optLong("ts") ?: -1L
        val isNewest = ts == fresh.last().optLong("ts")
        if (!isNewest && ts != curTs) return
        items = fresh
        idx = if (isNewest) fresh.lastIndex
              else fresh.indexOfLast { it.optLong("ts") == curTs }.let { if (it >= 0) it else fresh.lastIndex }
        renderCurrent()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoClose)
        try { unregisterReceiver(dismissReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(imgReceiver) } catch (_: Exception) {}
        stopSound()
        super.onDestroy()
    }
}
