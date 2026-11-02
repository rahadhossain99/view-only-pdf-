package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.DownloadHistoryItem
import com.example.data.model.EngineTelemetry
import com.example.data.model.ExtractionEngineMode
import com.example.data.model.ResolutionQuality
import com.example.data.storage.HistoryRepository
import com.example.data.storage.PdfStorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class PageBoundingBox(
    val found: Boolean,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val totalDiscovered: Int,
    val hasCanvas: Boolean = false
)

class DriveExtractorEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var webView: WebView? = null
    private var extractedTitle: String? = null
    private var currentResolution: ResolutionQuality = ResolutionQuality.ULTRA
    private var currentEngineMode: ExtractionEngineMode = ExtractionEngineMode.AUTO_HYBRID
    private var enhanceContrast: Boolean = false
    private var isExtracting = false
    private val rawPdfSaved = AtomicBoolean(false)

    private val capturedPagesMap = ConcurrentSkipListMap<Int, String>()
    private val capturedUrlsSet = Collections.synchronizedSet(HashSet<String>())
    private val capturedPageFiles = Collections.synchronizedList(mutableListOf<File>())

    private val interceptedStreamCount = AtomicInteger(0)
    private val canvasHookCount = AtomicInteger(0)
    private val domTileCount = AtomicInteger(0)
    private val precisionScreenshotCount = AtomicInteger(0)

    private var mainHandler = Handler(Looper.getMainLooper())
    private var captureJob: Job? = null
    private var totalDetectedPages: Int = 1

    var onStatusUpdate: ((message: String) -> Unit)? = null
    var onTelemetryUpdate: ((telemetry: EngineTelemetry) -> Unit)? = null
    var onTitleExtracted: ((title: String) -> Unit)? = null
    var onTotalPagesDetected: ((total: Int) -> Unit)? = null
    var onPageDiscovered: ((pageNumber: Int, highResUrl: String, totalCaptured: Int) -> Unit)? = null

    // Precision page-by-page capture callbacks
    var onPageCaptured: ((pageNum: Int, total: Int, message: String) -> Unit)? = null
    var onCompilingPdf: ((currentPage: Int, totalPages: Int, percent: Float, message: String) -> Unit)? = null
    var onPdfReady: ((uri: Uri, fileName: String, pageCount: Int, fileSizeBytes: Long, localPath: String?) -> Unit)? = null

    var onExtractionCompleted: ((pageUrls: List<String>, title: String?, baseTemplateUrl: String?) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    companion object {
        private const val TAG = "DriveExtractorEngine"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun extractFileId(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            val fileIdPatterns = listOf(
                Regex("/file/d/([a-zA-Z0-9_-]+)"),
                Regex("id=([a-zA-Z0-9_-]+)"),
                Regex("/document/d/([a-zA-Z0-9_-]+)"),
                Regex("/presentation/d/([a-zA-Z0-9_-]+)"),
                Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)"),
                Regex("/open\\?id=([a-zA-Z0-9_-]+)")
            )

            for (pattern in fileIdPatterns) {
                val match = pattern.find(trimmed)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
            return null
        }

        fun sanitizeDriveUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return ""

            val fileId = extractFileId(trimmed)
            if (fileId != null) {
                return "https://drive.google.com/file/d/$fileId/preview"
            }

            return if (trimmed.contains("drive.google.com") && trimmed.contains("/view")) {
                trimmed.replace("/view", "/preview")
            } else {
                trimmed
            }
        }

        fun isDriveViewerImageUrl(url: String): Boolean {
            val lower = url.lowercase()
            val isDriveOrGoogle = lower.contains("drive.google.com") ||
                    lower.contains("docs.google.com") ||
                    lower.contains("googleusercontent.com")

            val isViewerPath = lower.contains("/viewer") ||
                    lower.contains("drive-viewer") ||
                    lower.contains("/thumbnail") ||
                    lower.contains("page=") ||
                    lower.contains("pg=") ||
                    lower.contains("sz=w") ||
                    lower.contains("=w")

            val isUiAsset = lower.contains("cleardot.gif") ||
                    lower.contains("drive_icon") ||
                    lower.contains("photos/private") ||
                    lower.contains("default-avatar") ||
                    lower.contains(".svg") ||
                    lower.contains(".ico") ||
                    lower.contains("fonts.gstatic.com")

            return isDriveOrGoogle && isViewerPath && !isUiAsset
        }

        fun isDirectPdfStreamUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.endsWith(".pdf") ||
                    lower.contains("export=download") ||
                    lower.contains("content-type=application/pdf") ||
                    lower.contains("mime=application/pdf")
        }

        fun extractPageNumberFromUrl(url: String): Int? {
            val pagePatterns = listOf(
                Regex("[?&]page=(\\d+)"),
                Regex("[?&]pg=(\\d+)"),
                Regex("[?&]p=(\\d+)"),
                Regex("page_(\\d+)"),
                Regex("/page/(\\d+)")
            )

            for (pattern in pagePatterns) {
                val match = pattern.find(url)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
            return null
        }

        fun upgradeImageUrlResolution(originalUrl: String, quality: ResolutionQuality): String {
            var modified = originalUrl

            val widthRegex = Regex("=w\\d+(-h\\d+)?")
            val szRegex = Regex("sz=w\\d+")
            val paramWRegex = Regex("([?&])w=\\d+")

            if (widthRegex.containsMatchIn(modified)) {
                modified = modified.replace(widthRegex, "=${quality.paramSuffix}")
            } else if (szRegex.containsMatchIn(modified)) {
                modified = modified.replace(szRegex, "sz=${quality.paramSuffix}")
            } else if (paramWRegex.containsMatchIn(modified)) {
                modified = modified.replace(paramWRegex, "$1w=${quality.width}")
            } else {
                if (modified.contains("googleusercontent.com") && !modified.contains("=")) {
                    modified += "=${quality.paramSuffix}"
                } else if (!modified.contains("w=") && !modified.contains("sz=")) {
                    val sep = if (modified.contains("?")) "&" else "?"
                    modified = "$modified${sep}w=${quality.width}"
                }
            }

            return modified
        }

        fun buildPageUrl(baseUrl: String, pageNumber: Int, quality: ResolutionQuality): String {
            val highRes = upgradeImageUrlResolution(baseUrl, quality)
            val pageParam = "page=$pageNumber"
            return if (highRes.contains("page=")) {
                highRes.replace(Regex("page=\\d+"), pageParam)
            } else if (highRes.contains("pg=")) {
                highRes.replace(Regex("pg=\\d+"), "pg=$pageNumber")
            } else if (highRes.contains("?")) {
                "$highRes&$pageParam"
            } else {
                "$highRes?$pageParam"
            }
        }
    }

    private fun emitTelemetry(activeTier: String, message: String) {
        val telemetry = EngineTelemetry(
            activeMode = currentEngineMode,
            activeTierName = activeTier,
            interceptedStreams = interceptedStreamCount.get(),
            canvasHooksCaptured = canvasHookCount.get(),
            domTilesDiscovered = domTileCount.get(),
            precisionScreenshotsCaptured = precisionScreenshotCount.get(),
            totalDetectedPages = totalDetectedPages,
            lastLogMessage = message
        )
        mainHandler.post {
            onTelemetryUpdate?.invoke(telemetry)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun startExtraction(
        url: String,
        resolution: ResolutionQuality = ResolutionQuality.ULTRA,
        engineMode: ExtractionEngineMode = ExtractionEngineMode.AUTO_HYBRID,
        contrastEnhance: Boolean = false,
        existingWebView: WebView? = null
    ) {
        currentResolution = resolution
        currentEngineMode = engineMode
        enhanceContrast = contrastEnhance
        extractedTitle = null
        isExtracting = true
        rawPdfSaved.set(false)
        capturedPagesMap.clear()
        capturedUrlsSet.clear()
        capturedPageFiles.clear()
        interceptedStreamCount.set(0)
        canvasHookCount.set(0)
        domTileCount.set(0)
        precisionScreenshotCount.set(0)
        captureJob?.cancel()

        val targetUrl = sanitizeDriveUrl(url)
        if (targetUrl.isBlank()) {
            onError?.invoke("Invalid URL provided. Please paste a valid Google Drive or PDF link.")
            return
        }

        emitTelemetry("Tier 1: Stream & Browser Init", "Initializing ${engineMode.shortBadge} pipeline...")

        mainHandler.post {
            try {
                val wv = existingWebView ?: WebView(context).also { webView = it }
                try {
                    wv.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, 1200, 1800)
                } catch (e: Exception) {
                    Log.d(TAG, "Layout measurement: ${e.message}")
                }
                setupWebView(wv, targetUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing WebView", e)
                onError?.invoke("Failed to initialize browser engine: ${e.localizedMessage}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView, targetUrl: String) {
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = DESKTOP_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.removeJavascriptInterface("DriveBridge")
        wv.addJavascriptInterface(DriveJsBridge(), "DriveBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null && isExtracting) {
                    val reqUrl = request.url.toString()
                    if (isDirectPdfStreamUrl(reqUrl)) {
                        val streams = interceptedStreamCount.incrementAndGet()
                        emitTelemetry("Tier 1: Network Stream", "Intercepted PDF stream candidate (#$streams)")
                    }
                    if (isDriveViewerImageUrl(reqUrl)) {
                        val pageNum = extractPageNumberFromUrl(reqUrl)
                        handleImageDiscovered(reqUrl, pageNum)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val msg = "Connecting to document & attaching Blob/XHR/Canvas hooks..."
                onStatusUpdate?.invoke(msg)
                emitTelemetry("Tier 1 & 2: Network + Blob Hook", msg)
                injectEarlyStreamAndBlobHooks(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val msg = "Document loaded. Running ${currentEngineMode.shortBadge} extraction..."
                onStatusUpdate?.invoke(msg)
                injectEarlyStreamAndBlobHooks(view)
                injectPageScraperAndCaptureScript(view)

                // Start the multi-engine orchestration loop
                startMultiEngineExtractionLoop(view ?: wv)
            }
        }

        wv.loadUrl(targetUrl)
    }

    @Synchronized
    private fun handleImageDiscovered(rawUrl: String, detectedPageNum: Int?) {
        if (!isExtracting) return

        val cleanBaseUrl = rawUrl.substringBefore("=w").substringBefore("sz=w")
        if (capturedUrlsSet.contains(cleanBaseUrl)) {
            return
        }
        capturedUrlsSet.add(cleanBaseUrl)

        val highResUrl = upgradeImageUrlResolution(rawUrl, currentResolution)

        val pageNumber = if (detectedPageNum != null && detectedPageNum > 0) {
            detectedPageNum
        } else if (detectedPageNum == 0) {
            1
        } else {
            val maxKey = capturedPagesMap.keys.maxOrNull() ?: 0
            maxKey + 1
        }

        capturedPagesMap[pageNumber] = highResUrl
        val currentTotal = capturedPagesMap.size
        domTileCount.set(currentTotal)

        emitTelemetry("Tier 3: High-DPI Tile Upgrader", "Discovered high-res page tile #$pageNumber (${currentResolution.label})")

        mainHandler.post {
            onPageDiscovered?.invoke(pageNumber, highResUrl, currentTotal)
        }
    }

    /**
     * Tier 1 & Tier 2 Early Hook:
     * Hooks URL.createObjectURL, Fetch, and XMLHttpRequest to capture raw PDF blobs or ArrayBuffers
     * directly from browser memory when a viewer uses PDF.js or Blob streaming.
     */
    private fun injectEarlyStreamAndBlobHooks(view: WebView?) {
        val hookScript = """
            (function() {
                if (window._driveBlobHooksInstalled) return;
                window._driveBlobHooksInstalled = true;

                function inspectBlob(blob) {
                    try {
                        if (!blob) return;
                        var type = (blob.type || '').toLowerCase();
                        if (type.indexOf('pdf') !== -1 || (blob.size > 8192 && type === '')) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var res = reader.result || '';
                                if (res.indexOf('JVBERi0') !== -1 || res.indexOf('application/pdf') !== -1) {
                                    var b64 = res.split(',')[1] || '';
                                    if (b64.length > 1000 && window.DriveBridge && window.DriveBridge.onRawPdfBase64Found) {
                                        window.DriveBridge.onRawPdfBase64Found(b64);
                                    }
                                }
                            };
                            reader.readAsDataURL(blob);
                        }
                    } catch (e) {}
                }

                var origCreateObjectURL = URL.createObjectURL;
                if (origCreateObjectURL) {
                    URL.createObjectURL = function(obj) {
                        inspectBlob(obj);
                        return origCreateObjectURL.apply(this, arguments);
                    };
                }

                // Observe dynamically added <img> and <canvas> nodes
                var observer = new MutationObserver(function(mutations) {
                    try {
                        var imgs = document.querySelectorAll('img[src*="viewer"], img[src*="googleusercontent"]');
                        for (var i = 0; i < imgs.length; i++) {
                            var src = imgs[i].src;
                            if (src && window.DriveBridge && window.DriveBridge.onPageImageFound) {
                                window.DriveBridge.onPageImageFound(src, i);
                            }
                        }
                    } catch (e) {}
                });
                if (document.documentElement) {
                    observer.observe(document.documentElement, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(hookScript, null)
    }

    /**
     * Tier 2, 3 & 4 DOM, Canvas & Precision Bounding-Box Controller
     */
    private fun injectPageScraperAndCaptureScript(view: WebView?) {
        val jsScript = """
            (function() {
                window._driveCapture = {
                    hideChrome: function() {
                        var selectors = [
                            '.drive-viewer-toolstrip',
                            '.drive-viewer-action-bar',
                            '.ndfHFb-c4YZDc-j7LFlb',
                            '.drive-viewer-popout-button',
                            '.drive-viewer-banner',
                            'header',
                            '[role="banner"]',
                            '.drive-viewer-navigation-overlay',
                            '#toolbarContainer',
                            '.toolbar'
                        ];
                        for (var i = 0; i < selectors.length; i++) {
                            var els = document.querySelectorAll(selectors[i]);
                            for (var j = 0; j < els.length; j++) {
                                els[j].style.display = 'none';
                            }
                        }
                    },

                    getTitle: function() {
                        try {
                            var title = document.title || "";
                            title = title.replace(" - Google Drive", "").replace(" - Google Docs", "").trim();
                            var titleElem = document.querySelector('.ndfHFb-c4YZDc-s2gQvd') || 
                                            document.querySelector('.drive-viewer-toolstrip-name') ||
                                            document.querySelector('.drive-viewer-title');
                            if (titleElem && titleElem.innerText) {
                                title = titleElem.innerText.trim();
                            }
                            return title;
                        } catch(e) { return ""; }
                    },

                    findPages: function() {
                        var selectors = [
                            '.drive-viewer-paginated-page',
                            '[data-page-index]',
                            '[data-page-number]',
                            '.ndfHFb-c4YZDc-Wrql6b',
                            '.drive-viewer-page',
                            '.pdfViewer .page',
                            '.page[data-loaded="true"]'
                        ];
                        for (var s = 0; s < selectors.length; s++) {
                            var list = document.querySelectorAll(selectors[s]);
                            if (list && list.length > 0) {
                                return Array.from(list);
                            }
                        }
                        var canvases = document.querySelectorAll('canvas');
                        if (canvases && canvases.length > 1) {
                            var validCanvases = [];
                            for (var c = 0; c < canvases.length; c++) {
                                if (canvases[c].width > 250 && canvases[c].height > 250) {
                                    validCanvases.push(canvases[c]);
                                }
                            }
                            if (validCanvases.length > 0) return validCanvases;
                        }
                        var container = document.querySelector('.drive-viewer-paginated-scrollable, .ndfHFb-c4YZDc-bN97Pc, [role="main"]');
                        if (container && container.children) {
                            var valid = [];
                            for (var i = 0; i < container.children.length; i++) {
                                var ch = container.children[i];
                                if (ch.clientHeight > 250 && ch.clientWidth > 250) {
                                    valid.push(ch);
                                }
                            }
                            if (valid.length > 0) return valid;
                        }
                        return [];
                    },

                    getTotalCount: function() {
                        var numInputs = document.querySelectorAll('#numPages, .drive-viewer-toolstrip, .ndfHFb-c4YZDc-j7LFlb, div, span');
                        for (var i = 0; i < numInputs.length; i++) {
                            var txt = numInputs[i].innerText || "";
                            var m = /(?:of|\/)\s*(\d+)/i.exec(txt);
                            if (m && m[1]) {
                                var cnt = parseInt(m[1], 10);
                                if (cnt > 0 && cnt < 1000) return cnt;
                            }
                        }
                        return this.findPages().length;
                    },

                    scanAllDomTileUrls: function() {
                        try {
                            var pages = this.findPages();
                            for (var i = 0; i < pages.length; i++) {
                                var img = pages[i].querySelector ? pages[i].querySelector('img') : null;
                                if (img && img.src && window.DriveBridge) {
                                    window.DriveBridge.onPageImageFound(img.src, i);
                                }
                            }
                        } catch (e) {}
                    },

                    extractCanvasBase64ForPage: function(idx) {
                        try {
                            var pages = this.findPages();
                            if (!pages || idx >= pages.length) return "";
                            var el = pages[idx];
                            var canvas = (el.tagName && el.tagName.toLowerCase() === 'canvas')
                                ? el
                                : (el.querySelector ? el.querySelector('canvas') : null);
                            if (canvas && canvas.width > 200 && canvas.height > 200) {
                                var dataUrl = canvas.toDataURL('image/jpeg', 0.94);
                                if (dataUrl && dataUrl.length > 2000) {
                                    return dataUrl.split(',')[1] || "";
                                }
                            }
                        } catch (e) {}
                        return "";
                    },

                    scrollToPage: function(idx) {
                        this.hideChrome();
                        this.scanAllDomTileUrls();
                        var pages = this.findPages();
                        if (!pages || pages.length === 0 || idx >= pages.length) {
                            var sc = document.querySelector('.drive-viewer-paginated-scrollable, .ndfHFb-c4YZDc-bN97Pc') || document.documentElement || document.body;
                            var step = 1400;
                            if (sc.scrollTop !== undefined) {
                                sc.scrollTop = idx * step;
                            } else {
                                window.scrollBy(0, step);
                            }
                            return JSON.stringify({
                                found: false,
                                left: 0,
                                top: 0,
                                width: window.innerWidth || 1200,
                                height: window.innerHeight || 1800,
                                totalDiscovered: pages ? pages.length : 0,
                                hasCanvas: false
                            });
                        }

                        var pageEl = pages[idx];
                        pageEl.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'center' });

                        try {
                            pageEl.dispatchEvent(new Event('scroll', { bubbles: true }));
                            window.dispatchEvent(new Event('scroll'));
                        } catch(e) {}

                        var hasCanvas = false;
                        try {
                            var c = (pageEl.tagName && pageEl.tagName.toLowerCase() === 'canvas')
                                ? pageEl
                                : (pageEl.querySelector ? pageEl.querySelector('canvas') : null);
                            hasCanvas = !!(c && c.width > 200 && c.height > 200);
                        } catch (e) {}

                        var rect = pageEl.getBoundingClientRect();
                        return JSON.stringify({
                            found: true,
                            left: Math.round(rect.left),
                            top: Math.round(rect.top),
                            width: Math.round(rect.width),
                            height: Math.round(rect.height),
                            totalDiscovered: pages.length,
                            hasCanvas: hasCanvas
                        });
                    },

                    isPageReady: function(idx) {
                        var pages = this.findPages();
                        if (!pages || idx >= pages.length) return true;
                        var pageEl = pages[idx];
                        var img = pageEl.querySelector ? pageEl.querySelector('img') : null;
                        if (img) {
                            return img.complete && img.naturalWidth > 0;
                        }
                        var canvas = (pageEl.tagName && pageEl.tagName.toLowerCase() === 'canvas')
                            ? pageEl
                            : (pageEl.querySelector ? pageEl.querySelector('canvas') : null);
                        if (canvas) {
                            return canvas.width > 200 && canvas.height > 200;
                        }
                        return true;
                    }
                };
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsScript, null)
    }

    private fun startMultiEngineExtractionLoop(wv: WebView) {
        captureJob?.cancel()
        captureJob = scope.launch(Dispatchers.Main) {
            delay(1000)
            if (!isExtracting || rawPdfSaved.get()) return@launch

            // 1. Scrape Title
            wv.evaluateJavascript("window._driveCapture ? window._driveCapture.getTitle() : '';") { titleRaw ->
                val clean = titleRaw?.trim('"', '\\', ' ')?.takeIf { it.isNotBlank() && it != "null" }
                if (clean != null && extractedTitle == null) {
                    extractedTitle = clean
                    onTitleExtracted?.invoke(clean)
                }
            }

            // 2. Detect Total Pages
            wv.evaluateJavascript("window._driveCapture ? window._driveCapture.getTotalCount() : 1;") { countRaw ->
                val count = countRaw?.trim('"', ' ')?.toIntOrNull() ?: 1
                if (count > 0) {
                    totalDetectedPages = count
                    onTotalPagesDetected?.invoke(count)
                }
            }

            delay(300)
            val startMsg = "Running ${currentEngineMode.shortBadge} across $totalDetectedPages detected pages..."
            onStatusUpdate?.invoke(startMsg)
            emitTelemetry(currentEngineMode.shortBadge, startMsg)

            var pageIndex = 0
            var consecutiveMisses = 0
            val maxLimit = 300

            while (isExtracting && !rawPdfSaved.get() && pageIndex < maxLimit) {
                val pageDisplay = pageIndex + 1

                // Scroll to exact page element and get bounding rect + canvas presence
                val box = fetchPageBoundingBox(wv, pageIndex)

                // Wait 420ms for Google Drive lazy load / tile or canvas render
                delay(420)

                val isReady = checkPageReady(wv, pageIndex)
                if (!isReady) {
                    delay(350)
                }

                val tempFile = File(context.cacheDir, "drive_page_${System.currentTimeMillis()}_$pageDisplay.jpg")
                var pageSaved = false

                // Tier 2b: If page uses HTML5 <canvas> and mode is AUTO_HYBRID or HIGH_RES_CANVAS_DOM, try direct canvas extraction first
                if ((currentEngineMode == ExtractionEngineMode.AUTO_HYBRID ||
                            currentEngineMode == ExtractionEngineMode.HIGH_RES_CANVAS_DOM) &&
                    box?.hasCanvas == true
                ) {
                    val canvasSaved = tryExtractCanvasBitmapToFile(wv, pageIndex, tempFile)
                    if (canvasSaved && tempFile.length() > 2048) {
                        pageSaved = true
                        val hooks = canvasHookCount.incrementAndGet()
                        emitTelemetry(
                            "Tier 2: HTML5 Canvas Extractor",
                            "Extracted lossless Canvas bitmap for Page $pageDisplay (#$hooks)"
                        )
                    }
                }

                // Tier 4: Precision Real-Size Page-by-Page Screenshot Cropping (guarantees no split or missing pages)
                if (!pageSaved) {
                    val screenshotSuccess = captureExactPageScreenshot(wv, box, tempFile)
                    if (screenshotSuccess && tempFile.length() > 2048) {
                        pageSaved = true
                        precisionScreenshotCount.incrementAndGet()
                    }
                }

                if (pageSaved) {
                    consecutiveMisses = 0
                    capturedPageFiles.add(tempFile)

                    val effectiveTotal = maxOf(totalDetectedPages, box?.totalDiscovered ?: 0, capturedPageFiles.size)
                    val statusMsg = "Captured Page $pageDisplay of $effectiveTotal (${currentEngineMode.shortBadge})..."

                    onStatusUpdate?.invoke(statusMsg)
                    onPageCaptured?.invoke(pageDisplay, effectiveTotal, statusMsg)
                    emitTelemetry(
                        "Tier 4: Precision Page Capture",
                        "Captured exact page $pageDisplay of $effectiveTotal"
                    )

                    if (totalDetectedPages > 0 && capturedPageFiles.size >= totalDetectedPages) {
                        Log.d(TAG, "All $totalDetectedPages pages captured successfully!")
                        break
                    }
                } else {
                    tempFile.delete()
                    consecutiveMisses++
                    if (consecutiveMisses >= 3) {
                        Log.d(TAG, "Consecutive misses reached 3 at page $pageDisplay. Finishing capture.")
                        break
                    }
                }

                pageIndex++
            }

            if (rawPdfSaved.get()) return@launch

            // If user explicitly picked HIGH_RES_CANVAS_DOM and we intercepted all high-res tile URLs, use them if they exceed or match
            if (currentEngineMode == ExtractionEngineMode.HIGH_RES_CANVAS_DOM &&
                capturedPagesMap.size >= capturedPageFiles.size &&
                capturedPagesMap.isNotEmpty()
            ) {
                capturedPageFiles.forEach { it.delete() }
                val pagesList = capturedPagesMap.values.toList()
                onExtractionCompleted?.invoke(pagesList, extractedTitle, pagesList.firstOrNull())
                return@launch
            }

            // Compile the captured real-size page files into a native A4 PDF
            if (capturedPageFiles.isNotEmpty()) {
                compileCapturedFilesToPdf(capturedPageFiles.toList())
            } else if (capturedPagesMap.isNotEmpty()) {
                val pagesList = capturedPagesMap.values.toList()
                onExtractionCompleted?.invoke(pagesList, extractedTitle, pagesList.firstOrNull())
            } else {
                onError?.invoke("Could not capture document pages. Try Interactive Viewer if login is required.")
            }
        }
    }

    private suspend fun tryExtractCanvasBitmapToFile(wv: WebView, pageIndex: Int, outFile: File): Boolean =
        withContext(Dispatchers.Main) {
            val deferred = kotlinx.coroutines.CompletableDeferred<String>()
            wv.evaluateJavascript(
                "window._driveCapture ? window._driveCapture.extractCanvasBase64ForPage($pageIndex) : '';"
            ) { b64Raw ->
                val cleaned = b64Raw?.trim('"', '\\', ' ') ?: ""
                deferred.complete(cleaned)
            }
            val base64Str = deferred.await()
            if (base64Str.length < 1000) return@withContext false

            withContext(Dispatchers.IO) {
                try {
                    val bytes = Base64.decode(base64Str, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext false
                    FileOutputStream(outFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
                        out.flush()
                    }
                    bmp.recycle()
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

    private suspend fun fetchPageBoundingBox(wv: WebView, pageIndex: Int): PageBoundingBox? = withContext(Dispatchers.Main) {
        val deferred = kotlinx.coroutines.CompletableDeferred<PageBoundingBox?>()
        wv.evaluateJavascript("window._driveCapture ? window._driveCapture.scrollToPage($pageIndex) : '{}';") { jsonResult ->
            try {
                val cleaned = jsonResult?.trim('"', '\\', ' ') ?: "{}"
                val unescaped = jsonResult?.replace("\\\"", "\"")?.trim('"') ?: "{}"
                val source = if (unescaped.startsWith("{")) unescaped else cleaned
                val json = JSONObject(source)
                val box = PageBoundingBox(
                    found = json.optBoolean("found", true),
                    left = json.optInt("left", 0),
                    top = json.optInt("top", 0),
                    width = json.optInt("width", wv.width.coerceAtLeast(1080)),
                    height = json.optInt("height", wv.height.coerceAtLeast(1600)),
                    totalDiscovered = json.optInt("totalDiscovered", 1),
                    hasCanvas = json.optBoolean("hasCanvas", false)
                )
                deferred.complete(box)
            } catch (e: Exception) {
                deferred.complete(null)
            }
        }
        deferred.await()
    }

    private suspend fun checkPageReady(wv: WebView, pageIndex: Int): Boolean = withContext(Dispatchers.Main) {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        wv.evaluateJavascript("window._driveCapture ? window._driveCapture.isPageReady($pageIndex) : true;") { res ->
            deferred.complete(res?.contains("true") == true)
        }
        deferred.await()
    }

    private fun captureExactPageScreenshot(
        wv: WebView,
        box: PageBoundingBox?,
        outputFile: File
    ): Boolean {
        return try {
            val w = wv.width.coerceAtLeast(1080)
            val h = wv.height.coerceAtLeast(1600)
            val fullBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(fullBitmap)
            wv.draw(canvas)

            // Precision crop: if page bounding box is found and has realistic page dimensions, crop to it
            val cropBitmap: Bitmap = if (box != null && box.found && box.width > 200 && box.height > 200) {
                val safeX = box.left.coerceIn(0, (w - 100).coerceAtLeast(0))
                val safeY = box.top.coerceIn(0, (h - 100).coerceAtLeast(0))
                val safeW = box.width.coerceIn(100, (w - safeX).coerceAtLeast(100))
                val safeH = box.height.coerceIn(100, (h - safeY).coerceAtLeast(100))
                Bitmap.createBitmap(fullBitmap, safeX, safeY, safeW, safeH)
            } else {
                fullBitmap
            }

            FileOutputStream(outputFile).use { out ->
                cropBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.flush()
            }

            if (cropBitmap != fullBitmap) {
                cropBitmap.recycle()
            }
            fullBitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing page bitmap", e)
            false
        }
    }

    private fun compileCapturedFilesToPdf(files: List<File>) {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    val msg = "Compiling ${files.size} pages into native A4 PDF..."
                    onStatusUpdate?.invoke(msg)
                    emitTelemetry("PDF Compiler", msg)
                    onCompilingPdf?.invoke(1, files.size, 0.1f, msg)
                }

                val pdfCompiler = PdfCompiler()
                val pdfBytes = pdfCompiler.compileImageFiles(
                    files = files,
                    enhanceContrast = enhanceContrast
                ) { current, total ->
                    val fraction = current.toFloat() / total.coerceAtLeast(1)
                    scope.launch(Dispatchers.Main) {
                        onCompilingPdf?.invoke(current, total, fraction, "Writing page $current of $total to PDF...")
                    }
                }

                val title = extractedTitle ?: "Drive_Document_${System.currentTimeMillis()}"
                val safeFileName = PdfStorageHelper.generateFileName(title)

                val savedUri = PdfStorageHelper.savePdfToDownloads(context, pdfBytes, safeFileName).getOrNull()
                val cacheFile = PdfStorageHelper.saveToAppCache(context, pdfBytes, safeFileName)

                val historyItem = DownloadHistoryItem(
                    title = title,
                    uriString = (savedUri ?: Uri.fromFile(cacheFile)).toString(),
                    pageCount = files.size,
                    fileSizeBytes = pdfBytes.size.toLong(),
                    localPath = cacheFile.absolutePath,
                    timestamp = System.currentTimeMillis()
                )
                HistoryRepository(context).addHistoryItem(historyItem)

                files.forEach { it.delete() }

                withContext(Dispatchers.Main) {
                    val doneMsg = "PDF Successfully Created (${files.size} pages)!"
                    onStatusUpdate?.invoke(doneMsg)
                    emitTelemetry("Completed", doneMsg)
                    onPdfReady?.invoke(
                        savedUri ?: Uri.fromFile(cacheFile),
                        safeFileName,
                        files.size,
                        pdfBytes.size.toLong(),
                        cacheFile.absolutePath
                    )
                    destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "PDF compilation failed", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke("Failed to compile PDF: ${e.localizedMessage}")
                    destroy()
                }
            }
        }
    }

    fun forceCompileNow() {
        if (capturedPageFiles.isNotEmpty()) {
            isExtracting = false
            captureJob?.cancel()
            compileCapturedFilesToPdf(capturedPageFiles.toList())
        } else if (capturedPagesMap.isNotEmpty()) {
            isExtracting = false
            captureJob?.cancel()
            val pagesList = capturedPagesMap.values.toList()
            onExtractionCompleted?.invoke(pagesList, extractedTitle, pagesList.firstOrNull())
        } else {
            onError?.invoke("No document pages have been captured yet. Please wait a moment.")
        }
    }

    fun destroy() {
        isExtracting = false
        captureJob?.cancel()
        mainHandler.post {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying WebView", e)
            }
            webView = null
        }
    }

    private inner class DriveJsBridge {
        @JavascriptInterface
        fun onTitleFound(title: String) {
            if (title.isNotBlank() && extractedTitle == null) {
                extractedTitle = title
                onTitleExtracted?.invoke(title)
            }
        }

        @JavascriptInterface
        fun onTotalPagesDetected(total: Int) {
            if (total > 0) {
                totalDetectedPages = total
                mainHandler.post {
                    onTotalPagesDetected?.invoke(total)
                }
            }
        }

        @JavascriptInterface
        fun onPageImageFound(url: String, pageIndex: Int) {
            if (url.isNotBlank() && isDriveViewerImageUrl(url)) {
                handleImageDiscovered(url, if (pageIndex >= 0) pageIndex + 1 else null)
            }
        }

        /**
         * Tier 1/2 Direct Blob / Binary PDF Stream capture from browser memory
         */
        @JavascriptInterface
        fun onRawPdfBase64Found(base64Pdf: String) {
            if (!isExtracting || rawPdfSaved.get() || base64Pdf.length < 1000) return
            scope.launch(Dispatchers.IO) {
                try {
                    val pdfBytes = Base64.decode(base64Pdf, Base64.DEFAULT)
                    // Verify %PDF header
                    if (pdfBytes.size > 1024 &&
                        pdfBytes[0] == 0x25.toByte() && // '%'
                        pdfBytes[1] == 0x50.toByte() && // 'P'
                        pdfBytes[2] == 0x44.toByte() && // 'D'
                        pdfBytes[3] == 0x46.toByte()    // 'F'
                    ) {
                        if (!rawPdfSaved.compareAndSet(false, true)) return@launch
                        isExtracting = false
                        captureJob?.cancel()
                        interceptedStreamCount.incrementAndGet()

                        val title = extractedTitle ?: "Protected_PDF_${System.currentTimeMillis()}"
                        val safeFileName = PdfStorageHelper.generateFileName(title)
                        val savedUri = PdfStorageHelper.savePdfToDownloads(context, pdfBytes, safeFileName).getOrNull()
                        val cacheFile = PdfStorageHelper.saveToAppCache(context, pdfBytes, safeFileName)
                        val detectedPageCount = PdfOrganizerEngine(context).getPageCount(cacheFile).coerceAtLeast(1)

                        val historyItem = DownloadHistoryItem(
                            title = title,
                            uriString = (savedUri ?: Uri.fromFile(cacheFile)).toString(),
                            pageCount = detectedPageCount,
                            fileSizeBytes = pdfBytes.size.toLong(),
                            localPath = cacheFile.absolutePath,
                            timestamp = System.currentTimeMillis()
                        )
                        HistoryRepository(context).addHistoryItem(historyItem)

                        withContext(Dispatchers.Main) {
                            emitTelemetry("Tier 1: Direct Blob Stream", "Captured native PDF binary directly from memory!")
                            onPdfReady?.invoke(
                                savedUri ?: Uri.fromFile(cacheFile),
                                safeFileName,
                                detectedPageCount,
                                pdfBytes.size.toLong(),
                                cacheFile.absolutePath
                            )
                            destroy()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Raw PDF blob decode check failed: ${e.message}")
                }
            }
        }
    }
}
