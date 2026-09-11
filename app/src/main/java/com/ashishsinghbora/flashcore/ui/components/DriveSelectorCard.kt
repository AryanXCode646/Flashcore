package com.ashishsinghbora.flashcore.ui.components

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashishsinghbora.flashcore.ui.theme.ElegantAmber
import com.ashishsinghbora.flashcore.ui.theme.ElegantAmberBg
import com.ashishsinghbora.flashcore.ui.theme.ElegantAmberBorder
import com.ashishsinghbora.flashcore.ui.theme.ElegantBlueAccent
import com.ashishsinghbora.flashcore.ui.theme.ElegantBlueLight
import com.ashishsinghbora.flashcore.ui.theme.ElegantDarkBorder
import com.ashishsinghbora.flashcore.ui.theme.ElegantDarkCardInset
import com.ashishsinghbora.flashcore.ui.theme.ElegantDarkSurface
import com.ashishsinghbora.flashcore.ui.theme.ElegantEmeraldBg
import com.ashishsinghbora.flashcore.ui.theme.ElegantEmeraldBorder
import com.ashishsinghbora.flashcore.ui.theme.ElegantEmeraldLight
import com.ashishsinghbora.flashcore.ui.theme.TextHeadings
import com.ashishsinghbora.flashcore.ui.theme.TextMuted
import com.ashishsinghbora.flashcore.ui.theme.TextPrimary
import com.ashishsinghbora.flashcore.ui.theme.TextSecondary
import com.ashishsinghbora.flashcore.usb.UsbDiskInfo

@Composable
fun DriveSelectorCard(
    devices: List<UsbDiskInfo>,
    selectedDevice: UsbDiskInfo?,
    onSelectDevice: (UsbDiskInfo) -> Unit,
    onRequestPermission: (UsbDiskInfo) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, ElegantDarkBorder, RoundedCornerShape(24.dp))
            .testTag("drive_selector_card"),
        color = ElegantDarkSurface
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Top Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "TARGET INTERFACE",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = selectedDevice?.displayName ?: "No USB Target Attached",
                        color = TextHeadings,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (selectedDevice != null) {
                            val deviceName = selectedDevice.device?.deviceName ?: "USB Mass Storage"
                            "$deviceName [LUN 0]"
                        } else {
                            "Connect USB OTG flash drive or external SSD"
                        },
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Enum Status Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedDevice != null) ElegantEmeraldBg else ElegantAmberBg)
                            .border(
                                1.dp,
                                if (selectedDevice != null) ElegantEmeraldBorder else ElegantAmberBorder,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (selectedDevice != null) "ENUMERATED" else "UNATTACHED",
                            color = if (selectedDevice != null) ElegantEmeraldLight else ElegantAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(28.dp).testTag("refresh_devices_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Drives",
                            tint = ElegantBlueAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (selectedDevice != null) {
                // Capacity & Sector Size Sub-Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ElegantDarkCardInset)
                            .border(1.dp, ElegantDarkBorder, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "CAPACITY",
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "%.1f GiB".format(selectedDevice.totalCapacityBytes / (1024.0 * 1024.0 * 1024.0)),
                                color = TextHeadings,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ElegantDarkCardInset)
                            .border(1.dp, ElegantDarkBorder, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "SECTOR SIZE",
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${selectedDevice.sectorSizeBytes} Bytes",
                                color = TextHeadings,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                if (devices.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = true }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "Multiple drives found — Click to switch ▾",
                            color = ElegantBlueAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            devices.forEach { device ->
                                DropdownMenuItem(
                                    text = { Text(device.displayName) },
                                    onClick = {
                                        onSelectDevice(device)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (!selectedDevice.hasPermission && selectedDevice.device != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onRequestPermission(selectedDevice) },
                        colors = ButtonDefaults.buttonColors(containerColor = ElegantAmber),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("grant_permission_button")
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("GRANT ANDROID USB PERMISSION", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ElegantDarkCardInset)
                        .border(1.dp, ElegantDarkBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Awaiting USB OTG Storage Insertion",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

