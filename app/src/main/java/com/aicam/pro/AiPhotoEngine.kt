package com.aicam.pro

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import kotlin.math.sin

enum class ProLook(val label: String) {
    NATURAL("NATURAL"),
    CINEMATIC("CINEMA"),
    WARM_GOLD("GOLDEN"),
    VIVID("VIVID"),
    NOIR_BW("NOIR B&W")
}

enum class ReframeMode(val label: String, val ratio: Float) {
    OFF("REFR OFF", 0f),
    PORTRAIT_4_5("REFR 4:5", 4f / 5f),
    STORY_9_16("REFR 9:16", 9f / 16f),
    CLASSIC_3_4("REFR 3:4", 3f / 4f)
}

// Natural, realistic DSLR aperture depth (no artificial over-blur!)
enum class BokehLevel(val label: String, val downScale: Int, val radius: Int, val passes: Int) {
    OFF("BLUR OFF", 1, 0, 0),
    NATURAL_F4("f/4.0", 2, 2, 2),
    PORTRAIT_F28("f/2.8", 2, 4, 2),
    CREAMY_F18("f/1.8", 3, 5, 2)
}

data class BurstPickResult(
    val winnerFile: File,
    val winnerIndex: Int,
    val totalCount: Int
)

data class SaveOutcome(
    val summary: String,
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

    // ==================== AI STUDIO API (STRICT PHOTOREALISM ANCHOR) ====================

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

        val savedUri = saveRawImageBytesToGallery(
            bytes = result.imageBytes,
            mimeType = result.mimeType,
            fileName = "${fileName}_STUDIO"
        )
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
            val genConfig = JSONObject().put(
                "responseModalities",
                JSONArray().put("TEXT").put("IMAGE")
            )
            if (includeImageConfig) {
                genConfig.put("imageConfig", JSONObject().put("aspectRatio", "4:5"))
            }

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
                                        JSONObject()
                                            .put("mimeType", "image/jpeg")
                                            .put("data", base64Input)
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
                } catch (_: Exception) {
                    "HTTP $code"
                }
                return RawApiResult(false, code, null, "image/jpeg", "API $code: ${apiErr.take(90)}")
            }

            val root = JSONObject(respText)
            val candidates = root.optJSONArray("candidates")
                ?: return RawApiResult(false, code, null, "image/jpeg", "No candidates returned")
            if (candidates.length() == 0) {
                return RawApiResult(false, code, null, "image/jpeg", "Empty AI response")
            }

            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return RawApiResult(false, code, null, "image/jpeg", "No content parts")

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                if (inline != null) {
                    val dataStr = inline.optString("data", "")
                    val mime = inline.optString("mimeType", inline.optString("mime_type", "image/jpeg"))
                    if (dataStr.isNotEmpty()) {
                        val bytes = Base64.decode(dataStr, Base64.DEFAULT)
                        return RawApiResult(true, code, bytes, mime, "OK")
                    }
                }
            }

            RawApiResult(false, code, null, "image/jpeg", "Model returned text only (check Model ID)")
        } catch (e: Exception) {
            RawApiResult(false, -1, null, "image/jpeg", e.message ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    // ==================== BEST-SHOT BURST PICKER (HUMANS + OBJECTS) ====================

    fun pickBestBurstShot(files: List<File>): BurstPickResult {
        if (files.size <= 1) return BurstPickResult(files.first(), 1, files.size)

        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (i in files.indices) {
            val f = files[i]
            val thumb = decodeOrientedBitmap(f, maxDim = 480) ?: continue
            try {
                val rawFaces = try {
                    Tasks.await(faceDetector.process(InputImage.fromBitmap(thumb, 0)))
                } catch (_: Exception) {
                    emptyList()
                }
                val validFaces = rawFaces.filter {
                    it.leftEyeOpenProbability != null && it.rightEyeOpenProbability != null
                }

                val focusRect = if (validFaces.isNotEmpty()) {
                    val b = validFaces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!.boundingBox
                    Rect(
                        b.left.coerceIn(1, thumb.width - 2),
                        b.top.coerceIn(1, thumb.height - 2),
                        b.right.coerceIn(2, thumb.width - 1),
                        b.bottom.coerceIn(2, thumb.height - 1)
                    )
                } else {
                    val objs = try {
                        Tasks.await(singleObjectDetector.process(InputImage.fromBitmap(thumb, 0)))
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (objs.isNotEmpty()) {
                        val b = objs.first().boundingBox
                        Rect(
                            b.left.coerceIn(1, thumb.width - 2),
                            b.top.coerceIn(1, thumb.height - 2),
                            b.right.coerceIn(2, thumb.width - 1),
                            b.bottom.coerceIn(2, thumb.height - 1)
                        )
                    } else {
                        Rect(
                            (thumb.width * 0.2f).toInt().coerceAtLeast(1),
                            (thumb.height * 0.2f).toInt().coerceAtLeast(1),
                            (thumb.width * 0.8f).toInt().coerceAtMost(thumb.width - 1),
                            (thumb.height * 0.8f).toInt().coerceAtMost(thumb.height - 1)
                        )
                    }
                }

                val sharpness = computeSharpnessScore(thumb, focusRect)
                var expressionBonus = 0.0

                if (validFaces.isNotEmpty()) {
                    var allEyesOpen = true
                    var smileSum = 0.0
                    for (face in validFaces) {
                        val le = face.leftEyeOpenProbability ?: 0.8f
                        val re = face.rightEyeOpenProbability ?: 0.8f
                        if (le < 0.35f && re < 0.35f) allEyesOpen = false
                        smileSum += (face.smilingProbability ?: 0.3f)
                    }
                    expressionBonus += if (allEyesOpen) 45.0 else -60.0
                    expressionBonus += (smileSum / validFaces.size) * 18.0
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

    // ==================== LOCAL DSLR FINISHING PIPELINE ====================

    fun processAndSave(
        sourceFile: File,
        fileName: String,
        rollDeg: Float,
        isFrontCamera: Boolean,
        reframe: ReframeMode,
        bokeh: BokehLevel,
        look: ProLook
    ): SaveOutcome {
        val needsProcessing = (reframe != ReframeMode.OFF || bokeh != BokehLevel.OFF || look != ProLook.NATURAL)

        if (!needsProcessing) {
            val uri = saveFileDirectlyToGallery(sourceFile, fileName)
            return SaveOutcome("Saved HD", uri)
        }

        var current: Bitmap? = null
        val appliedTags = mutableListOf<String>()

        try {
            current = decodeOrientedBitmap(sourceFile, maxDim = 2048)
                ?: throw IllegalStateException("Bitmap decode failed")

            // 1. Smart Auto-Reframe (Horizon Straighten + Rule of Thirds for Humans & Objects — ZERO fake zoom)
            if (reframe != ReframeMode.OFF) {
                val reframed = applySmartReframe(current, rollDeg, isFrontCamera, reframe)
                if (reframed !== current) {
                    current.recycle()
                    current = reframed
                }
                appliedTags.add(reframe.label.removePrefix("REFR "))
            }

            // 2. Subtle Natural DSLR Bokeh (Automatically skipped if no clear human subject)
            if (bokeh != BokehLevel.OFF) {
                val blurred = applyNaturalDslrBokeh(current, bokeh)
                if (blurred !== current) {
                    current.recycle()
                    current = blurred
                    appliedTags.add(bokeh.label)
                }
            }

            // 3. Pro Color Look
            if (look != ProLook.NATURAL) {
                val graded = applyColorLook(current, look)
                if (graded !== current) {
                    current.recycle()
                    current = graded
                }
                appliedTags.add(look.label)
            }

            val uri = saveBitmapToGallery(current, fileName)
            val label = if (appliedTags.isEmpty()) "Saved" else "Saved (${appliedTags.joinToString(" + ")})"
            return SaveOutcome(label, uri)
        } catch (_: Throwable) {
            val uri = saveFileDirectlyToGallery(sourceFile, fileName)
            return SaveOutcome("Saved Original", uri)
        } finally {
            current?.recycle()
        }
    }

    private fun applySmartReframe(
        src: Bitmap,
        rollDeg: Float,
        isFrontCamera: Boolean,
        mode: ReframeMode
    ): Bitmap {
        var work = src

        // 1A. Horizon Auto-Straighten (only for gentle unintentional tilts 1.5°..15°)
        if (abs(rollDeg) in 1.5f..15f) {
            val correctionAngle = if (isFrontCamera) rollDeg else -rollDeg
            val rad = Math.toRadians(abs(correctionAngle).toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()

            val m = Matrix().apply { postRotate(correctionAngle) }
            val rotated = Bitmap.createBitmap(work, 0, 0, work.width, work.height, m, true)

            val a = work.width * 0.5f
            val b = work.height * 0.5f
            val k = min(a / (a * c + b * s), b / (a * s + b * c)) * 0.99f
            val safeW = (work.width * k).toInt().coerceIn(32, rotated.width)
            val safeH = (work.height * k).toInt().coerceAtLeast(32).coerceAtMost(rotated.height)
            val startX = ((rotated.width - safeW) / 2).coerceAtLeast(0)
            val startY = ((rotated.height - safeH) / 2).coerceAtLeast(0)

            val straightened = Bitmap.createBitmap(rotated, startX, startY, safeW, safeH)
            if (rotated !== straightened) rotated.recycle()
            work = straightened
        }

        val targetRatio = mode.ratio
        if (targetRatio <= 0f) return work

        val w = work.width
        val h = work.height
        val currentRatio = w.toFloat() / h.toFloat()

        // Preserve MAXIMUM field of view (no artificial zoom-in!)
        val cropW: Int
        val cropH: Int
        if (currentRatio > targetRatio) {
            cropH = h
            cropW = (h * targetRatio).toInt().coerceIn(32, w)
        } else {
            cropW = w
            cropH = (w / targetRatio).toInt().coerceIn(32, h)
        }

        var cropLeft = (w - cropW) / 2
        var cropTop = (h - cropH) / 2

        // Check for real human faces first
        val rawFaces = try {
            Tasks.await(faceDetector.process(InputImage.fromBitmap(work, 0)))
        } catch (_: Exception) {
            emptyList()
        }
        val validFaces = rawFaces.filter { f ->
            val aspect = if (f.boundingBox.height() > 0) f.boundingBox.width().toFloat() / f.boundingBox.height() else 0f
            aspect in 0.55f..1.65f && f.leftEyeOpenProbability != null && f.rightEyeOpenProbability != null
        }

        if (validFaces.isNotEmpty()) {
            val union = RectF(
                validFaces.minOf { it.boundingBox.left }.toFloat(),
                validFaces.minOf { it.boundingBox.top }.toFloat(),
                validFaces.maxOf { it.boundingBox.right }.toFloat(),
                validFaces.maxOf { it.boundingBox.bottom }.toFloat()
            )
            // Center horizontally on subject; align eyes near upper Rule of Thirds (33%)
            val desiredLeft = (union.centerX() - cropW * 0.5f).toInt()
            var desiredTop = (union.centerY() - cropH * 0.33f).toInt()

            // Guarantee natural headroom above top of head
            val minHeadroomTop = (union.top - union.height() * 0.55f).toInt()
            if (desiredTop > minHeadroomTop) desiredTop = minHeadroomTop

            cropLeft = desiredLeft.coerceIn(0, (w - cropW).coerceAtLeast(0))
            cropTop = desiredTop.coerceIn(0, (h - cropH).coerceAtLeast(0))
        } else {
            // No human face -> Smart Object-Aware Reframe
            val objects = try {
                Tasks.await(singleObjectDetector.process(InputImage.fromBitmap(work, 0)))
            } catch (_: Exception) {
                emptyList()
            }
            if (objects.isNotEmpty()) {
                val objBox = objects.first().boundingBox
                val desiredLeft = (objBox.centerX() - cropW * 0.5f).toInt()
                val desiredTop = (objBox.centerY() - cropH * 0.48f).toInt()
                cropLeft = desiredLeft.coerceIn(0, (w - cropW).coerceAtLeast(0))
                cropTop = desiredTop.coerceIn(0, (h - cropH).coerceAtLeast(0))
            }
        }

        val finalCrop = Bitmap.createBitmap(work, cropLeft, cropTop, cropW, cropH)
        if (work !== src && work !== finalCrop) work.recycle()
        return finalCrop
    }

    private fun applyNaturalDslrBokeh(src: Bitmap, level: BokehLevel): Bitmap {
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
            if (conf > 0.60f) fgPixels++
            // Gentle smoothstep feathering so hair & shoulders blend naturally
            val t = ((conf - 0.30f) / 0.55f).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            alphaRaw[i] = (smooth * 255f).toInt().coerceIn(0, 255)
        }

        // Skip bokeh automatically on objects/landscapes where no clear person is present
        val fgRatio = fgPixels.toFloat() / (mw * mh).coerceAtLeast(1)
        if (fgRatio < 0.05f || fgRatio > 0.94f) return src

        // Soft 3x3 edge-feather pass on mask to eliminate cut-out halo
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

        val blurredBg = createSmoothBlur(src, level.downScale, level.radius, level.passes)

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
                rSum += (px shr 16) and 0xFF
                gSum += (px shr 8) and 0xFF
                bSum += px and 0xFF
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
                rSum += (px shr 16) and 0xFF
                gSum += (px shr 8) and 0xFF
                bSum += px and 0xFF
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

    private fun applyColorLook(src: Bitmap, look: ProLook): Bitmap {
        val cm = ColorMatrix()
        when (look) {
            ProLook.NOIR_BW -> {
                cm.setSaturation(0f)
                cm.postConcat(ColorMatrix(floatArrayOf(
                    1.20f, 0f, 0f, 0f, -20f,
                    0f, 1.20f, 0f, 0f, -20f,
                    0f, 0f, 1.20f, 0f, -20f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            ProLook.CINEMATIC -> {
                cm.setSaturation(0.94f)
                cm.postConcat(ColorMatrix(floatArrayOf(
                    1.08f, 0f, 0f, 0f, -5f,
                    0f, 1.03f, 0f, 0f, -3f,
                    0f, 0f, 1.07f, 0f, 3f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            ProLook.WARM_GOLD -> {
                cm.setSaturation(1.10f)
                cm.postConcat(ColorMatrix(floatArrayOf(
                    1.08f, 0f, 0f, 0f, 6f,
                    0f, 1.02f, 0f, 0f, 2f,
                    0f, 0f, 0.92f, 0f, -5f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            ProLook.VIVID -> {
                cm.setSaturation(1.24f)
                cm.postConcat(ColorMatrix(floatArrayOf(
                    1.06f, 0f, 0f, 0f, -6f,
                    0f, 1.06f, 0f, 0f, -6f,
                    0f, 0f, 1.06f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            ProLook.NATURAL -> return src
        }

        val graded = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(graded)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return graded
    }

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
        while (longest / (sample * 2) >= maxDim) {
            sample *= 2
        }
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
                bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
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
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
            }
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
