package dev.ajithv.mymessages

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen red "alarm" screen with a single SILENCE button, shown OVER the lock screen and any app
 * (showWhenLocked + turnScreenOn) when [DashService] starts ringing — e.g. the evening-commute
 * "almost home" alert. The ring sound/vibration is owned by DashService (alarm stream, max volume,
 * looping); this activity is just the wake-the-screen dismiss UI. Tapping SILENCE tells the service to
 * stop; if the ring ends any other way (auto-timeout / MQTT stop) DashService broadcasts
 * ACTION_RING_STOPPED and this closes.
 */
class RingActivity : Activity() {

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null) } catch (_: Exception) {}

        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#C62828"))
            setPadding(px(28), px(28), px(28), px(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(TextView(this).apply {
            text = intent.getStringExtra(DashService.EXTRA_MSG) ?: "Almost home"
            setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Tap to silence the alarm"
            setTextColor(Color.parseColor("#FFEBEE")); textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, px(10), 0, px(40))
        })
        root.addView(Button(this).apply {
            text = "🔕  SILENCE"
            textSize = 22f
            setTextColor(Color.parseColor("#C62828"))
            setBackgroundColor(Color.WHITE)
            setPadding(px(48), px(28), px(48), px(28))
            setOnClickListener { silence() }
        })
        setContentView(root)

        val f = IntentFilter(DashService.ACTION_RING_STOPPED)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(stopReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(stopReceiver, f)
    }

    private fun silence() {
        try {
            startService(Intent(this, DashService::class.java).setAction(DashService.ACTION_STOP_RING))
        } catch (_: Exception) {}
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { silence() }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
    }
}
