package com.aicam.pro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Range
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2

class MainActivity : AppCompatActivity(), SensorEventListener {

    private enum class CamMode { AI_PRO, AUTO, MANUAL }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvBadge: TextView
    private lateinit var tvRecTimer: TextView
    private lateinit var tvCoach: TextView
    private lateinit var tvIso: TextView
    private lateinit var tvShutter: TextView
    private lateinit var tvZoom: TextView
    private lateinit var manualPanel: LinearLayout
    private lateinit var aiToggleRow: LinearLayout
    private lateinit var proFinishRow: LinearLayout
    private lateinit var seekIso: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var seekZoom: SeekBar
    private lateinit var btnMode: Button
    private lateinit var btnPhotoVideo: Button
    private lateinit var btnCapture: Button
    private lateinit var btnFlash: Button
    private lateinit var btnVoice: Button
    private lateinit var btnAutoClick: Button
    private lateinit var btnAutoZoom: Button
    private lateinit var btnBurst: Button
    private lateinit var btnReframe: Button
    private lateinit var btnBokeh: Button
    private lateinit var btnLook: Button
    private lateinit var btnDirector: Button

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentMode = CamMode.AI_PRO
    private var currentLook = ProLook.NATURAL
    private var currentReframe = ReframeMode.OFF
    private var currentBokeh = BokehLevel.OFF
    private var isBurstEnabled = false
    private var isVideoMode = false
    private var isRecording = false
    private var isVoiceEnabled = true
    private var isAutoClickEnabled = true
    private var isAutoZoomEnabled = true
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var torchEnabled = false

    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var activeShutterSteps: List<Pair<String, Long>> = emptyList()
    private var hasOis = false
    private var hasEis = false
    private var hardwareReport: String = "Scanning..."

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var ioExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector
    private lateinit var photoEngine: AiPhotoEngine
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile private var currentRollDeg = 0f
    @Volatile private var currentPitchZ = 0f
    @Volatile private var shakeIntensity = 0f
    private var lastAx = 0f
    private var lastAy = 0f
    private var lastAz = 0f

    private var lastVoiceTime = 0L
    private var lastVoiceTip = ""
    private var lastAutoClickTime = 0L
    private var lastAutoFocusTime = 0L
    private var lastEvAdjustTime = 0L
    private var lastAutoZoomTime = 0L
    private var lastHardwareProfileTime = 0L
    private var lastStatusHoldUntil = 0L
    private var lastNightFlag = false
    private var currentEvIndex = 0
    private var stablePerfectFrames = 0
    @Volatile private var isCapturingPhoto = false

    private val allShutterPresets = listOf(
        "1/4000s" to 250_000L,
        "1/2000s" to 500_000L,
        "1/1000s" to 1_000_000L,
        "1/500s" to 2_000_000L,
        "1/250s" to 4_000_000L,
        "1/125s" to 8_000_000L,
        "1/60s" to 16_666_666L,
        "1/30s" to 33_333_333L,
        "1/15s" to 66_666_666L,
        "1/8s" to 125_000_000L,
        "1/4s" to 250_000_000L,
        "1/2s" to 500_000_000L,
        "1s" to 1_000_000_000L,
        "2s" to 2_000_000_000L,
        "4s" to 4_000_000_000L
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] == true) {
                hardwareReport = scanAllCameraHardware()
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission zaroori hai", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvBadge = findViewById(R.id.tvBadge)
        tvRecTimer = findViewById(R.id.tvRecTimer)
        tvCoach = findViewById(R.id.tvCoach)
        tvIso = findViewById(R.id.tvIso)
        tvShutter = findViewById(R.id.tvShutter)
        tvZoom = findViewById(R.id.tvZoom)
        manualPanel = findViewById(R.id.manualPanel)
        aiToggleRow = findViewById(R.id.aiToggleRow)
        proFinishRow = findViewById(R.id.proFinishRow)
        seekIso = findViewById(R.id.seekIso)
        seekShutter = findViewById(R.id.seekShutter)
        seekZoom = findViewById(R.id.seekZoom)
        btnMode = findViewById(R.id.btnMode)
        btnPhotoVideo = findViewById(R.id.btnPhotoVideo)
        btnCapture = findViewById(R.id.btnCapture)
        btnFlash = findViewById(R.id.btnFlash)
        btnVoice = findViewById(R.id.btnVoice)
        btnAutoClick = findViewById(R.id.btnAutoClick)
        btnAutoZoom = findViewById(R.id.btnAutoZoom)
        btnBurst = findViewById(R.id.btnBurst)
        btnReframe = findViewById(R.id.btnReframe)
        btnBokeh = findViewById(R.id.btnBokeh)
        btnLook = findViewById(R.id.btnLook)
        btnDirector = findViewById(R.id.btnDirector)

        analysisExecutor = Executors.newSingleThreadExecutor()
        ioExecutor = Executors.newSingleThreadExecutor()

        val detectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.11f)
            .enableTracking()
            .build()
        faceDetector = FaceDetection.getClient(detectorOptions)
        photoEngine = AiPhotoEngine(this, faceDetector)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("hi", "IN"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("en", "IN"))
                }
                tts?.setSpeechRate(1.06f)
                ttsReady = true
            }
        }

        setupButtons()
        setupSliders()
        setupGestures()
        updateUIState()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hardwareReport = scanAllCameraHardware()
            startCamera()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnSpecs).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Phone Camera DNA Report")
                .setMessage(hardwareReport)
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<Button>(R.id.btnFlip).setOnClickListener {
            if (isRecording || isCapturingPhoto) {
                Toast.makeText(this, "Abhi processing/recording chal rahi hai", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            if (currentMode == CamMode.MANUAL) currentMode = CamMode.AI_PRO
            stablePerfectFrames = 0
            currentEvIndex = 0
            torchEnabled = false
            updateUIState()
            startCamera()
        }

        btnPhotoVideo.setOnClickListener {
            if (isCapturingPhoto) return@setOnClickListener
            if (isRecording) stopVideoRecording()
            isVideoMode = !isVideoMode
            if (isVideoMode && currentMode == CamMode.MANUAL) currentMode = CamMode.AI_PRO
            stablePerfectFrames = 0
            torchEnabled = false
            updateUIState()
            startCamera()
        }

        btnMode.setOnClickListener {
            currentMode = when (currentMode) {
                CamMode.AI_PRO -> CamMode.AUTO
                CamMode.AUTO -> {
                    if (!isVideoMode && isoRange != null && activeShutterSteps.isNotEmpty()) {
                        CamMode.MANUAL
                    } else {
                        CamMode.AI_PRO
                    }
                }
                CamMode.MANUAL -> CamMode.AI_PRO
            }
            stablePerfectFrames = 0
            updateUIState()
            applyManualOrAutoControl()
        }

        btnFlash.setOnClickListener {
            if (isVideoMode) {
                torchEnabled = !torchEnabled
                camera?.cameraControl?.enableTorch(torchEnabled)
                btnFlash.text = if (torchEnabled) "TORCH" else "FL:OFF"
            } else {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                    else -> ImageCapture.FLASH_MODE_AUTO
                }
                imageCapture?.flashMode = flashMode
                btnFlash.text = when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> "FL:A"
                    ImageCapture.FLASH_MODE_ON -> "FL:ON"
                    else -> "FL:OFF"
                }
            }
        }

        btnVoice.setOnClickListener {
            isVoiceEnabled = !isVoiceEnabled
            btnVoice.text = if (isVoiceEnabled) "VOICE:ON" else "VOICE:OFF"
            btnVoice.setTextColor(if (isVoiceEnabled) Color.parseColor("#00FFAA") else Color.GRAY)
        }

        btnAutoClick.setOnClickListener {
            isAutoClickEnabled = !isAutoClickEnabled
            btnAutoClick.text = if (isAutoClickEnabled) "AUTO:ON" else "AUTO:OFF"
            btnAutoClick.setTextColor(if (isAutoClickEnabled) Color.parseColor("#00FFAA") else Color.GRAY)
        }

        btnAutoZoom.setOnClickListener {
            isAutoZoomEnabled = !isAutoZoomEnabled
            btnAutoZoom.text = if (isAutoZoomEnabled) "ZOOM:ON" else "ZOOM:OFF"
            btnAutoZoom.setTextColor(if (isAutoZoomEnabled) Color.parseColor("#00FFAA") else Color.GRAY)
        }

        btnBurst.setOnClickListener {
            isBurstEnabled = !isBurstEnabled
            btnBurst.text = if (isBurstEnabled) "BURST:3x" else "BURST:OFF"
            btnBurst.setTextColor(if (isBurstEnabled) Color.parseColor("#FF80AB") else Color.parseColor("#AAAAAA"))
            val msg = if (isBurstEnabled) "Best-Shot 3x Burst ON (Zero Blur)" else "Burst OFF"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnReframe.setOnClickListener {
            val all = ReframeMode.values()
            currentReframe = all[(currentReframe.ordinal + 1) % all.size]
            btnReframe.text = currentReframe.label
            btnReframe.setTextColor(
                if (currentReframe == ReframeMode.OFF) Color.parseColor("#AAAAAA") else Color.parseColor("#80D8FF")
            )
            val msg = if (currentReframe == ReframeMode.OFF) {
                "Auto-Reframe OFF"
            } else {
                "iPhone Reframe ON: Auto-Straighten + ${currentReframe.label.removePrefix("REFR:")}"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnBokeh.setOnClickListener {
            val all = BokehLevel.values()
            currentBokeh = all[(currentBokeh.ordinal + 1) % all.size]
            btnBokeh.text = currentBokeh.label
            btnBokeh.setTextColor(
                if (currentBokeh == BokehLevel.OFF) Color.parseColor("#AAAAAA") else Color.parseColor("#B388FF")
            )
            val msg = if (currentBokeh == BokehLevel.OFF) {
                "DSLR Background Blur OFF"
            } else {
                "AI DSLR Bokeh ON (${currentBokeh.label.removePrefix("BLUR:")})"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnLook.setOnClickListener {
            val all = ProLook.values()
            currentLook = all[(currentLook.ordinal + 1) % all.size]
            btnLook.text = currentLook.label
            Toast.makeText(this, "Look: ${currentLook.label}", Toast.LENGTH_SHORT).show()
        }

        btnDirector.setOnClickListener {
            val savedKey = getGeminiKey()
            if (savedKey.isBlank()) showGeminiKeyDialog() else askGeminiCreativeDirector(savedKey)
        }
        btnDirector.setOnLongClickListener {
            showGeminiKeyDialog()
            true
        }

        btnCapture.setOnClickListener {
            if (isVideoMode) {
                if (isRecording) stopVideoRecording() else startVideoRecording()
            } else {
                triggerSmartCapture(isAuto = false)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zState = cam.cameraInfo.zoomState.value ?: return false
                val nextZoom = (zState.zoomRatio * detector.scaleFactor)
                    .coerceIn(zState.minZoomRatio, zState.maxZoomRatio.coerceAtMost(10f))
                cam.cameraControl.setZoomRatio(nextZoom)
                tvZoom.text = String.format(Locale.US, "Zoom: %.1fx", nextZoom)
                return true
            }
        })

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                val cam = camera ?: return@setOnTouchListener true
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                cam.cameraControl.startFocusAndMetering(action)
                overlayView.showFocusRing(event.x, event.y)
            }
            true
        }
    }

    private fun setupSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentMode == CamMode.MANUAL) applyManualOrAutoControl()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        seekIso.setOnSeekBarChangeListener(listener)
        seekShutter.setOnSeekBarChangeListener(listener)

        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
                val minZ = zoomState.minZoomRatio
                val maxZ = zoomState.maxZoomRatio.coerceAtMost(10f)
                val targetZoom = minZ + (maxZ - minZ) * (progress / 100f)
                camera?.cameraControl?.setZoomRatio(targetZoom)
                tvZoom.text = String.format(Locale.US, "Zoom: %.1fx", targetZoom)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun updateUIState() {
        btnPhotoVideo.text = if (isVideoMode) "VIDEO" else "PHOTO"
        proFinishRow.visibility = if (isVideoMode) View.GONE else View.VISIBLE
        btnAutoClick.visibility = if (isVideoMode) View.INVISIBLE else View.VISIBLE
        btnBurst.visibility = if (isVideoMode) View.INVISIBLE else View.VISIBLE

        if (isVideoMode) {
            btnCapture.text = if (isRecording) "STOP" else "REC"
            btnCapture.backgroundTintList = ColorStateList.valueOf(
                if (isRecording) Color.parseColor("#D50000") else Color.parseColor("#FF5252")
            )
            btnFlash.text = if (torchEnabled) "TORCH" else "FL:OFF"
        } else {
            btnCapture.text = if (isCapturingPhoto) "WAIT..." else "CAPTURE"
            btnCapture.backgroundTintList = null
            btnFlash.text = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> "FL:A"
                ImageCapture.FLASH_MODE_ON -> "FL:ON"
                else -> "FL:OFF"
            }
        }

        when (currentMode) {
            CamMode.AI_PRO -> {
                btnMode.text = "AI PRO"
                manualPanel.visibility = View.GONE
                aiToggleRow.visibility = View.VISIBLE
                tvCoach.visibility = View.VISIBLE
            }
            CamMode.AUTO -> {
                btnMode.text = "AUTO"
                manualPanel.visibility = View.GONE
                aiToggleRow.visibility = View.GONE
                tvCoach.visibility = View.GONE
                overlayView.updateOverlay(emptyList(), 0, 0f, false, isRecording)
            }
            CamMode.MANUAL -> {
                btnMode.text = "MANUAL"
                manualPanel.visibility = View.VISIBLE
                aiToggleRow.visibility = View.GONE
                tvCoach.visibility = View.GONE
                overlayView.updateOverlay(emptyList(), 0, 0f, false, isRecording)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeRecording?.stop()
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
        photoEngine.close()
        faceDetector.close()
        tts?.stop()
        tts?.shutdown()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            currentRollDeg = Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat()
            currentPitchZ = az
            val delta = abs(ax - lastAx) + abs(ay - lastAy) + abs(az - lastAz)
            shakeIntensity = shakeIntensity * 0.65f + delta * 0.35f
            lastAx = ax; lastAy = ay; lastAz = az
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                val boundCamera: Camera = if (isVideoMode) {
                    imageCapture = null
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(
                                Quality.FHD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                            )
                        )
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    try {
                        cameraProvider.bindToLifecycle(
                            this, cameraSelector, preview, videoCapture, imageAnalysis
                        )
                    } catch (_: Exception) {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                    }
                } else {
                    videoCapture = null
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setFlashMode(flashMode)
                        .build()
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, imageAnalysis
                    )
                }

                camera = boundCamera
                val c2Info = Camera2CameraInfo.from(boundCamera.cameraInfo)
                isoRange = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                exposureRange = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val oisModes = c2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                val eisModes = c2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                hasOis = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                hasEis = eisModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

                activeShutterSteps = exposureRange?.let { range ->
                    allShutterPresets.filter { it.second in range.lower..range.upper }
                } ?: emptyList()

                if (activeShutterSteps.isNotEmpty()) {
                    seekShutter.max = activeShutterSteps.size - 1
                    seekShutter.progress = activeShutterSteps.size / 2
                }

                seekZoom.progress = 0
                currentEvIndex = 0
                applyManualOrAutoControl()
            } catch (e: Exception) {
                Toast.makeText(this, "Camera start error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (currentMode != CamMode.AI_PRO || isCapturingPhoto) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val yBuffer = imageProxy.planes[0].buffer
        val limit = yBuffer.limit()
        var lumaSum = 0L
        var brightPx = 0
        var count = 0
        var idx = 0
        while (idx < limit) {
            val y = yBuffer.get(idx).toInt() and 0xFF
            lumaSum += y
            if (y > 235) brightPx++
            count++
            idx += 52
        }
        val avgLuma = if (count > 0) (lumaSum / count).toInt() else 128
        val brightRatio = if (count > 0) brightPx.toFloat() / count else 0f

        val rotation = imageProxy.imageInfo.rotationDegrees
        val uprightW = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val uprightH = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                evaluatePhotographerMind(faces, uprightW.toFloat(), uprightH.toFloat(), avgLuma, brightRatio)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun evaluatePhotographerMind(
        faces: List<Face>,
        imgW: Float,
        imgH: Float,
        avgLuma: Int,
        brightRatio: Float
    ) {
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        val roll = currentRollDeg
        val pitchZ = currentPitchZ
        val isShaking = shakeIntensity > 1.35f

        val normBoxes = faces.map { face ->
            val b = face.boundingBox
            val rawLeft = (b.left / imgW).coerceIn(0f, 1f)
            val rawRight = (b.right / imgW).coerceIn(0f, 1f)
            val top = (b.top / imgH).coerceIn(0f, 1f)
            val bottom = (b.bottom / imgH).coerceIn(0f, 1f)
            if (isFront) RectF(1f - rawRight, top, 1f - rawLeft, bottom)
            else RectF(rawLeft, top, rawRight, bottom)
        }

        val isNight = avgLuma < 52
        val isBacklit = faces.isNotEmpty() && brightRatio > 0.22f && avgLuma < 145
        val isHarshSun = brightRatio > 0.30f && avgLuma > 180

        val sceneTag = when {
            isVideoMode && isRecording -> "CINEMA REC"
            isVideoMode -> "AI VIDEO"
            isNight -> "NIGHT PRO"
            isBacklit -> "BACKLIT HDR"
            isHarshSun -> "SUNLIGHT"
            faces.size > 1 -> "GROUP (${faces.size})"
            faces.size == 1 && isFront -> "SELFIE PRO"
            faces.size == 1 -> "PORTRAIT"
            else -> "LANDSCAPE"
        }

        autoAdjustExposure(avgLuma, isBacklit, isHarshSun)
        applyHardwareAiProfile(isNight)

        val lightScore = when {
            isBacklit -> 15
            avgLuma in 70..190 -> 20
            avgLuma in 50..210 -> 14
            else -> 6
        }
        val horizonScore = when {
            abs(roll) <= 4.5f -> 15
            abs(roll) <= 9f -> 10
            else -> 3
        }
        val stabilityScore = if (!isShaking) 10 else 2

        if (faces.isEmpty()) {
            stablePerfectFrames = 0
            val sceneScore = ((lightScore / 20f) * 45f + (horizonScore / 15f) * 35f + (stabilityScore / 10f) * 20f).toInt()
            val tip = when {
                isShaking -> "Haath hil raha hai, phone steady rakho"
                abs(roll) > 7f -> "Phone seedha rakho (Horizon tedha hai)"
                isNight -> "Night Pro active: phone steady rakh kar click karo"
                isHarshSun -> "Light tez hai, highlights protect kar raha hu"
                else -> "Framing aur light balanced hai — ready!"
            }
            if (isVideoMode && isRecording && isShaking) speakTip("Haath steady rakho")

            runOnUiThread {
                overlayView.updateOverlay(emptyList(), sceneScore, roll, true, isRecording)
                tvBadge.text = "$sceneTag | $sceneScore% | EV:$currentEvIndex"
                if (System.currentTimeMillis() > lastStatusHoldUntil) {
                    tvCoach.text = tip
                    tvCoach.setTextColor(if (sceneScore >= 80) Color.parseColor("#00FFAA") else Color.WHITE)
                }
            }
            return
        }

        var bestIdx = 0
        var maxArea = 0f
        for (i in normBoxes.indices) {
            val area = normBoxes[i].width() * normBoxes[i].height()
            if (area > maxArea) {
                maxArea = area
                bestIdx = i
            }
        }
        val mainBox = normBoxes[bestIdx]
        val mainFace = faces[bestIdx]
        val cx = mainBox.centerX()
        val cy = mainBox.centerY()
        val fw = mainBox.width()

        autoFocusOnFace(cx, cy)
        if (isAutoZoomEnabled) autoAdjustZoom(faces.size, fw, isFront)

        var allEyesOpen = true
        for (f in faces) {
            val le = f.leftEyeOpenProbability ?: 1f
            val re = f.rightEyeOpenProbability ?: 1f
            if (le < 0.52f || re < 0.52f) {
                allEyesOpen = false
                break
            }
        }
        val smile = mainFace.smilingProbability ?: 0.5f

        var framingScore = 30
        var coachingTip = if (isVideoMode) "Cinematic tracking locked!" else "Perfect shot! Hold steady..."
        var voiceMsg = ""

        if (mainBox.top < 0.04f) {
            framingScore -= 14
            coachingTip = "Sir cut raha hai, camera thoda upar karo"
            voiceMsg = "Camera thoda upar karo"
        } else if (fw > 0.68f) {
            framingScore -= 14
            coachingTip = "Bahut paas ho, thoda peeche hato"
            voiceMsg = "Thoda peeche hato"
        } else if (fw < 0.13f && !isAutoZoomEnabled) {
            framingScore -= 12
            coachingTip = "Subject door hai, thoda paas aao"
            voiceMsg = "Thoda paas aao"
        } else if (cx < 0.24f) {
            framingScore -= 12
            coachingTip = "Subject left me hai, camera left ghumao"
            voiceMsg = "Thoda left ghumao"
        } else if (cx > 0.76f) {
            framingScore -= 12
            coachingTip = "Subject right me hai, camera right ghumao"
            voiceMsg = "Thoda right ghumao"
        } else if (cy > 0.63f) {
            framingScore -= 10
            coachingTip = "Chehra niche hai, camera thoda niche karo"
            voiceMsg = "Camera thoda niche karo"
        }

        var expressionScore = 25
        if (!allEyesOpen && !isVideoMode) {
            expressionScore -= 18
            coachingTip = if (faces.size > 1) "Kisi ki aankh band hai — aankhein kholo" else "Aankhein kholo"
            voiceMsg = "Aankhein kholo"
        } else if (isShaking) {
            coachingTip = "Phone hil raha hai, haath steady rakho"
            voiceMsg = "Haath steady rakho"
        } else if (isFront && pitchZ < -4.2f) {
            expressionScore -= 8
            coachingTip = "Selfie angle niche hai, phone eye-level par lao"
            voiceMsg = "Phone thoda upar lao"
        } else if (abs(roll) > 6.5f) {
            coachingTip = "Phone seedha rakho"
            voiceMsg = "Phone seedha rakho"
        } else if (isBacklit) {
            coachingTip = "Backlight detected — face exposure boosted"
        } else if (isNight) {
            coachingTip = "Night Pro active — haath bilkul steady rakho"
        } else if (smile > 0.55f && framingScore >= 26) {
            coachingTip = "Zabardast expression & framing!"
        }

        val totalScore = (framingScore + expressionScore + lightScore + horizonScore + stabilityScore).coerceIn(0, 100)
        val faceTag = when {
            !allEyesOpen && !isVideoMode -> "BLINK"
            isShaking -> "SHAKE"
            totalScore >= 85 -> "LOCKED $totalScore%"
            else -> "AI $totalScore%"
        }

        if (voiceMsg.isNotEmpty() && totalScore < 80 && !isRecording) {
            speakTip(voiceMsg)
        }

        if (!isVideoMode && isAutoClickEnabled && !isCapturingPhoto && totalScore >= 85 && allEyesOpen && !isShaking) {
            stablePerfectFrames++
            val now = System.currentTimeMillis()
            if (stablePerfectFrames >= 3 && now - lastAutoClickTime > 4500L) {
                lastAutoClickTime = now
                stablePerfectFrames = 0
                speakTip("Perfect!")
                runOnUiThread { triggerSmartCapture(isAuto = true) }
            }
        } else {
            stablePerfectFrames = 0
        }

        runOnUiThread {
            overlayView.updateOverlay(normBoxes, totalScore, roll, true, isRecording, faceTag)
            tvBadge.text = "$sceneTag | $totalScore% | EV:$currentEvIndex"
            if (System.currentTimeMillis() > lastStatusHoldUntil) {
                tvCoach.text = coachingTip
                tvCoach.setTextColor(
                    when {
                        totalScore >= 85 -> Color.parseColor("#00FFAA")
                        totalScore >= 65 -> Color.parseColor("#FFD54F")
                        else -> Color.parseColor("#FF8A80")
                    }
                )
            }
        }
    }

    // ==================== V4 SMART CAPTURE (BURST + REFRAME + DSLR BOKEH) ====================

    private fun triggerSmartCapture(isAuto: Boolean) {
        val capture = imageCapture ?: return
        if (isCapturingPhoto) return
        isCapturingPhoto = true
        updateUIState()

        val rollSnapshot = currentRollDeg
        val isFrontSnapshot = (lensFacing == CameraSelector.LENS_FACING_FRONT)
        val reframeSnapshot = currentReframe
        val bokehSnapshot = currentBokeh
        val lookSnapshot = currentLook
        val totalShots = if (isBurstEnabled) 3 else 1

        val needsPostAi = isBurstEnabled ||
            reframeSnapshot != ReframeMode.OFF ||
            bokehSnapshot != BokehLevel.OFF ||
            lookSnapshot != ProLook.NATURAL

        val prefix = if (isAuto) "AICAM_AUTO_" else "AICAM_V4_"
        val fileName = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        // Fast direct-to-gallery path if no V4 finishing is turned on
        if (!needsPostAi) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICam-Pro")
                }
            }
            val outOpts = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()

            capture.takePicture(
                outOpts,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        isCapturingPhoto = false
                        updateUIState()
                        val msg = if (isAuto) "AI Auto-Clicked!" else "Saved in Gallery/AICam-Pro"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                    override fun onError(exc: ImageCaptureException) {
                        isCapturingPhoto = false
                        updateUIState()
                        Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
            return
        }

        // V4 AI Pipeline path (Burst + Auto-Reframe + DSLR Bokeh + Pro Color Look)
        val capturedFiles = mutableListOf<File>()
        captureBurstStep(
            step = 0,
            total = totalShots,
            capturedFiles = capturedFiles,
            fileName = fileName,
            rollSnapshot = rollSnapshot,
            isFront = isFrontSnapshot,
            reframe = reframeSnapshot,
            bokeh = bokehSnapshot,
            look = lookSnapshot,
            isAuto = isAuto
        )
    }

    private fun captureBurstStep(
        step: Int,
        total: Int,
        capturedFiles: MutableList<File>,
        fileName: String,
        rollSnapshot: Float,
        isFront: Boolean,
        reframe: ReframeMode,
        bokeh: BokehLevel,
        look: ProLook,
        isAuto: Boolean
    ) {
        val capture = imageCapture
        if (capture == null || isFinishing || isDestroyed) {
            capturedFiles.forEach { it.delete() }
            isCapturingPhoto = false
            updateUIState()
            return
        }

        if (total > 1) {
            showCoachStatus("Burst ${step + 1}/$total... Hold steady!", "#FF80AB", 3000L)
        } else {
            showCoachStatus("Capturing...", "#00FFAA", 2000L)
        }

        val tempFile = File(cacheDir, "v4_shot_${System.currentTimeMillis()}_$step.jpg")
        val outOpts = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outOpts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedFiles.add(tempFile)
                    if (step + 1 < total) {
                        captureBurstStep(
                            step + 1, total, capturedFiles, fileName,
                            rollSnapshot, isFront, reframe, bokeh, look, isAuto
                        )
                    } else {
                        runV4PostProcessing(
                            capturedFiles, fileName, rollSnapshot, isFront, reframe, bokeh, look, isAuto
                        )
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    tempFile.delete()
                    if (capturedFiles.isNotEmpty()) {
                        runV4PostProcessing(
                            capturedFiles, fileName, rollSnapshot, isFront, reframe, bokeh, look, isAuto
                        )
                    } else {
                        isCapturingPhoto = false
                        updateUIState()
                        Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun runV4PostProcessing(
        files: List<File>,
        fileName: String,
        rollSnapshot: Float,
        isFront: Boolean,
        reframe: ReframeMode,
        bokeh: BokehLevel,
        look: ProLook,
        isAuto: Boolean
    ) {
        showCoachStatus("AI Pro Finishing (Reframe / Bokeh)...", "#80D8FF", 6000L)
        ioExecutor.execute {
            var winnerFile: File? = null
            try {
                val burstMsg = if (files.size > 1) {
                    val pick = photoEngine.pickBestBurstShot(files)
                    winnerFile = pick.winnerFile
                    "Best #${pick.winnerIndex}/${pick.totalCount} • "
                } else {
                    winnerFile = files.first()
                    ""
                }

                val summary = photoEngine.processAndSave(
                    sourceFile = winnerFile!!,
                    fileName = fileName,
                    rollDeg = rollSnapshot,
                    isFrontCamera = isFront,
                    reframe = reframe,
                    bokeh = bokeh,
                    look = look
                )

                val finalToast = (if (isAuto) "AI Auto! " else "") + burstMsg + summary
                runOnUiThread {
                    showCoachStatus(finalToast, "#00FFAA", 2800L)
                    Toast.makeText(this, finalToast, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "AI Finish error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                winnerFile?.delete()
                files.forEach { if (it.exists()) it.delete() }
                runOnUiThread {
                    isCapturingPhoto = false
                    updateUIState()
                }
            }
        }
    }

    private fun showCoachStatus(text: String, hexColor: String, holdMs: Long) {
        lastStatusHoldUntil = System.currentTimeMillis() + holdMs
        tvCoach.visibility = View.VISIBLE
        tvCoach.text = text
        tvCoach.setTextColor(Color.parseColor(hexColor))
    }

    // ==================== HARDWARE & AI AUTONOMOUS CONTROLS ====================

    private fun autoFocusOnFace(normX: Float, normY: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAutoFocusTime < 1500L) return
        lastAutoFocusTime = now
        runOnUiThread {
            val cam = camera ?: return@runOnUiThread
            val w = previewView.width.toFloat()
            val h = previewView.height.toFloat()
            if (w <= 0f || h <= 0f) return@runOnUiThread
            val point = previewView.meteringPointFactory.createPoint(normX * w, normY * h)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        }
    }

    private fun autoAdjustExposure(avgLuma: Int, isBacklit: Boolean, isHarshSun: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastEvAdjustTime < 1100L) return
        val cam = camera ?: return
        val evRange = cam.cameraInfo.exposureState.exposureCompensationRange
        if (evRange.lower == 0 && evRange.upper == 0) return

        val desiredDelta = when {
            isBacklit && currentEvIndex < 2 -> 1
            isHarshSun && currentEvIndex > -2 -> -1
            avgLuma < 62 -> 1
            avgLuma > 195 -> -1
            avgLuma in 105..155 && !isBacklit && currentEvIndex != 0 -> if (currentEvIndex > 0) -1 else 1
            else -> 0
        }
        if (desiredDelta == 0) return

        val nextEv = (currentEvIndex + desiredDelta).coerceIn(
            evRange.lower.coerceAtLeast(-6),
            evRange.upper.coerceAtMost(6)
        )
        if (nextEv != currentEvIndex) {
            currentEvIndex = nextEv
            lastEvAdjustTime = now
            cam.cameraControl.setExposureCompensationIndex(currentEvIndex)
        }
    }

    private fun autoAdjustZoom(faceCount: Int, faceWidth: Float, isFront: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastAutoZoomTime < 1400L) return
        val cam = camera ?: return
        val zState = cam.cameraInfo.zoomState.value ?: return
        val curZ = zState.zoomRatio
        val minZ = zState.minZoomRatio
        val maxZ = zState.maxZoomRatio.coerceAtMost(3.0f)

        val targetZoom = when {
            faceCount > 1 || faceWidth > 0.56f -> (curZ - 0.25f).coerceAtLeast(minZ)
            !isFront && faceCount == 1 && faceWidth < 0.20f -> (curZ + 0.25f).coerceAtMost(maxZ.coerceAtMost(2.0f))
            else -> curZ
        }
        if (abs(targetZoom - curZ) >= 0.1f) {
            lastAutoZoomTime = now
            runOnUiThread {
                cam.cameraControl.setZoomRatio(targetZoom)
                tvZoom.text = String.format(Locale.US, "AI Zoom: %.1fx", targetZoom)
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyHardwareAiProfile(isNight: Boolean) {
        val now = System.currentTimeMillis()
        if (isNight == lastNightFlag && now - lastHardwareProfileTime < 4000L) return
        lastNightFlag = isNight
        lastHardwareProfileTime = now

        val cam = camera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)
        val builder = CaptureRequestOptions.Builder()

        if (isNight) {
            builder.setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
            builder.setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CameraMetadata.EDGE_MODE_HIGH_QUALITY
            )
        }
        if (hasOis) {
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        } else if (hasEis) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }
        c2Control.setCaptureRequestOptions(builder.build())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualOrAutoControl() {
        val cam = camera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)

        if (currentMode != CamMode.MANUAL) {
            c2Control.setCaptureRequestOptions(CaptureRequestOptions.Builder().build())
            tvIso.text = "ISO: Auto"
            tvShutter.text = "Shutter: Auto"
            if (currentMode == CamMode.AUTO) {
                cam.cameraControl.setExposureCompensationIndex(0)
                tvBadge.text = if (isVideoMode) "AUTO VIDEO" else "AUTO PHOTO"
            }
            return
        }

        val iRange = isoRange ?: return
        if (activeShutterSteps.isEmpty()) return

        val maxUsableIso = iRange.upper.coerceAtMost(6400)
        val selectedIso = (iRange.lower.toLong() +
            ((maxUsableIso - iRange.lower).toLong() * seekIso.progress / 100L)).toInt()

        val shutterPair = activeShutterSteps[seekShutter.progress.coerceIn(0, activeShutterSteps.lastIndex)]
        tvIso.text = "ISO: $selectedIso"
        tvShutter.text = "Shutter: ${shutterPair.first}"
        tvBadge.text = "MANUAL | ISO $selectedIso | ${shutterPair.first}"

        val manualOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterPair.second)
            .build()
        c2Control.setCaptureRequestOptions(manualOptions)
    }

    // ==================== VIDEO RECORDING ====================

    @SuppressLint("MissingPermission")
    private fun startVideoRecording() {
        val vc = videoCapture ?: return
        val name = "AICAM_VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AICam-Pro")
            }
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        var pending = vc.output.prepareRecording(this, mediaStoreOutput)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    tvRecTimer.visibility = View.VISIBLE
                    tvRecTimer.text = "● REC 00:00"
                    updateUIState()
                }
                is VideoRecordEvent.Status -> {
                    val secs = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                    tvRecTimer.text = String.format(Locale.US, "● REC %02d:%02d", secs / 60, secs % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    activeRecording = null
                    tvRecTimer.visibility = View.GONE
                    updateUIState()
                    if (!event.hasError()) {
                        Toast.makeText(this, "Video Saved in Movies/AICam-Pro", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Video error: ${event.error}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun stopVideoRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    // ==================== OPTIONAL GEMINI CREATIVE DIRECTOR ====================

    private fun getGeminiKey(): String {
        return getSharedPreferences("aicam_prefs", Context.MODE_PRIVATE)
            .getString("gemini_api_key", "") ?: ""
    }

    private fun showGeminiKeyDialog() {
        val input = EditText(this).apply {
            hint = "AIzaSy... (Free key from aistudio.google.com)"
            setText(getGeminiKey())
        }
        AlertDialog.Builder(this)
            .setTitle("AI Creative Director (Optional)")
            .setMessage("aistudio.google.com se apni FREE Gemini API key paste karo. 'AI TIP' dabate hi pro photographer live scene dekh kar Hinglish me pose/light tip dega.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                getSharedPreferences("aicam_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("gemini_api_key", input.text.toString().trim())
                    .apply()
                Toast.makeText(this, "Key Saved! Ab 'AI TIP' dabao", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askGeminiCreativeDirector(apiKey: String) {
        val frame = previewView.bitmap
        if (frame == null) {
            Toast.makeText(this, "Camera preview ready nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        showCoachStatus("AI Director scene dekh raha hai...", "#80D8FF", 8000L)

        ioExecutor.execute {
            try {
                val targetW = 480
                val targetH = (frame.height * (targetW.toFloat() / frame.width)).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(frame, targetW, targetH, true)
                val baos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 78, baos)
                small.recycle()
                val base64Img = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                val prompt = "You are a world-class portrait and cinematic photographer coaching a user live. " +
                    "Look at this camera frame and give ONE super specific, practical tip in natural Hinglish " +
                    "(max 18 words) about lighting, angle, pose, or background to make this shot look pro."

                val body = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", prompt))
                                .put(
                                    JSONObject().put(
                                        "inline_data",
                                        JSONObject()
                                            .put("mime_type", "image/jpeg")
                                            .put("data", base64Img)
                                    )
                                )
                        )
                    )
                )

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val respText = stream.bufferedReader().use { it.readText() }
                conn.disconnect()

                if (conn.responseCode !in 200..299) throw IllegalStateException("API ${conn.responseCode}")

                val tip = JSONObject(respText)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                runOnUiThread {
                    showCoachStatus("Director: $tip", "#80D8FF", 7000L)
                    speakTip(tip)
                }
            } catch (e: Exception) {
                lastStatusHoldUntil = 0L
                runOnUiThread {
                    Toast.makeText(this, "AI Director error (${e.message}). Long-press AI TIP to edit key.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun speakTip(message: String) {
        if (!isVoiceEnabled || !ttsReady) return
        val now = System.currentTimeMillis()
        val cooldown = if (message == lastVoiceTip) 5000L else 2800L
        if (now - lastVoiceTime < cooldown) return
        lastVoiceTime = now
        lastVoiceTip = message
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ai_tip")
    }

    private fun scanAllCameraHardware(): String {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> "Rear"
                CameraMetadata.LENS_FACING_FRONT -> "Front"
                else -> "External"
            }
            val level = hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
            val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { "${it}mm" } ?: "?"
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString { "f/$it" } ?: "?"
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val hasRaw = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

            sb.append("Camera #$id ($facing)\n")
            sb.append("  Level: $level | RAW: ${if (hasRaw) "YES" else "NO"}\n")
            sb.append("  Lens: $focals ($apertures)\n")
            sb.append("  ISO: ${iso?.lower ?: "N/A"} - ${iso?.upper ?: "N/A"}\n")
            if (exp != null) {
                val minSec = 1_000_000_000L / exp.lower.coerceAtLeast(1L)
                val maxSec = exp.upper / 1_000_000_000.0
                sb.append(String.format(Locale.US, "  Shutter: 1/%ds to %.1fs\n\n", minSec, maxSec))
            } else {
                sb.append("  Shutter: Auto Only\n\n")
            }
        }
        return sb.toString()
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3 (Pro+RAW)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL (Manual OK)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }
}
