package dev.ajithv.mymessages

import android.content.Context
import org.json.JSONObject

/**
 * Live-tunable doorbell-talk audio settings, driven from the HA dashboard over MQTT and persisted
 * so they survive restarts. [DoorbellTalk] reads [gain] live (per mic buffer) and reads [highpass]/
 * [noiseSuppression] when a talk session starts (APM is built then). HA publishes a retained JSON
 * `{"gain":2.0,"hpf":true,"ns":true}` to `<base>/doorbell/talkcfg`; [DashService] feeds it to [apply].
 */
object TalkConfig {
    @Volatile var gain: Float = 2.0f
    @Volatile var highpass: Boolean = true
    @Volatile var noiseSuppression: Boolean = true

    private const val PREFS = "mymessages_talk"

    fun load(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            gain = p.getFloat("gain", gain)
            highpass = p.getBoolean("hpf", highpass)
            noiseSuppression = p.getBoolean("ns", noiseSuppression)
        } catch (_: Exception) {}
    }

    /** Apply a config JSON (any subset of gain/hpf/ns) and persist. */
    fun apply(ctx: Context, json: String) {
        try {
            val o = JSONObject(json)
            if (o.has("gain")) gain = o.getDouble("gain").toFloat().coerceIn(0.5f, 6.0f)
            if (o.has("hpf")) highpass = o.getBoolean("hpf")
            if (o.has("ns")) noiseSuppression = o.getBoolean("ns")
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("gain", gain)
                .putBoolean("hpf", highpass)
                .putBoolean("ns", noiseSuppression)
                .apply()
        } catch (_: Exception) {}
    }
}
