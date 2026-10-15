package dev.ajithv.mymessages

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import kotlin.math.abs

/**
 * Full-screen viewer for a notification's image(s), tapped in [NotifActivity]. The image is scaled to
 * fit the screen on black, touching the display edges in one dimension with no crop, and re-fits on
 * rotation. It is matrix-driven so it supports **pinch-to-zoom** (fit .. 5×), **drag-to-pan** while
 * zoomed, and **double-tap** to toggle between fit and a 3× zoom (so a double-tap always brings a
 * zoomed image back to fit). For an album (several images) an **interactive drag** at fit scale —
 * left/right OR up/down — moves the current image AND the revealed neighbour with the finger; on
 * release it commits to that neighbour only if dragged past 20% of the view, otherwise it springs back
 * to the current image. Closing is deliberately restricted to the ✕ chip or the Android Back button
 * (NOT a tap on the image). Shown over the lock screen.
 */
class ImageViewActivity : Activity() {

    companion object {
        const val EXTRA_PATHS = "paths"   // ArrayList<String> of image file paths
        const val EXTRA_START = "start"   // index to open first
        private const val MAX_ZOOM = 5f   // max scale as a multiple of the fit scale
        private const val DOUBLE_TAP_ZOOM = 3f
        private const val COMMIT_FRACTION = 0.2f   // drag >= 20% of the view to move to the next/prev image
        private const val SETTLE_MS = 200L         // spring-to-rest animation after release
    }

    private lateinit var paths: List<String>
    private var idx = 0
    private lateinit var iv: ImageView
    private lateinit var counter: TextView

    // Matrix state. `m` is the live image transform; `fitMatrix`/`fitScale` are the fit-to-screen
    // baseline recomputed per image + per view size. Zoom is clamped to [fitScale, fitScale*MAX_ZOOM].
    private val m = Matrix()
    private val fitMatrix = Matrix()
    private var fitScale = 1f
    private var maxScale = 5f
    private var bmpW = 0
    private var bmpH = 0

    private lateinit var root: FrameLayout
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gesture: GestureDetector
    private var lastTapMs = 0L   // for the manual (lenient) double-tap fallback
    private var sliding = false   // a settle animation is running (block new gestures)

    // Interactive page-drag state (finger-following): the current image + the revealed neighbour follow
    // the finger; on release we commit to the neighbour if dragged >= COMMIT_FRACTION, else spring back.
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var dragAxis = 0        // 0 none, 1 horizontal, 2 vertical (locked on first significant move)
    private var dragging = false
    private var incoming: ImageView? = null
    private var incomingIdx = -1
    private var incomingBmp: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        paths = (intent?.getStringArrayListExtra(EXTRA_PATHS)
            ?: intent?.getStringExtra("path")?.let { arrayListOf(it) }   // single-path back-compat
            ?: arrayListOf())
            .filter { it.isNotEmpty() && File(it).exists() }
        if (paths.isEmpty()) { finish(); return }
        idx = (intent?.getIntExtra(EXTRA_START, 0) ?: 0).coerceIn(0, paths.lastIndex)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.MATRIX   // we drive the transform ourselves (zoom/pan/fit)
        }
        root.addView(iv)

        // Re-fit whenever the view's SIZE changes — the initial layout AND every rotation. Doing it from
        // the layout callback (when the new bounds are already applied) is reliable; posting resetFit()
        // from onConfigurationChanged could run before the re-layout and fit to the stale pre-rotation
        // size (image left off-centre / mis-scaled). Pan/zoom change only the matrix, not the view bounds,
        // so they never trigger a re-fit.
        iv.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if ((r - l) != (orr - ol) || (b - t) != (ob - ot)) resetFit()
        }

        val close = TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(dp(18), dp(18), dp(18), dp(18)); setBackgroundColor(0x55000000)
            setOnClickListener { finish() }   // the ✕ is the ONLY on-screen close (plus Back)
        }
        root.addView(close, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END))

        counter = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(16), dp(10), dp(16), dp(10)); setBackgroundColor(0x55000000)
        }
        root.addView(counter, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (sliding) return false
                val cur = curScale()
                val target = (cur * d.scaleFactor).coerceIn(fitScale, maxScale)
                val factor = if (cur == 0f) 1f else target / cur
                m.postScale(factor, factor, d.focusX, d.focusY)
                fixBounds(); iv.imageMatrix = m
                return true
            }
        })

        gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // Must return true so the detector keeps receiving the rest of the stream (scroll/fling/
            // double-tap all rely on this).
            override fun onDown(e: MotionEvent): Boolean = true
            // A single tap does NOT close (deliberate) — but a slow double tap that the detector's own
            // 300 ms window misses is caught here with a lenient manual 500 ms window. Safe because a
            // lone tap is a no-op, so a generous double-tap window has no downside.
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val now = System.currentTimeMillis()
                if (now - lastTapMs < 500L) { lastTapMs = 0L; toggleZoom(e.x, e.y) }
                else lastTapMs = now
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { lastTapMs = 0L; toggleZoom(e.x, e.y); return true }
            // Pan (zoomed) and page-drag (at fit) are handled directly in [handleTouch], not here, so the
            // image follows the finger continuously.
        })

        root.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gesture.onTouchEvent(ev)
            handleTouch(ev)
            true
        }
        root.isClickable = true

        setContentView(root)
        show(idx)
    }

    private fun show(i: Int) {
        if (i < 0 || i > paths.lastIndex) return   // clamp at the ends
        idx = i
        val bmp = decode(paths[idx]) ?: return
        bmpW = bmp.width; bmpH = bmp.height
        iv.setImageBitmap(bmp)
        // Fit once the view has a measured size (first image may arrive before layout).
        if (iv.width == 0 || iv.height == 0) iv.post { resetFit() } else resetFit()
        counter.text = "${idx + 1}/${paths.size}"
        counter.visibility = if (paths.size > 1) View.VISIBLE else View.GONE
    }

    /** Recompute the fit-to-screen matrix for the current image + view size and snap to it. */
    private fun resetFit() {
        val vw = iv.width.toFloat(); val vh = iv.height.toFloat()
        if (vw <= 0f || vh <= 0f || bmpW <= 0 || bmpH <= 0) return
        val s = minOf(vw / bmpW, vh / bmpH)
        fitMatrix.reset()
        fitMatrix.setScale(s, s)
        fitMatrix.postTranslate((vw - bmpW * s) / 2f, (vh - bmpH * s) / 2f)
        fitScale = s; maxScale = s * MAX_ZOOM
        m.set(fitMatrix); iv.imageMatrix = m
    }

    /**
     * Single-finger touch handling: pan when zoomed, otherwise an interactive page-drag at fit scale.
     * The current image and the revealed neighbour translate WITH the finger; the axis is locked on the
     * first significant move. [ScaleGestureDetector] (pinch) and [gesture] (tap/double-tap) run in
     * parallel; we bow out here while a pinch is in progress or a settle animation is running.
     */
    private fun handleTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y; dragAxis = 0; dragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) revertPageDragNow()   // a second finger -> pinch takes over
                dragging = false; dragAxis = 0
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // A finger lifted (pinch ending) — resync the pan anchor to the remaining pointer so
                // the next move doesn't jump.
                val remain = if (ev.actionIndex == 0) 1 else 0
                if (remain < ev.pointerCount) { lastX = ev.getX(remain); lastY = ev.getY(remain) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (sliding) return
                if (scaleDetector.isInProgress || ev.pointerCount > 1) { lastX = ev.x; lastY = ev.y; return }
                if (curScale() > fitScale * 1.01f) {   // zoomed -> pan incrementally
                    m.postTranslate(ev.x - lastX, ev.y - lastY); fixBounds(); iv.imageMatrix = m
                    lastX = ev.x; lastY = ev.y
                    return
                }
                val tX = ev.x - downX; val tY = ev.y - downY   // at fit -> page-drag
                if (dragAxis == 0) {
                    val slop = dp(8).toFloat()
                    if (abs(tX) < slop && abs(tY) < slop) return
                    dragAxis = if (abs(tX) >= abs(tY)) 1 else 2
                    dragging = true
                }
                updatePageDrag(if (dragAxis == 1) tX else tY, dragAxis == 1)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) finishPageDrag(if (dragAxis == 1) ev.x - downX else ev.y - downY, dragAxis == 1)
                dragging = false; dragAxis = 0
            }
        }
    }

    /** During a page-drag: move the current image by `offset` and reveal the neighbour it uncovers
     *  (rubber-banded when there is no neighbour at that end). */
    private fun updatePageDrag(offset: Float, horizontal: Boolean) {
        val size = (if (horizontal) iv.width else iv.height).toFloat()
        if (size <= 0f) return
        val wantNext = offset < 0
        val nIdx = if (wantNext) idx + 1 else idx - 1
        val hasN = nIdx in 0..paths.lastIndex
        val eff = if (hasN) offset else offset * 0.35f   // rubber-band past the ends
        if (horizontal) { iv.translationX = eff; iv.translationY = 0f } else { iv.translationY = eff; iv.translationX = 0f }
        if (!hasN) { removeIncoming(); return }
        if (incoming == null || incomingIdx != nIdx) {
            removeIncoming()
            val bmp = decode(paths[nIdx]) ?: return
            incomingBmp = bmp
            incoming = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bmp)
            }
            incomingIdx = nIdx
            root.addView(incoming, 1)   // above the main image, below the ✕ / counter chrome
        }
        val base = if (wantNext) size else -size
        incoming?.let {
            if (horizontal) { it.translationX = base + eff; it.translationY = 0f }
            else { it.translationY = base + eff; it.translationX = 0f }
        }
    }

    /** On release: commit to the neighbour if dragged past COMMIT_FRACTION of the view, else spring back
     *  to the current image. Both cases animate to their resting position. */
    private fun finishPageDrag(offset: Float, horizontal: Boolean) {
        val size = (if (horizontal) iv.width else iv.height).toFloat()
        val wantNext = offset < 0
        val nIdx = if (wantNext) idx + 1 else idx - 1
        val hasN = nIdx in 0..paths.lastIndex
        val commit = hasN && abs(offset) >= size * COMMIT_FRACTION && incoming != null && incomingIdx == nIdx
        sliding = true
        if (commit) {
            val curTarget = if (wantNext) -size else size
            val inc = incoming!!; val bmp = incomingBmp
            iv.animate().apply { if (horizontal) translationX(curTarget) else translationY(curTarget) }
                .setDuration(SETTLE_MS).start()
            inc.animate().apply { if (horizontal) translationX(0f) else translationY(0f) }
                .setDuration(SETTLE_MS)
                .withEndAction {
                    idx = nIdx
                    if (bmp != null) { bmpW = bmp.width; bmpH = bmp.height; iv.setImageBitmap(bmp) }
                    iv.translationX = 0f; iv.translationY = 0f
                    resetFit()
                    removeIncoming()
                    counter.text = "${idx + 1}/${paths.size}"
                    counter.visibility = if (paths.size > 1) View.VISIBLE else View.GONE
                    sliding = false
                }.start()
        } else {
            iv.animate().apply { if (horizontal) translationX(0f) else translationY(0f) }
                .setDuration(SETTLE_MS)
                .withEndAction { iv.translationX = 0f; iv.translationY = 0f; removeIncoming(); sliding = false }
                .start()
            incoming?.let {
                val base = if (wantNext) size else -size
                it.animate().apply { if (horizontal) translationX(base) else translationY(base) }.setDuration(SETTLE_MS).start()
            }
        }
    }

    private fun revertPageDragNow() {
        iv.translationX = 0f; iv.translationY = 0f; removeIncoming()
    }

    private fun removeIncoming() {
        incoming?.let { root.removeView(it) }
        incoming = null; incomingIdx = -1; incomingBmp = null
    }

    /** Double-tap behaviour: if zoomed, snap back to fit; else zoom to DOUBLE_TAP_ZOOM at (x,y). */
    private fun toggleZoom(x: Float, y: Float) {
        if (sliding) return
        if (curScale() > fitScale * 1.05f) {
            m.set(fitMatrix)
        } else {
            val target = (fitScale * DOUBLE_TAP_ZOOM).coerceAtMost(maxScale)
            val factor = target / curScale()
            m.postScale(factor, factor, x, y)
            fixBounds()
        }
        iv.imageMatrix = m
    }

    private fun curScale(): Float {
        val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X]
    }

    /** Keep the image within the viewport: clamp pan when it overflows, centre the axis when it fits. */
    private fun fixBounds() {
        val v = FloatArray(9); m.getValues(v)
        val scale = v[Matrix.MSCALE_X]
        val tx = v[Matrix.MTRANS_X]; val ty = v[Matrix.MTRANS_Y]
        val vw = iv.width.toFloat(); val vh = iv.height.toFloat()
        val w = bmpW * scale; val h = bmpH * scale
        val nx = if (w <= vw) (vw - w) / 2f else tx.coerceIn(vw - w, 0f)
        val ny = if (h <= vh) (vh - h) / 2f else ty.coerceIn(vh - h, 0f)
        m.postTranslate(nx - tx, ny - ty)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // The re-fit happens in iv's OnLayoutChangeListener once the new-orientation bounds are applied
        // (see onCreate) — reliable, unlike re-fitting here against the not-yet-relaid-out view size.
    }

    /** Decode downsampled to roughly the larger screen dimension (avoids OOM; the matrix scales it). */
    private fun decode(path: String): Bitmap? = try {
        val dm = resources.displayMetrics
        val req = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / s > req * 2) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Exception) { null }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); goImmersive() }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }
}
