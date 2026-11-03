package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SketchHistoryModalSheet(
    items: List<DownloadHistoryItem>,
    onDismiss: () -> Unit,
    onOpenItem: (Uri, String?) -> Unit,
    onOpenInFiles: (Uri, String?) -> Unit,
    onShareItem: (Uri, String?, String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SketchMarkerYellow)
                            .border(1.8.dp, SketchInkBlack, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "History",
                            tint = SketchInkBlack,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "ডাউনলোড হিস্ট্রি (${items.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = SketchInkBlack
                        )
                        Text(
                            text = "কবে কোন ফাইল তৈরি করা হয়েছে — ট্যাপ করে ওপেন করুন",
                            style = MaterialTheme.typography.labelSmall,
                            color = SketchGraphite
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (items.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(SketchCoralWash)
                                .border(1.3.dp, SketchInkBlack, RoundedCornerShape(8.dp))
                                .clickable { onClearAll() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("sheet_clear_history_button")
                        ) {
                            Text(
                                text = "সব মুছুন",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = SketchCrimsonInk
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SketchCreamWash)
                            .border(1.4.dp, SketchInkBlack, RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close History",
                            tint = SketchInkBlack,
                            modifier = Modifier.size(18.dp)
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

            if (items.isEmpty()) {
                EmptyHistorySketchBox()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items, key = { it.id }) { item ->
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
                }
            }
        }
    }
}

@Composable
fun HistorySection(
    items: List<DownloadHistoryItem>,
    onOpenItem: (Uri, String?) -> Unit,
    onOpenInFiles: (Uri, String?) -> Unit = { _, _ -> },
    onShareItem: (Uri, String?, String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SketchMarkerYellow)
                            .border(1.5.dp, SketchInkBlack, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "History",
                            tint = SketchInkBlack,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "ডাউনলোড হিস্ট্রি (${items.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = SketchInkBlack
                        )
                        Text(
                            text = "যেকোনো ফাইলে ক্লিক করে সরাসরি ওপেন করুন",
                            style = MaterialTheme.typography.labelSmall,
                            color = SketchGraphite
                        )
                    }
                }

                if (items.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SketchCoralWash)
                            .border(1.2.dp, SketchInkBlack, RoundedCornerShape(8.dp))
                            .clickable { onClearAll() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("clear_history_button")
                    ) {
                        Text(
                            text = "সব মুছুন",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = SketchCrimsonInk
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
                    items.forEach { item ->
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
                text = "এখনও কোনো পিডিএফ হিস্ট্রিতে নেই",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.ExtraBold,
                color = SketchInkBlack,
                textAlign = TextAlign.Center
            )
            Text(
                text = "পিডিএফ তৈরি করলেই কবে ও কখন করা হয়েছে তার তালিকা এখানে জমা থাকবে",
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
            .background(SketchCreamWash.copy(alpha = 0.35f))
            .border(1.6.dp, SketchInkBlack, RoundedCornerShape(12.dp))
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
                    text = "${item.pageCount} পেজ • ${PdfStorageHelper.formatFileSize(item.fileSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = SketchInkBlack
                )
            }
        }

        // Quick Action Row: Open PDF, Open in Files, Share, Delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Open PDF directly
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
                        text = "ওপেন করুন",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
            }

            // Open in Files
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
                        text = "ফাইলস",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                }
            }

            // Share PDF
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

            // Delete from History
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
