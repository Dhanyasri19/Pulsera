package com.pulsera.ppg

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnStart: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvHR: TextView
    private lateinit var tvHRV: TextView
    private lateinit var tvRR: TextView
    private lateinit var tvSignalQuality: TextView
    private lateinit var waveformView: WaveformView

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null

    // Capture configuration
    private val captureDurationMs = 25_000L   // ~25 seconds is enough for HR/HRV, adequate for RR trend
    private val targetFps = 30.0
    private var isCapturing = false
    private var captureStartTime = 0L
    private val redChannelSamples = mutableListOf<Float>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCameraAndCapture() else {
                tvStatus.text = "Camera permission is required to run a checkup."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        btnStart = findViewById(R.id.btnStart)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvHR = findViewById(R.id.tvHR)
        tvHRV = findViewById(R.id.tvHRV)
        tvRR = findViewById(R.id.tvRR)
        tvSignalQuality = findViewById(R.id.tvSignalQuality)
        waveformView = findViewById(R.id.waveformView)

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnStart.setOnClickListener { onStartClicked() }
    }

    private fun onStartClicked() {
        resetUiForNewCapture()
        if (hasCameraPermission()) {
            startCameraAndCapture()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun resetUiForNewCapture() {
        redChannelSamples.clear()
        tvHR.text = "--"
        tvHRV.text = "--"
        tvRR.text = "--"
        tvSignalQuality.text = ""
        waveformView.setSamples(emptyList())
        progressBar.progress = 0
        btnStart.isEnabled = false
        tvStatus.text = "Starting camera..."
    }

    private fun startCameraAndCapture() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            analyzeFrame(imageProxy)
        }

        camera = provider.bindToLifecycle(this, cameraSelector, imageAnalysis)

        // Turn on flashlight - required for fingertip PPG (transmits light through tissue)
        camera?.cameraControl?.enableTorch(true)

        isCapturing = true
        captureStartTime = System.currentTimeMillis()

        mainHandler.post {
            tvStatus.text = "Capturing... keep your fingertip still over the camera and flash."
        }

        // Schedule progress updates and final stop
        mainHandler.postDelayed(progressUpdater, 200)
        mainHandler.postDelayed({ stopCaptureAndProcess() }, captureDurationMs)
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (!isCapturing) return
            val elapsed = System.currentTimeMillis() - captureStartTime
            val pct = ((elapsed.toFloat() / captureDurationMs) * 100).roundToInt().coerceIn(0, 100)
            progressBar.progress = pct
            if (elapsed < captureDurationMs) {
                mainHandler.postDelayed(this, 200)
            }
        }
    }

    /**
     * Extracts the average red-channel intensity from each frame. The red channel
     * carries the strongest PPG modulation when the fingertip is illuminated by the
     * white LED flash at close range (matches the wavelength sensitivity used in
     * reflective fingertip PPG).
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!isCapturing) {
            imageProxy.close()
            return
        }
        try {
            val yPlane = imageProxy.planes[0]
            val buffer = yPlane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Y (luma) plane average is used as a fast, robust intensity proxy.
            // With flash-illuminated fingertip contact, luma tracks the red-dominant
            // reflected signal closely enough to reveal the cardiac pulsation.
            var sum = 0L
            var i = 0
            // sample every 4th byte to keep this fast on-device
            while (i < bytes.size) {
                sum += bytes[i].toInt() and 0xFF
                i += 4
            }
            val sampleCount = (bytes.size / 4).coerceAtLeast(1)
            val avgIntensity = sum.toFloat() / sampleCount

            synchronized(redChannelSamples) {
                redChannelSamples.add(avgIntensity)
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun stopCaptureAndProcess() {
        isCapturing = false
        camera?.cameraControl?.enableTorch(false)
        cameraProvider?.unbindAll()

        tvStatus.text = "Processing signal..."
        progressBar.progress = 100

        val samplesCopy: List<Float>
        synchronized(redChannelSamples) {
            samplesCopy = redChannelSamples.toList()
        }

        // Effective fps actually achieved during capture (frames collected / duration)
        val effectiveFps = samplesCopy.size / (captureDurationMs / 1000.0)

        cameraExecutor.execute {
            val result = PPGProcessor.process(samplesCopy, effectiveFps)
            mainHandler.post { displayResult(result) }
        }
    }

    private fun displayResult(result: PPGResult) {
        btnStart.isEnabled = true
        tvStatus.text = "Checkup complete. Press Start Capturing to run again."
        tvSignalQuality.text = result.qualityMessage

        val messageColor = if (result.signalQualityOk) {
            android.graphics.Color.parseColor("#9AA0A6")
        } else {
            android.graphics.Color.parseColor("#F5C451")
        }
        tvSignalQuality.setTextColor(messageColor)

        waveformView.setSamples(
            if (result.filteredSignal.isNotEmpty()) result.filteredSignal else result.rawSignal
        )

        tvHR.text = result.heartRateBpm?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
        tvHRV.text = result.hrvRmssdMs?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
        tvRR.text = result.respirationRateBrpm?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
    }
    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
        cameraExecutor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
