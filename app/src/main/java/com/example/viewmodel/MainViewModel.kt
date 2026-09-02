package com.example.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DownloadHistoryItem
import com.example.data.model.DownloadState
import com.example.data.model.PageItem
import com.example.data.model.ResolutionQuality
import com.example.data.storage.HistoryRepository
import com.example.data.storage.PdfStorageHelper
import com.example.engine.DriveExtractorEngine
import com.example.service.PdfDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val historyRepository = HistoryRepository(context)

    private var extractorEngine: DriveExtractorEngine? = null

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _discoveredPages = MutableStateFlow<List<PageItem>>(emptyList())
    val discoveredPages: StateFlow<List<PageItem>> = _discoveredPages.asStateFlow()

    private val _inputUrl = MutableStateFlow("")
    val inputUrl: StateFlow<String> = _inputUrl.asStateFlow()

    private val _resolutionQuality = MutableStateFlow(ResolutionQuality.ULTRA)
    val resolutionQuality: StateFlow<ResolutionQuality> = _resolutionQuality.asStateFlow()

    private val _isInteractiveMode = MutableStateFlow(false)
    val isInteractiveMode: StateFlow<Boolean> = _isInteractiveMode.asStateFlow()

    private val _isScreenshotStudioOpen = MutableStateFlow(false)
    val isScreenshotStudioOpen: StateFlow<Boolean> = _isScreenshotStudioOpen.asStateFlow()

    private val _currentDocumentTitle = MutableStateFlow<String?>(null)
    val currentDocumentTitle: StateFlow<String?> = _currentDocumentTitle.asStateFlow()

    val historyItems: StateFlow<List<DownloadHistoryItem>> = historyRepository.historyFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val quickCheckClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init {
        // Collect background service state updates
        viewModelScope.launch {
            PdfDownloadService.downloadState.collect { serviceState ->
                if (serviceState !is DownloadState.Idle) {
                    _downloadState.value = serviceState
                }
            }
        }
    }

    fun updateUrl(url: String) {
        _inputUrl.value = url
    }

    fun setResolution(quality: ResolutionQuality) {
        _resolutionQuality.value = quality
    }

    fun setInteractiveMode(enabled: Boolean) {
        _isInteractiveMode.value = enabled
    }

    fun setScreenshotStudioOpen(open: Boolean) {
        _isScreenshotStudioOpen.value = open
    }

    fun setSampleUrl() {
        _inputUrl.value = "https://drive.google.com/file/d/1ExampleDocumentFileIdSample/preview"
    }

    fun startDownload(rawUrl: String = _inputUrl.value) {
        val cleanUrl = rawUrl.trim()
        if (cleanUrl.isBlank()) {
            _downloadState.value = DownloadState.Error("Please enter a valid Google Drive URL.")
            return
        }

        cancelDownload()
        PdfDownloadService.resetState()
        _discoveredPages.value = emptyList()
        _currentDocumentTitle.value = null
        _downloadState.value = DownloadState.LoadingWebView(cleanUrl, "Connecting to document...")

        viewModelScope.launch {
            // First check if direct PDF export is available (for unprotected or public files)
            val fileId = DriveExtractorEngine.extractFileId(cleanUrl)
            var directPdfUrl: String? = null

            if (fileId != null) {
                directPdfUrl = checkDirectPdfExport(fileId)
            }

            if (directPdfUrl != null) {
                Log.d("MainViewModel", "Direct PDF export is available! Starting direct download.")
                _downloadState.value = DownloadState.DownloadingImages(
                    current = 1,
                    total = 1,
                    percent = 0.2f,
                    message = "Direct PDF export available. Downloading file..."
                )
                PdfDownloadService.startDirectPdfDownload(context, directPdfUrl, _currentDocumentTitle.value)
                return@launch
            }

            // Otherwise, start the headless browser multi-page interceptor & scroller
            startBrowserPageExtraction(cleanUrl)
        }
    }

    private suspend fun checkDirectPdfExport(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val candidateUrl = "https://drive.google.com/uc?export=download&id=$fileId"
            val request = Request.Builder()
                .url(candidateUrl)
                .head()
                .addHeader("User-Agent", DriveExtractorEngine.DESKTOP_USER_AGENT)
                .build()

            val response = quickCheckClient.newCall(request).execute()
            val contentType = response.header("Content-Type") ?: ""
            response.close()

            if (response.isSuccessful && contentType.contains("application/pdf", ignoreCase = true)) {
                return@withContext candidateUrl
            }
        } catch (e: Exception) {
            Log.d("MainViewModel", "Direct check did not succeed, using extraction engine: ${e.localizedMessage}")
        }
        return@withContext null
    }

    private fun startBrowserPageExtraction(cleanUrl: String) {
        val engine = DriveExtractorEngine(context, viewModelScope)
        extractorEngine = engine

        engine.onStatusUpdate = { msg ->
            val currentState = _downloadState.value
            if (currentState is DownloadState.LoadingWebView) {
                _downloadState.value = currentState.copy(message = msg)
            } else if (currentState is DownloadState.InterceptingPages) {
                _downloadState.value = currentState.copy(message = msg)
            }
        }

        engine.onTitleExtracted = { title ->
            _currentDocumentTitle.value = title
        }

        engine.onTotalPagesDetected = { total ->
            val currentState = _downloadState.value
            if (currentState is DownloadState.InterceptingPages) {
                _downloadState.value = currentState.copy(
                    estimatedTotal = maxOf(currentState.estimatedTotal, total),
                    message = "Capturing pages (Total: $total detected in viewer)..."
                )
            }
        }

        engine.onPageDiscovered = { pageNum, url, totalCaptured ->
            val currentList = _discoveredPages.value.toMutableList()
            if (currentList.none { it.pageNumber == pageNum }) {
                currentList.add(
                    PageItem(
                        pageNumber = pageNum,
                        imageUrl = url,
                        highResUrl = url
                    )
                )
                currentList.sortBy { it.pageNumber }
                _discoveredPages.value = currentList
            }

            _downloadState.value = DownloadState.InterceptingPages(
                capturedCount = totalCaptured,
                estimatedTotal = totalCaptured,
                message = "Captured page $pageNum ($totalCaptured pages detected)..."
            )
        }

        engine.onExtractionCompleted = { pageUrls, title, baseTemplateUrl ->
            val docTitle = title ?: _currentDocumentTitle.value
            _currentDocumentTitle.value = docTitle

            if (pageUrls.size > 1) {
                // Multi-page exact URL download
                _downloadState.value = DownloadState.DownloadingImages(
                    current = 1,
                    total = pageUrls.size,
                    percent = 0.05f,
                    message = "Starting download of ${pageUrls.size} document pages..."
                )
                PdfDownloadService.startDownloadWithUrls(
                    context = context,
                    pageUrls = pageUrls,
                    docTitle = docTitle,
                    resolution = _resolutionQuality.value
                )
            } else if (!baseTemplateUrl.isNullOrBlank()) {
                // Fallback to resilient smart probe with retries and delay
                _downloadState.value = DownloadState.CompilingPdf(
                    currentPage = 1,
                    totalPages = 1,
                    percent = 0f,
                    message = "Starting resilient smart page download..."
                )
                PdfDownloadService.startProbeDownload(
                    context = context,
                    firstImageUrl = baseTemplateUrl,
                    docTitle = docTitle,
                    resolution = _resolutionQuality.value
                )
            } else {
                _downloadState.value = DownloadState.Error(
                    message = "No pages could be extracted from this document.",
                    details = "Try opening Interactive Mode to verify access."
                )
            }

            engine.destroy()
            extractorEngine = null
        }

        engine.onError = { errorMsg ->
            _downloadState.value = DownloadState.Error(
                message = errorMsg,
                details = "Make sure the link is accessible or open Interactive Mode to sign in."
            )
            engine.destroy()
            extractorEngine = null
        }

        engine.startExtraction(cleanUrl, _resolutionQuality.value)
    }

    fun forceFinishExtraction() {
        extractorEngine?.forceCompileNow()
    }

    fun startDownloadWithPages(pageUrls: List<String>, title: String?) {
        if (pageUrls.isEmpty()) return
        cancelDownload()
        val docTitle = title ?: _currentDocumentTitle.value
        _currentDocumentTitle.value = docTitle
        _downloadState.value = DownloadState.DownloadingImages(
            current = 1,
            total = pageUrls.size,
            percent = 0.05f,
            message = "Starting download of ${pageUrls.size} document pages..."
        )
        PdfDownloadService.startDownloadWithUrls(
            context = context,
            pageUrls = pageUrls,
            docTitle = docTitle,
            resolution = _resolutionQuality.value
        )
    }

    fun cancelDownload() {
        extractorEngine?.destroy()
        extractorEngine = null
        PdfDownloadService.cancelDownload(context)
        PdfDownloadService.resetState()
        _downloadState.value = DownloadState.Idle
    }

    fun openPdf(uri: Uri, localPath: String? = null) {
        val fallbackFile = localPath?.let { java.io.File(it) }
        PdfStorageHelper.openPdf(context, uri, fallbackFile)
    }

    fun sharePdf(uri: Uri, localPath: String? = null, title: String = "Drive PDF") {
        val fallbackFile = localPath?.let { java.io.File(it) }
        PdfStorageHelper.sharePdf(context, uri, fallbackFile, title)
    }

    fun deleteHistoryItem(id: String) {
        historyRepository.removeItem(id)
    }

    fun clearAllHistory() {
        historyRepository.clearAll()
    }

    override fun onCleared() {
        super.onCleared()
        extractorEngine?.destroy()
    }
}
