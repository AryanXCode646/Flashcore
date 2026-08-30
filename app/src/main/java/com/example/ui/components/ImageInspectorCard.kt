package com.example.ui.components

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
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dsa.IsoTrieParser
import com.example.ui.theme.ElegantAmber
import com.example.ui.theme.ElegantAmberBg
import com.example.ui.theme.ElegantAmberBorder
import com.example.ui.theme.ElegantBlueAccent
import com.example.ui.theme.ElegantBlueLight
import com.example.ui.theme.ElegantBluePrimary
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ElegantDarkCardInset
import com.example.ui.theme.ElegantDarkSurface
import com.example.ui.theme.ElegantEmerald
import com.example.ui.theme.ElegantEmeraldBg
import com.example.ui.theme.ElegantEmeraldLight
import com.example.ui.theme.ElegantPurple
import com.example.ui.theme.ElegantPurpleBg
import com.example.ui.theme.TextHeadings
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ImageInspectorCard(
    fileName: String?,
    analysis: IsoTrieParser.AnalysisResult?,
    isAnalyzing: Boolean,
    onPickImage: () -> Unit,
    onLoadSample: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, ElegantDarkBorder, RoundedCornerShape(24.dp))
            .testTag("image_inspector_card"),
        color = ElegantDarkSurface
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Main Payload Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x0DFFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Topic,
                            contentDescription = "Selected Payload",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SELECTED PAYLOAD",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = fileName ?: (analysis?.volumeLabel ?: "No image chosen"),
                            color = TextHeadings,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = when {
                                analysis != null -> "${analysis.imageType.displayName} (${analysis.architecture})"
                                else -> "Select bootable ISO, IMG or Windows WIM"
                            },
                            color = ElegantBlueAccent,
                            fontSize = 11.sp
                        )
                    }
                }

                TextButton(
                    onClick = onPickImage,
                    modifier = Modifier.testTag("select_iso_button")
                ) {
                    Text(
                        text = if (analysis != null) "CHANGE" else "SELECT ISO",
                        color = ElegantBlueAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (isAnalyzing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ElegantDarkCardInset)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = ElegantBlueLight, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Inspecting ISO structures & directory trie...",
                            color = ElegantBlueAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else if (analysis != null) {
                // Info Sub-Grid
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ElegantDarkCardInset)
                        .border(1.dp, ElegantDarkBorder, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Volume: ${analysis.volumeLabel}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace
                            )

                            // Tag
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when (analysis.imageType) {
                                            IsoTrieParser.ImageType.WINDOWS_INSTALLER -> Color(0x262563EB)
                                            IsoTrieParser.ImageType.VENTOY_BOOTABLE -> ElegantPurpleBg
                                            else -> ElegantEmeraldBg
                                        }
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = analysis.imageType.displayName.uppercase(),
                                    color = when (analysis.imageType) {
                                        IsoTrieParser.ImageType.WINDOWS_INSTALLER -> ElegantBlueAccent
                                        IsoTrieParser.ImageType.VENTOY_BOOTABLE -> ElegantPurple
                                        else -> ElegantEmeraldLight
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Size: ${analysis.formattedSize}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Boot: ${if (analysis.hasEfiBoot) "UEFI x64" else "BIOS MBR"}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        if (analysis.requiresWimSplit) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ElegantAmberBg)
                                    .border(1.dp, ElegantAmberBorder, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "⚡ install.wim > 4 GB (${analysis.installWimSize / (1024 * 1024)} MB). Auto-SWM split active.",
                                    color = ElegantAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            } else {
                // Preset samples
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ElegantDarkCardInset)
                                .border(1.dp, ElegantDarkBorder, RoundedCornerShape(12.dp))
                                .clickable { onLoadSample("ubuntu") }
                                .padding(12.dp)
                                .testTag("sample_ubuntu_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Ubuntu 24.04", fontSize = 11.sp, color = ElegantEmeraldLight, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ElegantDarkCardInset)
                                .border(1.dp, ElegantDarkBorder, RoundedCornerShape(12.dp))
                                .clickable { onLoadSample("windows") }
                                .padding(12.dp)
                                .testTag("sample_windows_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Windows 11 UEFI", fontSize = 11.sp, color = ElegantBlueAccent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Footer metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Rolling Digest: SHA-256",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Block Size: 4 MB",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

