package com.example.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class PdfCompiler {

    companion object {
        /**
         * Creates a high-contrast, crisp document filter for scanned or protected view-only pages.
         */
        fun createContrastColorFilter(contrast: Float = 1.22f, brightness: Float = -12f): ColorMatrixColorFilter {
            val cm = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            return ColorMatrixColorFilter(cm)
        }

        fun rotateBitmapIfNeeded(source: Bitmap, degrees: Int): Bitmap {
            val normalized = ((degrees % 360) + 360) % 360
            if (normalized == 0) return source
            val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
            return try {
                val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                if (rotated != source && !source.isRecycled) {
                    source.recycle()
                }
                rotated
            } catch (e: Exception) {
                source
            }
        }
    }

    /**
     * Converts an ordered list of image files on disk into a single A4 PDF document.
     * Supports optional document contrast boost for crisp text legibility.
     */
    suspend fun compileImageFiles(
        files: List<File>,
        enhanceContrast: Boolean = false,
        onProgress: (current: Int, total: Int) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val total = files.size

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
            if (enhanceContrast) {
                colorFilter = createContrastColorFilter()
            }
        }

        try {
            files.forEachIndexed { index, file ->
                onProgress(index + 1, total)
                if (!file.exists()) return@forEachIndexed

                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed

                // Standard A4 width is 595 points (72 DPI)
                val standardA4Width = 595
                val aspectRatio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 0.707f
                val pageWidth = standardA4Width
                val targetHeight = (pageWidth / aspectRatio).toInt().coerceAtLeast(100)
                val pageHeight = targetHeight

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                // Crisp white background
                canvas.drawColor(Color.WHITE)

                // Draw bitmap scaled to fill page bounds with high quality
                val destRect = Rect(0, 0, pageWidth, pageHeight)
                canvas.drawBitmap(bitmap, null, destRect, paint)

                pdfDocument.finishPage(page)

                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }

            val outputStream = ByteArrayOutputStream()
            pdfDocument.writeTo(outputStream)
            outputStream.toByteArray()
        } finally {
            pdfDocument.close()
        }
    }

    /**
     * Converts an ordered list of Bitmaps into a single PDF document.
     */
    suspend fun compilePdf(
        bitmaps: List<Pair<Int, Bitmap>>,
        enhanceContrast: Boolean = false,
        onProgress: (current: Int, total: Int) -> Unit
    ): ByteArray = withContext(Dispatchers.Default) {
        val pdfDocument = PdfDocument()
        val total = bitmaps.size

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
            if (enhanceContrast) {
                colorFilter = createContrastColorFilter()
            }
        }

        try {
            bitmaps.forEachIndexed { index, (_, bitmap) ->
                onProgress(index + 1, total)

                val standardA4Width = 595
                val aspectRatio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 0.707f
                val pageWidth = standardA4Width
                val targetHeight = (pageWidth / aspectRatio).toInt().coerceAtLeast(100)
                val pageHeight = targetHeight

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                canvas.drawColor(Color.WHITE)

                val destRect = Rect(0, 0, pageWidth, pageHeight)
                canvas.drawBitmap(bitmap, null, destRect, paint)

                pdfDocument.finishPage(page)

                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }

            val outputStream = ByteArrayOutputStream()
            pdfDocument.writeTo(outputStream)
            outputStream.toByteArray()
        } finally {
            pdfDocument.close()
        }
    }
}
