package com.aicam.pro

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class ShotBlueprint(val label: String) {
    SOLO_PORTRAIT("PORTRAIT"),
    SELFIE_PRO("SELFIE"),
    GROUP_SHOT("GROUP"),
    OBJECT_HERO("OBJECT"),
    LANDSCAPE_SCENE("SCENE")
}

// iPhone 18 Pro Max Photographic Styles 3 + S26 Ultra ProVisual Looks
enum class PhotoStyle(val label: String) {
    TRUE_PRO("TRUE PRO"),
    S26_VIVID("S26 VIVID"),
    AMBER_FILM("FILM 3"),
    STUDIO_LIGHT("STUDIO"),
    NOIR_GRAIN("NOIR")
}

// iPhone 18 Pro Max Variable Aperture Control (f/1.48 - f/4.0 + AUTO)
enum class ApertureSetting(val label: String, val downScale: Int, val radius: Int, val passes: Int) {
    AUTO("f/AUTO", 0, 0, 0),
    F_148("f/1.48", 3, 4, 2),
    F_18("f/1.8", 2, 4, 2),
    F_28("f/2.8", 2, 3, 2),
    F_40("f/4.0", 2, 2, 2)
}

enum class CaptureStrategy {
    BURST_BEST,
    SMART_HDR_FUSION,
    NIGHTOGRAPHY_STACK
}

data class BurstPickResult(
    val winnerFile: File,
    val winnerIndex: Int,
    val totalCount: Int
)

data class AutopilotResult(
    val summary: String,
    val blueprint: ShotBlueprint,
    val uri: Uri?
)

data class AiStudioOutcome(
    val success: Boolean,
    val message: String,
    val uri: Uri?
)

class AiPhotoEngine(
    private val context: Context,
    private val faceDetector: FaceDetector
) {

    companion object {
        const val DEFAULT_STUDIO_MODEL = "gemini-2.5-flash-image"

        val DEFAULT_STUDIO_PROMPT = """
Use the reference photo as a strict anchor: identical face, identical pose, identical clothing, identical background, identical lighting direction and colour temperature. Slight natural facial asymmetry preserved. Do not change identity or pose.

Camera look: modern flagship smartphone main camera, 24mm equivalent, natural computational HDR, clean but believable phone photography. Focus locked on the nearest eye, eyelashes crisp, ears and background slightly softer. Natural computational depth with soft subject edges (no cut-out halo). Slight edge sharpness falloff, subtle micro-vignetting, minimal chromatic aberration.

Light: single dominant source consistent with the reference. Soft contact shadows where cloth meets skin. Gentle fill on the shadow side of the face. Light wrap on hair and shoulder edges. Small controlled specular highlights on forehead, nose tip and cheekbones. Filmic highlight roll-off. Deep but not crushed shadows.

Skin: visible pores, peach fuzz visible in rim light, natural T-zone sheen, lip creases, real under-eye texture, nasolabial folds, slight uneven skin tone, subtle subsurface scattering. Mild micro-contrast only — no uniform sharpening, no smoothing.

Eyes: catchlight shaped like the actual light source, wet corneal reflection, visible iris fibers, faint sclera capillaries, individual lashes. No glassy or dead eyes.

Hair and fabric: individual flyaway strands, strand-level highlights, true fabric weave, visible stitching, natural creases and thread fuzz.

Color: skin hue stays in the natural red-brown / wheatish range of the reference photo. No orange cast, no grey-yellow cast. Consistent white balance across the whole scene. Natural saturation. Slightly warm real-life tone. Mid-tones gently lifted so the face reads clearly on small phone screens.

Sensor: fine luminance grain in the shadows, no chroma noise.

Anatomy: five fingers on each hand, natural nails, correct proportions. No extra people or objects. No readable text in the background.

Output: 4:5 portrait (Instagram feed ready), 4K resolution, clean, sharp, ready to post directly. No text, no watermark, no border, no UI elements.

Avoid: waxy skin, plastic skin, airbrushed skin, beauty filter, dead eyes, HDR halo, over-sharpened look, oil-painting look, uncanny perfect symmetry, extra fingers, AI gloss, artificial glow, CGI, 3D render, oversaturated colors, identity drift, pose change, smoothed skin, flat lighting.
        """.trimIndent()
    }

    private val segmenter: Segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
    )

    private val singleObjectDetector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
    )

    fun close() {
        segmenter.close()
        singleObjectDetector.close()
    }

    // ==================== 1. ZERO-BLUR BEST-SHOT BURST PICKER ====================

    fun pickBestBurstShot(files: List<File>): BurstPickResult {
        if (files.size <= 1) return BurstPickResult(files.first(), 1, files.size)

        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (i in files.indices) {
            val f = files[i]
            val thumb = decodeOrientedBitmap(f, maxDim = 480) ?: continue
            try {
                val faces = detectValidFaces(thumb)
                val focusRect = if (faces.isNotEmpty()) {
                    val b = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!.boundingBox
                    clampRect(b, thumb.width, thumb.height)
                } else {
                    val obj = detectHeroObject(thumb)
                    if (obj != null) clampRect(obj, thumb.width, thumb.height)
                    else Rect(
                        (thumb.width * 0.2f).toInt().coerceAtLeast(1),
                        (thumb.height * 0.2f).toInt().coerceAtLeast(1),
                        (thumb.width * 0.8f).toInt().coerceAtMost(thumb.width - 1),
                        (thumb.height * 0.8f).toInt().coerceAtMost(thumb.height - 1)
                    )
                }

                val sharpness = computeSharpnessScore(thumb, focusRect)
                var expressionBonus = 0.0

                if (faces.isNotEmpty()) {
                    var allEyesOpen = true
                    var smileSum = 0.0
                    for (face in faces) {
                        val le = face.leftEyeOpenProbability ?: 0.85f
                        val re = face.rightEyeOpenProbability ?: 0.85f
                        if ((le < 0.36f && re < 0.36f) || le < 0.18f || re < 0.18f) {
                            allEyesOpen = false
                        }
                        smileSum += (face.smilingProbability ?: 0.3f)
                    }
                    expressionBonus += if (allEyesOpen) 50.0 else -70.0
                    expressionBonus += (smileSum / faces.size) * 18.0
                }

                val total = sharpness + expressionBonus
                if (total > bestScore) {
                    bestScore = total
                    bestIndex = i
                }
            } finally {
                thumb.recycle()
            }
        }

        for (i in files.indices) {
            if (i != bestIndex) files[i].delete()
        }
        return BurstPickResult(files[bestIndex], bestIndex + 1, files.size)
    }

    // ==================== 2. MULTI-FRAME FUSION (SMART HDR 5 & NIGHTOGRAPHY) ====================

    private fun fuseExposureBracketedFrames(files: List<File>, isNightStack: Boolean): Bitmap? {
        if (files.isEmpty()) return null
        if (files.size == 1) return decodeOrientedBitmap(files.first(), maxDim = 1800)

        // Mertens well-exposedness LUT (Gaussian centered at mid-gray 128)
        val wellExpLut = IntArray(256) { i ->
            val d = (i - 128) / 56.0
            (exp(-0.5 * d * d) * 255.0).toInt().coerceIn(8, 255)
        }

        var baseW = 0
        var baseH = 0
        var accR: IntArray? = null
        var accG: IntArray? = null
        var accB: IntArray? = null
        var accW: IntArray? = null
        var fusedCount = 0

        for (file in files) {
            val bmp = decodeOrientedBitmap(file, maxDim = 1600) ?: continue
            try {
                if (baseW == 0) {
                    baseW = bmp.width
                    baseH = bmp.height
                    val total = baseW * baseH
                    accR = IntArray(total)
                    accG = IntArray(total)
                    accB = IntArray(total)
                    accW = IntArray(total)
                }
                if (bmp.width != baseW || bmp.height != baseH) continue

                val total = baseW * baseH
                val px = IntArray(total)
                bmp.getPixels(px, 0, baseW, 0, 0, baseW, baseH)
                val rArr = accR!!
                val gArr = accG!!
                val bArr = accB!!
                val wArr = accW!!

                if (isNightStack) {
                    // S26 Ultra Nightography: multi-frame averaging kills sensor noise cleanly
                    for (i in 0 until total) {
                        val p = px[i]
                        rArr[i] += (p shr 16) and 0xFF
                        gArr[i] += (p shr 8) and 0xFF
                        bArr[i] += p and 0xFF
                        wArr[i] += 1
                    }
                } else {
                    // iPhone Smart HDR 5 Exposure Fusion (-2 EV / 0 EV / +2 EV pixel weighting)
                    for (i in 0 until total) {
                        val p = px[i]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val lum = (77 * r + 150 * g + 29 * b) shr 8
                        val wt = wellExpLut[lum]
                        rArr[i] += r * wt
                        gArr[i] += g * wt
                        bArr[i] += b * wt
                        wArr[i] += wt
                    }
                }
                fusedCount++
            } finally {
                bmp.recycle()
            }
        }

        if (fusedCount == 0 || accR == null || accG == null || accB == null || accW == null) {
            return decodeOrientedBitmap(files.first(), maxDim = 1800)
        }

        val total = baseW * baseH
        val outPx = IntArray(total)
        val rArr = accR
        val gArr = accG
        val bArr = accB
        val wArr = accW
        for (i in 0 until total) {
            val wt = wArr[i].coerceAtLeast(1)
            val r = (rArr[i] / wt).coerceIn(0, 255)
            val g = (gArr[i] / wt).coerceIn(0, 255)
            val b = (bArr[i] / wt).coerceIn(0, 255)
            outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(baseW, baseH, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, baseW, 0, 0, baseW, baseH)
        return out
    }

    // ==================== 3. COMPLETE FLAGSHIP AUTOPILOT PIPELINE ====================

    fun processFlagshipAndSave(
        files: List<File>,
        strategy: CaptureStrategy,
        fileName: String,
        rollDeg: Float,
        isFrontCamera: Boolean,
        style: PhotoStyle,
        aperture: ApertureSetting
    ): AutopilotResult {
        var toRecycle: Bitmap? = null
        val tags = mutableListOf<String>()

        try {
            var current: Bitmap = when (strategy) {
                CaptureStrategy.SMART_HDR_FUSION -> {
                    tags.add("HDR5")
                    fuseExposureBracketedFrames(files, isNightStack = false)
                }
                CaptureStrategy.NIGHTOGRAPHY_STACK -> {
                    tags.add("NightStack")
                    fuseExposureBracketedFrames(files, isNightStack = true)
                }
                CaptureStrategy.BURST_BEST -> {
                    val pick = pickBestBurstShot(files)
                    if (pick.totalCount > 1) tags.add("Sharp#${pick.winnerIndex}")
                    decodeOrientedBitmap(pick.winnerFile, maxDim = 1800)
                }
            } ?: throw IllegalStateException("Decode failed")
            toRecycle = current

            // Step 1: Horizon Auto-Straighten (for gentle tilts 1.2°..15°)
            if (abs(rollDeg) in 1.2f..15f) {
                val straight = straightenHorizon(current, rollDeg, isFrontCamera)
                if (straight !== current) {
                    current.recycle()
                    current = straight
                    toRecycle = current
                    tags.add("Level")
                }
            }

            // Step 2: Autonomous Subject Blueprint
            val faces = detectValidFaces(current)
            val heroObj = if (faces.isEmpty()) detectHeroObject(current) else null
            val blueprint = when {
                faces.size >= 2 -> ShotBlueprint.GROUP_SHOT
                faces.size == 1 && isFrontCamera -> ShotBlueprint.SELFIE_PRO
                faces.size == 1 -> ShotBlueprint.SOLO_PORTRAIT
                heroObj != null -> ShotBlueprint.OBJECT_HERO
                else -> ShotBlueprint.LANDSCAPE_SCENE
            }

            // Step 3: Tight Editorial Autonomous Reframe (Fixes excess ceiling/fan!)
            val reframed = applyEditorialReframe(current, blueprint, faces, heroObj)
            if (reframed !== current) {
                current.recycle()
                current = reframed
                toRecycle = current
                tags.add(if (blueprint == ShotBlueprint.GROUP_SHOT || blueprint == ShotBlueprint.LANDSCAPE_SCENE) "3:4" else "4:5")
            }

            // Re-detect subject on final crop for pinpoint lighting & Studio Key-Light
            val finalFaces = if (faces.isNotEmpty()) detectValidFaces(current) else emptyList()
            val curW = current.width
            val curH = current.height
            val subjectRect = when {
                finalFaces.isNotEmpty() -> {
                    val b = finalFaces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!.boundingBox
                    clampRect(b, curW, curH)
                }
                heroObj != null -> detectHeroObject(current)?.let { clampRect(it, curW, curH) }
                else -> null
            }

            // Step 4: Flagship Photonic / ProVisual Lighting + Highlight Roll-Off + Photographic Style 3
            val lit = applyFlagshipToneAndStyle(
                src = current,
                blueprint = blueprint,
                subjectRect = subjectRect,
                style = style,
                isNight = (strategy == CaptureStrategy.NIGHTOGRAPHY_STACK)
            )
            if (lit !== current) {
                current.recycle()
                current = lit
                toRecycle = current
            }
            tags.add(style.label)

            // Step 5: Variable Aperture Optical Depth (Clean Halo-Free Mask!)
            val effectiveAperture = resolveEffectiveAperture(aperture, blueprint)
            if (effectiveAperture != null) {
                val depth = applyCleanOpticalBokeh(
                    current,
                    downScale = effectiveAperture.downScale,
                    radius = effectiveAperture.radius,
                    passes = effectiveAperture.passes
                )
                if (depth !== current) {
                    current.recycle()
                    current = depth
                    toRecycle = current
                    tags.add(effectiveAperture.label)
                }
            }

            val uri = saveBitmapToGallery(current, "${fileName}_${blueprint.label}")
            return AutopilotResult(
                summary = "✦ ${blueprint.label} • ${tags.joinToString(" • ")}",
                blueprint = blueprint,
                uri = uri
            )
        } catch (_: Throwable) {
            val fallbackFile = files.firstOrNull { it.exists() }
            val uri = if (fallbackFile != null) saveFileDirectlyToGallery(fallbackFile, fileName) else null
            return AutopilotResult("Saved Original HD", ShotBlueprint.LANDSCAPE_SCENE, uri)
        } finally {
            toRecycle?.recycle()
        }
    }

    private fun resolveEffectiveAperture(
        userAperture: ApertureSetting,
        blueprint: ShotBlueprint
    ): ApertureSetting? {
        if (blueprint == ShotBlueprint.OBJECT_HERO || blueprint == ShotBlueprint.LANDSCAPE_SCENE) return null
        if (blueprint == ShotBlueprint.GROUP_SHOT && userAperture == ApertureSetting.AUTO) return null
        return when (userAperture) {
            ApertureSetting.AUTO -> when (blueprint) {
                ShotBlueprint.SOLO_PORTRAIT -> ApertureSetting.F_28
                ShotBlueprint.SELFIE_PRO -> ApertureSetting.F_40
                else -> null
            }
            else -> userAperture
        }
    }

    fun saveManualPhoto(sourceFile: File, fileName: String): Uri? {
        return saveFileDirectlyToGallery(sourceFile, fileName)
    }

    // ==================== STEP 1: HORIZON STRAIGHTENING ====================

    private fun straightenHorizon(src: Bitmap, rollDeg: Float, isFrontCamera: Boolean): Bitmap {
        val correctionAngle = if (isFrontCamera) rollDeg else -rollDeg
        val rad = Math.toRadians(abs(correctionAngle).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        val m = Matrix().apply { postRotate(correctionAngle) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)

        val a = src.width * 0.5f
        val b = src.height * 0.5f
        val k = min(a / (a * c + b * s), b / (a * s + b * c)) * 0.99f
        val safeW = (src.width * k).toInt().coerceIn(32, rotated.width)
        val safeH = (src.height * k).toInt().coerceIn(32, rotated.height)
        val startX = ((rotated.width - safeW) / 2).coerceAtLeast(0)
        val startY = ((rotated.height - safeH) / 2).coerceAtLeast(0)

        val clean = Bitmap.createBitmap(rotated, startX, startY, safeW, safeH)
        if (rotated !== clean) rotated.recycle()
        return clean
    }

    // ==================== STEP 3: EDITORIAL REFRAME (NO EXCESS CEILING/FAN!) ====================

    private fun applyEditorialReframe(
        src: Bitmap,
        blueprint: ShotBlueprint,
        faces: List<Face>,
        heroObj: Rect?
    ): Bitmap {
        val w = src.width
        val h = src.height
        val targetRatio = when (blueprint) {
            ShotBlueprint.SOLO_PORTRAIT, ShotBlueprint.SELFIE_PRO, ShotBlueprint.OBJECT_HERO -> 4f / 5f
            ShotBlueprint.GROUP_SHOT, ShotBlueprint.LANDSCAPE_SCENE -> 3f / 4f
        }

        val currentRatio = w.toFloat() / h.toFloat()
        var cropW: Int
        var cropH: Int
        if (currentRatio > targetRatio) {
            cropH = h
            cropW = (h * targetRatio).toInt().coerceIn(64, w)
        } else {
            cropW = w
            cropH = (w / targetRatio).toInt().coerceIn(64, h)
        }

        var cropLeft = (w - cropW) / 2
        var cropTop = (h - cropH) / 2

        when (blueprint) {
            ShotBlueprint.SOLO_PORTRAIT, ShotBlueprint.SELFIE_PRO -> {
                if (faces.isNotEmpty()) {
                    val fBox = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!.boundingBox
                    val faceWidthFrac = fBox.width().toFloat() / cropW.toFloat()
                    val emptyTopFrac = fBox.top.toFloat() / h.toFloat()

                    // If excess ceiling/fan is above head (like > 19% empty top) and face isn't huge,
                    // tighten crop proportionally so ceiling/fan gets cleanly cropped out!
                    if (emptyTopFrac > 0.19f && faceWidthFrac < 0.25f) {
                        val trimScale = when {
                            emptyTopFrac > 0.27f && faceWidthFrac < 0.20f -> 0.78f
                            emptyTopFrac > 0.22f -> 0.84f
                            else -> 0.90f
                        }
                        cropW = (cropW * trimScale).toInt().coerceAtLeast(160)
                        cropH = (cropH * trimScale).toInt().coerceAtLeast(200)
                    }

                    // Editorial headroom: only ~0.48–0.65x faceHeight above forehead (12–16% top margin)
                    val desiredLeft = (fBox.centerX() - cropW * 0.50f).toInt()
                    val idealTopByHeadroom = (fBox.top - fBox.height() * 0.58f).toInt()
                    val idealTopByThirds = (fBox.centerY() - cropH * 0.25f).toInt()
                    val desiredTop = max(idealTopByHeadroom, idealTopByThirds)
                    val safeHairTop = (fBox.top - fBox.height() * 0.42f).toInt()
                    val finalTop = min(desiredTop, safeHairTop)

                    cropLeft = desiredLeft.coerceIn(0, (w - cropW).coerceAtLeast(0))
                    cropTop = finalTop.coerceIn(0, (h - cropH).coerceAtLeast(0))
                }
            }

            ShotBlueprint.GROUP_SHOT -> {
                if (faces.isNotEmpty()) {
                    val union = RectF(
                        faces.minOf { it.boundingBox.left }.toFloat(),
                        faces.minOf { it.boundingBox.top }.toFloat(),
                        faces.maxOf { it.boundingBox.right }.toFloat(),
                        faces.maxOf { it.boundingBox.bottom }.toFloat()
                    )
                    val desiredLeft = (union.centerX() - cropW * 0.50f).toInt()
                    val avgFaceH = faces.map { it.boundingBox.height() }.average().toFloat()
                    val desiredTop = (union.top - avgFaceH * 0.65f).toInt()
                    cropLeft = desiredLeft.coerceIn(0, (w - cropW).coerceAtLeast(0))
                    cropTop = desiredTop.coerceIn(0, (h - cropH).coerceAtLeast(0))
                }
            }

            ShotBlueprint.OBJECT_HERO -> {
                if (heroObj != null) {
                    val objWFrac = heroObj.width().toFloat() / cropW.toFloat()
                    val objHFrac = heroObj.height().toFloat() / cropH.toFloat()
                    if (objWFrac < 0.36f && objHFrac < 0.36f) {
                        cropW = (cropW * 0.82f).toInt().coerceAtLeast(140)
                        cropH = (cropH * 0.82f).toInt().coerceAtLeast(175)
                    }
                    val desiredLeft = (heroObj.centerX() - cropW * 0.50f).toInt()
                    val desiredTop = (heroObj.centerY() - cropH * 0.47f).toInt()
                    cropLeft = desiredLeft.coerceIn(0, (w - cropW).coerceAtLeast(0))
                    cropTop = desiredTop.coerceIn(0, (h - cropH).coerceAtLeast(0))
                }
            }

            ShotBlueprint.LANDSCAPE_SCENE -> {
                val salCenter = findVisualSaliencyCenter(src)
                val desiredLeft = (salCenter.first * w - cropW * 0.5f).toInt()
                val desiredTop = (salCenter.second * h - cropH * 0.5f).toInt()
                cropLeft = desiredLeft.coerceIn(0, (w - cropW).coerceAtLeast(0))
                cropTop = desiredTop.coerceIn(0, (h - cropH).coerceAtLeast(0))
            }
        }

        if (cropLeft == 0 && cropTop == 0 && cropW == w && cropH == h) return src
        return Bitmap.createBitmap(src, cropLeft, cropTop, cropW, cropH)
    }

    private fun findVisualSaliencyCenter(bmp: Bitmap): Pair<Float, Float> {
        val sw = 96
        val sh = ((bmp.height.toFloat() / bmp.width) * sw).toInt().coerceIn(32, 160)
        val tiny = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        tiny.getPixels(px, 0, sw, 0, 0, sw, sh)
        tiny.recycle()

        val luma = IntArray(sw * sh) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
        }

        var sumX = 0.0; var sumY = 0.0; var sumW = 0.0
        for (y in 1 until sh - 1) {
            val row = y * sw
            for (x in 1 until sw - 1) {
                val gx = abs(luma[row + x + 1] - luma[row + x - 1])
                val gy = abs(luma[row + sw + x] - luma[row - sw + x])
                val weight = (gx + gy).toDouble()
                sumX += (x.toDouble() / sw) * weight
                sumY += (y.toDouble() / sh) * weight
                sumW += weight
            }
        }
        if (sumW < 1.0) return 0.5f to 0.5f
        return (sumX / sumW).toFloat().coerceIn(0.32f, 0.68f) to (sumY / sumW).toFloat().coerceIn(0.32f, 0.68f)
    }

    // ==================== STEP 4: PHOTONIC HDR + PROVISUAL + PHOTOGRAPHIC STYLES 3 ====================

    private fun applyFlagshipToneAndStyle(
        src: Bitmap,
        blueprint: ShotBlueprint,
        subjectRect: Rect?,
        style: PhotoStyle,
        isNight: Boolean
    ): Bitmap {
        val w = src.width
        val h = src.height
        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        var sampleCount = 0
        var subjSum = 0L; var subjCount = 0
        var brightPixels = 0

        val sLeft = subjectRect?.left ?: (w / 4)
        val sRight = subjectRect?.right ?: (3 * w / 4)
        val sTop = subjectRect?.top ?: (h / 4)
        val sBottom = subjectRect?.bottom ?: (3 * h / 4)

        var y = 0
        while (y < h) {
            val row = y * w
            var x = 0
            while (x < w) {
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val lum = (77 * r + 150 * g + 29 * b) shr 8
                hist[lum]++
                if (lum > 232) brightPixels++
                sumR += r; sumG += g; sumB += b
                sampleCount++
                if (x in sLeft..sRight && y in sTop..sBottom) {
                    subjSum += lum
                    subjCount++
                }
                x += 2
            }
            y += 2
        }

        if (sampleCount == 0) return src

        var acc = 0; var p02 = 0; var p50 = 128; var p98 = 255
        val t02 = (sampleCount * 0.02f).toInt()
        val t50 = (sampleCount * 0.50f).toInt()
        val t98 = (sampleCount * 0.98f).toInt()
        for (i in 0..255) {
            acc += hist[i]
            if (p02 == 0 && acc >= t02) p02 = i
            if (acc >= t50 && p50 == 128) p50 = i
            if (acc >= t98) { p98 = i; break }
        }

        val subjMean = if (subjCount > 0) (subjSum / subjCount).toFloat() else p50.toFloat()
        val hiFrac = brightPixels.toFloat() / sampleCount
        val isHuman = blueprint == ShotBlueprint.SOLO_PORTRAIT ||
            blueprint == ShotBlueprint.SELFIE_PRO ||
            blueprint == ShotBlueprint.GROUP_SHOT
        val isBacklit = subjMean < 105f && p98 > 230

        // 4A. True Neutral AWB (Eliminates indoor yellow/orange cast!)
        val avgR = (sumR / sampleCount).toFloat().coerceAtLeast(1f)
        val avgG = (sumG / sampleCount).toFloat().coerceAtLeast(1f)
        val avgB = (sumB / sampleCount).toFloat().coerceAtLeast(1f)
        val avgGray = (avgR + avgG + avgB) / 3f

        // Stronger correction if indoor bulb creates warm orange cast
        val wbStrength = if (avgR > avgB * 1.12f) 0.48f else 0.34f
        var gainR = (1f + (avgGray / avgR - 1f) * wbStrength).coerceIn(0.86f, 1.10f)
        var gainG = (1f + (avgGray / avgG - 1f) * wbStrength).coerceIn(0.91f, 1.08f)
        var gainB = (1f + (avgGray / avgB - 1f) * wbStrength).coerceIn(0.88f, 1.14f)

        // Photographic Style 3 White Balance Personality
        when (style) {
            PhotoStyle.AMBER_FILM -> { gainR *= 1.03f; gainB *= 0.95f }
            PhotoStyle.S26_VIVID -> { gainB *= 1.02f }
            else -> {}
        }

        // 4B. Highlight-Safe Filmic HDR Tone Curve (Fixes white-shirt clipping!)
        val targetMid = if (isHuman) 126f else 120f
        val refLuma = (if (isHuman) subjMean * 0.65f + p50 * 0.35f else p50.toFloat()).coerceIn(32f, 225f)
        val gamma = when {
            isNight -> 0.78f
            refLuma < targetMid -> (0.80f + 0.20f * (refLuma / targetMid)).coerceIn(0.78f, 0.99f)
            refLuma > targetMid + 24f -> (1.0f + ((refLuma - targetMid - 24f) / 210f)).coerceIn(1.0f, 1.18f)
            else -> 1.0f
        }
        // NEVER clip bright whites when bright clothing/sky is present!
        val blackClip = min(p02 * 0.55f, 14f) / 255f
        val whiteClip = if (hiFrac > 0.04f || p98 > 235) 1.0f else max(p98.toFloat(), 246f) / 255f
        val span = (whiteClip - blackClip).coerceAtLeast(0.50f)

        val shadowLift = when {
            isNight -> 0.40f
            isBacklit -> 0.36f
            refLuma < 92f -> 0.24f
            else -> 0.08f
        }
        val sCurveBlend = when (style) {
            PhotoStyle.S26_VIVID -> 0.28f
            PhotoStyle.NOIR_GRAIN -> 0.34f
            PhotoStyle.AMBER_FILM -> 0.22f
            else -> if (isHuman) 0.14f else 0.22f
        }

        val toneLut = IntArray(256)
        for (i in 0..255) {
            var t = ((i / 255f - blackClip) / span).coerceIn(0f, 1f)
            t = t.pow(gamma)
            // Lift shadows smoothly without lifting whites
            t = (t + (1f - t) * (1f - t) * t * shadowLift).coerceIn(0f, 1f)
            val s = t * t * (3f - 2f * t)
            t = t * (1f - sCurveBlend) + s * sCurveBlend
            // Filmic highlight roll-off shoulder above 0.74 (protects white shirts & sky!)
            if (t > 0.74f) {
                val excess = t - 0.74f
                t = 0.74f + (excess / (1f + excess * 1.45f)) * 0.96f
            }
            toneLut[i] = (t * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        // Studio Key-Light coordinates (for STUDIO style)
        val subjCx = ((sLeft + sRight) * 0.5f)
        val subjCy = ((sTop + sBottom) * 0.5f)
        val subjRad = max((sRight - sLeft), (sBottom - sTop)).toFloat().coerceAtLeast(w * 0.22f) * 1.35f
        val invSubjRad2 = 1f / (subjRad * subjRad)

        val invHalfW = 2f / w.toFloat()
        val invHalfH = 2f / h.toFloat()
        val vigStrength = when (style) {
            PhotoStyle.STUDIO_LIGHT -> 0.20f
            PhotoStyle.AMBER_FILM, PhotoStyle.NOIR_GRAIN -> 0.16f
            else -> if (blueprint == ShotBlueprint.LANDSCAPE_SCENE) 0.06f else 0.10f
        }
        val addGrain = (style == PhotoStyle.AMBER_FILM || style == PhotoStyle.NOIR_GRAIN)

        for (py in 0 until h) {
            val row = py * w
            val ny = py * invHalfH - 1f
            val ny2 = ny * ny
            val dyS = py - subjCy
            for (px in 0 until w) {
                val idx = row + px
                val p = pixels[idx]
                val r0 = (((p shr 16) and 0xFF) * gainR).toInt().coerceIn(0, 255)
                val g0 = (((p shr 8) and 0xFF) * gainG).toInt().coerceIn(0, 255)
                val b0 = ((p and 0xFF) * gainB).toInt().coerceIn(0, 255)

                val lum0 = (77 * r0 + 150 * g0 + 29 * b0) shr 8
                val lum1 = toneLut[lum0]
                val ratio = ((lum1 + 3f) / (lum0 + 3f)).coerceIn(0.62f, 1.90f)

                var r = r0 * ratio * 0.72f + toneLut[r0] * 0.28f
                var g = g0 * ratio * 0.72f + toneLut[g0] * 0.28f
                var b = b0 * ratio * 0.72f + toneLut[b0] * 0.28f

                // Studio Portrait Key-Light (gently brightens face & adds depth to background)
                if (style == PhotoStyle.STUDIO_LIGHT && isHuman) {
                    val dxS = px - subjCx
                    val dist2 = (dxS * dxS + dyS * dyS) * invSubjRad2
                    val keyBoost = if (dist2 < 1f) (1.09f - 0.09f * dist2) else (1.0f - min(0.16f, (dist2 - 1f) * 0.06f))
                    r *= keyBoost; g *= keyBoost; b *= keyBoost
                }

                // Natural Wheatish Skin-Hue Guard + Style Saturation
                if (style == PhotoStyle.NOIR_GRAIN) {
                    val mono = (0.30f * r + 0.59f * g + 0.11f * b)
                    r = mono; g = mono; b = mono
                } else {
                    val avgC = (r + g + b) / 3f
                    val isSkin = r > g && g > b && (r - b) > 12f && (r - g) < 88f
                    if (isSkin && (r - g) > 42f) {
                        // Gently tame excessive orange cast on skin while keeping healthy warmth
                        r = r * 0.965f + g * 0.035f
                    }
                    val satMult = when {
                        isSkin -> if (style == PhotoStyle.S26_VIVID) 1.01f else 0.96f
                        style == PhotoStyle.S26_VIVID -> 1.24f
                        style == PhotoStyle.AMBER_FILM -> 1.06f
                        else -> 1.11f
                    }
                    r = avgC + (r - avgC) * satMult
                    g = avgC + (g - avgC) * satMult
                    b = avgC + (b - avgC) * satMult
                }

                // Optical 24mm Micro-Vignette
                val nx = px * invHalfW - 1f
                val d2 = (nx * nx + ny2) * 0.5f
                val vig = 1f - vigStrength * d2
                r *= vig; g *= vig; b *= vig

                // Photographic Styles 3 Fine Luminance Film Grain (in shadows/midtones)
                if (addGrain) {
                    val hash = ((idx * 1103515245 + 12345) shr 16) and 0x1F
                    val grain = (hash - 16) * 0.55f
                    r += grain; g += grain; b += grain
                }

                pixels[idx] = (0xFF shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }

        // ProVisual / Photonic Micro-Sharpening (noise-gated so skin stays natural, eyes & cloth crisp)
        val sharpAmt = when {
            style == PhotoStyle.S26_VIVID -> 0.44f
            blueprint == ShotBlueprint.OBJECT_HERO || blueprint == ShotBlueprint.LANDSCAPE_SCENE -> 0.42f
            else -> 0.28f
        }
        applyNoiseGatedSharpen(pixels, w, h, sharpAmt)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun applyNoiseGatedSharpen(pixels: IntArray, w: Int, h: Int, amount: Float) {
        val luma = IntArray(w * h) { i ->
            val p = pixels[i]
            (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
        }
        for (y in 1 until h - 1) {
            val row = y * w; val prev = row - w; val next = row + w
            for (x in 1 until w - 1) {
                val idx = row + x
                val blur = (
                    luma[prev + x - 1] + luma[prev + x] + luma[prev + x + 1] +
                    luma[row + x - 1] + luma[idx] + luma[row + x + 1] +
                    luma[next + x - 1] + luma[next + x] + luma[next + x + 1]
                ) / 9
                val diff = luma[idx] - blur
                if (abs(diff) > 7) {
                    val delta = (diff * amount).toInt().coerceIn(-16, 16)
                    val p = pixels[idx]
                    val r = (((p shr 16) and 0xFF) + delta).coerceIn(0, 255)
                    val g = (((p shr 8) and 0xFF) + delta).coerceIn(0, 255)
                    val b = ((p and 0xFF) + delta).coerceIn(0, 255)
                    pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    // ==================== STEP 5: CLEAN HALO-FREE OPTICAL BOKEH ====================

    private fun applyCleanOpticalBokeh(src: Bitmap, downScale: Int, radius: Int, passes: Int): Bitmap {
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(src, 0)))
        } catch (_: Exception) {
            return src
        }

        val mw = mask.width
        val mh = mask.height
        val buf = mask.buffer
        buf.rewind()

        val alphaA = IntArray(mw * mh)
        var fgPixels = 0
        for (i in 0 until mw * mh) {
            val conf = buf.float
            if (conf > 0.62f) fgPixels++
            // Tighter foreground threshold removes stray sharp background patches between arm & waist!
            val t = ((conf - 0.42f) / 0.42f).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            alphaA[i] = (smooth * 255f).toInt().coerceIn(0, 255)
        }

        val fgRatio = fgPixels.toFloat() / (mw * mh).coerceAtLeast(1)
        if (fgRatio < 0.06f || fgRatio > 0.92f) return src

        // 2-Pass Smooth Morphological Cleanup (erodes stray elbow/waist patches, then feathers smoothly)
        val alphaB = IntArray(mw * mh)
        for (y in 0 until mh) {
            val y0 = (y - 1).coerceAtLeast(0) * mw
            val y1 = y * mw
            val y2 = (y + 1).coerceAtMost(mh - 1) * mw
            for (x in 0 until mw) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x2 = (x + 1).coerceAtMost(mw - 1)
                val minN = minOf(
                    alphaA[y0 + x], alphaA[y1 + x0], alphaA[y1 + x], alphaA[y1 + x2], alphaA[y2 + x]
                )
                // Gentle erosion blends away isolated background islands near sleeves
                alphaB[y1 + x] = (alphaA[y1 + x] * 3 + minN * 2) / 5
            }
        }

        val maskPixels = IntArray(mw * mh)
        for (y in 0 until mh) {
            val y0 = (y - 1).coerceAtLeast(0) * mw
            val y1 = y * mw
            val y2 = (y + 1).coerceAtMost(mh - 1) * mw
            for (x in 0 until mw) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x2 = (x + 1).coerceAtMost(mw - 1)
                val avgA = (
                    alphaB[y0 + x0] + alphaB[y0 + x] + alphaB[y0 + x2] +
                    alphaB[y1 + x0] + alphaB[y1 + x] * 2 + alphaB[y1 + x2] +
                    alphaB[y2 + x0] + alphaB[y2 + x] + alphaB[y2 + x2]
                ) / 10
                maskPixels[y1 + x] = (avgA shl 24) or 0x00FFFFFF
            }
        }

        val rawMaskBitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        rawMaskBitmap.setPixels(maskPixels, 0, mw, 0, 0, mw, mh)

        val blurredBg = createSmoothBlur(src, downScale, radius, passes)
        val fgLayer = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(fgLayer)
        fgCanvas.drawBitmap(src, 0f, 0f, null)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        fgCanvas.drawBitmap(rawMaskBitmap, null, Rect(0, 0, src.width, src.height), maskPaint)
        rawMaskBitmap.recycle()

        val outCanvas = Canvas(blurredBg)
        outCanvas.drawBitmap(fgLayer, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        fgLayer.recycle()
        return blurredBg
    }

    private fun createSmoothBlur(src: Bitmap, downScale: Int, radius: Int, passes: Int): Bitmap {
        val sw = (src.width / downScale).coerceAtLeast(16)
        val sh = (src.height / downScale).coerceAtLeast(16)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val pixels = IntArray(sw * sh)
        val temp = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        repeat(passes) {
            boxBlurH(pixels, temp, sw, sh, radius)
            boxBlurV(temp, pixels, sw, sh, radius)
        }
        small.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        val upscaled = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        small.recycle()
        return if (upscaled.isMutable) upscaled else upscaled.copy(Bitmap.Config.ARGB_8888, true).also {
            upscaled.recycle()
        }
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -r..r) {
                val px = src[row + i.coerceIn(0, w - 1)]
                rSum += (px shr 16) and 0xFF; gSum += (px shr 8) and 0xFF; bSum += px and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val addPx = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val subPx = src[row + (x - r).coerceAtLeast(0)]
                rSum += ((addPx shr 16) and 0xFF) - ((subPx shr 16) and 0xFF)
                gSum += ((addPx shr 8) and 0xFF) - ((subPx shr 8) and 0xFF)
                bSum += (addPx and 0xFF) - (subPx and 0xFF)
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -r..r) {
                val px = src[i.coerceIn(0, h - 1) * w + x]
                rSum += (px shr 16) and 0xFF; gSum += (px shr 8) and 0xFF; bSum += px and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val addPx = src[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val subPx = src[(y - r).coerceAtLeast(0) * w + x]
                rSum += ((addPx shr 16) and 0xFF) - ((subPx shr 16) and 0xFF)
                gSum += ((addPx shr 8) and 0xFF) - ((subPx shr 8) and 0xFF)
                bSum += (addPx and 0xFF) - (subPx and 0xFF)
            }
        }
    }

    // ==================== OPTIONAL BACKGROUND AI STUDIO API ====================

    fun enhanceWithAiStudioApi(
        sourceFile: File,
        fileName: String,
        apiKey: String,
        modelId: String
    ): AiStudioOutcome {
        val oriented = decodeOrientedBitmap(sourceFile, maxDim = 1536)
            ?: return AiStudioOutcome(false, "Image read failed", null)

        val base64Input = try {
            val baos = ByteArrayOutputStream()
            oriented.compress(Bitmap.CompressFormat.JPEG, 92, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } finally {
            oriented.recycle()
        }

        val cleanModel = modelId.ifBlank { DEFAULT_STUDIO_MODEL }.trim()
        val firstTry = callGeminiImageEndpoint(cleanModel, apiKey, base64Input, includeImageConfig = true)
        val result = if (!firstTry.success && firstTry.httpCode == 400) {
            callGeminiImageEndpoint(cleanModel, apiKey, base64Input, includeImageConfig = false)
        } else {
            firstTry
        }

        if (!result.success || result.imageBytes == null) {
            return AiStudioOutcome(false, result.errorMsg, null)
        }

        val savedUri = saveRawImageBytesToGallery(result.imageBytes, result.mimeType, "${fileName}_STUDIO")
        return if (savedUri != null) {
            AiStudioOutcome(true, "✦ AI Studio 4:5 Saved!", savedUri)
        } else {
            AiStudioOutcome(false, "Gallery save failed", null)
        }
    }

    private data class RawApiResult(
        val success: Boolean,
        val httpCode: Int,
        val imageBytes: ByteArray?,
        val mimeType: String,
        val errorMsg: String
    )

    private fun callGeminiImageEndpoint(
        modelId: String,
        apiKey: String,
        base64Input: String,
        includeImageConfig: Boolean
    ): RawApiResult {
        var conn: HttpURLConnection? = null
        return try {
            val genConfig = JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
            if (includeImageConfig) genConfig.put("imageConfig", JSONObject().put("aspectRatio", "4:5"))

            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", DEFAULT_STUDIO_PROMPT))
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject().put("mimeType", "image/jpeg").put("data", base64Input)
                                    )
                                )
                        )
                    )
                )
                .put("generationConfig", genConfig)

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 90000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val apiErr = try {
                    JSONObject(respText).optJSONObject("error")?.optString("message") ?: "HTTP $code"
                } catch (_: Exception) { "HTTP $code" }
                return RawApiResult(false, code, null, "image/jpeg", "API $code: ${apiErr.take(80)}")
            }

            val candidates = JSONObject(respText).optJSONArray("candidates")
                ?: return RawApiResult(false, code, null, "image/jpeg", "No candidates")
            if (candidates.length() == 0) return RawApiResult(false, code, null, "image/jpeg", "Empty response")

            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: return RawApiResult(false, code, null, "image/jpeg", "No parts")

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                if (inline != null) {
                    val dataStr = inline.optString("data", "")
                    val mime = inline.optString("mimeType", inline.optString("mime_type", "image/jpeg"))
                    if (dataStr.isNotEmpty()) {
                        return RawApiResult(true, code, Base64.decode(dataStr, Base64.DEFAULT), mime, "OK")
                    }
                }
            }
            RawApiResult(false, code, null, "image/jpeg", "Text-only response")
        } catch (e: Exception) {
            RawApiResult(false, -1, null, "image/jpeg", e.message ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    // ==================== HELPERS ====================

    private fun detectValidFaces(bmp: Bitmap): List<Face> {
        val raw = try {
            Tasks.await(faceDetector.process(InputImage.fromBitmap(bmp, 0)))
        } catch (_: Exception) {
            emptyList()
        }
        return raw.filter { f ->
            val b = f.boundingBox
            val aspect = if (b.height() > 0) b.width().toFloat() / b.height() else 0f
            val hasEyes = f.leftEyeOpenProbability != null && f.rightEyeOpenProbability != null
            aspect in 0.55f..1.65f && (b.width().toFloat() / bmp.width) >= 0.07f && hasEyes
        }
    }

    private fun detectHeroObject(bmp: Bitmap): Rect? {
        val objs = try {
            Tasks.await(singleObjectDetector.process(InputImage.fromBitmap(bmp, 0)))
        } catch (_: Exception) {
            emptyList()
        }
        val imgArea = (bmp.width * bmp.height).toFloat().coerceAtLeast(1f)
        return objs.map { it.boundingBox }
            .filter { b ->
                val frac = (b.width() * b.height()).toFloat() / imgArea
                frac in 0.04f..0.85f
            }
            .maxByOrNull { it.width() * it.height() }
    }

    private fun clampRect(r: Rect, w: Int, h: Int): Rect = Rect(
        r.left.coerceIn(1, (w - 2).coerceAtLeast(1)),
        r.top.coerceIn(1, (h - 2).coerceAtLeast(1)),
        r.right.coerceIn(2, (w - 1).coerceAtLeast(2)),
        r.bottom.coerceIn(2, (h - 1).coerceAtLeast(2))
    )

    private fun computeSharpnessScore(bmp: Bitmap, roi: Rect): Double {
        val rw = roi.width().coerceAtLeast(3)
        val rh = roi.height().coerceAtLeast(3)
        val pixels = IntArray(rw * rh)
        bmp.getPixels(pixels, 0, rw, roi.left, roi.top, rw, rh)
        val luma = IntArray(rw * rh) { idx ->
            val p = pixels[idx]
            (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
        }
        var sumSq = 0.0; var count = 0
        var y = 1
        while (y < rh - 1) {
            var x = 1; val row = y * rw
            while (x < rw - 1) {
                val lap = 4 * luma[row + x] - luma[row + x - 1] - luma[row + x + 1] -
                    luma[row - rw + x] - luma[row + rw + x]
                sumSq += (lap * lap).toDouble()
                count++
                x += 2
            }
            y += 2
        }
        return if (count > 0) (sumSq / count) / 25.0 else 0.0
    }

    private fun decodeOrientedBitmap(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return raw
        }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    private fun saveBitmapToGallery(bmp: Bitmap, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICam-Pro")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }
        return uri
    }

    private fun saveRawImageBytesToGallery(bytes: ByteArray, mimeType: String, fileName: String): Uri? {
        val safeMime = if (mimeType.startsWith("image/")) mimeType else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, safeMime)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICam-Pro")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
        }
        return uri
    }

    private fun saveFileDirectlyToGallery(file: File, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICam-Pro")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            }
        }
        return uri
    }
}
