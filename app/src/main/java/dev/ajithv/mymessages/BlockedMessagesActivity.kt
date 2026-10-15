package dev.ajithv.mymessages

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Blocked messages — every received SMS that is CURRENTLY blocked, newest first. "Blocked by any means"
 * = the sender is in the app block list OR Android's system blocked-number list, OR the body matches a
 * regex text filter (the same three rules [SmsReceiver] applies and [SmsRepo.conversations]/[SmsRepo.inbox]
 * hide). Each row shows the message, the sender and the received time.
 *
 * **Search-as-you-type** (top bar, Regex toggle) filters the loaded list IN MEMORY — the blocked set is
 * bounded, so matching + highlight are instant. The query matches the sender (name/address) OR the body,
 * substring or regex, case-insensitive, with the match highlighted (same look as the Conversations search).
 *
 * The list is DYNAMIC: [SmsRepo.blockedMessages] re-derives it from the live rules on every [render], so a
 * message vanishes from here the instant the rule blocking it is removed — and, because the conversation /
 * inbox lists use the very same rules, it reappears there automatically ("moves back to Conversations").
 *
 * **Long-press a row →** the rule(s) blocking it (each removable) + two PERMANENT deletes (confirm first).
 */
class BlockedMessagesActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var count: TextView
    private lateinit var search: EditText
    private lateinit var regexBox: CheckBox
    private val handler = Handler(Looper.getMainLooper())
    private var filterRunnable: Runnable? = null

    /** The full blocked set as last loaded from the provider; the search filters this in memory. */
    private var allRows: List<Row> = emptyList()

    /** A blocked message plus the precomputed display + unblock facts (gathered off-thread in [render],
     *  so building views and opening the menu never touch the provider on the UI thread). */
    private data class Row(
        val m: SmsRepo.Message, val name: String, val timeText: String,
        val senderBlocked: Boolean, val patterns: List<String>, val senderPatterns: List<String>) {
        val sender: String get() = name.ifEmpty { m.address.ifEmpty { "(unknown)" } }
        val reason: String get() {
            val parts = ArrayList<String>(3)
            if (senderBlocked) parts.add("blocked sender")
            if (patterns.isNotEmpty()) parts.add("text filter")
            if (senderPatterns.isNotEmpty()) parts.add("sender filter")
            return parts.joinToString(" + ").ifEmpty { "blocked" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = "Blocked messages"; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(12), dp(16), dp(2))
        })
        root.addView(TextView(this).apply {
            text = "Received SMS currently hidden by a block rule — a blocked sender, Android's block " +
                "list, or a text filter. Long-press a message to unblock its sender or delete it."
            textSize = 12f; alpha = 0.7f; setPadding(dp(16), 0, dp(16), dp(6))
        })

        // Search bar (pinned above the scrolling list).
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(2))
        }
        search = EditText(this).apply {
            hint = "Search sender or message"; setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = scheduleFilter()
            })
        }
        bar.addView(search)
        bar.addView(TextView(this).apply {
            text = "✕"; textSize = 20f; setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true; setBackgroundResource(selectableItemBg())
            setOnClickListener { search.setText(""); search.clearFocus() }
        })
        root.addView(bar)
        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(2))
        }
        regexBox = CheckBox(this).apply {
            text = "Regex"
            setOnCheckedChangeListener { _, _ -> applyFilter() }
        }
        optRow.addView(regexBox)
        root.addView(optRow)

        count = TextView(this).apply {
            textSize = 12f; alpha = 0.7f; setPadding(dp(16), 0, dp(16), dp(6))
        }
        root.addView(count)
        empty = TextView(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(12)); visibility = View.GONE
        }
        root.addView(empty)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(rows)
        })

        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun query() = if (::search.isInitialized) search.text.toString().trim() else ""

    private fun scheduleFilter() {
        filterRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { applyFilter() }
        filterRunnable = r
        handler.postDelayed(r, 120)   // in-memory filter, so a tiny debounce just coalesces fast typing
    }

    /** Reload the blocked set from the provider (off-thread), then re-apply the current search. */
    private fun render() {
        count.text = "…"
        Thread {
            val msgs = SmsRepo.blockedMessages(this)
            // Prefetch the three rule sets ONCE and test each row against them (matchesLocal /
            // containsMatchIn) — no per-row system-block content query, and the menu's facts are ready
            // without touching the provider when it opens.
            val appBlocked = BlockRepo.blocked(this)
            val sysBlocked = BlockRepo.systemBlockedNumbers(this)
            val compiled = RegexRepo.patterns(this).map { it to RegexRepo.compile(it) }
            val compiledSender = RegexRepo.senderPatterns(this).map { it to RegexRepo.compile(it) }
            val fmt = SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault())
            val built = msgs.map { m ->
                val senderBlocked = BlockRepo.matchesLocal(appBlocked, m.address) ||
                    BlockRepo.matchesLocal(sysBlocked, m.address)
                val pats = compiled.filter { (_, rx) -> rx?.containsMatchIn(m.body) == true }.map { it.first }
                val spats = compiledSender.filter { (_, rx) -> rx?.containsMatchIn(m.address) == true }.map { it.first }
                Row(m, SmsRepo.contactName(this, m.address), fmt.format(Date(m.date)), senderBlocked, pats, spats)
            }
            runOnUiThread { allRows = built; applyFilter() }
        }.start()
    }

    /** Filter [allRows] by the current query (sender name/address OR body; regex or substring) and paint. */
    private fun applyFilter() {
        val q = query()
        val rx = SearchUtil.matcher(q, regexBox.isChecked)   // null = empty query or invalid regex → show all
        val shown = if (rx == null) allRows
            else allRows.filter { SearchUtil.matchesAny(rx, it.name, it.m.address, it.m.body) }
        rows.removeAllViews()
        when {
            allRows.isEmpty() -> { empty.text = "No blocked messages."; empty.visibility = View.VISIBLE }
            shown.isEmpty() -> { empty.text = "No blocked messages match."; empty.visibility = View.VISIBLE }
            else -> empty.visibility = View.GONE
        }
        count.text = when {
            allRows.isEmpty() -> ""
            rx != null -> "${shown.size} of ${allRows.size} blocked message(s)"
            else -> "${allRows.size} blocked message(s)"
        }
        for (r in shown) rows.addView(row(r, rx))
    }

    private fun row(r: Row, rx: Regex?): View {
        val base = Prefs.smsFontSp(this).toFloat()   // same list font as the conversation/received lists
        val col = itemColumn(onClick = { openThread(r) }, onLongClick = { showMenu(r) })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = SearchUtil.highlight(r.sender, rx); textSize = base + 1f
            setTextColor(colPrimary); setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = r.timeText; textSize = base - 2f; setTextColor(colSecondary); setPadding(dp(8), 0, 0, 0)
        })
        col.addView(top)

        // Raw address under the name, only when a contact name resolved and differs from it.
        if (r.name.isNotEmpty() && !r.name.equals(r.m.address, ignoreCase = true) && r.m.address.isNotEmpty()) {
            col.addView(TextView(this).apply {
                text = SearchUtil.highlight(r.m.address, rx); textSize = (base - 3f).coerceAtLeast(9f)
                setTextColor(colSecondary)
            })
        }
        col.addView(TextView(this).apply {
            text = SearchUtil.highlight(r.m.body, rx); textSize = base; setTextColor(colSecondary)
            maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = "⛔ ${r.reason}"; textSize = (base - 3f).coerceAtLeast(9f); setTextColor(colSecondary); alpha = 0.7f
        })
        return col
    }

    private fun openThread(r: Row) {
        startActivity(Intent(this, ThreadActivity::class.java)
            .putExtra(ThreadActivity.EXTRA_THREAD_ID, r.m.threadId)
            .putExtra(ThreadActivity.EXTRA_ADDRESS, r.m.address)
            .putExtra(ThreadActivity.EXTRA_NAME, r.name))
    }

    /** Long-press menu. First the rule(s) blocking THIS message, each removable (removing all of them is
     *  what lets it resurface in Conversations); then two PERMANENT delete actions. Unblock / filter-removal
     *  run straight off-thread; the deletes confirm first (they can't be undone). */
    private fun showMenu(r: Row) {
        val labels = ArrayList<String>()
        val handlers = ArrayList<() -> Unit>()   // run on the UI thread when picked
        if (r.senderBlocked) {
            labels.add("Unblock sender" + (if (r.m.address.isNotEmpty()) " (${r.m.address})" else ""))
            handlers.add { runOffThread("Unblocked — moved back to Conversations") {
                BlockRepo.unblock(this@BlockedMessagesActivity, r.m.address) } }
        }
        for (p in r.patterns) {
            labels.add("Remove text filter: ${RegexRepo.displayLabel(p)}")
            handlers.add { runOffThread("Filter removed") {
                RegexRepo.removePattern(this@BlockedMessagesActivity, p) } }
        }
        for (p in r.senderPatterns) {
            labels.add("Remove sender filter: ${RegexRepo.displayLabel(p)}")
            handlers.add { runOffThread("Filter removed") {
                RegexRepo.removeSenderPattern(this@BlockedMessagesActivity, p) } }
        }
        labels.add("Delete this message")
        handlers.add { confirmDelete("Delete this message?",
            "Permanently delete this message? This cannot be undone.") {
            SmsRepo.deleteMessage(this@BlockedMessagesActivity, r.m.id, r.m.isMms) } }
        labels.add("Delete all messages from this sender")
        handlers.add { confirmDelete("Delete all from ${r.sender}?",
            "Permanently delete ALL messages from ${r.sender}? This cannot be undone.") {
            val tid = if (r.m.threadId > 0) r.m.threadId
                      else SmsRepo.threadId(this@BlockedMessagesActivity, r.m.address)
            SmsRepo.deleteThread(this@BlockedMessagesActivity, tid) } }
        AlertDialog.Builder(this)
            .setTitle(r.sender)
            .setItems(labels.toTypedArray()) { _, which -> handlers[which]() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Run [op] off the main thread, then toast [done], broadcast the provider change and re-render. */
    private fun runOffThread(done: String, op: () -> Unit) {
        Thread {
            op()
            runOnUiThread {
                toast(done)
                sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName))
                render()
            }
        }.start()
    }

    /** Confirm a permanent delete, then run it off-thread. */
    private fun confirmDelete(title: String, msg: String, op: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> runOffThread("Deleted") { op() } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------- helpers (mirrors the list screens)

    private fun itemColumn(onClick: () -> Unit, onLongClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isClickable = true
            setBackgroundResource(selectableItemBg())
            setOnClickListener { onClick() }
            setOnLongClickListener { onLongClick(); true }
        }

    private val colPrimary by lazy { themeColor(android.R.attr.textColorPrimary) }
    private val colSecondary by lazy { themeColor(android.R.attr.textColorSecondary) }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
    }

    private fun selectableItemBg(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val r = a.getResourceId(0, 0); a.recycle(); return r
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
