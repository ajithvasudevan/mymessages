package dev.ajithv.mymessages

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.util.Log
import org.json.JSONArray

/**
 * Blocked-sender store. Two layers:
 *  1. Android's system **BlockedNumberContract** (the OS-wide blocked-number DB) — MyMessages, as the
 *     default SMS app, may read/write it, so blocking a real NUMBER here also blocks its calls, and
 *     numbers the user already blocked system-wide are honoured.
 *  2. An app-side list (SharedPreferences) — needed because BlockedNumberContract only handles phone
 *     numbers, whereas SMS "senders" are often **alphanumeric short codes** (e.g. "AD-ICICIT-S") that
 *     aren't valid numbers. The app-side list blocks those (exact, case-insensitive) and mirrors
 *     numbers too, so it's the single source the Blocked-senders screen lists.
 */
object BlockRepo {
    const val TAG = "BlockRepo"
    private const val FILE = "mymessages_blocks"
    private const val KEY = "blocked"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun arr(c: Context): JSONArray =
        try { JSONArray(sp(c).getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) = sp(c).edit().putString(KEY, a.toString()).apply()

    /** The app-side blocked senders (raw strings — numbers and/or alpha codes). */
    fun blocked(c: Context): List<String> {
        val a = arr(c)
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }

    /** App-side match against a PREFETCHED list (cheap for per-row list filtering): exact
     *  (case-insensitive), or digit-normalised for numbers (tolerates a country-code prefix difference
     *  by comparing the trailing digits). */
    fun matchesLocal(list: List<String>, sender: String): Boolean {
        val s = sender.trim()
        if (s.isEmpty()) return false
        if (list.any { it.equals(s, ignoreCase = true) }) return true
        val d = s.filter { it.isDigit() }
        if (d.length >= 7) {
            for (b in list) {
                val bd = b.filter { it.isDigit() }
                if (bd.length >= 7 && (bd == d || bd.endsWith(d) || d.endsWith(bd))) return true
            }
        }
        return false
    }

    private fun isBlockedLocal(c: Context, sender: String): Boolean = matchesLocal(blocked(c), sender)

    /** True if this sender is blocked — app-side OR in the system blocked-number DB. */
    fun isBlocked(c: Context, sender: String): Boolean {
        if (sender.isBlank()) return false
        if (isBlockedLocal(c, sender)) return true
        return try {
            BlockedNumberContract.canCurrentUserBlockNumbers(c) &&
                BlockedNumberContract.isBlocked(c, sender)
        } catch (e: Exception) { Log.w(TAG, "system isBlocked failed: ${e.message}"); false }
    }

    fun isNumber(sender: String): Boolean =
        sender.any { it.isDigit() } && sender.none { it.isLetter() }

    fun block(c: Context, sender: String) {
        val s = sender.trim()
        if (s.isEmpty()) return
        if (!isBlockedLocal(c, s)) save(c, arr(c).put(s))
        if (isNumber(s)) {
            try {
                if (BlockedNumberContract.canCurrentUserBlockNumbers(c)) {
                    c.contentResolver.insert(
                        BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        ContentValues().apply {
                            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, s)
                        })
                }
            } catch (e: Exception) { Log.w(TAG, "system block failed: ${e.message}") }
        }
    }

    fun unblock(c: Context, sender: String) {
        val s = sender.trim()
        val a = arr(c); val n = JSONArray()
        for (i in 0 until a.length()) { val v = a.optString(i); if (!v.equals(s, ignoreCase = true) && v.isNotEmpty()) n.put(v) }
        save(c, n)
        try {
            if (BlockedNumberContract.canCurrentUserBlockNumbers(c)) {
                c.contentResolver.delete(
                    BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER}=?", arrayOf(s))
            }
        } catch (e: Exception) { Log.w(TAG, "system unblock failed: ${e.message}") }
    }

    // ---------------------------------------------------------------- Android system block list
    // Direct view/edit of Android's OS-wide BlockedNumberContract DB — the SAME list the stock Phone /
    // Messages apps use. Readable/writable only by the default SMS or dialer app (which MyMessages is).
    // Distinct from the app-side list above: this shows numbers blocked ANYWHERE (e.g. from the dialer),
    // which the app-side list wouldn't otherwise contain.

    /** True if this user/app may read & write the system blocked-number DB (default SMS/dialer app). */
    fun canUseSystemBlocks(c: Context): Boolean =
        try { BlockedNumberContract.canCurrentUserBlockNumbers(c) } catch (_: Exception) { false }

    /** Numbers in Android's system block list, in their original (as-entered) form. Empty if this
     *  app can't read it. */
    fun systemBlockedNumbers(c: Context): List<String> {
        val out = ArrayList<String>()
        try {
            if (!canUseSystemBlocks(c)) return out
            c.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val nnum = cur.getString(0)?.trim().orEmpty()
                    if (nnum.isNotEmpty()) out.add(nnum)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "systemBlockedNumbers failed: ${e.message}") }
        return out
    }

    /** Add a number to Android's system block list. Returns true on success. */
    fun systemBlock(c: Context, number: String): Boolean {
        val s = number.trim(); if (s.isEmpty()) return false
        return try {
            if (!canUseSystemBlocks(c)) return false
            c.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                ContentValues().apply {
                    put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, s)
                }) != null
        } catch (e: Exception) { Log.w(TAG, "systemBlock failed: ${e.message}"); false }
    }

    /** Remove a number from Android's system block list (matched on its original form). */
    fun systemUnblock(c: Context, number: String): Boolean {
        val s = number.trim(); if (s.isEmpty()) return false
        return try {
            if (!canUseSystemBlocks(c)) return false
            c.contentResolver.delete(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER}=?", arrayOf(s)) > 0
        } catch (e: Exception) { Log.w(TAG, "systemUnblock failed: ${e.message}"); false }
    }
}
