package dev.ajithv.mymessages

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.tabs.TabLayout
import org.json.JSONObject

/**
 * Home / setup screen, split into three tabs (a [TabLayout] toggling three container views):
 *  - SMS: messaging entry points (Conversations / Compose / Blocked / Text filters / Set-default) + the
 *    SMS status + blocked-SMS summary.
 *  - Notifications: the full "Notification pop-up" overlay settings + notif/MQTT status.
 *  - Dashboard: the LockDash lock-screen screens (What's On / Door Camera / Music / Home Assistant).
 * Starts the overlay listener + MQTT service here and on boot.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var statusNotif: TextView
    private lateinit var blockedStats: TextView
    private lateinit var swNotif: SwitchCompat
    private lateinit var swService: SwitchCompat
    private lateinit var tvFont: TextView
    private lateinit var tvVol: TextView
    private lateinit var tvAutoClose: TextView
    private lateinit var tvSound: TextView
    private lateinit var swBg: android.view.View
    private lateinit var swFg: android.view.View
    private lateinit var btnClear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        statusNotif = findViewById(R.id.status_notif)
        blockedStats = findViewById(R.id.blocked_stats)

        // Three tabs (SMS / Notifications / Dashboard) toggling the container ScrollViews. A plain
        // TabLayout + visibility swap — no ViewPager/fragments needed, every child keeps its id.
        val tabs = findViewById<TabLayout>(R.id.tabs)
        val tabViews = listOf<View>(
            findViewById(R.id.tab_sms), findViewById(R.id.tab_notif),
            findViewById(R.id.tab_dash), findViewById(R.id.tab_trip))
        tabs.addTab(tabs.newTab().setText("SMS"))
        tabs.addTab(tabs.newTab().setText("Notifications"))
        tabs.addTab(tabs.newTab().setText("Dashboard"))
        tabs.addTab(tabs.newTab().setText("Trip"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabViews.forEachIndexed { i, v -> v.visibility = if (i == tab.position) View.VISIBLE else View.GONE }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Dashboard tab: LockDash's over-lock-screen screens, launched in-app (any lock state).
        findViewById<Button>(R.id.btn_whatson).setOnClickListener { openScreen(WhatsOnActivity::class.java) }
        findViewById<Button>(R.id.btn_camera).setOnClickListener { openScreen(CameraActivity::class.java) }
        findViewById<Button>(R.id.btn_music).setOnClickListener { openScreen(MusicActivity::class.java) }
        findViewById<Button>(R.id.btn_ha).setOnClickListener { openScreen(HaWebActivity::class.java) }

        wireTripTab()

        // --- messaging ---
        findViewById<Button>(R.id.btn_conversations).setOnClickListener {
            startActivity(Intent(this, ConversationListActivity::class.java))
        }
        findViewById<Button>(R.id.btn_compose).setOnClickListener {
            startActivity(Intent(this, ComposeActivity::class.java))
        }
        findViewById<Button>(R.id.btn_blocked).setOnClickListener {
            startActivity(Intent(this, BlockedListActivity::class.java))
        }
        findViewById<Button>(R.id.btn_blocked_messages).setOnClickListener {
            startActivity(Intent(this, BlockedMessagesActivity::class.java))
        }
        findViewById<Button>(R.id.btn_regex).setOnClickListener {
            startActivity(Intent(this, RegexListActivity::class.java))
        }
        findViewById<Button>(R.id.btn_notif_filters).setOnClickListener {
            startActivity(Intent(this, NotifFilterListActivity::class.java))
        }
        findViewById<Button>(R.id.btn_default_sms).setOnClickListener { requestDefaultSms() }
        wireSmsFont()

        // Opens the notification overlay on the LAST notification, silently, to page history.
        findViewById<Button>(R.id.btn_notif_screen).setOnClickListener {
            startActivity(Intent(this, NotifActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(DashService.EXTRA_PAYLOAD, """{"screen":"notif","silent":true}"""))
        }

        // --- notification pop-up settings ---
        swNotif = findViewById(R.id.sw_notif)
        tvFont = findViewById(R.id.tv_font)
        tvVol = findViewById(R.id.tv_vol)
        tvAutoClose = findViewById(R.id.tv_autoclose)
        tvSound = findViewById(R.id.tv_sound)
        swBg = findViewById(R.id.sw_bg)
        swFg = findViewById(R.id.sw_fg)
        wireNotifSettings()

        findViewById<Button>(R.id.btn_perms).setOnClickListener { requestAllPermissions() }
        findViewById<Button>(R.id.btn_restart).setOnClickListener { startDashService() }

        // Persistent-notification / background-service toggle. Off = no persistent notification; the app
        // is then a plain SMS client (incoming SMS still works). On = the always-on overlay + instant
        // Telegram machinery (needs the ongoing notification, an Android requirement for a FG service).
        swService = findViewById(R.id.sw_service)
        swService.isChecked = Prefs.fgEnabled(this)
        swService.setOnCheckedChangeListener { _, on ->
            Prefs.setFgEnabled(this, on)
            if (on) startDashService()
            else startService(Intent(this, DashService::class.java).setAction(DashService.ACTION_STOP))
        }

        startDashService()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshBlockedStats()
        maybePromptUsageAccess()
    }

    /** Blocked-SMS summary for the main screen. Scans the SMS provider for the hidden-message count, so
     *  it runs off the UI thread and updates the label when done. */
    private fun refreshBlockedStats() {
        if (::blockedStats.isInitialized) blockedStats.text = "Blocked SMS: …"
        Thread {
            val s = try { SmsRepo.blockedStats(this) } catch (_: Exception) { null }
            runOnUiThread {
                if (!::blockedStats.isInitialized) return@runOnUiThread
                blockedStats.text = if (s == null) "Blocked SMS: (unavailable)"
                else "Blocked SMS: ${s.appSenders} sender(s) · ${s.systemNumbers} system number(s) · " +
                    "${s.textFilters} text filter(s) — hiding ${s.hiddenMessages} message(s)"
            }
        }.start()
    }

    // ------------------------------------------------------------------ default SMS app

    private fun isDefaultSms(): Boolean {
        // RoleManager is authoritative on Q+ — OxygenOS leaves the legacy getDefaultSmsPackage null
        // even while we hold ROLE_SMS. Fall back to the legacy API only pre-Q.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null) return rm.isRoleHeld(RoleManager.ROLE_SMS)
        }
        return Telephony.Sms.getDefaultSmsPackage(this) == packageName
    }

    /** Ask Android to make MyMessages the default SMS app — RoleManager on Q+, the legacy
     *  ACTION_CHANGE_DEFAULT intent below that. */
    /** SMS message-list / thread font-size slider (Prefs.smsFontSp) — the same setting the pinch-to-zoom on
     *  those screens controls. */
    private fun wireSmsFont() {
        val tv = findViewById<TextView>(R.id.tv_sms_font)
        val sk = findViewById<SeekBar>(R.id.sk_sms_font)
        sk.max = Prefs.SMS_FONT_MAX - Prefs.SMS_FONT_MIN
        sk.progress = (Prefs.smsFontSp(this) - Prefs.SMS_FONT_MIN).coerceIn(0, sk.max)
        tv.text = "${Prefs.smsFontSp(this)} sp"
        sk.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val sp = Prefs.SMS_FONT_MIN + p
                tv.text = "$sp sp"
                if (fromUser) Prefs.setSmsFontSp(this@MainActivity, sp)
            }
        })
    }

    private fun requestDefaultSms() {
        if (isDefaultSms()) {
            toast("Already the default SMS app")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), REQ_DEFAULT_SMS)
                    return
                }
            }
            @Suppress("DEPRECATION")
            val i = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            startActivityForResult(i, REQ_DEFAULT_SMS)
        } catch (e: Exception) {
            toast("Couldn't open default-app chooser: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ notification settings

    private fun wireNotifSettings() {
        swNotif.isChecked = Prefs.notifEnabled(this)
        swNotif.setOnCheckedChangeListener { _, on ->
            Prefs.setNotifEnabled(this, on)
            // Publish overlay state to HA over MQTT (retained) so the Telegram-gate automation
            // knows MyMessages is now the active overlay.
            startService(Intent(this, DashService::class.java)
                .setAction(DashService.ACTION_PUBLISH_NOTIF_STATE))
            if (on && !hasNotifAccess()) openNotifAccess()
        }
        findViewById<Button>(R.id.btn_notif_access).setOnClickListener { openNotifAccess() }

        val skFont = findViewById<SeekBar>(R.id.sk_font)
        skFont.max = Prefs.FONT_MAX_SP - Prefs.FONT_MIN_SP
        skFont.progress = (Prefs.fontSp(this) - Prefs.FONT_MIN_SP).coerceIn(0, skFont.max)
        tvFont.text = "${Prefs.fontSp(this)} sp"
        skFont.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val sp = Prefs.FONT_MIN_SP + p
                tvFont.text = "$sp sp"
                if (fromUser) Prefs.setFontSp(this@MainActivity, sp)
            }
        })

        val skVol = findViewById<SeekBar>(R.id.sk_vol)
        skVol.max = 100
        skVol.progress = Prefs.volume(this)
        tvVol.text = "${Prefs.volume(this)}%"
        skVol.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvVol.text = "$p%"
                if (fromUser) Prefs.setVolume(this@MainActivity, p)
            }
        })

        val skAuto = findViewById<SeekBar>(R.id.sk_autoclose)
        skAuto.max = Prefs.AUTO_CLOSE_MAX
        skAuto.progress = Prefs.autoCloseSecs(this).coerceIn(0, skAuto.max)
        tvAutoClose.text = autoCloseLabel(Prefs.autoCloseSecs(this))
        skAuto.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvAutoClose.text = autoCloseLabel(p)
                if (fromUser) Prefs.setAutoCloseSecs(this@MainActivity, p)
            }
        })

        findViewById<Button>(R.id.btn_sound).setOnClickListener { pickSound() }
        findViewById<Button>(R.id.btn_bg).setOnClickListener {
            pickColor(Prefs.bgColor(this)) { Prefs.setBgColor(this, it); refresh() }
        }
        findViewById<Button>(R.id.btn_fg).setOnClickListener {
            pickColor(Prefs.fgColor(this)) { Prefs.setFgColor(this, it); refresh() }
        }
        findViewById<Button>(R.id.btn_test).setOnClickListener { testPopup() }
        findViewById<Button>(R.id.btn_filter).setOnClickListener {
            startActivity(Intent(this, FilterActivity::class.java))
        }

        val step = 25
        val tvHistMax = findViewById<TextView>(R.id.tv_hist_max)
        val skHist = findViewById<SeekBar>(R.id.sk_hist_max)
        skHist.max = (Prefs.HISTORY_LIMIT_MAX - Prefs.HISTORY_MIN) / step
        skHist.progress = ((Prefs.historyMax(this) - Prefs.HISTORY_MIN) / step).coerceIn(0, skHist.max)
        tvHistMax.text = Prefs.historyMax(this).toString()
        skHist.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = Prefs.HISTORY_MIN + p * step
                tvHistMax.text = v.toString()
                if (fromUser) Prefs.setHistoryMax(this@MainActivity, v)
            }
        })

        btnClear = findViewById(R.id.btn_clear_history)
        btnClear.setOnClickListener {
            val n = Prefs.history(this).size
            AlertDialog.Builder(this)
                .setTitle("Clear history")
                .setMessage("Remove all $n notifications from MyMessages overlay history? This also deletes their saved images and cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    Prefs.clearHistory(this)
                    toast("History cleared")
                    updateClearLabel()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        updateClearLabel()
    }

    private fun updateClearLabel() { btnClear.text = "Clear history (${Prefs.history(this).size})" }

    private fun hasNotifAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openNotifAccess() {
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun hasUsageAccess(): Boolean = try {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        // Google-recommended check: some OEMs (this OnePlus/OxygenOS) leave the appop at MODE_DEFAULT even
        // after the user enables Usage access — then defer to the actual permission grant. Only MODE_ALLOWED
        // / MODE_DEFAULT-with-permission count as granted (else it re-prompted despite being enabled).
        when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> true
            android.app.AppOpsManager.MODE_DEFAULT ->
                checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") == android.content.pm.PackageManager.PERMISSION_GRANTED
            else -> false
        }
    } catch (_: Exception) { false }

    private fun openUsageAccess() {
        try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
    }

    /** Usage access is a special (appop) permission: an app can't self-grant it or grant it via a one-tap
     *  runtime dialog — only the user can enable it on the system screen. So auto-ask once per launch when
     *  the overlay is on and it's missing, and on OK deep-link straight there. Needed so the overlay pops
     *  only over the home screen (not on top of an app you're using) when the phone is unlocked. */
    private var usagePrompted = false
    private fun maybePromptUsageAccess() {
        if (usagePrompted || !Prefs.notifEnabled(this) || hasUsageAccess()) return
        usagePrompted = true
        AlertDialog.Builder(this)
            .setTitle("Allow Usage access?")
            .setMessage("So the notification overlay only pops over the home screen — not on top of an app " +
                "you're using — when the phone is unlocked, MyMessages needs “Usage access”. Android only " +
                "lets you turn this on in Settings.\n\nOpen the Usage-access screen now? (Find MyMessages in the " +
                "list and enable it.)")
            .setPositiveButton("Open settings") { _, _ -> openUsageAccess() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun pickSound() {
        val cur = Prefs.soundUri(this)
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.notif_pick_sound))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (cur.isNotEmpty()) Uri.parse(cur) else null)
        }
        try { startActivityForResult(i, REQ_SOUND) } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SOUND && resultCode == RESULT_OK) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            Prefs.setSoundUri(this, uri?.toString() ?: "")
            refresh()
        } else if (requestCode == REQ_DEFAULT_SMS) {
            refresh()
            toast(if (isDefaultSms()) "MyMessages is now the default SMS app" else "Not set as default SMS app")
        }
    }

    /** HSV colour-wheel picker (no extra library): [ColorWheelView] + a brightness slider + preview. */
    private fun pickColor(initial: Int, onPick: (Int) -> Unit) {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(px(20), px(12), px(20), px(4))
        }
        val preview = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44))
                .also { it.bottomMargin = px(14) }
            setBackgroundColor(initial)
        }
        col.addView(preview)
        val wheel = ColorWheelView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setColor(initial)
        }
        col.addView(wheel)
        val hsv = FloatArray(3); Color.colorToHSV(initial, hsv)
        val brightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = px(12) }
        }
        brightRow.addView(TextView(this).apply { text = "☀"; width = px(28) })
        val bright = SeekBar(this).apply {
            max = 255; progress = (hsv[2] * 255).toInt()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        brightRow.addView(bright); col.addView(brightRow)
        wheel.onColorChanged = { c -> preview.setBackgroundColor(c) }
        bright.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { wheel.setValue(p / 255f) }
        })
        AlertDialog.Builder(this)
            .setView(col)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPick(wheel.getColor()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun testPopup() {
        val payload = JSONObject()
            .put("screen", NotifActivity.SCREEN)
            .put("preview", true)
            .put("app", "MyMessages")
            .put("pkg", packageName)
            .put("category", "msg")
            .put("ts", System.currentTimeMillis())
            .put("title", "Test Sender")
            .put("text", "This is a preview of the notification pop-up so you can tune the font size, colours and sound.")
            .toString()
        startActivity(Intent(this, NotifActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(DashService.EXTRA_PAYLOAD, payload))
    }

    // ------------------------------------------------------------------ dashboard screens

    /** Open a dashboard screen in its own task (they're showWhenLocked; from here we're unlocked). */
    private fun openScreen(cls: Class<*>) =
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    // ------------------------------------------------------------------ service / status

    private fun startDashService() {
        if (!Prefs.fgEnabled(this)) return   // background service turned off from the toggle
        val i = Intent(this, DashService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    // ------------------------------------------------------------------ Trip Tracker tab
    private val TRIP_IV_MIN = 2   // SeekBar progress 0 == this many seconds

    private fun wireTripTab() {
        val sk = findViewById<SeekBar>(R.id.sk_trip_interval)
        val lbl = findViewById<TextView>(R.id.tv_trip_interval)
        fun secs() = sk.progress + TRIP_IV_MIN
        sk.progress = (Prefs.tripInterval(this) - TRIP_IV_MIN).coerceIn(0, sk.max)
        lbl.text = "Send interval: ${secs()} s"
        sk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { lbl.text = "Send interval: ${p + TRIP_IV_MIN} s" }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        findViewById<Button>(R.id.btn_trip_start).setOnClickListener {
            if (!ensureLocationPermission()) return@setOnClickListener
            if (!Prefs.fgEnabled(this)) { Prefs.setFgEnabled(this, true) }   // trips need the persistent service
            val name = findViewById<EditText>(R.id.et_trip_name).text.toString().trim()
            val iv = secs()
            Prefs.setTripInterval(this, iv)
            startService(Intent(this, DashService::class.java)
                .setAction(DashService.ACTION_TRIP_START)
                .putExtra(DashService.EXTRA_PAYLOAD, name)
                .putExtra(DashService.EXTRA_SCREEN, iv.toString()))
            startDashService()   // make sure it's up to receive the action + connect MQTT
            findViewById<EditText>(R.id.et_trip_name).text?.clear()
            refresh()
        }
        findViewById<Button>(R.id.btn_trip_stop).setOnClickListener {
            startService(Intent(this, DashService::class.java).setAction(DashService.ACTION_TRIP_STOP))
            refresh()
        }
    }

    /** Fine+coarse location are enough while DashService (a `location` FGS) is running; background is
     *  requested too on Android 10+ for robustness but not required. Returns true if we already hold it. */
    private fun ensureLocationPermission(): Boolean {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == 0
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == 0
        if (!fine && !coarse) {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 7)
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != 0) {
            // Background is grantable only via Settings on Android 11+; prompt but don't block.
            try { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 8) } catch (_: Exception) {}
        }
        return true
    }

    private fun autoCloseLabel(secs: Int) = if (secs <= 0) "Never" else "${secs}s"

    private fun soundLabel(): String {
        val uriStr = Prefs.soundUri(this)
        if (uriStr.isEmpty()) return getString(R.string.notif_default_sound)
        return try {
            RingtoneManager.getRingtone(this, Uri.parse(uriStr))?.getTitle(this)
                ?: getString(R.string.notif_default_sound)
        } catch (_: Exception) { getString(R.string.notif_default_sound) }
    }

    private fun refresh() {
        swBg.setBackgroundColor(Prefs.bgColor(this))
        swFg.setBackgroundColor(Prefs.fgColor(this))
        tvSound.text = soundLabel()
        tvFont.text = "${Prefs.fontSp(this)} sp"
        tvVol.text = "${Prefs.volume(this)}%"
        tvAutoClose.text = autoCloseLabel(Prefs.autoCloseSecs(this))
        if (::btnClear.isInitialized) updateClearLabel()
        // Keep the SMS-font slider in sync with pinch-zoom changes made on the list/thread screens.
        findViewById<TextView>(R.id.tv_sms_font)?.text = "${Prefs.smsFontSp(this)} sp"
        findViewById<SeekBar>(R.id.sk_sms_font)?.progress =
            (Prefs.smsFontSp(this) - Prefs.SMS_FONT_MIN).coerceIn(0, Prefs.SMS_FONT_MAX - Prefs.SMS_FONT_MIN)

        val overlay = if (Settings.canDrawOverlays(this)) "yes" else "NO"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batt = if (pm.isIgnoringBatteryOptimizations(packageName)) "yes" else "NO"
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == 0).let { if (it) "yes" else "NO" }
        else "yes"
        val sms = if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) == 0) "yes" else "NO"
        val access = if (hasNotifAccess()) "yes" else "NO"
        val usage = if (hasUsageAccess()) "yes" else "NO"
        val popup = if (Prefs.notifEnabled(this)) "on" else "off"
        val default = if (isDefaultSms()) "yes" else "NO"
        val mqtt = if (DashService.connected) "connected" else "…"
        // SMS-tab status
        status.text = """
            MyMessages ${BuildConfig.VERSION_NAME}

            Default SMS app: $default
            SMS permission: $sms
        """.trimIndent()
        // Notifications-tab status
        if (::statusNotif.isInitialized) statusNotif.text = """
            MQTT: $mqtt
            Broker: ${BuildConfig.DEF_BROKER_HOST}:${BuildConfig.DEF_BROKER_PORT}
            Topic: ${BuildConfig.DEF_BASE_TOPIC}/#

            Overlay perm: $overlay
            Battery-unrestricted: $batt
            Notifications: $notif
            Notification access: $access
            Usage access: $usage
            Notification pop-up: $popup
        """.trimIndent()

        // Trip-tab status
        findViewById<TextView>(R.id.tv_trip_status)?.text =
            if (Prefs.tripActive(this))
                "Trip ACTIVE — id ${Prefs.tripId(this)}, every ${Prefs.tripInterval(this)}s\n" +
                "last fix: ${Prefs.tripLastFix(this).ifEmpty { "waiting for GPS…" }}\nMQTT: $mqtt"
            else "No active trip.\nMQTT: $mqtt"
    }

    private fun requestAllPermissions() {
        val want = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) want += Manifest.permission.POST_NOTIFICATIONS
        want += Manifest.permission.RECEIVE_SMS
        want += Manifest.permission.READ_SMS
        want += Manifest.permission.SEND_SMS
        want += Manifest.permission.RECEIVE_MMS
        want += Manifest.permission.READ_CONTACTS
        val ask = want.filter { checkSelfPermission(it) != 0 }.toTypedArray()
        if (ask.isNotEmpty()) requestPermissions(ask, 1)

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        if (!hasNotifAccess()) openNotifAccess()
        if (!hasUsageAccess()) openUsageAccess()
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    companion object {
        private const val REQ_SOUND = 42
        private const val REQ_DEFAULT_SMS = 43
    }
}
