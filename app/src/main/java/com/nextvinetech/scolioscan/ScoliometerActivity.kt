package com.nextvinetech.scolioscan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

class ScoliometerActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "ScoliometerActivity"
        const val EXTRA_JWT_TOKEN = "jwt_token"

        // Tuning constants
        private const val GRAV_ALPHA = 0.84
        private const val ACC_JUMP_MAX_DEG = 20.0
        private const val SNAP_ZERO = 0.25

        // Spring animation constants
        private const val WN = 6.0
        private const val C = 2 * WN
        private const val MAX_VEL_DEG_PER_SEC = 240.0

        // Measurement constants
        private const val TOTAL_MEASUREMENTS = 5
        private val MEASUREMENT_LABELS = arrayOf(
            "1. 흉추 상부 (Upper Thoracic)",
            "2. 흉추 중부 (Mid Thoracic)",
            "3. 흉요추 (Thoracolumbar)",
            "4. 요추 상부 (Upper Lumbar)",
            "5. 요추 하부 (Lower Lumbar)"
        )

        // Prefs keys
        private const val PREFS_NAME = "scoliometer_prefs"
        private const val PREF_ZERO_OFFSET = "zero_offset"
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private lateinit var gaugeView: ScoliometerGaugeView
    private lateinit var angleText: TextView
    private lateinit var peakText: TextView
    private lateinit var readingsCountText: TextView
    private lateinit var calibrateButton: Button
    private lateinit var recordButton: Button
    private lateinit var backButton: View
    private lateinit var measurementLabel: TextView

    // Camera related
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var hiddenPreviewView: PreviewView
    private lateinit var imageCapture: ImageCapture
    private var capturedImageFile: File? = null
    private var isCameraReady = false

    // JWT token
    private var jwtToken: String? = null

    // Camera permission request
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Log.w(TAG, "Camera permission denied - will submit without image")
        }
    }

    // Zero offset and peak
    private var zeroOffset = 0.0
    private var peakAbs = 0.0

    // Angles
    private var angleAccDeg = 0.0
    private var displayDeg = 0.0

    // Gravity LPF
    private var axLP = 0.0
    private var ayLP = 0.0
    private var azLP = 0.0
    private var lpfInit = false

    // Median-3 & outlier
    private val accAngleBuf = mutableListOf<Double>()
    private var lastAcceptedAcc = 0.0

    // Spring animation
    private var targetDeg = 0.0
    private var dispVel = 0.0
    private var freezeUntilMs = 0L

    // Frame loop
    private val handler = Handler(Looper.getMainLooper())
    private var lastFrameMs = 0L

    // Measurements for this session (5 readings)
    private val measurements = mutableListOf<Double>()
    private var isSubmitting = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            val nowMs = System.currentTimeMillis()
            var dt = (nowMs - lastFrameMs) / 1000.0
            lastFrameMs = nowMs

            if (dt <= 0 || dt > 0.1) dt = 0.016

            if (nowMs < freezeUntilMs) {
                displayDeg = 0.0
                dispVel = 0.0
                updateUI()
                handler.postDelayed(this, 16)
                return
            }

            val snapTarget = if (abs(targetDeg) < SNAP_ZERO) 0.0 else targetDeg

            val err = snapTarget - displayDeg
            val a = (WN * WN) * err - C * dispVel
            dispVel += a * dt

            dispVel = dispVel.coerceIn(-MAX_VEL_DEG_PER_SEC, MAX_VEL_DEG_PER_SEC)
            displayDeg += dispVel * dt
            displayDeg = displayDeg.coerceIn(-30.0, 30.0)

            val absVal = abs(displayDeg)
            if (absVal > peakAbs) peakAbs = absVal
            if (peakAbs > 30.0) peakAbs = 30.0

            updateUI()
            handler.postDelayed(this, 16)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scoliometer)

        jwtToken = intent.getStringExtra(EXTRA_JWT_TOKEN)
        Log.d(TAG, "JWT Token received: ${jwtToken?.take(20)}...")

        cameraExecutor = Executors.newSingleThreadExecutor()

        initViews()
        loadPrefs()
        initSensors()
        initCamera()
        startFrameLoop()
    }

    private fun initCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
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
        // Hidden preview - just for camera to work
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = hiddenPreviewView.surfaceProvider
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            isCameraReady = true
            Log.d(TAG, "Camera ready for capture")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", e)
            isCameraReady = false
        }
    }

    private fun captureImage(onComplete: (File?) -> Unit) {
        if (!isCameraReady) {
            Log.w(TAG, "Camera not ready, skipping capture")
            onComplete(null)
            return
        }

        val dir = getExternalFilesDir("scoliometer")!!
        if (!dir.exists()) dir.mkdirs()
        val photoFile = File.createTempFile("scolio_", ".jpg", dir)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Image captured: ${photoFile.absolutePath}")
                    capturedImageFile = photoFile
                    onComplete(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed", exception)
                    onComplete(null)
                }
            }
        )
    }

    private fun initViews() {
        gaugeView = findViewById(R.id.gaugeView)
        angleText = findViewById(R.id.angleText)
        peakText = findViewById(R.id.peakText)
        readingsCountText = findViewById(R.id.readingsCountText)
        calibrateButton = findViewById(R.id.calibrateButton)
        recordButton = findViewById(R.id.recordButton)
        backButton = findViewById(R.id.backButton)
        measurementLabel = findViewById(R.id.measurementLabel)
        hiddenPreviewView = findViewById(R.id.hiddenPreviewView)

        calibrateButton.setOnClickListener { calibrateZero() }
        recordButton.setOnClickListener { record() }
        backButton.setOnClickListener { confirmExit() }

        updateMeasurementLabel()
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        zeroOffset = prefs.getFloat(PREF_ZERO_OFFSET, 0f).toDouble()
    }

    private fun savePrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(PREF_ZERO_OFFSET, zeroOffset.toFloat())
            .apply()
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accelerometer == null) {
            Toast.makeText(this, "가속도 센서를 찾을 수 없습니다", Toast.LENGTH_LONG).show()
        }
    }

    private fun startFrameLoop() {
        lastFrameMs = System.currentTimeMillis()
        handler.post(frameRunnable)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(frameRunnable)
        cameraExecutor.shutdown()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> processAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> { /* Can be used for auto-invert detection */ }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processAccelerometer(event: SensorEvent) {
        val ax = event.values[0].toDouble()
        val ay = event.values[1].toDouble()
        val az = event.values[2].toDouble()

        if (!lpfInit) {
            axLP = ax
            ayLP = ay
            azLP = az
            lpfInit = true
        } else {
            axLP = GRAV_ALPHA * axLP + (1 - GRAV_ALPHA) * ax
            ayLP = GRAV_ALPHA * ayLP + (1 - GRAV_ALPHA) * ay
            azLP = GRAV_ALPHA * azLP + (1 - GRAV_ALPHA) * az
        }

        processFromGravity()
    }

    private fun processFromGravity() {
        val norm = max(1e-6, sqrt(axLP * axLP + ayLP * ayLP + azLP * azLP))
        val nx = axLP / norm
        val ny = ayLP / norm

        // Long edge mount (landscape mode)
        val raw = atan2(ny, nx) * 180.0 / Math.PI

        val med = pushAndMedian(raw)
        if (abs(med - lastAcceptedAcc) > ACC_JUMP_MAX_DEG) {
            lastAcceptedAcc = med
            return
        }
        lastAcceptedAcc = med

        angleAccDeg = med - zeroOffset
        updateDisplay(angleAccDeg)
    }

    private fun pushAndMedian(v: Double): Double {
        accAngleBuf.add(v)
        if (accAngleBuf.size > 3) accAngleBuf.removeAt(0)
        val sorted = accAngleBuf.sorted()
        return sorted[sorted.size / 2]
    }

    private fun updateDisplay(targetDeg: Double) {
        this.targetDeg = targetDeg.coerceIn(-30.0, 30.0)
    }

    private fun updateUI() {
        gaugeView.setAngle(displayDeg.toFloat())
        angleText.text = "${displayDeg.toInt()}°"
        peakText.text = "Peak: ${String.format("%.1f", peakAbs)}°"
        readingsCountText.text = "${measurements.size} / $TOTAL_MEASUREMENTS"

        // Update button state
        if (measurements.size >= TOTAL_MEASUREMENTS) {
            recordButton.text = "측정 완료"
            recordButton.isEnabled = !isSubmitting
        } else {
            recordButton.text = "기록 (${measurements.size + 1}/${TOTAL_MEASUREMENTS})"
            recordButton.isEnabled = true
        }
    }

    private fun updateMeasurementLabel() {
        val index = measurements.size
        if (index < TOTAL_MEASUREMENTS) {
            measurementLabel.text = MEASUREMENT_LABELS[index]
            measurementLabel.visibility = View.VISIBLE
        } else {
            measurementLabel.text = "모든 측정 완료"
            measurementLabel.visibility = View.VISIBLE
        }
    }

    private fun calibrateZero() {
        vibrate()

        val norm = max(1e-6, sqrt(axLP * axLP + ayLP * ayLP + azLP * azLP))
        val nx = axLP / norm
        val ny = ayLP / norm

        val raw = atan2(ny, nx) * 180.0 / Math.PI

        zeroOffset = raw
        savePrefs()

        angleAccDeg = 0.0
        displayDeg = 0.0
        dispVel = 0.0
        peakAbs = 0.0
        accAngleBuf.clear()
        accAngleBuf.addAll(listOf(raw, raw, raw))

        freezeUntilMs = System.currentTimeMillis() + 100

        Toast.makeText(this, "0° 보정 완료", Toast.LENGTH_SHORT).show()
    }

    private fun record() {
        if (isSubmitting) return

        if (measurements.size >= TOTAL_MEASUREMENTS) {
            // All measurements done, submit to API
            submitMeasurements()
            return
        }

        vibrate()

        val clamped = displayDeg.coerceIn(-30.0, 30.0)
        measurements.add(abs(clamped)) // Store absolute value

        Log.d(TAG, "Recorded measurement ${measurements.size}: ${String.format("%.1f", clamped)}°")

        updateUI()
        updateMeasurementLabel()

        // If all 5 measurements are done, show completion dialog
        if (measurements.size >= TOTAL_MEASUREMENTS) {
            showCompletionDialog()
        }
    }

    private fun showCompletionDialog() {
        val thoracic = calculateThoracic()
        val thoracolumbar = calculateThoracolumbar()
        val lumbar = calculateLumbar()
        val score = calculateScore()

        AlertDialog.Builder(this)
            .setTitle("측정 완료")
            .setMessage("""
                모든 측정이 완료되었습니다.

                흉추 (Thoracic): ${String.format("%.1f", thoracic)}°
                흉요추 (Thoracolumbar): ${String.format("%.1f", thoracolumbar)}°
                요추 (Lumbar): ${String.format("%.1f", lumbar)}°

                점수 (Score): ${String.format("%.1f", score)}

                결과를 저장하시겠습니까?
            """.trimIndent())
            .setPositiveButton("저장") { _, _ ->
                submitMeasurements()
            }
            .setNegativeButton("다시 측정") { _, _ ->
                measurements.clear()
                peakAbs = 0.0
                updateUI()
                updateMeasurementLabel()
                Toast.makeText(this, "측정을 다시 시작합니다", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Calculate thoracic ATR (average of measurements 1-2)
     */
    private fun calculateThoracic(): Double {
        if (measurements.size < 2) return 0.0
        return (measurements[0] + measurements[1]) / 2.0
    }

    /**
     * Calculate thoracolumbar ATR (measurement 3)
     */
    private fun calculateThoracolumbar(): Double {
        if (measurements.size < 3) return 0.0
        return measurements[2]
    }

    /**
     * Calculate lumbar ATR (average of measurements 4-5)
     */
    private fun calculateLumbar(): Double {
        if (measurements.size < 5) return 0.0
        return (measurements[3] + measurements[4]) / 2.0
    }

    /**
     * Calculate score (100 - total deviation, clamped to 0-100)
     * Higher score = better (less deviation)
     */
    private fun calculateScore(): Double {
        val mainThoracic = calculateThoracic()
        val lumbar = calculateLumbar()
        val totalDeviation = mainThoracic + lumbar
        return (100.0 - totalDeviation).coerceIn(0.0, 100.0)
    }

    private fun submitMeasurements() {
        val token = jwtToken
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "JWT token is not available")
            showErrorDialog("인증 정보가 없습니다")
            return
        }

        if (measurements.size < TOTAL_MEASUREMENTS) {
            showErrorDialog("측정이 완료되지 않았습니다")
            return
        }

        isSubmitting = true
        updateUI()

        // Capture image first, then submit
        captureImage { imageFile ->
            val request = ApiClient.ScoliometerRequest(
                mainThoracic = calculateThoracic(),
                secondThoracic = calculateThoracolumbar(),
                lumbar = calculateLumbar(),
                score = calculateScore(),
                imageFile = imageFile
            )

            Log.d(TAG, "Submitting scoliometer measurements: main_thoracic=${request.mainThoracic}, second_thoracic=${request.secondThoracic}, lumbar=${request.lumbar}, score=${request.score}, hasImage=${imageFile != null}")

            ApiClient.submitScoliometerAnalysis(token, request, object : ApiClient.ApiCallback {
                override fun onSuccess(response: JSONObject) {
                    Log.d(TAG, "Scoliometer measurement submitted successfully: $response")
                    runOnUiThread {
                        // Just close without toast on success
                        finish()
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Failed to submit scoliometer measurement: $error")
                    runOnUiThread {
                        isSubmitting = false
                        updateUI()
                        showErrorDialog("저장 실패: $error")
                    }
                }
            })
        }
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("오류")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun confirmExit() {
        if (measurements.isEmpty()) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("측정 종료")
            .setMessage("측정을 종료하시겠습니까?\n현재까지의 측정 데이터가 삭제됩니다.")
            .setPositiveButton("종료") { _, _ -> finish() }
            .setNegativeButton("취소", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmExit()
    }

    private fun vibrate() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }
}
