package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.data.model.DownloadHistoryItem
import com.example.data.model.DownloadState
import com.example.data.model.ResolutionQuality
import com.example.data.storage.HistoryRepository
import com.example.data.storage.PdfStorageHelper
import com.example.engine.DriveExtractorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class PdfDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var historyRepository: HistoryRepository

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val TAG = "PdfDownloadService"
        const val CHANNEL_ID = "pdf_download_channel"
        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_COMPLETE = 1002

        const val ACTION_START = "com.example.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.action.CANCEL_DOWNLOAD"

        const val EXTRA_FIRST_IMAGE_URL = "extra_first_image_url"
        const val EXTRA_DOC_TITLE = "extra_doc_title"
        const val EXTRA_RESOLUTION_NAME = "extra_resolution_name"

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

        fun startDownload(
            context: Context,
            firstImageUrl: String,
            docTitle: String?,
            resolution: ResolutionQuality = ResolutionQuality.ULTRA
        ) {
            val intent = Intent(context, PdfDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FIRST_IMAGE_URL, firstImageUrl)
                putExtra(EXTRA_DOC_TITLE, docTitle)
                putExtra(EXTRA_RESOLUTION_NAME, resolution.name)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, PdfDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

        fun resetState() {
            _downloadState.value = DownloadState.Idle
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        historyRepository = HistoryRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val firstImageUrl = intent.getStringExtra(EXTRA_FIRST_IMAGE_URL)
                val docTitle = intent.getStringExtra(EXTRA_DOC_TITLE)
                val resName = intent.getStringExtra(EXTRA_RESOLUTION_NAME)
                val resolution = try {
                    if (resName != null) ResolutionQuality.valueOf(resName) else ResolutionQuality.ULTRA
                } catch (e: Exception) {
                    ResolutionQuality.ULTRA
                }

                if (!firstImageUrl.isNullOrBlank()) {
                    startForegroundWithNotification("Initializing smart download...")
                    beginSmartLoopDownload(firstImageUrl, docTitle, resolution)
                } else {
                    stopSelf()
                }
            }
            ACTION_CANCEL -> {
                cancelCurrentProcess()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(initialText: String) {
        val notification = buildProgressNotification(initialText, 0, 0, true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID_PROGRESS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID_PROGRESS, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    /**
     * Smart Loop Downloader:
     * 1. Begins at page=1 (with page=0 fallback if needed)
     * 2. Loops dynamically until HTTP status != 200 or Bitmap == null (auto-detects end of document)
     * 3. Writes each Bitmap directly into PdfDocument and recycles it to keep memory footprint minimal
     * 4. Updates foreground notification on every page downloaded
     * 5. Saves compiled PDF to public Downloads directory
     */
    private fun beginSmartLoopDownload(
        firstImageUrl: String,
        title: String?,
        resolution: ResolutionQuality
    ) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            val pdfDocument = PdfDocument()
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }

            var currentPage = 1
            var hasMorePages = true
            var successfulPagesCount = 0
            val userAgent = DriveExtractorEngine.DESKTOP_USER_AGENT

            try {
                // Test if page 1 works, or if 0-indexed
                var startingPageIndex = 1
                val testPage1Url = DriveExtractorEngine.buildPageUrl(firstImageUrl, 1, resolution)
                val testPage1Bitmap = downloadBitmapFromUrl(testPage1Url, userAgent)

                if (testPage1Bitmap == null) {
                    // Try 0-indexed fallback
                    val testPage0Url = DriveExtractorEngine.buildPageUrl(firstImageUrl, 0, resolution)
                    val testPage0Bitmap = downloadBitmapFromUrl(testPage0Url, userAgent)
                    if (testPage0Bitmap != null) {
                        startingPageIndex = 0
                        writeBitmapToPdf(pdfDocument, testPage0Bitmap, 1, paint)
                        successfulPagesCount++
                        updatePageProgress(1, title)
                    }
                } else {
                    writeBitmapToPdf(pdfDocument, testPage1Bitmap, 1, paint)
                    successfulPagesCount++
                    updatePageProgress(1, title)
                }

                // If starting page succeeded, continue loop from next page index
                if (successfulPagesCount > 0) {
                    var pageToFetch = startingPageIndex + 1
                    while (hasMorePages && downloadJob?.isActive == true) {
                        val pageDisplayIndex = successfulPagesCount + 1
                        updatePageProgress(pageDisplayIndex, title)

                        val pageUrl = DriveExtractorEngine.buildPageUrl(firstImageUrl, pageToFetch, resolution)
                        val bitmap = downloadBitmapFromUrl(pageUrl, userAgent)

                        if (bitmap != null) {
                            writeBitmapToPdf(pdfDocument, bitmap, pageDisplayIndex, paint)
                            successfulPagesCount++
                            pageToFetch++
                        } else {
                            // Non-200 HTTP code or null Bitmap returned: reached end of document
                            hasMorePages = false
                            break
                        }
                    }
                }

                if (successfulPagesCount == 0) {
                    pdfDocument.close()
                    val errorMsg = "Could not download document pages from this link."
                    _downloadState.value = DownloadState.Error(errorMsg)
                    showErrorNotification(errorMsg)
                    stopSelf()
                    return@launch
                }

                // Finalize PDF
                val statusMsg = "Saving $successfulPagesCount pages to Downloads..."
                updateProgress(
                    DownloadState.CompilingPdf(
                        currentPage = successfulPagesCount,
                        totalPages = successfulPagesCount,
                        percent = 1f,
                        message = statusMsg
                    ),
                    statusMsg,
                    successfulPagesCount,
                    successfulPagesCount
                )

                val outputStream = ByteArrayOutputStream()
                pdfDocument.writeTo(outputStream)
                pdfDocument.close()
                val pdfBytes = outputStream.toByteArray()

                val finalFileName = PdfStorageHelper.generateFileName(title)
                val saveResult = PdfStorageHelper.savePdfToDownloads(applicationContext, pdfBytes, finalFileName)
                val cacheFile = withContext(Dispatchers.IO) {
                    PdfStorageHelper.saveToAppCache(applicationContext, pdfBytes, finalFileName)
                }

                saveResult.onSuccess { mediaStoreUri ->
                    val historyItem = DownloadHistoryItem(
                        id = UUID.randomUUID().toString(),
                        title = title ?: finalFileName,
                        uriString = mediaStoreUri.toString(),
                        pageCount = successfulPagesCount,
                        fileSizeBytes = pdfBytes.size.toLong(),
                        timestamp = System.currentTimeMillis()
                    )
                    historyRepository.addItem(historyItem)

                    _downloadState.value = DownloadState.Success(
                        uri = mediaStoreUri,
                        fileName = finalFileName,
                        pageCount = successfulPagesCount,
                        fileSizeBytes = pdfBytes.size.toLong(),
                        localPath = cacheFile.absolutePath
                    )

                    showSuccessNotification(mediaStoreUri, cacheFile, finalFileName, successfulPagesCount)
                }.onFailure { error ->
                    val errorMsg = "Failed to save PDF: ${error.localizedMessage}"
                    _downloadState.value = DownloadState.Error(errorMsg)
                    showErrorNotification(errorMsg)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download loop failed", e)
                try { pdfDocument.close() } catch (closeEx: Exception) {}
                val errorMsg = "Download error: ${e.localizedMessage ?: "Unknown error"}"
                _downloadState.value = DownloadState.Error(errorMsg)
                showErrorNotification(errorMsg)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun writeBitmapToPdf(
        pdfDocument: PdfDocument,
        bitmap: Bitmap,
        pageIndex: Int,
        paint: Paint
    ) {
        try {
            val standardA4Width = 595
            val aspectRatio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 0.707f
            val pageWidth = standardA4Width
            val pageHeight = (pageWidth / aspectRatio).toInt().coerceAtLeast(100)

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
            val pdfPage = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = pdfPage.canvas

            // Crisp white background
            canvas.drawColor(Color.WHITE)

            // Scaled bitmap rendering
            val destRect = Rect(0, 0, pageWidth, pageHeight)
            canvas.drawBitmap(bitmap, null, destRect, paint)

            pdfDocument.finishPage(pdfPage)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun updatePageProgress(pageNumber: Int, docTitle: String?) {
        val titlePrefix = if (!docTitle.isNullOrBlank()) "$docTitle: " else ""
        val message = "${titlePrefix}Downloading & writing page $pageNumber..."
        updateProgress(
            DownloadState.CompilingPdf(
                currentPage = pageNumber,
                totalPages = pageNumber,
                percent = 0f,
                message = message
            ),
            message,
            pageNumber,
            0 // Indeterminate progress until auto-end
        )
    }

    private fun downloadBitmapFromUrl(url: String, userAgent: String): Bitmap? {
        return try {
            val cookie = CookieManager.getInstance().getCookie(url)
            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", "https://drive.google.com/")
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

            if (!cookie.isNullOrBlank()) {
                requestBuilder.addHeader("Cookie", cookie)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (response.code != 200) {
                response.close()
                return null
            }

            val bytes = response.body?.bytes() ?: run {
                response.close()
                return null
            }
            if (bytes.isEmpty()) return null

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.w(TAG, "Request ended: ${e.localizedMessage}")
            null
        }
    }

    private fun updateProgress(
        state: DownloadState,
        notificationText: String,
        progress: Int,
        max: Int
    ) {
        _downloadState.value = state
        val indeterminate = max <= 0
        val notification = buildProgressNotification(notificationText, progress, max, indeterminate)
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    private fun buildProgressNotification(
        content: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean
    ): Notification {
        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, PdfDownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading Drive PDF")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(if (indeterminate) 0 else max, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showSuccessNotification(
        uri: Uri,
        cacheFile: File,
        fileName: String,
        pageCount: Int
    ) {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            Intent.createChooser(openIntent, "Open PDF"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val successNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDF Ready ($pageCount pages)")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Open PDF", openPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, successNotification)
    }

    private fun showErrorNotification(errorMessage: String) {
        val errorNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDF Download Failed")
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, errorNotification)
    }

    private fun cancelCurrentProcess() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drive PDF Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live progress for Google Drive PDF downloads and compilation"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
