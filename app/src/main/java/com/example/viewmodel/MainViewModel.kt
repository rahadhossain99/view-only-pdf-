package com.example.viewmodel

import android.app.Application
import android.net.Uri
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val _currentDocumentTitle = MutableStateFlow<String?>(null)
    val currentDocumentTitle: StateFlow<String?> = _currentDocumentTitle.asStateFlow()

    val historyItems: StateFlow<List<DownloadHistoryItem>> = historyRepository.historyFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

        val engine = DriveExtractorEngine(context, viewModelScope)
        extractorEngine = engine

        engine.onStatusUpdate = { msg ->
            val currentState = _downloadState.value
            if (currentState is DownloadState.LoadingWebView) {
                _downloadState.value = currentState.copy(message = msg)
            } else if (currentState is DownloadState.CompilingPdf) {
                _downloadState.value = currentState.copy(message = msg)
            }
        }

        engine.onTitleExtracted = { title ->
            _currentDocumentTitle.value = title
        }

        // Smart loop trigger: as soon as first page URL is captured, stop WebView and hand off to Foreground Service
        engine.onFirstImageCaptured = { firstUrl, title ->
            val docTitle = title ?: _currentDocumentTitle.value
            _currentDocumentTitle.value = docTitle
            _downloadState.value = DownloadState.CompilingPdf(
                currentPage = 1,
                totalPages = 1,
                percent = 0f,
                message = "Starting smart loop download..."
            )
            PdfDownloadService.startDownload(
                context = context,
                firstImageUrl = firstUrl,
                docTitle = docTitle,
                resolution = _resolutionQuality.value
            )
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
        // No-op in smart loop mode
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
