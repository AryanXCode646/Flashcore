package com.ashishsinghbora.flashcore.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashishsinghbora.flashcore.flasher.fsm.FlasherState
import com.ashishsinghbora.flashcore.ui.FlasherViewModel
import com.ashishsinghbora.flashcore.ui.components.CircularProgressGauge
import com.ashishsinghbora.flashcore.ui.components.DriveSelectorCard
import com.ashishsinghbora.flashcore.ui.components.ImageInspectorCard
import com.ashishsinghbora.flashcore.ui.components.SectorBlockMatrix
import com.ashishsinghbora.flashcore.ui.components.SparklineSpeedChart
import com.ashishsinghbora.flashcore.ui.components.SpscRingBufferSaturationBar
import com.ashishsinghbora.flashcore.ui.components.TerminalLogView
import com.ashishsinghbora.flashcore.ui.theme.ElegantAmber
import com.ashishsinghbora.flashcore.ui.theme.ElegantBlueAccent
import com.ashishsinghbora.flashcore.ui.theme.ElegantBlueLight
import com.ashishsinghbora.flashcore.ui.theme.ElegantBluePrimary
import com.ashishsinghbora.flashcore.ui.theme.ElegantCoral
import com.ashishsinghbora.flashcore.ui.theme.ElegantDarkBackground
import com.ashishsinghbora.flashcore.ui.theme.ElegantDarkBorder
import com.ashishsinghbora.flashcore.ui.theme.ElegantDarkBorderSubtle
import com.ashishsinghbora.flashcore.ui.theme.ElegantDarkCardInset
import com.ashishsinghbora.flashcore.ui.theme.ElegantDarkSurface
import com.ashishsinghbora.flashcore.ui.theme.ElegantEmerald
import com.ashishsinghbora.flashcore.ui.theme.ElegantEmeraldBg
import com.ashishsinghbora.flashcore.ui.theme.ElegantEmeraldBorder
import com.ashishsinghbora.flashcore.ui.theme.ElegantEmeraldLight
import com.ashishsinghbora.flashcore.ui.theme.TextHeadings
import com.ashishsinghbora.flashcore.ui.theme.TextMuted
import com.ashishsinghbora.flashcore.ui.theme.TextPrimary
import com.ashishsinghbora.flashcore.ui.theme.TextSecondary
import com.ashishsinghbora.flashcore.ui.theme.TextFaint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainFlasherScreen(
    viewModel: FlasherViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image.iso"
            viewModel.analyzeIsoUri(uri, name)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ElegantBluePrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "FlashCore",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "FlashCore",
                                color = TextHeadings,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.2).sp
                            )
                            Text(
                                text = "KOTLIN USB HOST | BOT SCSI ENGINE",
                                color = ElegantBlueAccent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                },
                actions = {
                    // USB Status Badge in Top App Bar
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (uiState.selectedDevice != null) ElegantEmeraldBg else Color(0x0DFFFFFF))
                            .border(
                                1.dp,
                                if (uiState.selectedDevice != null) ElegantEmeraldBorder else ElegantDarkBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (uiState.selectedDevice != null) ElegantEmeraldLight else ElegantAmber)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (uiState.selectedDevice != null) "ENUMERATED" else "NO USB",
                                color = if (uiState.selectedDevice != null) ElegantEmeraldLight else ElegantAmber,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = ElegantDarkBackground
                )
            )
        },
        containerColor = ElegantDarkBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // SECTION 1: TARGET USB DRIVE SELECTOR (Device status & interface)
            DriveSelectorCard(
                devices = uiState.connectedDevices,
                selectedDevice = uiState.selectedDevice,
                onSelectDevice = { viewModel.selectDevice(it) },
                onRequestPermission = { viewModel.requestDevicePermission(it) },
                onRefresh = { viewModel.refreshDevices() }
            )

            // SECTION 2: SOURCE IMAGE INSPECTOR
            ImageInspectorCard(
                fileName = uiState.selectedIsoName,
                analysis = uiState.isoAnalysis,
                isAnalyzing = uiState.isAnalyzingIso,
                onPickImage = { filePickerLauncher.launch(arrayOf("*/*", "application/x-iso9660-image", "application/octet-stream")) },
                onLoadSample = { viewModel.loadSampleImage(it) }
            )

            // SECTION 3: FLASHING STRATEGY SELECTOR
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, ElegantDarkBorder, RoundedCornerShape(24.dp))
                    .testTag("strategy_selector_card"),
                color = ElegantDarkSurface
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "ENGINE STRATEGY",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Strategy",
                            tint = ElegantBlueAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Bootloader & Partition Strategy",
                            color = TextHeadings,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    viewModel.getStrategies().forEach { strategy ->
                        val isSelected = uiState.selectedStrategyId == strategy.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) Color(0x262563EB) else ElegantDarkCardInset)
                                .border(
                                    1.dp,
                                    if (isSelected) ElegantBlueLight else ElegantDarkBorder,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { viewModel.selectStrategy(strategy.id) }
                                .padding(14.dp)
                                .testTag("strategy_item_${strategy.id}")
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = strategy.displayName,
                                        color = if (isSelected) ElegantBlueAccent else TextHeadings,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = ElegantBlueLight,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = strategy.description,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            // SECTION 4: HARDWARE & ENGINE TUNING CONFIGURATION
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, ElegantDarkBorder, RoundedCornerShape(24.dp)),
                color = ElegantDarkSurface
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "I/O HARDWARE & BUFFER TUNING",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Settings",
                            tint = ElegantBlueAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "BOT Stride & Verification",
                            color = TextHeadings,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Block Size Selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "SCSI Transfer Stride",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Direct ring buffer chunk size",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(512 * 1024 to "512K", 1024 * 1024 to "1MB", 2 * 1024 * 1024 to "2MB", 4 * 1024 * 1024 to "4MB").forEach { (size, label) ->
                                val isChosen = uiState.config.blockSizeBytes == size
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isChosen) ElegantBluePrimary else Color(0x0DFFFFFF))
                                        .border(
                                            1.dp,
                                            if (isChosen) ElegantBlueLight else ElegantDarkBorder,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { viewModel.updateConfig(uiState.config.copy(blockSizeBytes = size)) }
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isChosen) Color.White else TextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Verify After Write Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Verify SHA-256 Checksum",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Compute checksum during stream (Sector read-back planned)",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = uiState.config.verifyAfterWrite,
                            onCheckedChange = { viewModel.updateConfig(uiState.config.copy(verifyAfterWrite = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ElegantBluePrimary,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = Color(0x1AFFFFFF)
                            )
                        )
                    }

                    // Auto-Split WIM Toggle
                    if (uiState.selectedStrategyId == "WINDOWS_UEFI") {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Auto-Split >4GB WIMs (.swm)",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Enables FAT32 UEFI compatibility",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = uiState.config.autoSplitWim,
                                onCheckedChange = { viewModel.updateConfig(uiState.config.copy(autoSplitWim = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = ElegantBluePrimary,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = Color(0x1AFFFFFF)
                                )
                            )
                        }
                    }
                }
            }

            // SECTION 5: ACTIVE TELEMETRY DASHBOARD (When Flashing or Completed)
            AnimatedVisibility(
                visible = uiState.isFlashing || uiState.fsmState is FlasherState.Streaming || uiState.fsmState is FlasherState.Partitioning || uiState.fsmState is FlasherState.Completed,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, ElegantBlueLight.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                        .testTag("telemetry_dashboard"),
                    color = ElegantDarkSurface
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val state = uiState.fsmState
                        val progress = when (state) {
                            is FlasherState.Streaming -> state.progress
                            is FlasherState.Partitioning -> state.progress
                            is FlasherState.Completed -> 1.0f
                            else -> 0.0f
                        }
                        val speed = when (state) {
                            is FlasherState.Streaming -> state.speedMBps
                            is FlasherState.Completed -> state.averageSpeedMBps
                            else -> 0.0
                        }
                        val eta = when (state) {
                            is FlasherState.Streaming -> state.etaSeconds
                            else -> 0L
                        }
                        val saturation = when (state) {
                            is FlasherState.Streaming -> state.bufferSaturation
                            else -> 0.0f
                        }

                        // Header Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "FSM PIPELINE STATUS",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (uiState.isFlashing) ElegantBlueLight else ElegantEmeraldLight)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = when (state) {
                                            is FlasherState.Partitioning -> "PARTITIONING DISK"
                                            is FlasherState.Streaming -> "STREAMING SECTORS"
                                            is FlasherState.Completed -> "FLASH COMPLETE"
                                            else -> "READY"
                                        },
                                        color = TextHeadings,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "WRITE SPEED",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "%.1f MB/s".format(speed),
                                    color = ElegantBlueAccent,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Circular Progress Gauge
                        CircularProgressGauge(
                            progress = progress,
                            speedMBps = speed,
                            etaSeconds = eta
                        )

                        // SPSC Ring Buffer Saturation
                        SpscRingBufferSaturationBar(saturation = saturation)

                        // Live Speed Sparkline
                        SparklineSpeedChart(history = uiState.speedHistory)

                        // Physical 64-Block Stride Grid
                        SectorBlockMatrix(blocks = uiState.activeBlocks)

                        // Emergency Cancel Button
                        if (uiState.isFlashing) {
                            Button(
                                onClick = { viewModel.cancelFlashing() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("cancel_flash_button")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "ABORT OPERATION",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    letterSpacing = 1.5.sp
                                )
                            }
                        }
                    }
                }
            }

            // COMPLETED STATE BANNER
            if (uiState.fsmState is FlasherState.Completed) {
                val completed = uiState.fsmState as FlasherState.Completed
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, ElegantEmeraldBorder, RoundedCornerShape(24.dp))
                        .testTag("completed_banner"),
                    color = ElegantDarkSurface
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ElegantEmeraldLight, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "FLASH SUCCESSFUL — SAFE TO EJECT",
                                color = ElegantEmeraldLight,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Wrote ${completed.totalBytesWritten / (1024 * 1024)} MB in ${completed.durationMs / 1000}s (Avg: %.2f MB/s)".format(completed.averageSpeedMBps),
                            color = TextHeadings,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "SHA-256: ${completed.sha256Checksum.take(32)}...",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.resetSession() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("reset_session_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("FLASH ANOTHER DRIVE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                        }
                    }
                }
            }

            // PRIMARY ACTION: FLASH BUTTON (When not currently flashing)
            if (!uiState.isFlashing && uiState.fsmState !is FlasherState.Completed) {
                Button(
                    onClick = { viewModel.openSafetyConfirmation() },
                    enabled = uiState.selectedDevice != null && uiState.selectedIsoUri != null && !uiState.isAnalyzingIso,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        disabledContainerColor = Color(0x1AFFFFFF)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("flash_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "Flash Now",
                        tint = if (uiState.selectedDevice != null && uiState.selectedIsoUri != null) Color.Black else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "WRITE BOOTABLE MEDIA TO USB",
                        color = if (uiState.selectedDevice != null && uiState.selectedIsoUri != null) Color.Black else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 1.2.sp
                    )
                }

                Text(
                    text = "SPSC RING BUFFER ACTIVE | SCSI BULK TRANSPORT MODE",
                    color = TextFaint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            // SECTION 6: KERNEL LOG STREAM CONSOLE
            TerminalLogView(logs = uiState.logs)

            Spacer(Modifier.height(10.dp))
        }
    }

    // DESTRUCTIVE ACTION SAFETY MODAL
    if (uiState.showSafetyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSafetyConfirmation() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = ElegantAmber,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "CONFIRM DRIVE OVERWRITE",
                    color = TextHeadings,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "ALL PARTITIONS AND DATA on the target drive will be IRREVERSIBLY DESTROYED and replaced with the selected bootloader structures.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElegantDarkCardInset)
                            .border(1.dp, ElegantDarkBorder, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "Target: ${uiState.selectedDevice?.displayName}",
                                color = ElegantBlueAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Engine: ${uiState.selectedStrategyId}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.startFlashing() },
                    colors = ButtonDefaults.buttonColors(containerColor = ElegantCoral),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_wipe_and_flash_button")
                ) {
                    Text("YES, WIPE & FLASH", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissSafetyConfirmation() },
                    modifier = Modifier.testTag("cancel_wipe_dialog_button")
                ) {
                    Text("CANCEL", color = TextSecondary)
                }
            },
            containerColor = ElegantDarkSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

