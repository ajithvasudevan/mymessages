package dev.ajithv.mymessages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Self-contained HSV colour-wheel picker (no external library). Angle = hue, distance from centre =
 * saturation; brightness (value) is driven externally by [setValue] (a slider in the dialog).
 * Tap/drag anywhere on the wheel to choose hue+saturation. Emits the composed colour via
 * [onColorChanged]; the current colour is [getColor].
 */
class ColorWheelView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var onColorChanged: ((Int) -> Unit)? = null

    private var hue = 0f          // 0..360
    private var sat = 0f          // 0..1
    private var value = 1f        // 0..1 (brightness)

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val selWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE
    }
    private val selBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK
    }

    private val density = resources.displayMetrics.density
    private val selR = 9f * density

    /** Seed the wheel from an existing colour. */
    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        invalidate()
    }

    /** Brightness (0..1) from the external slider. */
    fun setValue(v: Float) {
        value = v.coerceIn(0f, 1f)
        invalidate()
        onColorChanged?.invoke(getColor())
    }

    fun getColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Square, capped so it fits comfortably in the dialog.
        val ws = MeasureSpec.getSize(widthMeasureSpec)
        val max = (300 * density).toInt()
        val s = ws.coerceAtMost(max).coerceAtLeast(1)
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - selR   // inset so the selector ring never clips
        // Hue around the rim (SweepGradient starts at 3 o'clock, clockwise — matches atan2).
        huePaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
                0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
            ),
            null
        )
        // White centre fading to transparent = saturation falloff.
        satPaint.shader = RadialGradient(
            cx, cy, radius, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, huePaint)
        canvas.drawCircle(cx, cy, radius, satPaint)
        if (value < 1f) {
            darkPaint.alpha = ((1f - value) * 255f).toInt()
            canvas.drawCircle(cx, cy, radius, darkPaint)
        }
        // Selector at (hue angle, sat radius).
        val ang = Math.toRadians(hue.toDouble())
        val dist = sat * radius
        val px = cx + (dist * cos(ang)).toFloat()
        val py = cy + (dist * sin(ang)).toFloat()
        selWhite.strokeWidth = 3f * density
        selBlack.strokeWidth = 1.5f * density
        canvas.drawCircle(px, py, selR, selWhite)
        canvas.drawCircle(px, py, selR - 2f * density, selBlack)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)   // drag isn't stolen by a scroll parent
                val dx = event.x - cx
                val dy = event.y - cy
                val dist = hypot(dx, dy)
                sat = (dist / radius).coerceIn(0f, 1f)
                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (deg < 0) deg += 360f
                hue = deg
                invalidate()
                onColorChanged?.invoke(getColor())
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
