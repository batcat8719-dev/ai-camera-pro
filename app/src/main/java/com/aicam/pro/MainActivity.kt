package com.aicam.pro

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
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
import android.util.Range
import android.view.View
import android.widget.Button
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
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
    private lateinit var tvCoach: TextView
    private lateinit var tvIso: TextView
    private lateinit var tvShutter: TextView
    private lateinit var tvZoom: TextView
    private lateinit var manualPanel: LinearLayout
    private lateinit var aiToggleRow: LinearLayout
    private lateinit var seekIso: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var seekZoom: SeekBar
    private lateinit var btnMode: Button
    private lateinit var btnVoice: Button
    private lateinit var btnAutoClick: Button

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentMode = CamMode.AI_PRO
    private var isVoiceEnabled = true
    private var isAutoClickEnabled = true

    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var activeShutterSteps: List<Pair<String, Long>> = emptyList()
    private var hardwareReport: String = "Scanning..."

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile private var currentRollDeg = 0f
    @Volatile private var currentPitchZ = 0f
    private var lastVoiceTime = 0L
    private var lastVoiceTip = ""
    private var lastAutoClickTime = 0L
    private var lastAutoFocusTime = 0L
    private var lastEvAdjustTime = 0L
    private var currentEvIndex = 0
    private var stablePerfectFrames = 0

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

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
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
        tvCoach = findViewById(R.id.tvCoach)
        tvIso = findViewById(R.id.tvIso)
        tvShutter = findViewById(R.id.tvShutter)
        tvZoom = findViewById(R.id.tvZoom)
        manualPanel = findViewById(R.id.manualPanel)
        aiToggleRow = findViewById(R.id.aiToggleRow)
        seekIso = findViewById(R.id.seekIso)
        seekShutter = findViewById(R.id.seekShutter)
        seekZoom = findViewById(R.id.seekZoom)
        btnMode = findViewById(R.id.btnMode)
        btnVoice = findViewById(R.id.btnVoice)
        btnAutoClick = findViewById(R.id.btnAutoClick)

        analysisExecutor = Executors.newSingleThreadExecutor()

        val detectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .build()
        faceDetector = FaceDetection.getClient(detectorOptions)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("hi", "IN"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("en", "IN"))
                }
                tts?.setSpeechRate(1.05f)
                ttsReady = true
            }
        }

        findViewById<Button>(R.id.btnSpecs).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Phone Camera DNA Report")
                .setMessage(hardwareReport)
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<Button>(R.id.btnFlip).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            if (currentMode == CamMode.MANUAL) currentMode = CamMode.AI_PRO
            stablePerfectFrames = 0
            currentEvIndex = 0
            updateModeUI()
            startCamera()
        }

        btnMode.setOnClickListener {
            currentMode = when (currentMode) {
                CamMode.AI_PRO -> CamMode.AUTO
                CamMode.AUTO -> {
                    if (isoRange != null && activeShutterSteps.isNotEmpty()) CamMode.MANUAL else CamMode.AI_PRO
                }
                CamMode.MANUAL -> CamMode.AI_PRO
            }
            stablePerfectFrames = 0
            updateModeUI()
            applyManualOrAutoControl()
        }

        btnVoice.setOnClickListener {
            isVoiceEnabled = !isVoiceEnabled
            btnVoice.text = if (isVoiceEnabled) "VOICE: ON" else "VOICE: OFF"
            btnVoice.setTextColor(if (isVoiceEnabled) Color.parseColor("#00FFAA") else Color.GRAY)
        }

        btnAutoClick.setOnClickListener {
            isAutoClickEnabled = !isAutoClickEnabled
            btnAutoClick.text = if (isAutoClickEnabled) "AUTO-CLICK: ON" else "AUTO-CLICK: OFF"
            btnAutoClick.setTextColor(if (isAutoClickEnabled) Color.parseColor("#00FFAA") else Color.GRAY)
        }

        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            takePhoto(isAuto = false)
        }

        setupSliders()
        updateModeUI()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hardwareReport = scanAllCameraHardware()
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
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
        analysisExecutor.shutdown()
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
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateModeUI() {
        when (currentMode) {
            CamMode.AI_PRO -> {
                btnMode.text = "MODE: AI PRO"
                manualPanel.visibility = View.GONE
                aiToggleRow.visibility = View.VISIBLE
                tvCoach.visibility = View.VISIBLE
            }
            CamMode.AUTO -> {
                btnMode.text = "MODE: AUTO"
                manualPanel.visibility = View.GONE
                aiToggleRow.visibility = View.GONE
                tvCoach.visibility = View.GONE
                overlayView.updateOverlay(emptyList(), 0, 0f, false)
            }
            CamMode.MANUAL -> {
                btnMode.text = "MODE: MANUAL"
                manualPanel.visibility = View.VISIBLE
                aiToggleRow.visibility = View.GONE
                tvCoach.visibility = View.GONE
                overlayView.updateOverlay(emptyList(), 0, 0f, false)
            }
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

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

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
                val boundCamera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalysis
                )
                camera = boundCamera

                val c2Info = Camera2CameraInfo.from(boundCamera.cameraInfo)
                isoRange = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                exposureRange = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

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
        if (currentMode != CamMode.AI_PRO) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        // Fast zero-allocation Y-plane brightness check (0..255)
        val yBuffer = imageProxy.planes[0].buffer
        val limit = yBuffer.limit()
        var lumaSum = 0L
        var lumaCount = 0
        var idx = 0
        while (idx < limit) {
            lumaSum += (yBuffer.get(idx).toInt() and 0xFF)
            lumaCount++
            idx += 48
        }
        val avgLuma = if (lumaCount > 0) (lumaSum / lumaCount).toInt() else 128

        val rotation = imageProxy.imageInfo.rotationDegrees
        val uprightW = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val uprightH = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                evaluatePhotographerMind(faces, uprightW.toFloat(), uprightH.toFloat(), avgLuma)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun evaluatePhotographerMind(
        faces: List<Face>,
        imgW: Float,
        imgH: Float,
        avgLuma: Int
    ) {
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        val roll = currentRollDeg
        val pitchZ = currentPitchZ

        val normBoxes = faces.map { face ->
            val b = face.boundingBox
            val rawLeft = (b.left / imgW).coerceIn(0f, 1f)
            val rawRight = (b.right / imgW).coerceIn(0f, 1f)
            val top = (b.top / imgH).coerceIn(0f, 1f)
            val bottom = (b.bottom / imgH).coerceIn(0f, 1f)
            if (isFront) {
                RectF(1f - rawRight, top, 1f - rawLeft, bottom)
            } else {
                RectF(rawLeft, top, rawRight, bottom)
            }
        }

        // Autonomous exposure tuning based on live scene brightness
        autoAdjustExposure(avgLuma)

        val lightScore = when {
            avgLuma in 75..185 -> 20
            avgLuma in 55..205 -> 14
            else -> 6
        }
        val horizonScore = when {
            abs(roll) <= 4.5f -> 15
            abs(roll) <= 9f -> 10
            else -> 3
        }

        if (faces.isEmpty()) {
            stablePerfectFrames = 0
            val sceneScore = ((lightScore / 20f) * 50f + (horizonScore / 15f) * 50f).toInt()
            val tip = when {
                abs(roll) > 7f -> "Phone seedha rakho (Horizon tedha hai)"
                avgLuma < 55 -> "Light kam hai, brightness ki taraf ghumo"
                avgLuma > 210 -> "Light zyada tez hai, angle thoda badlo"
                else -> "Scene balanced hai! Ready to shoot"
            }
            runOnUiThread {
                overlayView.updateOverlay(emptyList(), sceneScore, roll, true)
                tvBadge.text = "AI SCENE | Score: $sceneScore% | Light: $avgLuma"
                tvCoach.text = tip
                tvCoach.setTextColor(if (sceneScore >= 80) Color.parseColor("#00FFAA") else Color.WHITE)
            }
            return
        }

        // Primary subject = largest face in frame
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

        // Lock hardware autofocus & spot metering on subject's face
        autoFocusOnFace(cx, cy)

        // 1. Framing & Rule of Thirds (35 pts)
        var framingScore = 35
        var coachingTip = "Perfect shot! Hold steady..."
        var voiceMsg = ""

        val leftEye = mainFace.leftEyeOpenProbability ?: 1f
        val rightEye = mainFace.rightEyeOpenProbability ?: 1f
        val eyesOpen = leftEye > 0.55f && rightEye > 0.55f
        val smile = mainFace.smilingProbability ?: 0.5f

        if (mainBox.top < 0.04f) {
            framingScore -= 15
            coachingTip = "Sir cut raha hai, camera thoda upar karo"
            voiceMsg = "Camera thoda upar karo"
        } else if (fw > 0.68f) {
            framingScore -= 15
            coachingTip = "Bahut paas ho, thoda peeche hato"
            voiceMsg = "Thoda peeche hato"
        } else if (fw < 0.14f) {
            framingScore -= 12
            coachingTip = "Subject door hai, thoda paas aao ya zoom karo"
            voiceMsg = "Thoda paas aao"
        } else if (cx < 0.25f) {
            framingScore -= 12
            coachingTip = "Subject left me hai, camera left ghumao"
            voiceMsg = "Thoda left ghumao"
        } else if (cx > 0.75f) {
            framingScore -= 12
            coachingTip = "Subject right me hai, camera right ghumao"
            voiceMsg = "Thoda right ghumao"
        } else if (cy > 0.62f) {
            framingScore -= 10
            coachingTip = "Chehra niche hai, camera thoda niche jhukao"
            voiceMsg = "Camera thoda niche karo"
        }

        // 2. Eyes, Expression & Flattering Angle (30 pts)
        var expressionScore = 30
        if (!eyesOpen) {
            expressionScore -= 20
            coachingTip = "Aankhein band hain — aankhein kholo"
            voiceMsg = "Aankhein kholo"
        } else if (isFront && pitchZ < -4.0f) {
            expressionScore -= 10
            coachingTip = "Selfie angle niche hai, phone eye-level par lao"
            voiceMsg = "Phone thoda upar lao"
        } else if (abs(roll) > 6.5f) {
            coachingTip = "Phone seedha rakho"
            voiceMsg = "Phone seedha rakho"
        } else if (avgLuma < 55) {
            coachingTip = "Light kam hai, chehra light ki taraf karo"
            voiceMsg = "Chehra light ki taraf karo"
        } else if (smile > 0.55f && framingScore >= 30) {
            coachingTip = "Zabardast smile & framing! Hold..."
        }

        val totalScore = (framingScore + expressionScore + lightScore + horizonScore).coerceIn(0, 100)
        val groupLabel = if (faces.size > 1) "GROUP (${faces.size})" else if (isFront) "SELFIE" else "PORTRAIT"

        if (voiceMsg.isNotEmpty() && totalScore < 80) {
            speakTip(voiceMsg)
        }

        // Smart Auto-Click when score stays >= 85% and eyes are open
        if (isAutoClickEnabled && totalScore >= 85 && eyesOpen) {
            stablePerfectFrames++
            val now = System.currentTimeMillis()
            if (stablePerfectFrames >= 3 && now - lastAutoClickTime > 4000L) {
                lastAutoClickTime = now
                stablePerfectFrames = 0
                speakTip("Perfect!")
                runOnUiThread { takePhoto(isAuto = true) }
            }
        } else {
            stablePerfectFrames = 0
        }

        runOnUiThread {
            overlayView.updateOverlay(normBoxes, totalScore, roll, true)
            tvBadge.text = "AI $groupLabel | Score: $totalScore% | EV: $currentEvIndex"
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
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        }
    }

    private fun autoAdjustExposure(avgLuma: Int) {
        val now = System.currentTimeMillis()
        if (now - lastEvAdjustTime < 1200L) return
        val cam = camera ?: return
        val evRange = cam.cameraInfo.exposureState.exposureCompensationRange
        if (evRange.lower == 0 && evRange.upper == 0) return

        val desiredDelta = when {
            avgLuma < 65 -> 1
            avgLuma > 195 -> -1
            avgLuma in 105..155 && currentEvIndex != 0 -> if (currentEvIndex > 0) -1 else 1
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

    private fun speakTip(message: String) {
        if (!isVoiceEnabled || !ttsReady) return
        val now = System.currentTimeMillis()
        val cooldown = if (message == lastVoiceTip) 5000L else 3000L
        if (now - lastVoiceTime < cooldown) return
        lastVoiceTime = now
        lastVoiceTip = message
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ai_tip")
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualOrAutoControl() {
        val cam = camera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)

        if (currentMode != CamMode.MANUAL) {
            val autoOptions = CaptureRequestOptions.Builder().build()
            c2Control.setCaptureRequestOptions(autoOptions)
            tvIso.text = "ISO: Auto"
            tvShutter.text = "Shutter: Auto"
            if (currentMode == CamMode.AUTO) {
                cam.cameraControl.setExposureCompensationIndex(0)
                tvBadge.text = "AUTO MODE | ISO ${isoRange?.lower ?: "?"}-${isoRange?.upper ?: "?"}"
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

    private fun takePhoto(isAuto: Boolean) {
        val capture = imageCapture ?: return
        val prefix = if (isAuto) "AICAM_AUTO_" else "AICAM_"
        val name = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICam-Pro")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = if (isAuto) "AI Auto-Clicked! Saved in Gallery" else "Saved in Gallery/AICam-Pro"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
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
