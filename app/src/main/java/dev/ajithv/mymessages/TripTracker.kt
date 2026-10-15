package dev.ajithv.mymessages

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

/**
 * Streams GPS fixes to the trip-tracker add-on over MQTT while a trip is active.
 *
 * Driven entirely by the retained `trips/state` message: when it says a trip is active, we request
 * FusedLocation updates at the trip's interval and publish each fix to `trips/track`; when inactive,
 * we stop. The ADD-ON allocates the authoritative trip id (a Postgres serial) — the phone just learns
 * it from `trips/state`. Runs inside DashService's existing foreground service (wake/wifi locks +
 * the `location` FGS type), so there's no separate service lifecycle and foreground-location
 * permission alone keeps fixes flowing (no background-location grant needed while the FGS runs).
 */
class TripTracker(
    private val ctx: Context,
    private val base: String,
    private val publish: (String, String) -> Unit,
) {
    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(ctx)
    private var tripId: Int = 0
    private var intervalS: Int = Prefs.DEF_TRIP_INTERVAL
    @Volatile private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (tripId <= 0) return
            // Drop low-confidence (network/coarse) fixes so the track stays precise.
            if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) {
                Log.i(TAG, "fix dropped: accuracy ${loc.accuracy}m > ${MAX_ACCURACY_M}m")
                return
            }
            val o = JSONObject()
                .put("trip_id", tripId)
                .put("ts", loc.time)            // epoch ms
                .put("lat", loc.latitude)
                .put("lon", loc.longitude)
                .put("source", "app")
            if (loc.hasAccuracy()) o.put("acc", loc.accuracy.toDouble())
            if (loc.hasSpeed()) o.put("speed", loc.speed.toDouble())
            if (loc.hasAltitude()) o.put("alt", loc.altitude)
            publish("$base/track", o.toString())
            Prefs.setTripLastFix(ctx, String.format("%.5f, %.5f", loc.latitude, loc.longitude))
        }
    }

    /** React to a retained/live `trips/state` payload: start or stop the loop to match. */
    fun onState(payload: String) {
        val o = try { JSONObject(payload) } catch (_: Exception) { return }
        if (o.optBoolean("active", false)) {
            start(o.optInt("trip_id", 0), o.optInt("interval_s", Prefs.DEF_TRIP_INTERVAL).coerceAtLeast(1))
        } else {
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(id: Int, interval: Int) {
        if (id <= 0) return
        Prefs.setTripActive(ctx, true); Prefs.setTripId(ctx, id); Prefs.setTripInterval(ctx, interval)
        val changed = id != tripId || interval != intervalS || !running
        tripId = id; intervalS = interval
        if (!changed) return
        if (!hasLocationPermission()) { Log.w(TAG, "location permission not granted — cannot stream"); return }
        val ms = interval * 1000L
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
            .setMinUpdateIntervalMillis(ms)
            .setGranularity(Granularity.GRANULARITY_FINE)   // force precise (GPS), never coarse
            .setWaitForAccurateLocation(true)               // wait for an accurate fix, not an early network one
            .build()
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(req, callback, ctx.mainLooper)
            running = true
            Log.i(TAG, "trip $id streaming every ${interval}s")
        } catch (e: Exception) { Log.e(TAG, "requestLocationUpdates failed", e) }
    }

    fun stop() {
        Prefs.setTripActive(ctx, false)
        if (!running && tripId == 0) return
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        running = false; tripId = 0
        Log.i(TAG, "trip streaming stopped")
    }

    /** Belt-and-braces resume on service (re)start; the retained `trips/state` also re-triggers this. */
    fun resumeIfActive() {
        if (Prefs.tripActive(ctx)) start(Prefs.tripId(ctx), Prefs.tripInterval(ctx))
    }

    private fun hasLocationPermission() =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TripTracker"
        private const val MAX_ACCURACY_M = 50f   // ignore fixes less accurate than this (metres)
    }
}
