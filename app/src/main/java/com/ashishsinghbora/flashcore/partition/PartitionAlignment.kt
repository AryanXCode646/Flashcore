package com.ashishsinghbora.flashcore.partition

/**
 * Alignment utilities for partition geometry.
 *
 * Modern storage drives (Advanced Format 4Kn/512e and NAND flash SSDs/USBs)
 * require partition boundaries to align to physical erase block or page boundaries
 * (typically 1 MB = 2,048 sectors of 512 bytes or 4,096 bytes) to avoid
 * read-modify-write performance penalties and write amplification.
 */
object PartitionAlignment {

    /** Default 1 MB alignment in 512-byte sectors (2,048 sectors) */
    const val DEFAULT_ALIGNMENT_SECTORS: Long = 2048L

    /** 4 KB alignment in 512-byte sectors (8 sectors) */
    const val ALIGNMENT_4K_SECTORS: Long = 8L

    /**
     * Aligns [lba] upwards to the nearest multiple of [alignmentSectors].
     */
    fun alignUp(lba: Long, alignmentSectors: Long = DEFAULT_ALIGNMENT_SECTORS): Long {
        require(alignmentSectors > 0) { "Alignment sectors must be positive: $alignmentSectors" }
        val remainder = lba % alignmentSectors
        return if (remainder == 0L) lba else lba + (alignmentSectors - remainder)
    }

    /**
     * Aligns [lba] downwards to the nearest multiple of [alignmentSectors].
     */
    fun alignDown(lba: Long, alignmentSectors: Long = DEFAULT_ALIGNMENT_SECTORS): Long {
        require(alignmentSectors > 0) { "Alignment sectors must be positive: $alignmentSectors" }
        return lba - (lba % alignmentSectors)
    }

    /**
     * Checks if [lba] is aligned to [alignmentSectors].
     */
    fun isAligned(lba: Long, alignmentSectors: Long = DEFAULT_ALIGNMENT_SECTORS): Boolean {
        require(alignmentSectors > 0) { "Alignment sectors must be positive: $alignmentSectors" }
        return (lba % alignmentSectors) == 0L
    }
}
