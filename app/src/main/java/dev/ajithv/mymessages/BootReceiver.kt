package dev.ajithv.mymessages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Start the listener service after a reboot OR right after the app is updated
 *  (MY_PACKAGE_REPLACED), so the persistent notification comes back immediately. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs.fgEnabled(context)) return   // user turned the persistent background service off
        val i = Intent(context, DashService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
        else context.startService(i)
    }
}
