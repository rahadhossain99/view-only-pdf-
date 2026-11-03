package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadState
import com.example.data.model.PdfCompressionLevel
import com.example.data.storage.PdfStorageHelper
import com.example.ui.theme.SketchCard
import com.example.ui.theme.SketchCreamWash
import com.example.ui.theme.SketchForestInk
import com.example.ui.theme.SketchGraphite
import com.example.ui.theme.SketchInkBlack
import com.example.ui.theme.SketchMarkerYellow
import com.example.ui.theme.SketchMintWash
import com.example.ui.theme.SketchPenBlueWash

@Composable
fun SuccessCard(
    successState: DownloadState.Success,
    isCompressing: Boolean = false,
    compressionStatus: String? = null,
    onRenamePdf: (String) -> Unit = {},
    onCompressPdf: (PdfCompressionLevel) -> Unit = {},
    onOpenInFiles: () -> Unit = {},
    onOpenPdf: () -> Unit,
    onSharePdf: () -> Unit,
    onStartNewSession: () -> Unit = {},
    onOpenInStudio: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cleanInitialName = PdfStorageHelper.cleanDocumentTitle(successState.fileName)
        ?: successState.fileName.removeSuffix(".pdf")

    var editableName by remember(successState.fileName) {
        mutableStateOf(cleanInitialName)
    }
    var showRenameBox by remember { mutableStateOf(false) }

    LaunchedEffect(successState.fileName) {
        editableName = PdfStorageHelper.cleanDocumentTitle(successState.fileName)
            ?: successState.fileName.removeSuffix(".pdf")
    }

    SketchCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_success_card"),
        containerColor = Color.White,
        borderColor = SketchInkBlack,
        shadowColor = SketchInkBlack,
        cornerRadius = 18.dp,
        shadowOffset = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Success Header + Quick Rename Toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SketchMintWash)
                        .border(2.dp, SketchInkBlack, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = SketchForestInk,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PDF Ready (100%)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                    Text(
                        text = successState.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SketchGraphite,
                        maxLines = 1
                    )
                }

                // Compact Rename Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (showRenameBox) SketchMarkerYellow else SketchCreamWash)
                        .border(1.4.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                        .clickable { showRenameBox = !showRenameBox }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                        .testTag("toggle_rename_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DriveFileRenameOutline,
                            contentDescription = "Rename PDF",
                            tint = SketchInkBlack,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Rename",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = SketchInkBlack
                        )
                    }
                }
            }

            // 2. Sketch Info Badges (Pages, Current File Size, Before/After Savings)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SketchBadge(
                    text = "${successState.pageCount} Pages",
                    backgroundColor = SketchCreamWash
                )
                SketchBadge(
                    text = "Size: ${PdfStorageHelper.formatFileSize(successState.fileSizeBytes)}",
                    backgroundColor = SketchMarkerYellow
                )
                if (successState.previousSizeBytes != null && successState.previousSizeBytes > successState.fileSizeBytes) {
                    SketchBadge(
                        text = "Was: ${PdfStorageHelper.formatFileSize(successState.previousSizeBytes)}",
                        backgroundColor = SketchMintWash
                    )
                }
            }

            // 3. Collapsible PDF Rename Box
            AnimatedVisibility(
                visible = showRenameBox,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SketchCreamWash.copy(alpha = 0.5f))
                        .border(1.4.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = editableName,
                        onValueChange = { editableName = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("rename_pdf_input"),
                        placeholder = {
                            Text(
                                text = "Enter new file name...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = SketchInkBlack,
                            unfocusedBorderColor = SketchInkBlack.copy(alpha = 0.6f),
                            focusedTextColor = SketchInkBlack,
                            unfocusedTextColor = SketchInkBlack
                        )
                    )

                    Button(
                        onClick = {
                            onRenamePdf(editableName)
                            showRenameBox = false
                        },
                        enabled = editableName.isNotBlank() &&
                                editableName.trim() != successState.fileName.removeSuffix(".pdf"),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .border(1.6.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                            .testTag("save_rename_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SketchMarkerYellow,
                            contentColor = SketchInkBlack,
                            disabledContainerColor = SketchCreamWash,
                            disabledContentColor = SketchGraphite
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save Name",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            // 4. Post-Download 1-Tap PDF Size Reducer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SketchPenBlueWash.copy(alpha = 0.35f))
                    .border(1.4.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Compress,
                        contentDescription = "Compress PDF Size",
                        tint = SketchInkBlack,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Reduce File Size",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = SketchInkBlack
                        )
                        Text(
                            text = "Tap any preset below to compress this PDF:",
                            style = MaterialTheme.typography.labelSmall,
                            color = SketchGraphite
                        )
                    }
                }

                if (isCompressing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = SketchInkBlack,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = compressionStatus ?: "Compressing PDF...",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = SketchInkBlack
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PdfCompressionLevel.entries.forEach { level ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White)
                                    .border(1.5.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                                    .clickable { onCompressPdf(level) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                                    .testTag("compress_${level.name.lowercase()}_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = level.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = SketchInkBlack,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = level.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = SketchForestInk,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isCompressing && !compressionStatus.isNullOrBlank()) {
                    Text(
                        text = compressionStatus,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SketchForestInk
                    )
                }
            }

            // 5. Primary Action: Open in Files
            Button(
                onClick = onOpenInFiles,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .border(2.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                    .testTag("open_in_files_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SketchInkBlack,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Open in Files",
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open in Files",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // 6. Secondary Row: Open PDF & Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenPdf,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(SketchMarkerYellow, RoundedCornerShape(10.dp))
                        .border(1.8.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                        .testTag("open_pdf_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open PDF",
                        tint = SketchInkBlack,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open PDF",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                }

                OutlinedButton(
                    onClick = onSharePdf,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(Color.White, RoundedCornerShape(10.dp))
                        .border(1.8.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                        .testTag("share_pdf_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share PDF",
                        tint = SketchInkBlack,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Share",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                }
            }

            // 7. "+ New PDF" Button
            OutlinedButton(
                onClick = onStartNewSession,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(SketchCreamWash.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .border(1.5.dp, SketchInkBlack, RoundedCornerShape(10.dp))
                    .testTag("success_new_session_button")
            ) {
                Text(
                    text = "+ Create New PDF",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = SketchInkBlack
                )
            }
        }
    }
}

@Composable
private fun SketchBadge(
    text: String,
    backgroundColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(1.4.dp, SketchInkBlack, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = SketchInkBlack,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = SketchInkBlack
            )
        }
    }
}
