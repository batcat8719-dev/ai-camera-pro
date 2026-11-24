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

    // ==================== 2. FULL AUTONOMOUS PHOTOGRAPHER PIPELINE ====================

    fun processAutopilotAndSave(
        sourceFile: File,
        fileName: String,
        rollDeg: Float,
        isFrontCamera: Boolean
    ): AutopilotResult {
        var toRecycle: Bitmap? = null
        val tags = mutableListOf<String>()

        try {
            var current: Bitmap = decodeOrientedBitmap(sourceFile, maxDim = 1800)
                ?: throw IllegalStateException("Decode failed")
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

            // Step 2: Analyze Subject & Choose Photographer Blueprint
            val faces = detectValidFaces(current)
            val heroObj = if (faces.isEmpty()) detectHeroObject(current) else null
            val blueprint = when {
                faces.size >= 2 -> ShotBlueprint.GROUP_SHOT
                faces.size == 1 && isFrontCamera -> ShotBlueprint.SELFIE_PRO
                faces.size == 1 -> ShotBlueprint.SOLO_PORTRAIT
                heroObj != null -> ShotBlueprint.OBJECT_HERO
                else -> ShotBlueprint.LANDSCAPE_SCENE
            }

            // Step 3: Autonomous Framing
            val reframed = applyAutonomousReframe(current, blueprint, faces, heroObj)
            if (reframed !== current) {
                current.recycle()
                current = reframed
                toRecycle = current
                tags.add(if (blueprint == ShotBlueprint.GROUP_SHOT || blueprint == ShotBlueprint.LANDSCAPE_SCENE) "3:4" else "4:5")
            }

            // Re-detect primary subject box on final cropped frame
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

            // Step 4: Real Adaptive Computational Lighting + HDR + AWB + Micro-Sharpening
            val lit = applyComputationalLighting(current, blueprint, subjectRect)
            if (lit !== current) {
                current.recycle()
                current = lit
                toRecycle = current
            }
            tags.add("Pro HDR")

            // Step 5: Selective Subtle Optical Depth (ONLY for Solo Portrait & Selfie)
            if (blueprint == ShotBlueprint.SOLO_PORTRAIT) {
                val depth = applySubtleDepthBokeh(current, downScale = 2, radius = 3, passes = 2)
                if (depth !== current) {
                    current.recycle()
                    current = depth
                    toRecycle = current
                    tags.add("f/2.8")
                }
            } else if (blueprint == ShotBlueprint.SELFIE_PRO) {
                val depth = applySubtleDepthBokeh(current, downScale = 2, radius = 2, passes = 2)
                if (depth !== current) {
                    current.recycle()
                    current = depth
                    toRecycle = current
                    tags.add("f/4.0")
                }
            }

            val uri = saveBitmapToGallery(current, "${fileName}_${blueprint.label}")
            return AutopilotResult(
                summary = "✦ ${blueprint.label} • ${tags.joinToString(" • ")}",
                blueprint = blueprint,
                uri = uri
            )
        } catch (_: Throwable) {
            val uri = saveFileDirectlyToGallery(sourceFile, fileName)
            return AutopilotResult("Saved Original HD", ShotBlueprint.LANDSCAPE_SCENE, uri)
        } finally {
            toRecycle?.recycle()
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

    // ==================== STEP 3: AUTONOMOUS SMART REFRAME ====================

    private fun applyAutonomousReframe(
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
                    val faceWidthRatio = fBox.width().toFloat() / cropW.toFloat()
                    if (faceWidthRatio < 0.19f && blueprint == ShotBlueprint.SOLO_PORTRAIT) {
                        cropW = (cropW * 0.85f).toInt().coerceAtLeast(128)
                        cropH = (cropH * 0.85f).toInt().coerceAtLeast(128)
                    }

                    val desiredLeft = (fBox.centerX() - cropW * 0.50f).toInt()
                    var desiredTop = (fBox.centerY() - cropH * 0.32f).toInt()
                    val minHeadroomTop = (fBox.top - fBox.height() * 0.58f).toInt()
                    if (desiredTop > minHeadroomTop) desiredTop = minHeadroomTop

                    cropLeft = desiredLeft.coerceIn(0, (w - cropW).coerceAtLeast(0))
                    cropTop = desiredTop.coerceIn(0, (h - cropH).coerceAtLeast(0))
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
                    var desiredTop = (union.centerY() - cropH * 0.36f).toInt()
                    val avgFaceH = faces.map { it.boundingBox.height() }.average().toFloat()
                    val minHeadroomTop = (union.top - avgFaceH * 0.62f).toInt()
                    if (desiredTop > minHeadroomTop) desiredTop = minHeadroomTop

                    cropLeft = desiredLeft.coerceIn(0, (w - cropW).coerceAtLeast(0))
                    cropTop = desiredTop.coerceIn(0, (h - cropH).coerceAtLeast(0))
                }
            }

            ShotBlueprint.OBJECT_HERO -> {
                if (heroObj != null) {
                    val objWFrac = heroObj.width().toFloat() / cropW.toFloat()
                    val objHFrac = heroObj.height().toFloat() / cropH.toFloat()
                    if (objWFrac < 0.38f && objHFrac < 0.38f) {
                        cropW = (cropW * 0.82f).toInt().coerceAtLeast(128)
                        cropH = (cropH * 0.82f).toInt().coerceAtLeast(128)
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

        var sumX = 0.0
        var sumY = 0.0
        var sumW = 0.0
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
        val cx = (sumX / sumW).toFloat().coerceIn(0.32f, 0.68f)
        val cy = (sumY / sumW).toFloat().coerceIn(0.32f, 0.68f)
        return cx to cy
    }

    // ==================== STEP 4: ADAPTIVE COMPUTATIONAL LIGHTING & HDR ====================

    private fun applyComputationalLighting(
        src: Bitmap,
        blueprint: ShotBlueprint,
        subjectRect: Rect?
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

        var acc = 0
        var p02 = 0
        var p50 = 128
        var p98 = 255
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
        val isHuman = blueprint == ShotBlueprint.SOLO_PORTRAIT ||
            blueprint == ShotBlueprint.SELFIE_PRO ||
            blueprint == ShotBlueprint.GROUP_SHOT
        val isBacklit = subjMean < 108f && p98 > 232

        val avgR = (sumR / sampleCount).toFloat().coerceAtLeast(1f)
        val avgG = (sumG / sampleCount).toFloat().coerceAtLeast(1f)
        val avgB = (sumB / sampleCount).toFloat().coerceAtLeast(1f)
        val avgGray = (avgR + avgG + avgB) / 3f

        var gainR = (1f + (avgGray / avgR - 1f) * 0.30f).coerceIn(0.91f, 1.11f)
        val gainG = (1f + (avgGray / avgG - 1f) * 0.30f).coerceIn(0.93f, 1.08f)
        var gainB = (1f + (avgGray / avgB - 1f) * 0.30f).coerceIn(0.91f, 1.11f)

        if (isHuman) {
            gainR *= 1.02f
            gainB *= 0.985f
        }

        val targetMid = if (isHuman) 136f else 124f
        val refLuma = (if (isHuman) subjMean * 0.72f + p50 * 0.28f else p50.toFloat()).coerceIn(32f, 225f)
        val gamma = when {
            refLuma < targetMid -> (0.72f + 0.28f * (refLuma / targetMid)).coerceIn(0.68f, 0.98f)
            refLuma > targetMid + 22f -> (1.0f + ((refLuma - targetMid - 22f) / 220f)).coerceIn(1.0f, 1.16f)
            else -> 1.0f
        }
        val blackClip = min(p02 * 0.65f, 18f) / 255f
        val whiteClip = max(p98.toFloat(), 240f) / 255f
        val span = (whiteClip - blackClip).coerceAtLeast(0.45f)
        val shadowLift = if (isBacklit) 0.42f else if (refLuma < 95f) 0.28f else 0.10f
        val sCurveBlend = if (isHuman) 0.16f else 0.24f

        val toneLut = IntArray(256)
        for (i in 0..255) {
            var t = ((i / 255f - blackClip) / span).coerceIn(0f, 1f)
            t = t.pow(gamma)
            t = (t + (1f - t) * (1f - t) * t * shadowLift).coerceIn(0f, 1f)
            val s = t * t * (3f - 2f * t)
            t = t * (1f - sCurveBlend) + s * sCurveBlend
            if (t > 0.86f) {
                t = 0.86f + (t - 0.86f) * 0.88f
            }
            toneLut[i] = (t * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        val invHalfW = 2f / w.toFloat()
        val invHalfH = 2f / h.toFloat()
        val vigStrength = if (blueprint == ShotBlueprint.LANDSCAPE_SCENE) 0.07f else 0.11f

        for (py in 0 until h) {
            val row = py * w
            val ny = py * invHalfH - 1f
            val ny2 = ny * ny
            for (px in 0 until w) {
                val idx = row + px
                val p = pixels[idx]
                val r0 = (((p shr 16) and 0xFF) * gainR).toInt().coerceIn(0, 255)
                val g0 = (((p shr 8) and 0xFF) * gainG).toInt().coerceIn(0, 255)
                val b0 = ((p and 0xFF) * gainB).toInt().coerceIn(0, 255)

                val lum0 = (77 * r0 + 150 * g0 + 29 * b0) shr 8
                val lum1 = toneLut[lum0]
                val ratio = ((lum1 + 2f) / (lum0 + 2f)).coerceIn(0.60f, 2.05f)

                var r = (r0 * ratio * 0.65f + toneLut[r0] * 0.35f)
                var g = (g0 * ratio * 0.65f + toneLut[g0] * 0.35f)
                var b = (b0 * ratio * 0.65f + toneLut[b0] * 0.35f)

                val avgC = (r + g + b) / 3f
                val isSkin = r > g && g > b && (r - b) > 14f && (r - g) < 85f
                val satMult = if (isSkin) 1.03f else 1.15f
                r = avgC + (r - avgC) * satMult
                g = avgC + (g - avgC) * satMult
                b = avgC + (b - avgC) * satMult

                val nx = px * invHalfW - 1f
                val d2 = (nx * nx + ny2) * 0.5f
                val vig = 1f - vigStrength * d2
                val rf = (r * vig).toInt().coerceIn(0, 255)
                val gf = (g * vig).toInt().coerceIn(0, 255)
                val bf = (b * vig).toInt().coerceIn(0, 255)

                pixels[idx] = (0xFF shl 24) or (rf shl 16) or (gf shl 8) or bf
            }
        }

        val sharpStrength = if (blueprint == ShotBlueprint.OBJECT_HERO || blueprint == ShotBlueprint.LANDSCAPE_SCENE) 0.42f else 0.30f
        applyNoiseGatedSharpen(pixels, w, h, sharpStrength)

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
            val row = y * w
            val prev = row - w
            val next = row + w
            for (x in 1 until w - 1) {
                val idx = row + x
                val blur = (
                    luma[prev + x - 1] + luma[prev + x] + luma[prev + x + 1] +
                    luma[row + x - 1] + luma[idx] + luma[row + x + 1] +
                    luma[next + x - 1] + luma[next + x] + luma[next + x + 1]
                ) / 9
                val diff = luma[idx] - blur
                if (abs(diff) > 6) {
                    val delta = (diff * amount).toInt().coerceIn(-18, 18)
                    val p = pixels[idx]
                    val r = (((p shr 16) and 0xFF) + delta).coerceIn(0, 255)
                    val g = (((p shr 8) and 0xFF) + delta).coerceIn(0, 255)
                    val b = ((p and 0xFF) + delta).coerceIn(0, 255)
                    pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    // ==================== STEP 5: SUBTLE NATURAL DSLR DEPTH ====================

    private fun applySubtleDepthBokeh(src: Bitmap, downScale: Int, radius: Int, passes: Int): Bitmap {
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(src, 0)))
        } catch (_: Exception) {
            return src
        }

        val mw = mask.width
        val mh = mask.height
        val buf = mask.buffer
        buf.rewind()

        val alphaRaw = IntArray(mw * mh)
        var fgPixels = 0
        for (i in 0 until mw * mh) {
            val conf = buf.float
            if (conf > 0.62f) fgPixels++
            val t = ((conf - 0.30f) / 0.55f).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            alphaRaw[i] = (smooth * 255f).toInt().coerceIn(0, 255)
        }

        val fgRatio = fgPixels.toFloat() / (mw * mh).coerceAtLeast(1)
        if (fgRatio < 0.06f || fgRatio > 0.92f) return src

        val maskPixels = IntArray(mw * mh)
        for (y in 0 until mh) {
            val y0 = (y - 1).coerceAtLeast(0) * mw
            val y1 = y * mw
            val y2 = (y + 1).coerceAtMost(mh - 1) * mw
            for (x in 0 until mw) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x2 = (x + 1).coerceAtMost(mw - 1)
                val avgA = (
                    alphaRaw[y0 + x0] + alphaRaw[y0 + x] + alphaRaw[y0 + x2] +
                    alphaRaw[y1 + x0] + alphaRaw[y1 + x] * 2 + alphaRaw[y1 + x2] +
                    alphaRaw[y2 + x0] + alphaRaw[y2 + x] + alphaRaw[y2 + x2]
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
            aspect in 0.55f..1.65f && (b.width().toFloat() / bmp.width) >= 0.08f && hasEyes
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
        var sumSq = 0.0
        var count = 0
        var y = 1
        while (y < rh - 1) {
            var x = 1
            val row = y * rw
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
