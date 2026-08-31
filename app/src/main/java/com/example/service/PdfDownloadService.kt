package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.DownloadHistoryItem
import com.example.data.model.DownloadState
import com.example.data.storage.HistoryRepository
import com.example.data.storage.PdfStorageHelper
import com.example.engine.DriveExtractorEngine
import com.example.engine.ImageDownloader
import com.example.engine.PdfCompiler
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
import java.io.File
import java.util.UUID

class PdfDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var historyRepository: HistoryRepository
    private val imageDownloader = ImageDownloader()
    private val pdfCompiler = PdfCompiler()

    companion object {
        private const val TAG = "PdfDownloadService"
        const val CHANNEL_ID = "pdf_download_channel"
        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_COMPLETE = 1002

        const val ACTION_START = "com.example.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.action.CANCEL_DOWNLOAD"

        const val EXTRA_PAGE_NUMBERS = "extra_page_numbers"
        const val EXTRA_PAGE_URLS = "extra_page_urls"
        const val EXTRA_DOC_TITLE = "extra_doc_title"

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

        fun startDownload(
            context: Context,
            pages: List<Pair<Int, String>>,
            docTitle: String?
        ) {
            val intent = Intent(context, PdfDownloadService::class.java).apply {
                action = ACTION_START
                putIntegerArrayListExtra(EXTRA_PAGE_NUMBERS, ArrayList(pages.map { it.first }))
                putStringArrayListExtra(EXTRA_PAGE_URLS, ArrayList(pages.map { it.second }))
                putExtra(EXTRA_DOC_TITLE, docTitle)
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
                val pageNumbers = intent.getIntegerArrayListExtra(EXTRA_PAGE_NUMBERS) ?: emptyList()
                val pageUrls = intent.getStringArrayListExtra(EXTRA_PAGE_URLS) ?: emptyList()
                val docTitle = intent.getStringExtra(EXTRA_DOC_TITLE)

                if (pageNumbers.isNotEmpty() && pageNumbers.size == pageUrls.size) {
                    val pages = pageNumbers.zip(pageUrls)
                    startForegroundWithNotification("Preparing download...")
                    beginDownloadProcess(pages, docTitle)
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

    private fun beginDownloadProcess(pages: List<Pair<Int, String>>, title: String?) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            try {
                val totalPages = pages.size
                updateProgress(
                    DownloadState.DownloadingImages(
                        current = 0,
                        total = totalPages,
                        percent = 0f,
                        message = "Downloading page 0 of $totalPages..."
                    ),
                    "Downloading page 1 of $totalPages...",
                    0,
                    100
                )

                // 1. Download images concurrently via OkHttp
                val downloadedBitmaps = imageDownloader.downloadPageBitmaps(
                    pages = pages,
                    userAgent = DriveExtractorEngine.DESKTOP_USER_AGENT
                ) { current, total ->
                    val percent = (current.toFloat() / total.toFloat()) * 0.7f // Download is 0-70%
                    val message = "Downloading page $current of $total..."
                    updateProgress(
                        DownloadState.DownloadingImages(
                            current = current,
                            total = total,
                            percent = current.toFloat() / total.toFloat(),
                            message = message
                        ),
                        message,
                        (percent * 100).toInt(),
                        100
                    )
                }

                if (downloadedBitmaps.isEmpty()) {
                    val errorMsg = "Failed to download page images."
                    _downloadState.value = DownloadState.Error(errorMsg)
                    showErrorNotification(errorMsg)
                    stopSelf()
                    return@launch
                }

                // 2. Native PDF Compilation via PdfDocument
                updateProgress(
                    DownloadState.CompilingPdf(
                        currentPage = 0,
                        totalPages = downloadedBitmaps.size,
                        percent = 0f,
                        message = "Compiling PDF document..."
                    ),
                    "Compiling PDF document...",
                    70,
                    100
                )

                val pdfBytes = pdfCompiler.compilePdf(downloadedBitmaps) { current, total ->
                    val progressFraction = 0.7f + (current.toFloat() / total.toFloat()) * 0.3f // Compile is 70-100%
                    val message = "Compiling page $current of $total..."
                    updateProgress(
                        DownloadState.CompilingPdf(
                            currentPage = current,
                            totalPages = total,
                            percent = current.toFloat() / total.toFloat(),
                            message = message
                        ),
                        message,
                        (progressFraction * 100).toInt(),
                        100
                    )
                }

                // 3. Save to Public Downloads via MediaStore
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
                        pageCount = pages.size,
                        fileSizeBytes = pdfBytes.size.toLong(),
                        timestamp = System.currentTimeMillis()
                    )
                    historyRepository.addItem(historyItem)

                    _downloadState.value = DownloadState.Success(
                        uri = mediaStoreUri,
                        fileName = finalFileName,
                        pageCount = pages.size,
                        fileSizeBytes = pdfBytes.size.toLong(),
                        localPath = cacheFile.absolutePath
                    )

                    showSuccessNotification(mediaStoreUri, cacheFile, finalFileName, pages.size)
                }.onFailure { error ->
                    val errorMsg = "Failed to save PDF: ${error.localizedMessage}"
                    _downloadState.value = DownloadState.Error(errorMsg)
                    showErrorNotification(errorMsg)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download service failed", e)
                val errorMsg = "Download error: ${e.localizedMessage ?: "Unknown error"}"
                _downloadState.value = DownloadState.Error(errorMsg)
                showErrorNotification(errorMsg)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateProgress(
        state: DownloadState,
        notificationText: String,
        progress: Int,
        max: Int
    ) {
        _downloadState.value = state
        val notification = buildProgressNotification(notificationText, progress, max, false)
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
            .setProgress(max, progress, indeterminate)
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
        // Intent to open PDF
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
            .setContentTitle("PDF Ready")
            .setContentText("$fileName ($pageCount pages)")
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
