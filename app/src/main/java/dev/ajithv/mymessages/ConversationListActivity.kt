package dev.ajithv.mymessages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * The single messages screen. A search bar (in place of a title) + a Threads/Received toggle:
 *  - Threads  = one row per conversation (mms-sms provider), latest snippet.
 *  - Received = flat list of every received SMS (inbox), one row per message.
 * Typing anything runs a debounced substring search across ALL messages (ignores the toggle);
 * tapping a search result opens the thread jumped to + highlighting the match. Both modes paint from
 * an instant cache, then refresh from the provider.
 */
class ConversationListActivity : AppCompatActivity() {

    private enum class Mode { THREADS, RECEIVED }
    private var mode = Mode.THREADS

    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var search: EditText
    private lateinit var regexBox: CheckBox
    private lateinit var senderBox: CheckBox
    private lateinit var chipThreads: Chip
    private lateinit var chipReceived: Chip
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    // Bumped on every new/cancelled search so a still-running worker's streamed batches (and the
    // conversation/inbox loaders) know they've been superseded and stop touching the list.
    private var searchSeq = 0

    // Pinch-to-zoom the message-list font (persisted in Prefs.smsFontSp; the top search/toggle bar is
    // fixed). reRender rebuilds the CURRENTLY-shown rows with the new size, without re-querying.
    private lateinit var scaleDetector: ScaleGestureDetector
    private var pinchSpF = 0f
    private var reRender: (() -> Unit)? = null

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(4))
        }
        search = EditText(this).apply {
            hint = "Search messages"; setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = scheduleSearch(s?.toString().orEmpty())
            })
        }
        bar.addView(search)
        // Clear (✕) at the end of the search field — empties the query, dropping the applied search/filter
        // and returning to the normal Threads/Received list.
        bar.addView(TextView(this).apply {
            text = "✕"; textSize = 20f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setBackgroundResource(selectableItemBg())
            setOnClickListener { search.setText(""); search.clearFocus() }
        })
        // Block: add the current search phrase ITSELF to the regex filters — a text/regex filter for a
        // body search, or a SENDER filter for a sender search — so ONE entry replaces blocking each match.
        bar.addView(Button(this).apply { text = "Block"; setOnClickListener { blockCurrentSearch() } })
        root.addView(bar)

        // Search options (own row so the search field keeps its width): Regex vs plain substring, and
        // Sender-scope (match the sender's name/number instead of the message body).
        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(2))
        }
        regexBox = CheckBox(this).apply {
            text = "Regex"
            setOnCheckedChangeListener { _, _ -> if (currentQuery().isNotEmpty()) runSearch(currentQuery()) }
        }
        optRow.addView(regexBox)
        senderBox = CheckBox(this).apply {
            text = "Sender"
            setOnCheckedChangeListener { _, on ->
                search.hint = if (on) "Search senders" else "Search messages"
                if (currentQuery().isNotEmpty()) runSearch(currentQuery())
            }
        }
        optRow.addView(senderBox)
        root.addView(optRow)

        // Row 2: Threads/Received toggle (single-selection chips) + right-aligned "New" chip.
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        val toggle = ChipGroup(this).apply {
            isSingleLine = true; isSingleSelection = true; isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        chipThreads = Chip(this).apply { id = View.generateViewId(); text = "Threads"; isCheckable = true; isChecked = true }
        chipReceived = Chip(this).apply { id = View.generateViewId(); text = "Received"; isCheckable = true }
        toggle.addView(chipThreads); toggle.addView(chipReceived)
        toggle.setOnCheckedStateChangeListener { _, _ ->
            mode = if (chipReceived.isChecked) Mode.RECEIVED else Mode.THREADS
            if (currentQuery().isEmpty()) { renderCachedForMode(); loadForMode() }
        }
        row2.addView(toggle)
        // "New" chip — same chip style as the toggle, right-aligned (toggle takes the remaining width).
        row2.addView(Chip(this).apply {
            id = View.generateViewId(); text = "New"; isCheckable = false
            setOnClickListener { startActivity(Intent(this@ConversationListActivity, ComposeActivity::class.java)) }
        })
        root.addView(row2)

        // Thin indeterminate bar shown while a search is scanning the provider — the "something is
        // happening" affordance (results also stream in live below it, so it clears fast).
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
        root.addView(progress)

        empty = TextView(this).apply {
            setPadding(dp(24), dp(24), dp(24), dp(24)); visibility = View.GONE
        }
        root.addView(empty)

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(rows)
        root.addView(scroll)
        setContentView(root)

        // Pinch anywhere to zoom the message-list font (persisted, shared with the thread view + the
        // main-screen SMS-tab slider). Re-renders per integer-sp step; only the list rows scale.
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pinchSpF = Prefs.smsFontSp(this@ConversationListActivity).toFloat(); return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinchSpF = (pinchSpF * d.scaleFactor)
                    .coerceIn(Prefs.SMS_FONT_MIN.toFloat(), Prefs.SMS_FONT_MAX.toFloat())
                val sp = Math.round(pinchSpF)
                if (sp != Prefs.smsFontSp(this@ConversationListActivity)) {
                    Prefs.setSmsFontSp(this@ConversationListActivity, sp); reRender?.invoke()
                }
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) return true   // a pinch owns the gesture (don't scroll the list)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, changed,
            IntentFilter(SmsRepo.ACTION_SMS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (currentQuery().isEmpty()) renderCachedForMode()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(changed) } catch (_: Exception) {}
    }

    private fun currentQuery() = if (::search.isInitialized) search.text.toString().trim() else ""

    private fun refresh() {
        val q = currentQuery()
        if (q.isEmpty()) loadForMode() else runSearch(q)
    }

    private fun scheduleSearch(text: String) {
        searchRunnable?.let { handler.removeCallbacks(it) }
        val q = text.trim()
        if (q.isEmpty()) { renderCachedForMode(); loadForMode(); return }
        val r = Runnable { runSearch(q) }
        searchRunnable = r
        handler.postDelayed(r, 300)   // debounce
    }

    /** Leave "search mode": supersede any in-flight streaming search (so its late batches are dropped)
     *  and hide the progress bar. Called by every path that repaints the thread/inbox list. */
    private fun cancelSearch() {
        searchSeq++
        if (::progress.isInitialized) progress.visibility = View.GONE
    }

    private fun renderCachedForMode() {
        cancelSearch()
        when (mode) {
            Mode.THREADS -> renderConversations(SmsRepo.cachedConversations(this), fresh = false)
            Mode.RECEIVED -> renderInbox(SmsRepo.cachedInbox(this), fresh = false)
        }
    }

    private fun loadForMode() {
        cancelSearch()
        val m = mode
        Thread {
            if (m == Mode.THREADS) {
                val list = SmsRepo.conversations(this); SmsRepo.cacheConversations(this, list)
                runOnUiThread { if (mode == Mode.THREADS && currentQuery().isEmpty()) renderConversations(list, true) }
            } else {
                val list = SmsRepo.inbox(this); SmsRepo.cacheInbox(this, list)
                for (msg in list) SmsRepo.contactName(this, msg.address)   // pre-warm names off-thread
                runOnUiThread { if (mode == Mode.RECEIVED && currentQuery().isEmpty()) renderInbox(list, true) }
            }
        }.start()
    }

    // ------------------------------------------------------------- Threads mode

    private fun renderConversations(list: List<SmsRepo.Conversation>, fresh: Boolean) {
        if (!fresh && list.isEmpty()) return
        reRender = { renderConversations(list, true) }   // font pinch rebuilds these rows (no re-query)
        rows.removeAllViews()
        empty.text = "No conversations.\nMake sure MyMessages is your default SMS app and SMS permission is granted."
        empty.visibility = if (fresh && list.isEmpty()) View.VISIBLE else View.GONE
        for (conv in list) rows.addView(conversationRow(conv))
    }

    private fun conversationRow(conv: SmsRepo.Conversation): View {
        val base = Prefs.smsFontSp(this).toFloat()   // pinch/slider-controlled list font
        val title = conv.name.ifEmpty { conv.address }
        val col = itemColumn(onClick = {
            startActivity(Intent(this, ThreadActivity::class.java)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, conv.threadId)
                .putExtra(ThreadActivity.EXTRA_ADDRESS, conv.address)
                .putExtra(ThreadActivity.EXTRA_NAME, conv.name))
        }, onLongClick = { showThreadMenu(title, conv.address, conv.threadId) })
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = title; textSize = base + 2f; setTextColor(colPrimary)
            applyWeight(conv.unread)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply { text = SmsRepo.fmtTime(conv.date); textSize = base - 2f; setTextColor(colSecondary) })
        col.addView(top)
        col.addView(TextView(this).apply {
            text = conv.snippet; textSize = base
            setTextColor(if (conv.unread) colPrimary else colSecondary)
            applyWeight(conv.unread)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return col
    }

    // ------------------------------------------------------------- Received (inbox) mode

    private fun renderInbox(list: List<SmsRepo.Message>, fresh: Boolean) {
        if (!fresh && list.isEmpty()) return
        reRender = { renderInbox(list, true) }   // font pinch rebuilds these rows (no re-query)
        rows.removeAllViews()
        empty.text = "No received messages (or SMS permission not granted)."
        empty.visibility = if (fresh && list.isEmpty()) View.VISIBLE else View.GONE
        for (m in list) rows.addView(messageRow(m, null))
    }

    // ------------------------------------------------------------- search results (any mode)

    /** Stream a search across all SMS: results are appended AS THEY'RE FOUND rather than after the whole
     *  scan completes, and a "Searching…" bar + text shows immediately so the screen never sits on a
     *  stale "No messages match". A [searchSeq] guard drops the batches of any search the user has
     *  already typed past (and stops the worker via `cancelled`). */
    private fun runSearch(q: String) {
        val regex = ::regexBox.isInitialized && regexBox.isChecked
        val sender = ::senderBox.isInitialized && senderBox.isChecked
        val seq = ++searchSeq
        rows.removeAllViews()
        empty.text = "Searching…"
        empty.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        // Touched only on the main thread (inside the runOnUiThread posts), so a plain flag is safe.
        val gotAny = booleanArrayOf(false)
        // Accumulate the streamed results so a font pinch can rebuild the rows without re-querying.
        val acc = ArrayList<SmsRepo.Message>()
        reRender = { rows.removeAllViews(); for (m in acc) rows.addView(messageRow(m, q, regex, sender)) }
        Thread {
            SmsRepo.searchMessagesStreaming(this, q, regex, sender,
                cancelled = { seq != searchSeq },
                onBatch = { batch ->
                    runOnUiThread {
                        if (seq != searchSeq) return@runOnUiThread
                        if (!gotAny[0]) { gotAny[0] = true; empty.visibility = View.GONE }
                        acc.addAll(batch)
                        for (m in batch) rows.addView(messageRow(m, q, regex, sender))
                    }
                },
                onDone = {
                    runOnUiThread {
                        if (seq != searchSeq) return@runOnUiThread
                        progress.visibility = View.GONE
                        if (!gotAny[0]) { empty.text = "No messages match “$q”"; empty.visibility = View.VISIBLE }
                    }
                })
        }.start()
    }

    /** Add the current search phrase ITSELF to the regex filters — a SENDER filter when the Sender box
     *  is ticked, else a body TEXT filter (plain→escaped literal, regex→as typed) — so ONE entry replaces
     *  blocking each matched message/sender one by one. A sender filter matches the sender's number /
     *  short-code, so it hides every message from a matching sender AND silently blocks future incoming
     *  ones; a text filter does the same keyed on the body. Then re-run the search so the now-hidden
     *  matches disappear from every view. */
    private fun blockCurrentSearch() {
        val q = currentQuery()
        if (q.isEmpty()) { toast("Type a search phrase to block"); return }
        val regex = ::regexBox.isInitialized && regexBox.isChecked
        val sender = ::senderBox.isInitialized && senderBox.isChecked
        val pattern = if (regex) q else Regex.escape(q)
        val ok = if (sender) RegexRepo.addSenderPattern(this, pattern) else RegexRepo.addPattern(this, pattern)
        if (ok) {
            toast(if (sender) "Added to sender filters — hiding matches" else "Added to text filters — hiding matches")
            sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName))
            runSearch(q)
        } else {
            toast("Invalid or duplicate filter")
        }
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    /** A per-message row (inbox feed OR a search result). [highlightQuery] non-null → highlight the
     *  match here and deep-link into the thread jumped to that message. */
    private fun messageRow(m: SmsRepo.Message, highlightQuery: String?, regex: Boolean = false,
                           senderScope: Boolean = false): View {
        val base = Prefs.smsFontSp(this).toFloat()   // pinch/slider-controlled list font
        val name = SmsRepo.contactName(this, m.address).ifEmpty { m.address }
        val unread = m.unread
        // In sender-scope search the match is in the sender (name/number), so the body carries no match
        // to highlight or deep-link — highlight the NAME instead, and open the thread without a body jump.
        val highlightBody = highlightQuery != null && !senderScope
        val col = itemColumn(onClick = {
            // Tapping a specific message in Received marks THAT message read.
            if (unread) Thread { SmsRepo.markMessageRead(this, m.id) }.start()
            // Pass whatever threadId we already have (0 if unknown for a Received row) — ThreadActivity
            // resolves a 0 off its own worker thread, so there's no content query on this tap.
            val tid = m.threadId
            val i = Intent(this, ThreadActivity::class.java)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, tid)
                .putExtra(ThreadActivity.EXTRA_ADDRESS, m.address)
                .putExtra(ThreadActivity.EXTRA_NAME, name)
            if (highlightBody) {
                // In regex mode, deep-link the literal first match so the thread can highlight it.
                val hl = if (regex) (firstRegexMatch(m.body, highlightQuery!!) ?: "") else highlightQuery!!
                i.putExtra(ThreadActivity.EXTRA_HIGHLIGHT_ID, m.id).putExtra(ThreadActivity.EXTRA_HIGHLIGHT_QUERY, hl)
            }
            startActivity(i)
        }, onLongClick = { showMessageMenu(name, m) })
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = when {
                !senderScope || highlightQuery == null -> name
                regex -> highlightByRegex(name, highlightQuery!!)
                else -> highlight(name, highlightQuery!!)
            }
            textSize = base + 1f; setTextColor(colPrimary)
            applyWeight(unread)   // unread sender = extra-heavy
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply { text = SmsRepo.fmtTime(m.date); textSize = base - 2f; setTextColor(colSecondary) })
        col.addView(top)
        col.addView(TextView(this).apply {
            text = when {
                !highlightBody -> m.body
                regex -> highlightByRegex(m.body, highlightQuery!!)
                else -> highlight(m.body, highlightQuery!!)
            }
            textSize = base
            applyWeight(unread)   // unread body = extra-heavy
            setTextColor(if (unread) colPrimary else colSecondary)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return col
    }

    // ------------------------------------------------------------- helpers

    private fun itemColumn(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isClickable = true
            setBackgroundResource(selectableItemBg())
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }

    /** Threads long-press: title = sender, vertical actions Block / Delete-whole-thread. */
    private fun showThreadMenu(name: String, address: String, threadId: Long) {
        AlertDialog.Builder(this)
            .setTitle(name.ifEmpty { address.ifEmpty { "Conversation" } })
            .setItems(arrayOf("Block this sender", "Delete all messages from this sender")) { _, which ->
                when (which) {
                    0 -> blockSenderAsync(address)
                    1 -> deleteThreadAsync(threadId)
                }
            }.show()
    }

    /** Received long-press: title = sender, vertical actions Block / Delete-this-message. */
    private fun showMessageMenu(name: String, m: SmsRepo.Message) {
        AlertDialog.Builder(this)
            .setTitle(name.ifEmpty { m.address.ifEmpty { "Message" } })
            .setItems(arrayOf("Block this sender", "Delete this message")) { _, which ->
                when (which) {
                    0 -> blockSenderAsync(m.address)
                    1 -> deleteMessageAsync(m.id, m.isMms)
                }
            }.show()
    }

    private fun blockSenderAsync(sender: String) {
        Thread {
            BlockRepo.block(this, sender)
            runOnUiThread { sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName)); refresh() }
        }.start()
    }

    private fun deleteThreadAsync(threadId: Long) {
        Thread {
            SmsRepo.deleteThread(this, threadId)
            runOnUiThread { sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName)); refresh() }
        }.start()
    }

    private fun deleteMessageAsync(id: Long, isMms: Boolean) {
        Thread {
            SmsRepo.deleteMessage(this, id, isMms)
            runOnUiThread { sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName)); refresh() }
        }.start()
    }

    private fun highlight(text: String, q: String): CharSequence {
        val s = SpannableString(text)
        if (q.isEmpty()) return s
        val lo = text.lowercase(); val ql = q.lowercase()
        var i = lo.indexOf(ql)
        while (i >= 0) {
            s.setSpan(BackgroundColorSpan(0xFFFFF176.toInt()), i, i + q.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(Color.BLACK), i, i + q.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = lo.indexOf(ql, i + q.length)
        }
        return s
    }

    private fun highlightByRegex(text: String, pattern: String): CharSequence {
        val s = SpannableString(text)
        val rx = RegexRepo.compile(pattern) ?: return s
        try {
            for (mr in rx.findAll(text)) {
                val st = mr.range.first; val en = mr.range.last + 1
                if (en > st) {
                    s.setSpan(BackgroundColorSpan(0xFFFFF176.toInt()), st, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ForegroundColorSpan(Color.BLACK), st, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } catch (_: Exception) {}
        return s
    }

    private fun firstRegexMatch(text: String, pattern: String): String? =
        try { RegexRepo.compile(pattern)?.find(text)?.value?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    // Theme-aware text colours so rows are readable in BOTH light and dark mode (hardcoded near-white
    // for "unread" was invisible on a light background — the "very dim" bug).
    private val colPrimary by lazy { themeColor(android.R.attr.textColorPrimary) }
    private val colSecondary by lazy { themeColor(android.R.attr.textColorSecondary) }
    // Extra-heavy (weight 900) for unread rows so they read darker than plain bold. Falls back to
    // bold pre-API 28 (Typeface.create(family, weight, italic) is API 28+).
    private val heavyTypeface: Typeface by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, 900, false)
        else Typeface.DEFAULT_BOLD
    }
    private fun android.widget.TextView.applyWeight(unread: Boolean) {
        if (unread) typeface = heavyTypeface else setTypeface(typeface, Typeface.NORMAL)
    }
    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
    }

    private fun selectableItemBg(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val r = a.getResourceId(0, 0); a.recycle(); return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
