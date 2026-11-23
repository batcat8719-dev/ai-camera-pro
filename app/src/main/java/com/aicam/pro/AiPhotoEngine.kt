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
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.io.FileInputStream
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
    OFF("REFR:OFF", 0f),
    PORTRAIT_4_5("REFR:4:5", 4f / 5f),
    STORY_9_16("REFR:9:16", 9f / 16f),
    CLASSIC_3_4("REFR:3:4", 3f / 4f)
}

enum class BokehLevel(val label: String, val downScale: Int, val radius: Int) {
    OFF("BLUR:OFF", 1, 0),
    SOFT_F2("BLUR:f/2.0", 4, 5),
    PRO_F14("BLUR:f/1.4", 6, 9)
}

data class BurstPickResult(
    val winnerFile: File,
    val winnerIndex: Int,
    val totalCount: Int
)

class AiPhotoEngine(
    private val context: Context,
    private val faceDetector: FaceDetector
) {

    private val segmenter: Segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
    )

    fun close() {
        segmenter.close()
    }

    /**
     * Evaluates a burst of photos for Focus Sharpness (Laplacian variance),
     * Open Eyes, and Natural Smile, returning the single best photo.
     */
    fun pickBestBurstShot(files: List<File>): BurstPickResult {
        if (files.size <= 1) {
            return BurstPickResult(files.first(), 1, files.size)
        }

        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (i in files.indices) {
            val f = files[i]
            val thumb = decodeOrientedBitmap(f, maxDim = 480) ?: continue
            try {
                val faces = try {
                    Tasks.await(faceDetector.process(InputImage.fromBitmap(thumb, 0)))
                } catch (_: Exception) {
                    emptyList()
                }

                // Measure sharpness over face area if present, else center 60% of frame
                val focusRect = if (faces.isNotEmpty()) {
                    val b = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!.boundingBox
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

                val sharpness = computeSharpnessScore(thumb, focusRect)
                var expressionBonus = 0.0

                if (faces.isNotEmpty()) {
                    var allEyesOpen = true
                    var smileSum = 0.0
                    for (face in faces) {
                        val le = face.leftEyeOpenProbability ?: 0.8f
                        val re = face.rightEyeOpenProbability ?: 0.8f
                        if (le < 0.52f || re < 0.52f) allEyesOpen = false
                        smileSum += (face.smilingProbability ?: 0.3f)
                    }
                    expressionBonus += if (allEyesOpen) 45.0 else -60.0
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

        // Delete losing burst frames immediately to save space
        for (i in files.indices) {
            if (i != bestIndex) files[i].delete()
        }
        return BurstPickResult(files[bestIndex], bestIndex + 1, files.size)
    }

    /**
     * Full V4 AI Finishing Pipeline:
     * 1. Auto-Straighten & iPhone-style Smart Reframe
     * 2. AI DSLR Background Blur (Portrait Bokeh)
     * 3. Pro Color Grading LUT
     * 4. Save to Gallery (Pictures/AICam-Pro)
     */
    fun processAndSave(
        sourceFile: File,
        fileName: String,
        rollDeg: Float,
        isFrontCamera: Boolean,
        reframe: ReframeMode,
        bokeh: BokehLevel,
        look: ProLook
    ): String {
        val needsProcessing = (reframe != ReframeMode.OFF || bokeh != BokehLevel.OFF || look != ProLook.NATURAL)

        if (!needsProcessing) {
            saveFileDirectlyToGallery(sourceFile, fileName)
            return "Saved Original HD"
        }

        var current: Bitmap? = null
        val appliedTags = mutableListOf<String>()

        try {
            // Decode safely up to ~2048px longest edge (ultra crisp + zero OOM crash)
            current = decodeOrientedBitmap(sourceFile, maxDim = 2048)
                ?: throw IllegalStateException("Bitmap decode failed")

            // Step 1: iPhone-style Auto-Straighten + Smart Subject Reframe
            if (reframe != ReframeMode.OFF) {
                val reframed = applyAutoReframe(current, rollDeg, isFrontCamera, reframe)
                if (reframed !== current) {
                    current.recycle()
                    current = reframed
                }
                appliedTags.add(reframe.label.removePrefix("REFR:"))
            }

            // Step 2: AI DSLR Portrait Bokeh
            if (bokeh != BokehLevel.OFF) {
                val blurred = applyDslrBokeh(current, bokeh)
                if (blurred !== current) {
                    current.recycle()
                    current = blurred
                    appliedTags.add(bokeh.label.removePrefix("BLUR:"))
                }
            }

            // Step 3: Pro Color Grading
            if (look != ProLook.NATURAL) {
                val graded = applyColorLook(current, look)
                if (graded !== current) {
                    current.recycle()
                    current = graded
                }
                appliedTags.add(look.label)
            }

            saveBitmapToGallery(current, fileName)
            return if (appliedTags.isEmpty()) "Saved!" else "Saved (${appliedTags.joinToString(" + ")})"
        } catch (_: Throwable) {
            // Bulletproof fallback: never lose a user's photo
            saveFileDirectlyToGallery(sourceFile, fileName)
            return "Saved Original (Safe Fallback)"
        } finally {
            current?.recycle()
        }
    }

    private fun applyAutoReframe(
        src: Bitmap,
        rollDeg: Float,
        isFrontCamera: Boolean,
        mode: ReframeMode
    ): Bitmap {
        var work = src

        // 1A. Horizon Auto-Straighten (for unintentional tilts between 1.2° and 18°)
        if (abs(rollDeg) in 1.2f..18f) {
            val correctionAngle = if (isFrontCamera) rollDeg else -rollDeg
            val rad = Math.toRadians(abs(correctionAngle).toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()

            val m = Matrix().apply { postRotate(correctionAngle) }
            val rotated = Bitmap.createBitmap(work, 0, 0, work.width, work.height, m, true)

            // Exact largest inscribed centered rectangle (zero black corners)
            val a = work.width * 0.5f
            val b = work.height * 0.5f
            val k = min(a / (a * c + b * s), b / (a * s + b * c)) * 0.99f
            val safeW = (work.width * k).toInt().coerceIn(32, rotated.width)
            val safeH = (work.height * k).toInt().coerceIn(32, rotated.height)
            val startX = ((rotated.width - safeW) / 2).coerceAtLeast(0)
            val startY = ((rotated.height - safeH) / 2).coerceAtLeast(0)

            val straightened = Bitmap.createBitmap(rotated, startX, startY, safeW, safeH)
            if (rotated !== straightened) rotated.recycle()
            work = straightened
        }

        // 1B. Smart Face-Aware Rule-of-Thirds Crop
        val targetRatio = mode.ratio
        if (targetRatio <= 0f) return work

        val w = work.width
        val h = work.height
        val currentRatio = w.toFloat() / h.toFloat()

        var cropW: Int
        var cropH: Int
        if (currentRatio > targetRatio) {
            cropH = h
            cropW = (h * targetRatio).toInt().coerceIn(32, w)
        } else {
            cropW = w
            cropH = (w / targetRatio).toInt().coerceIn(32, h)
        }

        // Detect faces on straightened frame to lock composition
        val faces = try {
            Tasks.await(faceDetector.process(InputImage.fromBitmap(work, 0)))
        } catch (_: Exception) {
            emptyList()
        }

        var cropLeft = (w - cropW) / 2
        var cropTop = (h - cropH) / 2

        if (faces.isNotEmpty()) {
            val union = RectF(
                faces.minOf { it.boundingBox.left }.toFloat(),
                faces.minOf { it.boundingBox.top }.toFloat(),
                faces.maxOf { it.boundingBox.right }.toFloat(),
                faces.maxOf { it.boundingBox.bottom }.toFloat()
            )

            // Slight smart punch-in (up to 14%) if face is very small in frame
            val faceRatioW = union.width() / cropW.toFloat()
            if (faceRatioW < 0.16f && faces.size == 1) {
                cropW = (cropW * 0.86f).toInt().coerceAtLeast(64)
                cropH = (cropH * 0.86f).toInt().coerceAtLeast(64)
            }

            // Center horizontally on subject; align face vertically near upper Rule-of-Thirds (34%)
            val desiredLeft = (union.centerX() - cropW * 0.5f).toInt()
            var desiredTop = (union.centerY() - cropH * 0.34f).toInt()

            // Guarantee headroom above top of head
            val minHeadroomTop = (union.top - union.height() * 0.50f).toInt()
            if (desiredTop > minHeadroomTop) {
                desiredTop = minHeadroomTop
            }

            cropLeft = desiredLeft.coerceIn(0, (w - cropW).coerceAtLeast(0))
            cropTop = desiredTop.coerceIn(0, (h - cropH).coerceAtLeast(0))
        }

        val finalCrop = Bitmap.createBitmap(work, cropLeft, cropTop, cropW, cropH)
        if (work !== src && work !== finalCrop) work.recycle()
        return finalCrop
    }

    private fun applyDslrBokeh(src: Bitmap, level: BokehLevel): Bitmap {
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(src, 0)))
        } catch (_: Exception) {
            return src
        }

        val mw = mask.width
        val mh = mask.height
        val buf = mask.buffer
        buf.rewind()

        val maskPixels = IntArray(mw * mh)
        var fgPixels = 0
        for (i in 0 until mw * mh) {
            val conf = buf.float
            if (conf > 0.55f) fgPixels++
            // Smoothstep feathering for natural hair & shoulder edges
            val t = ((conf - 0.25f) / 0.53f).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            val alpha = (smooth * 255f).toInt().coerceIn(0, 255)
            maskPixels[i] = (alpha shl 24) or 0x00FFFFFF
        }

        // Skip blur if no clear human subject detected (protects landscape/object shots)
        val fgRatio = fgPixels.toFloat() / (mw * mh).coerceAtLeast(1)
        if (fgRatio < 0.03f || fgRatio > 0.96f) {
            return src
        }

        val rawMaskBitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        rawMaskBitmap.setPixels(maskPixels, 0, mw, 0, 0, mw, mh)

        // Generate creamy blurred background via fast 3-pass separable Gaussian approx
        val blurredBg = createSmoothBlur(src, level.downScale, level.radius)

        // Cut out feathered sharp foreground using native Skia DST_IN
        val fgLayer = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(fgLayer)
        fgCanvas.drawBitmap(src, 0f, 0f, null)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        fgCanvas.drawBitmap(rawMaskBitmap, null, Rect(0, 0, src.width, src.height), maskPaint)
        rawMaskBitmap.recycle()

        // Composite sharp foreground onto creamy blurred background
        val outCanvas = Canvas(blurredBg)
        outCanvas.drawBitmap(fgLayer, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        fgLayer.recycle()

        return blurredBg
    }

    private fun createSmoothBlur(src: Bitmap, downScale: Int, radius: Int): Bitmap {
        val sw = (src.width / downScale).coerceAtLeast(16)
        val sh = (src.height / downScale).coerceAtLeast(16)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)

        val pixels = IntArray(sw * sh)
        val temp = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        // 3 passes of separable box blur = smooth Gaussian bokeh
        repeat(3) {
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
            var rSum = 0
            var gSum = 0
            var bSum = 0
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
            var rSum = 0
            var gSum = 0
            var bSum = 0
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
                cm.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.22f, 0f, 0f, 0f, -22f,
                            0f, 1.22f, 0f, 0f, -22f,
                            0f, 0f, 1.22f, 0f, -22f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            ProLook.CINEMATIC -> {
                cm.setSaturation(0.92f)
                cm.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.10f, 0f, 0f, 0f, -6f,
                            0f, 1.04f, 0f, 0f, -4f,
                            0f, 0f, 1.08f, 0f, 4f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            ProLook.WARM_GOLD -> {
                cm.setSaturation(1.12f)
                cm.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.10f, 0f, 0f, 0f, 8f,
                            0f, 1.03f, 0f, 0f, 3f,
                            0f, 0f, 0.90f, 0f, -6f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            ProLook.VIVID -> {
                cm.setSaturation(1.30f)
                cm.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.08f, 0f, 0f, 0f, -8f,
                            0f, 1.08f, 0f, 0f, -8f,
                            0f, 0f, 1.08f, 0f, -8f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
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
        val rw = (roi.width()).coerceAtLeast(3)
        val rh = (roi.height()).coerceAtLeast(3)
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

    private fun saveBitmapToGallery(bmp: Bitmap, fileName: String) {
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
    }

    private fun saveFileDirectlyToGallery(file: File, fileName: String) {
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
    }
}
