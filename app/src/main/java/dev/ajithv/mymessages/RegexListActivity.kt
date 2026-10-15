package dev.ajithv.mymessages

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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

/** Manage the regex SMS filters: add / list / delete BODY (text) and SENDER patterns; tap a pattern
 *  to view the messages it matches. A SENDER pattern matches the sender's number / short-code, so one
 *  rule hides every message from a matching sender (from Conversations and the Received feed) and
 *  silently blocks future incoming ones — like a body text filter, but keyed on who it's from. */
class RegexListActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var input: EditText
    private lateinit var senderBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        root.addView(TextView(this).apply {
            text = "Text filters (regex)"; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Incoming SMS matching any pattern is blocked (stored, but no notification) and hidden " +
                "from Conversations. Case-insensitive, matches anywhere. Tick the Sender box to match the " +
                "sender's number / short-code instead of the message text."
            textSize = 12f; setPadding(0, dp(4), 0, dp(8))
        })

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "New regex pattern"; setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        senderBox = CheckBox(this).apply { text = "Sender" }
        addRow.addView(input)
        addRow.addView(senderBox)
        addRow.addView(Button(this).apply { text = "Add"; setOnClickListener { addPattern() } })
        root.addView(addRow)

        empty = TextView(this).apply {
            text = "No filters yet."; setPadding(dp(4), dp(16), dp(4), dp(16)); visibility = View.GONE
        }
        root.addView(empty)

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(rows)
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun addPattern() {
        val p = input.text.toString().trim()
        if (p.isEmpty()) return
        val ok = if (senderBox.isChecked) RegexRepo.addSenderPattern(this, p) else RegexRepo.addPattern(this, p)
        if (ok) { input.setText(""); render() }
        else Toast.makeText(this, "Invalid or duplicate regex", Toast.LENGTH_LONG).show()
    }

    private fun render() {
        val body = RegexRepo.patterns(this)
        val sender = RegexRepo.senderPatterns(this)
        rows.removeAllViews()
        empty.visibility = if (body.isEmpty() && sender.isEmpty()) View.VISIBLE else View.GONE
        for (p in body) rows.addView(row(p, false))
        for (p in sender) rows.addView(row(p, true))
    }

    private fun row(pattern: String, sender: Boolean): View {
        val rowLp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            setBackgroundResource(selectableItemBg())
            setOnClickListener {
                startActivity(Intent(this@RegexListActivity, RegexMatchesActivity::class.java)
                    .putExtra(RegexMatchesActivity.EXTRA_PATTERN, pattern)
                    .putExtra(RegexMatchesActivity.EXTRA_SENDER, sender))
            }
        }
        col.addView(TextView(this).apply {
            text = if (sender) "SENDER" else "TEXT"; textSize = 11f
            setTextColor(if (sender) 0xFF1565C0.toInt() else Color.GRAY)
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = RegexRepo.displayLabel(pattern); typeface = Typeface.MONOSPACE; textSize = 15f
        })
        rowLp.addView(col)
        rowLp.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener {
                if (sender) RegexRepo.removeSenderPattern(this@RegexListActivity, pattern)
                else RegexRepo.removePattern(this@RegexListActivity, pattern)
                render()
            }
        })
        return rowLp
    }

    private fun selectableItemBg(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val r = a.getResourceId(0, 0); a.recycle(); return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
