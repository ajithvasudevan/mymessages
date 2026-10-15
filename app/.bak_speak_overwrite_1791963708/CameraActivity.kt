package dev.ajithv.mymessages

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Full-screen camera screen shown over the lock screen (the "camera" LockDash screen).
 * Plays an RTSP feed via libVLC. The stream URL + label come from the `lockdash/show`
 * payload (`{"screen":"camera","rtsp":"...","title":"..."}`) when present, else fall back
 * to the baked doorbell defaults. Auto-closes after DEF_DISMISS_MS, on the close button,
 * or when the service broadcasts ACTION_DISMISS (an MQTT `lockdash/dismiss`).
 */
class CameraActivity : Activity() {

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private lateinit var videoLayout: VLCVideoLayout
    private var muteBtn: ImageView? = null
    private var muted = true   // audio starts muted by default
    private var timerView: TextView? = null
    private var remainingSec = 0
    private val handler = Handler(Looper.getMainLooper())

    private var rtspUrl: String = ""
    private var ringtone: Ringtone? = null

    // Ticks once a second: decrement, update the "NN sec" counter, finish at zero.
    private val tick = object : Runnable {
        override fun run() {
            remainingSec -= 1
            if (remainingSec <= 0) {
                Log.i(TAG, "auto-close timeout")
                finish()
                return
            }
            updateTimer()
            handler.postDelayed(this, 1000)
        }
    }

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            Log.i(TAG, "ACTION_DISMISS received -> finish")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: showing camera over lockscreen")

        rtspUrl = resolveUrl(intent)

        // Wake the screen + show over the (secure) lock screen without unlocking it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.requestDismissKeyguard(this, null) // best-effort; harmless if it stays locked
        goImmersive()

        setContentView(R.layout.activity_camera)
        videoLayout = findViewById(R.id.video_layout)
        intent.getStringExtra(DashService.EXTRA_PAYLOAD)?.let { p ->
            try { JSONObject(p).optString("title").takeIf { it.isNotEmpty() }
                ?.let { findViewById<TextView>(R.id.label).text = it } } catch (_: Exception) {}
        }
        // Close ONLY via the dedicated button (or auto-timeout / MQTT dismiss).
        findViewById<android.view.View>(R.id.btn_close).setOnClickListener { finish() }

        muteBtn = findViewById(R.id.btn_mute)
        updateMuteIcon()
        muteBtn?.setOnClickListener {
            muted = !muted
            applyVolume()
            updateMuteIcon()
        }

        timerView = findViewById(R.id.timer)
        timerView?.setOnClickListener {
            remainingSec += 30   // extend the auto-close by 30s
            updateTimer()
        }

        startPlayer()
        ContextCompat.registerReceiver(
            this, dismissReceiver, IntentFilter(DashService.ACTION_DISMISS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scheduleAutoClose()
        // Chime only when this pop was a real doorbell ring (payload "ring":true, set by
        // automation bellcam_tablet_wake) — NOT on voice / notification-button / launcher opens.
        if (payloadWantsRing(intent)) playRingSound()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-trigger while already showing: reset the auto-close timer + re-chime on a new ring.
        scheduleAutoClose()
        if (payloadWantsRing(intent)) playRingSound()
    }

    /** True when the show payload carries "ring":true (the actual doorbell path). */
    private fun payloadWantsRing(intent: Intent?): Boolean =
        intent?.getStringExtra(DashService.EXTRA_PAYLOAD)?.let {
            try { JSONObject(it).optBoolean("ring", false) } catch (_: Exception) { false }
        } ?: false

    /** One-shot default notification tone. Best-effort — never blocks the camera. Resolving the tone URI
     *  and building the Ringtone touch the settings/media provider, so do it on a worker thread (this is
     *  called from onCreate/onNewIntent right as the camera pops — don't stall that on the UI thread). */
    private fun playRingSound() {
        Thread {
            try {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return@Thread
                ringtone?.let { try { if (it.isPlaying) it.stop() } catch (_: Exception) {} }
                val r = RingtoneManager.getRingtone(applicationContext, uri) ?: return@Thread
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone = r
                r.play()
            } catch (e: Exception) {
                Log.w(TAG, "ring sound failed: ${e.message}")
            }
        }.start()
    }

    /** Prefer a URL from the show payload; else the baked default (doorbell). */
    private fun resolveUrl(intent: Intent): String {
        intent.getStringExtra(DashService.EXTRA_PAYLOAD)?.let { p ->
            try {
                val o = JSONObject(p)
                val u = o.optString("rtsp").ifEmpty { o.optString("url") }
                if (u.isNotEmpty()) return u
            } catch (_: Exception) {}
        }
        return BuildConfig.DEF_RTSP_URL
    }

    // Idle auto-close: reset the countdown to full and tick down, but ONLY over the lock screen. Every
    // user interaction re-calls this via onUserInteraction(), so it's an idle timeout (measured from the
    // last touch/key). In the unlocked state self-dismissal is disabled (timer cleared, no countdown).
    private fun scheduleAutoClose() {
        handler.removeCallbacks(tick)
        if (!isLocked()) { timerView?.text = ""; return }       // unlocked -> never self-dismiss
        remainingSec = BuildConfig.DEF_DISMISS_MS / 1000
        if (remainingSec <= 0) { timerView?.text = ""; return }  // auto-close disabled
        updateTimer()
        handler.postDelayed(tick, 1000)
    }

    private fun isLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    override fun onUserInteraction() { super.onUserInteraction(); scheduleAutoClose() }

    private fun updateTimer() {
        timerView?.text = "$remainingSec secs"
    }

    private fun startPlayer() {
        if (rtspUrl.isEmpty()) { Log.w(TAG, "no RTSP url"); return }
        val args = arrayListOf(
            "--rtsp-tcp",              // TCP transport = reliable over wifi
            "--network-caching=300",  // ~300ms buffer: low latency, tolerant
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--live-caching=300"
        )
        val vlc = LibVLC(this, args)
        val mp = MediaPlayer(vlc)
        libVlc = vlc
        player = mp
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.EncounteredError -> Log.e(TAG, "player error")
                MediaPlayer.Event.Playing -> applyVolume()  // enforce mute state once audio is live
            }
        }
        val media = Media(vlc, Uri.parse(rtspUrl)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=300")
        }
        mp.media = media
        media.release()
        mp.play()
        applyVolume()
    }

    private fun applyVolume() {
        try { player?.setVolume(if (muted) 0 else 100) } catch (_: Exception) {}
    }

    private fun updateMuteIcon() {
        muteBtn?.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
    }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { unregisterReceiver(dismissReceiver) } catch (_: Exception) {}
        try {
            player?.stop()
            player?.detachViews()
            player?.release()
            libVlc?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release error: ${e.message}")
        }
        player = null
        libVlc = null
        super.onDestroy()
    }

    companion object { const val TAG = "CameraActivity" }
}
