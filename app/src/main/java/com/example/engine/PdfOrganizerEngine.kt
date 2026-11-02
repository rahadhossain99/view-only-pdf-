package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.data.model.PdfPageEditItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class PdfOrganizerEngine(private val context: Context) {

    /**
     * Resolves a local file path or content Uri into a seekable local File for PdfRenderer.
     */
    suspend fun resolveSeekableFile(uri: Uri?, localPath: String?): File? = withContext(Dispatchers.IO) {
        if (!localPath.isNullOrBlank()) {
            val f = File(localPath)
            if (f.exists() && f.length() > 0) return@withContext f
        }

        if (uri != null) {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                if (f.exists() && f.length() > 0) return@withContext f
            }
            return@withContext try {
                val tempFile = File(context.cacheDir, "studio_active_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) tempFile else null
            } catch (e: Exception) {
                null
            }
        }
        null
    }

    /**
     * Returns the total page count of a seekable PDF file.
     */
    suspend fun getPageCount(pdfFile: File): Int = withContext(Dispatchers.IO) {
        if (!pdfFile.exists()) return@withContext 0
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (e: Exception) {
            0
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Renders a single page from the PDF file at the requested target width, with optional rotation and contrast filter.
     */
    suspend fun renderPage(
        pdfFile: File,
        pageIndex: Int,
        targetWidthPx: Int = 900,
        rotationDegrees: Int = 0,
        enhanceContrast: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!pdfFile.exists()) return@withContext null
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            val page = renderer.openPage(pageIndex)
            val aspect = if (page.height > 0) page.width.toFloat() / page.height.toFloat() else 0.707f
            val bmpW = targetWidthPx.coerceIn(240, 2400)
            val bmpH = (bmpW / aspect).toInt().coerceIn(240, 3400)

            val rawBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val rawCanvas = Canvas(rawBitmap)
            rawCanvas.drawColor(Color.WHITE)
            page.render(rawBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val contrastedBitmap = if (enhanceContrast) {
                val filtered = Bitmap.createBitmap(rawBitmap.width, rawBitmap.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(filtered)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    colorFilter = PdfCompiler.createContrastColorFilter()
                }
                c.drawBitmap(rawBitmap, 0f, 0f, paint)
                rawBitmap.recycle()
                filtered
            } else {
                rawBitmap
            }

            PdfCompiler.rotateBitmapIfNeeded(contrastedBitmap, rotationDegrees)
        } catch (e: Exception) {
            null
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Recompiles the source PDF file applying page reordering, deletions, rotations, and contrast enhancements.
     */
    suspend fun exportEditedPdf(
        sourcePdfFile: File,
        activeEdits: List<PdfPageEditItem>,
        onProgress: (current: Int, total: Int) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val keptPages = activeEdits.filter { !it.isDeleted }
        if (keptPages.isEmpty() || !sourcePdfFile.exists()) return@withContext ByteArray(0)

        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val outPdf = PdfDocument()

        try {
            pfd = ParcelFileDescriptor.open(sourcePdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val total = keptPages.size

            keptPages.forEachIndexed { index, editItem ->
                onProgress(index + 1, total)
                val origIndex = editItem.originalPageIndex
                if (origIndex < 0 || origIndex >= renderer.pageCount) return@forEachIndexed

                val srcPage = renderer.openPage(origIndex)
                // Render at high resolution (2x A4 points = 1190px width)
                val renderWidth = 1400
                val srcAspect = if (srcPage.height > 0) srcPage.width.toFloat() / srcPage.height.toFloat() else 0.707f
                val renderHeight = (renderWidth / srcAspect).toInt().coerceAtLeast(200)

                var bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                val bCanvas = Canvas(bitmap)
                bCanvas.drawColor(Color.WHITE)
                srcPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                srcPage.close()

                bitmap = PdfCompiler.rotateBitmapIfNeeded(bitmap, editItem.rotationDegrees)

                val outAspect = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 0.707f
                val pageWidth = if (outAspect > 1f) 842 else 595
                val pageHeight = (pageWidth / outAspect).toInt().coerceAtLeast(100)

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val destPage = outPdf.startPage(pageInfo)
                val destCanvas = destPage.canvas
                destCanvas.drawColor(Color.WHITE)

                val paint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                    isDither = true
                    if (editItem.enhanceContrast) {
                        colorFilter = PdfCompiler.createContrastColorFilter()
                    }
                }

                destCanvas.drawBitmap(bitmap, null, Rect(0, 0, pageWidth, pageHeight), paint)
                outPdf.finishPage(destPage)

                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }

            val outStream = ByteArrayOutputStream()
            outPdf.writeTo(outStream)
            outStream.toByteArray()
        } finally {
            try { outPdf.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }
}
