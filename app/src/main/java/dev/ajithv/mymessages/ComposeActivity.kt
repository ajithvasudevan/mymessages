package dev.ajithv.mymessages

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Compose a new message. The "To" field is a chip/pill recipient picker with search-as-you-type
 * contact autocomplete: each contact NUMBER is a separate suggestion (Home / Work selectable
 * independently), a pick becomes a removable pill, and the message is sent to every selected number.
 * Also the app's SENDTO/SEND handler (the default-SMS-app compose activity).
 */
class ComposeActivity : AppCompatActivity() {

    private val recipients = ArrayList<SmsRepo.Contact>()
    private lateinit var chips: ChipGroup
    private lateinit var actv: AutoCompleteTextView
    private lateinit var body: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.btn_compose); textSize = 20f
            setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(12))
        })
        root.addView(TextView(this).apply { text = "To:"; textSize = 13f; setPadding(0, 0, 0, dp(4)) })

        chips = ChipGroup(this).apply { isSingleLine = false }
        root.addView(chips)

        actv = AutoCompleteTextView(this).apply {
            hint = "Search name or number"
            setSingleLine(true)
            threshold = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val adapter = ContactAdapter()
        actv.setAdapter(adapter)
        // Solid dark popup so the (white) suggestion text is always high-contrast, regardless of the
        // day/night theme's translucent default popup background.
        actv.setDropDownBackgroundDrawable(ColorDrawable(0xFF1E1E22.toInt()))
        actv.setOnItemClickListener { _, _, pos, _ ->
            adapter.getItem(pos)?.let { addRecipient(it) }
            actv.setText("")
        }
        root.addView(actv)

        body = EditText(this).apply {
            hint = getString(R.string.msg_type)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(12) }
            minLines = 3; gravity = Gravity.TOP
        }
        root.addView(body)

        root.addView(Button(this).apply {
            text = getString(R.string.msg_send)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(12) }
            setOnClickListener { send() }
        })
        setContentView(root)

        prefillFromIntent()
    }

    // ---------------------------------------------------------------- recipients

    private fun addRecipient(ct: SmsRepo.Contact) {
        val norm = ct.number.filter { !it.isWhitespace() }
        if (recipients.any { it.number.filter { ch -> !ch.isWhitespace() } == norm }) {
            Toast.makeText(this, "${ct.display()} already added", Toast.LENGTH_SHORT).show(); return
        }
        recipients.add(ct)
        val chip = Chip(this).apply {
            text = ct.display()
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                chips.removeView(this)
                recipients.remove(ct)
            }
        }
        chips.addView(chip)
    }

    private fun prefillFromIntent() {
        val data: Uri? = intent?.data
        if (data != null && data.scheme in listOf("sms", "smsto", "mms", "mmsto")) {
            val num = data.schemeSpecificPart?.trim().orEmpty()
            if (num.isNotEmpty()) addRecipient(SmsRepo.Contact(SmsRepo.contactName(this, num), num, ""))
        }
        val text = intent?.getStringExtra("sms_body") ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrEmpty()) body.setText(text)
    }

    // ---------------------------------------------------------------- send

    private fun send() {
        // A number typed but not picked from the dropdown still counts as a recipient.
        val typed = actv.text.toString().trim()
        if (typed.isNotEmpty()) { addRecipient(SmsRepo.Contact("", typed, "")); actv.setText("") }

        val msg = body.text.toString().trim()
        if (recipients.isEmpty()) { Toast.makeText(this, "Add at least one recipient", Toast.LENGTH_SHORT).show(); return }
        if (msg.isEmpty()) { Toast.makeText(this, "Enter a message", Toast.LENGTH_SHORT).show(); return }
        if (!SmsRepo.isDefault(this)) {
            Toast.makeText(this, "Set MyMessages as the default SMS app to send", Toast.LENGTH_LONG).show(); return
        }
        val targets = recipients.toList()
        Thread {
            var firstErr: String? = null
            for (r in targets) {
                val e = SmsRepo.sendSms(this, r.number, msg)
                if (e != null && firstErr == null) firstErr = e
            }
            // Resolve the recipient name off-thread; ThreadActivity resolves the threadId off-thread too.
            val single = targets.size == 1
            val addr = if (single) targets[0].number else ""
            val nm = if (single) targets[0].name.ifEmpty { SmsRepo.contactName(this, addr) } else ""
            runOnUiThread {
                if (firstErr != null) { Toast.makeText(this, "Couldn't send: $firstErr", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                if (single) {
                    startActivity(Intent(this, ThreadActivity::class.java)
                        .putExtra(ThreadActivity.EXTRA_ADDRESS, addr)
                        .putExtra(ThreadActivity.EXTRA_NAME, nm))
                } else {
                    Toast.makeText(this, "Sending to ${targets.size} recipients", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }.start()
    }

    // ---------------------------------------------------------------- contact autocomplete adapter

    /** Filterable adapter: performFiltering (worker thread) queries contacts as you type. */
    private inner class ContactAdapter : BaseAdapter(), Filterable {
        private var items: List<SmsRepo.Contact> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int): SmsRepo.Contact? = items.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@ComposeActivity).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundColor(0xFF1E1E22.toInt())   // opaque, so nothing shows through / overlaps
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
            }
            val ct = items[position]
            val label = if (ct.label.isNotBlank()) "  ·  ${ct.label}" else ""
            tv.text = "${ct.name.ifBlank { ct.number }}$label\n${ct.number}"
            return tv
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val list = SmsRepo.searchContacts(this@ComposeActivity, constraint?.toString().orEmpty())
                return FilterResults().apply { values = list; count = list.size }
            }
            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                items = (results?.values as? List<SmsRepo.Contact>) ?: emptyList()
                if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
            }
            // We clear the field on pick, so the completion text doesn't matter.
            override fun convertResultToString(resultValue: Any?): CharSequence =
                (resultValue as? SmsRepo.Contact)?.number ?: ""
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
