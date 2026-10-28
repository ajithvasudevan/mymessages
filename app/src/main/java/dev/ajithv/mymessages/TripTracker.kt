package dev.ajithv.mymessages

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streams GPS fixes to the trip-tracker add-on over MQTT while a trip is active.
 *
 * Driven by the retained `trips/state`: when a trip is active we request FusedLocation updates and
 * record fixes; when inactive we stop. The ADD-ON allocates the authoritative trip id (a Postgres
 * serial) — the phone learns it from `trips/state`. Runs inside DashService's foreground service.
 *
 * Spacing modes (from `trips/state`):
 *   - "time":     request updates every `interval_s` and record every (accurate) fix.
 *   - "distance": sample fast (~1/s) and record a fix only when ≥ `min_distance_m` from the last
 *                 RECORDED fix — so points are spaced by distance, not time.
 *
 * Offline buffering: every recorded fix goes into a durable queue (in-memory, spilled to a flash file
 * past [MEM_MAX]) and is published to `trips/track` oldest-first whenever MQTT is connected. So an
 * MQTT/network outage no longer drops points — the track fills in on reconnect. Each buffered payload
 * carries its own trip_id, so points replayed after the trip is stopped still land in the right trip.
 */
class TripTracker(
    private val ctx: Context,
    private val base: String,
    private val isConnected: () -> Boolean,
    private val publish: (String, String) -> Unit,
) {
    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(ctx)
    private var tripId: Int = 0
    private var intervalS: Int = Prefs.DEF_TRIP_INTERVAL
    private var mode: String = "time"
    private var minDistM: Int = 0
    // Last fix we RECORDED (buffered) — the reference for the distance gate. null = nothing yet.
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    @Volatile private var running = false
    // Stop/resume detection + previous RAW fix (for a delta-speed fallback when a fix lacks GPS speed).
    private var moving = false
    private var powerSave = false   // battery saver (motion-gated GPS); read from Prefs at start()
    private var reqMoving = true    // which LocationRequest is currently active (high-accuracy vs low-power)
    private var prevFixLat = 0.0
    private var prevFixLon = 0.0
    private var prevFixMs = 0L
    private var lastSentMs = 0L   // epoch ms of the last RECORDED point (hybrid time criterion)
    private val recentSp = ArrayDeque<Pair<Long, Double>>()   // (epoch ms, speed m/s) of recent recorded fixes

    // ---- durable offline buffer (all buffer state touched only on `io`, a single thread) ----
    private val io = Executors.newSingleThreadExecutor()
    private val pending = ArrayDeque<String>()
    private fun bufFile() = File(ctx.filesDir, "trip_buffer.ndjson")

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (tripId <= 0) return
            // Drop low-confidence (network/coarse) fixes so the track stays precise.
            if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) {
                Log.i(TAG, "fix dropped: accuracy ${loc.accuracy}m > ${MAX_ACCURACY_M}m")
                return
            }
            // Instantaneous speed (m/s): prefer the GPS/Doppler speed (accurate, ~0 at rest); fall
            // back to a delta-from-previous-fix estimate only when the fix carries no speed.
            var sp = if (loc.hasSpeed()) loc.speed.toDouble() else Double.NaN
            if (sp.isNaN() && prevFixMs != 0L) {
                val dtp = (loc.time - prevFixMs) / 1000.0
                if (dtp > 0) sp = haversine(prevFixLat, prevFixLon, loc.latitude, loc.longitude) / dtp
            }
            prevFixLat = loc.latitude; prevFixLon = loc.longitude; prevFixMs = loc.time

            // Stop/resume detection (hysteresis): emit a point the instant we come to rest or move
            // off, even though distance mode would otherwise send nothing — so the shown speed is
            // not stuck at the last rolling value after we stop.
            val wasMoving = moving
            if (!sp.isNaN()) {
                if (moving && sp < STOP_MS) moving = false
                else if (!moving && sp > START_MS) moving = true
            }
            val transition = moving != wasMoving
            // Battery saver: while stopped, drop to a low-power/coarse request; switch back to 1 Hz
            // high-accuracy the moment motion resumes. Only in distance/hybrid (time mode is already paced).
            if (powerSave && mode != "time" && reqMoving != moving) applyRequest(moving)

            // Spacing gate. time: every (interval-sampled) fix. distance: when moved >= minDist.
            // hybrid: when moved >= minDist OR interval elapsed — so it is distance-spaced at speed
            // and purely time-spaced at rest. A stop/resume transition always records.
            if (mode == "distance" || mode == "hybrid") {
                val pla = lastLat; val plo = lastLon
                if (pla != null && plo != null && !transition) {
                    val far = minDistM > 0 && haversine(pla, plo, loc.latitude, loc.longitude) >= minDistM
                    val timeUp = mode == "hybrid" && lastSentMs != 0L && (loc.time - lastSentMs) >= intervalS * 1000L
                    if (!far && !timeUp) return
                }
            }
            // Adaptive GPS-outlier gate: drop a fix that lands farther than max(BASE, recentSpeed*dt*K) from
            // the last recorded point. Scales with recent speed (train/plane friendly); BASE catches teleports
            // from near-rest. Skipped when dt is large (a real data gap) or there's no reference point yet.
            run {
                val rl = lastLat; val ro = lastLon
                if (rl != null && ro != null && lastSentMs != 0L) {
                    val dtO = (loc.time - lastSentMs) / 1000.0
                    if (dtO > 0 && dtO <= OUTLIER_GAP_S) {
                        val distO = haversine(rl, ro, loc.latitude, loc.longitude)
                        val recentMs = if (recentSp.isEmpty()) 0.0 else recentSp.sumOf { it.second } / recentSp.size
                        val allow = maxOf(OUTLIER_BASE_M, recentMs * dtO * OUTLIER_K)
                        if (distO > allow) {
                            Log.i(TAG, "fix dropped: ${distO.toInt()}m jump > ${allow.toInt()}m (recent ${(recentMs * 3.6).toInt()} km/h, dt ${dtO}s) — GPS outlier")
                            return
                        }
                    }
                }
            }
            val o = JSONObject()
                .put("trip_id", tripId)
                .put("ts", loc.time)            // epoch ms
                .put("lat", loc.latitude)
                .put("lon", loc.longitude)
                .put("source", "app")
            if (loc.hasAccuracy()) o.put("acc", loc.accuracy.toDouble())
            if (!sp.isNaN()) o.put("speed", sp)   // GPS speed, or the delta estimate when absent
            if (loc.hasAltitude()) o.put("alt", loc.altitude)
            // Record first (distance-gate reference + buffer), independent of connectivity.
            lastLat = loc.latitude; lastLon = loc.longitude; lastSentMs = loc.time
            if (!sp.isNaN()) {   // feed the recent-average-speed window (recorded fixes only)
                recentSp.addLast(loc.time to sp)
                while (recentSp.isNotEmpty() && loc.time - recentSp.first().first > RECENT_WINDOW_MS) recentSp.removeFirst()
            }
            Prefs.setTripLastFix(ctx, String.format("%.5f, %.5f", loc.latitude, loc.longitude))
            // Hand the fix straight to the Trip Tracker app on this device (outage-proof live render,
            // separate from the MQTT->add-on path, which continues unchanged). Explicit + signature-gated.
            try {
                val bi = Intent("dev.ajithv.triptracker.LIVE_POINT").setPackage("dev.ajithv.triptracker")
                bi.putExtra("trip_id", tripId)
                bi.putExtra("ts", loc.time)
                bi.putExtra("lat", loc.latitude)
                bi.putExtra("lon", loc.longitude)
                if (loc.hasAltitude()) bi.putExtra("alt", loc.altitude)
                if (!sp.isNaN()) bi.putExtra("speed", sp.toFloat())
                if (loc.hasAccuracy()) bi.putExtra("acc", loc.accuracy)
                ctx.sendBroadcast(bi)
            } catch (e: Exception) { Log.w(TAG, "live broadcast failed: ${e.message}") }
            val payload = o.toString()
            io.execute { enqueue(payload); flush() }
        }
    }

    // ---- buffer ops (io thread only) ----
    private fun enqueue(json: String) {
        pending.addLast(json)
        if (pending.size >= MEM_MAX) spill()
    }

    /** Move the whole in-memory queue to flash, bounding RAM during a long outage. */
    private fun spill() {
        try {
            bufFile().appendText(pending.joinToString("\n", postfix = "\n"))
            Log.i(TAG, "spilled ${pending.size} buffered points to flash")
            pending.clear()
        } catch (e: Exception) { Log.w(TAG, "spill failed: ${e.message}") }
    }

    /** Publish the backlog oldest-first (flash file, then memory) while connected. */
    private fun flush() {
        if (!isConnected()) return
        var sent = 0
        val f = bufFile()
        if (f.exists()) {
            val lines = try { f.readLines() } catch (e: Exception) { emptyList() }
            var i = 0
            while (i < lines.size && isConnected()) {
                if (lines[i].isNotBlank()) { publish("$base/track", lines[i]); throttle(++sent) }
                i++
            }
            try {
                if (i >= lines.size) f.delete()
                else { f.writeText(lines.subList(i, lines.size).joinToString("\n", postfix = "\n")); return }
            } catch (e: Exception) { Log.w(TAG, "flush file rewrite failed: ${e.message}") }
        }
        while (pending.isNotEmpty() && isConnected()) { publish("$base/track", pending.removeFirst()); throttle(++sent) }
        if (sent > 0) Log.i(TAG, "flushed $sent buffered points")
    }

    // Keep well under Paho's in-flight window so a large replay isn't silently dropped.
    private fun throttle(n: Int) { if (n % 50 == 0) try { Thread.sleep(40) } catch (_: InterruptedException) {} }

    /** DashService calls this on (re)connect so the backlog drains as soon as the link is back. */
    fun onMqttConnected() { io.execute { flush() } }

    /** React to a retained/live `trips/state` payload: start or stop the loop to match. */
    fun onState(payload: String) {
        val o = try { JSONObject(payload) } catch (_: Exception) { return }
        if (o.optBoolean("active", false)) {
            start(
                o.optInt("trip_id", 0),
                o.optInt("interval_s", Prefs.DEF_TRIP_INTERVAL).coerceAtLeast(1),
                o.optString("mode", "hybrid"),
                o.optInt("min_distance_m", 0),
            )
        } else {
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(id: Int, interval: Int, mode: String = "time", minDist: Int = 0) {
        if (id <= 0) return
        val m = when (mode) { "distance" -> "distance"; "hybrid" -> "hybrid"; else -> "time" }
        Prefs.setTripActive(ctx, true); Prefs.setTripId(ctx, id); Prefs.setTripInterval(ctx, interval)
        Prefs.setTripMode(ctx, m); if (minDist > 0) Prefs.setTripDistance(ctx, minDist)
        val changed = id != tripId || interval != intervalS || m != this.mode || minDist != minDistM || !running
        tripId = id; intervalS = interval; this.mode = m; minDistM = minDist
        if (!changed) return
        // A fresh start (or a mode/param change) resets the distance reference so the first fix is recorded.
        lastLat = null; lastLon = null
        moving = false; prevFixMs = 0L; lastSentMs = 0L
        if (!hasLocationPermission()) { Log.w(TAG, "location permission not granted — cannot stream"); return }
        powerSave = Prefs.tripPowerSave(ctx)
        reqMoving = true   // start high-accuracy; motion gate drops to low-power once a stop is detected
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(buildRequest(true), callback, ctx.mainLooper)
            running = true
            Log.i(TAG, "trip $id streaming mode=$m dist=${minDist}m interval=${interval}s powerSave=$powerSave")
            io.execute { flush() }   // drain anything buffered from a prior session/outage
        } catch (e: Exception) { Log.e(TAG, "requestLocationUpdates failed", e) }
    }

    /** The location request for the current motion state. Battery-saver + stopped (distance/hybrid) → a
     *  low-power, coarse request that just notices movement resuming; otherwise 1 Hz precise GPS. */
    private fun buildRequest(movingNow: Boolean): LocationRequest {
        if (powerSave && mode != "time" && !movingNow) {
            return LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, STOPPED_MS)
                .setMinUpdateIntervalMillis(STOPPED_MS)
                .build()
        }
        val ms = if (mode == "time") intervalS * 1000L else FAST_SAMPLE_MS
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
            .setMinUpdateIntervalMillis(ms)
            .setGranularity(Granularity.GRANULARITY_FINE)   // force precise (GPS), never coarse
            .setWaitForAccurateLocation(true)               // wait for an accurate fix, not an early network one
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun applyRequest(movingNow: Boolean) {
        if (!running) return
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(buildRequest(movingNow), callback, ctx.mainLooper)
            reqMoving = movingNow
            Log.i(TAG, "power-save: switched to ${if (movingNow) "high-accuracy 1Hz" else "low-power/coarse"}")
        } catch (e: Exception) { Log.e(TAG, "re-request failed", e) }
    }

    fun stop() {
        Prefs.setTripActive(ctx, false)
        io.execute { flush() }   // best-effort: push whatever is buffered before we go idle
        if (!running && tripId == 0) return
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        running = false; tripId = 0
        lastLat = null; lastLon = null
        Log.i(TAG, "trip streaming stopped")
    }

    /** Belt-and-braces resume on service (re)start; the retained `trips/state` also re-triggers this. */
    fun resumeIfActive() {
        if (Prefs.tripActive(ctx))
            start(Prefs.tripId(ctx), Prefs.tripInterval(ctx), Prefs.tripMode(ctx), Prefs.tripDistance(ctx))
        else
            io.execute { flush() }   // not active, but an earlier trip may have left buffered points
    }

    private fun hasLocationPermission() =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TripTracker"
        private const val MAX_ACCURACY_M = 50f     // ignore fixes less accurate than this (metres)
        private const val OUTLIER_BASE_M = 50.0    // max jump from the last point when near-stationary (metres)
        private const val OUTLIER_K = 2.0          // recent-speed margin: allow = max(BASE, recent*dt*K)
        private const val OUTLIER_GAP_S = 20.0     // above this dt it's a data gap, not an outlier — don't gate
        private const val RECENT_WINDOW_MS = 20000L  // window for the recent-average-speed estimate
        private const val FAST_SAMPLE_MS = 1000L   // distance-mode sampling rate (~1/s)
        private const val STOPPED_MS = 6000L       // battery-saver sampling rate while stopped (~6 s)
        private const val STOP_MS = 0.6            // m/s: below this = stopped (emit a rest point)
        private const val START_MS = 1.4           // m/s: above this = moving again (hysteresis)
        private const val MEM_MAX = 7200          // ~4 h of points at the observed ~2 s avg gap, then spill to flash

        /** Great-circle distance in metres between two lat/lon points. */
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
