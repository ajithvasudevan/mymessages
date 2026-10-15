package dev.ajithv.mymessages

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-managed filters for the NOTIFICATION overlay — the analogue of [RegexRepo] (which filters
 * incoming SMS), but for notifications. A notification whose app / sender / body matches ANY stored
 * filter is hidden from the overlay pop AND from the overlay's prev/next history paging — the same
 * treatment as a [Prefs] filter rule, except matched by SUBSTRING (case-insensitive) or REGEX
 * instead of an exact app+category / sender / text key. (Rows are still recorded into history, exactly
 * like filtered rows, so a filter can be removed without losing the notifications it hid.)
 *
 * Stored as a JSON array of {"v":<pattern>,"r":<isRegex bool>}. Matching runs against the combined
 * "app\ntitle\ntext" so a single rule can hide by app name, by sender, or by body content.
 */
object NotifFilterRepo {
    const val TAG = "NotifFilterRepo"
    private const val FILE = "mymessages_notif_filters"
    private const val KEY = "filters"

    /** A single filter: [value] is either a literal substring or a regex source, per [regex]. */
    data class Filter(val value: String, val regex: Boolean)

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun arr(c: Context): JSONArray =
        try { JSONArray(sp(c).getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) = sp(c).edit().putString(KEY, a.toString()).apply()

    fun filters(c: Context): List<Filter> {
        val a = arr(c); val out = ArrayList<Filter>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val v = o.optString("v"); if (v.isEmpty()) continue
            out.add(Filter(v, o.optBoolean("r", false)))
        }
        return out
    }

    /** Add a filter. [regex]=true → compiled as a case-insensitive regex (rejected if invalid);
     *  [regex]=false → matched as a literal case-insensitive substring. Returns false if the value is
     *  blank, an invalid regex, or a duplicate of an existing (value, regex) filter. */
    fun addFilter(c: Context, value: String, regex: Boolean): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (regex && compile(v) == null) return false
        if (filters(c).any { it.value == v && it.regex == regex }) return false
        save(c, arr(c).put(JSONObject().put("v", v).put("r", regex)))
        return true
    }

    fun removeFilter(c: Context, value: String, regex: Boolean) {
        val a = arr(c); val n = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("v") == value && o.optBoolean("r", false) == regex) continue
            n.put(o)
        }
        save(c, n)
    }

    fun clear(c: Context) = sp(c).edit().remove(KEY).apply()

    /** Compile a regex source (case-insensitive), or null if it isn't a valid regex. */
    fun compile(pattern: String): Regex? =
        try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { null }

    private fun matchesOne(f: Filter, hay: String): Boolean = try {
        if (f.regex) compile(f.value)?.containsMatchIn(hay) == true
        else hay.contains(f.value, ignoreCase = true)
    } catch (e: Exception) { Log.w(TAG, "match '${f.value}' failed: ${e.message}"); false }

    /** True if this notification (app + sender/title + body) matches any stored filter. Cheap no-op
     *  when the user has no filters, so the hot notification path pays nothing until one is added. */
    fun matches(c: Context, app: String, title: String, text: String): Boolean {
        val list = filters(c)
        if (list.isEmpty()) return false
        val hay = "$app\n$title\n$text"
        return list.any { matchesOne(it, hay) }
    }
}
