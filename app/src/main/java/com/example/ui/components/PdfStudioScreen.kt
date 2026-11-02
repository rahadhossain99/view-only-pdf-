package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadHistoryItem
import com.example.data.model.PdfPageEditItem
import com.example.viewmodel.MainViewModel
import java.io.File

private enum class StudioSubMode {
    READER,
    ORGANIZER_GRID
}

@Composable
fun PdfStudioScreen(
    viewModel: MainViewModel,
    studioPdfFile: File?,
    studioTitle: String,
    pageEdits: List<PdfPageEditItem>,
    isStudioLoading: Boolean,
    exportMessage: String?,
    historyItems: List<DownloadHistoryItem>,
    modifier: Modifier = Modifier
) {
    var subMode by remember { mutableStateOf(StudioSubMode.READER) }
    var activeReaderIndex by remember { mutableIntStateOf(0) }
    var readerZoom by remember { mutableFloatStateOf(1f) }
    var readerPanX by remember { mutableFloatStateOf(0f) }
    var readerPanY by remember { mutableFloatStateOf(0f) }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { pickedUri: Uri? ->
        if (pickedUri != null) {
            viewModel.openPdfInStudio(
                uri = pickedUri,
                localPath = null,
                title = "Imported_Document_${System.currentTimeMillis() % 10000}"
            )
        }
    }

    val activePages = remember(pageEdits) { pageEdits.filter { !it.isDeleted } }
    if (activeReaderIndex >= activePages.size && activePages.isNotEmpty()) {
        activeReaderIndex = activePages.lastIndex
    }

    val hasAnyEdits = remember(pageEdits) {
        pageEdits.withIndex().any { (idx, item) ->
            item.originalPageIndex != idx ||
                    item.rotationDegrees != 0 ||
                    item.isDeleted ||
                    item.enhanceContrast
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (studioPdfFile == null || pageEdits.isEmpty()) {
            // Empty Studio State with Device Picker + Saved PDF Quick Loader
            EmptyStudioView(
                historyItems = historyItems,
                onPickFromDevice = { pdfPickerLauncher.launch("application/pdf") },
                onSelectHistoryItem = { item ->
                    viewModel.openPdfInStudio(
                        uri = Uri.parse(item.uriString),
                        localPath = item.localPath,
                        title = item.title
                    )
                }
            )
        } else {
            // Studio Top Header & Mode Switcher Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = studioTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${activePages.size} of ${pageEdits.size} Pages Active • Built-in PDF Studio",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { pdfPickerLauncher.launch("application/pdf") },
                                modifier = Modifier.testTag("studio_pick_pdf_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FileOpen,
                                    contentDescription = "Open Another PDF File",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    viewModel.sharePdf(
                                        uri = Uri.fromFile(studioPdfFile),
                                        localPath = studioPdfFile.absolutePath,
                                        title = studioTitle
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share PDF",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    viewModel.openPdf(
                                        uri = Uri.fromFile(studioPdfFile),
                                        localPath = studioPdfFile.absolutePath
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = "Open in External PDF App",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Mode Switcher & Quick Batch Tools
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = subMode == StudioSubMode.READER,
                                onClick = { subMode = StudioSubMode.READER },
                                label = { Text("Reader View") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("studio_reader_tab")
                            )

                            FilterChip(
                                selected = subMode == StudioSubMode.ORGANIZER_GRID,
                                onClick = { subMode = StudioSubMode.ORGANIZER_GRID },
                                label = { Text("Page Organizer") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.GridView,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("studio_organizer_tab")
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { viewModel.toggleAllStudioContrast() },
                                modifier = Modifier.testTag("studio_contrast_all_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoFixHigh,
                                    contentDescription = "Toggle Crisp Contrast for All Pages",
                                    tint = if (pageEdits.all { it.enhanceContrast }) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            if (hasAnyEdits) {
                                IconButton(
                                    onClick = { viewModel.resetStudioEdits() },
                                    modifier = Modifier.testTag("studio_reset_edits_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reset Page Edits",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Export status feedback banner
            AnimatedVisibility(visible = exportMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isStudioLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = exportMessage ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // Main Studio Content Area
            Box(modifier = Modifier.weight(1f)) {
                if (subMode == StudioSubMode.READER && activePages.isNotEmpty()) {
                    val currentPageItem = activePages[activeReaderIndex.coerceIn(0, activePages.lastIndex)]
                    PdfReaderContentView(
                        viewModel = viewModel,
                        studioPdfFile = studioPdfFile,
                        pageItem = currentPageItem,
                        currentIndex = activeReaderIndex,
                        totalPages = activePages.size,
                        zoom = readerZoom,
                        panX = readerPanX,
                        panY = readerPanY,
                        onUpdateZoomPan = { z, px, py ->
                            readerZoom = z
                            readerPanX = px
                            readerPanY = py
                        },
                        onPrevPage = {
                            if (activeReaderIndex > 0) {
                                activeReaderIndex--
                                readerZoom = 1f
                                readerPanX = 0f
                                readerPanY = 0f
                            }
                        },
                        onNextPage = {
                            if (activeReaderIndex < activePages.lastIndex) {
                                activeReaderIndex++
                                readerZoom = 1f
                                readerPanX = 0f
                                readerPanY = 0f
                            }
                        },
                        onRotateCurrent = { viewModel.rotateStudioPage(currentPageItem.id) },
                        onToggleContrastCurrent = { viewModel.toggleContrastStudioPage(currentPageItem.id) }
                    )
                } else {
                    PdfOrganizerGridView(
                        viewModel = viewModel,
                        studioPdfFile = studioPdfFile,
                        pageEdits = pageEdits,
                        onOpenPageInReader = { editItem ->
                            val idxInActive = activePages.indexOfFirst { it.id == editItem.id }
                            if (idxInActive >= 0) {
                                activeReaderIndex = idxInActive
                                subMode = StudioSubMode.READER
                            }
                        }
                    )
                }
            }

            // Sticky Save / Export Updated PDF Bar
            if (hasAnyEdits || subMode == StudioSubMode.ORGANIZER_GRID) {
                Button(
                    onClick = { viewModel.exportEditedStudioPdf() },
                    enabled = !isStudioLoading && activePages.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("export_organized_pdf_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SaveAlt,
                        contentDescription = "Save Updated PDF",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isStudioLoading) {
                            "Saving Organized PDF..."
                        } else {
                            "Save & Export Organized PDF (${activePages.size} Pages)"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfReaderContentView(
    viewModel: MainViewModel,
    studioPdfFile: File,
    pageItem: PdfPageEditItem,
    currentIndex: Int,
    totalPages: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
    onUpdateZoomPan: (Float, Float, Float) -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onRotateCurrent: () -> Unit,
    onToggleContrastCurrent: () -> Unit
) {
    var renderedBitmap by remember(
        studioPdfFile.absolutePath,
        pageItem.originalPageIndex,
        pageItem.rotationDegrees,
        pageItem.enhanceContrast
    ) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(
        studioPdfFile.absolutePath,
        pageItem.originalPageIndex,
        pageItem.rotationDegrees,
        pageItem.enhanceContrast
    ) {
        renderedBitmap = viewModel.renderStudioPageBitmap(
            pageIndex = pageItem.originalPageIndex,
            targetWidthPx = 1200,
            rotationDegrees = pageItem.rotationDegrees,
            enhanceContrast = pageItem.enhanceContrast
        )
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // High-Resolution Zoomable Page Surface
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val newZoom = (zoom * gestureZoom).coerceIn(1f, 3.5f)
                            val maxOffset = (newZoom - 1f) * 450f
                            val newPanX = (panX + pan.x).coerceIn(-maxOffset, maxOffset)
                            val newPanY = (panY + pan.y).coerceIn(-maxOffset, maxOffset)
                            onUpdateZoomPan(newZoom, newPanX, newPanY)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val bmp = renderedBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "PDF Page ${currentIndex + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = panX
                                translationY = panY
                            }
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text(
                            text = "Rendering Page ${currentIndex + 1}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Bottom Reader Controls Bar (Page Navigation, Zoom, Rotate, Contrast)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Page Prev / Counter / Next
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onPrevPage,
                        enabled = currentIndex > 0,
                        modifier = Modifier.testTag("reader_prev_page_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Previous Page"
                        )
                    }
                    Text(
                        text = "${currentIndex + 1} / $totalPages",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onNextPage,
                        enabled = currentIndex < totalPages - 1,
                        modifier = Modifier.testTag("reader_next_page_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next Page"
                        )
                    }
                }

                // Quick Zoom, Rotate & Contrast Controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val nextZoom = (zoom - 0.4f).coerceAtLeast(1f)
                            onUpdateZoomPan(nextZoom, if (nextZoom == 1f) 0f else panX, if (nextZoom == 1f) 0f else panY)
                        },
                        enabled = zoom > 1f
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomOut,
                            contentDescription = "Zoom Out"
                        )
                    }
                    IconButton(
                        onClick = {
                            val nextZoom = (zoom + 0.4f).coerceAtMost(3f)
                            onUpdateZoomPan(nextZoom, panX, panY)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Zoom In"
                        )
                    }
                    IconButton(
                        onClick = onRotateCurrent,
                        modifier = Modifier.testTag("reader_rotate_page_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RotateRight,
                            contentDescription = "Rotate Page 90 Degrees",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onToggleContrastCurrent,
                        modifier = Modifier.testTag("reader_contrast_page_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = "Enhance Page Contrast",
                            tint = if (pageItem.enhanceContrast) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfOrganizerGridView(
    viewModel: MainViewModel,
    studioPdfFile: File,
    pageEdits: List<PdfPageEditItem>,
    onOpenPageInReader: (PdfPageEditItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(pageEdits, key = { _, item -> item.id }) { index, item ->
            OrganizerPageCard(
                viewModel = viewModel,
                studioPdfFile = studioPdfFile,
                item = item,
                displayOrderNumber = index + 1,
                canMoveEarlier = index > 0,
                canMoveLater = index < pageEdits.lastIndex,
                onOpenInReader = { onOpenPageInReader(item) },
                onRotate = { viewModel.rotateStudioPage(item.id) },
                onMoveEarlier = { viewModel.moveStudioPage(index, -1) },
                onMoveLater = { viewModel.moveStudioPage(index, +1) },
                onToggleContrast = { viewModel.toggleContrastStudioPage(item.id) },
                onToggleDelete = { viewModel.toggleDeleteStudioPage(item.id) }
            )
        }
    }
}

@Composable
private fun OrganizerPageCard(
    viewModel: MainViewModel,
    studioPdfFile: File,
    item: PdfPageEditItem,
    displayOrderNumber: Int,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onOpenInReader: () -> Unit,
    onRotate: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onToggleContrast: () -> Unit,
    onToggleDelete: () -> Unit
) {
    var thumbBitmap by remember(
        studioPdfFile.absolutePath,
        item.originalPageIndex,
        item.rotationDegrees,
        item.enhanceContrast
    ) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(
        studioPdfFile.absolutePath,
        item.originalPageIndex,
        item.rotationDegrees,
        item.enhanceContrast
    ) {
        thumbBitmap = viewModel.renderStudioPageBitmap(
            pageIndex = item.originalPageIndex,
            targetWidthPx = 480,
            rotationDegrees = item.rotationDegrees,
            enhanceContrast = item.enhanceContrast
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.isDeleted) 0.45f else 1f)
            .border(
                width = if (item.enhanceContrast) 2.dp else 1.dp,
                color = if (item.isDeleted) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                } else if (item.enhanceContrast) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                },
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Thumbnail Preview Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !item.isDeleted) { onOpenInReader() },
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Page $displayOrderNumber Thumbnail",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }

                // Page Number Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = "#$displayOrderNumber",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Rotation / Status Badge
                if (item.rotationDegrees != 0 || item.isDeleted) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (item.isDeleted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = if (item.isDeleted) "Removed" else "${item.rotationDegrees}°",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Action Toolbar Row (Move Left, Rotate, Contrast, Delete, Move Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onMoveEarlier,
                    enabled = canMoveEarlier,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Move Page Earlier",
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onRotate,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RotateRight,
                        contentDescription = "Rotate Page",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onToggleContrast,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = "Enhance Contrast",
                        tint = if (item.enhanceContrast) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onToggleDelete,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (item.isDeleted) Icons.Default.RestoreFromTrash else Icons.Default.DeleteOutline,
                        contentDescription = if (item.isDeleted) "Restore Page" else "Remove Page",
                        tint = if (item.isDeleted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onMoveLater,
                    enabled = canMoveLater,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Move Page Later",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStudioView(
    historyItems: List<DownloadHistoryItem>,
    onPickFromDevice: () -> Unit,
    onSelectHistoryItem: (DownloadHistoryItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = "PDF Studio",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = "Built-in PDF Viewer & Page Organizer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "Inspect extracted PDFs at high zoom, reorder pages, rotate upside-down scans, remove blank pages, or apply crisp contrast filters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilledTonalButton(
                        onClick = onPickFromDevice,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("pick_device_pdf_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "Open PDF from Device",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open PDF File from Device Storage",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (historyItems.isNotEmpty()) {
            item {
                Text(
                    text = "Or Select from Recently Extracted PDFs",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            items(historyItems.size) { idx ->
                val item = historyItems[idx]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectHistoryItem(item) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.pageCount} pages • Tap to open in PDF Studio",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { onSelectHistoryItem(item) },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Open")
                        }
                    }
                }
            }
        }
    }
}
