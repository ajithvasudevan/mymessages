package dev.ajithv.mymessages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * "Now Playing" screen. Data is pushed from HA over MQTT (dumb app): the active Music
 * Assistant speaker's title/artist/album, play state, volume and a room picker.
 * Polls `lockdash/request`=music every 2s while visible (answered on lockdash/refresh).
 *
 * Two control channels, both over lockdash/cmd:
 *  - Transport/volume/album/pick: {action:"music", cmd:..., entity_id:<player>, target:<room>}.
 *  - Pick & Play (album/track dropdowns + Play): {action:"pnp", cmd:"select_album|select_track|play",
 *    value:<option>, entity_id:<player>}. HA reuses the SAME backend as the Multimedia dashboard's
 *    Pick & Play card (the music_v2_* input_selects + script.music_v2_dash_play); the album/track
 *    OPTION LISTS and current selections ride back in the music refresh (pnp_albums / pnp_tracks /
 *    pnp_album / pnp_track). There is no speaker dropdown here — the play target is the speaker this
 *    overlay is showing (currentPlayer) / whatever the room chips picked.
 */
class MusicActivity : Activity() {

    companion object {
        const val TAG = "MusicActivity"
        const val SCREEN = "music"
        const val REFRESH_MS = 2000L
        @Volatile var visible = false
    }

    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var speaker: TextView
    private lateinit var vol: TextView
    private lateinit var playPause: ImageButton
    private lateinit var chips: LinearLayout
    private lateinit var prevAlbum: ImageButton
    private lateinit var nextAlbum: ImageButton
    private lateinit var albumSpinner: Spinner
    private lateinit var trackSpinner: Spinner
    private lateinit var btnPlaySel: Button
    private lateinit var searchBox: EditText
    private lateinit var searchClear: TextView
    private lateinit var resultsSpinner: Spinner
    private lateinit var pnpBody: LinearLayout
    private lateinit var pnpArrow: TextView
    private lateinit var seekRow: View
    private lateinit var seekBar: SeekBar
    private lateinit var seekCur: TextView
    private lateinit var seekRem: TextView

    private var currentPlayer = ""
    private var trackDur = 0
    private var trackPos = 0
    private var trackPlaying = false
    private var seekDragging = false

    // Pick & Play dropdown state. `*Opts` mirror the option lists we last rendered so a refresh only
    // rebuilds an adapter when the list actually changed. `*Touched` gate publishing to genuine user
    // picks — a programmatic setSelection (from a refresh) also fires onItemSelected, and we must NOT
    // echo that back to HA (feedback loop). The touch listener flips the flag true just before a real
    // tap opens the dropdown.
    private var albumOpts: List<String> = emptyList()
    private var trackOpts: List<String> = emptyList()
    private var resultsOpts: List<String> = emptyList()
    private var albumTouched = false
    private var trackTouched = false
    private var resultsTouched = false
    private val searchEmpty = listOf("🔎 type to search")
    // All Pick & Play JSON parsing + adapter building runs on this serial worker (off the UI thread);
    // only the final adapter/selection swap is posted back to the UI thread. pnpSeq drops stale results.
    private val pnpExec = Executors.newSingleThreadExecutor()
    private var pnpSeq = 0

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { requestData(); handler.postDelayed(this, REFRESH_MS) }
    }
    // Debounced search-as-you-type: publish the current query (empty => HA clears results), then poll a
    // couple of extra times so the results dropdown refreshes quickly rather than on the next 2s tick.
    private val searchRun = Runnable {
        val q = searchBox.text?.toString()?.trim().orEmpty()
        pnpCmd("search", q)
        handler.postDelayed({ requestData() }, 1200)
        handler.postDelayed({ requestData() }, 2200)
    }
    // 1s ticker that advances the seek bar between the 2s refreshes (which resync it). Paused while the
    // user is dragging the thumb or the track isn't playing.
    private val tick = object : Runnable {
        override fun run() {
            if (trackPlaying && !seekDragging && trackDur > 0 && trackPos < trackDur) {
                trackPos++
                updateSeekUi()
            }
            handler.postDelayed(this, 1000L)
        }
    }
    // No auto-dismiss: the Now Playing screen stays until the user taps ✕ (or HA dismiss).

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
        setContentView(R.layout.activity_music)
        enterImmersive()

        title = findViewById(R.id.m_title)
        sub = findViewById(R.id.m_sub)
        speaker = findViewById(R.id.m_speaker)
        vol = findViewById(R.id.m_vol)
        playPause = findViewById(R.id.btn_playpause)
        chips = findViewById(R.id.chips)

        findViewById<View>(R.id.close).setOnClickListener { finish() }
        playPause.setOnClickListener { musicCmd("playpause") }
        findViewById<View>(R.id.btn_prev).setOnClickListener { musicCmd("prev") }
        findViewById<View>(R.id.btn_next).setOnClickListener { musicCmd("next") }
        prevAlbum = findViewById(R.id.btn_prev_album)
        nextAlbum = findViewById(R.id.btn_next_album)
        prevAlbum.setOnClickListener { musicCmd("prevalbum") }
        nextAlbum.setOnClickListener { musicCmd("nextalbum") }
        findViewById<View>(R.id.btn_voldown).setOnClickListener { musicCmd("voldown") }
        findViewById<View>(R.id.btn_volup).setOnClickListener { musicCmd("volup") }

        albumSpinner = findViewById(R.id.album_spinner)
        trackSpinner = findViewById(R.id.track_spinner)
        btnPlaySel = findViewById(R.id.btn_play_sel)
        wireSpinner(albumSpinner, { albumTouched }, { albumTouched = it }, "select_album")
        wireSpinner(trackSpinner, { trackTouched }, { trackTouched = it }, "select_track")
        btnPlaySel.setOnClickListener { pnpCmd("play") }

        searchBox = findViewById(R.id.search_box)
        searchClear = findViewById(R.id.search_clear)
        searchClear.setOnClickListener { searchBox.setText("") }   // clears -> TextWatcher does the rest
        resultsSpinner = findViewById(R.id.results_spinner)
        wireSpinner(resultsSpinner, { resultsTouched }, { resultsTouched = it }, "pick_result")
        // Search-as-you-type: debounce keystrokes (350ms) into a search; clear the results dropdown
        // instantly when the box is emptied. Enter/Search just commits immediately + hides the keyboard.
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchRun)
                searchClear.visibility = if ((s?.length ?: 0) > 0) View.VISIBLE else View.GONE
                if (s?.toString()?.trim().isNullOrEmpty()) showEmptyResults()   // instant local clear
                handler.postDelayed(searchRun, 350)
            }
        })
        searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                handler.removeCallbacks(searchRun)
                searchRun.run()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else false
        }

        // Collapsible Pick & Play: tap the header to expand/collapse. Starts collapsed (also reset
        // in onResume so it's collapsed every time the overlay becomes visible).
        pnpBody = findViewById(R.id.pnp_body)
        pnpArrow = findViewById(R.id.pnp_arrow)
        findViewById<View>(R.id.pnp_header).setOnClickListener {
            setPnpExpanded(pnpBody.visibility != View.VISIBLE)
        }
        setPnpExpanded(false)

        seekRow = findViewById(R.id.seek_row)
        seekBar = findViewById(R.id.seek_bar)
        seekCur = findViewById(R.id.seek_cur)
        seekRem = findViewById(R.id.seek_rem)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { trackPos = progress; updateTimeLabels() }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { seekDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                seekDragging = false
                trackPos = sb.progress
                if (currentPlayer.isNotEmpty()) musicCmd("seek", pos = sb.progress)
            }
        })

        intent.getStringExtra(DashService.EXTRA_PAYLOAD)?.let { render(it) }
    }

    private fun setPnpExpanded(expanded: Boolean) {
        pnpBody.visibility = if (expanded) View.VISIBLE else View.GONE
        pnpArrow.text = if (expanded) "▼" else "▶"   // down triangle expanded, right triangle collapsed
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(DashService.EXTRA_PAYLOAD)?.let { render(it) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()   // re-hide the bars after they transiently reappear
    }

    /** Truly full-screen: draw edge-to-edge and hide the status + navigation bars (they reappear
     *  transiently on an edge swipe). */
    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * While the Now Playing overlay is showing, the phone's hardware volume rocker controls the
     * ACTIVE SPEAKER's volume instead of the phone's own media/ring volume. Each press is routed
     * through the same musicCmd("volup"/"voldown") path as the on-screen -/+ buttons (HA maps it to
     * script.music_v2_apply_volume, global +/-5%). Fire once per physical press (repeatCount==0) so a
     * held button does not flood MQTT, and consume the paired KEY_UP so the system volume UI never
     * appears. When there is no active session (currentPlayer empty) we DON'T hijack the keys -- fall
     * through so the rocker adjusts phone volume as usual.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (currentPlayer.isEmpty()) return super.onKeyDown(keyCode, event)
                if (event.repeatCount == 0) musicCmd("volup")
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (currentPlayer.isEmpty()) return super.onKeyDown(keyCode, event)
                if (event.repeatCount == 0) musicCmd("voldown")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            && currentPlayer.isNotEmpty()) return true   // swallow so no system volume panel
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply { addAction(DashService.ACTION_UPDATE); addAction(DashService.ACTION_DISMISS) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.post(poll)
        handler.post(tick)
        setPnpExpanded(false)   // collapsed every time the overlay becomes visible
    }

    override fun onPause() {
        super.onPause()
        visible = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        handler.removeCallbacks(poll)
        handler.removeCallbacks(searchRun)
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        pnpExec.shutdownNow()
    }

    private fun requestData() {
        startService(Intent(this, DashService::class.java)
            .setAction(DashService.ACTION_REQUEST)
            .putExtra(DashService.EXTRA_SCREEN, SCREEN))
    }

    /** cmd = playpause|next|prev|volup|voldown|nextalbum|prevalbum|pick|seek; target for pick, pos for seek. */
    private fun musicCmd(cmd: String, target: String? = null, pos: Int? = null) {
        if (target == null && currentPlayer.isEmpty()) return  // transport/volume/seek need a player
        val o = JSONObject().apply {
            put("action", "music")
            put("cmd", cmd)
            put("entity_id", currentPlayer)
            if (target != null) put("target", target)
            if (pos != null) put("pos", pos)
        }
        startService(Intent(this, DashService::class.java)
            .setAction(DashService.ACTION_PUBLISH_CMD)
            .putExtra(DashService.EXTRA_PAYLOAD, o.toString()))
        handler.postDelayed({ requestData() }, 600)   // reflect the change promptly
    }

    /**
     * Pick & Play command: {action:"pnp", cmd, value?, entity_id:<player>}.
     *  - select_album -> HA sets the album dropdown + refetches its tracks (the new track list comes
     *    back in the next refresh),
     *  - select_track -> HA sets the track dropdown,
     *  - play         -> HA maps entity_id to the speaker and presses Play Selection (music_v2_dash_play).
     */
    private fun pnpCmd(cmd: String, value: String? = null) {
        val o = JSONObject().apply {
            put("action", "pnp")
            put("cmd", cmd)
            put("entity_id", currentPlayer)   // play target = the speaker this overlay is showing
            if (value != null) put("value", value)
        }
        startService(Intent(this, DashService::class.java)
            .setAction(DashService.ACTION_PUBLISH_CMD)
            .putExtra(DashService.EXTRA_PAYLOAD, o.toString()))
        handler.postDelayed({ requestData() }, 700)
    }

    private fun render(payload: String) {
        val o = try { JSONObject(payload) } catch (e: Exception) { Log.w(TAG, "bad payload"); return }
        if (o.optString("screen") != SCREEN) return

        currentPlayer = o.optString("player", "")
        val playing = o.optBoolean("playing", false)
        val t = o.optString("title", "")
        // Only say "Nothing playing" when there's genuinely no player. If a speaker is the
        // active session but the track has no title metadata (e.g. Yamaha), don't mislabel it.
        title.text = when {
            t.isNotEmpty() -> t
            currentPlayer.isEmpty() -> "Nothing playing"
            playing -> "Playing…"
            else -> "Paused"
        }
        val artist = o.optString("artist", "")
        val album = o.optString("album", "")
        sub.text = listOf(artist, album).filter { it.isNotEmpty() }.joinToString("  •  ")
        val pname = o.optString("player_name", "")
        speaker.text = if (pname.isNotEmpty()) (if (playing) "▶ $pname" else "❙❙ $pname") else ""
        vol.text = if (currentPlayer.isNotEmpty()) "${o.optInt("volume", 0)}%" else ""
        playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Seek bar: resync from HA (unless the user is mid-drag). The 1s ticker advances it in between.
        trackPlaying = playing
        trackDur = o.optInt("duration", 0)
        if (!seekDragging) trackPos = o.optInt("position", 0)
        if (trackDur > 0) {
            seekRow.visibility = View.VISIBLE
            if (!seekDragging) {
                if (seekBar.max != trackDur) seekBar.max = trackDur
                updateSeekUi()
            }
        } else {
            seekRow.visibility = View.GONE
        }
        renderChips(o.optJSONArray("rooms"))
        renderPickPlay(o)
    }

    /** Populate the album/track dropdowns from the refresh, keeping the current selection in sync
     *  with what HA reports without echoing it back as a user pick. */
    private fun renderPickPlay(o: JSONObject) {
        // Parse the JSON + build the adapters OFF the UI thread (serial worker); only the finished
        // adapter/selection swap runs on the UI thread. Results mirror HA only while the search box has
        // text — an empty box shows the "type to search" placeholder (results cleared).
        val q = searchBox.text?.toString()?.trim().orEmpty()
        val albJson = o.optJSONArray("pnp_albums")
        val trkJson = o.optJSONArray("pnp_tracks")
        val resJson = o.optJSONArray("pnp_results")
        val albumSel = o.optString("pnp_album", "")
        val trackSel = o.optString("pnp_track", "")
        val resultSel = if (q.isEmpty()) "" else o.optString("pnp_result", "")
        val curAlbum = albumOpts; val curTrack = trackOpts; val curResults = resultsOpts
        val seq = ++pnpSeq
        pnpExec.execute {
            val albums = toList(albJson)
            val tracks = toList(trkJson)
            val results = if (q.isEmpty()) searchEmpty else toList(resJson)
            val albAd = if (albums != curAlbum) makeAdapter(albums) else null
            val trkAd = if (tracks != curTrack) makeAdapter(tracks) else null
            val resAd = if (results != curResults) makeAdapter(results) else null
            runOnUiThread {
                if (seq != pnpSeq) return@runOnUiThread     // a newer refresh already superseded this one
                if (albAd != null) { albumOpts = albums; albumSpinner.adapter = albAd }
                if (trkAd != null) { trackOpts = tracks; trackSpinner.adapter = trkAd }
                if (resAd != null) { resultsOpts = results; resultsSpinner.adapter = resAd }
                selectSilently(albumSpinner, albumOpts, albumSel) { albumTouched = false }
                selectSilently(trackSpinner, trackOpts, trackSel) { trackTouched = false }
                selectSilently(resultsSpinner, resultsOpts, resultSel) { resultsTouched = false }
            }
        }
    }

    /** Instant local clear of the results dropdown (UI thread) when the search box is emptied. */
    private fun showEmptyResults() {
        if (resultsOpts != searchEmpty) {
            resultsOpts = searchEmpty
            resultsSpinner.adapter = makeAdapter(searchEmpty)
            resultsTouched = false
        }
    }

    private fun selectSilently(spinner: Spinner, opts: List<String>, sel: String, clearTouch: () -> Unit) {
        val idx = opts.indexOf(sel)
        if (idx >= 0 && spinner.selectedItemPosition != idx) {
            clearTouch()                       // the setSelection below fires onItemSelected -> ignore it
            spinner.setSelection(idx, false)
        }
    }

    private fun wireSpinner(spinner: Spinner, touched: () -> Boolean, setTouched: (Boolean) -> Unit, cmd: String) {
        spinner.setOnTouchListener { v, _ -> setTouched(true); v.performClick(); false }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!touched()) return         // programmatic (refresh) selection -> don't publish
                setTouched(false)
                (parent?.getItemAtPosition(pos) as? String)?.let {
                    if (isPlaceholder(it)) return    // header / status rows are not real picks
                    pnpCmd(cmd, it)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Header / status rows (search prompt, "Searching…", "Loading…", "(no tracks)") are never a pick. */
    private fun isPlaceholder(s: String) =
        s.isEmpty() || s.startsWith("(") || s.startsWith("🔎") || s.startsWith("⏳")

    private fun makeAdapter(items: List<String>): ArrayAdapter<String> {
        val a = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent) as TextView
                v.setTextColor(ContextCompat.getColor(this@MusicActivity, R.color.text_light))
                v.textSize = 16f
                return v
            }
            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(pos, cv, parent) as TextView
                v.setTextColor(ContextCompat.getColor(this@MusicActivity, R.color.text_light))
                v.setBackgroundColor(0xFF1B1B20.toInt())
                v.textSize = 16f
                v.setPadding(dp(16), dp(12), dp(16), dp(12))
                return v
            }
        }
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return a
    }

    private fun toList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val l = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) l.add(arr.optString(i))
        return l
    }

    private fun renderChips(arr: org.json.JSONArray?) {
        chips.removeAllViews()
        if (arr == null) return
        // Row hugs the screen edges (3dp side margins); the width reclaimed from the old big
        // side margins is spent on wider inter-chip gaps. Chips are 1.5x taller than before
        // (56 -> 84dp) and each label is drawn on a -45° diagonal so a long name like "Workshop"
        // runs along the tall chip diagonal instead of truncating across the narrow width.
        val h = dp(84); val gap = dp(18); val side = dp(3)
        // Let a rotated label overflow its chip (into the widened gaps) rather than be clipped.
        chips.clipChildren = false
        chips.clipToPadding = false
        val last = arr.length() - 1
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val id = r.optString("id"); val name = r.optString("name"); val active = r.optBoolean("active")
            val chip = FrameLayout(this).apply {
                clipChildren = false
                setBackgroundResource(if (active) R.drawable.chip_active else R.drawable.chip_inactive)
                setOnClickListener { musicCmd("pick", id) }
            }
            val label = TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setSingleLine(true)
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(this@MusicActivity,
                    if (active) R.color.white else R.color.text_light))
                textSize = 15f
                rotation = -45f   // diagonal (lower-left -> upper-right)
            }
            // Fixed generous width => the label is measured at full length (never truncated),
            // then centered + rotated inside the chip; clipChildren=false shows the overflow.
            val labelLp = FrameLayout.LayoutParams(dp(100), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            chip.addView(label, labelLp)
            // width 0 + weight 1 => chips fill the row evenly; 3dp at the ends, wide gaps between.
            val lp = LinearLayout.LayoutParams(0, h, 1f).apply {
                marginStart = if (i == 0) side else gap
                if (i == last) marginEnd = side
            }
            chips.addView(chip, lp)
        }
    }

    private fun updateSeekUi() {
        if (!seekDragging) seekBar.progress = trackPos.coerceIn(0, if (seekBar.max > 0) seekBar.max else trackPos)
        updateTimeLabels()
    }

    private fun updateTimeLabels() {
        val cur = if (seekDragging) seekBar.progress else trackPos
        seekCur.text = fmtTime(cur)
        seekRem.text = "-" + fmtTime((trackDur - cur).coerceAtLeast(0))
    }

    private fun fmtTime(s: Int): String = "%d:%02d".format(s / 60, s % 60)

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
