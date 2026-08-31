package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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

class DriveExtractorEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var webView: WebView? = null
    private var extractedTitle: String? = null
    private var currentResolution: ResolutionQuality = ResolutionQuality.ULTRA
    private var isExtracting = false
    private var hasCapturedFirstImage = false

    var onStatusUpdate: ((message: String) -> Unit)? = null
    var onTitleExtracted: ((title: String) -> Unit)? = null
    var onFirstImageCaptured: ((firstImageUrl: String, title: String?) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    companion object {
        private const val TAG = "DriveExtractorEngine"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun sanitizeDriveUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return ""

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
                    val fileId = match.groupValues[1]
                    return "https://drive.google.com/file/d/$fileId/preview"
                }
            }

            return if (trimmed.contains("drive.google.com") && trimmed.contains("/view")) {
                trimmed.replace("/view", "/preview")
            } else {
                trimmed
            }
        }

        fun isDriveViewerImageUrl(url: String): Boolean {
            val isDriveImg = url.contains("/viewer/img") ||
                    url.contains("drive.google.com/viewer") ||
                    url.contains("drive-viewer") ||
                    url.contains("googleusercontent.com/drive-viewer") ||
                    url.contains("drive.google.com/thumbnail")

            val hasPageOrId = url.contains("page=") ||
                    url.contains("pg=") ||
                    url.contains("p=") ||
                    url.contains("page_") ||
                    url.contains("/page/") ||
                    url.contains("id=")

            return isDriveImg && hasPageOrId
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

        /**
         * Dynamically builds the high-resolution URL for a specific page number
         * by replacing page=\d+ in the captured template URL.
         */
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
        hasCapturedFirstImage = false

        val targetUrl = sanitizeDriveUrl(url)
        if (targetUrl.isBlank()) {
            onError?.invoke("Invalid Google Drive URL provided.")
            return
        }

        Handler(Looper.getMainLooper()).post {
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
            databaseEnabled = false
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
                if (request != null && isExtracting && !hasCapturedFirstImage) {
                    val reqUrl = request.url.toString()
                    if (isDriveViewerImageUrl(reqUrl)) {
                        handleFirstImageCaptured(reqUrl, view)
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
                onStatusUpdate?.invoke("Waiting for first page image stream...")
                injectTitleScraper(view)
            }
        }

        wv.loadUrl(targetUrl)
    }

    private fun handleFirstImageCaptured(url: String, view: WebView?) {
        if (hasCapturedFirstImage) return
        hasCapturedFirstImage = true
        isExtracting = false

        onStatusUpdate?.invoke("First page image captured! Handing off to smart downloader...")

        Handler(Looper.getMainLooper()).post {
            val wvTitle = view?.title
                ?.replace(" - Google Drive", "")
                ?.replace(" - Google Docs", "")
                ?.trim()

            val finalTitle = extractedTitle ?: if (!wvTitle.isNullOrBlank()) wvTitle else null

            // Stop WebView immediately to save system resources
            try {
                view?.stopLoading()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping webView: ${e.localizedMessage}")
            }

            onFirstImageCaptured?.invoke(url, finalTitle)
            destroy()
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

    fun finishExtractionManually() {
        // No-op for smart loop mode as it auto-triggers on first capture
    }

    fun destroy() {
        isExtracting = false
        hasCapturedFirstImage = true
        Handler(Looper.getMainLooper()).post {
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
            if (!title.isBlank() && extractedTitle == null) {
                extractedTitle = title
                onTitleExtracted?.invoke(title)
            }
        }
    }
}
