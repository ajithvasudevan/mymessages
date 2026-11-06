package dev.ajithv.mymessages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Relays the doorbell to the Wear OS watch over the Google Wearable Data Layer (Bluetooth).
 *
 * The watch counterpart app shares this package name (dev.ajithv.mymessages) + signing key, so
 * DataItems route to it directly — the watch needs NO Wi-Fi / no HA reachability of its own.
 *
 * Everything rides on the /bell/photo DataItem (DataItem sync is reliable over BT; a separate
 * MessageClient message proved not to deliver phone->watch here). Each pushed item holds:
 *   - photo : the fresh, downscaled bellcam JPEG, and
 *   - ring  : true on the FIRST frame of a doorbell ring -> the watch wakes + pops full-screen;
 *             false on the streaming frames that follow (near-live refresh).
 *
 * TUNING (live, from HA, like [TalkConfig]): HA publishes a retained JSON
 * `{"window":<sec>,"cadence":<sec>}` to `<base>/watch/cfg`; [DashService] feeds it to [applyConfig].
 *   - window  = how long to keep streaming after a ring (default 60 s).
 *   - cadence = seconds between frames during the window (default 2.5 s).
 *
 * HA is reached at BuildConfig.DEF_HA_URL (LAN IP, always available via Tailscale). The live
 * camera_proxy frame needs the bearer token (DEF_HA_TOKEN); the public saved snapshot is fallback.
 */
object WatchRelay {
    private const val TAG = "WatchRelay"
    private const val PATH_PHOTO = "/bell/photo"
    private const val MAX_DIM = 480
    private const val JPEG_Q = 72
    private const val PREFS = "mymessages_watch"
    /** Broadcast when the shared config changes, so the MainActivity sliders re-sync. */
    const val ACTION_CFG_UPDATED = "dev.ajithv.mymessages.WATCH_CFG_UPDATED"

    // Live-tunable (ms). Defaults match the original hardcoded values.
    @Volatile private var windowMs = 60_000L
    @Volatile private var frameMs = 2_500L

    /** Current config for the UI (shared source of truth = the HA helpers via retained lockdash/watch/cfg). */
    fun windowSec(): Int = (windowMs / 1000L).toInt()
    fun cadenceSec(): Double = frameMs / 1000.0

    private val sched = Executors.newScheduledThreadPool(2)
    private val lock = Any()
    private var task: ScheduledFuture<*>? = null
    @Volatile private var deadline = 0L
    @Volatile private var pendingRing = false

    private fun cameraUrl() = "${BuildConfig.DEF_HA_URL}/api/camera_proxy/camera.reolink_camera"
    private fun savedUrl() = "${BuildConfig.DEF_HA_URL}/local/bellsnaps/doorbell_latest.jpg"

    /** Restore persisted tuning on service start. */
    fun loadConfig(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            windowMs = p.getLong("window_ms", windowMs)
            frameMs = p.getLong("frame_ms", frameMs)
            Log.i(TAG, "config loaded window=${windowMs}ms cadence=${frameMs}ms")
            broadcastUpdate(ctx)
        } catch (_: Exception) {}
    }

    private fun broadcastUpdate(ctx: Context) {
        try { ctx.sendBroadcast(Intent(ACTION_CFG_UPDATED).setPackage(ctx.packageName)) } catch (_: Exception) {}
    }

    /** Apply + persist a config JSON (any subset of window/cadence, in seconds). */
    fun applyConfig(ctx: Context, json: String) {
        try {
            val o = JSONObject(json)
            if (o.has("window")) windowMs = (o.getDouble("window") * 1000).toLong().coerceIn(10_000L, 600_000L)
            if (o.has("cadence")) frameMs = (o.getDouble("cadence") * 1000).toLong().coerceIn(1_000L, 10_000L)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("window_ms", windowMs)
                .putLong("frame_ms", frameMs)
                .apply()
            Log.i(TAG, "config applied window=${windowMs}ms cadence=${frameMs}ms")
            broadcastUpdate(ctx)
        } catch (e: Exception) { Log.w(TAG, "applyConfig: ${e.message}") }
    }

    /**
     * Fire on a doorbell ring (ring=true) or a watch's manual "show doorbell" (ring=false):
     * push frames to the watch for the window. Calling again extends the window. A ring sends an
     * immediate ring-flagged frame so the watch pops with minimal delay.
     */
    fun relayBell(ctx: Context, ring: Boolean) {
        val app = ctx.applicationContext
        deadline = System.currentTimeMillis() + windowMs
        if (ring) {
            pendingRing = true
            sched.execute { safePush(app) }     // immediate ring frame
        }
        synchronized(lock) {
            if (task == null || task!!.isDone) {
                task = sched.scheduleWithFixedDelay({
                    if (System.currentTimeMillis() > deadline) {
                        synchronized(lock) { task?.cancel(false); task = null }
                    } else {
                        safePush(app)
                    }
                }, frameMs, frameMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun safePush(app: Context) {
        try { pushFrame(app) } catch (e: Exception) { Log.w(TAG, "push: ${e.message}") }
    }

    private fun pushFrame(app: Context) {
        val jpeg = fetchFrame() ?: return        // keep pendingRing for the next attempt on failure
        val ringNow = pendingRing
        pendingRing = false                      // consume once we actually have a frame to send
        val small = downscale(jpeg)
        val asset = Asset.createFromBytes(small)
        val req = PutDataMapRequest.create(PATH_PHOTO).apply {
            dataMap.putAsset("photo", asset)
            dataMap.putLong("ts", System.currentTimeMillis())
            dataMap.putBoolean("ring", ringNow)
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(app).putDataItem(req))
        Log.i(TAG, "frame pushed ring=$ringNow (${small.size} B)")
    }

    private fun fetchFrame(): ByteArray? {
        fetch(cameraUrl(), BuildConfig.DEF_HA_TOKEN)?.let { return it }
        return fetch(savedUrl(), null)
    }

    private fun fetch(url: String, token: String?): ByteArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 10000; instanceFollowRedirects = true
            if (!token.isNullOrEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else { c.disconnect(); null }
    } catch (e: Exception) { Log.w(TAG, "fetch failed: ${e.message}"); null }

    private fun downscale(jpeg: ByteArray): ByteArray {
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val w = bmp.width; val h = bmp.height
            val scale = MAX_DIM.toFloat() / maxOf(w, h)
            val out = if (scale < 1f)
                Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true) else bmp
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, bos)
            bos.toByteArray()
        } catch (e: Exception) { jpeg }
    }
}
