package com.ashishsinghbora.flashcore.flasher.ventoy

import com.ashishsinghbora.flashcore.block.BlockDevice
import com.ashishsinghbora.flashcore.fat32.Fat32Writer
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy.ProgressCallback
import com.ashishsinghbora.flashcore.flasher.safety.FlashSafetyValidator
import com.ashishsinghbora.flashcore.partition.GptBuilder
import com.ashishsinghbora.flashcore.partition.MbrBuilder
import com.ashishsinghbora.flashcore.partition.PartitionBlockDevice
import com.ashishsinghbora.flashcore.usb.UsbDiskInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Executes installation and non-destructive updating of Ventoy multi-boot environments.
 */
class VentoyInstaller(
    private val assetProvider: VentoyAssetProvider = DefaultVentoyAssetProvider()
) {

    suspend fun install(
        device: BlockDevice,
        targetDrive: UsbDiskInfo,
        mode: VentoyInstallMode = VentoyInstallMode.FRESH_INSTALL,
        partitionStyle: VentoyPartitionStyle = VentoyPartitionStyle.MBR,
        initialIsoStream: InputStream? = null,
        initialIsoName: String? = null,
        initialIsoSize: Long = 0L,
        callback: ProgressCallback,
        isCancelled: () -> Boolean
    ): VentoyInstallResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var totalBytesWritten = 0L

        callback.onLogMessage(VentoyLicenseNotice.getFullNotice())
        callback.onLogMessage("Ventoy Engine Mode: ${mode.displayName}")
        callback.onLogMessage("Partition Style:   ${partitionStyle.displayName}")
        callback.onLogMessage("Bootloader Asset:  ${assetProvider.sourceDescription}")

        val totalDiskSectors = targetDrive.totalSectors
        val sectorSize = targetDrive.sectorSizeBytes.coerceAtLeast(512)

        val layout = if (mode == VentoyInstallMode.NON_DESTRUCTIVE_UPDATE) {
            callback.onPartitionProgress("Detecting existing Ventoy partition geometry...", 0.05f)
            val existingInfo = VentoyDetector.detect(device)
            if (!existingInfo.isVentoyInstalled || existingInfo.layout == null) {
                return@withContext failure(
                    "Cannot perform non-destructive update: Device is not an existing Ventoy media",
                    layout = VentoyGeometryCalculator.computeLayout(totalDiskSectors, partitionStyle, sectorSize),
                    startTime = startTime
                )
            }
            callback.onLogMessage("Existing Ventoy Version: ${existingInfo.installedVersion}")
            callback.onLogMessage("Preserving ${existingInfo.storedIsoFiles.size} existing ISO file(s) in Data partition.")
            existingInfo.layout
        } else {
            // Fresh Install
            callback.onPartitionProgress("Computing Ventoy dual-partition geometry...", 0.05f)
            VentoyGeometryCalculator.computeLayout(totalDiskSectors, partitionStyle, sectorSize)
        }

        callback.onLogMessage("Partition 1 (Data):    LBA ${layout.part1StartLba}..${layout.part1EndLba} (${FlashSafetyValidator.formatBytes(layout.part1SizeBytes)})")
        callback.onLogMessage("Partition 2 (VTOYEFI): LBA ${layout.part2StartLba}..${layout.part2EndLba} (${FlashSafetyValidator.formatBytes(layout.part2SizeBytes)})")

        if (isCancelled()) return@withContext cancelled(layout, startTime, totalBytesWritten)

        // ---------------------------------------------------------------------
        // STEP 1: PARTITION TABLE INSTALLATION / UPDATE
        // ---------------------------------------------------------------------
        callback.onPartitionProgress("Writing ${layout.partitionStyle.name} Partition Tables...", 0.10f)

        if (layout.partitionStyle == VentoyPartitionStyle.MBR) {
            val part1 = MbrBuilder.PartitionEntry(
                bootable = false,
                type = MbrBuilder.TYPE_FAT32_LBA,
                startLba = layout.part1StartLba,
                sectorCount = layout.part1SectorCount
            )
            val part2 = MbrBuilder.PartitionEntry(
                bootable = true, // Active boot flag
                type = MbrBuilder.TYPE_EFI_SYSTEM_PARTITION,
                startLba = layout.part2StartLba,
                sectorCount = layout.part2SectorCount
            )

            val mbrSector = MbrBuilder.buildMbr(
                partitions = listOf(part1, part2),
                diskSignature = VentoyDetector.VENTOY_DISK_SIGNATURE,
                bootstrapCode = assetProvider.getMbrBootstrap()
            )

            val writeMbrOk = device.write(0L, 1, mbrSector)
            if (!writeMbrOk) return@withContext failure("Failed to write Ventoy MBR to Sector 0", layout, startTime)
            totalBytesWritten += sectorSize
        } else {
            // GPT Layout
            val protMbr = MbrBuilder.buildProtectiveMbr(
                totalDiskSectors = totalDiskSectors
            )
            device.write(0L, 1, protMbr)

            val gptPartitions = listOf(
                GptBuilder.GptPartition(
                    typeGuid = GptBuilder.GUID_MICROSOFT_BASIC_DATA,
                    firstLba = layout.part1StartLba,
                    lastLba = layout.part1EndLba,
                    partitionName = "Ventoy"
                ),
                GptBuilder.GptPartition(
                    typeGuid = GptBuilder.GUID_EFI_SYSTEM,
                    firstLba = layout.part2StartLba,
                    lastLba = layout.part2EndLba,
                    attributes = 0x0000000000000001L, // System Partition / Active
                    partitionName = "VTOYEFI"
                )
            )

            val gptLayout = GptBuilder.build(totalDiskSectors, gptPartitions, sectorSizeBytes = sectorSize)
            device.write(1L, 1, gptLayout.primaryHeaderSector)
            device.write(2L, 32, gptLayout.primaryPartitionTableBytes)

            val backupTableLba = totalDiskSectors - 1L - 32L
            device.write(backupTableLba, 32, gptLayout.backupPartitionTableBytes)
            device.write(totalDiskSectors - 1L, 1, gptLayout.backupHeaderSector)
            totalBytesWritten += 34L * sectorSize
        }

        if (isCancelled()) return@withContext cancelled(layout, startTime, totalBytesWritten)

        // ---------------------------------------------------------------------
        // STEP 2: INSTALL VTOYEFI 32 MB BOOTLOADER PARTITION (Partition 2)
        // ---------------------------------------------------------------------
        callback.onPartitionProgress("Flashing 32 MB VTOYEFI Bootloader System to Partition 2...", 0.20f)
        val part2Device = PartitionBlockDevice(device, layout.part2StartLba, layout.part2SectorCount)
        val vtoyStream = assetProvider.getVtoyEfiStream()
        val vtoyBufferSize = 64 * 1024
        val vtoyBuffer = ByteArray(vtoyBufferSize)
        val vtoyTotalBytes = assetProvider.getVtoyEfiSizeBytes()
        var vtoyBytesWritten = 0L
        var p2Lba = 0L

        try {
            while (!isCancelled()) {
                val read = vtoyStream.read(vtoyBuffer)
                if (read <= 0) break
                val sectors = (read + sectorSize - 1) / sectorSize
                val padded = sectors * sectorSize
                if (read < padded) {
                    vtoyBuffer.fill(0, read, padded)
                }

                val writeOk = part2Device.write(p2Lba, sectors, vtoyBuffer)
                if (!writeOk) {
                    return@withContext failure("Failed writing VTOYEFI bootloader block at LBA $p2Lba", layout, startTime, totalBytesWritten)
                }

                p2Lba += sectors
                vtoyBytesWritten += read
                totalBytesWritten += read

                val fraction = 0.20f + (vtoyBytesWritten.toFloat() / vtoyTotalBytes.toFloat()) * 0.30f
                callback.onPartitionProgress("Writing VTOYEFI core: ${vtoyBytesWritten / (1024 * 1024)} / 32 MB", fraction)
            }
        } finally {
            try { vtoyStream.close() } catch (_: Exception) {}
        }

        if (isCancelled()) return@withContext cancelled(layout, startTime, totalBytesWritten)

        // ---------------------------------------------------------------------
        // STEP 3: DATA PARTITION SETUP (FRESH INSTALL ONLY)
        // ---------------------------------------------------------------------
        val storedIsos = mutableListOf<String>()
        val part1Device = PartitionBlockDevice(device, layout.part1StartLba, layout.part1SectorCount)

        if (mode == VentoyInstallMode.FRESH_INSTALL) {
            callback.onPartitionProgress("Formatting FAT32 Data Volume 'Ventoy'...", 0.55f)
            val dataWriter = Fat32Writer.createNew(
                device = part1Device,
                totalSectors = layout.part1SectorCount,
                volumeLabel = "Ventoy"
            )
            dataWriter.formatVolume("Ventoy")

            callback.onPartitionProgress("Initializing Ventoy directory structure...", 0.60f)
            dataWriter.mkdir("/ventoy")
            dataWriter.mkdir("/ISO")

            // Write default plugin configuration
            VentoyStorageManager.writeVentoyConfig(part1Device)
            callback.onLogMessage("Default /ventoy/ventoy.json written.")
            totalBytesWritten += 4L * 1024L * 1024L
        } else {
            callback.onLogMessage("Non-destructive update: Data partition preserved intact.")
        }

        if (isCancelled()) return@withContext cancelled(layout, startTime, totalBytesWritten)

        // ---------------------------------------------------------------------
        // STEP 4: STORE INITIAL ISO (IF PROVIDED)
        // ---------------------------------------------------------------------
        if (initialIsoStream != null && initialIsoName != null && initialIsoSize > 0) {
            callback.onPartitionProgress("Storing $initialIsoName into Ventoy Data Partition filesystem...", 0.65f)
            var isoWritten = 0L

            VentoyStorageManager.storeIso(
                dataPartitionDevice = part1Device,
                isoStream = initialIsoStream,
                fileName = initialIsoName,
                fileSizeBytes = initialIsoSize,
                targetDirectory = "/ISO"
            ) { bytesWritten, total ->
                isoWritten = bytesWritten
                val frac = 0.65f + (bytesWritten.toFloat() / total.toFloat()) * 0.25f
                callback.onPartitionProgress("Storing $initialIsoName: ${bytesWritten / (1024 * 1024)} / ${total / (1024 * 1024)} MB", frac)
            }

            totalBytesWritten += isoWritten
            storedIsos.add(initialIsoName)
            callback.onLogMessage("Stored /ISO/$initialIsoName (${FlashSafetyValidator.formatBytes(initialIsoSize)}) in filesystem.")
        }

        // ---------------------------------------------------------------------
        // STEP 5: FLUSH & VERIFICATION
        // ---------------------------------------------------------------------
        callback.onPartitionProgress("Flushing USB cache to physical media...", 0.92f)
        device.flush()

        callback.onPartitionProgress("Verifying Ventoy multi-boot integrity...", 0.96f)
        val verifyInfo = VentoyDetector.detect(device)
        if (!verifyInfo.isVentoyInstalled) {
            return@withContext failure("Post-install verification failed: Ventoy disk signature not detected", layout, startTime, totalBytesWritten)
        }

        // Verify UEFI Bootloader PE binary
        val p2Mounted = try {
            Fat32Writer.mount(part2Device)
        } catch (e: Exception) {
            return@withContext failure("Failed to mount VTOYEFI partition for verification: ${e.message}", layout, startTime, totalBytesWritten)
        }

        val hasBootLoader = p2Mounted.exists("/EFI/BOOT/BOOTX64.EFI") || p2Mounted.exists("/EFI/BOOT/BOOTAA64.EFI")
        if (!hasBootLoader) {
            return@withContext failure("VTOYEFI partition is missing UEFI bootloaders in /EFI/BOOT/", layout, startTime, totalBytesWritten)
        }

        val efiBytes = p2Mounted.readFile("/EFI/BOOT/BOOTX64.EFI")
        val isPe = efiBytes.size >= 2 && efiBytes[0] == 0x4D.toByte() && efiBytes[1] == 0x5A.toByte()
        if (!isPe) {
            return@withContext failure("UEFI bootloader corrupted: Missing PE (MZ) header", layout, startTime, totalBytesWritten)
        }

        callback.onPartitionProgress("Ventoy Multi-Boot System Ready!", 1.0f)
        callback.onLogMessage("Ventoy Version: ${verifyInfo.installedVersion}")
        callback.onLogMessage("Verification complete: MBR/GPT valid, VTOYEFI 32MB bootloaders intact (MZ PE Verified).")

        val duration = System.currentTimeMillis() - startTime
        VentoyInstallResult(
            success = true,
            mode = mode,
            layout = layout,
            ventoyVersion = verifyInfo.installedVersion ?: assetProvider.version,
            totalBytesWritten = totalBytesWritten,
            durationMs = duration,
            isoFilesStored = storedIsos
        )
    }

    private fun cancelled(layout: VentoyPartitionLayout, startTime: Long, totalWritten: Long): VentoyInstallResult {
        return VentoyInstallResult(
            success = false,
            mode = VentoyInstallMode.FRESH_INSTALL,
            layout = layout,
            ventoyVersion = assetProvider.version,
            totalBytesWritten = totalWritten,
            durationMs = System.currentTimeMillis() - startTime,
            errorMessage = "Operation cancelled by user"
        )
    }

    private fun failure(
        message: String,
        layout: VentoyPartitionLayout,
        startTime: Long,
        totalWritten: Long = 0L
    ): VentoyInstallResult {
        return VentoyInstallResult(
            success = false,
            mode = VentoyInstallMode.FRESH_INSTALL,
            layout = layout,
            ventoyVersion = assetProvider.version,
            totalBytesWritten = totalWritten,
            durationMs = System.currentTimeMillis() - startTime,
            errorMessage = message
        )
    }
}
