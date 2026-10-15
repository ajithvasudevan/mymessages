package dev.ajithv.mymessages

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** All stored SMS matching a given regex pattern — by BODY (default) or by SENDER when [EXTRA_SENDER]
 *  is set. Tap a row to open its thread. */
class RegexMatchesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATTERN = "pattern"
        const val EXTRA_SENDER = "sender"
    }

    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView
    private var pattern = ""
    private var sender = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pattern = intent.getStringExtra(EXTRA_PATTERN).orEmpty()
        sender = intent.getBooleanExtra(EXTRA_SENDER, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = if (sender) "Matches (sender)" else "Matches"
            textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(12), dp(16), dp(2))
        })
        root.addView(TextView(this).apply {
            text = RegexRepo.displayLabel(pattern); typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.GRAY)
            setPadding(dp(16), 0, dp(16), dp(10))
        })
        empty = TextView(this).apply {
            text = "Loading…"; setPadding(dp(24), dp(24), dp(24), dp(24))
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

    override fun onResume() {
        super.onResume()
        Thread {
            val list = if (sender) SmsRepo.messagesFromSendersMatching(this, pattern)
                       else SmsRepo.messagesMatching(this, pattern)
            for (m in list) SmsRepo.contactName(this, m.address)   // pre-warm the name cache off-thread
            runOnUiThread { render(list) }
        }.start()
    }

    private fun render(list: List<SmsRepo.Message>) {
        rows.removeAllViews()
        empty.text = "No matching messages."
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (m in list) rows.addView(row(m))
    }

    private fun row(m: SmsRepo.Message): View {
        val name = SmsRepo.contactName(this, m.address).ifEmpty { m.address }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setBackgroundResource(selectableItemBg())
            setOnClickListener {   // ThreadActivity resolves threadId off-thread — no query on tap
                startActivity(Intent(this@RegexMatchesActivity, ThreadActivity::class.java)
                    .putExtra(ThreadActivity.EXTRA_ADDRESS, m.address)
                    .putExtra(ThreadActivity.EXTRA_NAME, name))
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = name; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply { text = SmsRepo.fmtTime(m.date); textSize = 12f; setTextColor(Color.GRAY) })
        col.addView(top)
        col.addView(TextView(this).apply {
            text = m.body; textSize = 14f; setTextColor(Color.GRAY)
            maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return col
    }

    private fun selectableItemBg(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val r = a.getResourceId(0, 0); a.recycle(); return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
