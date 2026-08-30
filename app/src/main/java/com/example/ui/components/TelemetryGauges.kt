package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElegantAmber
import com.example.ui.theme.ElegantBlueAccent
import com.example.ui.theme.ElegantBlueLight
import com.example.ui.theme.ElegantBluePrimary
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ElegantDarkCardInset
import com.example.ui.theme.ElegantDarkSurface
import com.example.ui.theme.ElegantDarkTrack
import com.example.ui.theme.ElegantEmerald
import com.example.ui.theme.ElegantEmeraldLight
import com.example.ui.theme.TextHeadings
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun CircularProgressGauge(
    progress: Float,
    speedMBps: Double,
    etaSeconds: Long,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ring_glow")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier.size(190.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            val arcSize = Size(diameter, diameter)

            // Background Track
            drawArc(
                color = ElegantDarkTrack,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Glowing Progress Arc
            val sweep = (progress * 360f).coerceIn(0f, 360f)
            if (sweep > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to ElegantBluePrimary,
                        0.5f to ElegantEmerald,
                        1.0f to ElegantBlueLight
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "%.1f%%".format(progress * 100f),
                color = TextHeadings,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "%.1f MB/s".format(speedMBps),
                color = ElegantEmeraldLight,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (etaSeconds > 0) "ETA: %02d:%02d".format(etaSeconds / 60, etaSeconds % 60) else "SYNCHRONIZING",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SparklineSpeedChart(
    history: List<Double>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, ElegantDarkBorder, RoundedCornerShape(14.dp)),
        color = ElegantDarkCardInset
    ) {
        if (history.size < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for I/O sample burst...", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            return@Surface
        }

        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
            val maxSpeed = (history.maxOrNull() ?: 10.0).coerceAtLeast(5.0)
            val w = size.width
            val h = size.height

            val stepX = w / (history.size - 1)
            val path = Path()
            val fillPath = Path()

            history.forEachIndexed { index, speed ->
                val x = index * stepX
                val y = h - ((speed / maxSpeed) * h).toFloat().coerceIn(0f, h)

                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            fillPath.lineTo((history.size - 1) * stepX, h)
            fillPath.close()

            // Draw Area Fill
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(ElegantBluePrimary.copy(alpha = 0.35f), Color.Transparent)
                )
            )

            // Draw Stroke Line
            drawPath(
                path = path,
                color = ElegantBlueLight,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun SpscRingBufferSaturationBar(
    saturation: Float,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "SPSC DIRECT RING BUFFER",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "%.0f%% LOAD".format(saturation * 100f),
                color = if (saturation > 0.8f) ElegantAmber else ElegantBlueAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(ElegantDarkTrack)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(saturation.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(ElegantBlueAccent, if (saturation > 0.85f) ElegantAmber else ElegantEmerald)
                        )
                    )
            )
        }
    }
}

@Composable
fun SectorBlockMatrix(
    blocks: List<Int>, // 0=pending, 1=active, 2=done
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "PHYSICAL SECTOR MAPPING (64-BLOCK STRIDE)",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, ElegantDarkBorder, RoundedCornerShape(14.dp))
                .padding(10.dp),
            color = ElegantDarkCardInset
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(16),
                modifier = Modifier.height(64.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(blocks.size) { index ->
                    val status = blocks[index]
                    val color = when (status) {
                        2 -> ElegantEmerald // Done
                        1 -> ElegantBlueAccent // Active
                        else -> ElegantDarkTrack // Pending
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color)
                    )
                }
            }
        }
    }
}
