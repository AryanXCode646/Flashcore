package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElegantAmber
import com.example.ui.theme.ElegantBlueAccent
import com.example.ui.theme.ElegantBlueLight
import com.example.ui.theme.ElegantCoral
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ElegantDarkCardInset
import com.example.ui.theme.ElegantDarkSurface
import com.example.ui.theme.ElegantEmeraldLight
import com.example.ui.theme.TextHeadings
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun TerminalLogView(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, ElegantDarkBorder, RoundedCornerShape(20.dp))
            .testTag("terminal_log_view"),
        color = ElegantDarkSurface
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Terminal Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFF59E0B)))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF10B981)))
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Kernel Log Console",
                    tint = ElegantBlueAccent,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "RAW SCSI BOT & I/O KERNEL LOG",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            // Log Console Stream Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ElegantDarkCardInset)
                    .border(1.dp, ElegantDarkBorder, RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                ) {
                    if (logs.isEmpty()) {
                        item {
                            Text(
                                text = "[SYSTEM] Ready for SCSI BOT transactions...",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        items(logs) { log ->
                            val textColor = when {
                                log.contains("Error", ignoreCase = true) || log.contains("FAILED", ignoreCase = true) -> ElegantCoral
                                log.contains("SUCCESS", ignoreCase = true) || log.contains("Granted", ignoreCase = true) -> ElegantEmeraldLight
                                log.contains("Warning", ignoreCase = true) || log.contains("WIM", ignoreCase = true) -> ElegantAmber
                                log.contains("SCSI", ignoreCase = true) || log.contains("BOT", ignoreCase = true) -> ElegantBlueLight
                                else -> TextPrimary
                            }
                            Text(
                                text = log,
                                color = textColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

