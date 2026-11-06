package dev.ajithv.mymessages

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Phone-side Data Layer listener. The watch asks for fresh doorbell frames via /bell/request
 * (e.g. when the user opens the doorbell screen on the watch manually). We answer by streaming
 * frames for a window WITHOUT ringing (no vibrate — the user opened it deliberately).
 */
class WatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/bell/request") {
            Log.i("WatchListener", "watch requested frames")
            WatchRelay.relayBell(applicationContext, ring = false)
        }
    }
}
