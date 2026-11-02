package com.example.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.DownloadState
import com.example.data.model.WorkspaceTab
import com.example.ui.components.AutoScreenshotStudioDialog
import com.example.ui.components.HistorySection
import com.example.ui.components.InteractiveWebViewSheet
import com.example.ui.components.PdfStudioScreen
import com.example.ui.components.ProgressCard
import com.example.ui.components.SuccessCard
import com.example.ui.components.UrlInputField
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val activeTab by viewModel.activeWorkspaceTab.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val inputUrl by viewModel.inputUrl.collectAsState()
    val resolutionQuality by viewModel.resolutionQuality.collectAsState()
    val engineMode by viewModel.engineMode.collectAsState()
    val enhanceContrast by viewModel.enhanceContrast.collectAsState()
    val telemetry by viewModel.engineTelemetry.collectAsState()
    val isInteractiveMode by viewModel.isInteractiveMode.collectAsState()
    val isScreenshotStudioOpen by viewModel.isScreenshotStudioOpen.collectAsState()
    val documentTitle by viewModel.currentDocumentTitle.collectAsState()
    val historyItems by viewModel.historyItems.collectAsState()

    // Studio state
    val studioPdfFile by viewModel.studioPdfFile.collectAsState()
    val studioTitle by viewModel.studioDocumentTitle.collectAsState()
    val studioPageEdits by viewModel.studioPageEdits.collectAsState()
    val isStudioLoading by viewModel.isStudioLoading.collectAsState()
    val studioExportMessage by viewModel.studioExportMessage.collectAsState()

    var librarySearchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    if (activeTab != WorkspaceTab.EXTRACTOR) {
        BackHandler {
            viewModel.setWorkspaceTab(WorkspaceTab.EXTRACTOR)
        }
    }

    val isLoading = downloadState is DownloadState.LoadingWebView ||
            downloadState is DownloadState.InterceptingPages ||
            downloadState is DownloadState.DownloadingImages ||
            downloadState is DownloadState.CompilingPdf

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = "Drive PDF Studio Logo",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Drive PDF Studio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "4-Tier Protected PDF Extractor & Organizer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = engineMode.shortBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setInteractiveMode(true) },
                        modifier = Modifier.testTag("top_interactive_browser_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Visibility,
                            contentDescription = "Interactive Browser Sign-In",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == WorkspaceTab.EXTRACTOR,
                    onClick = { viewModel.setWorkspaceTab(WorkspaceTab.EXTRACTOR) },
                    icon = {
                        Icon(
                            imageVector = if (activeTab == WorkspaceTab.EXTRACTOR) {
                                Icons.Default.Download
                            } else {
                                Icons.Outlined.Download
                            },
                            contentDescription = "Extractor"
                        )
                    },
                    label = { Text("Extractor") },
                    modifier = Modifier.testTag("nav_tab_extractor")
                )

                NavigationBarItem(
                    selected = activeTab == WorkspaceTab.PDF_STUDIO,
                    onClick = { viewModel.setWorkspaceTab(WorkspaceTab.PDF_STUDIO) },
                    icon = {
                        Icon(
                            imageVector = if (activeTab == WorkspaceTab.PDF_STUDIO) {
                                Icons.Default.AutoFixHigh
                            } else {
                                Icons.Outlined.AutoFixHigh
                            },
                            contentDescription = "PDF Studio"
                        )
                    },
                    label = { Text("PDF Studio") },
                    modifier = Modifier.testTag("nav_tab_studio")
                )

                NavigationBarItem(
                    selected = activeTab == WorkspaceTab.LIBRARY,
                    onClick = { viewModel.setWorkspaceTab(WorkspaceTab.LIBRARY) },
                    icon = {
                        Icon(
                            imageVector = if (activeTab == WorkspaceTab.LIBRARY) {
                                Icons.Default.FolderSpecial
                            } else {
                                Icons.Outlined.FolderSpecial
                            },
                            contentDescription = "Saved Library"
                        )
                    },
                    label = { Text("Library (${historyItems.size})") },
                    modifier = Modifier.testTag("nav_tab_library")
                )
            }
        }
    ) { innerPadding ->
        // Active on-screen extraction WebView kept within visible screen bounds so Chromium
        // IntersectionObserver and Google Drive's lazy-page loader fire for all pages 1..N
        Box(
            modifier = Modifier
                .width(380.dp)
                .height(580.dp)
                .graphicsLayer {
                    alpha = 0.02f
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    viewModel.getOrCreateExtractionWebView(ctx).apply {
                        (parent as? android.view.ViewGroup)?.removeView(this)
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            when (activeTab) {
                WorkspaceTab.EXTRACTOR -> {
                    ExtractorWorkspaceView(
                        viewModel = viewModel,
                        inputUrl = inputUrl,
                        isLoading = isLoading,
                        downloadState = downloadState,
                        documentTitle = documentTitle,
                        telemetry = telemetry,
                        historyItems = historyItems
                    )
                }

                WorkspaceTab.PDF_STUDIO -> {
                    PdfStudioScreen(
                        viewModel = viewModel,
                        studioPdfFile = studioPdfFile,
                        studioTitle = studioTitle,
                        pageEdits = studioPageEdits,
                        isStudioLoading = isStudioLoading,
                        exportMessage = studioExportMessage,
                        historyItems = historyItems
                    )
                }

                WorkspaceTab.LIBRARY -> {
                    LibraryWorkspaceView(
                        viewModel = viewModel,
                        historyItems = historyItems,
                        searchQuery = librarySearchQuery,
                        onSearchQueryChange = { librarySearchQuery = it }
                    )
                }
            }
        }

        // Interactive WebView Sheet for sign-in or manual inspection
        if (isInteractiveMode) {
            InteractiveWebViewSheet(
                url = inputUrl.ifBlank { "https://drive.google.com" },
                onDismiss = { viewModel.setInteractiveMode(false) },
                onCompilePages = { pageUrls, title ->
                    viewModel.setInteractiveMode(false)
                    viewModel.startDownloadWithPages(pageUrls, title)
                },
                onStartAutoExtraction = {
                    viewModel.setInteractiveMode(false)
                    viewModel.startDownload(inputUrl)
                }
            )
        }

        // Full-Screen Live Auto-Scroll Screenshot Studio Dialog
        if (isScreenshotStudioOpen) {
            AutoScreenshotStudioDialog(
                url = inputUrl.ifBlank { "https://drive.google.com" },
                onDismiss = { viewModel.setScreenshotStudioOpen(false) },
                onPdfCreated = { uri, fileName, _ ->
                    viewModel.setScreenshotStudioOpen(false)
                    viewModel.openPdfInStudio(uri = uri, localPath = null, title = fileName)
                }
            )
        }
    }
}

@Composable
private fun ExtractorWorkspaceView(
    viewModel: MainViewModel,
    inputUrl: String,
    isLoading: Boolean,
    downloadState: DownloadState,
    documentTitle: String?,
    telemetry: com.example.data.model.EngineTelemetry,
    historyItems: List<com.example.data.model.DownloadHistoryItem>
) {
    val resolutionQuality by viewModel.resolutionQuality.collectAsState()
    val engineMode by viewModel.engineMode.collectAsState()
    val enhanceContrast by viewModel.enhanceContrast.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Multi-Engine URL Input & Configuration Card
        item {
            UrlInputField(
                url = inputUrl,
                onUrlChange = viewModel::updateUrl,
                isLoading = isLoading,
                selectedResolution = resolutionQuality,
                onResolutionChange = viewModel::setResolution,
                selectedEngineMode = engineMode,
                onEngineModeChange = viewModel::setEngineMode,
                enhanceContrast = enhanceContrast,
                onEnhanceContrastChange = viewModel::setEnhanceContrast,
                onStartDownload = { viewModel.startDownload(inputUrl) },
                onToggleInteractiveMode = { viewModel.setInteractiveMode(true) },
                onOpenScreenshotStudio = { viewModel.setScreenshotStudioOpen(true) }
            )
        }

        // 2. Active Progress & Live Multi-Engine Telemetry Card
        item {
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ProgressCard(
                    state = downloadState,
                    documentTitle = documentTitle,
                    telemetry = telemetry,
                    onCancel = viewModel::cancelDownload,
                    onForceCompile = viewModel::forceFinishExtraction,
                    onOpenInteractive = { viewModel.setInteractiveMode(true) }
                )
            }
        }

        // 3. Success Card with direct PDF Studio & Page Organizer launch
        item {
            AnimatedVisibility(
                visible = downloadState is DownloadState.Success,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (downloadState is DownloadState.Success) {
                    SuccessCard(
                        successState = downloadState,
                        onOpenPdf = { viewModel.openPdf(downloadState.uri, downloadState.localPath) },
                        onSharePdf = {
                            viewModel.sharePdf(
                                downloadState.uri,
                                downloadState.localPath,
                                downloadState.fileName
                            )
                        },
                        onOpenInStudio = {
                            viewModel.openPdfInStudio(
                                uri = downloadState.uri,
                                localPath = downloadState.localPath,
                                title = downloadState.fileName
                            )
                        }
                    )
                }
            }
        }

        // 4. Error Card with recovery actions
        item {
            AnimatedVisibility(
                visible = downloadState is DownloadState.Error,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (downloadState is DownloadState.Error) {
                    ErrorRecoveryCard(
                        errorState = downloadState,
                        onRetry = { viewModel.startDownload(inputUrl) },
                        onOpenInteractive = { viewModel.setInteractiveMode(true) }
                    )
                }
            }
        }

        // 5. 4-Tier Protected PDF Extraction Architecture Overview (when idle)
        if (!isLoading && downloadState !is DownloadState.Success) {
            item {
                MultiEngineCapabilitiesCard()
            }
        }

        // 6. Recent Saved PDFs Preview
        item {
            HistorySection(
                items = historyItems.take(5),
                onOpenItem = { uri ->
                    val matched = historyItems.find { it.uriString == uri.toString() }
                    viewModel.openPdf(uri, matched?.localPath)
                },
                onShareItem = { uri, title ->
                    val matched = historyItems.find { it.uriString == uri.toString() }
                    viewModel.sharePdf(uri, matched?.localPath, title)
                },
                onDeleteItem = viewModel::deleteHistoryItem,
                onClearAll = viewModel::clearAllHistory,
                onOpenInStudio = { item ->
                    viewModel.openPdfInStudio(
                        uri = Uri.parse(item.uriString),
                        localPath = item.localPath,
                        title = item.title
                    )
                }
            )
        }
    }
}

@Composable
private fun LibraryWorkspaceView(
    viewModel: MainViewModel,
    historyItems: List<com.example.data.model.DownloadHistoryItem>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    val filteredItems = remember(historyItems, searchQuery) {
        if (searchQuery.isBlank()) {
            historyItems
        } else {
            historyItems.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("library_search_input"),
                placeholder = { Text("Search extracted PDFs by title...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Library"
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear Search"
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
        }

        item {
            HistorySection(
                items = filteredItems,
                onOpenItem = { uri ->
                    val matched = historyItems.find { it.uriString == uri.toString() }
                    viewModel.openPdf(uri, matched?.localPath)
                },
                onShareItem = { uri, title ->
                    val matched = historyItems.find { it.uriString == uri.toString() }
                    viewModel.sharePdf(uri, matched?.localPath, title)
                },
                onDeleteItem = viewModel::deleteHistoryItem,
                onClearAll = viewModel::clearAllHistory,
                onOpenInStudio = { item ->
                    viewModel.openPdfInStudio(
                        uri = Uri.parse(item.uriString),
                        localPath = item.localPath,
                        title = item.title
                    )
                }
            )
        }
    }
}

@Composable
private fun ErrorRecoveryCard(
    errorState: DownloadState.Error,
    onRetry: () -> Unit,
    onOpenInteractive: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_error_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = errorState.message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            if (!errorState.details.isNullOrBlank()) {
                Text(
                    text = errorState.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("retry_download_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry")
                }

                OutlinedButton(
                    onClick = onOpenInteractive,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .testTag("error_interactive_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Sign In",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Browser Sign-In")
                }
            }
        }
    }
}

@Composable
private fun MultiEngineCapabilitiesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = "4-Tier Hybrid Protected PDF Technology",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Automatically combines 4 extraction engines for 100% page integrity",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            CapabilityRow(
                icon = Icons.Default.AutoAwesome,
                title = "Tier 1 & 2: Direct Network Stream, Blob & Canvas Hook",
                subtitle = "Intercepts raw PDF binary streams, blob: buffers & HTML5 <canvas> bitmaps directly from memory."
            )
            CapabilityRow(
                icon = Icons.Outlined.Layers,
                title = "Tier 3: High-DPI Viewer Tile Upgrader (=w3200)",
                subtitle = "Rewrites Google Drive viewer tile endpoints to Ultra HD 3200p resolution."
            )
            CapabilityRow(
                icon = Icons.Outlined.Security,
                title = "Tier 4: Precision Bounding-Box Auto-Scroller",
                subtitle = "Scrolls page-by-page and crops exact DOM page boundaries so pages are never cut or split."
            )
        }
    }
}

@Composable
private fun CapabilityRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
