package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.ResolutionQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentSkipListMap

class DriveExtractorEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var webView: WebView? = null
    private var extractedTitle: String? = null
    private var currentResolution: ResolutionQuality = ResolutionQuality.ULTRA
    private var isExtracting = false

    private val capturedPagesMap = ConcurrentSkipListMap<Int, String>()
    private val capturedUrlsSet = Collections.synchronizedSet(HashSet<String>())
    private var mainHandler = Handler(Looper.getMainLooper())
    private var finishDebounceRunnable: Runnable? = null
    private var isScrollCompleted = false

    var onStatusUpdate: ((message: String) -> Unit)? = null
    var onTitleExtracted: ((title: String) -> Unit)? = null
    var onPageDiscovered: ((pageNumber: Int, highResUrl: String, totalCaptured: Int) -> Unit)? = null
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
                    lower.contains("sz=w") ||
                    lower.contains("=w")

            val isUiAsset = lower.contains("cleardot.gif") ||
                    lower.contains("drive_icon") ||
                    lower.contains("photos/private") ||
                    lower.contains("default-avatar") ||
                    lower.contains(".svg") ||
                    lower.contains(".ico")

            return isDriveOrGoogle && isViewerPath && !isUiAsset
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

        fun buildPageUrl(
            templateUrl: String,
            pageNumber: Int,
            quality: ResolutionQuality
        ): String {
            var modified = templateUrl

            val pagePatterns = listOf(
                Regex("([?&]page=)(\\d+)") to "$1$pageNumber",
                Regex("([?&]pg=)(\\d+)") to "$1$pageNumber",
                Regex("([?&]p=)(\\d+)") to "$1$pageNumber",
                Regex("(page_)(\\d+)") to "$1$pageNumber",
                Regex("(/page/)(\\d+)") to "$1$pageNumber"
            )

            var replaced = false
            for ((regex, replacement) in pagePatterns) {
                if (regex.containsMatchIn(modified)) {
                    modified = modified.replace(regex, replacement)
                    replaced = true
                    break
                }
            }

            if (!replaced) {
                val sep = if (modified.contains("?")) "&" else "?"
                modified = "$modified${sep}page=$pageNumber"
            }

            return upgradeImageUrlResolution(modified, quality)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun startExtraction(
        url: String,
        resolution: ResolutionQuality = ResolutionQuality.ULTRA,
        existingWebView: WebView? = null
    ) {
        currentResolution = resolution
        extractedTitle = null
        isExtracting = true
        isScrollCompleted = false
        capturedPagesMap.clear()
        capturedUrlsSet.clear()
        cancelFinishDebounce()

        val targetUrl = sanitizeDriveUrl(url)
        if (targetUrl.isBlank()) {
            onError?.invoke("Invalid Google Drive URL provided.")
            return
        }

        mainHandler.post {
            try {
                val wv = existingWebView ?: WebView(context).also { webView = it }
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

        wv.addJavascriptInterface(DriveJsBridge(), "DriveBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null && isExtracting) {
                    val reqUrl = request.url.toString()
                    if (isDriveViewerImageUrl(reqUrl)) {
                        val pageNum = extractPageNumberFromUrl(reqUrl)
                        handleImageDiscovered(reqUrl, pageNum)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onStatusUpdate?.invoke("Loading Google Drive document viewer...")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onStatusUpdate?.invoke("Document viewer loaded. Scrolling to capture all pages...")
                injectTitleScraper(view)
                injectAutoScroller(view)

                // Safety timeout: if after 15 seconds we have at least 1 page, trigger compile
                mainHandler.postDelayed({
                    if (isExtracting && capturedPagesMap.isNotEmpty()) {
                        Log.d(TAG, "Safety timer fired, triggering completion with ${capturedPagesMap.size} pages")
                        triggerCompletion()
                    }
                }, 15000)
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
            // Sequential ordering
            val maxKey = capturedPagesMap.keys.maxOrNull() ?: 0
            maxKey + 1
        }

        capturedPagesMap[pageNumber] = highResUrl
        val currentTotal = capturedPagesMap.size

        mainHandler.post {
            onPageDiscovered?.invoke(pageNumber, highResUrl, currentTotal)
            onStatusUpdate?.invoke("Captured page $pageNumber (Total: $currentTotal pages detected)...")
        }

        // Debounce: if no new pages are captured within 3.5 seconds and scroll completed, or 5 seconds otherwise
        val debounceDelay = if (isScrollCompleted) 2000L else 4000L
        scheduleFinishDebounce(debounceDelay)
    }

    private fun scheduleFinishDebounce(delayMs: Long) {
        cancelFinishDebounce()
        finishDebounceRunnable = Runnable {
            if (isExtracting && capturedPagesMap.isNotEmpty()) {
                Log.d(TAG, "Debounce timer expired, auto-completing with ${capturedPagesMap.size} pages")
                triggerCompletion()
            }
        }
        mainHandler.postDelayed(finishDebounceRunnable!!, delayMs)
    }

    private fun cancelFinishDebounce() {
        finishDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        finishDebounceRunnable = null
    }

    private fun triggerCompletion() {
        if (!isExtracting) return
        isExtracting = false
        cancelFinishDebounce()

        val pagesList = capturedPagesMap.values.toList()
        val firstUrl = pagesList.firstOrNull()

        // Flush cookies to OkHttp
        CookieManager.getInstance().flush()

        mainHandler.post {
            val title = extractedTitle ?: webView?.title
                ?.replace(" - Google Drive", "")
                ?.replace(" - Google Docs", "")
                ?.trim()

            onExtractionCompleted?.invoke(pagesList, title, firstUrl)
            destroy()
        }
    }

    fun forceCompileNow() {
        if (capturedPagesMap.isNotEmpty()) {
            triggerCompletion()
        } else {
            onError?.invoke("No document pages have been captured yet. Please wait a moment.")
        }
    }

    private fun injectTitleScraper(view: WebView?) {
        val jsScript = """
            (function() {
                try {
                    var title = document.title || "";
                    title = title.replace(" - Google Drive", "").replace(" - Google Docs", "").trim();
                    var titleElem = document.querySelector('.ndfHFb-c4YZDc-s2gQvd') || 
                                    document.querySelector('.drive-viewer-toolstrip-name') ||
                                    document.querySelector('.drive-viewer-title');
                    if (titleElem && titleElem.innerText) {
                        title = titleElem.innerText.trim();
                    }
                    if (title) {
                        window.DriveBridge.onTitleFound(title);
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsScript, null)
    }

    private fun injectAutoScroller(view: WebView?) {
        val jsScript = """
            (function() {
                if (window._driveAutoScrollerActive) return;
                window._driveAutoScrollerActive = true;

                function findScrollElement() {
                    return document.querySelector('.drive-viewer-paginated-scrollable') || 
                           document.querySelector('.ndfHFb-c4YZDc-bN97Pc') ||
                           document.querySelector('.drive-viewer-content') ||
                           document.documentElement || 
                           document.body;
                }

                function scrapeDomImages() {
                    var imgs = document.querySelectorAll('img');
                    for (var i = 0; i < imgs.length; i++) {
                        var img = imgs[i];
                        var src = img.src || img.getAttribute('src') || img.getAttribute('data-src');
                        if (src && (src.indexOf('viewer') !== -1 || src.indexOf('googleusercontent') !== -1 || src.indexOf('drive') !== -1)) {
                            var pageIdx = -1;
                            var parent = img.closest('[data-page-index], [data-page-number], .drive-viewer-paginated-page');
                            if (parent) {
                                var idx = parent.getAttribute('data-page-index') || parent.getAttribute('data-page-number');
                                if (idx) pageIdx = parseInt(idx, 10);
                            }
                            if (window.DriveBridge && window.DriveBridge.onPageImageFound) {
                                window.DriveBridge.onPageImageFound(src, pageIdx);
                            }
                        }
                    }
                }

                var stagnantCycles = 0;
                var lastScroll = -1;
                var scrollTimer = setInterval(function() {
                    var el = findScrollElement();
                    scrapeDomImages();

                    var curScroll = el.scrollTop || window.pageYOffset || 0;
                    var maxScroll = (el.scrollHeight || document.body.scrollHeight) - (el.clientHeight || window.innerHeight);

                    if (curScroll === lastScroll || (maxScroll > 0 && curScroll >= maxScroll - 40)) {
                        stagnantCycles++;
                        if (stagnantCycles >= 4) {
                            clearInterval(scrollTimer);
                            scrapeDomImages();
                            if (window.DriveBridge && window.DriveBridge.onScrollFinished) {
                                window.DriveBridge.onScrollFinished();
                            }
                        }
                    } else {
                        stagnantCycles = 0;
                    }
                    lastScroll = curScroll;

                    var step = Math.max(350, (el.clientHeight || window.innerHeight) * 0.75);
                    if (el.scrollTop !== undefined && el.scrollHeight > el.clientHeight) {
                        el.scrollTop = curScroll + step;
                    } else {
                        window.scrollBy(0, step);
                    }
                }, 350);
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsScript, null)
    }

    fun destroy() {
        isExtracting = false
        cancelFinishDebounce()
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
        fun onPageImageFound(url: String, pageIndex: Int) {
            if (url.isNotBlank() && isDriveViewerImageUrl(url)) {
                handleImageDiscovered(url, if (pageIndex >= 0) pageIndex + 1 else null)
            }
        }

        @JavascriptInterface
        fun onScrollFinished() {
            Log.d(TAG, "JS reported scroll finished. Captured count: ${capturedPagesMap.size}")
            isScrollCompleted = true
            if (capturedPagesMap.isNotEmpty()) {
                scheduleFinishDebounce(1000L)
            }
        }
    }
}
