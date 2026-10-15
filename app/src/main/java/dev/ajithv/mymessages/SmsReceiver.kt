package dev.ajithv.mymessages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Receives SMS_DELIVER — delivered ONLY to the default SMS app. Because we're default, the system no
 * longer writes the message to the provider for us, so we do it here (into Android's own Telephony DB),
 * then: post the incoming-message notification, feed the lock-screen overlay, and refresh open lists.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SmsReceiver"
        const val SMS_CHANNEL = "mymsg_sms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        Thread {
            try { handle(context.applicationContext, intent) }
            catch (e: Exception) { Log.w(TAG, "handle failed: ${e.message}") }
            finally { pending.finish() }
        }.start()
    }

    private fun handle(c: Context, intent: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val address = msgs[0].originatingAddress ?: msgs[0].displayOriginatingAddress ?: ""
        // A long SMS arrives as several parts in one SMS_DELIVER — concatenate their bodies.
        val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        val ts = msgs[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (address.isEmpty() && body.isEmpty()) return

        // 1) Persist to Android's own SMS provider (only the default app can write).
        val threadId = SmsRepo.writeInbox(c, address, body, ts)

        // Blocked sender OR body matches a regex text filter? Keep the message in the provider
        // (viewable in the Blocked / filter screens) but do NOT notify or pop the overlay.
        if (BlockRepo.isBlocked(c, address) || RegexRepo.matches(c, body) || RegexRepo.matchesSender(c, address)) {
            Log.i(TAG, "blocked (sender or regex) from $address — stored silently, no notification")
            c.sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(c.packageName))
            return
        }

        val name = SmsRepo.contactName(c, address).ifEmpty { address }

        // 2) Standard shade notification (we're default → the system won't post one for us).
        postNotification(c, threadId, address, name, body)

        // 3) Feed the lock-screen overlay: record in its history and pop it (gated by the same
        //    master-switch / filter as every other notification the overlay shows).
        Prefs.addHistory(c, "Messages", c.packageName, "msg", name, body, ts)
        if (Prefs.notifEnabled(c) && !Prefs.globalDisabled(c) &&
            !Prefs.isItemFiltered(c, c.packageName, "msg", name, body)) {
            val payload = JSONObject()
                .put("screen", "notif").put("app", "Messages").put("pkg", c.packageName)
                .put("category", "msg").put("title", name).put("text", body).put("ts", ts).toString()
            try {
                val i = Intent(c, DashService::class.java)
                    .setAction(DashService.ACTION_OPEN_NOTIF)
                    .putExtra(DashService.EXTRA_PAYLOAD, payload)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) { Log.w(TAG, "overlay pop failed: ${e.message}") }
        }

        // 4) Refresh any open list/thread.
        c.sendBroadcast(Intent(SmsRepo.ACTION_SMS_CHANGED).setPackage(c.packageName))
    }

    private fun postNotification(c: Context, threadId: Long, address: String, name: String, body: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                SMS_CHANNEL, "Text messages", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            c, threadId.toInt().coerceAtLeast(1),
            Intent(c, ThreadActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(ThreadActivity.EXTRA_ADDRESS, address)
                .putExtra(ThreadActivity.EXTRA_NAME, name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, SMS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(threadId.toInt().coerceAtLeast(1), n) }
        catch (e: Exception) { Log.w(TAG, "notify failed: ${e.message}") }
    }
}
