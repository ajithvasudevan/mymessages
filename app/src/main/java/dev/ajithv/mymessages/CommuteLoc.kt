package dev.ajithv.mymessages

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
 * On-demand commute location stream, INDEPENDENT of Trip Tracker trips (no trip, no DB, no buffering).
 * Toggled by the Bus Tracker COMMUTE_LOC broadcast, the HA commute/control MQTT, or the Notifications-tab
 * switch. While on, per high-accuracy fix it (1) broadcasts dev.ajithv.bustracker.LIVE_POINT to the Bus
 * Tracker map and (2) publishes MQTT commute/gps so the HA Commute dashboard's ETA updates.
 *
 * SELF-STOP (so it can never stream forever if nothing turns it off — e.g. Bus Tracker is backgrounded
 * and can't send the off signal): auto-expires past the evening window end (22:30) or after MAX hours.
 * onExpire() lets DashService clear its persisted flag + static state.
 */
class CommuteLoc(
    private val ctx: Context,
    private val onExpire: () -> Unit,
    private val publish: (String, String) -> Unit,
) {
    private val fused: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(ctx)
    @Volatile private var running = false
    @Volatile private var manual = false   // user's manual override: ignore the evening-window self-stop
    private var intervalS = 5
    private var startedMs = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            // Self-stop guard: never stream past the evening window end or beyond the hard cap.
            val cal = java.util.Calendar.getInstance()
            val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            // A manual override ignores the evening-window cutoff (streams any time) and uses a longer
            // safety cap; an auto session self-stops past the window or the 4 h cap.
            val cap = if (manual) MANUAL_MAX else MAX_MS
            if ((!manual && mins > WIN_END) || (startedMs > 0 && System.currentTimeMillis() - startedMs > cap)) {
                Log.i(TAG, "commute location self-expired (manual=$manual)"); onExpire(); return
            }
            if (loc.hasAccuracy() && loc.accuracy > 60f) return   // drop coarse/network fixes
            if (loc.time > 0 && System.currentTimeMillis() - loc.time > 15_000L) return   // drop stale/cached fixes (last-known jumps)
            try {
                val bb = Intent("dev.ajithv.bustracker.LIVE_POINT").setPackage("dev.ajithv.bustracker")
                bb.putExtra("ts", loc.time)
                bb.putExtra("lat", loc.latitude)
                bb.putExtra("lon", loc.longitude)
                if (loc.hasSpeed()) bb.putExtra("speed", loc.speed)
                if (loc.hasAccuracy()) bb.putExtra("acc", loc.accuracy)
                ctx.sendBroadcast(bb)
            } catch (e: Exception) { Log.w(TAG, "commute broadcast failed: ${e.message}") }
            val o = JSONObject().put("ts", loc.time).put("lat", loc.latitude).put("lon", loc.longitude)
            if (loc.hasSpeed()) o.put("speed", loc.speed.toDouble())
            if (loc.hasAccuracy()) o.put("acc", loc.accuracy.toDouble())
            publish("commute/gps", o.toString())
        }
    }

    @SuppressLint("MissingPermission")
    fun start(interval: Int, manual: Boolean = false) {
        val iv = interval.coerceIn(2, 60)
        this.manual = manual   // update even on an idempotent re-assert, so the self-stop reflects it
        if (running && iv == intervalS) return   // idempotent re-assert
        intervalS = iv
        if (!hasPerm()) { Log.w(TAG, "no location permission — cannot stream commute GPS"); return }
        try {
            fused.removeLocationUpdates(callback)
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, iv * 1000L)
                .setMinUpdateIntervalMillis(iv * 1000L)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .build()
            fused.requestLocationUpdates(req, callback, ctx.mainLooper)
            startedMs = System.currentTimeMillis()
            running = true
            Log.i(TAG, "commute location ON (interval ${iv}s)")
        } catch (e: Exception) { Log.e(TAG, "commute start failed", e) }
    }

    fun stop() {
        if (!running) return
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        running = false
        Log.i(TAG, "commute location OFF")
    }

    private fun hasPerm() =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CommuteLoc"
        private const val WIN_END = 22 * 60 + 30        // 22:30 — evening window end (auto only)
        private const val MAX_MS = 4L * 60 * 60 * 1000  // auto hard cap: never stream > 4 h
        private const val MANUAL_MAX = 12L * 60 * 60 * 1000  // manual-override safety cap: 12 h
    }
}
