package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadHistoryItem
import com.example.data.storage.PdfStorageHelper
import com.example.ui.theme.SketchCard
import com.example.ui.theme.SketchCoralWash
import com.example.ui.theme.SketchCreamWash
import com.example.ui.theme.SketchCrimsonInk
import com.example.ui.theme.SketchGraphite
import com.example.ui.theme.SketchInkBlack
import com.example.ui.theme.SketchMarkerYellow
import com.example.ui.theme.SketchMintWash
import com.example.ui.theme.SketchPaperBackground
import com.example.ui.theme.SketchPenBlueWash
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated full-screen History Page opened when the user clicks the History button.
 */
@Composable
fun DedicatedHistoryScreen(
    items: List<DownloadHistoryItem>,
    onBack: () -> Unit,
    onOpenItem: (Uri, String?) -> Unit,
    onOpenInFiles: (Uri, String?) -> Unit,
    onShareItem: (Uri, String?, String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val totalPagesSaved = items.sumOf { it.pageCount }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("dedicated_history_screen"),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SketchCreamWash)
                                .border(1.8.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                                .clickable { onBack() }
                                .testTag("history_back_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Home",
                                tint = SketchInkBlack,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Download History",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = SketchInkBlack
                            )
                            Text(
                                text = "${items.size} PDFs • $totalPagesSaved total pages",
                                style = MaterialTheme.typography.labelSmall,
                                color = SketchGraphite
                            )
                        }
                    }

                    if (items.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SketchCoralWash)
                                .border(1.5.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                                .clickable { onClearAll() }
                                .padding(horizontal = 12.dp)
                                .testTag("page_clear_history_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Clear All",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = SketchCrimsonInk
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(SketchInkBlack)
                )
            }
        }
    ) { innerPadding ->
        SketchPaperBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 600.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Summary Banner
                    if (items.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SketchMintWash.copy(alpha = 0.7f))
                                        .border(1.6.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "SAVED PDFS",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = SketchGraphite
                                        )
                                        Text(
                                            text = "${items.size} Files",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = SketchInkBlack
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SketchPenBlueWash.copy(alpha = 0.6f))
                                        .border(1.6.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "TOTAL PAGES",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = SketchGraphite
                                        )
                                        Text(
                                            text = "$totalPagesSaved Pages",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = SketchInkBlack
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (items.isEmpty()) {
                        item {
                            SketchCard(
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = Color.White,
                                borderColor = SketchInkBlack,
                                shadowColor = SketchInkBlack,
                                cornerRadius = 18.dp,
                                shadowOffset = 4.dp
                            ) {
                                Box(modifier = Modifier.padding(16.dp)) {
                                    EmptyHistorySketchBox()
                                }
                            }
                        }
                    } else {
                        items(items, key = { it.id }) { item ->
                            SketchCard(
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = Color.White,
                                borderColor = SketchInkBlack,
                                shadowColor = SketchInkBlack,
                                cornerRadius = 16.dp,
                                shadowOffset = 3.dp
                            ) {
                                Box(modifier = Modifier.padding(2.dp)) {
                                    SketchHistoryItemCard(
                                        item = item,
                                        containerBackground = Color.White,
                                        showBorder = false,
                                        onOpen = {
                                            onOpenItem(Uri.parse(item.uriString), item.localPath)
                                        },
                                        onOpenInFiles = {
                                            onOpenInFiles(Uri.parse(item.uriString), item.localPath)
                                        },
                                        onShare = {
                                            onShareItem(Uri.parse(item.uriString), item.localPath, item.title)
                                        },
                                        onDelete = {
                                            onDeleteItem(item.id)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * Inline History Preview Section displayed right at the bottom of the Home Screen.
 */
@Composable
fun HistorySection(
    items: List<DownloadHistoryItem>,
    onOpenItem: (Uri, String?) -> Unit,
    onOpenInFiles: (Uri, String?) -> Unit = { _, _ -> },
    onShareItem: (Uri, String?, String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit,
    onOpenFullHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val recentPreviewItems = items.take(4)

    SketchCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("history_section_card"),
        containerColor = Color.White,
        borderColor = SketchInkBlack,
        shadowColor = SketchInkBlack,
        cornerRadius = 18.dp,
        shadowOffset = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(SketchMarkerYellow)
                            .border(1.5.dp, SketchInkBlack, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "History",
                            tint = SketchInkBlack,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = if (items.isNotEmpty()) "Recent Downloads (${items.size})" else "Recent Downloads",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = SketchInkBlack
                        )
                        Text(
                            text = "Tap any PDF to open or view full history",
                            style = MaterialTheme.typography.labelSmall,
                            color = SketchGraphite
                        )
                    }
                }

                // "View All" button to open the dedicated History Page
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(SketchCreamWash)
                        .border(1.4.dp, SketchInkBlack, RoundedCornerShape(9.dp))
                        .clickable { onOpenFullHistory() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("inline_view_all_history_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Full History",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = SketchInkBlack
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open Full History Page",
                            tint = SketchInkBlack,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                EmptyHistorySketchBox()
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    recentPreviewItems.forEach { item ->
                        SketchHistoryItemCard(
                            item = item,
                            onOpen = {
                                onOpenItem(Uri.parse(item.uriString), item.localPath)
                            },
                            onOpenInFiles = {
                                onOpenInFiles(Uri.parse(item.uriString), item.localPath)
                            },
                            onShare = {
                                onShareItem(Uri.parse(item.uriString), item.localPath, item.title)
                            },
                            onDelete = {
                                onDeleteItem(item.id)
                            }
                        )
                    }

                    if (items.size > 4) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SketchMarkerYellow.copy(alpha = 0.6f))
                                .border(1.4.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                                .clickable { onOpenFullHistory() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "View All ${items.size} Downloaded PDFs →",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = SketchInkBlack
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistorySketchBox() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SketchCreamWash.copy(alpha = 0.5f))
            .border(1.4.dp, SketchInkBlack.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = "No PDFs",
                tint = SketchGraphite,
                modifier = Modifier.size(30.dp)
            )
            Text(
                text = "No PDFs in History Yet",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.ExtraBold,
                color = SketchInkBlack,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Downloaded Google Drive PDFs will appear here for quick access",
                style = MaterialTheme.typography.labelSmall,
                color = SketchGraphite,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SketchHistoryItemCard(
    item: DownloadHistoryItem,
    containerBackground: Color = SketchCreamWash.copy(alpha = 0.35f),
    showBorder: Boolean = true,
    onOpen: () -> Unit,
    onOpenInFiles: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("dd MMM yyyy • hh:mm a", Locale.getDefault()).format(Date(item.timestamp))
    val displayTitle = PdfStorageHelper.cleanDocumentTitle(item.title) ?: item.title

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerBackground)
            .then(
                if (showBorder) Modifier.border(1.6.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                else Modifier
            )
            .clickable { onOpen() }
            .padding(12.dp)
            .testTag("history_item_${item.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SketchMintWash)
                    .border(1.5.dp, SketchInkBlack, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = "PDF File",
                    tint = SketchInkBlack,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = if (displayTitle.endsWith(".pdf", ignoreCase = true)) displayTitle else "$displayTitle.pdf",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = SketchInkBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = SketchGraphite,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = SketchGraphite,
                        maxLines = 1
                    )
                }
            }

            // Page & Size Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SketchMarkerYellow)
                    .border(1.3.dp, SketchInkBlack, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${item.pageCount}p • ${PdfStorageHelper.formatFileSize(item.fileSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = SketchInkBlack
                )
            }
        }

        // Quick Action Row: Open, Files, Share, Delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SketchInkBlack)
                    .clickable { onOpen() }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open PDF",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Open",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.3.dp, SketchInkBlack, RoundedCornerShape(8.dp))
                    .clickable { onOpenInFiles() }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Files",
                        tint = SketchInkBlack,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Files",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.3.dp, SketchInkBlack, RoundedCornerShape(8.dp))
                    .clickable { onShare() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share PDF",
                    tint = SketchInkBlack,
                    modifier = Modifier.size(15.dp)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SketchCoralWash)
                    .border(1.3.dp, SketchInkBlack, RoundedCornerShape(8.dp))
                    .clickable { onDelete() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete History Item",
                    tint = SketchCrimsonInk,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}
