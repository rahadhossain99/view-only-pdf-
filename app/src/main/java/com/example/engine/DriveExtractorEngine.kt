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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class PageScrollInspection(
    val foundPageElement: Boolean,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val domPageCount: Int,
    val textTotalCount: Int,
    val hasCanvas: Boolean,
    val imgSrc: String,
    val reachedScrollBottom: Boolean,
    val scrollHeight: Int,
    val clientHeight: Int
)

class DriveExtractorEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var ownedWebView: WebView? = null
    private var activeWebView: WebView? = null
    private var extractedTitle: String? = null
    private var currentResolution: ResolutionQuality = ResolutionQuality.ULTRA
    private var currentEngineMode: ExtractionEngineMode = ExtractionEngineMode.AUTO_HYBRID
    private var enhanceContrast: Boolean = false
    private var isExtracting = false
    private val rawPdfSaved = AtomicBoolean(false)

    private val capturedPagesMap = ConcurrentSkipListMap<Int, String>()
    private val capturedUrlsSet = Collections.synchronizedSet(HashSet<String>())
    private val capturedPageFiles = Collections.synchronizedList(mutableListOf<File>())
    private val downloadedImgUrls = Collections.synchronizedSet(HashSet<String>())

    private val interceptedStreamCount = AtomicInteger(0)
    private val canvasHookCount = AtomicInteger(0)
    private val domTileCount = AtomicInteger(0)
    private val precisionScreenshotCount = AtomicInteger(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureJob: Job? = null
    private var totalDetectedPages: Int = 0
    private var firstInterceptedTemplateUrl: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
                    lower.contains("=w") ||
                    lower.contains("=s")

            val isUiAsset = lower.contains("cleardot.gif") ||
                    lower.contains("drive_icon") ||
                    lower.contains("photos/private") ||
                    lower.contains("default-avatar") ||
                    lower.contains(".svg") ||
                    lower.contains(".ico") ||
                    lower.contains("fonts.gstatic.com") ||
                    lower.contains("ssl.gstatic.com") ||
                    lower.contains(" avatar")

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
            val zeroIndexedPatterns = listOf(
                Regex("[?&]page=(\\d+)"),
                Regex("[?&]pg=(\\d+)")
            )
            for (pattern in zeroIndexedPatterns) {
                val match = pattern.find(url)
                if (match != null) {
                    val raw = match.groupValues[1].toIntOrNull()
                    if (raw != null) {
                        // Google Drive viewerng `page=0` is 0-indexed (page=0 -> Page 1, page=1 -> Page 2)
                        return if (url.contains("viewer") || url.contains("page=0") || url.contains("pg=0")) {
                            raw + 1
                        } else {
                            raw.coerceAtLeast(1)
                        }
                    }
                }
            }

            val oneIndexedPatterns = listOf(
                Regex("[?&]p=(\\d+)"),
                Regex("page_(\\d+)"),
                Regex("/page/(\\d+)")
            )
            for (pattern in oneIndexedPatterns) {
                val match = pattern.find(url)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
            return null
        }

        fun upgradeImageUrlResolution(originalUrl: String, quality: ResolutionQuality): String {
            var modified = originalUrl

            val widthRegex = Regex("=[ws]\\d+(-h\\d+)?(-[a-zA-Z0-9]+)*")
            val szRegex = Regex("sz=[ws]\\d+")
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
            totalDetectedPages = totalDetectedPages.coerceAtLeast(capturedPageFiles.size).coerceAtLeast(1),
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
        totalDetectedPages = 0
        firstInterceptedTemplateUrl = null
        capturedPagesMap.clear()
        capturedUrlsSet.clear()
        capturedPageFiles.clear()
        downloadedImgUrls.clear()
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

        emitTelemetry("Tier 1: Stream & Browser Init", "Initializing ${engineMode.shortBadge} multi-page pipeline...")

        mainHandler.post {
            try {
                try {
                    WebView.enableSlowWholeDocumentDraw()
                } catch (_: Exception) {}

                val wv = existingWebView ?: WebView(context).also { ownedWebView = it }
                activeWebView = wv

                if (existingWebView == null || wv.width <= 0 || wv.height <= 0) {
                    try {
                        wv.measure(
                            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY)
                        )
                        wv.layout(0, 0, 1080, 1600)
                    } catch (e: Exception) {
                        Log.d(TAG, "Layout measurement: ${e.message}")
                    }
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
                        if (firstInterceptedTemplateUrl == null && (reqUrl.contains("page=") || reqUrl.contains("pg="))) {
                            firstInterceptedTemplateUrl = reqUrl
                        }
                        val pageNum = extractPageNumberFromUrl(reqUrl)
                        handleImageDiscovered(reqUrl, pageNum)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val msg = "Connecting to Google Drive & attaching multi-page hooks..."
                onStatusUpdate?.invoke(msg)
                emitTelemetry("Tier 1 & 2: Network + Blob Hook", msg)
                injectEarlyStreamAndBlobHooks(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val msg = "Document loaded. Detecting all pages for ${currentEngineMode.shortBadge}..."
                onStatusUpdate?.invoke(msg)
                injectEarlyStreamAndBlobHooks(view)
                injectPageScraperAndCaptureScript(view)

                // Start the multi-page extraction orchestration loop
                startMultiEngineExtractionLoop(view ?: wv)
            }
        }

        wv.loadUrl(targetUrl)
    }

    @Synchronized
    private fun handleImageDiscovered(rawUrl: String, detectedPageNum: Int?) {
        if (!isExtracting) return

        val cleanBaseUrl = rawUrl
            .replace(Regex("=[ws]\\d+(-h\\d+)?(-[a-zA-Z0-9]+)*"), "")
            .replace(Regex("[?&]w=\\d+"), "")
            .replace(Regex("[?&]sz=[ws]\\d+"), "")

        if (capturedUrlsSet.contains(cleanBaseUrl)) {
            return
        }
        capturedUrlsSet.add(cleanBaseUrl)

        val highResUrl = upgradeImageUrlResolution(rawUrl, currentResolution)

        val pageNumber = if (detectedPageNum != null && detectedPageNum > 0 && !capturedPagesMap.containsKey(detectedPageNum)) {
            detectedPageNum
        } else {
            val maxKey = capturedPagesMap.keys.maxOrNull() ?: 0
            maxKey + 1
        }

        capturedPagesMap[pageNumber] = highResUrl
        val currentTotal = capturedPagesMap.size
        domTileCount.set(currentTotal)

        emitTelemetry(
            "Tier 3: High-DPI Tile Upgrader",
            "Discovered high-res page tile #$pageNumber (Total discovered: $currentTotal)"
        )

        mainHandler.post {
            onPageDiscovered?.invoke(pageNumber, highResUrl, currentTotal)
        }
    }

    /**
     * Tier 1 & Tier 2 Early Hook:
     * Hooks URL.createObjectURL, Fetch, XMLHttpRequest, and MutationObserver to capture raw PDF blobs
     * or dynamically loaded page tile images across all pages.
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
            })();
        """.trimIndent()
        view?.evaluateJavascript(hookScript, null)
    }

    /**
     * Tier 2, 3 & 4 Multi-Page DOM, Image, Canvas & Precision Bounding-Box Controller.
     * Specifically engineered for Google Drive's `.ndfHFb-c4YZDc-cYSp0e-DARUcf` per-page containers
     * and `.ndfHFb-c4YZDc-bN97Pc` scrollable viewport.
     */
    private fun injectPageScraperAndCaptureScript(view: WebView?) {
        val jsScript = """
            (function() {
                window._driveCapture = {
                    getScrollContainer: function() {
                        var knownSelectors = [
                            '.ndfHFb-c4YZDc-bN97Pc',
                            '.drive-viewer-paginated-scrollable',
                            '#viewerContainer',
                            '.ndfHFb-c4YZDc-K9a4Re'
                        ];
                        for (var i = 0; i < knownSelectors.length; i++) {
                            var els = document.querySelectorAll(knownSelectors[i]);
                            for (var k = 0; k < els.length; k++) {
                                if (els[k].scrollHeight > els[k].clientHeight + 40) {
                                    return els[k];
                                }
                            }
                        }
                        var all = document.querySelectorAll('div, main, [role="main"], [role="document"]');
                        var best = null;
                        var maxScrollHeight = 0;
                        for (var j = 0; j < all.length; j++) {
                            var el = all[j];
                            if (el.scrollHeight > el.clientHeight + 60 && el.clientHeight > 150) {
                                var st = window.getComputedStyle(el);
                                if (st.overflowY === 'auto' || st.overflowY === 'scroll' || st.overflow === 'auto' || st.overflow === 'scroll' || el.scrollTop > 0) {
                                    if (el.scrollHeight > maxScrollHeight) {
                                        maxScrollHeight = el.scrollHeight;
                                        best = el;
                                    }
                                }
                            }
                        }
                        if (best) return best;
                        return document.scrollingElement || document.documentElement || document.body;
                    },

                    hideChrome: function() {
                        var selectors = [
                            '.drive-viewer-toolstrip',
                            '.drive-viewer-action-bar',
                            '.ndfHFb-c4YZDc-j7LFlb',
                            '.ndfHFb-c4YZDc-to915-LgbsSe',
                            '.drive-viewer-popout-button',
                            '.drive-viewer-banner',
                            '.drive-viewer-navigation-overlay',
                            '#toolbarContainer',
                            '.toolbar'
                        ];
                        for (var i = 0; i < selectors.length; i++) {
                            var els = document.querySelectorAll(selectors[i]);
                            for (var j = 0; j < els.length; j++) {
                                els[j].style.opacity = '0';
                                els[j].style.pointerEvents = 'none';
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
                            if (titleElem && titleElem.textContent) {
                                var t = titleElem.textContent.trim();
                                if (t.length > 0) title = t;
                            }
                            return title;
                        } catch(e) { return ""; }
                    },

                    findPages: function() {
                        // 1. Exact Google Drive & PDF.js individual page container selectors
                        // NOTE: Never include .ndfHFb-c4YZDc-Wrql6b here as that is the single outer document wrapper!
                        var pageSelectors = [
                            '.ndfHFb-c4YZDc-cYSp0e-DARUcf',
                            '.drive-viewer-paginated-page',
                            '[data-page-number]',
                            '[data-page-index]',
                            '.pdfViewer .page',
                            '.drive-viewer-page'
                        ];
                        for (var s = 0; s < pageSelectors.length; s++) {
                            var list = document.querySelectorAll(pageSelectors[s]);
                            if (list && list.length > 0) {
                                var filtered = [];
                                for (var idx = 0; idx < list.length; idx++) {
                                    if (list[idx].offsetHeight > 120 || list[idx].clientHeight > 120) {
                                        filtered.push(list[idx]);
                                    }
                                }
                                if (filtered.length > 0) return filtered;
                                return Array.from(list);
                            }
                        }

                        // 2. Structural heuristic: find any parent container inside the viewer that holds >= 2 page-sized children
                        var allParents = document.querySelectorAll('.ndfHFb-c4YZDc-Wrql6b, .ndfHFb-c4YZDc-cYSp0e-Oz6c3e, div');
                        var bestChildren = [];
                        for (var p = 0; p < allParents.length; p++) {
                            var parent = allParents[p];
                            var ch = parent.children;
                            if (!ch || ch.length < 2) continue;
                            var pageLike = [];
                            for (var c = 0; c < ch.length; c++) {
                                var child = ch[c];
                                var h = child.offsetHeight || child.clientHeight || 0;
                                var w = child.offsetWidth || child.clientWidth || 0;
                                if (h > 220 && w > 160 && h < 5000) {
                                    pageLike.push(child);
                                }
                            }
                            if (pageLike.length >= 2 && pageLike.length > bestChildren.length) {
                                bestChildren = pageLike;
                            }
                        }
                        if (bestChildren.length >= 2) {
                            return bestChildren;
                        }

                        // 3. Fallback for 1-page documents
                        var singlePage = document.querySelector('.ndfHFb-c4YZDc-cYSp0e-DARUcf, .ndfHFb-c4YZDc-cYSp0e-DARUcf-PLDbbf');
                        if (singlePage) return [singlePage];

                        return [];
                    },

                    getTotalCount: function() {
                        var domPagesCount = this.findPages().length;
                        var bestTextTotal = 0;

                        try {
                            // Check aria-labels first
                            var ariaNodes = document.querySelectorAll('[aria-label]');
                            for (var a = 0; a < ariaNodes.length; a++) {
                                var lbl = ariaNodes[a].getAttribute('aria-label') || '';
                                var ma = /(?:Page\s+\d+\s*(?:of|\/)\s*|of\s+)(\d+)/i.exec(lbl);
                                if (ma && ma[1]) {
                                    var parsedA = parseInt(ma[1], 10);
                                    if (parsedA > bestTextTotal && parsedA < 2000) bestTextTotal = parsedA;
                                }
                            }

                            // Check textContent of toolbar / page counter nodes
                            var nodes = document.querySelectorAll('.ndfHFb-c4YZDc-j7LFlb, .ndfHFb-c4YZDc-to915-LgbsSe, #numPages, .drive-viewer-toolstrip, span, div');
                            for (var i = 0; i < nodes.length; i++) {
                                if (nodes[i].children && nodes[i].children.length > 3) continue;
                                var txt = (nodes[i].textContent || "").trim();
                                if (txt.length === 0 || txt.length > 45) continue;
                                var m1 = /(?:Page\s*\d+\s*(?:of|\/)\s*|\b\d+\s+of\s+|\b\d+\s*\/\s*)(\d+)\b/i.exec(txt);
                                if (m1 && m1[1]) {
                                    var cnt = parseInt(m1[1], 10);
                                    if (cnt > bestTextTotal && cnt < 2000) {
                                        bestTextTotal = cnt;
                                    }
                                }
                            }
                        } catch (e) {}

                        var sc = this.getScrollContainer();
                        var estimatedByScroll = 0;
                        if (sc && sc.clientHeight > 200 && sc.scrollHeight > sc.clientHeight * 1.4) {
                            var pages = this.findPages();
                            var avgPageH = (pages.length > 0 && pages[0].offsetHeight > 200) ? pages[0].offsetHeight : (sc.clientHeight * 0.85);
                            estimatedByScroll = Math.round(sc.scrollHeight / Math.max(250, avgPageH));
                        }

                        return Math.max(domPagesCount, bestTextTotal, estimatedByScroll);
                    },

                    getViewerInitState: function() {
                        var pages = this.findPages();
                        var sc = this.getScrollContainer();
                        var firstReady = false;
                        if (pages.length > 0) {
                            var img = pages[0].querySelector ? pages[0].querySelector('img') : null;
                            var canvas = pages[0].querySelector ? pages[0].querySelector('canvas') : null;
                            if (img && img.complete && img.naturalWidth > 100) firstReady = true;
                            if (canvas && canvas.width > 100) firstReady = true;
                        }
                        return JSON.stringify({
                            domPages: pages.length,
                            totalCount: this.getTotalCount(),
                            firstReady: firstReady,
                            scrollHeight: sc ? sc.scrollHeight : 0,
                            clientHeight: sc ? sc.clientHeight : 0
                        });
                    },

                    findPageImageElement: function(pageEl) {
                        if (!pageEl) return null;
                        if (pageEl.tagName && pageEl.tagName.toLowerCase() === 'img') return pageEl;
                        if (!pageEl.querySelectorAll) return null;
                        var imgs = pageEl.querySelectorAll('img');
                        var bestImg = null;
                        var bestArea = 0;
                        for (var i = 0; i < imgs.length; i++) {
                            var im = imgs[i];
                            var src = im.src || '';
                            if (src.indexOf('cleardot.gif') !== -1 || src.indexOf('.svg') !== -1) continue;
                            var area = (im.naturalWidth || im.width || 1) * (im.naturalHeight || im.height || 1);
                            if (area >= bestArea) {
                                bestArea = area;
                                bestImg = im;
                            }
                        }
                        return bestImg;
                    },

                    scrollToPage: function(idx) {
                        this.hideChrome();
                        var sc = this.getScrollContainer();
                        var pages = this.findPages();
                        var totalCount = this.getTotalCount();

                        if (pages && idx < pages.length) {
                            var pageEl = pages[idx];
                            try {
                                pageEl.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'center' });
                            } catch (e) {
                                if (sc) sc.scrollTop = pageEl.offsetTop;
                            }

                            try {
                                if (sc) sc.dispatchEvent(new Event('scroll', { bubbles: true }));
                                pageEl.dispatchEvent(new Event('scroll', { bubbles: true }));
                                window.dispatchEvent(new Event('scroll'));
                            } catch (e) {}

                            var img = this.findPageImageElement(pageEl);
                            var imgSrc = (img && img.src) ? img.src : "";
                            if (imgSrc && window.DriveBridge && window.DriveBridge.onPageImageFound) {
                                window.DriveBridge.onPageImageFound(imgSrc, idx);
                            }

                            var hasCanvas = false;
                            try {
                                var c = (pageEl.tagName && pageEl.tagName.toLowerCase() === 'canvas')
                                    ? pageEl
                                    : (pageEl.querySelector ? pageEl.querySelector('canvas') : null);
                                hasCanvas = !!(c && c.width > 150 && c.height > 150);
                            } catch (e) {}

                            var rect = pageEl.getBoundingClientRect();
                            var atBottom = sc ? (sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 25) : false;

                            return JSON.stringify({
                                foundPageElement: true,
                                left: Math.round(rect.left),
                                top: Math.round(rect.top),
                                width: Math.round(rect.width),
                                height: Math.round(rect.height),
                                domPageCount: pages.length,
                                textTotalCount: totalCount,
                                hasCanvas: hasCanvas,
                                imgSrc: imgSrc,
                                reachedScrollBottom: atBottom,
                                scrollHeight: sc ? sc.scrollHeight : 0,
                                clientHeight: sc ? sc.clientHeight : 0
                            });
                        }

                        // Fallback continuous scroll when page containers are virtualized or not yet mounted
                        var curScroll = sc ? sc.scrollTop : (window.pageYOffset || 0);
                        var cHeight = sc ? sc.clientHeight : (window.innerHeight || 1200);
                        var sHeight = sc ? sc.scrollHeight : (document.body.scrollHeight || 1200);
                        var step = Math.max(450, Math.round(cHeight * 0.88));

                        if (idx === 0) {
                            if (sc) sc.scrollTop = 0;
                            window.scrollTo(0, 0);
                        } else {
                            if (sc) sc.scrollTop = curScroll + step;
                            window.scrollBy(0, step);
                        }

                        try {
                            if (sc) sc.dispatchEvent(new Event('scroll', { bubbles: true }));
                            window.dispatchEvent(new Event('scroll'));
                        } catch (e) {}

                        var newScroll = sc ? sc.scrollTop : (window.pageYOffset || 0);
                        var reachedBottom = (idx > 0) && (newScroll + cHeight >= sHeight - 25) && (Math.abs(newScroll - curScroll) < 10);

                        return JSON.stringify({
                            foundPageElement: false,
                            left: 0,
                            top: 0,
                            width: window.innerWidth || 1080,
                            height: window.innerHeight || 1600,
                            domPageCount: pages ? pages.length : 0,
                            textTotalCount: totalCount,
                            hasCanvas: false,
                            imgSrc: "",
                            reachedScrollBottom: reachedBottom,
                            scrollHeight: sHeight,
                            clientHeight: cHeight
                        });
                    },

                    getPageReadyAndSource: function(idx) {
                        var pages = this.findPages();
                        if (!pages || idx >= pages.length) {
                            return JSON.stringify({ ready: false, imgSrc: "", hasCanvas: false, width: 0, height: 0 });
                        }
                        var pageEl = pages[idx];
                        var rect = pageEl.getBoundingClientRect();
                        var img = this.findPageImageElement(pageEl);
                        if (img) {
                            var isLoaded = !!(img.complete && img.naturalWidth > 120 && img.naturalHeight > 120);
                            var src = img.src || "";
                            if (isLoaded && src && window.DriveBridge && window.DriveBridge.onPageImageFound) {
                                window.DriveBridge.onPageImageFound(src, idx);
                            }
                            return JSON.stringify({
                                ready: isLoaded,
                                imgSrc: src,
                                hasCanvas: false,
                                left: Math.round(rect.left),
                                top: Math.round(rect.top),
                                width: Math.round(rect.width),
                                height: Math.round(rect.height)
                            });
                        }
                        var canvas = (pageEl.tagName && pageEl.tagName.toLowerCase() === 'canvas')
                            ? pageEl
                            : (pageEl.querySelector ? pageEl.querySelector('canvas') : null);
                        if (canvas && canvas.width > 150 && canvas.height > 150) {
                            return JSON.stringify({
                                ready: true,
                                imgSrc: "",
                                hasCanvas: true,
                                left: Math.round(rect.left),
                                top: Math.round(rect.top),
                                width: Math.round(rect.width),
                                height: Math.round(rect.height)
                            });
                        }
                        return JSON.stringify({
                            ready: false,
                            imgSrc: "",
                            hasCanvas: false,
                            left: Math.round(rect.left),
                            top: Math.round(rect.top),
                            width: Math.round(rect.width),
                            height: Math.round(rect.height)
                        });
                    },

                    extractPageBitmapBase64: function(idx) {
                        try {
                            var pages = this.findPages();
                            if (!pages || idx >= pages.length) return "";
                            var pageEl = pages[idx];

                            // 1. Check for <canvas>
                            var canvas = (pageEl.tagName && pageEl.tagName.toLowerCase() === 'canvas')
                                ? pageEl
                                : (pageEl.querySelector ? pageEl.querySelector('canvas') : null);
                            if (canvas && canvas.width > 150 && canvas.height > 150) {
                                var cUrl = canvas.toDataURL('image/jpeg', 0.94);
                                if (cUrl && cUrl.length > 2000) {
                                    return cUrl.split(',')[1] || "";
                                }
                            }

                            // 2. Check for <img> and draw its full naturalWidth x naturalHeight onto an offscreen canvas
                            var img = this.findPageImageElement(pageEl);
                            if (img && img.complete && img.naturalWidth > 150 && img.naturalHeight > 150) {
                                var offCanvas = document.createElement('canvas');
                                offCanvas.width = img.naturalWidth;
                                offCanvas.height = img.naturalHeight;
                                var ctx = offCanvas.getContext('2d');
                                ctx.fillStyle = '#FFFFFF';
                                ctx.fillRect(0, 0, offCanvas.width, offCanvas.height);
                                ctx.drawImage(img, 0, 0, offCanvas.width, offCanvas.height);
                                var iUrl = offCanvas.toDataURL('image/jpeg', 0.94);
                                if (iUrl && iUrl.length > 2000) {
                                    return iUrl.split(',')[1] || "";
                                }
                            }
                        } catch (e) {}
                        return "";
                    }
                };
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsScript, null)
    }

    private fun startMultiEngineExtractionLoop(wv: WebView) {
        captureJob?.cancel()
        captureJob = scope.launch(Dispatchers.Main) {
            // Poll up to 6.5 seconds for Google Drive's PDF Viewer JS to finish initializing its page containers
            var initAttempts = 0
            while (isExtracting && !rawPdfSaved.get() && initAttempts < 15) {
                delay(420)
                val initState = fetchViewerInitState(wv)
                val domPages = initState.first
                val totalDetected = initState.second
                val firstReady = initState.third

                if (totalDetected > totalDetectedPages) {
                    totalDetectedPages = totalDetected
                    onTotalPagesDetected?.invoke(totalDetected)
                }

                if (domPages > 0 && (firstReady || initAttempts >= 6)) {
                    break
                }
                initAttempts++
            }

            if (!isExtracting || rawPdfSaved.get()) return@launch

            // 1. Scrape Document Title
            wv.evaluateJavascript("window._driveCapture ? window._driveCapture.getTitle() : '';") { titleRaw ->
                val clean = titleRaw?.trim('"', '\\', ' ')?.takeIf { it.isNotBlank() && it != "null" }
                if (clean != null && extractedTitle == null) {
                    extractedTitle = clean
                    onTitleExtracted?.invoke(clean)
                }
            }

            // 2. Refresh Total Pages Count
            val initialTotal = queryTotalCount(wv)
            if (initialTotal > totalDetectedPages) {
                totalDetectedPages = initialTotal
                onTotalPagesDetected?.invoke(initialTotal)
            }

            val startMsg = if (totalDetectedPages > 1) {
                "Auto-extracting all $totalDetectedPages pages (${currentEngineMode.shortBadge})..."
            } else {
                "Scanning & capturing all document pages (${currentEngineMode.shortBadge})..."
            }
            onStatusUpdate?.invoke(startMsg)
            emitTelemetry(currentEngineMode.shortBadge, startMsg)

            var pageIndex = 0
            var consecutiveMisses = 0
            val maxLimit = 400

            while (isExtracting && !rawPdfSaved.get() && pageIndex < maxLimit) {
                val pageDisplay = pageIndex + 1

                // Scroll to exact page element (or next scroll viewport)
                val inspection = scrollAndInspectPage(wv, pageIndex)
                if (inspection != null) {
                    val discoveredMax = maxOf(inspection.domPageCount, inspection.textTotalCount)
                    if (discoveredMax > totalDetectedPages) {
                        totalDetectedPages = discoveredMax
                        onTotalPagesDetected?.invoke(totalDetectedPages)
                    }
                }

                // If we scrolled past the last DOM page AND reached the bottom of the scroll container, we are done!
                if (inspection != null &&
                    pageIndex > 0 &&
                    !inspection.foundPageElement &&
                    inspection.reachedScrollBottom
                ) {
                    Log.d(TAG, "Reached bottom of scroll container at page index $pageIndex.")
                    break
                }

                // Wait for Google Drive to lazy-load the high-res <img> or <canvas> for this specific page
                // Poll every 250ms up to 3.5 seconds so slower pages never get skipped or captured blank!
                var readyInfo = pollUntilPageReady(wv, pageIndex, maxWaitMs = 3500L)
                var pageImgUrl = readyInfo.optString("imgSrc", "").ifBlank { inspection?.imgSrc ?: "" }
                val hasCanvas = readyInfo.optBoolean("hasCanvas", inspection?.hasCanvas == true)

                val tempFile = File(context.cacheDir, "drive_page_${System.currentTimeMillis()}_$pageDisplay.jpg")
                var pageSaved = false

                // STAGE A: Direct Full-Page High-Res Image Download from the page's <img src="...">
                // This downloads the 100% full, uncropped page image directly from Google's server using WebView cookies!
                if (pageImgUrl.startsWith("http://") || pageImgUrl.startsWith("https://")) {
                    val downloaded = downloadPageImageToFile(pageImgUrl, tempFile)
                    if (downloaded && tempFile.length() > 2048) {
                        pageSaved = true
                        domTileCount.incrementAndGet()
                        emitTelemetry(
                            "Tier 3: High-DPI Full Page Tile",
                            "Downloaded exact full-size Page $pageDisplay (${currentResolution.label})"
                        )
                    }
                }

                // STAGE B: In-Browser HTML5 <canvas> or <img> naturalWidth x naturalHeight Base64 Extractor
                if (!pageSaved) {
                    val canvasSaved = tryExtractPageBitmapBase64ToFile(wv, pageIndex, tempFile)
                    if (canvasSaved && tempFile.length() > 2048) {
                        pageSaved = true
                        val hooks = canvasHookCount.incrementAndGet()
                        emitTelemetry(
                            "Tier 2: Canvas / Blob Extractor",
                            "Extracted full-page bitmap for Page $pageDisplay (#$hooks)"
                        )
                    }
                }

                // STAGE C: Precision Bounding-Box Page Screenshot Fallback
                if (!pageSaved && (inspection?.foundPageElement == true || pageIndex == 0 || !inspection?.reachedScrollBottom!!)) {
                    // Refresh bounding rect right before drawing
                    readyInfo = pollUntilPageReady(wv, pageIndex, maxWaitMs = 300L)
                    val cropLeft = readyInfo.optInt("left", inspection?.left ?: 0)
                    val cropTop = readyInfo.optInt("top", inspection?.top ?: 0)
                    val cropWidth = readyInfo.optInt("width", inspection?.width ?: wv.width)
                    val cropHeight = readyInfo.optInt("height", inspection?.height ?: wv.height)

                    val screenshotSuccess = captureExactPageScreenshot(
                        wv = wv,
                        foundElement = inspection?.foundPageElement == true,
                        left = cropLeft,
                        top = cropTop,
                        width = cropWidth,
                        height = cropHeight,
                        outputFile = tempFile
                    )
                    if (screenshotSuccess && tempFile.length() > 2048) {
                        pageSaved = true
                        precisionScreenshotCount.incrementAndGet()
                    }
                }

                if (pageSaved) {
                    consecutiveMisses = 0
                    capturedPageFiles.add(tempFile)

                    val latestDomPages = inspection?.domPageCount ?: 0
                    val effectiveTotal = maxOf(totalDetectedPages, latestDomPages, capturedPageFiles.size)
                    if (effectiveTotal > totalDetectedPages) {
                        totalDetectedPages = effectiveTotal
                    }

                    val statusMsg = "Captured Page $pageDisplay of $effectiveTotal (${currentEngineMode.shortBadge})..."
                    onStatusUpdate?.invoke(statusMsg)
                    onPageCaptured?.invoke(pageDisplay, effectiveTotal, statusMsg)
                    emitTelemetry(
                        "Multi-Page Capture Active",
                        "Captured full page $pageDisplay of $effectiveTotal"
                    )

                    // Only stop when we have captured ALL known pages AND:
                    // - Either totalDetectedPages > 1 and we reached it (with no more DOM pages discovered)
                    // - Or it's genuinely a 1-page document where domPageCount <= 1 and scrollHeight <= clientHeight * 1.25
                    if (latestDomPages > 1 && capturedPageFiles.size >= latestDomPages && pageIndex + 1 >= latestDomPages) {
                        // Scroll once more to check if Google Drive dynamically appends more pages at the bottom
                        val checkMore = scrollAndInspectPage(wv, pageIndex + 1)
                        val newDomCount = checkMore?.domPageCount ?: latestDomPages
                        if (newDomCount <= latestDomPages) {
                            Log.d(TAG, "Captured all $latestDomPages DOM pages!")
                            break
                        } else {
                            totalDetectedPages = maxOf(totalDetectedPages, newDomCount)
                        }
                    } else if (latestDomPages <= 1 && totalDetectedPages <= 1) {
                        val sHeight = inspection?.scrollHeight ?: 0
                        val cHeight = (inspection?.clientHeight ?: 1).coerceAtLeast(1)
                        if (sHeight <= (cHeight * 1.25).toInt()) {
                            // Genuinely a single-page document
                            Log.d(TAG, "Single page document confirmed by scrollHeight ($sHeight <= 1.25 * $cHeight).")
                            break
                        }
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

            // Fallback Probe: If only <= 1 page was captured via DOM, but we intercepted a `page=` template URL
            // or discovered more URLs in `capturedPagesMap`, probe remaining pages directly via HTTP!
            if (capturedPageFiles.size <= 1) {
                val probeTemplate = firstInterceptedTemplateUrl ?: capturedPagesMap.values.firstOrNull {
                    it.contains("page=") || it.contains("pg=")
                }
                if (probeTemplate != null) {
                    probeAdditionalPagesViaHttp(probeTemplate)
                } else if (capturedPagesMap.size > capturedPageFiles.size) {
                    downloadDiscoveredMapUrlsToFiles()
                }
            }

            // Compile all captured full-size page files into a native A4 PDF
            if (capturedPageFiles.isNotEmpty()) {
                compileCapturedFilesToPdf(capturedPageFiles.toList())
            } else if (capturedPagesMap.isNotEmpty()) {
                val pagesList = capturedPagesMap.values.toList()
                onExtractionCompleted?.invoke(pagesList, extractedTitle, pagesList.firstOrNull())
            } else {
                onError?.invoke("Could not capture document pages. Try Interactive Browser if Google sign-in is required.")
            }
        }
    }

    /**
     * Downloads a single page's `<img src="...">` at upgraded resolution (with automatic fallback to original URL)
     * using the WebView's cookies so protected Google Drive viewer tiles download cleanly.
     */
    private suspend fun downloadPageImageToFile(rawImgUrl: String, outFile: File): Boolean = withContext(Dispatchers.IO) {
        val highResUrl = upgradeImageUrlResolution(rawImgUrl, currentResolution)
        val candidates = if (highResUrl != rawImgUrl) listOf(highResUrl, rawImgUrl) else listOf(rawImgUrl)

        for (candidateUrl in candidates) {
            try {
                val cookie = CookieManager.getInstance().getCookie(candidateUrl)
                    ?: CookieManager.getInstance().getCookie("https://drive.google.com")
                val reqBuilder = Request.Builder()
                    .url(candidateUrl)
                    .addHeader("User-Agent", DESKTOP_USER_AGENT)
                    .addHeader("Referer", "https://drive.google.com/")
                    .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")

                if (!cookie.isNullOrBlank()) {
                    reqBuilder.addHeader("Cookie", cookie)
                }

                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.size > 1500) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null && bmp.width > 120 && bmp.height > 120) {
                                FileOutputStream(outFile).use { out ->
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
                                    out.flush()
                                }
                                bmp.recycle()
                                return@withContext true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Direct page image download attempt failed: ${e.message}")
            }
        }
        false
    }

    /**
     * If Google Drive uses `viewerng/img?id=...&page=0` URLs and DOM capture only got 1 page,
     * automatically probes page=0, 1, 2, 3... via HTTP with WebView cookies!
     */
    private suspend fun probeAdditionalPagesViaHttp(templateUrl: String) = withContext(Dispatchers.IO) {
        try {
            val probeFiles = mutableListOf<File>()
            // Determine whether 0-indexed (`page=0`) or 1-indexed (`page=1`)
            val page0Url = buildPageUrl(templateUrl, 0, currentResolution)
            val file0 = File(context.cacheDir, "probe_page_${System.currentTimeMillis()}_0.jpg")
            val startIndex = if (downloadPageImageToFile(page0Url, file0)) {
                probeFiles.add(file0)
                1
            } else {
                file0.delete()
                1
            }

            var currentIdx = startIndex
            var misses = 0
            while (isExtracting && misses < 2 && currentIdx < 300) {
                val pageNumDisplay = probeFiles.size + 1
                withContext(Dispatchers.Main) {
                    val msg = "Probing high-res Page $pageNumDisplay..."
                    onStatusUpdate?.invoke(msg)
                    onPageCaptured?.invoke(pageNumDisplay, maxOf(totalDetectedPages, pageNumDisplay), msg)
                }

                val nextUrl = buildPageUrl(templateUrl, currentIdx, currentResolution)
                val nextFile = File(context.cacheDir, "probe_page_${System.currentTimeMillis()}_$currentIdx.jpg")
                if (downloadPageImageToFile(nextUrl, nextFile) && nextFile.length() > 2048) {
                    misses = 0
                    probeFiles.add(nextFile)
                    domTileCount.set(probeFiles.size)
                } else {
                    nextFile.delete()
                    misses++
                }
                currentIdx++
                delay(140)
            }

            if (probeFiles.size > capturedPageFiles.size) {
                capturedPageFiles.forEach { it.delete() }
                capturedPageFiles.clear()
                capturedPageFiles.addAll(probeFiles)
            } else {
                probeFiles.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP page probe fallback error: ${e.message}")
        }
    }

    private suspend fun downloadDiscoveredMapUrlsToFiles() = withContext(Dispatchers.IO) {
        val urls = capturedPagesMap.values.toList()
        val downloaded = mutableListOf<File>()
        for ((idx, url) in urls.withIndex()) {
            if (!isExtracting) break
            val f = File(context.cacheDir, "map_page_${System.currentTimeMillis()}_${idx + 1}.jpg")
            if (downloadPageImageToFile(url, f) && f.length() > 2048) {
                downloaded.add(f)
                withContext(Dispatchers.Main) {
                    onPageCaptured?.invoke(downloaded.size, urls.size, "Downloaded page ${downloaded.size} of ${urls.size}...")
                }
            } else {
                f.delete()
            }
        }
        if (downloaded.size > capturedPageFiles.size) {
            capturedPageFiles.forEach { it.delete() }
            capturedPageFiles.clear()
            capturedPageFiles.addAll(downloaded)
        } else {
            downloaded.forEach { it.delete() }
        }
    }

    private suspend fun fetchViewerInitState(wv: WebView): Triple<Int, Int, Boolean> = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Triple<Int, Int, Boolean>>()
        wv.evaluateJavascript(
            "window._driveCapture ? window._driveCapture.getViewerInitState() : '{}';"
        ) { raw ->
            try {
                val unescaped = raw?.replace("\\\"", "\"")?.trim('"', ' ') ?: "{}"
                val json = JSONObject(unescaped)
                deferred.complete(
                    Triple(
                        json.optInt("domPages", 0),
                        json.optInt("totalCount", 0),
                        json.optBoolean("firstReady", false)
                    )
                )
            } catch (e: Exception) {
                deferred.complete(Triple(0, 0, false))
            }
        }
        deferred.await()
    }

    private suspend fun queryTotalCount(wv: WebView): Int = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Int>()
        wv.evaluateJavascript("window._driveCapture ? window._driveCapture.getTotalCount() : 0;") { countRaw ->
            val count = countRaw?.trim('"', ' ')?.toIntOrNull() ?: 0
            deferred.complete(count)
        }
        deferred.await()
    }

    private suspend fun tryExtractPageBitmapBase64ToFile(wv: WebView, pageIndex: Int, outFile: File): Boolean =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()
            wv.evaluateJavascript(
                "window._driveCapture ? window._driveCapture.extractPageBitmapBase64($pageIndex) : '';"
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

    private suspend fun scrollAndInspectPage(wv: WebView, pageIndex: Int): PageScrollInspection? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<PageScrollInspection?>()
            wv.evaluateJavascript("window._driveCapture ? window._driveCapture.scrollToPage($pageIndex) : '{}';") { jsonResult ->
                try {
                    val unescaped = jsonResult?.replace("\\\"", "\"")?.trim('"', ' ') ?: "{}"
                    val json = JSONObject(unescaped)
                    deferred.complete(
                        PageScrollInspection(
                            foundPageElement = json.optBoolean("foundPageElement", false),
                            left = json.optInt("left", 0),
                            top = json.optInt("top", 0),
                            width = json.optInt("width", wv.width.coerceAtLeast(1080)),
                            height = json.optInt("height", wv.height.coerceAtLeast(1600)),
                            domPageCount = json.optInt("domPageCount", 0),
                            textTotalCount = json.optInt("textTotalCount", 0),
                            hasCanvas = json.optBoolean("hasCanvas", false),
                            imgSrc = json.optString("imgSrc", ""),
                            reachedScrollBottom = json.optBoolean("reachedScrollBottom", false),
                            scrollHeight = json.optInt("scrollHeight", 0),
                            clientHeight = json.optInt("clientHeight", 0)
                        )
                    )
                } catch (e: Exception) {
                    deferred.complete(null)
                }
            }
            deferred.await()
        }

    private suspend fun pollUntilPageReady(wv: WebView, pageIndex: Int, maxWaitMs: Long): JSONObject =
        withContext(Dispatchers.Main) {
            var elapsed = 0L
            var lastJson = JSONObject()
            while (elapsed < maxWaitMs && isExtracting) {
                val deferred = CompletableDeferred<JSONObject>()
                wv.evaluateJavascript(
                    "window._driveCapture ? window._driveCapture.getPageReadyAndSource($pageIndex) : '{}';"
                ) { raw ->
                    try {
                        val unescaped = raw?.replace("\\\"", "\"")?.trim('"', ' ') ?: "{}"
                        deferred.complete(JSONObject(unescaped))
                    } catch (e: Exception) {
                        deferred.complete(JSONObject())
                    }
                }
                lastJson = deferred.await()
                if (lastJson.optBoolean("ready", false)) {
                    return@withContext lastJson
                }
                delay(240)
                elapsed += 240
            }
            lastJson
        }

    private fun captureExactPageScreenshot(
        wv: WebView,
        foundElement: Boolean,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        outputFile: File
    ): Boolean {
        return try {
            val w = wv.width.coerceAtLeast(1080)
            val h = wv.height.coerceAtLeast(1600)
            val fullBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(fullBitmap)
            wv.draw(canvas)

            // Density scale factor between CSS pixels (from getBoundingClientRect) and Android View pixels
            @Suppress("DEPRECATION")
            val scale = wv.scale.takeIf { it > 0.1f } ?: context.resources.displayMetrics.density

            val cropBitmap: Bitmap = if (foundElement && width > 120 && height > 120) {
                val pxLeft = (left * scale).toInt().coerceIn(0, (w - 100).coerceAtLeast(0))
                val pxTop = (top * scale).toInt().coerceIn(0, (h - 100).coerceAtLeast(0))
                val pxWidth = (width * scale).toInt().coerceIn(100, (w - pxLeft).coerceAtLeast(100))
                val pxHeight = (height * scale).toInt().coerceIn(100, (h - pxTop).coerceAtLeast(100))
                Bitmap.createBitmap(fullBitmap, pxLeft, pxTop, pxWidth, pxHeight)
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
                activeWebView?.stopLoading()
                ownedWebView?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping/destroying WebView", e)
            }
            ownedWebView = null
            activeWebView = null
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
            if (total > totalDetectedPages) {
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
