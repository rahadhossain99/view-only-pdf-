package com.example.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class PdfCompiler {

    /**
     * Converts an ordered list of image files on disk into a single PDF document.
     * Extremely memory-efficient as bitmaps are loaded, drawn, and recycled one by one.
     */
    suspend fun compileImageFiles(
        files: List<File>,
        onProgress: (current: Int, total: Int) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val total = files.size

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
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

                // Free memory immediately
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
     * Uses standard A4 width (595 points) and proportional or standard height.
     */
    suspend fun compilePdf(
        bitmaps: List<Pair<Int, Bitmap>>,
        onProgress: (current: Int, total: Int) -> Unit
    ): ByteArray = withContext(Dispatchers.Default) {
        val pdfDocument = PdfDocument()
        val total = bitmaps.size

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        try {
            bitmaps.forEachIndexed { index, (pageNumber, bitmap) ->
                onProgress(index + 1, total)

                // Standard A4 width is 595 points (72 DPI)
                val standardA4Width = 595
                val standardA4Height = 842

                // Calculate target page dimensions preserving bitmap aspect ratio
                val aspectRatio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 0.707f
                
                // If aspect ratio is close to standard portrait/landscape, adapt page size
                val pageWidth = standardA4Width
                val targetHeight = (pageWidth / aspectRatio).toInt().coerceAtLeast(100)
                val pageHeight = targetHeight

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                // Draw crisp white background
                canvas.drawColor(Color.WHITE)

                // Draw bitmap scaled to fill page bounds with high quality
                val destRect = Rect(0, 0, pageWidth, pageHeight)
                canvas.drawBitmap(bitmap, null, destRect, paint)

                pdfDocument.finishPage(page)

                // Recycle bitmap if needed to conserve memory
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
