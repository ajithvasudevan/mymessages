package dev.ajithv.mymessages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** A single conversation: SMS + MMS bubbles read from the provider, with an SMS reply box. */
class ThreadActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        const val EXTRA_HIGHLIGHT_ID = "hl_id"       // message _id to jump to
        const val EXTRA_HIGHLIGHT_QUERY = "hl_q"     // phrase to highlight in it
    }

    private var threadId = 0L
    private var address = ""
    private var name = ""
    private var highlightId = 0L
    private var highlightQuery = ""
    private var addressBlocked = false   // resolved off-thread in load(), read by updateBlockBtn()

    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var header: TextView
    private lateinit var blockBtn: Button

    // Pinch-to-zoom the bubble font (persisted in Prefs.smsFontSp, shared with the list + SMS-tab slider).
    private lateinit var scaleDetector: ScaleGestureDetector
    private var pinchSpF = 0f
    private var lastList: List<SmsRepo.Message> = emptyList()
    private var lastBmps: Map<Long, Bitmap> = emptyMap()

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threadId = intent.getLongExtra(EXTRA_THREAD_ID, 0L)
        address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        highlightId = intent.getLongExtra(EXTRA_HIGHLIGHT_ID, 0L)
        highlightQuery = intent.getStringExtra(EXTRA_HIGHLIGHT_QUERY).orEmpty()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
        }
        header = TextView(this).apply {
            text = name.ifEmpty { address }.ifEmpty { "Conversation" }
            textSize = 19f; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(header)
        blockBtn = Button(this).apply { setOnClickListener { toggleBlock() } }
        headerRow.addView(blockBtn)
        root.addView(headerRow)
        updateBlockBtn()

        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(messages)
        root.addView(scroll)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        input = EditText(this).apply {
            hint = getString(R.string.msg_type)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 5
        }
        bar.addView(input)
        bar.addView(Button(this).apply {
            text = getString(R.string.msg_send)
            setOnClickListener { send() }
        })
        root.addView(bar)
        setContentView(root)

        // Pinch anywhere to zoom the bubble font (persisted, shared with the conversation list + SMS-tab
        // slider). Re-renders the current bubbles per integer-sp step without reloading the thread.
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pinchSpF = Prefs.smsFontSp(this@ThreadActivity).toFloat(); return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinchSpF = (pinchSpF * d.scaleFactor)
                    .coerceIn(Prefs.SMS_FONT_MIN.toFloat(), Prefs.SMS_FONT_MAX.toFloat())
                val sp = Math.round(pinchSpF)
                if (sp != Prefs.smsFontSp(this@ThreadActivity)) {
                    Prefs.setSmsFontSp(this@ThreadActivity, sp); render(lastList, lastBmps)
                }
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) return true   // a pinch owns the gesture (don't scroll bubbles)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, changed,
            IntentFilter(SmsRepo.ACTION_SMS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        load()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(changed) } catch (_: Exception) {}
    }

    /** Everything the provider/contacts/MMS-decode needs runs on a worker thread; only the finished
     *  view build touches the UI. Previously threadId resolution, mark-read, the block-state query and —
     *  worst of all — every MMS image decode ran on the UI thread, stalling the screen on image threads. */
    private fun load() {
        val addr0 = address
        val tid0 = threadId
        Thread {
            var tid = tid0
            if (tid <= 0 && addr0.isNotEmpty()) tid = SmsRepo.threadId(this, addr0)
            if (tid > 0) SmsRepo.markThreadRead(this, tid)
            val list = if (tid > 0) SmsRepo.messagesForThread(this, tid) else emptyList()
            // Recover the address/name off-thread if we arrived without one (e.g. from history).
            var addrR = addr0
            var nameR = name
            if (addrR.isEmpty()) {
                list.firstOrNull { it.incoming && it.address.isNotEmpty() }?.let {
                    addrR = it.address
                    if (nameR.isEmpty()) nameR = SmsRepo.contactName(this, addrR)
                }
            }
            val blocked = addrR.isNotEmpty() && BlockRepo.isBlocked(this, addrR)
            // Decode all MMS image parts here, on the worker, keyed by part id.
            val bmps = HashMap<Long, Bitmap>()
            for (m in list) for (pid in m.imagePartIds)
                if (!bmps.containsKey(pid)) SmsRepo.mmsPartBitmap(this, pid)?.let { bmps[pid] = it }
            runOnUiThread {
                threadId = tid; address = addrR; name = nameR; addressBlocked = blocked
                header.text = name.ifEmpty { address }.ifEmpty { "Conversation" }
                updateBlockBtn()
                render(list, bmps)
            }
        }.start()
    }

    private fun render(list: List<SmsRepo.Message>, bmps: Map<Long, Bitmap>) {
        lastList = list; lastBmps = bmps   // kept so a font pinch can re-render without reloading
        messages.removeAllViews()
        var target: View? = null
        for (m in list) {
            val v = bubble(m, bmps)
            messages.addView(v)
            if (highlightId != 0L && m.id == highlightId && !m.isMms) target = v
        }
        scroll.post {
            val t = target
            if (t != null) scroll.smoothScrollTo(0, (t.top - dp(8)).coerceAtLeast(0))
            else scroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Highlight every case-insensitive occurrence of [q] in [text] (yellow bg, black text). */
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

    /** Cheap — reads the pre-resolved [addressBlocked] flag; no content query (the isBlocked lookup
     *  hits the system BlockedNumberContract, so it's done off-thread in load()/toggleBlock()). */
    private fun updateBlockBtn() {
        blockBtn.isEnabled = address.isNotEmpty()
        blockBtn.text = if (address.isNotEmpty() && addressBlocked) "Unblock" else "Block"
    }

    private fun toggleBlock() {
        val addr = address
        if (addr.isEmpty()) { Toast.makeText(this, "No sender to block", Toast.LENGTH_SHORT).show(); return }
        blockBtn.isEnabled = false
        Thread {
            val nowBlocked = if (BlockRepo.isBlocked(this, addr)) { BlockRepo.unblock(this, addr); false }
                             else { BlockRepo.block(this, addr); true }
            runOnUiThread {
                addressBlocked = nowBlocked
                updateBlockBtn()
                Toast.makeText(this, if (nowBlocked) "Blocked $addr" else "Unblocked", Toast.LENGTH_SHORT).show()
                sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName))
            }
        }.start()
    }

    private fun bubble(m: SmsRepo.Message, bmps: Map<Long, Bitmap>): View {
        val base = Prefs.smsFontSp(this).toFloat()   // pinch/slider-controlled font (shared with the list)
        val incoming = m.incoming
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(3); it.bottomMargin = dp(3) }
            gravity = if (incoming) Gravity.START else Gravity.END
        }
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (incoming) 0xFF33333A.toInt() else 0xFF0B6BCB.toInt())
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(6); lp.marginEnd = dp(6)
            layoutParams = lp
        }
        val cap = (resources.displayMetrics.widthPixels * 0.82).toInt()
        // MMS images (pre-decoded off the UI thread in load())
        for (partId in m.imagePartIds) {
            val bmp = bmps[partId] ?: continue
            bubble.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxWidth = cap
                maxHeight = resources.displayMetrics.heightPixels / 2
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(4); layoutParams = lp
            })
        }
        val bodyText = if (m.body.isNotEmpty()) m.body else if (m.isMms && m.imagePartIds.isNotEmpty()) "" else if (m.isMms) "[MMS]" else ""
        if (bodyText.isNotEmpty()) bubble.addView(TextView(this).apply {
            text = if (m.id == highlightId && highlightQuery.isNotEmpty()) highlight(bodyText, highlightQuery) else bodyText
            setTextColor(Color.WHITE); textSize = base + 2f; maxWidth = cap
        })
        bubble.addView(TextView(this).apply {
            text = SmsRepo.fmtTime(m.date); textSize = (base - 3f).coerceAtLeast(9f)
            setTextColor(0xFFCCCCCC.toInt()); gravity = Gravity.END
        })
        wrap.addView(bubble)
        wrap.setOnLongClickListener { confirmDeleteMessage(m); true }
        return wrap
    }

    private fun confirmDeleteMessage(m: SmsRepo.Message) {
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete this message from the SMS database?")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    SmsRepo.deleteMessage(this, m.id, m.isMms)
                    runOnUiThread {
                        sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(packageName))
                        load()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun send() {
        val body = input.text.toString().trim()
        if (body.isEmpty()) return
        if (address.isEmpty()) { Toast.makeText(this, "No recipient", Toast.LENGTH_SHORT).show(); return }
        if (!SmsRepo.isDefault(this)) {
            Toast.makeText(this, "Set MyMessages as the default SMS app to send", Toast.LENGTH_LONG).show(); return
        }
        input.setText("")
        Thread {
            val err = SmsRepo.sendSms(this, address, body)   // null = dispatched OK (result via SmsSentReceiver)
            runOnUiThread {
                if (err != null) Toast.makeText(this, "Couldn't send: $err", Toast.LENGTH_LONG).show()
                if (threadId <= 0) threadId = SmsRepo.threadId(this, address)
                load()
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
