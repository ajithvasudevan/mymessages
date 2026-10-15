package dev.ajithv.mymessages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Receives the platform's send-result for an SMS we dispatched (via the sentIntent in
 * [SmsRepo.sendSms]). On success it writes the message to the provider's Sent box (so it now shows
 * only when the radio actually accepted it) and toasts; on failure it toasts the exact reason.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val app = c.applicationContext
        val address = intent.getStringExtra("address").orEmpty()
        val body = intent.getStringExtra("body").orEmpty()
        val ok = resultCode == Activity.RESULT_OK
        Log.i("SmsSentReceiver", "sent result=$resultCode ok=$ok to=$address")
        if (ok) {
            try { SmsRepo.writeSent(app, address, body) } catch (e: Exception) { Log.w("SmsSentReceiver", "writeSent: ${e.message}") }
            Toast.makeText(app, "Message sent", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(app, "SMS not sent: ${SmsRepo.sentResultReason(resultCode)}", Toast.LENGTH_LONG).show()
        }
        app.sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(app.packageName))
    }
}
