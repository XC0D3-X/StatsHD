package com.stathd.purestatus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * HdPhotoOptimizer — WhatsApp Status HD Photo Optimizer
 *
 * Prevents WhatsApp Status from aggressively downscaling and crushing high-res photos:
 * 1. Safely decodes and fixes EXIF camera orientation (portrait/landscape).
 * 2. Downscales smoothly to WhatsApp's exact HD sweet spot (1600px or 2048px long side, never upscales).
 * 3. Applies fast unsharp-mask edge sharpening + subtle vibrance pop when Wink Mode is enabled.
 * 4. Compresses at optimal JPEG quality (92) to stay well below WhatsApp's re-compression trigger.
 */
object HdPhotoOptimizer {

    data class PhotoResult(
        val outputFile: File,
        val beforeBitmap: Bitmap,
        val afterBitmap: Bitmap,
        val outWidth: Int,
        val outHeight: Int
    )

    fun decodeOrientedBitmap(context: Context, uri: Uri, maxDimForPreview: Int = 2048): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while (max(rawW / sampleSize, rawH / sampleSize) > maxDimForPreview * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return decoded
        }

        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    fun optimizeForWhatsApp(
        context: Context,
        uri: Uri,
        outFile: File,
        enableSharpen: Boolean,
        highClarity: Boolean,
        rotate90: Boolean
    ): PhotoResult {
        var source = decodeOrientedBitmap(context, uri, maxDimForPreview = 2048)
            ?: throw IllegalStateException("Unable to decode image")

        if (rotate90) {
            val m = Matrix().apply { postRotate(90f) }
            val rot = Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
            if (rot !== source) source.recycle()
            source = rot
        }

        // WhatsApp Status HD Photo sweet spot: 1600px (standard) or 2048px (high clarity), NEVER upscale
        val maxLongSide = if (highClarity) 2048 else 1600
        val longSide = max(source.width, source.height)
        val scaledBefore = if (longSide > maxLongSide) {
            val ratio = maxLongSide.toDouble() / longSide.toDouble()
            val targetW = max(2, (source.width * ratio).roundToInt())
            val targetH = max(2, (source.height * ratio).roundToInt())
            val resized = Bitmap.createScaledBitmap(source, targetW, targetH, true)
            if (resized !== source) source.recycle()
            resized
        } else {
            source
        }

        val enhanced = if (enableSharpen) {
            applyFastUnsharpAndVibrance(
                src = scaledBefore,
                strength = if (highClarity) 1.15f else 0.75f,
                contrast = 1.05f,
                vibrance = if (highClarity) 0.11f else 0.06f
            )
        } else {
            scaledBefore.copy(Bitmap.Config.ARGB_8888, false)
        }

        FileOutputStream(outFile).use { fos ->
            enhanced.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }

        return PhotoResult(
            outputFile = outFile,
            beforeBitmap = scaledBefore,
            afterBitmap = enhanced,
            outWidth = enhanced.width,
            outHeight = enhanced.height
        )
    }

    /**
     * Fast 3x3 Unsharp Mask + Subtle Micro-Contrast & Vibrance on ARGB_8888 IntArray.
     */
    private fun applyFastUnsharpAndVibrance(
        src: Bitmap,
        strength: Float,
        contrast: Float,
        vibrance: Float
    ): Bitmap {
        val w = src.width
        val h = src.height
        val inPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        src.getPixels(inPixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            val yUp = if (y > 0) y - 1 else 0
            val yDown = if (y < h - 1) y + 1 else h - 1
            val rowOff = y * w
            val upOff = yUp * w
            val downOff = yDown * w

            for (x in 0 until w) {
                val xLeft = if (x > 0) x - 1 else 0
                val xRight = if (x < w - 1) x + 1 else w - 1

                val c = inPixels[rowOff + x]
                val n = inPixels[upOff + x]
                val s = inPixels[downOff + x]
                val l = inPixels[rowOff + xLeft]
                val r = inPixels[rowOff + xRight]

                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF

                val avgR = (((n shr 16) and 0xFF) + ((s shr 16) and 0xFF) + ((l shr 16) and 0xFF) + ((r shr 16) and 0xFF)) * 0.25f
                val avgG = (((n shr 8) and 0xFF) + ((s shr 8) and 0xFF) + ((l shr 8) and 0xFF) + ((r shr 8) and 0xFF)) * 0.25f
                val avgB = ((n and 0xFF) + (s and 0xFF) + (l and 0xFF) + (r and 0xFF)) * 0.25f

                // Unsharp edge boost with soft clamping (+-48 levels to avoid harsh ringing)
                val dr = (cr - avgR) * strength
                val dg = (cg - avgG) * strength
                val db = (cb - avgB) * strength

                var rr = (cr + dr.coerceIn(-48f, 48f)).coerceIn(0f, 255f)
                var rg = (cg + dg.coerceIn(-48f, 48f)).coerceIn(0f, 255f)
                var rb = (cb + db.coerceIn(-48f, 48f)).coerceIn(0f, 255f)

                // Subtle micro-contrast
                rr = ((rr - 128f) * contrast + 128f).coerceIn(0f, 255f)
                rg = ((rg - 128f) * contrast + 128f).coerceIn(0f, 255f)
                rb = ((rb - 128f) * contrast + 128f).coerceIn(0f, 255f)

                // Subtle vibrance pop
                val luma = rr * 0.299f + rg * 0.587f + rb * 0.114f
                val sat = 1f + vibrance
                val fr = (luma + (rr - luma) * sat).toInt().coerceIn(0, 255)
                val fg = (luma + (rg - luma) * sat).toInt().coerceIn(0, 255)
                val fb = (luma + (rb - luma) * sat).toInt().coerceIn(0, 255)

                outPixels[rowOff + x] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }
}
