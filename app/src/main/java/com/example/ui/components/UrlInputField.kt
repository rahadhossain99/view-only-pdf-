package com.example.ui.components

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.ExtractionEngineMode
import com.example.data.model.ResolutionQuality
import com.example.ui.theme.SketchCard
import com.example.ui.theme.SketchCreamWash
import com.example.ui.theme.SketchGraphite
import com.example.ui.theme.SketchInkBlack
import com.example.ui.theme.SketchMarkerYellow
import com.example.ui.theme.SketchPenBlue
import com.example.ui.theme.SketchPenBlueWash

@Composable
fun UrlInputField(
    url: String,
    onUrlChange: (String) -> Unit,
    customFileName: String = "",
    onCustomFileNameChange: (String) -> Unit = {},
    isLoading: Boolean,
    selectedResolution: ResolutionQuality,
    onResolutionChange: (ResolutionQuality) -> Unit,
    selectedEngineMode: ExtractionEngineMode = ExtractionEngineMode.AUTO_HYBRID,
    onEngineModeChange: (ExtractionEngineMode) -> Unit = {},
    enhanceContrast: Boolean = false,
    onEnhanceContrastChange: (Boolean) -> Unit = {},
    onStartDownload: () -> Unit,
    onToggleInteractiveMode: () -> Unit,
    onOpenScreenshotStudio: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    SketchCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.White,
        borderColor = SketchInkBlack,
        shadowColor = SketchInkBlack,
        cornerRadius = 16.dp,
        shadowOffset = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Sketch Header Label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SketchMarkerYellow)
                        .border(1.5.dp, SketchInkBlack, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Drive Link",
                        tint = SketchInkBlack,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "View-Only Google Drive Link",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = SketchInkBlack
                    )
                    Text(
                        text = "লিংক পেস্ট করুন — সব পেজ অটোমেটিক পারফেক্ট পিডিএফ হবে",
                        style = MaterialTheme.typography.labelSmall,
                        color = SketchGraphite
                    )
                }
            }

            // 2. URL Input Box with Sketch Border
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drive_url_input"),
                placeholder = {
                    Text(
                        text = "https://drive.google.com/file/d/.../view",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SketchGraphite.copy(alpha = 0.65f)
                    )
                },
                singleLine = false,
                maxLines = 2,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SketchCreamWash.copy(alpha = 0.55f),
                    unfocusedContainerColor = SketchCreamWash.copy(alpha = 0.35f),
                    focusedBorderColor = SketchInkBlack,
                    unfocusedBorderColor = SketchInkBlack.copy(alpha = 0.75f),
                    focusedTextColor = SketchInkBlack,
                    unfocusedTextColor = SketchInkBlack
                ),
                trailingIcon = {
                    if (url.isNotEmpty()) {
                        IconButton(
                            onClick = { onUrlChange("") },
                            modifier = Modifier.testTag("clear_url_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear Link",
                                tint = SketchInkBlack
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                                    onUrlChange(text)
                                }
                            },
                            modifier = Modifier.testTag("paste_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste Link",
                                tint = SketchInkBlack
                            )
                        }
                    }
                }
            )

            // 3. Editable PDF File Name Field (Before or During Download)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DriveFileRenameOutline,
                        contentDescription = null,
                        tint = SketchInkBlack,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "পিডিএফ ফাইলের নাম (ইচ্ছামতো নাম দিন)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SketchInkBlack
                    )
                }

                OutlinedTextField(
                    value = customFileName,
                    onValueChange = onCustomFileNameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_pdf_name_input"),
                    placeholder = {
                        Text(
                            text = "যেমন: My_Lecture_Sheet (খালি রাখলে অটো নাম নেবে)",
                            style = MaterialTheme.typography.bodySmall,
                            color = SketchGraphite.copy(alpha = 0.65f)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = SketchInkBlack,
                        unfocusedBorderColor = SketchInkBlack.copy(alpha = 0.55f),
                        focusedTextColor = SketchInkBlack,
                        unfocusedTextColor = SketchInkBlack
                    ),
                    trailingIcon = {
                        if (customFileName.isNotEmpty()) {
                            IconButton(
                                onClick = { onCustomFileNameChange("") },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear File Name",
                                    tint = SketchGraphite,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                )
            }

            // 4. Upfront PDF Quality & File Size Selector (3 Sketch Cards)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "পিডিএফ সাইজ ও কোয়ালিটি নির্বাচন করুন:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = SketchInkBlack
                )

                val qualityOptions = listOf(
                    ResolutionQuality.STANDARD,
                    ResolutionQuality.HIGH,
                    ResolutionQuality.ULTRA
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    qualityOptions.forEach { quality ->
                        val isSelected = selectedResolution == quality
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) SketchMarkerYellow else SketchCreamWash.copy(alpha = 0.45f)
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.2.dp,
                                    color = SketchInkBlack,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onResolutionChange(quality) }
                                .padding(vertical = 10.dp, horizontal = 6.dp)
                                .testTag("quality_option_${quality.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = quality.shortTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = SketchInkBlack,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = quality.sizeHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) SketchInkBlack else SketchGraphite,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // 5. Primary Sketch Ink Download Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                Button(
                    onClick = onStartDownload,
                    enabled = url.isNotBlank() && !isLoading,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .border(2.dp, SketchInkBlack, RoundedCornerShape(12.dp))
                        .testTag("start_download_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SketchInkBlack,
                        contentColor = Color.White,
                        disabledContainerColor = SketchCreamWash,
                        disabledContentColor = SketchGraphite
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download PDF",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isLoading) "পিডিএফ তৈরি হচ্ছে..." else "পিডিএফ ডাউনলোড করুন (Download PDF)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // 6. Subtle Sign-In Helper for Private Links
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SketchPenBlueWash.copy(alpha = 0.45f))
                    .border(1.dp, SketchInkBlack.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .clickable { onToggleInteractiveMode() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("interactive_viewer_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.LockOpen,
                    contentDescription = "Private Link Login",
                    tint = SketchPenBlue,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "প্রাইভেট লিংক বা পেজ দেখতে চাইলে এখানে ট্যাপ করে ব্রাউজারে ওপেন করুন",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SketchInkBlack
                )
            }
        }
    }
}
