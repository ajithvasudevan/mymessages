package dev.ajithv.mymessages

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/**
 * Persistent foreground service. Two jobs:
 *  1. WAKE the phone + pop the notification overlay ([NotifActivity]) over the lock screen on demand
 *     (from [NotifListener], [SmsReceiver], or an MQTT push).
 *  2. Hold a persistent MQTT connection to the HA broker so Telegram (and any HA-originated push)
 *     reaches the overlay INSTANTLY via the `telegram-userbot` add-on's `<base>/notif_push`, beating
 *     the phone's Doze-delayed notification.
 *
 * MQTT is a SUBSET of LockDash's: it handles only the overlay-relevant topics
 * (`notif_push`, `tg_image`, `control`, `dismiss`, `ring`, publishes `notif_state`). It does NOT handle
 * LockDash's dashboard `show`/`refresh`/`request`/`cmd` (MyMessages has none of those screens).
 *
 * NOTE: shares LockDash's base topic `lockdash` (so it receives the SAME userbot pushes with no HA
 * change) but uses a DISTINCT client id (`mymessages-<id>`) so it and LockDash don't evict each other.
 */
class DashService : Service() {

    companion object {
        const val TAG = "DashService"
        // MIN-importance channel: hides the status-bar icon and collapses the ongoing notification to a
        // faint minimized row at the bottom of the shade. A channel's importance is immutable once
        // created, so this is a NEW id (the old LOW "mymsg_svc" is deleted in createChannels).
        const val ONGOING_CHANNEL = "mymsg_svc_btn"   // LOW importance: renders the button row collapsed
        // Older channel ids to clean up (importance is immutable → each change needs a fresh id).
        private val OLD_CHANNELS = listOf("mymsg_svc", "mymsg_svc_min")
        const val ONGOING_ID = 1

        const val ACTION_OPEN_NOTIF = "dev.ajithv.mymessages.OPEN_NOTIF"
        const val ACTION_OPEN_NOTIF_LAST = "dev.ajithv.mymessages.OPEN_NOTIF_LAST"
        const val ACTION_NOTIF_DISMISSED = "dev.ajithv.mymessages.NOTIF_DISMISSED"
        // MainActivity's overlay master switch toggled -> publish state to HA (retained).
        const val ACTION_PUBLISH_NOTIF_STATE = "dev.ajithv.mymessages.PUB_NOTIF_STATE"
        // MainActivity turned the persistent-notification / background service OFF -> shut down.
        const val ACTION_STOP = "dev.ajithv.mymessages.STOP_SERVICE"

        // service -> a visible overlay activity
        const val ACTION_DISMISS = "dev.ajithv.mymessages.DISMISS"

        const val EXTRA_PAYLOAD = "payload"
        // --- dashboard half (ported from LockDash) ---
        const val ACTION_OPEN_WHATSON = "dev.ajithv.mymessages.OPEN_WHATSON"
        const val ACTION_OPEN_CAMERA = "dev.ajithv.mymessages.OPEN_CAMERA"
        const val ACTION_OPEN_HA = "dev.ajithv.mymessages.OPEN_HA"
        const val ACTION_OPEN_MUSIC = "dev.ajithv.mymessages.OPEN_MUSIC"
        const val ACTION_REQUEST = "dev.ajithv.mymessages.REQUEST"       // extra EXTRA_SCREEN
        const val ACTION_PUBLISH_CMD = "dev.ajithv.mymessages.PUB_CMD"   // extra EXTRA_PAYLOAD
        const val ACTION_UPDATE = "dev.ajithv.mymessages.UPDATE"         // live-screen refresh (EXTRA_PAYLOAD)
        const val EXTRA_SCREEN = "screen"

        // --- on-demand ring (find-my-phone) ---
        const val ACTION_START_RING = "dev.ajithv.mymessages.START_RING"
        const val ACTION_STOP_RING = "dev.ajithv.mymessages.STOP_RING"
        const val ACTION_CLEAR_STATUS = "dev.ajithv.mymessages.CLEAR_STATUS"  // Clear button on the status line
        // Trip Tracker: the Trip tab's buttons publish trips/control; the loop itself is driven by the
        // retained trips/state (see TripTracker). EXTRA_PAYLOAD = trip name, EXTRA_SCREEN = interval (s).
        const val ACTION_TRIP_START = "dev.ajithv.mymessages.TRIP_START"
        const val ACTION_TRIP_STOP  = "dev.ajithv.mymessages.TRIP_STOP"
        const val RING_CHANNEL = "mymsg_ring"                             // HIGH importance (heads-up + Stop)
        const val RING_ID = 2                                            // distinct from ONGOING_ID (1)
        const val RING_DEFAULT_SECS = 120                                // auto-stop safety if no /ring stop

        @Volatile var connected = false
    }

    private var client: MqttAsyncClient? = null
    private val base get() = BuildConfig.DEF_BASE_TOPIC
    private val tripBase get() = BuildConfig.DEF_TRIP_TOPIC
    private var trip: TripTracker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    // Free-text status shown below the notification buttons; set over MQTT (<base>/status).
    @Volatile private var statusText: String = ""

    // ---- on-demand ring state ----
    private var ringPlayer: MediaPlayer? = null
    private var ringVibrator: Vibrator? = null
    private var ringPrevAlarmVol: Int = -1            // saved alarm-stream volume, restored on stop
    private val ringHandler = Handler(Looper.getMainLooper())
    private val ringTimeout = Runnable { stopRinging() }
    @Volatile private var ringing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TalkConfig.load(this)   // doorbell-talk audio settings (gain/hpf/ns), updated live over MQTT
        trip = TripTracker(this, tripBase) { t, p -> publish(t, p) }
        createChannels()
        // A foreground service MUST post its notification promptly (mandatory when started via
        // startForegroundService), so always startForeground first, then drop it if we're not meant to
        // run persistently (user turned the persistent notification off, or globally disabled).
        startForeground(ONGOING_ID, ongoingNotification())
        if (shouldRunForeground()) {
            acquireLocks()
            connect()
            trip?.resumeIfActive()   // resume GPS streaming if a trip was active before the restart
        } else {
            hideOngoing()   // no persistent notification; created only to service a one-off pop
        }
    }

    /** The persistent foreground service (+ its notification + MQTT + wake locks) runs only when the
     *  user has left it on AND we aren't globally disabled. Off = MyMessages is a plain SMS app with no
     *  persistent notification (SMS reception, handled by the manifest receiver, is unaffected). */
    private fun shouldRunForeground() = Prefs.fgEnabled(this) && !Prefs.globalDisabled(this)

    /** Partial wake lock + high-perf WiFi lock so the MQTT keepalive keeps flowing under Doze
     *  (else the broker drops us and a push in that window is lost). */
    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mymessages:mqtt").apply {
                setReferenceCounted(false); acquire()
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mymessages:mqtt").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.w(TAG, "lock acquire failed: ${e.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_OPEN_NOTIF ->
                if (shouldPopOverlay()) openActivity(NotifActivity::class.java, intent.getStringExtra(EXTRA_PAYLOAD))
                else Log.i(TAG, "overlay pop suppressed (unlocked + an app is foreground)")
            ACTION_OPEN_NOTIF_LAST ->
                openActivity(NotifActivity::class.java, """{"screen":"notif","silent":true}""")
            ACTION_NOTIF_DISMISSED -> updateOngoing()
            ACTION_PUBLISH_NOTIF_STATE -> publishNotifState()
            ACTION_OPEN_WHATSON -> openActivity(WhatsOnActivity::class.java, null, respectDisable = false)
            ACTION_OPEN_CAMERA -> openActivity(CameraActivity::class.java, null, respectDisable = false)
            ACTION_OPEN_HA -> openActivity(HaWebActivity::class.java, null, respectDisable = false)
            ACTION_OPEN_MUSIC -> openActivity(MusicActivity::class.java, null, respectDisable = false)
            ACTION_REQUEST -> intent.getStringExtra(EXTRA_SCREEN)?.let { publish("$base/request", it) }
            ACTION_PUBLISH_CMD -> intent.getStringExtra(EXTRA_PAYLOAD)?.let { publish("$base/cmd", it) }
            ACTION_START_RING -> startRinging(intent.getStringExtra(EXTRA_PAYLOAD)?.toIntOrNull() ?: RING_DEFAULT_SECS)
            ACTION_STOP_RING -> stopRinging()   // the heads-up "Stop" action, or a manual stop
            ACTION_CLEAR_STATUS -> {        // the "Clear" button on the status line
                statusText = ""
                updateOngoing()
                // also clear the retained source so it doesn't reappear on reconnect
                try { client?.publish("$base/status", MqttMessage(ByteArray(0)).apply { qos = 1; isRetained = true }) }
                catch (e: Exception) { Log.w(TAG, "status clear publish failed: ${e.message}") }
            }
            ACTION_TRIP_START -> {
                val name = intent.getStringExtra(EXTRA_PAYLOAD) ?: ""
                val iv = intent.getStringExtra(EXTRA_SCREEN)?.toIntOrNull() ?: Prefs.tripInterval(this)
                val o = JSONObject().put("cmd", "start").put("source", "app").put("interval_s", iv)
                if (name.isNotEmpty()) o.put("name", name)
                publish("$tripBase/control", o.toString())   // add-on creates the trip + publishes trips/state
            }
            ACTION_TRIP_STOP ->
                publish("$tripBase/control", JSONObject().put("cmd", "stop").put("source", "app").toString())
        }
        // When the persistent service is OFF, we were started only to service this one command (e.g. an
        // overlay pop). Don't linger and don't get resurrected.
        if (!shouldRunForeground()) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    /** Tear everything down (user turned the persistent notification off): disconnect MQTT, release
     *  locks, remove the notification, stop the service. */
    private fun stopEverything() {
        stopRinging()
        trip?.stop()
        try { client?.disconnectForcibly(300) } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        client = null; connected = false
        releaseLocks()
        hideOngoing()
        stopSelf()
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    private fun connect() {
        val uri = "tcp://${BuildConfig.DEF_BROKER_HOST}:${BuildConfig.DEF_BROKER_PORT}"
        try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "dev"
            val clientId = "mymessages-" + androidId.take(15)   // DISTINCT from LockDash's "lockdash-..."
            Log.i(TAG, "connecting as $clientId")
            val c = MqttAsyncClient(uri, clientId, MemoryPersistence())
            client = c
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false           // broker queues pushes across the ~1.3s Doze reconnects
                connectionTimeout = 10
                keepAliveInterval = 30
                if (BuildConfig.DEF_BROKER_USER.isNotEmpty()) userName = BuildConfig.DEF_BROKER_USER
                if (BuildConfig.DEF_BROKER_PASS.isNotEmpty()) password = BuildConfig.DEF_BROKER_PASS.toCharArray()
            }
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "connected (reconnect=$reconnect)")
                    connected = true
                    updateOngoing()
                    c.subscribe("$base/#", 1)
                    c.subscribe("$tripBase/#", 1)   // Trip Tracker: state/control/track
                    try {
                        c.publish("$base/notif_state",
                            MqttMessage(notifStatePayload().toByteArray()).apply { qos = 1; isRetained = true })
                    } catch (e: Exception) { Log.w(TAG, "notif_state publish failed: ${e.message}") }
                }
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "connectionLost: ${cause?.message}"); connected = false; updateOngoing()
                }
                override fun messageArrived(t: String?, message: MqttMessage?) {
                    val topic = t ?: ""; val payload = message?.toString() ?: ""
                    Log.i(TAG, "message $topic = '${payload.take(200)}'")
                    when {
                        // Trip Tracker FIRST — trips/control must not fall into the lockdash "/control"
                        // handler below. Only state drives the GPS loop; track/control are our own echoes.
                        topic == "$tripBase/state" -> trip?.onState(payload)
                        topic.startsWith("$tripBase/") -> { /* our own trips/track or trips/control echo */ }
                        topic.endsWith("/dismiss") -> broadcastLocal(ACTION_DISMISS, null)
                        topic.endsWith("/control") -> handleControl(payload)
                        topic.endsWith("/tg_image") -> handleTgImage(payload)
                        topic.endsWith("/notif_push") -> handleNotifPush(payload)
                        topic.endsWith("/ring") -> handleRing(payload)
                        topic.endsWith("/talkcfg") -> TalkConfig.apply(this@DashService, payload)
                        topic.endsWith("/status") -> handleStatus(payload)
                        topic.endsWith("/show") -> dispatch(payload)
                        topic.endsWith("/refresh") -> {
                            if (WhatsOnActivity.visible || MusicActivity.visible) broadcastLocal(ACTION_UPDATE, payload)
                        }
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            c.connect(opts)
        } catch (e: Exception) { Log.e(TAG, "connect failed", e); updateOngoing() }
    }

    /** `<base>/status`: free-text status shown on a full-width line below the notification
     *  buttons; the notification grows to fit up to 4 lines. Empty / "clear" / "none" hides the line.
     *  Payload is plain text, or JSON {"text":"..."}. Any publisher (HA or otherwise) can drive it. */
    private fun handleStatus(payload: String) {
        var s = payload.trim()
        if (s.startsWith("{")) {
            try { s = JSONObject(s).optString("text", "").trim() } catch (_: Exception) {}
        }
        if (s.equals("clear", true) || s.equals("none", true)) s = ""
        statusText = s
        updateOngoing()
    }

    /** `<base>/control`: disable/off/0/false -> disable, enable/on/1/true -> enable; tg_phone_off/on
     *  routes Telegram exclusively through the userbot (ignore the phone's own Telegram notifications). */
    private fun handleControl(payload: String) {
        val p = payload.trim().lowercase()
        when (p) {
            "tg_phone_off" -> { Prefs.setSuppressTelegram(this, true); Log.i(TAG, "Telegram phone capture OFF"); return }
            "tg_phone_on" -> { Prefs.setSuppressTelegram(this, false); Log.i(TAG, "Telegram phone capture ON"); return }
        }
        val disable = when (p) {
            "disable", "off", "0", "false", "no" -> true
            "enable", "on", "1", "true", "yes" -> false
            else -> { Log.w(TAG, "bad /control payload '$payload'"); return }
        }
        if (Prefs.globalDisabled(this) == disable) { applyGlobalState(); return }
        Prefs.setGlobalDisabled(this, disable)
        Log.i(TAG, "global ${if (disable) "DISABLE" else "ENABLE"}")
        applyGlobalState()
    }

    private fun applyGlobalState() {
        if (Prefs.globalDisabled(this)) { broadcastLocal(ACTION_DISMISS, null); hideOngoing() }
        else if (Prefs.fgEnabled(this)) startForeground(ONGOING_ID, ongoingNotification())
    }

    private fun hideOngoing() {
        try { @Suppress("DEPRECATION") stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { getSystemService(NotificationManager::class.java).cancel(ONGOING_ID) } catch (_: Exception) {}
    }

    /** A Telegram photo the phone's own notification couldn't include; the userbot published
     *  {pkg, ts(ms), url}. Fetch it and attach to the matching row. */
    private fun handleTgImage(payload: String) {
        val o = try { JSONObject(payload) } catch (e: Exception) { Log.w(TAG, "bad tg_image: ${e.message}"); return }
        val url = o.optString("url"); val ts = o.optLong("ts", 0L)
        val pkg = o.optString("pkg", "org.telegram.messenger")
        if (url.isEmpty() || ts <= 0L) return
        Thread {
            val tmp = downloadToTemp(url) ?: return@Thread
            try {
                repeat(6) { _ ->
                    val target = Prefs.targetRowForImage(this, pkg, ts, url, 120_000L)
                    if (target != null) {
                        val dest = File(Prefs.imageDir(this), "${System.nanoTime()}.jpg")
                        tmp.copyTo(dest, overwrite = true)
                        if (Prefs.attachExternalImage(this, target, url, dest.absolutePath)) {
                            sendBroadcast(Intent(NotifListener.ACTION_IMG_UPDATED)
                                .setPackage(packageName).putExtra(NotifListener.EXTRA_TS, target))
                        } else dest.delete()
                        return@Thread
                    }
                    Thread.sleep(1500)
                }
            } catch (e: Exception) { Log.w(TAG, "tg_image attach failed: ${e.message}") }
            finally { tmp.delete() }
        }.start()
    }

    /** `<base>/notif_push`: the userbot's instant Telegram overlay pop {pkg,app,title,text,ts,category,url(s)}. */
    private fun handleNotifPush(payload: String) {
        val o = try { JSONObject(payload) } catch (e: Exception) { Log.w(TAG, "bad notif_push: ${e.message}"); return }
        val pkg = o.optString("pkg", "org.telegram.messenger")
        val app = o.optString("app", "Telegram")
        val title = o.optString("title")
        val text = o.optString("text")
        val category = o.optString("category", "")
        val ts = o.optLong("ts", 0L)
        val urls = ArrayList<String>()
        o.optJSONArray("urls")?.let { for (i in 0 until it.length()) { val u = it.optString(i); if (u.isNotEmpty()) urls.add(u) } }
        if (urls.isEmpty()) o.optString("url").takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        if (ts <= 0L) return
        Thread {
            try {
                val existing = Prefs.messageRowTs(this, pkg, ts, title)
                if (existing != null) {
                    val changed = text.isNotEmpty() && Prefs.updateRowText(this, existing, text)
                    if (urls.isNotEmpty()) attachUrlImageToRow(existing, urls.first())
                    else if (changed) sendBroadcast(Intent(NotifListener.ACTION_IMG_UPDATED)
                        .setPackage(packageName).putExtra(NotifListener.EXTRA_TS, existing))
                    return@Thread
                }
                Prefs.addHistory(this, app, pkg, category, title, text, ts, ts, "ub") { "" }
                for (u in urls) fetchAndAppendImage(ts, u)

                val stale = System.currentTimeMillis() - ts > 60_000L
                if (!stale && !Prefs.globalDisabled(this) && Prefs.notifEnabled(this) &&
                    !Prefs.isItemHidden(this, pkg, app, category, title, text) && shouldPopOverlay()) {
                    openActivity(NotifActivity::class.java,
                        JSONObject().put("screen", NotifActivity.SCREEN).put("ts", ts).toString())
                    sendBroadcast(Intent(NotifListener.ACTION_IMG_UPDATED)
                        .setPackage(packageName).putExtra(NotifListener.EXTRA_TS, ts))
                }
            } catch (e: Exception) { Log.w(TAG, "notif_push failed: ${e.message}") }
        }.start()
    }

    private fun fetchAndAppendImage(rowTs: Long, url: String) {
        val tmp = downloadToTemp(url) ?: return
        try {
            val dest = File(Prefs.imageDir(this), "${System.nanoTime()}.jpg")
            tmp.copyTo(dest, overwrite = true)
            if (Prefs.appendImageToTs(this, rowTs, url, dest.absolutePath)) {
                sendBroadcast(Intent(NotifListener.ACTION_IMG_UPDATED)
                    .setPackage(packageName).putExtra(NotifListener.EXTRA_TS, rowTs))
            } else dest.delete()
        } catch (e: Exception) { Log.w(TAG, "append url image failed: ${e.message}") }
        finally { tmp.delete() }
    }

    private fun attachUrlImageToRow(rowTs: Long, url: String) {
        val tmp = downloadToTemp(url) ?: return
        try {
            val dest = File(Prefs.imageDir(this), "${System.nanoTime()}.jpg")
            tmp.copyTo(dest, overwrite = true)
            if (Prefs.attachExternalImage(this, rowTs, url, dest.absolutePath)) {
                sendBroadcast(Intent(NotifListener.ACTION_IMG_UPDATED)
                    .setPackage(packageName).putExtra(NotifListener.EXTRA_TS, rowTs))
            } else dest.delete()
        } catch (e: Exception) { Log.w(TAG, "attach url image failed: ${e.message}") }
        finally { tmp.delete() }
    }

    private fun downloadToTemp(url: String): File? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 15000; instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            val f = File.createTempFile("tg_", ".img", cacheDir)
            FileOutputStream(f).use { input.copyTo(it) }; f
        }
    } catch (e: Exception) { Log.w(TAG, "download failed: ${e.message}"); null }

    /** Route a `<base>/show` payload to the matching dashboard Activity (open/wake, or update in
     *  place if it is already visible). Music auto-pop is lock-screen-only; whatson/camera always. */
    private fun dispatch(payload: String) {
        val screen = try { JSONObject(payload).optString("screen") } catch (e: Exception) {
            Log.w(TAG, "bad /show payload: ${e.message}"); return }
        val target: Class<*> = when (screen) {
            "whatson" -> WhatsOnActivity::class.java
            "camera" -> CameraActivity::class.java
            "music" -> MusicActivity::class.java
            else -> { Log.w(TAG, "unknown screen '$screen'"); return }
        }
        if ((screen == "whatson" && WhatsOnActivity.visible) ||
            (screen == "music" && MusicActivity.visible)) { broadcastLocal(ACTION_UPDATE, payload); return }
        if (screen == "music") {
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (km?.isKeyguardLocked != true) { Log.i(TAG, "music push ignored (unlocked)"); return }
        }
        openActivity(target, payload, respectDisable = false)
    }

    private fun publish(topic: String, payload: String) {
        try {
            client?.publish(topic, MqttMessage(payload.toByteArray()).apply { qos = 1 })
            Log.i(TAG, "publish $topic = '${payload.take(200)}'")
        } catch (e: Exception) { Log.w(TAG, "publish failed: ${e.message}") }
    }

    /** Wake the screen and bring an overlay Activity up over the lock screen. */
    private fun openActivity(target: Class<*>, payload: String?, respectDisable: Boolean = true) {
        if (respectDisable && Prefs.globalDisabled(this)) { Log.i(TAG, "open ignored (globally disabled)"); return }
        wakeScreen()
        val i = Intent(this, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (payload != null) putExtra(EXTRA_PAYLOAD, payload)
        }
        try { startActivity(i) } catch (e: Exception) { Log.w(TAG, "startActivity failed: ${e.message}") }
    }

    /** Whether an auto-arriving notification should actually POP the overlay right now:
     *  - locked  -> always (over the lock screen, as before);
     *  - unlocked -> only over the HOME screen (a launcher) or our own UI — never on top of another app
     *    the user is actively using.
     *  Fails OPEN (pops) when the foreground can't be determined (e.g. Usage-access not granted), so
     *  notifications are never silently swallowed. User-initiated views (ACTION_OPEN_NOTIF_LAST, Test) and
     *  the dashboard screens do NOT go through this gate. */
    private fun shouldPopOverlay(): Boolean {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return true          // over the lock screen: unchanged
        val fg = foregroundPackage() ?: return true            // foreground unknown -> fail open (pop)
        val ok = fg == packageName || fg in homePackages()     // home / our own UI only when unlocked
        if (!ok) Log.i(TAG, "overlay pop suppressed (unlocked, app in foreground: $fg)")
        return ok
    }

    private var homePkgsCache: Set<String>? = null
    /** The CURRENT home-screen launcher package. Uses resolveActivity(MATCH_DEFAULT_ONLY) — the actual
     *  default home — NOT queryIntentActivities(CATEGORY_HOME), which on OxygenOS also returns
     *  com.android.settings (a stray CATEGORY_HOME activity) and made Settings count as "home". */
    private fun homePackages(): Set<String> {
        homePkgsCache?.let { return it }
        val s = HashSet<String>()
        try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val def = packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            if (def != null && def != "android") s.add(def)   // "android" = the multi-home resolver
        } catch (e: Exception) { Log.w(TAG, "homePackages: ${e.message}") }
        homePkgsCache = s
        return s
    }

    /** The package currently in the foreground, via UsageStats (needs the PACKAGE_USAGE_STATS grant).
     *  Returns null only when it truly can't be determined (no grant). The window is generous: the LAST
     *  foreground transition = the current foreground app; a 10s window missed an app opened earlier (the
     *  bug where the overlay popped over an app). Falls back to the most-recently-used app if no transition
     *  is in-window (e.g. one app held foreground for a long time). */
    private fun foregroundPackage(): String? = try {
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        var pkg: String? = null
        var events = usm?.queryEvents(now - 3_600_000L, now)   // last hour
        val e = android.app.usage.UsageEvents.Event()
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(e)
            // MOVE_TO_FOREGROUND (1) == ACTIVITY_RESUMED (1): the same value across API levels.
            if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = e.packageName
        }
        if (pkg == null && usm != null) {
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 86_400_000L, now)
            pkg = stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        }
        pkg
    } catch (e: Exception) { Log.w(TAG, "foregroundPackage: ${e.message}"); null }

    private fun wakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "mymessages:wake")
            wl.acquire(5000)
        } catch (e: Exception) { Log.w(TAG, "wakeup lock failed: ${e.message}") }
    }

    private fun broadcastLocal(action: String, payload: String?) {
        val i = Intent(action).setPackage(packageName)
        if (payload != null) i.putExtra(EXTRA_PAYLOAD, payload)
        sendBroadcast(i)
    }

    private fun notifStatePayload() = if (Prefs.notifEnabled(this)) "on" else "off"

    private fun publishNotifState() {
        try {
            client?.publish("$base/notif_state",
                MqttMessage(notifStatePayload().toByteArray()).apply { qos = 1; isRetained = true })
            Log.i(TAG, "publish(retained) $base/notif_state = '${notifStatePayload()}'")
        } catch (e: Exception) { Log.w(TAG, "notif_state publish failed: ${e.message}") }
    }

    // ---- on-demand ring (find-my-phone) ----

    /** `<base>/ring`: ring the phone loudly on demand, regardless of the overlay kill-switch or ringer
     *  mode (plays on the ALARM stream, which sounds through silent/vibrate). Payload stop/off/0/false/no
     *  -> stop; a bare integer or JSON `{"secs":N}` -> ring that many seconds; anything else -> ring for
     *  [RING_DEFAULT_SECS]. An auto-stop timer is always armed so a lost "stop" can't ring forever. */
    private fun handleRing(payload: String) {
        val p = payload.trim(); val low = p.lowercase()
        if (low in setOf("stop", "off", "0", "false", "no")) { stopRinging(); return }
        var secs = RING_DEFAULT_SECS
        if (p.startsWith("{")) {
            try { secs = JSONObject(p).optInt("secs", RING_DEFAULT_SECS) } catch (_: Exception) {}
        } else low.toIntOrNull()?.let { if (it > 1) secs = it }
        startRinging(secs)
    }

    /** Max the alarm stream, loop the default ringtone (fallback alarm/notification) through the ALARM
     *  usage, vibrate, wake the screen, and post a HIGH heads-up with a Stop action. Idempotent while
     *  ringing (re-arms the auto-stop timer). */
    private fun startRinging(secs: Int) {
        ringHandler.removeCallbacks(ringTimeout)
        wakeScreen()
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (ringPrevAlarmVol < 0) ringPrevAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: Exception) { Log.w(TAG, "ring volume: ${e.message}") }

        if (ringPlayer == null) {
            try {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringPlayer = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@DashService, uri)
                    isLooping = true
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, w, e -> Log.w(TAG, "ring MediaPlayer err $w/$e"); true }
                    prepareAsync()
                }
            } catch (e: Exception) { Log.e(TAG, "ring player: ${e.message}") }
        }
        startVibrate()
        ringing = true
        try { getSystemService(NotificationManager::class.java).notify(RING_ID, ringNotification()) }
        catch (e: Exception) { Log.w(TAG, "ring notify: ${e.message}") }
        ringHandler.postDelayed(ringTimeout, secs.toLong().coerceIn(5, 600) * 1000L)
        Log.i(TAG, "ringing (${secs}s)")
    }

    private fun stopRinging() {
        ringHandler.removeCallbacks(ringTimeout)
        ringing = false
        try { ringPlayer?.apply { if (isPlaying) stop(); release() } } catch (_: Exception) {}
        ringPlayer = null
        try { ringVibrator?.cancel() } catch (_: Exception) {}
        ringVibrator = null
        try {
            if (ringPrevAlarmVol >= 0) (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_ALARM, ringPrevAlarmVol, 0)
        } catch (e: Exception) { Log.w(TAG, "ring restore vol: ${e.message}") }
        ringPrevAlarmVol = -1
        try { getSystemService(NotificationManager::class.java).cancel(RING_ID) } catch (_: Exception) {}
        Log.i(TAG, "ring stopped")
    }

    private fun startVibrate() {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            ringVibrator = v
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 500), 0), attrs)  // 0 = loop
        } catch (e: Exception) { Log.w(TAG, "vibrate: ${e.message}") }
    }

    private fun ringNotification(): Notification {
        val stopPI = servicePI(ACTION_STOP_RING, 130)
        return NotificationCompat.Builder(this, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dash)
            .setContentTitle("MyMessages is ringing")
            .setContentText("Tap Stop to silence")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(stopPI)
            .addAction(0, "Stop", stopPI)
            .build()
    }

    // ---- notifications ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        for (old in OLD_CHANNELS) try { nm.deleteNotificationChannel(old) } catch (_: Exception) {}
        // LOW (not MIN): silent + no heads-up, but the collapsed notification still renders the quick-
        // launch button row without the user expanding it (MIN hides action content until expanded).
        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, "MyMessages service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the message/notification overlay + MQTT running; quick-launch buttons"
                setShowBadge(false)
            })
        // HIGH importance for the on-demand ring heads-up + Stop action. Audio/vibration are driven by us
        // (MediaPlayer on the alarm stream + looping Vibrator), so the channel itself is silent to avoid
        // a double sound.
        nm.createNotificationChannel(
            NotificationChannel(RING_CHANNEL, "MyMessages ring", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "On-demand loud ring (find my phone)"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            })
    }

    private fun servicePI(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, DashService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun ongoingNotification(): Notification {
        val openMain = PendingIntent.getActivity(
            this, 101, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Custom RemoteViews = six one-tap shortcuts, drawn in the COLLAPSED state via
        // DecoratedCustomViewStyle (which keeps the system header + subText). Dashboard buttons route
        // through the service (getService → openActivity, keyguard-friendly over the lock screen);
        // Messages opens the SMS list; Notifs opens the overlay on the last notification.
        val rv = RemoteViews(packageName, R.layout.notif_buttons)
        rv.setOnClickPendingIntent(R.id.btn_whatson, servicePI(ACTION_OPEN_WHATSON, 111))
        rv.setOnClickPendingIntent(R.id.btn_camera, servicePI(ACTION_OPEN_CAMERA, 112))
        rv.setOnClickPendingIntent(R.id.btn_ha, servicePI(ACTION_OPEN_HA, 113))
        rv.setOnClickPendingIntent(R.id.btn_music, servicePI(ACTION_OPEN_MUSIC, 114))
        rv.setOnClickPendingIntent(R.id.btn_conversations, PendingIntent.getActivity(
            this, 115, Intent(this, ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        rv.setOnClickPendingIntent(R.id.btn_notifications, servicePI(ACTION_OPEN_NOTIF_LAST, 116))
        // Full-width status line below the buttons: shown (up to 4 lines) only when set.
        val status = statusText
        if (status.isNotEmpty()) {
            rv.setTextViewText(R.id.notif_status, status)
            rv.setViewVisibility(R.id.notif_status_row, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.notif_status_row, View.GONE)
        }
        rv.setOnClickPendingIntent(R.id.btn_status_clear, servicePI(ACTION_CLEAR_STATUS, 121))
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dash)
            .setSubText(if (connected) "MQTT connected" else "MQTT disconnected")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setContentIntent(openMain)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDeleteIntent(servicePI(ACTION_NOTIF_DISMISSED, 120))
            .build()
    }

    private fun updateOngoing() {
        if (!shouldRunForeground()) { hideOngoing(); return }
        getSystemService(NotificationManager::class.java).notify(ONGOING_ID, ongoingNotification())
    }

    override fun onDestroy() {
        stopRinging()
        try { client?.disconnectForcibly(500) } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        connected = false
        super.onDestroy()
    }
}
