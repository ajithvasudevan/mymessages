package dev.ajithv.mymessages

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Standalone screen that lists every filtered notification rule (app+category, sender, or text),
 * each removable, plus a Clear-all. Opened from the "Manage filter" button on [MainActivity].
 * All state lives in [Prefs]; this only renders and edits it.
 */
class FilterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_filter)
        findViewById<Button>(R.id.btn_clear_filter).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Clear the whole notification filter?")
                .setPositiveButton(android.R.string.ok) { _, _ -> Prefs.clearFilter(this); renderFilter() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        renderFilter()
    }

    override fun onResume() { super.onResume(); renderFilter() }

    /** Rebuild the removable filter list (on create/resume, and after each remove/clear). */
    private fun renderFilter() {
        val list = findViewById<LinearLayout>(R.id.bl_list)
        list.removeAllViews()
        val entries = Prefs.filterEntries(this).sortedBy { it.second.lowercase() }
        findViewById<Button>(R.id.btn_clear_filter).visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.notif_filter_none)
                setPadding(0, px(4), 0, px(4)); alpha = 0.6f
            })
            return
        }
        for ((key, label) in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, px(6), px(8), px(6))
            })
            row.addView(Button(this).apply {
                text = "✕"
                minWidth = px(48)
                setOnClickListener { Prefs.removeFilter(this@FilterActivity, key); renderFilter() }
            })
            list.addView(row)
        }
    }
}
