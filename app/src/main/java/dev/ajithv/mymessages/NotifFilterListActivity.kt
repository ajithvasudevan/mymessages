package dev.ajithv.mymessages

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

/**
 * Manage the NOTIFICATION-overlay filters ([NotifFilterRepo]): add / list / delete substring or regex
 * rules. A notification whose app / sender / body matches any rule is hidden from the overlay pop and
 * from its history paging (still recorded, like a filter entry). Opened from the "Notification
 * filters" button on [MainActivity]'s notification-pop-up settings.
 */
class NotifFilterListActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var input: EditText
    private lateinit var regexBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        root.addView(TextView(this).apply {
            text = "Notification filters"; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "A notification whose app name, sender or body matches any rule is hidden from the " +
                "overlay pop-up and its history. Substring is case-insensitive; tick Regex for a pattern."
            textSize = 12f; setPadding(0, dp(4), 0, dp(8))
        })

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Substring or regex to hide"; setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addRow.addView(input)
        regexBox = CheckBox(this).apply { text = "Regex" }
        addRow.addView(regexBox)
        addRow.addView(Button(this).apply { text = "Add"; setOnClickListener { addFilter() } })
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

    private fun addFilter() {
        val v = input.text.toString().trim()
        if (v.isEmpty()) return
        if (NotifFilterRepo.addFilter(this, v, regexBox.isChecked)) { input.setText(""); render() }
        else Toast.makeText(this, "Invalid or duplicate filter", Toast.LENGTH_LONG).show()
    }

    private fun render() {
        val list = NotifFilterRepo.filters(this)
        rows.removeAllViews()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (f in list) rows.addView(row(f))
    }

    private fun row(f: NotifFilterRepo.Filter): View {
        val rowLp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = f.value; typeface = Typeface.MONOSPACE; textSize = 15f
        })
        col.addView(TextView(this).apply {
            text = if (f.regex) "regex" else "substring"; textSize = 12f; setTextColor(Color.GRAY)
        })
        rowLp.addView(col)
        rowLp.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener { NotifFilterRepo.removeFilter(this@NotifFilterListActivity, f.value, f.regex); render() }
        })
        return rowLp
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
