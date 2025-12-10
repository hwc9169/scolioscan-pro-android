package com.nextvinetech.scolioscan

import android.Manifest
import android.media.Image
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.RectF
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.google.common.util.concurrent.ListenableFuture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.BitmapImageBuilder

import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.core.RunningMode

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "MainActivity"
  }

  private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
  private lateinit var cameraExecutor: ExecutorService
  private lateinit var previewView: PreviewView
  private lateinit var poseGuideline: PoseGuideline
  private lateinit var countdownText: TextView
  private lateinit var statusText: TextView

  private lateinit var imageCapture: ImageCapture

  // JWT token received from ServiceActivity
  private var jwtToken: String? = null

  private val requestCameraPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) startCamera()
    else Log.e("CameraX", "Camera permission denied")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // Get JWT token from intent
    jwtToken = intent.getStringExtra(ServiceActivity.EXTRA_JWT_TOKEN)
    Log.d(TAG, "JWT Token received: ${jwtToken?.take(20)}...")

    // Initialize UI elements
    previewView = findViewById(R.id.previewView)
    countdownText = findViewById(R.id.countdownText)
    statusText = findViewById(R.id.statusText)

    cameraExecutor = Executors.newSingleThreadExecutor()

    if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
      == PackageManager.PERMISSION_GRANTED
    ) {
      startCamera()
    } else {
      requestCameraPermission.launch(Manifest.permission.CAMERA)
    }
  }

  private fun startCamera() {
    cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    cameraProviderFuture.addListener({
      val cameraProvider = cameraProviderFuture.get()
      bindCameraUseCases(cameraProvider)
    }, ContextCompat.getMainExecutor(this))
  }

  private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
    val preview = Preview.Builder().build().also{
      it.surfaceProvider = previewView.surfaceProvider
    }

    poseGuideline = findViewById(R.id.poseGuideline)
    poseGuideline.setMirrorHorizontally(false)
    val outerN = RectF(0.05f, 0.2f, 0.8f, 0.9f)
    val innerN = RectF(0.22f, 0.3f, 0.43f, 0.8f)
    poseGuideline.setGuidelineNormalized(outerN, innerN)

    // Set measurement completion listener
    poseGuideline.setOnMeasurementCompleteListener(object : PoseGuideline.OnMeasurementCompleteListener {
      override fun onMeasurementComplete(result: PoseGuideline.MeasurementResult) {
        Log.d(TAG, "Measurement complete: mainThoracic=${result.mainThoracic}, lumbar=${result.lumbar}")

        val imageFile = poseGuideline.getLastCapturedImageFile()

        submitMeasurement(
          mainThoracic = result.mainThoracic,
          secondThoracic = result.secondThoracic,
          lumbar = result.lumbar,
          severity = result.severity,
          backType = result.backType,
          imageFile = imageFile!!
        )
      }
    })

    // Set state update listener for UI changes
    poseGuideline.setOnStateUpdateListener(object : PoseGuideline.OnStateUpdateListener {
      override fun onStateUpdate(isOk: Boolean, remainingSeconds: Int, statusMessage: String) {
        updateGuidelineUI(isOk, remainingSeconds, statusMessage)
      }
    })

    poseGuideline.setOnImageCaptureRequestListener(
      object: PoseGuideline.OnImageCaptureRequestListener {
        override fun onImageCaptureRequested() {
          val dir = getExternalFilesDir("scolioscan")!!
          if (!dir.exists()) dir.mkdirs()
          val photoFile = java.io.File.createTempFile("scolio_", ".jpg", dir)
          val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

          imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this@MainActivity),
            object : ImageCapture.OnImageSavedCallback {
              override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                Log.d(TAG, "Image saved: ${photoFile.absolutePath}")
                poseGuideline.processCapturedImage(photoFile)
              }

              override fun onError(exception: ImageCaptureException) {
                poseGuideline.resetMeasurement()
                TODO("Not yet implemented")
              }
            }
          )
        }
      }
    )

    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    imageCapture = ImageCapture.Builder()
      .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
      .build()

    val imageAnalysis = ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()
      .also {
        it.setAnalyzer(Executors.newSingleThreadExecutor(), PoseAnalyzer(this, poseGuideline))
      }

    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis)
  }

  /**
   * Update UI based on pose detection state
   */
  private fun updateGuidelineUI(isOk: Boolean, remainingSeconds: Int, statusMessage: String) {
    // Update countdown text
    if (isOk && remainingSeconds > 0) {
      countdownText.text = remainingSeconds.toString()
      countdownText.visibility = View.VISIBLE
    } else {
      countdownText.visibility = View.GONE
    }

    // Update status text
    statusText.text = statusMessage
  }

  /**
   * Submit measurement result to the API
   * Call this method when the spine measurement is complete
   *
   * @param mainThoracic Main thoracic curve angle
   * @param secondThoracic Second thoracic curve angle (optional)
   * @param lumbar Lumbar curve angle
   * @param imageUrl Image URL if captured (optional)
   */
  fun submitMeasurement(
    mainThoracic: Double,
    secondThoracic: Double? = null,
    lumbar: Double,
    severity: String,
    backType: String,
    imageFile: java.io.File,
    imageUrl: String? = null
  ) {
    val token = jwtToken
    if (token.isNullOrEmpty()) {
      Log.e(TAG, "JWT token is not available")
      runOnUiThread {
        Toast.makeText(this, "인증 정보가 없습니다", Toast.LENGTH_SHORT).show()
      }
      return
    }

    val request = ApiClient.AnalysisRequest(
      analysisType = ApiClient.AnalysisType.TYPE_2D,
      mainThoracic = mainThoracic,
      secondThoracic = secondThoracic,
      lumbar = lumbar,
      severity = severity,
      backType = backType,
      imageFile = imageFile,
      imageUrl = imageUrl
    )

    ApiClient.submitAnalysis(token, request, object : ApiClient.ApiCallback {
      override fun onSuccess(response: JSONObject) {
        Log.d(TAG, "Measurement submitted successfully: $response")
        runOnUiThread {
          Toast.makeText(this@MainActivity, "측정 결과가 저장되었습니다", Toast.LENGTH_SHORT).show()
          finish() // Return to ServiceActivity
        }
      }

      override fun onError(error: String) {
        Log.e(TAG, "Failed to submit measurement: $error")
        runOnUiThread {
          Toast.makeText(this@MainActivity, "저장 실패: $error", Toast.LENGTH_SHORT).show()
        }
      }
    })
  }

  override fun onDestroy() {
    super.onDestroy()
    cameraExecutor.shutdown()
  }
}

class PoseAnalyzer(context: Context, poseGuideline: PoseGuideline): ImageAnalysis.Analyzer {
  private val landmarker: PoseLandmarker

  init {
    val modelName = "models/pose_landmarker_lite.task"
    val base = BaseOptions.builder()
      .setModelAssetPath(modelName)
      .setDelegate(Delegate.CPU)
      .build()

    val options = PoseLandmarker.PoseLandmarkerOptions.builder()
      .setBaseOptions(base)
      .setMinPoseDetectionConfidence(0.4f)
      .setMinTrackingConfidence(0.4f)
      .setRunningMode(RunningMode.LIVE_STREAM)
      .setNumPoses(1)
      .setResultListener { result: PoseLandmarkerResult, _: MPImage? ->
        poseGuideline.update(result)
      }
      .build()

    landmarker = PoseLandmarker.createFromOptions(context, options)
  }

  @OptIn(ExperimentalGetImage::class)
  override fun analyze(imageProxy: ImageProxy) {
    val mediaImage = imageProxy.image ?: run { imageProxy.close(); return}
    val bitmap = yuvToRgb(mediaImage)
    val argb = if (bitmap.config != Bitmap.Config.ARGB_8888)
      bitmap.copy(Bitmap.Config.ARGB_8888, false)
    else
      bitmap
    val mpImage: MPImage = BitmapImageBuilder(argb).build()

    val processing = ImageProcessingOptions.builder()
      .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
      .build()

    landmarker.detectAsync(
      mpImage,
      processing,
      SystemClock.uptimeMillis()
    )
    imageProxy.close()
  }

  private fun yuvToRgb(image: Image): Bitmap {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = android.graphics.YuvImage(
      nv21,
      ImageFormat.NV21,
      image.width,
      image.height,
      null
    )
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 100, out)
    val yuvBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(yuvBytes, 0, yuvBytes.size)
  }
}
