package com.ashishsinghbora.flashcore.flasher.ventoy

import com.ashishsinghbora.flashcore.block.BlockDevice
import com.ashishsinghbora.flashcore.fat32.Fat32Writer
import com.ashishsinghbora.flashcore.partition.GptBuilder
import com.ashishsinghbora.flashcore.partition.MbrBuilder
import com.ashishsinghbora.flashcore.partition.PartitionBlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Inspects a physical block device to detect existing Ventoy installations, partition styles,
 * installed bootloader version, and stored ISO images.
 */
object VentoyDetector {

    const val VENTOY_DISK_SIGNATURE = 0x56544F59 // "VTOY" in Little-Endian ASCII

    suspend fun detect(device: BlockDevice): VentoyDiskInfo {
        val cap = try {
            device.capacity()
        } catch (_: Exception) {
            return VentoyDiskInfo.notInstalled()
        }

        val totalSectors = cap.totalSectors
        val sectorSize = cap.sectorSizeBytes.coerceAtLeast(512)

        // 1. Read Sector 0 (MBR)
        val mbrSector = ByteArray(sectorSize)
        if (!device.read(0L, 1, mbrSector)) {
            return VentoyDiskInfo.notInstalled()
        }

        val mbrBuf = ByteBuffer.wrap(mbrSector).order(ByteOrder.LITTLE_ENDIAN)
        val diskSig = mbrBuf.getInt(440)
        val hasVtoySig = (diskSig == VENTOY_DISK_SIGNATURE)
        val hasVtoyOem = mbrSector[3] == 'V'.code.toByte() &&
                mbrSector[4] == 'E'.code.toByte() &&
                mbrSector[5] == 'N'.code.toByte() &&
                mbrSector[6] == 'T'.code.toByte() &&
                mbrSector[7] == 'O'.code.toByte() &&
                mbrSector[8] == 'Y'.code.toByte()

        // 2. Check for MBR Partition Style
        val p1Type = mbrSector[446 + 4]
        val p2Type = mbrSector[446 + 16 + 4]
        val p1StartLba = mbrBuf.getInt(446 + 8).toLong() and 0xFFFFFFFFL
        val p1SectorCount = mbrBuf.getInt(446 + 12).toLong() and 0xFFFFFFFFL
        val p2StartLba = mbrBuf.getInt(446 + 16 + 8).toLong() and 0xFFFFFFFFL
        val p2SectorCount = mbrBuf.getInt(446 + 16 + 12).toLong() and 0xFFFFFFFFL

        val isMbrVentoy = (hasVtoySig || hasVtoyOem || p2Type == MbrBuilder.TYPE_EFI_SYSTEM_PARTITION) &&
                p2SectorCount == VentoyGeometryCalculator.VTOYEFI_SECTOR_COUNT &&
                p1StartLba == VentoyGeometryCalculator.PART1_START_LBA

        var detectedStyle: VentoyPartitionStyle? = null
        var layout: VentoyPartitionLayout? = null

        if (isMbrVentoy) {
            detectedStyle = VentoyPartitionStyle.MBR
            layout = VentoyPartitionLayout(
                diskTotalSectors = totalSectors,
                partitionStyle = VentoyPartitionStyle.MBR,
                sectorSizeBytes = sectorSize,
                part1StartLba = p1StartLba,
                part1SectorCount = p1SectorCount,
                part2StartLba = p2StartLba,
                part2SectorCount = p2SectorCount
            )
        } else if (p1Type == MbrBuilder.TYPE_GPT_PROTECTIVE) {
            // Check for GPT Ventoy
            val gptHeaderSector = ByteArray(sectorSize)
            if (device.read(1L, 1, gptHeaderSector)) {
                val sig = String(gptHeaderSector, 0, 8, Charsets.US_ASCII)
                if (sig == GptBuilder.GPT_HEADER_SIGNATURE) {
                    val gptEntries = ByteArray(32 * sectorSize)
                    if (device.read(2L, 32, gptEntries)) {
                        val eBuf = ByteBuffer.wrap(gptEntries).order(ByteOrder.LITTLE_ENDIAN)

                        // Inspect Partition 2 (offset 128)
                        val p2TypeGuid = UUID(eBuf.getLong(128 + 8), eBuf.getLong(128))
                        val gptP2Start = eBuf.getLong(128 + 32)
                        val gptP2End = eBuf.getLong(128 + 40)
                        val gptP2Count = gptP2End - gptP2Start + 1L

                        val gptP1Start = eBuf.getLong(32)
                        val gptP1End = eBuf.getLong(40)
                        val gptP1Count = gptP1End - gptP1Start + 1L

                        if (p2TypeGuid == GptBuilder.GUID_EFI_SYSTEM && gptP2Count == VentoyGeometryCalculator.VTOYEFI_SECTOR_COUNT) {
                            detectedStyle = VentoyPartitionStyle.GPT
                            layout = VentoyPartitionLayout(
                                diskTotalSectors = totalSectors,
                                partitionStyle = VentoyPartitionStyle.GPT,
                                sectorSizeBytes = sectorSize,
                                part1StartLba = gptP1Start,
                                part1SectorCount = gptP1Count,
                                part2StartLba = gptP2Start,
                                part2SectorCount = gptP2Count,
                                backupGptLba = totalSectors - 1L
                            )
                        }
                    }
                }
            }
        }

        if (detectedStyle == null || layout == null) {
            return VentoyDiskInfo.notInstalled()
        }

        // 3. Inspect Partition 2 (VTOYEFI) to read version and verify filesystem
        var installedVersion: String? = null
        try {
            val part2Device = PartitionBlockDevice(device, layout.part2StartLba, layout.part2SectorCount)
            val p2Writer = Fat32Writer.mount(part2Device)
            if (p2Writer.exists("/ventoy/version")) {
                val versionBytes = p2Writer.readFile("/ventoy/version")
                installedVersion = String(versionBytes, Charsets.UTF_8).trim()
            } else {
                installedVersion = "Generic Ventoy Core"
            }
        } catch (_: Exception) {
            installedVersion = "Unreadable VTOYEFI"
        }

        // 4. Inspect Partition 1 (Data) to list existing stored OS images
        val storedIsos = try {
            val part1Device = PartitionBlockDevice(device, layout.part1StartLba, layout.part1SectorCount)
            VentoyStorageManager.listStoredImages(part1Device)
        } catch (_: Exception) {
            emptyList()
        }

        return VentoyDiskInfo(
            isVentoyInstalled = true,
            installedVersion = installedVersion,
            partitionStyle = detectedStyle,
            layout = layout,
            dataPartitionSizeBytes = layout.part1SizeBytes,
            vtoyEfiSizeBytes = layout.part2SizeBytes,
            storedIsoFiles = storedIsos,
            canNonDestructiveUpdate = true
        )
    }
}
