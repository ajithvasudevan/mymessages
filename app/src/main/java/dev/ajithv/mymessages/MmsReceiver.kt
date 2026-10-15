package dev.ajithv.mymessages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives WAP_PUSH_DELIVER (the MMS notification) — required so Android accepts MyMessages as a
 * default SMS/MMS app. A full inbound-MMS download is a carrier-specific transaction (parse the WAP
 * push PDU → fetch the content from the MMSC over the mobile data APN, e.g. via
 * SmsManager.downloadMultimediaMessage): that needs a real SIM to build/verify and is not implemented
 * here. MMS already stored in Android's provider is still shown in the thread view (SmsRepo reads the
 * mms/part tables); this receiver just logs the push and refreshes open lists.
 */
class MmsReceiver : BroadcastReceiver() {
    companion object { const val TAG = "MmsReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "WAP push (MMS) received: ${intent.action} type=${intent.type}")
        // TODO(mms-download): parse the PDU and pull the message from the carrier MMSC.
        context.applicationContext.sendBroadcast(
            Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(context.packageName))
    }
}
