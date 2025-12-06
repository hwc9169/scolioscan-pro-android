package com.nextvinetech.scolioscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.graphics.withSave
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

class PoseGuideline @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : View(context, attrs) {

  private var outerGuidelineN: RectF? = null
  private var innerGuidelineN: RectF? = null
  private var outerGuidelinePx: RectF? = null
  private var innerGuidelinePx: RectF? = null

  // Measurement completion callback
  interface OnMeasurementCompleteListener {
    fun onMeasurementComplete(result: MeasurementResult)
  }

  // UI state update callback
  interface OnStateUpdateListener {
    fun onStateUpdate(isOk: Boolean, remainingSeconds: Int, statusMessage: String)
  }

  data class MeasurementResult(
    val mainThoracic: Double,
    val secondThoracic: Double?,
    val lumbar: Double,
    val score: Double?
  )

  private var measurementListener: OnMeasurementCompleteListener? = null
  private var stateUpdateListener: OnStateUpdateListener? = null
  private var okStartTime: Long = 0
  private var isMeasuring = false
  private var measurementCompleted = false
  private val mainHandler = Handler(Looper.getMainLooper())

  // Time required to hold position for measurement (milliseconds)
  private val requiredHoldTime = 3000L

  fun setOnMeasurementCompleteListener(listener: OnMeasurementCompleteListener?) {
    this.measurementListener = listener
  }

  fun setOnStateUpdateListener(listener: OnStateUpdateListener?) {
    this.stateUpdateListener = listener
  }

  /** Reset measurement state to allow new measurement */
  fun resetMeasurement() {
    measurementCompleted = false
    isMeasuring = false
    okStartTime = 0
  }

  private fun centeredSymmetric01(src: RectF): RectF {
    val w = (src.right - src.left).coerceIn(0f, 1f)
    val h = (src.bottom - src.top).coerceIn(0f, 1f)
    val halfW = w / 2f
    val halfH = h / 2f
    // Center at (0.5, 0.5)
    val cx = 0.5f
    val cy = 0.5f
    return RectF(
      (cx - halfW).coerceAtLeast(0f),
      (cy - halfH).coerceAtLeast(0f),
      (cx + halfW).coerceAtMost(1f),
      (cy + halfH).coerceAtMost(1f)
    )
  }

  fun setGuidelineNormalized(outerN: RectF, innerN: RectF) {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)

    val outerNC = centeredSymmetric01(outerN)
    val innerNC = centeredSymmetric01(innerN)

    require(outerNC.contains(innerNC))

    outerGuidelineN = outerNC
    innerGuidelineN = innerNC

    outerGuidelinePx = RectF(outerNC.left * w, outerNC.top * h, outerNC.right * w, outerNC.bottom * h)
    innerGuidelinePx = RectF(innerNC.left * w, innerNC.top * h, innerNC.right * w, innerNC.bottom * h)

    postInvalidateOnAnimation()
  }

  data class GuideResult(
    val ok: Boolean,
    val shouldersInOuter: Boolean,
    val shouldersOutOfInner: Boolean,
    val fromBehind: Boolean,
  )

  private var lastGuideResult: GuideResult? = null

  // Latest landmarks (first detected person)
  private val latestLandmarks = AtomicReference<List<NormalizedLandmark>?>(null)

  // Optional: flip horizontally for front camera mirroring
  @Volatile private var mirror = false

  // Styling
  private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    strokeWidth = 6f
  }
  private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 4f
  }
  private val outerGuidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.argb(200, 255, 255, 255)
    strokeWidth = 3f
  }
  private val innerGuidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.argb(200, 255, 0, 0)
    strokeWidth = 3f
  }
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = 16f * resources.displayMetrics.scaledDensity
  }

  // Body guideline paint
  private val bodyGuidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.argb(136, 136, 136, 136) // #88888888
    strokeWidth = 12f
  }

  // Guideline state colors
  private var isGuidelineOk = false

  /** Update guideline color state */
  fun setGuidelineOk(ok: Boolean) {
    isGuidelineOk = ok
    bodyGuidelinePaint.color = if (ok) {
      Color.argb(255, 76, 175, 80) // #FF4CAF50 green
    } else {
      Color.argb(136, 136, 136, 136) // #88888888 gray
    }
    postInvalidateOnAnimation()
  }

  /** Call to mirror horizontally (use true for front camera preview). */
  fun setMirrorHorizontally(mirror: Boolean) {
    this.mirror = mirror
    postInvalidateOnAnimation()
  }

  /** Update from analyzer thread. Safe to call off the UI thread. */
  fun update(result: PoseLandmarkerResult) {
    // We'll visualize only the first pose for simplicity.
    val poses = result.landmarks()
    latestLandmarks.set(poses.firstOrNull())
    lastGuideResult = algorithm()
    lastGuideResult?.let { guide ->
      Log.d(
        "PoseOverlay", "GuideResult: ok=${guide.ok}, fromBehind=${guide.fromBehind}, " +
          "shouldersInOuter=${guide.shouldersInOuter}, " +
          "shouldersOutOfInner=${guide.shouldersOutOfInner}"
      )

      // Check for measurement completion
      checkMeasurementCompletion(guide)
    }
    postInvalidateOnAnimation()
  }

  private fun checkMeasurementCompletion(guide: GuideResult) {
    if (measurementCompleted) return

    val currentTime = System.currentTimeMillis()

    if (guide.ok) {
      // Update guideline color to green
      mainHandler.post { setGuidelineOk(true) }

      if (!isMeasuring) {
        // Start measuring
        isMeasuring = true
        okStartTime = currentTime
        Log.d("PoseGuideline", "Started measuring - hold position for ${requiredHoldTime}ms")

        // Notify UI: position OK, countdown starting
        val remainingSeconds = (requiredHoldTime / 1000).toInt()
        mainHandler.post {
          stateUpdateListener?.onStateUpdate(true, remainingSeconds, "자세를 유지하세요")
        }
      } else {
        // Check if held long enough
        val elapsedTime = currentTime - okStartTime
        val remainingTime = requiredHoldTime - elapsedTime
        val remainingSeconds = ((remainingTime + 999) / 1000).toInt().coerceAtLeast(0)
        Log.d("PoseGuideline", "Holding position: ${elapsedTime}ms / ${requiredHoldTime}ms")

        // Update countdown
        mainHandler.post {
          stateUpdateListener?.onStateUpdate(true, remainingSeconds, "자세를 유지하세요")
        }

        if (elapsedTime >= requiredHoldTime) {
          // Measurement complete!
          measurementCompleted = true
          isMeasuring = false

          val measurementResult = calculateSpineAngles()
          Log.d("PoseGuideline", "Measurement complete: $measurementResult")

          mainHandler.post {
            stateUpdateListener?.onStateUpdate(true, 0, "측정 완료!")
            measurementListener?.onMeasurementComplete(measurementResult)
          }
        }
      }
    } else {
      // Update guideline color to gray
      mainHandler.post { setGuidelineOk(false) }

      // Position lost - reset timer
      if (isMeasuring) {
        Log.d("PoseGuideline", "Position lost - resetting measurement")
        isMeasuring = false
        okStartTime = 0
      }

      // Notify UI: position not OK
      val statusMessage = when {
        !guide.fromBehind -> "뒤를 보고 서세요"
        !guide.shouldersInOuter -> "가이드라인 안으로 들어오세요"
        !guide.shouldersOutOfInner -> "조금 뒤로 오세요"
        else -> "가이드라인에 맞춰 서세요"
      }
      mainHandler.post {
        stateUpdateListener?.onStateUpdate(false, 0, statusMessage)
      }
    }
  }

  /**
   * Calculate spine angles from pose landmarks.
   * This is a simplified estimation based on shoulder and hip positions.
   * For accurate medical measurements, a proper algorithm is required.
   */
  private fun calculateSpineAngles(): MeasurementResult {
    val lm = latestLandmarks.get()

    if (lm == null) {
      // Return default values if no landmarks
      return MeasurementResult(
        mainThoracic = 0.0,
        secondThoracic = null,
        lumbar = 0.0,
        score = null
      )
    }

    // Get key spine-related landmarks
    // 11: Left shoulder, 12: Right shoulder
    // 23: Left hip, 24: Right hip
    val leftShoulder = lm.safeGet(11)
    val rightShoulder = lm.safeGet(12)
    val leftHip = lm.safeGet(23)
    val rightHip = lm.safeGet(24)

    // Calculate shoulder tilt angle (approximation for thoracic curve)
    val shoulderAngle = if (leftShoulder != null && rightShoulder != null) {
      val dx = rightShoulder.x() - leftShoulder.x()
      val dy = rightShoulder.y() - leftShoulder.y()
      Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
    } else 0.0

    // Calculate hip tilt angle (approximation for lumbar curve)
    val hipAngle = if (leftHip != null && rightHip != null) {
      val dx = rightHip.x() - leftHip.x()
      val dy = rightHip.y() - leftHip.y()
      Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
    } else 0.0

    // These are simplified estimations
    // Main thoracic: based on shoulder alignment
    val mainThoracic = abs(shoulderAngle)

    // Lumbar: based on hip alignment relative to shoulders
    val lumbar = abs(hipAngle - shoulderAngle)

    // Score: inverse of total deviation (higher is better, max 100)
    val totalDeviation = mainThoracic + lumbar
    val score = (100.0 - totalDeviation.coerceIn(0.0, 100.0)).coerceIn(0.0, 100.0)

    return MeasurementResult(
      mainThoracic = mainThoracic,
      secondThoracic = null, // Would need more complex calculation
      lumbar = lumbar,
      score = score
    )
  }


  fun algorithm(): GuideResult? {
    val lm = latestLandmarks.get() ?: return null
    val outer = outerGuidelineN
    val inner = innerGuidelineN

    if (outer == null || inner == null) {
      return null
    }

    fun normRotated(i: Int): Pair<Float, Float>? {
      val p = lm.safeGet(i) ?: return null
      return rotateNormalize(p.x(), p.y())
    }
    /** BACK if subject's left shoulder (idx 11) appears to the RIGHT of right shoulder (idx 12). */
    fun isBehind(deltaThreshold: Float = 0.02f, log: Boolean = true): Boolean {
      val lm = latestLandmarks.get() ?: return false
      // get rotated-normalized X for L/R shoulders
      val l = lm.safeGet(11)?.let { rotateNormalize(it.x(), it.y()) }  // left shoulder
      val r = lm.safeGet(12)?.let { rotateNormalize(it.x(), it.y()) }  // right shoulder
      if (l == null || r == null) return false

      val leftX  = l.first  // normalized [0..1] after rotation/mirror
      val rightX = r.first

      // If left shoulder is to the right of right shoulder by a margin → BACK
      val isBack = (rightX - leftX) > deltaThreshold

      if (log) {
        Log.d(
          "PoseGuideline",
          "ShoulderX check: leftX=%.3f rightX=%.3f Δ=%.3f -> isBack=%s"
            .format(leftX, rightX, (leftX - rightX), isBack)
        )
      }
      return isBack
    }

    fun Pair<Float, Float>.inside(r: RectF): Boolean =
      first in r.left..r.right && second in r.top..r.bottom

    // 뒷모습인지 확인
    val behind = isBehind(0.02f)

    // 두 어깨 모두 outer 박스 안에 있고, 동시에 inner 박스 밖이어야 함
    val lShoulder = normRotated(11)
    val rShoulder = normRotated(12)

    val lInOuter = lShoulder?.inside(outer) == true
    val rInOuter = rShoulder?.inside(outer) == true
    val lInInner = lShoulder?.inside(inner) == true
    val rInInner = rShoulder?.inside(inner) == true

    val shouldersInOuter = (lInOuter && rInOuter)
    val shouldersOutOfInner = (!lInInner && !rInInner)

    val ok = behind && shouldersInOuter && shouldersOutOfInner

    return GuideResult(
      ok = ok,
      shouldersInOuter = shouldersInOuter,
      shouldersOutOfInner = shouldersOutOfInner,
      fromBehind = behind,
    )
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    // Draw body guideline (upper body silhouette)
    fun drawBodyGuideline() {
      val inner = innerGuidelinePx ?: return

      // Calculate body proportions based on screen size
      // Spine should be centered in the inner guideline area
      val spineCenterX = (inner.left + inner.right) / 2
      val bodyTop = height * 0.05f  // Head starts near top
      val bodyBottom = height * 0.85f  // Torso ends at 85% of screen height

      // Body width - wider than inner guideline, fills most of outer area
      val outerArea = outerGuidelinePx ?: return
      val bodyWidth = (outerArea.right - outerArea.left) * 0.9f
      val halfBodyWidth = bodyWidth / 2

      // Head dimensions (relative to body)
      //val headRadius = bodyWidth * 0.12f
      val headRadius = bodyWidth * 0.25f
      val headCenterY = bodyTop + headRadius

      // Neck
      val neckTop = headCenterY + headRadius
      val neckBottom = neckTop + height * 0.01f
      val neckWidth = headRadius * 0.6f

      // Shoulders
      val shoulderY = neckBottom + height * 0.02f
      val shoulderWidth = halfBodyWidth

      // Arms extend outward
      val armY = shoulderY + height * 0.12f
      val handY = armY + height * 0.15f

      // Torso
      val waistY = bodyBottom

      val path = android.graphics.Path()

      // Draw head (circle outline)
      path.addCircle(spineCenterX, headCenterY, headRadius, android.graphics.Path.Direction.CW)

      // Draw neck
      path.moveTo(spineCenterX - neckWidth, neckTop)
      path.lineTo(spineCenterX - neckWidth, neckBottom)
      path.moveTo(spineCenterX + neckWidth, neckTop)
      path.lineTo(spineCenterX + neckWidth, neckBottom)

      // Draw shoulders and upper body outline
      val bodyPath = android.graphics.Path()

      // Left side: neck -> shoulder -> arm -> hand -> back to torso -> waist
      bodyPath.moveTo(spineCenterX - neckWidth, neckBottom)
      bodyPath.lineTo(spineCenterX - shoulderWidth, shoulderY)  // Left shoulder
      bodyPath.lineTo(spineCenterX - shoulderWidth - bodyWidth * 0.15f, armY)  // Upper arm
      bodyPath.lineTo(spineCenterX - shoulderWidth - bodyWidth * 0.2f, handY)  // Hand/forearm
      bodyPath.moveTo(spineCenterX - shoulderWidth, shoulderY)
      bodyPath.lineTo(spineCenterX - shoulderWidth, waistY)  // Left torso to waist

      // Right side
      bodyPath.moveTo(spineCenterX + neckWidth, neckBottom)
      bodyPath.lineTo(spineCenterX + shoulderWidth, shoulderY)  // Right shoulder
      bodyPath.lineTo(spineCenterX + shoulderWidth + bodyWidth * 0.15f, armY)  // Upper arm
      bodyPath.lineTo(spineCenterX + shoulderWidth + bodyWidth * 0.2f, handY)  // Hand/forearm
      bodyPath.moveTo(spineCenterX + shoulderWidth, shoulderY)
      bodyPath.lineTo(spineCenterX + shoulderWidth, waistY)  // Right torso to waist

      // Connect shoulders across back
      bodyPath.moveTo(spineCenterX - shoulderWidth, shoulderY)
      bodyPath.lineTo(spineCenterX + shoulderWidth, shoulderY)

      // Spine line (center line from neck to waist)
      bodyPath.moveTo(spineCenterX, neckTop)
      bodyPath.lineTo(spineCenterX, waistY)

      canvas.drawPath(path, bodyGuidelinePaint)
      canvas.drawPath(bodyPath, bodyGuidelinePaint)
    }

   // Draw guideline rectangles
    fun drawGuideline() {
     val o = outerGuidelinePx
     val i = innerGuidelinePx
     if (o == null || i == null) return

     canvas.drawRect(o, outerGuidelinePaint)
     canvas.drawRect(i, innerGuidelinePaint)
   }

    // Draw landmarker
    fun drawLandmarker() {
      val lm = latestLandmarks.get() ?: return
      val cLeft  = 0xFF4CAF50.toInt()   // green-ish
      val cRight = 0xFF2196F3.toInt()   // blue-ish
      val cMid   = 0xFFFFC107.toInt()   // amber

      pointPaint.color = cMid
      linePaint.color  = cMid
      linePaint.strokeCap = Paint.Cap.ROUND
      linePaint.strokeJoin = Paint.Join.ROUND
      linePaint.strokeWidth = max(4f, min(width, height) * 0.004f)

      canvas.withSave {
        // Draw connections first
        for ((a, b) in POSE_CONNECTIONS) {
          val pA = lm.safeGet(a) ?: continue
          val pB = lm.safeGet(b) ?: continue
          val (x1, y1) = toViewXY(pA.x(), pA.y())
          val (x2, y2) = toViewXY(pB.x(), pB.y())
          // Color mid / left / right groups for readability
          linePaint.color = when {
            LEFT_IDS.contains(a) && LEFT_IDS.contains(b) -> cLeft
            RIGHT_IDS.contains(a) && RIGHT_IDS.contains(b) -> cRight
            else -> cMid
          }
          canvas.drawLine(x1, y1, x2, y2, linePaint)
        }

        // Draw keypoints
        val r = max(4f, min(width, height) * 0.006f)
        lm.forEachIndexed { idx, p ->
          val (cx, cy) = toViewXY(p.x(), p.y())
          pointPaint.color = when (idx) {
            in LEFT_IDS  -> cLeft
            in RIGHT_IDS -> cRight
            else         -> cMid
          }
          canvas.drawCircle(cx, cy, r, pointPaint)
        }
      }
    }

    drawBodyGuideline()
    //drawGuideline()
    //drawLandmarker()
  }

  /** Convert normalized landmark coords [0..1] into view coordinates. */
  private fun toViewXY(nx: Float, ny: Float): Pair<Float, Float> {
    val (rx, ry) = rotateNormalize(nx, ny)
    return Pair(rx * width, ry * height)
  }

  private fun rotateNormalize(nx: Float, ny: Float): Pair<Float, Float> {
    val x = if (mirror) 1f - nx else nx
    val y = ny

    val rotatedX = 1f - y
    val rotatedY = x
    return rotatedX to rotatedY
  }

  private fun List<NormalizedLandmark>.safeGet(i: Int): NormalizedLandmark? =
    if (i in indices) this[i] else null

  companion object {
    // MediaPipe Pose topology (33 landmarks).
    // Index reference: https://developers.google.com/mediapipe/solutions/vision/pose_landmarker
    // 0:nose, 1:LEyeInner, 2:LEye, 3:LEyeOuter, 4:REyeInner, 5:REye, 6:REyeOuter,
    // 7:L ear, 8:R ear, 9:L mouth,10:R mouth,
    // 11:L shoulder,12:R shoulder,13:L elbow,14:R elbow,15:L wrist,16:R wrist,
    // 17:L pinky,18:R pinky,19:L index,20:R index,21:L thumb,22:R thumb,
    // 23:L hip,24:R hip,25:L knee,26:R knee,27:L ankle,28:R ankle,
    // 29:L heel,30:R heel,31:L foot index,32:R foot index
    val POSE_CONNECTIONS = arrayOf(
      // Torso
      11 to 12, 11 to 23, 12 to 24, 23 to 24,
      // Left arm
      11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21,
      // Right arm
      12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22,
      // Left leg
      23 to 25, 25 to 27, 27 to 29, 29 to 31,
      // Right leg
      24 to 26, 26 to 28, 28 to 30, 30 to 32,
      // Face (simple outline)
      0 to 1, 1 to 2, 2 to 3, 3 to 7,
      0 to 4, 4 to 5, 5 to 6, 6 to 8,
      9 to 10, 2 to 5
    )

    // Helpful groups for coloring
    val LEFT_IDS = setOf(1,2,3,7,9,11,13,15,17,19,21,23,25,27,29,31)
    val RIGHT_IDS = setOf(4,5,6,8,10,12,14,16,18,20,22,24,26,28,30,32)
  }
}

