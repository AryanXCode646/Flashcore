package com.ashishsinghbora.flashcore.scsi

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * SCSI Command Block Wrapper (CBW) according to USB Mass Storage Class Bulk-Only Transport (BOT) Spec v1.0.
 * Total Length: Exactly 31 bytes.
 *
 * Structure:
 * 0..3   : dCBWSignature (0x43425355 - "USBC" in Little Endian)
 * 4..7   : dCBWTag (32-bit unique command identifier)
 * 8..11  : dCBWDataTransferLength (32-bit uint: bytes expected to transfer)
 * 12     : bmCBWFlags (Bit 7: 0 = Data-Out / Host-to-Device, 1 = Data-In / Device-to-Host)
 * 13     : bCBWLUN (Bits 0..3: Logical Unit Number, usually 0)
 * 14     : bCBWCBLength (1..16: length of CDB in bytes)
 * 15..30 : CBWCB (16-byte SCSI Command Descriptor Block)
 */
data class CommandBlockWrapper(
    val tag: Int,
    val dataTransferLength: Int,
    val direction: Direction,
    val lun: Byte = 0,
    val cdbLength: Byte,
    val cdb: ByteArray
) {
    enum class Direction(val flag: Byte) {
        DATA_OUT(0x00.toByte()), // Host to Device
        DATA_IN(0x80.toByte()),  // Device to Host
        NONE(0x00.toByte())
    }

    companion object {
        const val CBW_SIGNATURE = 0x43425355 // "USBC" in Little-Endian
        const val CBW_SIZE = 31
        private val tagGenerator = AtomicInteger(1000)

        fun nextTag(): Int = tagGenerator.incrementAndGet()

        fun create(
            dataTransferLength: Int,
            direction: Direction,
            cdb: ByteArray,
            lun: Byte = 0,
            tag: Int = nextTag()
        ): CommandBlockWrapper {
            require(cdb.size in 1..16) { "CDB size must be between 1 and 16 bytes, was: ${cdb.size}" }
            return CommandBlockWrapper(
                tag = tag,
                dataTransferLength = dataTransferLength,
                direction = direction,
                lun = lun,
                cdbLength = cdb.size.toByte(),
                cdb = cdb
            )
        }
    }

    /**
     * Serializes this CBW into a 31-byte ByteBuffer formatted in Little-Endian.
     */
    fun toByteBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocate(CBW_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CBW_SIGNATURE)
        buffer.putInt(tag)
        buffer.putInt(dataTransferLength)
        buffer.put(direction.flag)
        buffer.put(lun)
        buffer.put(cdbLength)
        
        // Put CDB (padded to 16 bytes)
        buffer.put(cdb)
        val padding = 16 - cdb.size
        for (i in 0 until padding) {
            buffer.put(0.toByte())
        }
        buffer.flip()
        return buffer
    }

    /**
     * Serializes this CBW into a 31-byte ByteArray.
     */
    fun toByteArray(): ByteArray {
        val buf = toByteBuffer()
        val array = ByteArray(CBW_SIZE)
        buf.get(array)
        return array
    }
}
