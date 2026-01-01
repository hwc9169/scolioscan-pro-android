package com.nextvinetech.scolioscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 척추측만계 게이지 뷰
 * -30° ~ +30° 범위의 각도를 표시하는 아크형 게이지
 */
class ScoliometerGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BRAND_COLOR = 0xFF359296.toInt()
    }

    private var angleDeg: Float = 0f
    private var peakAbs: Float = 0f

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E8EEF2")
        strokeCap = Paint.Cap.ROUND
    }

    private val innerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#DCE6EA")
        strokeCap = Paint.Cap.ROUND
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(20, 0, 0, 0)
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textAlign = Paint.Align.CENTER
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BRAND_COLOR
    }

    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = darken(BRAND_COLOR, 0.20f)
        strokeWidth = 2f
    }

    private val bubbleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(51, 0, 0, 0)
    }

    private val notchFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val notchStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = darken(BRAND_COLOR, 0.25f)
        strokeWidth = 1.2f
        strokeJoin = Paint.Join.ROUND
    }

    fun setAngle(angle: Float) {
        angleDeg = angle.coerceIn(-30f, 30f)
        invalidate()
    }

    fun setPeak(peak: Float) {
        peakAbs = peak.coerceIn(0f, 30f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val scale = 1f

        // Arc geometry
        val chordBase = w * 0.78f
        val chord = min(chordBase * scale, w * 0.92f)
        val arcSagittaPx = h * 0.20f * scale.coerceIn(0.9f, 1.2f)
        val halfChord = chord / 2f
        val R = (arcSagittaPx / 2f) + (chord * chord) / (8f * arcSagittaPx)

        val liftClamped = 0.14f.coerceIn(0f, 0.40f)
        val midY = h * (0.88f - liftClamped)
        val centerY = midY - (R - arcSagittaPx)
        val centerX = w * 0.5f

        val phi = atan((R - arcSagittaPx) / halfChord)
        val startA = Math.PI.toFloat() - phi
        val endA = phi
        val sweep = endA - startA

        // Track widths
        val trackW = h * 0.22f * scale
        val innerW = trackW * 0.55f

        // Draw shadow
        shadowPaint.strokeWidth = trackW * 0.56f
        val arcRect = RectF(centerX - R, centerY - R, centerX + R, centerY + R)
        canvas.drawArc(arcRect, Math.toDegrees(startA.toDouble()).toFloat(),
            Math.toDegrees(sweep.toDouble()).toFloat(), false, shadowPaint)

        // Draw outer track
        trackPaint.strokeWidth = trackW
        canvas.drawArc(arcRect, Math.toDegrees(startA.toDouble()).toFloat(),
            Math.toDegrees(sweep.toDouble()).toFloat(), false, trackPaint)

        // Draw inner track
        innerTrackPaint.strokeWidth = innerW
        canvas.drawArc(arcRect, Math.toDegrees(startA.toDouble()).toFloat(),
            Math.toDegrees(sweep.toDouble()).toFloat(), false, innerTrackPaint)

        // Draw ticks and labels
        val tickBaseR = R - (trackW * 0.56f)
        for (i in -30..30 step 5) {
            val t = (i + 30) / 60f
            val a = startA + sweep * t
            val dirX = cos(a)
            val dirY = sin(a)
            val baseX = centerX + dirX * tickBaseR
            val baseY = centerY + dirY * tickBaseR

            val isMajor = i % 10 == 0
            val len = if (isMajor) h * 0.052f * scale else h * 0.030f * scale
            val endX = baseX + dirX * len
            val endY = baseY + dirY * len

            tickPaint.strokeWidth = if (isMajor) 2.2f else 1.4f
            canvas.drawLine(baseX, baseY, endX, endY, tickPaint)

            if (isMajor) {
                val labelGap = h * 0.060f * scale
                val labelX = endX + dirX * labelGap
                val labelY = endY + dirY * labelGap
                val label = if (i == 0) "0" else kotlin.math.abs(i).toString()

                textPaint.textSize = h * 0.080f * scale
                textPaint.isFakeBoldText = true
                canvas.drawText(label, labelX, labelY + textPaint.textSize / 3f, textPaint)
            }
        }

        // Draw center notch
        val aMid = startA + sweep * 0.5f
        val dirMidX = cos(aMid)
        val dirMidY = sin(aMid)
        val dirOutX = dirMidX
        val dirOutY = dirMidY
        val outerR = R + trackW * 0.50f
        val gap = trackW * 0.10f
        val baseNotchX = centerX + dirOutX * (outerR + gap)
        val baseNotchY = centerY + dirOutY * (outerR + gap)
        val halfW = trackW * 0.35f
        val depth = trackW * 0.45f
        val tangentX = -dirOutY
        val tangentY = dirOutX

        val pLX = baseNotchX - tangentX * halfW
        val pLY = baseNotchY - tangentY * halfW
        val pRX = baseNotchX + tangentX * halfW
        val pRY = baseNotchY + tangentY * halfW
        val pTipX = baseNotchX + dirOutX * depth
        val pTipY = baseNotchY + dirOutY * depth

        val notchPath = Path().apply {
            moveTo(pLX, pLY)
            lineTo(pTipX, pTipY)
            lineTo(pRX, pRY)
            close()
        }

        canvas.drawPath(notchPath, notchFillPaint)
        canvas.drawPath(notchPath, notchStrokePaint)

        // Draw bubble
        val clamped = angleDeg.coerceIn(-30f, 30f)
        val tVal = (clamped + 30f) / 60f
        val aVal = startA + sweep * tVal
        val dirBubbleX = cos(aVal)
        val dirBubbleY = sin(aVal)
        val bubbleCenterX = centerX + dirBubbleX * (R - trackW * 0.10f)
        val bubbleCenterY = centerY + dirBubbleY * (R - trackW * 0.10f)
        val bubbleRadius = trackW * 0.26f

        // Bubble shadow
        canvas.drawCircle(bubbleCenterX, bubbleCenterY - 2f, bubbleRadius, bubbleShadowPaint)
        // Bubble fill
        canvas.drawCircle(bubbleCenterX, bubbleCenterY, bubbleRadius, bubblePaint)
        // Bubble stroke
        canvas.drawCircle(bubbleCenterX, bubbleCenterY, bubbleRadius, bubbleStrokePaint)
    }

    private fun darken(color: Int, amount: Float): Int {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        val newR = (r * (1 - amount)).coerceIn(0f, 1f)
        val newG = (g * (1 - amount)).coerceIn(0f, 1f)
        val newB = (b * (1 - amount)).coerceIn(0f, 1f)

        return Color.rgb((newR * 255).toInt(), (newG * 255).toInt(), (newB * 255).toInt())
    }
}
