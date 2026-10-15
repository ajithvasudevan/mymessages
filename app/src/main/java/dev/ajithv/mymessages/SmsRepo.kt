package dev.ajithv.mymessages

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import android.telephony.SmsManager
import android.text.format.DateUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * All SMS/MMS access goes through Android's own Telephony content provider + [SmsManager] — MyMessages
 * keeps NO private message store. Reads work for any app with READ_SMS; writes (inserting received /
 * sent rows, marking read) only succeed while MyMessages is the DEFAULT SMS app.
 */
object SmsRepo {
    const val TAG = "SmsRepo"
    /** Local broadcast fired when the provider changed (new SMS/MMS delivered) so open lists refresh. */
    const val ACTION_SMS_CHANGED = "dev.ajithv.mymessages.SMS_CHANGED"
    /** Fired by the platform (via our sentIntent) with the send result — see [SmsSentReceiver]. */
    const val ACTION_SMS_SENT = "dev.ajithv.mymessages.SMS_SENT"

    data class Conversation(
        val threadId: Long, val address: String, val name: String,
        val snippet: String, val date: Long, val unread: Boolean)

    data class Message(
        val id: Long, val address: String, val body: String, val date: Long,
        val incoming: Boolean, val isMms: Boolean, val imagePartIds: List<Long> = emptyList(),
        val threadId: Long = 0L, val read: Boolean = true) {
        val unread: Boolean get() = incoming && !read
    }

    /** One selectable addressee = a specific phone NUMBER of a contact (so Home and Work are two
     *  distinct entries). [label] is the number type ("Home"/"Work"/"Mobile"/…). */
    data class Contact(val name: String, val number: String, val label: String) {
        /** Pill / list text: "Ajith (Home)" when named, else the bare number. */
        fun display(): String = when {
            name.isBlank() -> number
            label.isBlank() -> name
            else -> "$name ($label)"
        }
    }

    /** Whether MyMessages is the default SMS app. On Q+ ask RoleManager (the source of truth) — on
     *  some OEM builds (e.g. OxygenOS) the legacy `Telephony.Sms.getDefaultSmsPackage` returns null
     *  even while we hold ROLE_SMS, which wrongly blocked sending. Falls back to the legacy API pre-Q. */
    fun isDefault(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = c.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null) return rm.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
        }
        return Telephony.Sms.getDefaultSmsPackage(c) == c.packageName
    }

    @Suppress("DEPRECATION")
    fun smsManager(c: Context): SmsManager? =
        (if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else null)
            ?: SmsManager.getDefault()

    // ---------------------------------------------------------------- sending

    /**
     * Dispatch an SMS (multipart-safe). Returns null if it was handed to the radio OK — the FINAL
     * result (and the write to the Sent box) then arrives at [SmsSentReceiver] via [ACTION_SMS_SENT].
     * Returns a non-null error string if it couldn't even be dispatched (e.g. SecurityException,
     * no SmsManager). This is deliberately NOT "fire and assume success": the real reason a send
     * fails (no service, radio off, generic failure, …) surfaces from the platform's sent-intent.
     */
    fun sendSms(c: Context, address: String, body: String): String? {
        val sm = smsManager(c) ?: return "No SmsManager on this device"
        return try {
            val parts = sm.divideMessage(body)
            val code = (System.currentTimeMillis() and 0xffffff).toInt()
            val sent = PendingIntent.getBroadcast(
                c, code,
                Intent(ACTION_SMS_SENT).setPackage(c.packageName)
                    .putExtra("address", address).putExtra("body", body),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (parts.size > 1) {
                val sents = ArrayList<PendingIntent?>(parts.size)
                for (i in parts.indices) sents.add(if (i == parts.size - 1) sent else null)
                sm.sendMultipartTextMessage(address, null, parts, sents, null)
            } else {
                sm.sendTextMessage(address, null, body, sent, null)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "sendSms dispatch failed: ${e.message}")
            e.message ?: e.toString()
        }
    }

    /** Human-readable reason for an SmsManager sentIntent result code. */
    fun sentResultReason(code: Int): String = when (code) {
        android.app.Activity.RESULT_OK -> "OK"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit exceeded"
        else -> "error code $code"
    }

    // ---------------------------------------------------------------- provider writes

    fun threadId(c: Context, address: String): Long =
        try { Telephony.Threads.getOrCreateThreadId(c, address) } catch (_: Exception) { 0L }

    fun writeInbox(c: Context, address: String, body: String, date: Long): Long {
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            val t = threadId(c, address); if (t > 0) put(Telephony.Sms.THREAD_ID, t)
        }
        return try {
            c.contentResolver.insert(Telephony.Sms.CONTENT_URI, v)
            threadId(c, address)
        } catch (e: Exception) { Log.w(TAG, "writeInbox failed: ${e.message}"); 0L }
    }

    fun writeSent(c: Context, address: String, body: String) {
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            val t = threadId(c, address); if (t > 0) put(Telephony.Sms.THREAD_ID, t)
        }
        try { c.contentResolver.insert(Telephony.Sms.CONTENT_URI, v) }
        catch (e: Exception) { Log.w(TAG, "writeSent failed: ${e.message}") }
    }

    /** Delete an ENTIRE conversation (all its SMS + MMS) from the provider. Default SMS app only. */
    fun deleteThread(c: Context, threadId: Long): Boolean {
        if (threadId <= 0) return false
        return try {
            var n = c.contentResolver.delete(Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()))
            try {
                n += c.contentResolver.delete(Telephony.Mms.CONTENT_URI,
                    "${Telephony.Mms.THREAD_ID}=?", arrayOf(threadId.toString()))
            } catch (e: Exception) { Log.w(TAG, "delete mms thread: ${e.message}") }
            n > 0
        } catch (e: Exception) { Log.w(TAG, "deleteThread failed: ${e.message}"); false }
    }

    /** Delete a SINGLE message (SMS or MMS) from the provider by its row id. */
    fun deleteMessage(c: Context, id: Long, isMms: Boolean): Boolean {
        if (id <= 0) return false
        return try {
            val base = if (isMms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
            c.contentResolver.delete(ContentUris.withAppendedId(base, id), null, null) > 0
        } catch (e: Exception) { Log.w(TAG, "deleteMessage failed: ${e.message}"); false }
    }

    /** Mark a single SMS read+seen (used when tapping a specific message in the Received list). */
    fun markMessageRead(c: Context, id: Long): Boolean {
        if (id <= 0) return false
        val v = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
        return try {
            c.contentResolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), v, null, null) > 0
        } catch (e: Exception) { Log.w(TAG, "markMessageRead failed: ${e.message}"); false }
    }

    fun markThreadRead(c: Context, threadId: Long) {
        val v = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
        try {
            c.contentResolver.update(Telephony.Sms.CONTENT_URI, v,
                "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.READ}=0", arrayOf(threadId.toString()))
        } catch (e: Exception) { Log.w(TAG, "markRead failed: ${e.message}") }
    }

    // ---------------------------------------------------------------- contacts

    /** Search-as-you-type contacts by **substring** of the name OR the number. Number matching is
     *  separator-insensitive (strips spaces/dashes/parens on the stored number), so "9900" matches
     *  "+91 99009-90099". Returns ONE entry per phone number (Home + Work yield two rows, each
     *  separately selectable). Needs READ_CONTACTS. */
    fun searchContacts(c: Context, query: String, limit: Int = 30): List<Contact> {
        val out = ArrayList<Contact>()
        val q = query.trim()
        if (q.isEmpty()) return out
        val digits = q.filter { it.isDigit() }
        // name substring, OR number substring after stripping ' ', '-', '(', ')'
        val sel = StringBuilder("${Phone.DISPLAY_NAME} LIKE ?")
        val args = ArrayList<String>()
        args.add("%$q%")
        if (digits.isNotEmpty()) {
            sel.append(" OR REPLACE(REPLACE(REPLACE(REPLACE(${Phone.NUMBER},' ',''),'-',''),'(',''),')','') LIKE ?")
            args.add("%$digits%")
            sel.append(" OR ${Phone.NORMALIZED_NUMBER} LIKE ?")
            args.add("%$digits%")
        }
        try {
            c.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
                sel.toString(), args.toTypedArray(),
                "${Phone.DISPLAY_NAME} COLLATE NOCASE ASC LIMIT 60"
            )?.use { cur ->
                val seen = HashSet<String>()
                while (cur.moveToNext()) {
                    val name = cur.getString(0).orEmpty()
                    val number = cur.getString(1) ?: continue
                    val type = cur.getInt(2)
                    val labelCol = cur.getString(3)
                    val label = Phone.getTypeLabel(c.resources, type, labelCol).toString()
                    // Keep Home vs Work (different numbers) separate; drop true duplicate rows.
                    if (!seen.add(name + "|" + number.filter { !it.isWhitespace() })) continue
                    out.add(Contact(name, number, label))
                    if (out.size >= limit) break
                }
            }
        } catch (e: Exception) { Log.w(TAG, "searchContacts failed: ${e.message}") }
        return out
    }

    // Process-wide contact-name cache. A PhoneLookup content query per lookup is expensive, and the
    // list/search render paths call contactName once PER ROW — up to 200 blocking queries on the UI
    // thread, the main cause of the multi-second search stall. Memoise by address (bounded by the
    // number of distinct senders); misses ("") are cached too so we don't re-query them. Lets a worker
    // thread pre-warm names before the UI thread renders (see searchMessagesStreaming). Display cache
    // only — stale until app restart if a contact is renamed mid-session, which is fine here.
    private val nameMemo = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun contactName(c: Context, address: String): String {
        if (address.isBlank()) return ""
        nameMemo[address]?.let { return it }
        val name = try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" } ?: ""
        } catch (_: Exception) { "" }
        nameMemo[address] = name
        return name
    }

    // ---------------------------------------------------------------- conversation list

    /** One row per thread (latest snippet + unread flag), newest first. Read straight from the
     *  mms-sms conversations view; recipient ids resolved via the canonical-addresses table.
     *  Capped at [limit] so the per-row contact-name lookups don't stall a huge history. */
    fun conversations(c: Context, limit: Int = 200): List<Conversation> {
        val out = ArrayList<Conversation>()
        val addrMap = canonicalAddresses(c)
        try {
            c.contentResolver.query(
                Uri.parse("content://mms-sms/conversations?simple=true"),
                null, null, null, "date DESC"
            )?.use { cur ->
                val iId = cur.getColumnIndex("_id")
                val iDate = cur.getColumnIndex("date")
                val iSnip = cur.getColumnIndex("snippet")
                val iRead = cur.getColumnIndex("read")
                val iRec = cur.getColumnIndex("recipient_ids")
                while (cur.moveToNext()) {
                    val threadId = if (iId >= 0) cur.getLong(iId) else continue
                    val recIds = if (iRec >= 0) cur.getString(iRec).orEmpty() else ""
                    val address = recIds.trim().split(Regex("\\s+"))
                        .mapNotNull { addrMap[it.toLongOrNull() ?: -1L] }
                        .joinToString(", ").ifEmpty { "(unknown)" }
                    val name = address.split(",").firstOrNull()?.trim()?.let { contactName(c, it) }.orEmpty()
                    val snippet = if (iSnip >= 0) cur.getString(iSnip).orEmpty() else ""
                    val date = if (iDate >= 0) cur.getLong(iDate) else 0L
                    val unread = iRead >= 0 && cur.getInt(iRead) == 0
                    // Hide threads matching ANY of the 3 block types: blocked number/contact,
                    // blocked alpha sender-code (both BlockRepo), or a regex text filter (on the
                    // latest snippet). Blocked senders live in the Blocked screen; regex-matched
                    // messages are reviewable per-pattern.
                    if (BlockRepo.isBlocked(c, address) || RegexRepo.matches(c, snippet) || RegexRepo.matchesSender(c, address)) continue
                    out.add(Conversation(threadId, address, name, snippet, date, unread))
                    if (out.size >= limit) break
                }
            }
        } catch (e: Exception) { Log.w(TAG, "conversations failed: ${e.message}") }
        return out
    }

    // ---------------------------------------------------------------- conversation-list cache
    // A copy of the last-rendered conversation list, so the screen paints INSTANTLY on open and
    // then refreshes from the provider in the background (the live query + N contact lookups take a
    // few seconds on a large history). This is a DISPLAY cache only — the messages themselves always
    // live in Android's Telephony provider, never here.
    private const val CACHE_FILE = "mymessages_cache"
    private const val KEY_CONVS = "conversations"
    private const val KEY_INBOX = "inbox"

    fun cachedConversations(c: Context): List<Conversation> {
        val s = c.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE).getString(KEY_CONVS, null)
            ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Conversation(o.optLong("t"), o.optString("a"), o.optString("n"),
                    o.optString("s"), o.optLong("d"), o.optBoolean("u"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun cacheConversations(c: Context, list: List<Conversation>) {
        try {
            val a = JSONArray()
            for (cv in list) a.put(JSONObject()
                .put("t", cv.threadId).put("a", cv.address).put("n", cv.name)
                .put("s", cv.snippet).put("d", cv.date).put("u", cv.unread))
            c.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY_CONVS, a.toString()).apply()
        } catch (e: Exception) { Log.w(TAG, "cacheConversations failed: ${e.message}") }
    }

    /** Same instant-paint cache for the Received-SMS (inbox) screen. */
    fun cachedInbox(c: Context): List<Message> {
        val s = c.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE).getString(KEY_INBOX, null)
            ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Message(o.optLong("i"), o.optString("a"), o.optString("b"), o.optLong("d"), true, false,
                    read = o.optBoolean("r", true))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun cacheInbox(c: Context, list: List<Message>) {
        try {
            val a = JSONArray()
            for (m in list) a.put(JSONObject()
                .put("i", m.id).put("a", m.address).put("b", m.body).put("d", m.date).put("r", m.read))
            c.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY_INBOX, a.toString()).apply()
        } catch (e: Exception) { Log.w(TAG, "cacheInbox failed: ${e.message}") }
    }

    private fun canonicalAddresses(c: Context): Map<Long, String> {
        val m = HashMap<Long, String>()
        try {
            c.contentResolver.query(
                Uri.parse("content://mms-sms/canonical-addresses"),
                arrayOf("_id", "address"), null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) m[cur.getLong(0)] = cur.getString(1).orEmpty()
            }
        } catch (e: Exception) { Log.w(TAG, "canonical-addresses failed: ${e.message}") }
        return m
    }

    // ---------------------------------------------------------------- flat inbox ("received SMS")

    fun inbox(c: Context, limit: Int = 300): List<Message> {
        val out = ArrayList<Message>()
        val blocked = BlockRepo.blocked(c)
        val rxList = RegexRepo.compiledPatterns(c)
        val rxSender = RegexRepo.compiledSenderPatterns(c)
        try {
            c.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val address = cur.getString(1).orEmpty()
                    val body = cur.getString(2).orEmpty()
                    // Hide blocked-sender / regex-filtered messages from the Received feed too.
                    if (BlockRepo.matchesLocal(blocked, address) || rxList.any { it.containsMatchIn(body) } || rxSender.any { it.containsMatchIn(address) }) continue
                    out.add(Message(cur.getLong(0), address, body, cur.getLong(3), true, false,
                        read = cur.getInt(4) == 1))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "inbox failed: ${e.message}") }
        return out
    }

    // ---------------------------------------------------------------- blocked messages

    /** Every received SMS that is CURRENTLY blocked (newest first): the sender is in the app-side block
     *  list OR Android's system blocked-number list, OR the body matches a regex text filter — i.e. the
     *  exact set [SmsReceiver] stores silently (no notification/overlay) and [conversations]/[inbox] hide.
     *
     *  Dynamic by construction: it re-derives from the live block rules on every call, so a message drops
     *  out the moment the rule blocking it is removed (and reappears in the normal conversation/inbox
     *  lists). Scans the provider + pre-warms contact names, so call it OFF the main thread. The
     *  system-list match is digit-normalised (same as [BlockRepo.matchesLocal]) rather than a per-row
     *  [android.provider.BlockedNumberContract.isBlocked] query (which would be one content query per
     *  row). */
    fun blockedMessages(c: Context, limit: Int = 500): List<Message> {
        val out = ArrayList<Message>()
        val appBlocked = BlockRepo.blocked(c)
        val sysBlocked = BlockRepo.systemBlockedNumbers(c)
        val rxList = RegexRepo.compiledPatterns(c)
        val rxSender = RegexRepo.compiledSenderPatterns(c)
        if (appBlocked.isEmpty() && sysBlocked.isEmpty() && rxList.isEmpty() && rxSender.isEmpty()) return out
        try {
            c.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.THREAD_ID),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val addr = cur.getString(1).orEmpty()
                    val body = cur.getString(2).orEmpty()
                    val blocked = BlockRepo.matchesLocal(appBlocked, addr) ||
                        BlockRepo.matchesLocal(sysBlocked, addr) ||
                        rxList.any { it.containsMatchIn(body) } ||
                        rxSender.any { it.containsMatchIn(addr) }
                    if (!blocked) continue
                    contactName(c, addr)   // pre-warm the name cache on this worker thread
                    out.add(Message(cur.getLong(0), addr, body, cur.getLong(3), true, false,
                        emptyList(), cur.getLong(5), cur.getInt(4) == 1))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "blockedMessages failed: ${e.message}") }
        return out
    }

    /** All SMS whose body matches [pattern] (case-insensitive substring), newest first — for the
     *  "view messages matching this filter" screen. Uses [RegexRepo.compile] so it matches exactly
     *  what the incoming-SMS filter blocks. */
    fun messagesMatching(c: Context, pattern: String, limit: Int = 500): List<Message> {
        val rx = RegexRepo.compile(pattern) ?: return emptyList()
        val out = ArrayList<Message>()
        try {
            c.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val body = cur.getString(2).orEmpty()
                    if (body.isEmpty() || !rx.containsMatchIn(body)) continue
                    out.add(Message(cur.getLong(0), cur.getString(1).orEmpty(), body,
                        cur.getLong(3), cur.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX, false))
                    if (out.size >= limit) break
                }
            }
        } catch (e: Exception) { Log.w(TAG, "messagesMatching failed: ${e.message}") }
        return out
    }

    /** All SMS whose SENDER (address) matches [pattern] (case-insensitive substring), newest first —
     *  the sender-filter counterpart of [messagesMatching], for the "view matches" screen. */
    fun messagesFromSendersMatching(c: Context, pattern: String, limit: Int = 500): List<Message> {
        val rx = RegexRepo.compile(pattern) ?: return emptyList()
        val out = ArrayList<Message>()
        try {
            c.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val addr = cur.getString(1).orEmpty()
                    if (addr.isEmpty() || !rx.containsMatchIn(addr)) continue
                    out.add(Message(cur.getLong(0), addr, cur.getString(2).orEmpty(),
                        cur.getLong(3), cur.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX, false))
                    if (out.size >= limit) break
                }
            }
        } catch (e: Exception) { Log.w(TAG, "messagesFromSendersMatching failed: ${e.message}") }
        return out
    }

    /** Search across ALL SMS (newest first). Scope is the message BODY unless [senderScope] is set, in
     *  which case it's the SENDER — the raw address AND the resolved contact name — with the body
     *  ignored (so "search by sender" finds a contact typed by name, not just by number). [regex]=false
     *  → plain case-insensitive substring; [regex]=true → the query is a regex. Results already-blocked
     *  by a saved sender/regex filter are omitted (so a just-added Block hides them everywhere). Each
     *  result carries thread_id + row id + read so the caller can navigate, highlight and style it.
     *  Blocking convenience wrapper around [searchMessagesStreaming] — collects every match into a list. */
    fun searchMessages(c: Context, query: String, regex: Boolean = false,
                       senderScope: Boolean = false, limit: Int = 200): List<Message> {
        val out = ArrayList<Message>()
        searchMessagesStreaming(c, query, regex, senderScope, limit, batchSize = limit,
            onBatch = { out.addAll(it) })
        return out
    }

    /** Streaming variant of [searchMessages]: instead of returning the full list at the end, it invokes
     *  [onBatch] (on the CALLER's thread) with each chunk of matches AS THEY'RE FOUND (newest first),
     *  then [onDone] once the scan finishes or hits [limit]. [cancelled] is polled every row so a
     *  superseded search (the user kept typing) stops immediately. It also PRE-WARMS the contact-name
     *  cache for each match on this (worker) thread, so the UI thread's per-row contactName() call is a
     *  cache hit rather than a blocking PhoneLookup. Run it from a background thread. Same scope/blocking
     *  semantics as [searchMessages]. */
    fun searchMessagesStreaming(
        c: Context, query: String, regex: Boolean = false, senderScope: Boolean = false,
        limit: Int = 200, batchSize: Int = 15,
        cancelled: () -> Boolean = { false },
        onBatch: (List<Message>) -> Unit,
        onDone: () -> Unit = {}
    ) {
        val q = query.trim()
        if (q.isEmpty()) { onDone(); return }
        val blocked = BlockRepo.blocked(c)
        val rxList = RegexRepo.compiledPatterns(c)
        val rxSender = RegexRepo.compiledSenderPatterns(c)
        val proj = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
        fun skip(addr: String, body: String) =
            BlockRepo.matchesLocal(blocked, addr) || rxList.any { it.containsMatchIn(body) } || rxSender.any { it.containsMatchIn(addr) }
        val buf = ArrayList<Message>(batchSize)
        var count = 0
        fun flush() { if (buf.isNotEmpty()) { onBatch(ArrayList(buf)); buf.clear() } }
        /** Buffer a matched row (pre-warming its contact name off the UI thread); return true while
         *  more results are still wanted. */
        fun emit(cur: android.database.Cursor): Boolean {
            val addr = cur.getString(2).orEmpty()
            contactName(c, addr)   // pre-warm the name cache on this worker thread
            buf.add(Message(cur.getLong(0), addr, cur.getString(3).orEmpty(),
                cur.getLong(4), cur.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX, false,
                emptyList(), cur.getLong(1), cur.getInt(6) == 1))
            count++
            if (buf.size >= batchSize) flush()
            return count < limit
        }
        try {
            // ---- Sender-scoped search: contact-name isn't in the provider, so scan (newest first) and
            //      test each row's address + its resolved contact name (memoised in contactName). ----
            if (senderScope) {
                val rx = if (regex) (RegexRepo.compile(q) ?: run { onDone(); return }) else null
                c.contentResolver.query(Telephony.Sms.CONTENT_URI, proj, null, null,
                    "${Telephony.Sms.DATE} DESC")?.use { cur ->
                    while (cur.moveToNext()) {
                        if (cancelled()) return
                        val addr = cur.getString(2).orEmpty()
                        val nm = contactName(c, addr)
                        val hay = if (nm.isEmpty()) addr else "$addr $nm"
                        val match = if (rx != null) rx.containsMatchIn(hay) else hay.contains(q, ignoreCase = true)
                        if (!match) continue
                        if (skip(addr, cur.getString(3).orEmpty())) continue
                        if (!emit(cur)) break
                    }
                }
            } else if (regex) {
                val rx = RegexRepo.compile(q) ?: run { onDone(); return }
                c.contentResolver.query(Telephony.Sms.CONTENT_URI, proj, null, null,
                    "${Telephony.Sms.DATE} DESC")?.use { cur ->
                    while (cur.moveToNext()) {
                        if (cancelled()) return
                        val body = cur.getString(3).orEmpty()
                        if (!rx.containsMatchIn(body)) continue
                        if (skip(cur.getString(2).orEmpty(), body)) continue
                        if (!emit(cur)) break
                    }
                }
            } else {
                val like = "%${q.replace("%", "\\%").replace("_", "\\_")}%"
                c.contentResolver.query(Telephony.Sms.CONTENT_URI, proj,
                    "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\' OR ${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\'",
                    arrayOf(like, like), "${Telephony.Sms.DATE} DESC")?.use { cur ->
                    while (cur.moveToNext()) {
                        if (cancelled()) return
                        if (skip(cur.getString(2).orEmpty(), cur.getString(3).orEmpty())) continue
                        if (!emit(cur)) break
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "searchMessagesStreaming failed: ${e.message}") }
        flush()
        onDone()
    }

    // ---------------------------------------------------------------- a single thread (SMS + MMS)

    fun messagesForThread(c: Context, threadId: Long): List<Message> {
        val out = ArrayList<Message>()
        // SMS parts
        try {
            c.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()), null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val type = cur.getInt(4)
                    out.add(Message(cur.getLong(0), cur.getString(1).orEmpty(), cur.getString(2).orEmpty(),
                        cur.getLong(3), type == Telephony.Sms.MESSAGE_TYPE_INBOX, false))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "thread sms failed: ${e.message}") }
        // MMS parts (dates are in SECONDS)
        try {
            c.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                "${Telephony.Mms.THREAD_ID}=?", arrayOf(threadId.toString()), null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val date = cur.getLong(1) * 1000L
                    val incoming = cur.getInt(2) == Telephony.Mms.MESSAGE_BOX_INBOX
                    out.add(Message(id, mmsAddress(c, id), mmsText(c, id), date, incoming, true, mmsImageParts(c, id)))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "thread mms failed: ${e.message}") }
        out.sortBy { it.date }
        return out
    }

    private fun mmsText(c: Context, mmsId: Long): String {
        val sb = StringBuilder()
        try {
            c.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct", "text"), "mid=?", arrayOf(mmsId.toString()), null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(1) == "text/plain") {
                        val t = cur.getString(2)
                        if (!t.isNullOrEmpty()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(t) }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "mmsText failed: ${e.message}") }
        return sb.toString()
    }

    private fun mmsImageParts(c: Context, mmsId: Long): List<Long> {
        val out = ArrayList<Long>()
        try {
            c.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct"), "mid=?", arrayOf(mmsId.toString()), null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(1)?.startsWith("image/") == true) out.add(cur.getLong(0))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "mmsImageParts failed: ${e.message}") }
        return out
    }

    /** Sender address of an MMS (PduHeaders.FROM == 137). */
    private fun mmsAddress(c: Context, mmsId: Long): String {
        try {
            val uri = Uri.parse("content://mms/$mmsId/addr")
            c.contentResolver.query(uri, arrayOf("address", "type"), "type=137", null, null)?.use { cur ->
                if (cur.moveToFirst()) return cur.getString(0).orEmpty()
            }
        } catch (e: Exception) { Log.w(TAG, "mmsAddress failed: ${e.message}") }
        return ""
    }

    /** Decode an MMS image part bitmap (downsampled to [maxPx]) for display in a bubble. */
    fun mmsPartBitmap(c: Context, partId: Long, maxPx: Int = 1024): Bitmap? {
        val uri = ContentUris.withAppendedId(Uri.parse("content://mms/part"), partId)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) { Log.w(TAG, "mmsPartBitmap failed: ${e.message}"); null }
    }

    fun fmtTime(ts: Long): CharSequence = try {
        DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    } catch (_: Exception) { "" }

    // ---------------------------------------------------------------- blocked-SMS stats (main screen)

    /** Counts for the main-screen "blocked" summary. [hiddenMessages] is how many SMS currently in the
     *  provider are hidden by a block rule (a blocked app-side sender OR a regex text filter) — the
     *  count of messages the block rules are actively suppressing from the lists. */
    data class BlockStats(
        val appSenders: Int, val systemNumbers: Int, val textFilters: Int, val hiddenMessages: Int)

    /** Compute the blocked-SMS stats. Scans the SMS table for [hiddenMessages], so call OFF the main
     *  thread. */
    fun blockedStats(c: Context): BlockStats {
        val blocked = BlockRepo.blocked(c)
        val rxList = RegexRepo.compiledPatterns(c)
        val rxSender = RegexRepo.compiledSenderPatterns(c)
        val systemNumbers = BlockRepo.systemBlockedNumbers(c).size
        var hidden = 0
        if (blocked.isNotEmpty() || rxList.isNotEmpty() || rxSender.isNotEmpty()) {
            try {
                c.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                    null, null, null
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val addr = cur.getString(0).orEmpty()
                        val body = cur.getString(1).orEmpty()
                        if (BlockRepo.matchesLocal(blocked, addr) || rxList.any { it.containsMatchIn(body) } || rxSender.any { it.containsMatchIn(addr) }) hidden++
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "blockedStats failed: ${e.message}") }
        }
        return BlockStats(blocked.size, systemNumbers, rxList.size + rxSender.size, hidden)
    }
}
