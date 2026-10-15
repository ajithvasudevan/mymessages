package dev.ajithv.mymessages

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Blocked senders — two lists:
 *  1. App block list ([BlockRepo]): every sender MyMessages blocks, including alphanumeric short-codes
 *     (e.g. "AD-ICICI") that aren't valid phone numbers. Tap a row to view that sender's messages;
 *     Unblock removes it here (and from the system list if it's a number).
 *  2. Android's SYSTEM block list ([android.provider.BlockedNumberContract]) — the OS-wide list shared
 *     with the stock Phone / Messages apps. Editable here because MyMessages is the default SMS app:
 *     add a number, or Unblock any entry (including numbers blocked elsewhere, e.g. from the dialer).
 *
 * **Search-as-you-type** (top bar, Regex toggle) filters BOTH lists in memory. The query matches the
 * sender (raw string + resolved contact name) OR the content of any of that sender's blocked messages
 * (substring or regex, case-insensitive) — so you can find a blocked sender by something they texted. The
 * sender is highlighted; a content-only match also shows the matching message snippet. The per-sender
 * "message content" index is built once per load (off-thread), so each keystroke is instant.
 */
class BlockedListActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var sysRows: LinearLayout
    private lateinit var sysEmpty: TextView
    private lateinit var sysInput: EditText
    private lateinit var search: EditText
    private lateinit var regexBox: CheckBox
    private val handler = Handler(Looper.getMainLooper())
    private var filterRunnable: Runnable? = null

    // Loaded data cached so the search filters in memory (no re-query per keystroke).
    private var appList: List<String> = emptyList()
    private var appDisplay: Map<String, String> = emptyMap()
    private var canSys = false
    private var sysList: List<String> = emptyList()
    private var sysName: Map<String, String> = emptyMap()
    // sender string -> bodies of its currently-blocked messages (for the content search). Built once per
    // load, so a keystroke only scans a small per-sender list rather than all messages.
    private var bodiesFor: Map<String, List<String>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- Pinned search bar ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(2))
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
            text = "Regex"; setOnCheckedChangeListener { _, _ -> applyFilter() }
        }
        optRow.addView(regexBox)
        root.addView(optRow)

        // ---- Scrolling content: the two sections ----
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(header("Blocked senders (app)"))
        empty = TextView(this).apply {
            setPadding(dp(24), dp(12), dp(24), dp(12)); visibility = View.GONE
        }
        content.addView(empty)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows)

        content.addView(divider())
        content.addView(header("Android system block list"))
        content.addView(TextView(this).apply {
            text = "The OS-wide blocked-number list, shared with the Phone and Messages apps. " +
                "Blocks calls and texts. Numbers only."
            textSize = 12f; setPadding(dp(16), 0, dp(16), dp(8)); alpha = 0.7f
        })
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), dp(4))
        }
        sysInput = EditText(this).apply {
            hint = "Add a number to block"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addRow.addView(sysInput)
        addRow.addView(Button(this).apply { text = "Block"; setOnClickListener { addSystemNumber() } })
        content.addView(addRow)
        sysEmpty = TextView(this).apply {
            setPadding(dp(24), dp(8), dp(24), dp(12)); visibility = View.GONE
        }
        content.addView(sysEmpty)
        sysRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sysRows)

        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(content)
        })
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun query() = if (::search.isInitialized) search.text.toString().trim() else ""

    private fun scheduleFilter() {
        filterRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { applyFilter() }
        filterRunnable = r
        handler.postDelayed(r, 120)
    }

    /** Load both block lists + the per-sender blocked-message index on a worker, then paint via the current
     *  search. All reads here (app list, the BlockedNumberContract query, per-sender contact names, the
     *  blocked-message scan) are content queries — gather them off the UI thread. */
    private fun render() {
        Thread {
            val app = BlockRepo.blocked(this)
            val appDisp = app.associateWith { s ->
                if (BlockRepo.isNumber(s)) SmsRepo.contactName(this, s).ifEmpty { s } else s
            }
            val sysOk = BlockRepo.canUseSystemBlocks(this)
            val sys = if (sysOk) BlockRepo.systemBlockedNumbers(this) else emptyList()
            val sysN = sys.associateWith { n -> SmsRepo.contactName(this, n) }
            // Per-sender blocked-message bodies, for the content search (built once per load).
            val senders = app + sys
            val bodies = HashMap<String, MutableList<String>>()
            for (s in senders) bodies[s] = ArrayList()
            if (senders.isNotEmpty()) {
                for (m in SmsRepo.blockedMessages(this)) {
                    for (s in senders) if (BlockRepo.matchesLocal(listOf(s), m.address)) bodies[s]!!.add(m.body)
                }
            }
            runOnUiThread {
                appList = app; appDisplay = appDisp; canSys = sysOk; sysList = sys; sysName = sysN
                bodiesFor = bodies
                applyFilter()
            }
        }.start()
    }

    /** Filter both lists by the current query and paint. */
    private fun applyFilter() {
        val q = query()
        val rx = SearchUtil.matcher(q, regexBox.isChecked)   // null = empty query or invalid regex → show all

        rows.removeAllViews()
        var appShown = 0
        for (sender in appList) {
            val display = appDisplay[sender] ?: sender
            val snippet = if (rx == null) null else bodiesFor[sender].orEmpty().firstOrNull { rx.containsMatchIn(it) }
            if (rx != null && !SearchUtil.matchesAny(rx, sender, display) && snippet == null) continue
            rows.addView(appRow(sender, display, rx, snippet))
            appShown++
        }
        empty.text = when {
            appList.isEmpty() -> "No blocked senders.\nOpen a conversation and tap Block to add one."
            appShown == 0 -> "No matching blocked senders."
            else -> ""
        }
        empty.visibility = if (appList.isEmpty() || appShown == 0) View.VISIBLE else View.GONE

        sysRows.removeAllViews()
        if (!canSys) {
            sysEmpty.text = "Can't access the system block list (MyMessages must be the default SMS app)."
            sysEmpty.visibility = View.VISIBLE
            sysInput.isEnabled = false
            return
        }
        sysInput.isEnabled = true
        var sysShown = 0
        for (number in sysList) {
            val name = sysName[number].orEmpty()
            val snippet = if (rx == null) null else bodiesFor[number].orEmpty().firstOrNull { rx.containsMatchIn(it) }
            if (rx != null && !SearchUtil.matchesAny(rx, number, name) && snippet == null) continue
            sysRows.addView(systemRow(number, name, rx, snippet))
            sysShown++
        }
        sysEmpty.text = when {
            sysList.isEmpty() -> "No numbers in the system block list."
            sysShown == 0 -> "No matching numbers."
            else -> ""
        }
        sysEmpty.visibility = if (sysList.isEmpty() || sysShown == 0) View.VISIBLE else View.GONE
    }

    private fun addSystemNumber() {
        val n = sysInput.text.toString().trim()
        if (n.isEmpty()) return
        if (!BlockRepo.isNumber(n)) {
            Toast.makeText(this, "The system block list accepts phone numbers only", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val ok = BlockRepo.systemBlock(this, n)
            runOnUiThread {
                if (ok) {
                    sysInput.setText("")
                    Toast.makeText(this, "Blocked $n (system)", Toast.LENGTH_SHORT).show()
                    sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName))
                    render()
                } else {
                    Toast.makeText(this, "Couldn't add to the system block list", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** A row in the app block list: tap = view the sender's messages, Unblock = remove (app + system).
     *  [rx] highlights the sender match; [snippet] (non-null on a content-only match) shows the matching
     *  message so it's clear WHY the row is here. */
    private fun appRow(sender: String, display: String, rx: Regex?, snippet: String?): View {
        val rowLp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            setBackgroundResource(selectableItemBg())
            setOnClickListener {   // tap = view this sender's messages (ThreadActivity resolves threadId off-thread)
                startActivity(Intent(this@BlockedListActivity, ThreadActivity::class.java)
                    .putExtra(ThreadActivity.EXTRA_ADDRESS, sender)
                    .putExtra(ThreadActivity.EXTRA_NAME, if (display != sender) display else ""))
            }
        }
        col.addView(TextView(this).apply {
            text = SearchUtil.highlight(display, rx); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        if (display != sender) col.addView(TextView(this).apply {
            text = SearchUtil.highlight(sender, rx); textSize = 13f; setTextColor(Color.GRAY)
        })
        if (snippet != null) col.addView(TextView(this).apply {
            text = SearchUtil.highlight(snippet, rx); textSize = 13f; setTextColor(Color.GRAY)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        rowLp.addView(col)
        rowLp.addView(Button(this).apply {
            text = "Unblock"
            setOnClickListener {
                isEnabled = false
                Thread {
                    BlockRepo.unblock(this@BlockedListActivity, sender)
                    runOnUiThread {
                        Toast.makeText(this@BlockedListActivity, "Unblocked $sender", Toast.LENGTH_SHORT).show()
                        sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName))
                        render()
                    }
                }.start()
            }
        })
        return rowLp
    }

    /** A row in the system block list: shows the number (+ contact name if known) and an Unblock. */
    private fun systemRow(number: String, name: String, rx: Regex?, snippet: String?): View {
        val rowLp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = SearchUtil.highlight(name.ifEmpty { number }, rx); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        if (name.isNotEmpty()) col.addView(TextView(this).apply {
            text = SearchUtil.highlight(number, rx); textSize = 13f; setTextColor(Color.GRAY)
        })
        if (snippet != null) col.addView(TextView(this).apply {
            text = SearchUtil.highlight(snippet, rx); textSize = 13f; setTextColor(Color.GRAY)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        rowLp.addView(col)
        rowLp.addView(Button(this).apply {
            text = "Unblock"
            setOnClickListener {
                isEnabled = false
                Thread {
                    val ok = BlockRepo.systemUnblock(this@BlockedListActivity, number)
                    runOnUiThread {
                        Toast.makeText(this@BlockedListActivity,
                            if (ok) "Unblocked $number (system)" else "Couldn't unblock $number",
                            if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                        sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName))
                        render()
                    }
                }.start()
            }
        })
        return rowLp
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(16), dp(12), dp(16), dp(8))
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(16); bottomMargin = dp(8); leftMargin = dp(16); rightMargin = dp(16)
        }
        setBackgroundColor(0x33888888)
    }

    private fun selectableItemBg(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val r = a.getResourceId(0, 0); a.recycle(); return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
