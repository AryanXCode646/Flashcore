package com.example.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import com.example.block.BlockDevice
import com.example.block.DeviceCapacity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Android Production Engineering Performance & Benchmark Suite.
 *
 * Benchmarks USB and block device streaming across production scale workloads:
 * - 1 GB (1,073,741,824 bytes)
 * - 4 GB (4,294,967,296 bytes)
 * - 8 GB (8,589,934,592 bytes)
 * - 16 GB (17,179,869,184 bytes)
 * - 32 GB (34,359,738,368 bytes)
 * - 64 GB (68,719,476,736 bytes)
 *
 * Records:
 * - Throughput (MB/s average, peak, min)
 * - Process CPU % (user + kernel time via Process.getElapsedCpuTime)
 * - Memory footprint (JVM heap used, native heap allocated, direct buffer memory)
 * - ART Garbage Collection (GC count, GC total pause time)
 * - USB & I/O errors (retries, stalls, status failures)
 * - Thermal status & Temperature (ThermalHeadroom, CurrentThermalStatus, Battery temp)
 */
object FlashPerformanceBenchmark {

    const val SIZE_1_GB = 1L * 1024L * 1024L * 1024L
    const val SIZE_4_GB = 4L * 1024L * 1024L * 1024L
    const val SIZE_8_GB = 8L * 1024L * 1024L * 1024L
    const val SIZE_16_GB = 16L * 1024L * 1024L * 1024L
    const val SIZE_32_GB = 32L * 1024L * 1024L * 1024L
    const val SIZE_64_GB = 64L * 1024L * 1024L * 1024L

    data class BenchmarkMetrics(
        val workloadSizeBytes: Long,
        val formattedSize: String,
        val durationMs: Long,
        val averageMBps: Double,
        val peakMBps: Double,
        val minMBps: Double,
        val cpuUtilizationPercent: Double,
        val ramHeapUsedMb: Double,
        val ramNativeAllocatedMb: Double,
        val gcCount: Long,
        val gcTimeMs: Long,
        val usbErrorsCount: Long,
        val thermalStatus: String,
        val thermalHeadroom: Float?,
        val temperatureCelsius: Float?
    ) {
        fun toMarkdownRow(): String {
            val therm = temperatureCelsius?.let { "%.1f°C".format(it) } ?: thermalStatus
            return "| $formattedSize | ${"%.2f".format(averageMBps)} MB/s | ${"%.1f".format(cpuUtilizationPercent)}% | ${"%.1f".format(ramHeapUsedMb)} MB | $gcCount ($gcTimeMs ms) | $usbErrorsCount | $therm |"
        }
    }

    data class BenchmarkReport(
        val timestamp: Long,
        val deviceDescription: String,
        val metrics: List<BenchmarkMetrics>
    ) {
        fun toMarkdownTable(): String {
            return buildString {
                appendLine("### 📊 FlashCore Performance Benchmark Report")
                appendLine("- **Device:** $deviceDescription")
                appendLine("- **Timestamp:** $timestamp")
                appendLine()
                appendLine("| Workload | Throughput | CPU % | RAM (Heap) | GC Invocations | USB Errors | Thermal |")
                appendLine("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |")
                for (m in metrics) {
                    appendLine(m.toMarkdownRow())
                }
            }
        }
    }

    /**
     * Executes a throughput and resource benchmark for the specified [workloadBytes].
     *
     * Streams synthetic I/O blocks into [targetDevice] using [blockSizeBytes].
     */
    suspend fun runBenchmark(
        context: Context?,
        targetDevice: BlockDevice,
        workloadBytes: Long,
        blockSizeBytes: Int = 1024 * 1024, // 1 MB
        onProgress: ((bytesTransferred: Long, total: Long, currentSpeedMBps: Double) -> Unit)? = null
    ): BenchmarkMetrics = withContext(Dispatchers.IO) {
        val sectorSize = targetDevice.sectorSizeBytes.coerceAtLeast(512)
        val sectorsPerBlock = blockSizeBytes / sectorSize
        val totalBlocks = (workloadBytes + blockSizeBytes - 1) / blockSizeBytes

        // Snapshot initial resources
        val startCpuTime = try { Process.getElapsedCpuTime() } catch (_: Exception) { 0L }
        val startGcCount = readArtStat("art.gc.gc-count")
        val startGcTime = readArtStat("art.gc.gc-time-ms")
        var usbErrors = 0L

        // Buffer preparation
        val buffer = ByteArray(blockSizeBytes) { (it and 0xFF).toByte() }

        var transferred = 0L
        var lba = 0L
        var peakSpeed = 0.0
        var minSpeed = Double.MAX_VALUE
        var lastIntervalBytes = 0L

        val startTime = System.currentTimeMillis()
        var lastIntervalTime = startTime

        for (block in 0 until totalBlocks) {
            currentCoroutineContext().ensureActive()

            val bytesToWrite = minOf(blockSizeBytes.toLong(), workloadBytes - transferred).toInt()
            val sectorsToWrite = (bytesToWrite + sectorSize - 1) / sectorSize

            val writeSuccess = try {
                targetDevice.write(lba, sectorsToWrite, buffer)
            } catch (e: IOException) {
                usbErrors++
                false
            }

            if (!writeSuccess) {
                usbErrors++
            }

            transferred += bytesToWrite
            lba += sectorsToWrite

            val now = System.currentTimeMillis()
            val intervalElapsed = now - lastIntervalTime
            if (intervalElapsed >= 250 || transferred >= workloadBytes) {
                val intervalBytes = transferred - lastIntervalBytes
                val intervalSpeed = (intervalBytes.toDouble() / (1024.0 * 1024.0)) / (intervalElapsed / 1000.0).coerceAtLeast(0.001)

                if (intervalSpeed > peakSpeed) peakSpeed = intervalSpeed
                if (intervalSpeed < minSpeed && intervalSpeed > 0.0) minSpeed = intervalSpeed

                onProgress?.invoke(transferred, workloadBytes, intervalSpeed)
                lastIntervalBytes = transferred
                lastIntervalTime = now
            }
        }

        try { targetDevice.flush() } catch (_: Exception) { usbErrors++ }

        val endTime = System.currentTimeMillis()
        val totalDurationMs = (endTime - startTime).coerceAtLeast(1L)
        val averageMBps = (transferred.toDouble() / (1024.0 * 1024.0)) / (totalDurationMs / 1000.0)

        // Snapshot final resources
        val endCpuTime = try { Process.getElapsedCpuTime() } catch (_: Exception) { 0L }
        val cpuUsagePercent = if (totalDurationMs > 0 && endCpuTime >= startCpuTime) {
            val cpuDelta = endCpuTime - startCpuTime
            ((cpuDelta.toDouble() / totalDurationMs.toDouble()) * 100.0).coerceIn(0.0, 800.0)
        } else {
            0.0
        }

        val heapUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).toDouble() / (1024.0 * 1024.0)
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize().toDouble() / (1024.0 * 1024.0)

        val endGcCount = readArtStat("art.gc.gc-count")
        val endGcTime = readArtStat("art.gc.gc-time-ms")

        val gcInvocations = (endGcCount - startGcCount).coerceAtLeast(0L)
        val gcDurationMs = (endGcTime - startGcTime).coerceAtLeast(0L)

        // Thermal telemetry
        val thermalTelemetry = queryThermalStatus(context)

        BenchmarkMetrics(
            workloadSizeBytes = workloadBytes,
            formattedSize = formatBytes(workloadBytes),
            durationMs = totalDurationMs,
            averageMBps = averageMBps,
            peakMBps = if (peakSpeed > 0.0) peakSpeed else averageMBps,
            minMBps = if (minSpeed != Double.MAX_VALUE) minSpeed else averageMBps,
            cpuUtilizationPercent = cpuUsagePercent,
            ramHeapUsedMb = heapUsedMb,
            ramNativeAllocatedMb = nativeHeapMb,
            gcCount = gcInvocations,
            gcTimeMs = gcDurationMs,
            usbErrorsCount = usbErrors,
            thermalStatus = thermalTelemetry.thermalStatus,
            thermalHeadroom = thermalTelemetry.thermalHeadroom,
            temperatureCelsius = thermalTelemetry.batteryTempCelsius
        )
    }

    /**
     * Executes the standard production benchmark suite (1GB, 4GB, 8GB, 16GB, 32GB, 64GB).
     */
    suspend fun runFullSuite(
        context: Context?,
        targetDevice: BlockDevice,
        deviceDescription: String = "Storage Device",
        onWorkloadProgress: ((metrics: BenchmarkMetrics) -> Unit)? = null
    ): BenchmarkReport {
        val workloads = listOf(SIZE_1_GB, SIZE_4_GB, SIZE_8_GB, SIZE_16_GB, SIZE_32_GB, SIZE_64_GB)
        val results = mutableListOf<BenchmarkMetrics>()

        for (size in workloads) {
            val metrics = runBenchmark(context, targetDevice, size)
            results.add(metrics)
            onWorkloadProgress?.invoke(metrics)
        }

        return BenchmarkReport(
            timestamp = System.currentTimeMillis(),
            deviceDescription = deviceDescription,
            metrics = results
        )
    }

    private data class ThermalData(
        val thermalStatus: String,
        val thermalHeadroom: Float?,
        val batteryTempCelsius: Float?
    )

    private fun queryThermalStatus(context: Context?): ThermalData {
        var statusStr = "NORMAL"
        var headroom: Float? = null
        var batteryTemp: Float? = null

        if (context != null) {
            // PowerManager Thermal Status
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val status = powerManager?.currentThermalStatus ?: 0
                statusStr = when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> "NONE"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "STATUS_$status"
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    headroom = powerManager?.getThermalHeadroom(30)
                }
            }

            // Battery Temperature
            try {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                if (tempTenths > 0) {
                    batteryTemp = tempTenths / 10.0f
                }
            } catch (_: Exception) {}
        }

        return ThermalData(statusStr, headroom, batteryTemp)
    }

    private fun readArtStat(key: String): Long {
        return try {
            Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) "%.0f GB".format(gb) else "%.0f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
    }
}

/**
 * Synthetic sink block device for benchmarking high-capacity workloads (e.g. 16 GB, 32 GB, 64 GB)
 * without exhausting physical RAM or host flash storage.
 */
class SyntheticBenchmarkSinkDevice(
    val totalSectors: Long = 134_217_728L, // 64 GB @ 512B
    override val sectorSizeBytes: Int = 512,
    override val isConnected: Boolean = true
) : BlockDevice {

    var totalBytesWritten: Long = 0L
        private set

    override suspend fun capacity(): DeviceCapacity = DeviceCapacity(totalSectors, sectorSizeBytes)

    override suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int): Boolean {
        dest.fill(0, offset, offset + blockCount * sectorSizeBytes)
        return true
    }

    override suspend fun write(lba: Long, blockCount: Int, src: ByteArray, offset: Int): Boolean {
        totalBytesWritten += blockCount * sectorSizeBytes
        return true
    }

    override suspend fun writeDirectBuffer(
        lba: Long,
        blockCount: Int,
        directBuffer: ByteBuffer,
        offset: Int,
        length: Int
    ): Boolean {
        totalBytesWritten += length
        return true
    }

    override suspend fun flush(): Boolean = true

    override fun close() {}
}
