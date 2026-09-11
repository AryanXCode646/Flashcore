package com.example.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * UEFI Mixed-Endian GUID Serialization and Well-Known Partition GUIDs conforming to UEFI Spec v2.10.
 *
 * In UEFI/GPT, the first three fields of a UUID are stored Little-Endian, while
 * the last two fields (clock sequence and node) are stored Big-Endian (Network Byte Order):
 *
 * [time_low: 4 bytes LE] [time_mid: 2 bytes LE] [time_hi: 2 bytes LE] [clock_seq: 2 bytes BE] [node: 6 bytes BE]
 */
object GptGuidHelper {

    // Standard RFC 4122 Partition Type GUIDs
    val GUID_EFI_SYSTEM: UUID = UUID.fromString("C12A7328-F81F-11D2-BA4B-00A0C93EC93B")
    val GUID_MICROSOFT_BASIC_DATA: UUID = UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")
    val GUID_MICROSOFT_RESERVED: UUID = UUID.fromString("E3C9E316-0B5C-4DB8-817D-F92DF00215AE")
    val GUID_MICROSOFT_RECOVERY: UUID = UUID.fromString("DE94BBA4-06D1-4D40-A16A-BFD50179D6AC")
    val GUID_LINUX_FILESYSTEM: UUID = UUID.fromString("0FC63DAF-8483-4772-8E79-3D69D8477DE4")
    val GUID_LINUX_SWAP: UUID = UUID.fromString("0657FD6D-A4AB-43C4-84E5-0933C84B4F4F")
    val GUID_LINUX_LVM: UUID = UUID.fromString("E6D6D379-F507-44C2-A23C-238F2A3DF928")
    val GUID_BIOS_BOOT: UUID = UUID.fromString("21686148-6449-6E6F-744E-656564454649")
    val GUID_UNUSED_ENTRY: UUID = UUID(0L, 0L)

    /**
     * Serializes a standard [UUID] into a 16-byte UEFI Mixed-Endian byte array.
     */
    fun toMixedEndianByteArray(uuid: UUID): ByteArray {
        val bytes = ByteArray(16)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        writeMixedEndian(buf, uuid)
        return bytes
    }

    /**
     * Writes a [UUID] to a [ByteBuffer] in UEFI Mixed-Endian format.
     */
    fun writeMixedEndian(buf: ByteBuffer, uuid: UUID) {
        val originalOrder = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)

        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits

        val timeLow = ((msb ushr 32) and 0xFFFFFFFFL).toInt()
        val timeMid = ((msb ushr 16) and 0xFFFFL).toShort()
        val timeHi = (msb and 0xFFFFL).toShort()

        buf.putInt(timeLow)
        buf.putShort(timeMid)
        buf.putShort(timeHi)

        // Write Big-Endian for clock seq and node
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putLong(lsb)

        buf.order(originalOrder)
    }

    /**
     * Parses a 16-byte UEFI Mixed-Endian binary representation into a standard [UUID].
     */
    fun fromMixedEndianByteArray(bytes: ByteArray, offset: Int = 0): UUID {
        require(bytes.size - offset >= 16) { "Buffer too small for 16-byte GUID (${bytes.size - offset} bytes)" }
        val buf = ByteBuffer.wrap(bytes, offset, 16).order(ByteOrder.LITTLE_ENDIAN)
        return readMixedEndian(buf)
    }

    /**
     * Reads a [UUID] from a [ByteBuffer] in UEFI Mixed-Endian format.
     */
    fun readMixedEndian(buf: ByteBuffer): UUID {
        val originalOrder = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)

        val timeLow = buf.int.toLong() and 0xFFFFFFFFL
        val timeMid = buf.short.toLong() and 0xFFFFL
        val timeHi = buf.short.toLong() and 0xFFFFL

        val msb = (timeLow shl 32) or (timeMid shl 16) or timeHi

        buf.order(ByteOrder.BIG_ENDIAN)
        val lsb = buf.long

        buf.order(originalOrder)
        return UUID(msb, lsb)
    }
}
