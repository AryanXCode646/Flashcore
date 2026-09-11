package com.example.partition

import com.example.block.BlockDevice
import java.util.UUID

/**
 * Representation of an individual disk partition.
 */
data class Partition(
    val index: Int,
    val firstLba: Long,
    val lastLba: Long,
    val name: String,
    val typeGuid: UUID? = null,
    val uniqueGuid: UUID? = null,
    val mbrType: Byte? = null,
    val bootable: Boolean = false,
    val attributes: Long = 0L
) {
    val sectorCount: Long get() = (lastLba - firstLba + 1L).coerceAtLeast(0L)

    fun sizeBytes(sectorSizeBytes: Int = 512): Long = sectorCount * sectorSizeBytes.toLong()

    /**
     * Exposes this partition as an isolated, bounds-checked [BlockDevice].
     */
    fun asBlockDevice(parent: BlockDevice): BlockDevice = PartitionBlockDevice(parent, this)
}

/**
 * Result of a partition table or geometry validation.
 */
data class PartitionValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    companion object {
        fun valid(warnings: List<String> = emptyList()) = PartitionValidationResult(
            isValid = true,
            errors = emptyList(),
            warnings = warnings
        )

        fun invalid(errors: List<String>, warnings: List<String> = emptyList()) = PartitionValidationResult(
            isValid = false,
            errors = errors,
            warnings = warnings
        )
    }
}

enum class PartitionTableType {
    MBR,
    GPT
}

/**
 * Common abstraction for disk partition tables.
 */
sealed interface PartitionTable {
    val type: PartitionTableType
    val partitions: List<Partition>
    val totalDiskSectors: Long
    val sectorSizeBytes: Int

    /**
     * Validates partition boundaries, overlapping ranges, alignment, and table signatures.
     */
    fun validate(): PartitionValidationResult

    /**
     * Serializes and writes this partition table to the target [BlockDevice].
     */
    suspend fun writeTo(device: BlockDevice): Boolean
}

/**
 * Master Boot Record (MBR) Partition Table.
 */
data class MbrPartitionTable(
    override val partitions: List<Partition>,
    override val totalDiskSectors: Long,
    override val sectorSizeBytes: Int = 512,
    val diskSignature: Int = 0x5A4C3E21,
    val bootstrapCode: ByteArray? = null,
    val isProtective: Boolean = false
) : PartitionTable {
    override val type: PartitionTableType get() = PartitionTableType.MBR

    override fun validate(): PartitionValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (totalDiskSectors <= 0) {
            errors.add("Total disk sectors must be positive ($totalDiskSectors)")
        }

        if (partitions.size > 4) {
            errors.add("MBR supports at most 4 primary partitions (found ${partitions.size})")
        }

        var bootableCount = 0
        val sorted = partitions.sortedBy { it.firstLba }

        for (i in sorted.indices) {
            val p = sorted[i]

            if (p.firstLba < 1L) {
                errors.add("Partition ${p.index} starts at LBA ${p.firstLba} which overlaps MBR Sector 0")
            }

            if (p.lastLba < p.firstLba) {
                errors.add("Partition ${p.index} has invalid range [${p.firstLba}..${p.lastLba}]")
            }

            if (p.lastLba >= totalDiskSectors) {
                errors.add("Partition ${p.index} extends to LBA ${p.lastLba} which exceeds disk sectors ($totalDiskSectors)")
            }

            if (p.bootable) {
                bootableCount++
            }

            // Check alignment
            if (!PartitionAlignment.isAligned(p.firstLba)) {
                warnings.add("Partition ${p.index} starting at LBA ${p.firstLba} is not 1MB aligned")
            }

            // Check overlap
            if (i > 0) {
                val prev = sorted[i - 1]
                if (p.firstLba <= prev.lastLba) {
                    errors.add("Partition ${p.index} [${p.firstLba}..${p.lastLba}] overlaps Partition ${prev.index} [${prev.firstLba}..${prev.lastLba}]")
                }
            }
        }

        if (bootableCount > 1) {
            warnings.add("Multiple active boot partitions ($bootableCount) in MBR table")
        }

        return if (errors.isEmpty()) PartitionValidationResult.valid(warnings) else PartitionValidationResult.invalid(errors, warnings)
    }

    override suspend fun writeTo(device: BlockDevice): Boolean {
        val validation = validate()
        if (!validation.isValid) {
            throw IllegalArgumentException("Cannot write invalid MBR partition table: ${validation.errors.joinToString()}")
        }
        val mbrBytes = PartitionEngine.buildMbrBytes(this)
        return device.write(0L, 1, mbrBytes)
    }
}

/**
 * GUID Partition Table (GPT).
 */
data class GptPartitionTable(
    override val partitions: List<Partition>,
    override val totalDiskSectors: Long,
    override val sectorSizeBytes: Int = 512,
    val diskGuid: UUID = UUID.randomUUID(),
    val firstUsableLba: Long = 34L,
    val lastUsableLba: Long = totalDiskSectors - 34L,
    val partitionEntryCount: Int = 128,
    val partitionEntrySizeBytes: Int = 128
) : PartitionTable {
    override val type: PartitionTableType get() = PartitionTableType.GPT

    override fun validate(): PartitionValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val tableSectors = (partitionEntryCount * partitionEntrySizeBytes + sectorSizeBytes - 1) / sectorSizeBytes
        val minFirstUsable = 2L + tableSectors
        val maxLastUsable = totalDiskSectors - 2L - tableSectors

        if (totalDiskSectors < (minFirstUsable + tableSectors + 2L)) {
            errors.add("Total disk sectors ($totalDiskSectors) too small for valid GPT layout")
        }

        if (firstUsableLba < minFirstUsable) {
            errors.add("firstUsableLba ($firstUsableLba) overlaps primary partition table (min $minFirstUsable)")
        }

        if (lastUsableLba > maxLastUsable) {
            errors.add("lastUsableLba ($lastUsableLba) overlaps backup partition table (max $maxLastUsable)")
        }

        if (firstUsableLba > lastUsableLba) {
            errors.add("firstUsableLba ($firstUsableLba) cannot be greater than lastUsableLba ($lastUsableLba)")
        }

        val sorted = partitions.sortedBy { it.firstLba }

        for (i in sorted.indices) {
            val p = sorted[i]

            if (p.firstLba < firstUsableLba) {
                errors.add("Partition ${p.index} [${p.firstLba}] starts before firstUsableLba ($firstUsableLba)")
            }

            if (p.lastLba > lastUsableLba) {
                errors.add("Partition ${p.index} [${p.lastLba}] extends past lastUsableLba ($lastUsableLba)")
            }

            if (p.lastLba < p.firstLba) {
                errors.add("Partition ${p.index} has invalid negative length [${p.firstLba}..${p.lastLba}]")
            }

            if (p.typeGuid == null || p.typeGuid == GptGuidHelper.GUID_UNUSED_ENTRY) {
                errors.add("Partition ${p.index} must have a valid non-zero type GUID")
            }

            // Check alignment
            if (!PartitionAlignment.isAligned(p.firstLba)) {
                warnings.add("Partition ${p.index} (${p.name}) starting at LBA ${p.firstLba} is not 1MB aligned")
            }

            // Check overlap
            if (i > 0) {
                val prev = sorted[i - 1]
                if (p.firstLba <= prev.lastLba) {
                    errors.add("Partition ${p.index} [${p.firstLba}..${p.lastLba}] overlaps Partition ${prev.index} [${prev.firstLba}..${prev.lastLba}]")
                }
            }
        }

        return if (errors.isEmpty()) PartitionValidationResult.valid(warnings) else PartitionValidationResult.invalid(errors, warnings)
    }

    override suspend fun writeTo(device: BlockDevice): Boolean {
        val validation = validate()
        if (!validation.isValid) {
            throw IllegalArgumentException("Cannot write invalid GPT partition table: ${validation.errors.joinToString()}")
        }
        return PartitionEngine.writeGptToDevice(this, device)
    }
}
