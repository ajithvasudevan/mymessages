package dev.ajithv.mymessages

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Quick-reply service (ACTION_RESPOND_VIA_MESSAGE) — the fourth component Android requires of a
 * default SMS app. The dialer starts it when the user picks "reply with a message" on an incoming
 * call; we send the chosen text as an SMS to the caller.
 */
class HeadlessSmsSendService : Service() {
    companion object { const val TAG = "HeadlessSmsSend" }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent != null && TelephonyManager.ACTION_RESPOND_VIA_MESSAGE == intent.action) {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra("android.intent.extra.TEXT") ?: ""
                val recipients = intent.data?.schemeSpecificPart
                    ?.split(";", ",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                if (text.isNotEmpty() && recipients.isNotEmpty()) {
                    for (r in recipients) SmsRepo.sendSms(applicationContext, r, text)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "respond-via-message failed: ${e.message}") }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
