package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ResolutionQuality
import com.example.engine.DriveExtractorEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentSkipListMap

@Composable
fun InteractiveWebViewSheet(
    url: String,
    onDismiss: () -> Unit,
    onCompilePages: ((List<String>, String?) -> Unit)? = null,
    onStartAutoExtraction: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    var isPageLoading by remember { mutableStateOf(true) }
    var documentTitle by remember { mutableStateOf<String?>(null) }
    var detectedTotalPages by remember { mutableIntStateOf(0) }
    var isAutoScrolling by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Concurrent map for fast thread-safe URL page storage
    val capturedPagesMap = remember { ConcurrentSkipListMap<Int, String>() }
    val capturedUrlsSet = remember { Collections.synchronizedSet(HashSet<String>()) }
    var capturedCount by remember { mutableIntStateOf(0) }

    val sanitizedUrl = remember(url) {
        DriveExtractorEngine.sanitizeDriveUrl(url)
    }

    // Auto-scroll loop
    DisposableEffect(isAutoScrolling) {
        var active = isAutoScrolling
        val job = coroutineScope.launch {
            while (active) {
                webViewInstance?.evaluateJavascript(
                    """
                    (function() {
                        var selectors = ['.drive-viewer-paginated-scrollable', '.ndfHFb-c4YZDc-bN97Pc', '.ndfHFb-c4YZDc-Wrql6b', '.drive-viewer-content', '[role="main"]'];
                        var el = null;
                        for (var i = 0; i < selectors.length; i++) {
                            var candidate = document.querySelector(selectors[i]);
                            if (candidate && candidate.scrollHeight > candidate.clientHeight + 40) {
                                el = candidate;
                                break;
                            }
                        }
                        if (!el) {
                            var divs = document.querySelectorAll('div');
                            for (var j = 0; j < divs.length; j++) {
                                var d = divs[j];
                                if (d.scrollHeight > d.clientHeight + 80) {
                                    var st = window.getComputedStyle(d);
                                    if (st.overflowY === 'auto' || st.overflowY === 'scroll') {
                                        el = d;
                                        break;
                                    }
                                }
                            }
                        }
                        if (!el) el = document.scrollingElement || document.documentElement || document.body;

                        var step = Math.max(450, (el.clientHeight || window.innerHeight || 800) * 0.85);
                        if (el.scrollTop !== undefined && el.scrollHeight > el.clientHeight) {
                            el.scrollTop += step;
                        }
                        window.scrollBy(0, step);
                        try {
                            el.dispatchEvent(new WheelEvent('wheel', { deltaY: step, bubbles: true }));
                        } catch(e) {}
                    })();
                    """.trimIndent(),
                    null
                )
                delay(600)
            }
        }
        onDispose {
            active = false
            job.cancel()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar (Edge-to-Edge friendly)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Viewer",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = documentTitle ?: "Document Page Viewer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (capturedCount > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                                            )
                                    )
                                    Text(
                                        text = if (detectedTotalPages > 0) {
                                            "Captured: $capturedCount of $detectedTotalPages pages"
                                        } else {
                                            "Captured: $capturedCount pages loaded"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (capturedCount > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Auto Scroll toggle
                                FilledTonalButton(
                                    onClick = { isAutoScrolling = !isAutoScrolling },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (isAutoScrolling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isAutoScrolling) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Auto Scroll",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isAutoScrolling) "Pause" else "Auto-Scroll",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                IconButton(onClick = { webViewInstance?.reload() }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reload Page",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Native WebView with full gesture & touch scrolling
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )

                                isVerticalScrollBarEnabled = true
                                isHorizontalScrollBarEnabled = false
                                isScrollbarFadingEnabled = false

                                @SuppressLint("SetJavaScriptEnabled")
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    loadsImagesAutomatically = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    userAgentString = DriveExtractorEngine.DESKTOP_USER_AGENT
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                }

                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                // Bridge to receive scraped images and titles
                                class ViewerBridge {
                                    @JavascriptInterface
                                    fun onTitleFound(title: String) {
                                        if (title.isNotBlank()) {
                                            Handler(Looper.getMainLooper()).post {
                                                documentTitle = title
                                            }
                                        }
                                    }

                                    @JavascriptInterface
                                    fun onTotalPagesDetected(total: Int) {
                                        if (total > 0) {
                                            Handler(Looper.getMainLooper()).post {
                                                detectedTotalPages = total
                                            }
                                        }
                                    }

                                    @JavascriptInterface
                                    fun onPageImageFound(url: String, pageIndex: Int) {
                                        if (url.isNotBlank() && DriveExtractorEngine.isDriveViewerImageUrl(url)) {
                                            val cleanBaseUrl = url.substringBefore("=w").substringBefore("sz=w")
                                            if (!capturedUrlsSet.contains(cleanBaseUrl)) {
                                                capturedUrlsSet.add(cleanBaseUrl)
                                                val highRes = DriveExtractorEngine.upgradeImageUrlResolution(url, ResolutionQuality.ULTRA)
                                                val pageNum = if (pageIndex >= 0) pageIndex + 1 else (capturedPagesMap.keys.maxOrNull() ?: 0) + 1
                                                capturedPagesMap[pageNum] = highRes
                                                Handler(Looper.getMainLooper()).post {
                                                    capturedCount = capturedPagesMap.size
                                                }
                                            }
                                        }
                                    }
                                }

                                addJavascriptInterface(ViewerBridge(), "DriveBridge")

                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? {
                                        if (request != null) {
                                            val reqUrl = request.url.toString()
                                            if (DriveExtractorEngine.isDriveViewerImageUrl(reqUrl)) {
                                                val cleanBaseUrl = reqUrl.substringBefore("=w").substringBefore("sz=w")
                                                if (!capturedUrlsSet.contains(cleanBaseUrl)) {
                                                    capturedUrlsSet.add(cleanBaseUrl)
                                                    val highRes = DriveExtractorEngine.upgradeImageUrlResolution(reqUrl, ResolutionQuality.ULTRA)
                                                    val pageNum = DriveExtractorEngine.extractPageNumberFromUrl(reqUrl)
                                                        ?: ((capturedPagesMap.keys.maxOrNull() ?: 0) + 1)
                                                    capturedPagesMap[pageNum] = highRes
                                                    Handler(Looper.getMainLooper()).post {
                                                        capturedCount = capturedPagesMap.size
                                                    }
                                                }
                                            }
                                        }
                                        return super.shouldInterceptRequest(view, request)
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        isPageLoading = true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isPageLoading = false
                                        // Inject scrapers to watch DOM
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                try {
                                                    var title = document.title || "";
                                                    title = title.replace(" - Google Drive", "").replace(" - Google Docs", "").trim();
                                                    var titleElem = document.querySelector('.ndfHFb-c4YZDc-s2gQvd') || 
                                                                    document.querySelector('.drive-viewer-toolstrip-name');
                                                    if (titleElem && titleElem.innerText) {
                                                        title = titleElem.innerText.trim();
                                                    }
                                                    if (title) window.DriveBridge.onTitleFound(title);

                                                    function scrape() {
                                                        var imgs = document.querySelectorAll('img');
                                                        for (var i = 0; i < imgs.length; i++) {
                                                            var img = imgs[i];
                                                            var src = img.src || img.getAttribute('src');
                                                            if (src && (src.indexOf('viewer') !== -1 || src.indexOf('googleusercontent') !== -1)) {
                                                                var pIdx = -1;
                                                                var parent = img.closest('[data-page-index], [data-page-number], .drive-viewer-paginated-page');
                                                                if (parent) {
                                                                    var idx = parent.getAttribute('data-page-index') || parent.getAttribute('data-page-number');
                                                                    if (idx) pIdx = parseInt(idx, 10);
                                                                }
                                                                window.DriveBridge.onPageImageFound(src, pIdx);
                                                            }
                                                        }
                                                        var textNodes = document.querySelectorAll('.drive-viewer-toolstrip, .ndfHFb-c4YZDc-j7LFlb, div, span');
                                                        for (var j = 0; j < textNodes.length; j++) {
                                                            var m = /(?:of|\/)\s*(\d+)/i.exec(textNodes[j].innerText || "");
                                                            if (m && m[1]) {
                                                                window.DriveBridge.onTotalPagesDetected(parseInt(m[1], 10));
                                                                break;
                                                            }
                                                        }
                                                    }
                                                    scrape();
                                                    setInterval(scrape, 800);
                                                } catch(e) {}
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    }
                                }

                                loadUrl(sanitizedUrl)
                                webViewInstance = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isPageLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        text = "Loading Google Drive Document...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // Sticky Bottom Action Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (capturedCount > 0) {
                                    "✨ $capturedCount pages ready! Scroll for more or compile now:"
                                } else {
                                    "👉 Scroll down to load all pages (or tap Auto-Scroll):"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )

                            // Quick scroll downward button
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        webViewInstance?.evaluateJavascript(
                                            """
                                            (function() {
                                                window.scrollBy(0, 600);
                                                var selectors = ['.drive-viewer-paginated-scrollable', '.ndfHFb-c4YZDc-bN97Pc', '.drive-viewer-content'];
                                                for (var i = 0; i < selectors.length; i++) {
                                                    var el = document.querySelector(selectors[i]);
                                                    if (el) el.scrollTop += 600;
                                                }
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Scroll Down",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Scroll Down", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // Primary Compile Button
                        Button(
                            onClick = {
                                isAutoScrolling = false
                                if (capturedCount > 0 && onCompilePages != null) {
                                    val pagesList = capturedPagesMap.values.toList()
                                    onCompilePages(pagesList, documentTitle)
                                } else {
                                    onDismiss()
                                    onStartAutoExtraction()
                                }
                            },
                            enabled = capturedCount > 0 || !isPageLoading,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("compile_captured_pages_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = "Compile",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (capturedCount > 0) {
                                    "Compile PDF Now ($capturedCount Pages Captured)"
                                } else {
                                    "Extract & Build PDF"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
