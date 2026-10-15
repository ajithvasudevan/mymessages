package dev.ajithv.mymessages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat

/** Flat list of received SMS (the provider's Inbox), newest first. Tap a row → its thread. */
class MessageListActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "Received SMS"; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        })
        empty = TextView(this).apply {
            text = "No received messages (or SMS permission not granted)."
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
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, changed,
            IntentFilter(SmsRepo.ACTION_SMS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        render(SmsRepo.cachedInbox(this), fresh = false)   // instant paint from cache
        load()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(changed) } catch (_: Exception) {}
    }

    private fun load() {
        Thread {
            val list = SmsRepo.inbox(this)
            SmsRepo.cacheInbox(this, list)
            runOnUiThread { render(list, fresh = true) }
        }.start()
    }

    private fun render(list: List<SmsRepo.Message>, fresh: Boolean = true) {
        if (!fresh && list.isEmpty()) return
        rows.removeAllViews()
        empty.visibility = if (fresh && list.isEmpty()) View.VISIBLE else View.GONE
        for (m in list) rows.addView(row(m))
    }

    private fun row(m: SmsRepo.Message): View {
        val name = SmsRepo.contactName(this, m.address).ifEmpty { m.address }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setBackgroundResource(selectableItemBg())
            setOnClickListener {
                val tid = SmsRepo.threadId(this@MessageListActivity, m.address)
                startActivity(Intent(this@MessageListActivity, ThreadActivity::class.java)
                    .putExtra(ThreadActivity.EXTRA_THREAD_ID, tid)
                    .putExtra(ThreadActivity.EXTRA_ADDRESS, m.address)
                    .putExtra(ThreadActivity.EXTRA_NAME, name))
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = name; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = SmsRepo.fmtTime(m.date); textSize = 12f; setTextColor(Color.GRAY)
        })
        col.addView(top)
        col.addView(TextView(this).apply {
            text = m.body; textSize = 14f; setTextColor(Color.GRAY)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return col
    }

    private fun selectableItemBg(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val r = a.getResourceId(0, 0); a.recycle(); return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
