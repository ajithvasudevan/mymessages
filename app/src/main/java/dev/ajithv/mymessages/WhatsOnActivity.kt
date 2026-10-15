package dev.ajithv.mymessages

import android.content.BroadcastReceiver
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * The "What's On" dashboard: a table of the devices that were ON, each with its live
 * power draw and a HA-style toggle. Data is pushed from HA over MQTT (the app is dumb).
 *
 * The activity owns the frozen row set: the first payload establishes the rows; then it
 * polls HA (`<base>/request` = "whatson") every [REFRESH_MS] while visible and merges the
 * result (a device that dropped out of HA's on-set is shown OFF but its row stays). Opening
 * directly (launcher / shortcut / Main button) just starts the same poll loop.
 */
class WhatsOnActivity : AppCompatActivity() {

    companion object {
        const val TAG = "WhatsOnActivity"
        const val SCREEN = "whatson"
        const val REFRESH_MS = 2000L
        @Volatile var visible = false
    }

    private lateinit var adapter: DeviceAdapter
    private lateinit var title: TextView
    private lateinit var empty: TextView
    private var frozen = false

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }
    private val poll = object : Runnable {
        override fun run() {
            requestData()
            handler.postDelayed(this, REFRESH_MS)
        }
    }
    private val dismissMs get() = BuildConfig.DEF_DISMISS_MS.toLong()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DashService.ACTION_UPDATE ->
                    intent.getStringExtra(DashService.EXTRA_PAYLOAD)?.let { render(it) }
                DashService.ACTION_DISMISS -> finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_whatson)

        title = findViewById(R.id.title)
        empty = findViewById(R.id.empty)
        adapter = DeviceAdapter { d -> onToggle(d) }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@WhatsOnActivity)
            adapter = this@WhatsOnActivity.adapter
        }
        findViewById<View>(R.id.close).setOnClickListener { finish() }

        empty.text = "Loading…"
        empty.visibility = View.VISIBLE
        // A fresh push (voice / service) delivers the first payload as an extra.
        intent.getStringExtra(DashService.EXTRA_PAYLOAD)?.let { render(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(DashService.EXTRA_PAYLOAD)?.let { render(it) }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        val filter = IntentFilter().apply {
            addAction(DashService.ACTION_UPDATE)
            addAction(DashService.ACTION_DISMISS)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        resetAutoDismiss()
        handler.post(poll)               // start the live refresh loop
    }

    override fun onPause() {
        super.onPause()
        visible = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        handler.removeCallbacks(autoDismiss)
        handler.removeCallbacks(poll)    // stop polling when not shown
    }

    // Idle auto-dismiss: (re)arm the timeout, but ONLY over the lock screen. Every user interaction
    // re-calls this via onUserInteraction(), so it's measured from the last touch/key (idle), not from
    // when the screen opened. In the unlocked state self-dismissal is disabled.
    private fun resetAutoDismiss() {
        handler.removeCallbacks(autoDismiss)
        if (!isLocked()) return                              // unlocked -> never self-dismiss
        handler.postDelayed(autoDismiss, dismissMs)
    }

    private fun isLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    override fun onUserInteraction() { super.onUserInteraction(); resetAutoDismiss() }

    private fun requestData() {
        startService(Intent(this, DashService::class.java)
            .setAction(DashService.ACTION_REQUEST)
            .putExtra(DashService.EXTRA_SCREEN, SCREEN))
    }

    private fun onToggle(d: Device) {
        val cmd = JSONObject().apply {
            put("action", "toggle")
            put("entity_id", d.entityId)
            put("domain", d.domain)
        }.toString()
        startService(Intent(this, DashService::class.java)
            .setAction(DashService.ACTION_PUBLISH_CMD)
            .putExtra(DashService.EXTRA_PAYLOAD, cmd))
        resetAutoDismiss()                        // don't close while interacting
        handler.postDelayed({ requestData() }, 600) // reflect the new state promptly
    }

    private fun render(payload: String) {
        val list = try {
            val o = JSONObject(payload)
            if (o.optString("screen") != SCREEN) return   // ignore other screens' payloads
            title.text = o.optString("title", "What's On")
            val arr = o.optJSONArray("devices")
            (0 until (arr?.length() ?: 0)).map { i ->
                val d = arr!!.getJSONObject(i)
                Device(
                    name = d.optString("name"),
                    entityId = d.optString("entity_id"),
                    domain = d.optString("domain"),
                    on = d.optBoolean("on"),
                    power = d.optString("power", "")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad payload: ${e.message}"); return
        }
        if (!frozen) {
            adapter.submit(list)     // first payload establishes the frozen row set
            frozen = true
        } else {
            adapter.merge(list)      // later payloads only update existing rows
        }
        empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        if (adapter.itemCount == 0) empty.text = "Nothing is on."
    }
}
