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
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
  private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
  private lateinit var cameraExecutor: ExecutorService
  private lateinit var previewView: PreviewView
  private lateinit var poseGuideline: PoseGuideline

  private val requestCameraPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) startCamera()
    else Log.e("CameraX", "Camera permission denied")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    previewView = findViewById(R.id.previewView)
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
    //val previewView = findViewById<PreviewView>(R.id.previewView)
    val preview = Preview.Builder().build().also{
      it.surfaceProvider = previewView.surfaceProvider
    }

    poseGuideline = findViewById(R.id.poseGuideline)
    poseGuideline.setMirrorHorizontally(false)
    val outerN = RectF(0.05f, 0.2f, 0.8f, 0.9f)
    val innerN = RectF(0.22f, 0.3f, 0.43f, 0.8f)
    poseGuideline.setGuidelineNormalized(outerN, innerN)

    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    val imageAnalysis = ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()
      .also {
        it.setAnalyzer(Executors.newSingleThreadExecutor(), PoseAnalyzer(this, poseGuideline))
      }

    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
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



