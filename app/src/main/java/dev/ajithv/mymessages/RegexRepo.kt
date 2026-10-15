package dev.ajithv.mymessages

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * App-side regex filters for incoming / stored SMS. Two independent, user-managed pattern lists:
 *  - BODY patterns ([patterns]): a message is filtered when its TEXT matches.
 *  - SENDER patterns ([senderPatterns]): a message is filtered when its SENDER (the address / short
 *    code) matches — e.g. silence a whole rotating bank-sender family (`.*DOPBNK.*`) with one rule.
 * A filtered message is still stored in the provider (viewable on the Blocked / filter screens) but
 * produces no notification / overlay, and is hidden from the conversation & received lists.
 *
 * Matching is case-insensitive + substring ([Regex.containsMatchIn]); the SAME [compile] is used by
 * the incoming-SMS filter and the "view matches" screen so they never disagree.
 */
object RegexRepo {
    const val TAG = "RegexRepo"
    private const val FILE = "mymessages_regex"
    private const val KEY = "patterns"                  // body patterns (unchanged key — back-compat)
    private const val KEY_SENDER = "sender_patterns"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun arr(c: Context, key: String): JSONArray =
        try { JSONArray(sp(c).getString(key, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, key: String, a: JSONArray) = sp(c).edit().putString(key, a.toString()).apply()

    private fun list(c: Context, key: String): List<String> {
        val a = arr(c, key)
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }
    /** Add to a store. Returns false if blank, a duplicate, or an invalid regex. */
    private fun addTo(c: Context, key: String, pattern: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty() || compile(p) == null) return false
        if (list(c, key).any { it == p }) return false
        save(c, key, arr(c, key).put(p))
        return true
    }
    private fun removeFrom(c: Context, key: String, pattern: String) {
        val a = arr(c, key); val n = JSONArray()
        for (i in 0 until a.length()) { val v = a.optString(i); if (v != pattern && v.isNotEmpty()) n.put(v) }
        save(c, key, n)
    }
    private fun anyMatch(pats: List<String>, hay: String): Boolean {
        if (hay.isEmpty()) return false
        for (p in pats) {
            try { if (compile(p)?.containsMatchIn(hay) == true) return true }
            catch (e: Exception) { Log.w(TAG, "match '$p' failed: ${e.message}") }
        }
        return false
    }

    // ---- BODY (message text) patterns ----
    fun patterns(c: Context): List<String> = list(c, KEY)
    fun addPattern(c: Context, pattern: String): Boolean = addTo(c, KEY, pattern)
    fun removePattern(c: Context, pattern: String) = removeFrom(c, KEY, pattern)
    /** All saved BODY patterns compiled once (invalid ones dropped) — for efficient per-row filtering. */
    fun compiledPatterns(c: Context): List<Regex> = patterns(c).mapNotNull { compile(it) }
    /** True if [body] matches any stored BODY pattern. */
    fun matches(c: Context, body: String): Boolean = anyMatch(patterns(c), body)

    // ---- SENDER (address / short-code) patterns ----
    fun senderPatterns(c: Context): List<String> = list(c, KEY_SENDER)
    fun addSenderPattern(c: Context, pattern: String): Boolean = addTo(c, KEY_SENDER, pattern)
    fun removeSenderPattern(c: Context, pattern: String) = removeFrom(c, KEY_SENDER, pattern)
    fun compiledSenderPatterns(c: Context): List<Regex> = senderPatterns(c).mapNotNull { compile(it) }
    /** True if [address] (the sender's number / alpha code) matches any stored SENDER pattern. */
    fun matchesSender(c: Context, address: String): Boolean = anyMatch(senderPatterns(c), address)

    /** Human-readable form of a stored pattern for DISPLAY only (never for matching/removal). A plain
     *  phrase blocked via the search-bar "Block" button is stored as `Regex.escape(phrase)` =
     *  `Pattern.quote(phrase)` = `\Q`…`\E`, which is confusing to show verbatim. Unwrap a pure
     *  `\Q`…`\E` literal back to its phrase (undoing the `\E\\E\Q` embedding Pattern.quote uses for a
     *  literal `\E` inside the phrase); leave a real regex untouched. */
    fun displayLabel(pattern: String): String {
        if (pattern.length >= 4 && pattern.startsWith("\\Q") && pattern.endsWith("\\E")) {
            return pattern.substring(2, pattern.length - 2).replace("\\E\\\\E\\Q", "\\E")
        }
        return pattern
    }

    /** Compile a pattern (case-insensitive), or null if it isn't a valid regex. */
    fun compile(pattern: String): Regex? = try {
        Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (e: Exception) { null }
}
