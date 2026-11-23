package com.aicam.pro

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Range
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
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

    private enum class CamMode { AI_PRO, MANUAL }

    private data class SceneMetrics(
        val avgLuma: Int,
        val centerLuma: Int,
        val highlightRatio: Float,
        val shadowRatio: Float
    )

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var topPillBar: LinearLayout
    private lateinit var tvBadge: TextView
    private lateinit var tvRecTimer: TextView
    private lateinit var tvAiStatus: TextView
    private lateinit var tvCoach: TextView
    private lateinit var tvIso: TextView
    private lateinit var tvShutter: TextView
    private lateinit var tvZoom: TextView
    private lateinit var bottomDeck: LinearLayout
    private lateinit var manualPanel: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var seekIso: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var seekZoom: SeekBar

    private lateinit var btnFlash: TextView
    private lateinit var btnApiKey: TextView
    private lateinit var btnSpecs: TextView
    private lateinit var btnFlip: TextView
    private lateinit var btnAiStudio: TextView
    private lateinit var btnReframe: TextView
    private lateinit var btnBokeh: TextView
    private lateinit var btnBurst: TextView
    private lateinit var btnLook: TextView
    private lateinit var btnVoice: TextView
    private lateinit var btnAutoClick: TextView
    private lateinit var btnAutoZoom: TextView
    private lateinit var btnDirector: TextView
    private lateinit var btnZ1: TextView
    private lateinit var btnZ15: TextView
    private lateinit var btnZ2: TextView
    private lateinit var btnZ3: TextView
    private lateinit var tabPhoto: TextView
    private lateinit var tabVideo: TextView
    private lateinit var tabPro: TextView
    private lateinit var btnGalleryWrap: FrameLayout
    private lateinit var ivGalleryThumb: ImageView
    private lateinit var btnCaptureOuter: FrameLayout
    private lateinit var vCaptureInner: View

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentMode = CamMode.AI_PRO
    private var currentLook = ProLook.NATURAL
    private var currentReframe = ReframeMode.OFF
    private var currentBokeh = BokehLevel.OFF
    private var isAiStudioEnabled = false
    private var isBurstEnabled = false
    private var isVideoMode = false
    private var isRecording = false
    private var isVoiceEnabled = true
    private var isAutoClickEnabled = true
    private var isAutoZoomEnabled = false // OFF by default so camera never randomly zooms!
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var torchEnabled = false

    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var activeShutterSteps: List<Pair<String, Long>> = emptyList()
    private var hasOis = false
    private var hasEis = false
    private var supportsHqNoiseReduction = false
    private var supportsHqEdge = false
    private var supportsHqAberration = false
    private var hardwareReport: String = "Scanning..."
    private var lastSavedUri: Uri? = null

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var ioExecutor: ExecutorService
    private lateinit var apiExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector
    private lateinit var objectDetector: ObjectDetector
    private lateinit var photoEngine: AiPhotoEngine
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile private var currentRollDeg = 0f
    @Volatile private var currentPitchZ = 0f
    @Volatile private var shakeIntensity = 0f
    @Volatile private var tripodSteadyTicks = 0
    private var lastAx = 0f
    private var lastAy = 0f
    private var lastAz = 0f

    private var lastVoiceTime = 0L
    private var lastVoiceTip = ""
    private var lastAutoClickTime = 0L
    private var lastObjectAutoClickSig = ""
    private var lastAutoFocusTime = 0L
    private var lastEvAdjustTime = 0L
    private var lastAutoZoomTime = 0L
    private var lastHardwareProfileTime = 0L
    private var lastStatusHoldUntil = 0L
    private var lastAppliedEnvTag = ""
    private var currentEvIndex = 0
    private var stablePerfectFrames = 0
    @Volatile private var isCapturingPhoto = false
    @Volatile private var pendingApiJobs = 0

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.parseColor("#CC09090B")
        setContentView(R.layout.activity_main)

        bindViews()
        applyGenZStaticStyling()

        analysisExecutor = Executors.newSingleThreadExecutor()
        ioExecutor = Executors.newSingleThreadExecutor()
        apiExecutor = Executors.newSingleThreadExecutor()

        val faceOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.13f)
            .enableTracking()
            .build()
        faceDetector = FaceDetection.getClient(faceOptions)

        val objectOptions = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
        objectDetector = ObjectDetection.getClient(objectOptions)

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

        isAiStudioEnabled = getGeminiKey().isNotBlank()

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

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        topPillBar = findViewById(R.id.topPillBar)
        tvBadge = findViewById(R.id.tvBadge)
        tvRecTimer = findViewById(R.id.tvRecTimer)
        tvAiStatus = findViewById(R.id.tvAiStatus)
        tvCoach = findViewById(R.id.tvCoach)
        tvIso = findViewById(R.id.tvIso)
        tvShutter = findViewById(R.id.tvShutter)
        tvZoom = findViewById(R.id.tvZoom)
        bottomDeck = findViewById(R.id.bottomDeck)
        manualPanel = findViewById(R.id.manualPanel)
        chipScroll = findViewById(R.id.chipScroll)
        seekIso = findViewById(R.id.seekIso)
        seekShutter = findViewById(R.id.seekShutter)
        seekZoom = findViewById(R.id.seekZoom)
        btnFlash = findViewById(R.id.btnFlash)
        btnApiKey = findViewById(R.id.btnApiKey)
        btnSpecs = findViewById(R.id.btnSpecs)
        btnFlip = findViewById(R.id.btnFlip)
        btnAiStudio = findViewById(R.id.btnAiStudio)
        btnReframe = findViewById(R.id.btnReframe)
        btnBokeh = findViewById(R.id.btnBokeh)
        btnBurst = findViewById(R.id.btnBurst)
        btnLook = findViewById(R.id.btnLook)
        btnVoice = findViewById(R.id.btnVoice)
        btnAutoClick = findViewById(R.id.btnAutoClick)
        btnAutoZoom = findViewById(R.id.btnAutoZoom)
        btnDirector = findViewById(R.id.btnDirector)
        btnZ1 = findViewById(R.id.btnZ1)
        btnZ15 = findViewById(R.id.btnZ15)
        btnZ2 = findViewById(R.id.btnZ2)
        btnZ3 = findViewById(R.id.btnZ3)
        tabPhoto = findViewById(R.id.tabPhoto)
        tabVideo = findViewById(R.id.tabVideo)
        tabPro = findViewById(R.id.tabPro)
        btnGalleryWrap = findViewById(R.id.btnGalleryWrap)
        ivGalleryThumb = findViewById(R.id.ivGalleryThumb)
        btnCaptureOuter = findViewById(R.id.btnCaptureOuter)
        vCaptureInner = findViewById(R.id.vCaptureInner)
    }

    // ==================== GEN-Z GLASSMORPHISM UI ENGINE ====================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makePill(
        fillHex: String,
        strokeHex: String = "#2AFFFFFF",
        radiusDp: Int = 22,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(Color.parseColor(fillHex))
        if (strokeDp > 0) setStroke(dp(strokeDp), Color.parseColor(strokeHex))
    }

    private fun applyGenZStaticStyling() {
        topPillBar.background = makePill("#A6101015", "#26FFFFFF", 24, 1)
        tvCoach.background = makePill("#BA0E0E12", "#22FFFFFF", 20, 1)
        tvRecTimer.background = makePill("#E6FF2E4D", "#44FFFFFF", 18, 1)
        tvAiStatus.background = makePill("#EECCFF00", "#FFFFFF", 18, 1)
        manualPanel.background = makePill("#CC121218", "#2BFFFFFF", 18, 1)

        bottomDeck.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#C809090B"), Color.parseColor("#F509090B"))
        )

        btnFlash.background = makePill("#22FFFFFF", "#1FFFFFFF", 15, 1)
        btnApiKey.background = makePill("#22FFFFFF", "#2AFFFFFF", 15, 1)
        btnSpecs.background = makePill("#18FFFFFF", "#18FFFFFF", 15, 1)
        btnFlip.background = makePill("#B316161C", "#33FFFFFF", 25, 1)
        btnGalleryWrap.background = makePill("#B316161C", "#44FFFFFF", 14, 1)
        ivGalleryThumb.clipToOutline = true

        listOf(btnZ1, btnZ15, btnZ2, btnZ3).forEach {
            it.background = makePill("#8014141A", "#22FFFFFF", 14, 1)
        }
    }

    private fun styleChip(view: TextView, active: Boolean, activeFill: String = "#CCFF00", activeText: String = "#09090B") {
        if (active) {
            view.background = makePill(activeFill, "#00000000", 18, 0)
            view.setTextColor(Color.parseColor(activeText))
        } else {
            view.background = makePill("#9914141B", "#26FFFFFF", 18, 1)
            view.setTextColor(Color.parseColor("#E4E4E7"))
        }
    }

    private fun styleShutterButton() {
        val ringColor = when {
            isRecording -> "#FF2E4D"
            isAiStudioEnabled && !isVideoMode -> "#CCFF00"
            else -> "#FFFFFF"
        }
        btnCaptureOuter.background = makePill("#26000000", ringColor, 38, 3)

        val lp = vCaptureInner.layoutParams as FrameLayout.LayoutParams
        when {
            isVideoMode && isRecording -> {
                lp.width = dp(28); lp.height = dp(28)
                vCaptureInner.background = makePill("#FF2E4D", "#00000000", 8, 0)
            }
            isVideoMode -> {
                lp.width = dp(58); lp.height = dp(58)
                vCaptureInner.background = makePill("#FF2E4D", "#00000000", 29, 0)
            }
            isCapturingPhoto -> {
                lp.width = dp(48); lp.height = dp(48)
                vCaptureInner.background = makePill("#CCFF00", "#00000000", 24, 0)
            }
            else -> {
                lp.width = dp(58); lp.height = dp(58)
                vCaptureInner.background = makePill("#FFFFFF", "#00000000", 29, 0)
            }
        }
        lp.gravity = Gravity.CENTER
        vCaptureInner.layoutParams = lp
    }

    private fun updateUIState() {
        val showPhotoChips = !isVideoMode
        btnAiStudio.visibility = if (showPhotoChips) View.VISIBLE else View.GONE
        btnReframe.visibility = if (showPhotoChips) View.VISIBLE else View.GONE
        btnBokeh.visibility = if (showPhotoChips) View.VISIBLE else View.GONE
        btnBurst.visibility = if (showPhotoChips) View.VISIBLE else View.GONE
        btnLook.visibility = if (showPhotoChips) View.VISIBLE else View.GONE
        btnAutoClick.visibility = if (showPhotoChips) View.VISIBLE else View.GONE

        manualPanel.visibility = if (currentMode == CamMode.MANUAL && !isVideoMode) View.VISIBLE else View.GONE
        chipScroll.visibility = if (currentMode == CamMode.MANUAL) View.GONE else View.VISIBLE
        tvCoach.visibility = if (currentMode == CamMode.MANUAL) View.GONE else View.VISIBLE

        if (isVideoMode) {
            btnFlash.text = if (torchEnabled) "⚡ON" else "⚡OFF"
        } else {
            btnFlash.text = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> "⚡A"
                ImageCapture.FLASH_MODE_ON -> "⚡ON"
                else -> "⚡OFF"
            }
        }

        val hasKey = getGeminiKey().isNotBlank()
        btnApiKey.text = if (hasKey) "✦ API ✓" else "✦ API"
        btnApiKey.setTextColor(if (hasKey) Color.parseColor("#CCFF00") else Color.WHITE)

        btnAiStudio.text = if (isAiStudioEnabled) "✦ AI STUDIO: ON" else "✦ AI STUDIO"
        styleChip(btnAiStudio, isAiStudioEnabled, "#CCFF00", "#09090B")

        btnReframe.text = currentReframe.label
        styleChip(btnReframe, currentReframe != ReframeMode.OFF, "#38BDF8", "#09090B")

        btnBokeh.text = currentBokeh.label
        styleChip(btnBokeh, currentBokeh != BokehLevel.OFF, "#C084FC", "#09090B")

        btnBurst.text = if (isBurstEnabled) "BURST 3x" else "BURST OFF"
        styleChip(btnBurst, isBurstEnabled, "#FB7185", "#09090B")

        btnLook.text = currentLook.label
        styleChip(btnLook, currentLook != ProLook.NATURAL, "#FACC15", "#09090B")

        styleChip(btnAutoClick, isAutoClickEnabled, "#27272A", "#CCFF00")
        styleChip(btnVoice, isVoiceEnabled, "#27272A", "#CCFF00")
        styleChip(btnAutoZoom, isAutoZoomEnabled, "#27272A", "#CCFF00")
        styleChip(btnDirector, false)

        styleChip(tabPhoto, !isVideoMode && currentMode == CamMode.AI_PRO, "#CCFF00", "#09090B")
        styleChip(tabVideo, isVideoMode, "#FF2E4D", "#FFFFFF")
        styleChip(tabPro, !isVideoMode && currentMode == CamMode.MANUAL, "#CCFF00", "#09090B")

        tvAiStatus.visibility = if (pendingApiJobs > 0) View.VISIBLE else View.GONE
        if (pendingApiJobs > 0) {
            tvAiStatus.text = if (pendingApiJobs == 1) "✦ AI STUDIO PROCESSING 4:5..." else "✦ AI STUDIO ($pendingApiJobs QUEUED)..."
        }

        styleShutterButton()
    }

    // ==================== BUTTON & GESTURE INTERACTIONS ====================

    private fun setupButtons() {
        btnSpecs.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Phone Camera DNA Report")
                .setMessage(hardwareReport)
                .setPositiveButton("OK", null)
                .show()
        }

        btnApiKey.setOnClickListener { showApiSettingsDialog() }

        btnFlip.setOnClickListener {
            if (isRecording || isCapturingPhoto) {
                Toast.makeText(this, "Abhi capture/recording chal rahi hai", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            if (currentMode == CamMode.MANUAL) currentMode = CamMode.AI_PRO
            stablePerfectFrames = 0
            lastObjectAutoClickSig = ""
            currentEvIndex = 0
            torchEnabled = false
            updateUIState()
            startCamera()
        }

        tabPhoto.setOnClickListener {
            if (isCapturingPhoto) return@setOnClickListener
            if (isRecording) stopVideoRecording()
            val needRestart = isVideoMode
            isVideoMode = false
            currentMode = CamMode.AI_PRO
            torchEnabled = false
            lastObjectAutoClickSig = ""
            updateUIState()
            if (needRestart) startCamera() else applyManualOrAutoControl()
        }

        tabVideo.setOnClickListener {
            if (isCapturingPhoto || isVideoMode) return@setOnClickListener
            isVideoMode = true
            currentMode = CamMode.AI_PRO
            torchEnabled = false
            updateUIState()
            startCamera()
        }

        tabPro.setOnClickListener {
            if (isCapturingPhoto) return@setOnClickListener
            if (isRecording) stopVideoRecording()
            if (isoRange == null || activeShutterSteps.isEmpty()) {
                Toast.makeText(this, "Ye lens manual ISO/Shutter support nahi karta", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val needRestart = isVideoMode
            isVideoMode = false
            currentMode = CamMode.MANUAL
            overlayView.updateOverlay(emptyList(), 0, 0f, false, false)
            updateUIState()
            if (needRestart) startCamera() else applyManualOrAutoControl()
        }

        btnFlash.setOnClickListener {
            if (isVideoMode) {
                torchEnabled = !torchEnabled
                camera?.cameraControl?.enableTorch(torchEnabled)
            } else {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                    else -> ImageCapture.FLASH_MODE_AUTO
                }
                imageCapture?.flashMode = flashMode
            }
            updateUIState()
        }

        btnAiStudio.setOnClickListener {
            if (getGeminiKey().isBlank()) {
                showApiSettingsDialog()
                return@setOnClickListener
            }
            isAiStudioEnabled = !isAiStudioEnabled
            updateUIState()
            val msg = if (isAiStudioEnabled) {
                "✦ AI Studio ON: Click hote hi strict-anchor 4:5 flagship processing chalegi"
            } else {
                "AI Studio OFF (Local AI DSLR Mode)"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        btnAiStudio.setOnLongClickListener {
            showApiSettingsDialog()
            true
        }

        btnReframe.setOnClickListener {
            val all = ReframeMode.values()
            currentReframe = all[(currentReframe.ordinal + 1) % all.size]
            updateUIState()
        }

        btnBokeh.setOnClickListener {
            val all = BokehLevel.values()
            currentBokeh = all[(currentBokeh.ordinal + 1) % all.size]
            updateUIState()
        }

        btnBurst.setOnClickListener {
            isBurstEnabled = !isBurstEnabled
            updateUIState()
        }

        btnLook.setOnClickListener {
            val all = ProLook.values()
            currentLook = all[(currentLook.ordinal + 1) % all.size]
            updateUIState()
        }

        btnVoice.setOnClickListener {
            isVoiceEnabled = !isVoiceEnabled
            updateUIState()
        }

        btnAutoClick.setOnClickListener {
            isAutoClickEnabled = !isAutoClickEnabled
            lastObjectAutoClickSig = ""
            updateUIState()
        }

        btnAutoZoom.setOnClickListener {
            isAutoZoomEnabled = !isAutoZoomEnabled
            updateUIState()
        }

        btnDirector.setOnClickListener {
            val key = getGeminiKey()
            if (key.isBlank()) showApiSettingsDialog() else askGeminiCreativeDirector(key)
        }

        btnZ1.setOnClickListener { setQuickZoom(1.0f) }
        btnZ15.setOnClickListener { setQuickZoom(1.5f) }
        btnZ2.setOnClickListener { setQuickZoom(2.0f) }
        btnZ3.setOnClickListener { setQuickZoom(3.0f) }

        btnGalleryWrap.setOnClickListener { openLastPhotoInGallery() }

        btnCaptureOuter.setOnClickListener {
            if (isVideoMode) {
                if (isRecording) stopVideoRecording() else startVideoRecording()
            } else {
                triggerSmartCapture(isAuto = false)
            }
        }
    }

    private fun setQuickZoom(ratio: Float) {
        val cam = camera ?: return
        val zState = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(zState.minZoomRatio, zState.maxZoomRatio.coerceAtMost(10f))
        cam.cameraControl.setZoomRatio(clamped)
        tvZoom.text = String.format(Locale.US, "%.1fx", clamped)
    }

    private fun openLastPhotoInGallery() {
        try {
            val intent = if (lastSavedUri != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(lastSavedUri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Gallery me Pictures/AICam-Pro check karo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateGalleryThumbnail(uri: Uri?) {
        if (uri == null) return
        lastSavedUri = uri
        ioExecutor.execute {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                if (bmp != null) {
                    runOnUiThread { ivGalleryThumb.setImageBitmap(bmp) }
                }
            } catch (_: Exception) {}
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
                tvZoom.text = String.format(Locale.US, "%.1fx", nextZoom)
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
                lastObjectAutoClickSig = "" // Allow fresh object auto-click after manual tap
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
                tvZoom.text = String.format(Locale.US, "%.1fx", targetZoom)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
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
        apiExecutor.shutdown()
        photoEngine.close()
        faceDetector.close()
        objectDetector.close()
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
            if (shakeIntensity < 0.16f) {
                tripodSteadyTicks = (tripodSteadyTicks + 1).coerceAtMost(100)
            } else {
                tripodSteadyTicks = 0
            }
            lastAx = ax; lastAy = ay; lastAz = az
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ==================== HARDWARE-ADAPTIVE CAMERA PIPELINE ====================

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
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
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
                val nrModes = c2Info.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                val edgeModes = c2Info.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                val abModes = c2Info.getCameraCharacteristic(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)

                hasOis = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                hasEis = eisModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
                supportsHqNoiseReduction = nrModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true
                supportsHqEdge = edgeModes?.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY) == true
                supportsHqAberration = abModes?.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY) == true

                activeShutterSteps = exposureRange?.let { range ->
                    allShutterPresets.filter { it.second in range.lower..range.upper }
                } ?: emptyList()

                if (activeShutterSteps.isNotEmpty()) {
                    seekShutter.max = activeShutterSteps.size - 1
                    seekShutter.progress = activeShutterSteps.size / 2
                }

                seekZoom.progress = 0
                currentEvIndex = 0
                lastAppliedEnvTag = ""
                applyManualOrAutoControl()
            } catch (e: Exception) {
                Toast.makeText(this, "Camera start error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ==================== DUAL HUMAN + OBJECT AUTONOMOUS DSLR BRAIN ====================

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

        val metrics = computeSceneMetrics(imageProxy)
        val rotation = imageProxy.imageInfo.rotationDegrees
        val uprightW = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val uprightH = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { rawFaces ->
                // Strict real-human validation so objects/posters NEVER trigger "Aankhein kholo"
                val validFaces = rawFaces.filter { f ->
                    val b = f.boundingBox
                    val fw = b.width().toFloat()
                    val fh = b.height().toFloat()
                    val aspect = if (fh > 0f) fw / fh else 0f
                    val hasEyes = f.leftEyeOpenProbability != null && f.rightEyeOpenProbability != null
                    aspect in 0.55f..1.65f && (fw / uprightW) >= 0.10f && hasEyes
                }

                if (validFaces.isNotEmpty()) {
                    evaluateHumanPortraitBrain(validFaces, uprightW.toFloat(), uprightH.toFloat(), metrics)
                    imageProxy.close()
                } else {
                    // Seamless switch to Object / Product / Scene DSLR Brain
                    objectDetector.process(inputImage)
                        .addOnSuccessListener { objects ->
                            evaluateObjectOrSceneBrain(objects, uprightW.toFloat(), uprightH.toFloat(), metrics)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }
            }
            .addOnFailureListener {
                imageProxy.close()
            }
            .addOnCanceledListener {
                imageProxy.close()
            }
    }

    private fun computeSceneMetrics(imageProxy: ImageProxy): SceneMetrics {
        val plane = imageProxy.planes[0]
        val yBuf = plane.buffer
        val w = imageProxy.width
        val h = imageProxy.height
        val rowStride = plane.rowStride

        var totalSum = 0L
        var totalCount = 0
        var centerSum = 0L
        var centerCount = 0
        var brightPx = 0
        var darkPx = 0

        val xMin = w / 4
        val xMax = (3 * w) / 4
        val yMin = h / 4
        val yMax = (3 * h) / 4

        var y = 0
        while (y < h) {
            val rowOff = y * rowStride
            var x = 0
            while (x < w) {
                val idx = rowOff + x
                if (idx < yBuf.limit()) {
                    val v = yBuf.get(idx).toInt() and 0xFF
                    totalSum += v
                    totalCount++
                    if (v > 236) brightPx++
                    if (v < 32) darkPx++
                    if (x in xMin..xMax && y in yMin..yMax) {
                        centerSum += v
                        centerCount++
                    }
                }
                x += 8
            }
            y += 8
        }

        val avgLuma = if (totalCount > 0) (totalSum / totalCount).toInt() else 128
        val centerLuma = if (centerCount > 0) (centerSum / centerCount).toInt() else avgLuma
        val hiRatio = if (totalCount > 0) brightPx.toFloat() / totalCount else 0f
        val shRatio = if (totalCount > 0) darkPx.toFloat() / totalCount else 0f
        return SceneMetrics(avgLuma, centerLuma, hiRatio, shRatio)
    }

    private fun evaluateHumanPortraitBrain(
        faces: List<Face>,
        imgW: Float,
        imgH: Float,
        m: SceneMetrics
    ) {
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        val roll = currentRollDeg
        val pitchZ = currentPitchZ
        val isOnTripod = tripodSteadyTicks >= 18
        val isShaking = shakeIntensity > 1.35f
        val show45 = !isVideoMode && (isAiStudioEnabled || currentReframe == ReframeMode.PORTRAIT_4_5)

        val normBoxes = faces.map { face ->
            val b = face.boundingBox
            val rawLeft = (b.left / imgW).coerceIn(0f, 1f)
            val rawRight = (b.right / imgW).coerceIn(0f, 1f)
            val top = (b.top / imgH).coerceIn(0f, 1f)
            val bottom = (b.bottom / imgH).coerceIn(0f, 1f)
            if (isFront) RectF(1f - rawRight, top, 1f - rawLeft, bottom)
            else RectF(rawLeft, top, rawRight, bottom)
        }

        val isNight = m.avgLuma < 52
        val isBacklit = m.highlightRatio > 0.20f && m.centerLuma < 120
        val isHarshSun = m.highlightRatio > 0.26f && m.avgLuma > 178

        val envTag = when {
            isVideoMode && isRecording -> "REC"
            isVideoMode -> "VIDEO"
            isOnTripod && isNight -> "TRIPOD NIGHT"
            isOnTripod -> "TRIPOD PRO"
            isNight -> "NIGHT DSLR"
            isBacklit -> "BACKLIT HDR"
            isHarshSun -> "SUN HDR"
            faces.size > 1 -> "GROUP ${faces.size}"
            isFront -> "SELFIE PRO"
            else -> "PORTRAIT"
        }

        autoAdjustEnvironmentExposure(m, isBacklit, isHarshSun, isNight)
        applyEnvironmentHardwareProfile(envTag, isNight, isOnTripod)

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

        autoFocusOnPoint(cx, cy)
        if (isAutoZoomEnabled) autoAdjustGentleZoom(faces.size, fw, isFront)

        // Accurate blink check (no false warnings on glasses/shadows)
        var allEyesOpen = true
        for (f in faces) {
            val le = f.leftEyeOpenProbability ?: 1f
            val re = f.rightEyeOpenProbability ?: 1f
            if ((le < 0.36f && re < 0.36f) || (le < 0.20f || re < 0.20f)) {
                allEyesOpen = false
                break
            }
        }
        val smile = mainFace.smilingProbability ?: 0.5f

        val lightScore = when {
            isBacklit -> 16
            m.centerLuma in 72..192 -> 20
            m.centerLuma in 50..210 -> 14
            else -> 7
        }
        val horizonScore = when {
            abs(roll) <= 4.5f -> 15
            abs(roll) <= 9f -> 10
            else -> 3
        }
        val stabilityScore = when {
            isOnTripod -> 10
            !isShaking -> 9
            else -> 2
        }

        var framingScore = 30
        var coachingTip = when {
            isVideoMode -> "Cinematic face tracking locked"
            isOnTripod -> "Tripod Pro Locked • Hold pose..."
            else -> "Perfect DSLR frame! Hold..."
        }
        var voiceMsg = ""

        if (mainBox.top < 0.03f) {
            framingScore -= 14
            coachingTip = "Sir cut raha hai, thoda peeche ya camera upar karo"
            voiceMsg = "Thoda peeche hato"
        } else if (fw > 0.70f) {
            framingScore -= 14
            coachingTip = "Bahut paas ho, thoda peeche hato"
            voiceMsg = "Thoda peeche hato"
        } else if (cx < 0.20f) {
            framingScore -= 12
            coachingTip = "Subject left me hai, center me aao"
            voiceMsg = "Thoda right aao"
        } else if (cx > 0.80f) {
            framingScore -= 12
            coachingTip = "Subject right me hai, center me aao"
            voiceMsg = "Thoda left aao"
        } else if (cy > 0.65f) {
            framingScore -= 10
            coachingTip = "Chehra niche hai, camera thoda niche karo"
            voiceMsg = "Camera thoda niche karo"
        }

        var expressionScore = 25
        if (!allEyesOpen && !isVideoMode) {
            expressionScore -= 18
            coachingTip = "Aankhein kholo"
            voiceMsg = "Aankhein kholo"
        } else if (isShaking) {
            coachingTip = "Haath steady rakho"
            voiceMsg = "Haath steady rakho"
        } else if (isFront && !isOnTripod && pitchZ < -4.2f) {
            expressionScore -= 8
            coachingTip = "Phone eye-level par thoda upar lao"
            voiceMsg = "Phone thoda upar lao"
        } else if (!isOnTripod && abs(roll) > 6.5f) {
            coachingTip = "Phone seedha rakho"
            voiceMsg = "Phone seedha rakho"
        } else if (isBacklit) {
            coachingTip = "Backlight HDR active — face brightness balanced"
        } else if (smile > 0.55f && framingScore >= 26) {
            coachingTip = if (isOnTripod) "Tripod Pro • Great pose! Clicking..." else "Fire framing! Hold..."
        }

        val totalScore = (framingScore + expressionScore + lightScore + horizonScore + stabilityScore).coerceIn(0, 100)
        val faceTag = when {
            !allEyesOpen && !isVideoMode -> "BLINK"
            isShaking -> "SHAKE"
            isOnTripod && totalScore >= 82 -> "TRIPOD $totalScore%"
            totalScore >= 85 -> "LOCKED $totalScore%"
            else -> "$totalScore%"
        }

        if (voiceMsg.isNotEmpty() && totalScore < 80 && !isRecording) speakTip(voiceMsg)

        // Hands-free Auto-Click (Works both handheld & on Tripod!)
        val triggerThreshold = if (isOnTripod) 80 else 85
        val cooldownMs = if (isOnTripod) 5500L else 4200L
        if (!isVideoMode && isAutoClickEnabled && !isCapturingPhoto && totalScore >= triggerThreshold && allEyesOpen && !isShaking) {
            stablePerfectFrames++
            val now = System.currentTimeMillis()
            if (stablePerfectFrames >= 3 && now - lastAutoClickTime > cooldownMs) {
                lastAutoClickTime = now
                stablePerfectFrames = 0
                speakTip("Perfect!")
                runOnUiThread { triggerSmartCapture(isAuto = true) }
            }
        } else {
            stablePerfectFrames = 0
        }

        runOnUiThread {
            overlayView.updateOverlay(normBoxes, totalScore, roll, true, isRecording, faceTag, show45, objectTarget = false)
            tvBadge.text = "● $envTag • $totalScore% • EV $currentEvIndex"
            if (System.currentTimeMillis() > lastStatusHoldUntil) {
                tvCoach.text = coachingTip
                tvCoach.setTextColor(
                    when {
                        totalScore >= 85 -> Color.parseColor("#CCFF00")
                        totalScore >= 65 -> Color.parseColor("#FFD166")
                        else -> Color.parseColor("#FF4D6D")
                    }
                )
            }
        }
    }

    private fun evaluateObjectOrSceneBrain(
        objects: List<DetectedObject>,
        imgW: Float,
        imgH: Float,
        m: SceneMetrics
    ) {
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        val roll = currentRollDeg
        val isOnTripod = tripodSteadyTicks >= 18
        val isShaking = shakeIntensity > 1.20f
        val show45 = !isVideoMode && (isAiStudioEnabled || currentReframe == ReframeMode.PORTRAIT_4_5)

        val isNight = m.avgLuma < 52
        val isBacklit = m.highlightRatio > 0.22f && m.centerLuma < 115
        val isHarshSun = m.highlightRatio > 0.28f && m.avgLuma > 180

        val normObjBoxes = objects.mapNotNull { obj ->
            val b = obj.boundingBox
            val rawLeft = (b.left / imgW).coerceIn(0f, 1f)
            val rawRight = (b.right / imgW).coerceIn(0f, 1f)
            val top = (b.top / imgH).coerceIn(0f, 1f)
            val bottom = (b.bottom / imgH).coerceIn(0f, 1f)
            val rect = if (isFront) RectF(1f - rawRight, top, 1f - rawLeft, bottom)
            else RectF(rawLeft, top, rawRight, bottom)
            // Ignore tiny noise or full-frame background boxes
            val area = rect.width() * rect.height()
            if (area in 0.03f..0.88f) rect else null
        }

        val hasObject = normObjBoxes.isNotEmpty()
        val mainObj = normObjBoxes.maxByOrNull { it.width() * it.height() }

        val envTag = when {
            isVideoMode && isRecording -> "REC"
            isVideoMode -> "VIDEO"
            isOnTripod && hasObject -> "TRIPOD OBJ"
            isOnTripod && isNight -> "TRIPOD NIGHT"
            isOnTripod -> "TRIPOD DSLR"
            hasObject -> "OBJECT PRO"
            isNight -> "NIGHT PRO"
            isHarshSun -> "SUN HDR"
            else -> "SCENE DSLR"
        }

        autoAdjustEnvironmentExposure(m, isBacklit, isHarshSun, isNight)
        applyEnvironmentHardwareProfile(envTag, isNight, isOnTripod)

        // Lock focus & metering directly on detected object (or frame center for landscape)
        if (mainObj != null) {
            autoFocusOnPoint(mainObj.centerX(), mainObj.centerY())
        } else {
            autoFocusOnPoint(0.5f, 0.5f)
        }

        val lightScore = when {
            m.centerLuma in 65..195 -> 35
            m.centerLuma in 45..215 -> 25
            else -> 14
        }
        val horizonScore = when {
            abs(roll) <= 4.5f -> 25
            abs(roll) <= 9f -> 18
            else -> 6
        }
        val stabilityScore = when {
            isOnTripod -> 25
            !isShaking -> 20
            else -> 5
        }
        val framingScore = if (mainObj != null) {
            val centered = mainObj.centerX() in 0.22f..0.78f && mainObj.centerY() in 0.20f..0.80f
            if (centered) 15 else 8
        } else {
            15
        }

        val totalScore = (lightScore + horizonScore + stabilityScore + framingScore).coerceIn(0, 100)

        // Build scene signature so stationary object auto-clicks ONCE and doesn't spam
        val objSig = if (mainObj != null) {
            "OBJ_${(mainObj.centerX() * 5).toInt()}_${(mainObj.centerY() * 5).toInt()}_${(mainObj.width() * 5).toInt()}"
        } else {
            "SCN_${m.avgLuma / 20}_${(roll / 10).toInt()}"
        }

        val tip = when {
            isShaking -> "Haath steady rakho"
            abs(roll) > 7f && !isOnTripod -> "Phone seedha rakho"
            hasObject && objSig == lastObjectAutoClickSig -> "Object Captured ✓ Naya object ya angle lao"
            isOnTripod && hasObject -> "Tripod Pro • Object focused! Auto-clicking..."
            hasObject -> "Object Locked • Steady rakho..."
            isOnTripod -> "Tripod DSLR Ready • Balanced light & horizon"
            isNight -> "Night Pro active — steady shoot karo"
            else -> "Scene balanced hai — ready to shoot!"
        }

        val boxTag = if (hasObject) "OBJECT $totalScore%" else ""

        // Smart Object / Tripod Scene Auto-Click (NO blink check ever!)
        val allowObjectAutoClick = !isVideoMode &&
            isAutoClickEnabled &&
            !isCapturingPhoto &&
            !isShaking &&
            totalScore >= 82 &&
            (hasObject || isOnTripod) &&
            objSig != lastObjectAutoClickSig

        if (allowObjectAutoClick) {
            stablePerfectFrames++
            val now = System.currentTimeMillis()
            if (stablePerfectFrames >= 4 && now - lastAutoClickTime > 4500L) {
                lastAutoClickTime = now
                lastObjectAutoClickSig = objSig
                stablePerfectFrames = 0
                speakTip("Captured")
                runOnUiThread { triggerSmartCapture(isAuto = true) }
            }
        } else if (isShaking) {
            stablePerfectFrames = 0
        }

        runOnUiThread {
            val drawBoxes = if (mainObj != null) listOf(mainObj) else emptyList()
            overlayView.updateOverlay(drawBoxes, totalScore, roll, true, isRecording, boxTag, show45, objectTarget = true)
            tvBadge.text = "● $envTag • $totalScore% • EV $currentEvIndex"
            if (System.currentTimeMillis() > lastStatusHoldUntil) {
                tvCoach.text = tip
                tvCoach.setTextColor(
                    when {
                        hasObject && totalScore >= 80 -> Color.parseColor("#38BDF8")
                        totalScore >= 80 -> Color.parseColor("#CCFF00")
                        else -> Color.WHITE
                    }
                )
            }
        }
    }

    // ==================== V5 SMART CAPTURE + BACKGROUND AI STUDIO API ====================

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
        val runStudioApi = isAiStudioEnabled && getGeminiKey().isNotBlank()
        val totalShots = if (isBurstEnabled) 3 else 1

        val prefix = if (isAuto) "AICAM_AUTO_" else "AICAM_"
        val fileName = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

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
            runStudioApi = runStudioApi,
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
        runStudioApi: Boolean,
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
            showCoachStatus("Burst ${step + 1}/$total • Hold steady", "#FB7185", 2500L)
        } else {
            showCoachStatus("Capturing DSLR HD...", "#CCFF00", 1800L)
        }

        val tempFile = File(cacheDir, "v5_shot_${System.currentTimeMillis()}_$step.jpg")
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
                            rollSnapshot, isFront, reframe, bokeh, look, runStudioApi, isAuto
                        )
                    } else {
                        runLocalAndStudioPipeline(
                            capturedFiles, fileName, rollSnapshot, isFront,
                            reframe, bokeh, look, runStudioApi, isAuto
                        )
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    tempFile.delete()
                    if (capturedFiles.isNotEmpty()) {
                        runLocalAndStudioPipeline(
                            capturedFiles, fileName, rollSnapshot, isFront,
                            reframe, bokeh, look, runStudioApi, isAuto
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

    private fun runLocalAndStudioPipeline(
        files: List<File>,
        fileName: String,
        rollSnapshot: Float,
        isFront: Boolean,
        reframe: ReframeMode,
        bokeh: BokehLevel,
        look: ProLook,
        runStudioApi: Boolean,
        isAuto: Boolean
    ) {
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

                // 1. Copy clean sensor shot for AI Studio background API BEFORE deleting temp
                if (runStudioApi && winnerFile != null) {
                    val apiCopy = File(cacheDir, "ai_queue_${System.currentTimeMillis()}.jpg")
                    winnerFile!!.copyTo(apiCopy, overwrite = true)
                    enqueueAiStudioJob(apiCopy, fileName)
                }

                // 2. Always save local DSLR photo immediately
                val localOutcome = photoEngine.processAndSave(
                    sourceFile = winnerFile!!,
                    fileName = fileName,
                    rollDeg = rollSnapshot,
                    isFrontCamera = isFront,
                    reframe = reframe,
                    bokeh = bokeh,
                    look = look
                )
                updateGalleryThumbnail(localOutcome.uri)

                val suffix = if (runStudioApi) " • Sent to AI Studio" else ""
                val msg = (if (isAuto) "AI Auto • " else "") + burstMsg + localOutcome.summary + suffix
                runOnUiThread {
                    showCoachStatus(msg, "#CCFF00", 2600L)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Save error: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun enqueueAiStudioJob(queuedFile: File, fileName: String) {
        val apiKey = getGeminiKey()
        val modelId = getStudioModelId()
        pendingApiJobs++
        runOnUiThread { updateUIState() }

        apiExecutor.execute {
            try {
                val result = photoEngine.enhanceWithAiStudioApi(
                    sourceFile = queuedFile,
                    fileName = fileName,
                    apiKey = apiKey,
                    modelId = modelId
                )
                if (result.success) {
                    updateGalleryThumbnail(result.uri)
                    runOnUiThread {
                        showCoachStatus(result.message, "#CCFF00", 4000L)
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        speakTip("AI Studio photo ready hai")
                    }
                } else {
                    runOnUiThread {
                        showCoachStatus("AI Studio: ${result.message}", "#FF4D6D", 4500L)
                        Toast.makeText(this, "AI Studio failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                queuedFile.delete()
                pendingApiJobs = (pendingApiJobs - 1).coerceAtLeast(0)
                runOnUiThread { updateUIState() }
            }
        }
    }

    private fun showCoachStatus(text: String, hexColor: String, holdMs: Long) {
        lastStatusHoldUntil = System.currentTimeMillis() + holdMs
        tvCoach.visibility = View.VISIBLE
        tvCoach.text = text
        tvCoach.setTextColor(Color.parseColor(hexColor))
    }

    // ==================== AUTONOMOUS ENVIRONMENT & HARDWARE ENGINE ====================

    private fun autoFocusOnPoint(normX: Float, normY: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAutoFocusTime < 1400L) return
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

    private fun autoAdjustEnvironmentExposure(
        m: SceneMetrics,
        isBacklit: Boolean,
        isHarshSun: Boolean,
        isNight: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (now - lastEvAdjustTime < 1000L) return
        val cam = camera ?: return
        val evRange = cam.cameraInfo.exposureState.exposureCompensationRange
        if (evRange.lower == 0 && evRange.upper == 0) return

        val desiredDelta = when {
            isHarshSun && currentEvIndex > -2 -> -1
            isBacklit && currentEvIndex < 2 -> 1
            isNight && currentEvIndex < 2 -> 1
            m.centerLuma < 60 && m.highlightRatio < 0.15f -> 1
            m.centerLuma > 195 || m.highlightRatio > 0.30f -> -1
            m.centerLuma in 100..160 && !isBacklit && !isNight && currentEvIndex != 0 ->
                if (currentEvIndex > 0) -1 else 1
            else -> 0
        }
        if (desiredDelta == 0) return

        val nextEv = (currentEvIndex + desiredDelta).coerceIn(
            evRange.lower.coerceAtLeast(-5),
            evRange.upper.coerceAtMost(5)
        )
        if (nextEv != currentEvIndex) {
            currentEvIndex = nextEv
            lastEvAdjustTime = now
            cam.cameraControl.setExposureCompensationIndex(currentEvIndex)
        }
    }

    private fun autoAdjustGentleZoom(faceCount: Int, faceWidth: Float, isFront: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastAutoZoomTime < 1800L) return
        val cam = camera ?: return
        val zState = cam.cameraInfo.zoomState.value ?: return
        val curZ = zState.zoomRatio
        val minZ = zState.minZoomRatio
        val maxGentleZ = zState.maxZoomRatio.coerceAtMost(1.6f)

        val targetZoom = when {
            faceCount > 1 || faceWidth > 0.55f -> (curZ - 0.2f).coerceAtLeast(minZ)
            !isFront && faceCount == 1 && faceWidth < 0.17f -> (curZ + 0.15f).coerceAtMost(maxGentleZ)
            else -> curZ
        }
        if (abs(targetZoom - curZ) >= 0.1f) {
            lastAutoZoomTime = now
            runOnUiThread {
                cam.cameraControl.setZoomRatio(targetZoom)
                tvZoom.text = String.format(Locale.US, "%.1fx", targetZoom)
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyEnvironmentHardwareProfile(envTag: String, isNight: Boolean, isOnTripod: Boolean) {
        val now = System.currentTimeMillis()
        val profileKey = "${isNight}_${isOnTripod}"
        if (profileKey == lastAppliedEnvTag && now - lastHardwareProfileTime < 4500L) return
        lastAppliedEnvTag = profileKey
        lastHardwareProfileTime = now

        val cam = camera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)
        val builder = CaptureRequestOptions.Builder()

        if (supportsHqNoiseReduction) {
            builder.setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
        }
        if (supportsHqEdge) {
            builder.setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CameraMetadata.EDGE_MODE_HIGH_QUALITY
            )
        }
        if (supportsHqAberration) {
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            )
        }
        if (hasOis) {
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        } else if (hasEis && !isOnTripod) {
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
            tvIso.text = "ISO AUTO"
            tvShutter.text = "SHUTTER AUTO"
            lastAppliedEnvTag = ""
            return
        }

        val iRange = isoRange ?: return
        if (activeShutterSteps.isEmpty()) return

        val maxUsableIso = iRange.upper.coerceAtMost(6400)
        val selectedIso = (iRange.lower.toLong() +
            ((maxUsableIso - iRange.lower).toLong() * seekIso.progress / 100L)).toInt()

        val shutterPair = activeShutterSteps[seekShutter.progress.coerceIn(0, activeShutterSteps.lastIndex)]
        tvIso.text = "ISO $selectedIso"
        tvShutter.text = "SHUTTER ${shutterPair.first}"
        tvBadge.text = "● MANUAL • ISO $selectedIso • ${shutterPair.first}"

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

    // ==================== API SETTINGS & LIVE DIRECTOR ====================

    private fun getGeminiKey(): String {
        return getSharedPreferences("aicam_prefs", Context.MODE_PRIVATE)
            .getString("gemini_api_key", "") ?: ""
    }

    private fun getStudioModelId(): String {
        return getSharedPreferences("aicam_prefs", Context.MODE_PRIVATE)
            .getString("studio_model_id", AiPhotoEngine.DEFAULT_STUDIO_MODEL)
            ?.ifBlank { AiPhotoEngine.DEFAULT_STUDIO_MODEL }
            ?: AiPhotoEngine.DEFAULT_STUDIO_MODEL
    }

    private fun showApiSettingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val lblKey = TextView(this).apply {
            text = "Gemini API Key (aistudio.google.com)"
            textSize = 12f
        }
        val etKey = EditText(this).apply {
            hint = "AIzaSy..."
            setText(getGeminiKey())
        }

        val lblModel = TextView(this).apply {
            text = "Image Studio Model ID"
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        val etModel = EditText(this).apply {
            hint = AiPhotoEngine.DEFAULT_STUDIO_MODEL
            setText(getStudioModelId())
        }

        box.addView(lblKey)
        box.addView(etKey)
        box.addView(lblModel)
        box.addView(etModel)

        AlertDialog.Builder(this)
            .setTitle("✦ AI Studio API Setup")
            .setMessage("Key save karte hi '✦ AI STUDIO' chip ON kar sakte ho. Har click ke baad tumhara strict-anchor 4:5 flagship prompt automatically apply hoga.")
            .setView(box)
            .setPositiveButton("Save & Enable") { _, _ ->
                val newKey = etKey.text.toString().trim()
                val newModel = etModel.text.toString().trim().ifBlank { AiPhotoEngine.DEFAULT_STUDIO_MODEL }
                getSharedPreferences("aicam_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("gemini_api_key", newKey)
                    .putString("studio_model_id", newModel)
                    .apply()
                isAiStudioEnabled = newKey.isNotBlank()
                updateUIState()
                Toast.makeText(this, if (isAiStudioEnabled) "✦ AI Studio Ready!" else "API Key Cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askGeminiCreativeDirector(apiKey: String) {
        val frame = previewView.bitmap ?: return
        showCoachStatus("✦ Director analyzing scene...", "#38BDF8", 8000L)

        apiExecutor.execute {
            try {
                val targetW = 480
                val targetH = (frame.height * (targetW.toFloat() / frame.width)).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(frame, targetW, targetH, true)
                val baos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 78, baos)
                small.recycle()
                val base64Img = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                val prompt = "You are a world-class portrait and product photographer coaching a user live. " +
                    "Look at this camera frame and give ONE super specific, practical tip in natural Hinglish " +
                    "(max 18 words) about lighting, angle, pose, or composition to make this shot look DSLR pro."

                val body = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", prompt))
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "image/jpeg")
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

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val respText = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()

                if (code !in 200..299) throw IllegalStateException("API $code")

                val tip = JSONObject(respText)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                runOnUiThread {
                    showCoachStatus("✦ $tip", "#38BDF8", 7000L)
                    speakTip(tip)
                }
            } catch (e: Exception) {
                lastStatusHoldUntil = 0L
                runOnUiThread {
                    Toast.makeText(this, "Live Tip error: ${e.message}", Toast.LENGTH_SHORT).show()
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
