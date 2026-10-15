package dev.ajithv.mymessages

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan

/**
 * Shared search helpers for the Blocked-senders / Blocked-messages screens: case-insensitive,
 * regex-or-substring matching with the match highlighted — the same yellow look as the Conversations
 * search. A single compiled [Regex] drives BOTH the match test and the highlight so they never disagree.
 */
object SearchUtil {
    private val HL = 0xFFFFF176.toInt()   // yellow highlight, matches ConversationListActivity

    /** A matcher for [q]: regex=false → the literal substring (q escaped), regex=true → q as a regex.
     *  Always case-insensitive. Returns null for an empty query OR an invalid regex (caller then shows
     *  the unfiltered list rather than a jarring blank while a pattern is half-typed). */
    fun matcher(q: String, regex: Boolean): Regex? {
        if (q.isEmpty()) return null
        return try {
            Regex(if (regex) q else Regex.escape(q), RegexOption.IGNORE_CASE)
        } catch (_: Exception) { null }
    }

    /** True if any of [hays] contains a match for [rx]. */
    fun matchesAny(rx: Regex, vararg hays: String): Boolean =
        hays.any { it.isNotEmpty() && rx.containsMatchIn(it) }

    /** [text] with every [rx] match highlighted (yellow bg, black fg). No-op when [rx] is null. */
    fun highlight(text: String, rx: Regex?): CharSequence {
        val s = SpannableString(text)
        if (rx == null || text.isEmpty()) return s
        try {
            for (m in rx.findAll(text)) {
                val st = m.range.first; val en = m.range.last + 1
                if (en > st) {
                    s.setSpan(BackgroundColorSpan(HL), st, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ForegroundColorSpan(Color.BLACK), st, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } catch (_: Exception) {}
        return s
    }
}
