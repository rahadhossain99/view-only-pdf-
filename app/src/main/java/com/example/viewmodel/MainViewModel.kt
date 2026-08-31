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
import com.example.engine.ImageDownloader
import com.example.engine.PdfCompiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val historyRepository = HistoryRepository(context)
    private val imageDownloader = ImageDownloader()
    private val pdfCompiler = PdfCompiler()

    private var extractorEngine: DriveExtractorEngine? = null
    private var downloadJob: Job? = null

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

    private val _currentDocumentTitle = MutableStateFlow<String?>(null)
    val currentDocumentTitle: StateFlow<String?> = _currentDocumentTitle.asStateFlow()

    val historyItems: StateFlow<List<DownloadHistoryItem>> = historyRepository.historyFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun updateUrl(url: String) {
        _inputUrl.value = url
    }

    fun setResolution(quality: ResolutionQuality) {
        _resolutionQuality.value = quality
    }

    fun setInteractiveMode(enabled: Boolean) {
        _isInteractiveMode.value = enabled
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
        _discoveredPages.value = emptyList()
        _currentDocumentTitle.value = null
        _downloadState.value = DownloadState.LoadingWebView(cleanUrl, "Connecting to document...")

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

        engine.onPagesDiscovered = { capturedCount, estimatedTotal ->
            val pagesList = (1..capturedCount).map { index ->
                PageItem(
                    pageNumber = index,
                    imageUrl = "",
                    highResUrl = ""
                )
            }
            _discoveredPages.value = pagesList
            _downloadState.value = DownloadState.InterceptingPages(
                capturedCount = capturedCount,
                estimatedTotal = estimatedTotal.coerceAtLeast(capturedCount),
                message = "Intercepted $capturedCount pages..."
            )
        }

        engine.onExtractionFinished = { pages, title ->
            _currentDocumentTitle.value = title ?: _currentDocumentTitle.value
            executeDownloadAndCompile(pages, title)
        }

        engine.onError = { errorMsg ->
            _downloadState.value = DownloadState.Error(
                message = errorMsg,
                details = "Make sure the link is public or open Interactive Mode to sign in."
            )
            engine.destroy()
        }

        engine.startExtraction(cleanUrl, _resolutionQuality.value)
    }

    fun forceFinishExtraction() {
        extractorEngine?.finishExtractionManually()
    }

    private fun executeDownloadAndCompile(pages: List<Pair<Int, String>>, title: String?) {
        downloadJob = viewModelScope.launch {
            try {
                val totalPages = pages.size
                _downloadState.value = DownloadState.DownloadingImages(
                    current = 0,
                    total = totalPages,
                    percent = 0f,
                    message = "Starting concurrent download of $totalPages pages..."
                )

                // 1. Download images concurrently via OkHttp + Coroutines
                val downloadedBitmaps = imageDownloader.downloadPageBitmaps(
                    pages = pages,
                    userAgent = DriveExtractorEngine.DESKTOP_USER_AGENT
                ) { current, total ->
                    _downloadState.value = DownloadState.DownloadingImages(
                        current = current,
                        total = total,
                        percent = current.toFloat() / total.toFloat(),
                        message = "Downloading page $current of $total..."
                    )
                }

                if (downloadedBitmaps.isEmpty()) {
                    _downloadState.value = DownloadState.Error("Failed to download page images.")
                    return@launch
                }

                // 2. Native PDF Compilation via PdfDocument
                _downloadState.value = DownloadState.CompilingPdf(
                    currentPage = 0,
                    totalPages = downloadedBitmaps.size,
                    percent = 0f,
                    message = "Compiling PDF document with native PDF engine..."
                )

                val pdfBytes = pdfCompiler.compilePdf(downloadedBitmaps) { current, total ->
                    _downloadState.value = DownloadState.CompilingPdf(
                        currentPage = current,
                        totalPages = total,
                        percent = current.toFloat() / total.toFloat(),
                        message = "Rendering page $current of $total to PDF..."
                    )
                }

                // 3. Save to Public Downloads via MediaStore
                val finalFileName = PdfStorageHelper.generateFileName(title ?: _currentDocumentTitle.value)
                val saveResult = PdfStorageHelper.savePdfToDownloads(context, pdfBytes, finalFileName)

                val cacheFile = withContext(Dispatchers.IO) {
                    PdfStorageHelper.saveToAppCache(context, pdfBytes, finalFileName)
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
                }.onFailure { error ->
                    _downloadState.value = DownloadState.Error(
                        message = "Failed to save PDF: ${error.localizedMessage}"
                    )
                }

            } catch (e: Exception) {
                Log.e("MainViewModel", "Download/Compile error", e)
                _downloadState.value = DownloadState.Error("An error occurred: ${e.localizedMessage}")
            } finally {
                extractorEngine?.destroy()
                extractorEngine = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        extractorEngine?.destroy()
        extractorEngine = null
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
