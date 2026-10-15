package dev.ajithv.mymessages

import android.app.PendingIntent

/**
 * In-process cache of each recorded notification's own tap intent (its `contentIntent` — the deep link
 * the source app fires when you tap the notification, e.g. a news headline that opens that exact
 * article). A [PendingIntent] is a live token that can't be serialised into the JSON history, so it's
 * held here in memory, keyed by the history row's `ts`, written by [NotifListener] and read by
 * [NotifActivity] (same app process). Bounded to the last [MAX] entries. Lost on process death — the
 * overlay then falls back to just launching the app.
 */
object NotifIntents {
    private const val MAX = 120
    private val map = LinkedHashMap<Long, PendingIntent>()

    @Synchronized fun put(ts: Long, pi: PendingIntent?) {
        if (pi == null) { map.remove(ts); return }
        map.remove(ts)          // re-insert so LinkedHashMap order tracks recency
        map[ts] = pi
        while (map.size > MAX) {
            val it = map.keys.iterator()
            if (it.hasNext()) { it.next(); it.remove() } else break
        }
    }

    @Synchronized fun get(ts: Long): PendingIntent? = map[ts]
}
