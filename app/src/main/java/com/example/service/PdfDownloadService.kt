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
import com.example.engine.EncodedJpegPage
import com.example.engine.PdfCompiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

        const val ACTION_START_WITH_URLS = "com.example.action.START_WITH_URLS"
        const val ACTION_START_PROBE = "com.example.action.START_PROBE"
        const val ACTION_START_DIRECT = "com.example.action.START_DIRECT"
        const val ACTION_CANCEL = "com.example.action.CANCEL_DOWNLOAD"

        const val EXTRA_PAGE_URLS = "extra_page_urls"
        const val EXTRA_FIRST_IMAGE_URL = "extra_first_image_url"
        const val EXTRA_DIRECT_URL = "extra_direct_url"
        const val EXTRA_DOC_TITLE = "extra_doc_title"
        const val EXTRA_RESOLUTION_NAME = "extra_resolution_name"

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

        fun startDownloadWithUrls(
            context: Context,
            pageUrls: List<String>,
            docTitle: String?,
            resolution: ResolutionQuality = ResolutionQuality.HIGH
        ) {
            val intent = Intent(context, PdfDownloadService::class.java).apply {
                action = ACTION_START_WITH_URLS
                putStringArrayListExtra(EXTRA_PAGE_URLS, ArrayList(pageUrls))
                putExtra(EXTRA_DOC_TITLE, docTitle)
                putExtra(EXTRA_RESOLUTION_NAME, resolution.name)
            }
            startServiceIntent(context, intent)
        }

        fun startProbeDownload(
            context: Context,
            firstImageUrl: String,
            docTitle: String?,
            resolution: ResolutionQuality = ResolutionQuality.HIGH
        ) {
            val intent = Intent(context, PdfDownloadService::class.java).apply {
                action = ACTION_START_PROBE
                putExtra(EXTRA_FIRST_IMAGE_URL, firstImageUrl)
                putExtra(EXTRA_DOC_TITLE, docTitle)
                putExtra(EXTRA_RESOLUTION_NAME, resolution.name)
            }
            startServiceIntent(context, intent)
        }

        fun startDirectPdfDownload(
            context: Context,
            directPdfUrl: String,
            docTitle: String?
        ) {
            val intent = Intent(context, PdfDownloadService::class.java).apply {
                action = ACTION_START_DIRECT
                putExtra(EXTRA_DIRECT_URL, directPdfUrl)
                putExtra(EXTRA_DOC_TITLE, docTitle)
            }
            startServiceIntent(context, intent)
        }

        private fun startServiceIntent(context: Context, intent: Intent) {
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
            ACTION_START_WITH_URLS -> {
                val pageUrls = intent.getStringArrayListExtra(EXTRA_PAGE_URLS) ?: arrayListOf()
                val docTitle = intent.getStringExtra(EXTRA_DOC_TITLE)
                val resName = intent.getStringExtra(EXTRA_RESOLUTION_NAME)
                val resolution = try {
                    if (resName != null) ResolutionQuality.valueOf(resName) else ResolutionQuality.HIGH
                } catch (e: Exception) {
                    ResolutionQuality.HIGH
                }

                if (pageUrls.isNotEmpty()) {
                    startForegroundWithNotification("Preparing ${pageUrls.size} pages...")
                    beginPagesDownload(pageUrls, docTitle, resolution)
                } else {
                    stopSelf()
                }
            }

            ACTION_START_PROBE -> {
                val firstImageUrl = intent.getStringExtra(EXTRA_FIRST_IMAGE_URL)
                val docTitle = intent.getStringExtra(EXTRA_DOC_TITLE)
                val resName = intent.getStringExtra(EXTRA_RESOLUTION_NAME)
                val resolution = try {
                    if (resName != null) ResolutionQuality.valueOf(resName) else ResolutionQuality.HIGH
                } catch (e: Exception) {
                    ResolutionQuality.HIGH
                }

                if (!firstImageUrl.isNullOrBlank()) {
                    startForegroundWithNotification("Scanning document pages...")
                    beginResilientProbeDownload(firstImageUrl, docTitle, resolution)
                } else {
                    stopSelf()
                }
            }

            ACTION_START_DIRECT -> {
                val directUrl = intent.getStringExtra(EXTRA_DIRECT_URL)
                val docTitle = intent.getStringExtra(EXTRA_DOC_TITLE)
                if (!directUrl.isNullOrBlank()) {
                    startForegroundWithNotification("Downloading PDF...")
                    beginDirectDownload(directUrl, docTitle)
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
        val notification = buildProgressNotification(initialText, 0, 100, true)
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

    private fun beginPagesDownload(
        pageUrls: List<String>,
        title: String?,
        resolution: ResolutionQuality
    ) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            val jpegPages = mutableListOf<EncodedJpegPage>()
            val total = pageUrls.size
            val userAgent = DriveExtractorEngine.DESKTOP_USER_AGENT

            try {
                for (i in pageUrls.indices) {
                    if (!isActiveTask()) break
                    val pageDisplayNum = i + 1
                    val rawUrl = pageUrls[i]
                    val upgradedUrl = DriveExtractorEngine.upgradeImageUrlResolution(rawUrl, resolution)

                    val pct = ((pageDisplayNum.toFloat() / total.coerceAtLeast(1)) * 90f).toInt().coerceIn(1, 95)
                    val statusMsg = "Downloading page $pageDisplayNum of $total ($pct%)..."
                    updateProgress(
                        DownloadState.DownloadingImages(
                            current = pageDisplayNum,
                            total = total,
                            percent = pct / 100f,
                            message = statusMsg
                        ),
                        statusMsg,
                        pct,
                        100
                    )

                    val bitmap = downloadBitmapWithRetry(upgradedUrl, userAgent, maxRetries = 3)
                    if (bitmap != null) {
                        val jpegPage = PdfCompiler.scaleAndCompressToJpeg(
                            source = bitmap,
                            maxWidthPx = resolution.width,
                            jpegQuality = resolution.jpegQuality,
                            enhanceContrast = false
                        )
                        if (!bitmap.isRecycled) bitmap.recycle()
                        jpegPages.add(jpegPage)
                    } else {
                        Log.w(TAG, "Failed to fetch page $pageDisplayNum after retries")
                    }

                    delay(100)
                }

                if (jpegPages.isEmpty()) {
                    val errorMsg = "Could not download any pages from document."
                    _downloadState.value = DownloadState.Error(errorMsg)
                    showErrorNotification(errorMsg)
                    return@launch
                }

                compileAndSaveJpegPages(jpegPages, title)

            } catch (e: Exception) {
                Log.e(TAG, "Page download failed", e)
                val errorMsg = "Download error: ${e.localizedMessage ?: "Unknown error"}"
                _downloadState.value = DownloadState.Error(errorMsg)
                showErrorNotification(errorMsg)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun beginResilientProbeDownload(
        firstImageUrl: String,
        title: String?,
        resolution: ResolutionQuality
    ) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            val jpegPages = mutableListOf<EncodedJpegPage>()
            val userAgent = DriveExtractorEngine.DESKTOP_USER_AGENT
            var consecutiveFailures = 0

            try {
                var startingPageIndex = 1
                val testPage1Url = DriveExtractorEngine.buildPageUrl(firstImageUrl, 1, resolution)
                val testPage1Bitmap = downloadBitmapWithRetry(testPage1Url, userAgent, maxRetries = 2)

                if (testPage1Bitmap != null) {
                    jpegPages.add(
                        PdfCompiler.scaleAndCompressToJpeg(
                            source = testPage1Bitmap,
                            maxWidthPx = resolution.width,
                            jpegQuality = resolution.jpegQuality
                        )
                    )
                    if (!testPage1Bitmap.isRecycled) testPage1Bitmap.recycle()
                    updateProbeProgress(1, title)
                } else {
                    val testPage0Url = DriveExtractorEngine.buildPageUrl(firstImageUrl, 0, resolution)
                    val testPage0Bitmap = downloadBitmapWithRetry(testPage0Url, userAgent, maxRetries = 2)
                    if (testPage0Bitmap != null) {
                        startingPageIndex = 0
                        jpegPages.add(
                            PdfCompiler.scaleAndCompressToJpeg(
                                source = testPage0Bitmap,
                                maxWidthPx = resolution.width,
                                jpegQuality = resolution.jpegQuality
                            )
                        )
                        if (!testPage0Bitmap.isRecycled) testPage0Bitmap.recycle()
                        updateProbeProgress(1, title)
                    }
                }

                if (jpegPages.isNotEmpty()) {
                    var pageToFetch = startingPageIndex + 1
                    while (isActiveTask()) {
                        val pageDisplayIndex = jpegPages.size + 1
                        updateProbeProgress(pageDisplayIndex, title)

                        val pageUrl = DriveExtractorEngine.buildPageUrl(firstImageUrl, pageToFetch, resolution)
                        val bitmap = downloadBitmapWithRetry(pageUrl, userAgent, maxRetries = 3)

                        if (bitmap != null) {
                            consecutiveFailures = 0
                            jpegPages.add(
                                PdfCompiler.scaleAndCompressToJpeg(
                                    source = bitmap,
                                    maxWidthPx = resolution.width,
                                    jpegQuality = resolution.jpegQuality
                                )
                            )
                            if (!bitmap.isRecycled) bitmap.recycle()
                            pageToFetch++
                            delay(120)
                        } else {
                            consecutiveFailures++
                            if (consecutiveFailures >= 2) {
                                break
                            }
                            pageToFetch++
                        }
                    }
                }

                if (jpegPages.isEmpty()) {
                    val errorMsg = "Could not download document pages from this link."
                    _downloadState.value = DownloadState.Error(errorMsg)
                    showErrorNotification(errorMsg)
                    return@launch
                }

                compileAndSaveJpegPages(jpegPages, title)

            } catch (e: Exception) {
                Log.e(TAG, "Download probe failed", e)
                val errorMsg = "Download error: ${e.localizedMessage ?: "Unknown error"}"
                _downloadState.value = DownloadState.Error(errorMsg)
                showErrorNotification(errorMsg)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun beginDirectDownload(directUrl: String, title: String?) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            try {
                updateProgress(
                    DownloadState.DownloadingImages(1, 1, 0.5f, "Downloading original PDF document (50%)..."),
                    "Downloading original PDF document (50%)...",
                    50,
                    100
                )

                val request = Request.Builder()
                    .url(directUrl)
                    .addHeader("User-Agent", DriveExtractorEngine.DESKTOP_USER_AGENT)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw Exception("Direct download failed with HTTP ${response.code}")
                }

                val bytes = response.body?.bytes() ?: throw Exception("Empty PDF response received")
                val finalFileName = PdfStorageHelper.generateFileName(title)
                val saveResult = PdfStorageHelper.savePdfToDownloads(applicationContext, bytes, finalFileName)
                val cacheFile = withContext(Dispatchers.IO) {
                    PdfStorageHelper.saveToAppCache(applicationContext, bytes, finalFileName)
                }

                saveResult.onSuccess { mediaStoreUri ->
                    val historyItem = DownloadHistoryItem(
                        id = UUID.randomUUID().toString(),
                        title = title ?: finalFileName,
                        uriString = mediaStoreUri.toString(),
                        pageCount = 1,
                        fileSizeBytes = bytes.size.toLong(),
                        timestamp = System.currentTimeMillis()
                    )
                    historyRepository.addItem(historyItem)

                    _downloadState.value = DownloadState.Success(
                        uri = mediaStoreUri,
                        fileName = finalFileName,
                        pageCount = 1,
                        fileSizeBytes = bytes.size.toLong(),
                        localPath = cacheFile.absolutePath
                    )
                    DownloadNotificationHelper.showCompleted(
                        context = applicationContext,
                        fileName = finalFileName,
                        pageCount = 1,
                        fileSizeBytes = bytes.size.toLong(),
                        uri = mediaStoreUri,
                        cacheFile = cacheFile
                    )
                }.onFailure { error ->
                    val errorMsg = "Failed to save PDF: ${error.localizedMessage}"
                    _downloadState.value = DownloadState.Error(errorMsg)
                    showErrorNotification(errorMsg)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Direct download failed", e)
                val errorMsg = "Direct PDF download failed: ${e.localizedMessage}"
                _downloadState.value = DownloadState.Error(errorMsg)
                showErrorNotification(errorMsg)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun compileAndSaveJpegPages(
        jpegPages: List<EncodedJpegPage>,
        title: String?
    ) {
        val pageCount = jpegPages.size
        val statusMsg = "Building compact PDF ($pageCount pages)..."
        updateProgress(
            DownloadState.CompilingPdf(
                currentPage = pageCount,
                totalPages = pageCount,
                percent = 0.96f,
                message = statusMsg
            ),
            statusMsg,
            96,
            100
        )

        val pdfBytes = PdfCompiler.writeJpegPagesToPdfBytes(jpegPages)
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
                pageCount = pageCount,
                fileSizeBytes = pdfBytes.size.toLong(),
                timestamp = System.currentTimeMillis()
            )
            historyRepository.addItem(historyItem)

            _downloadState.value = DownloadState.Success(
                uri = mediaStoreUri,
                fileName = finalFileName,
                pageCount = pageCount,
                fileSizeBytes = pdfBytes.size.toLong(),
                localPath = cacheFile.absolutePath
            )

            DownloadNotificationHelper.showCompleted(
                context = applicationContext,
                fileName = finalFileName,
                pageCount = pageCount,
                fileSizeBytes = pdfBytes.size.toLong(),
                uri = mediaStoreUri,
                cacheFile = cacheFile
            )
        }.onFailure { error ->
            val errorMsg = "Failed to save PDF: ${error.localizedMessage}"
            _downloadState.value = DownloadState.Error(errorMsg)
            showErrorNotification(errorMsg)
        }
    }

    private suspend fun downloadBitmapWithRetry(
        url: String,
        userAgent: String,
        maxRetries: Int = 3
    ): Bitmap? {
        var attempts = 0
        while (attempts < maxRetries && isActiveTask()) {
            attempts++
            val result = downloadSingleBitmap(url, userAgent)
            if (result.first != null) {
                return result.first
            }

            val httpCode = result.second
            if (httpCode == 404) {
                return null
            }

            if (httpCode == 429) {
                delay(1200L * attempts)
            } else {
                delay(400L * attempts)
            }
        }
        return null
    }

    private fun downloadSingleBitmap(url: String, userAgent: String): Pair<Bitmap?, Int> {
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
            val code = response.code
            if (code != 200) {
                response.close()
                return Pair(null, code)
            }

            val bytes = response.body?.bytes() ?: run {
                response.close()
                return Pair(null, code)
            }
            if (bytes.isEmpty()) return Pair(null, code)

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = true
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            Pair(bitmap, 200)
        } catch (e: Exception) {
            Log.w(TAG, "Download request error: ${e.localizedMessage}")
            Pair(null, -1)
        }
    }

    private fun updateProbeProgress(pageNumber: Int, docTitle: String?) {
        val titlePrefix = if (!docTitle.isNullOrBlank()) "$docTitle: " else ""
        val message = "${titlePrefix}Downloading page $pageNumber..."
        updateProgress(
            DownloadState.CompilingPdf(
                currentPage = pageNumber,
                totalPages = pageNumber,
                percent = 0f,
                message = message
            ),
            message,
            pageNumber,
            0
        )
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

        val title = if (!indeterminate && max > 0) {
            val pct = ((progress.toFloat() / max.toFloat()) * 100f).toInt().coerceIn(0, 100)
            "Downloading PDF • $pct%"
        } else {
            "Downloading PDF..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
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

    private fun isActiveTask(): Boolean {
        return downloadJob?.isActive == true
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
