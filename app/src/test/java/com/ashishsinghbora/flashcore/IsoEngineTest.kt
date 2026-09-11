package com.ashishsinghbora.flashcore

import com.ashishsinghbora.flashcore.iso.BootMechanism
import com.ashishsinghbora.flashcore.iso.ByteArrayIsoSource
import com.ashishsinghbora.flashcore.iso.FirmwareArchitecture
import com.ashishsinghbora.flashcore.iso.IsoFilesystemReader
import com.ashishsinghbora.flashcore.iso.PayloadType
import com.ashishsinghbora.flashcore.iso.RecommendedStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IsoEngineTest {

    private val sectorSize = 2048



    @Test
    fun testUbuntuImageCapabilities() {
        val ubuntuDiskInfo = "Ubuntu 24.04.1 LTS \"Noble Numbat\" - Release amd64 (20240827)\n"
        val image = SyntheticIsoBuilder("Ubuntu 24.04 LTS", isIsohybrid = true, hasElToritoBios = true, hasElToritoEfi = true)
            .addFile("/.disk/info", ubuntuDiskInfo.toByteArray(Charsets.UTF_8))
            .addFile("/casper/vmlinuz", "vmlinuz_kernel_bytes".toByteArray())
            .addFile("/casper/initrd", "initrd_bytes".toByteArray())
            .addFile("/casper/filesystem.squashfs", "squashfs_rootfs".toByteArray())
            .addFile("/EFI/BOOT/BOOTX64.EFI", "UEFI_BOOT_LOADER".toByteArray())
            .build()

        val reader = IsoFilesystemReader(ByteArrayIsoSource(image))
        assertTrue(reader.open())

        val caps = reader.capabilities
        assertNotNull(caps)
        assertEquals(PayloadType.LINUX_LIVE_INSTALLER, caps!!.payloadType)
        assertTrue(caps.isIsohybrid)
        assertTrue(caps.hasElTorito)
        assertTrue(caps.supportedFirmware.contains(FirmwareArchitecture.X86_64))
        assertTrue(caps.supportedFirmware.contains(FirmwareArchitecture.BIOS_X86))
        assertTrue(caps.bootMechanisms.contains(BootMechanism.ISOHYBRID_MBR))
        assertTrue(caps.bootMechanisms.contains(BootMechanism.NATIVE_UEFI_TREE))

        // Dynamic OS extraction: accurately extracted from /.disk/info without hardcoding
        assertNotNull(caps.osName)
        assertTrue("osName should contain Ubuntu", caps.osName!!.contains("Ubuntu"))
        assertEquals("UBUNTU", caps.distroIdentifier)
        assertEquals(RecommendedStrategy.RAW_DD, caps.recommendedStrategy)
    }

    @Test
    fun testDebianImageCapabilities() {
        val debianDiskInfo = "Debian GNU/Linux 12.5.0 \"Bookworm\" - Official amd64 NETINST with firmware\n"
        val image = SyntheticIsoBuilder("Debian 12 Bookworm", isIsohybrid = true, hasElToritoBios = true, hasElToritoEfi = true)
            .addFile("/.disk/info", debianDiskInfo.toByteArray(Charsets.UTF_8))
            .addFile("/install.amd/vmlinuz", "kernel_bytes".toByteArray())
            .addFile("/install.amd/initrd.gz", "initrd_bytes".toByteArray())
            .addFile("/EFI/BOOT/BOOTX64.EFI", "UEFI_BOOT_LOADER".toByteArray())
            .build()

        val reader = IsoFilesystemReader(ByteArrayIsoSource(image))
        assertTrue(reader.open())

        val caps = reader.capabilities
        assertNotNull(caps)
        assertEquals(PayloadType.LINUX_LIVE_INSTALLER, caps!!.payloadType)
        assertTrue(caps.isIsohybrid)
        assertTrue(caps.osName!!.contains("Debian"))
        assertEquals("DEBIAN", caps.distroIdentifier)
        assertEquals(RecommendedStrategy.RAW_DD, caps.recommendedStrategy)
    }

    @Test
    fun testArchLinuxImageCapabilities() {
        val grubCfg = "menuentry 'Arch Linux install medium (x86_64)' --class arch {\n linux /arch/boot/x86_64/vmlinuz-linux\n}\n"
        val image = SyntheticIsoBuilder("ARCH_202609", isIsohybrid = true, hasElToritoBios = true, hasElToritoEfi = true)
            .addFile("/boot/grub/grub.cfg", grubCfg.toByteArray(Charsets.UTF_8))
            .addFile("/arch/boot/x86_64/vmlinuz-linux", "arch_kernel".toByteArray())
            .addFile("/arch/boot/x86_64/initramfs-linux.img", "arch_initrd".toByteArray())
            .addFile("/arch/x86_64/airootfs.sfs", "arch_rootfs".toByteArray())
            .addFile("/EFI/BOOT/BOOTX64.EFI", "UEFI_BOOT_LOADER".toByteArray())
            .build()

        val reader = IsoFilesystemReader(ByteArrayIsoSource(image))
        assertTrue(reader.open())

        val caps = reader.capabilities
        assertNotNull(caps)
        assertEquals(PayloadType.LINUX_LIVE_INSTALLER, caps!!.payloadType)
        assertTrue(caps.isIsohybrid)
        assertNotNull(caps.osName)
        assertTrue("osName should contain Arch Linux from grub.cfg", caps.osName!!.contains("Arch Linux"))
        assertEquals(RecommendedStrategy.RAW_DD, caps.recommendedStrategy)
    }

    @Test
    fun testFedoraImageCapabilities() {
        val diskDefines = "#DISKDEF\nNAME Fedora\nVERSION 40\nTYPE Live\n"
        val image = SyntheticIsoBuilder("Fedora-WS-Live-40", isIsohybrid = true, hasElToritoBios = true, hasElToritoEfi = true)
            .addFile("/README.diskdefines", diskDefines.toByteArray(Charsets.UTF_8))
            .addFile("/images/pxeboot/vmlinuz", "fedora_kernel".toByteArray())
            .addFile("/LiveOS/squashfs.img", "fedora_squashfs".toByteArray())
            .addFile("/EFI/BOOT/BOOTX64.EFI", "UEFI_BOOT_LOADER".toByteArray())
            .build()

        val reader = IsoFilesystemReader(ByteArrayIsoSource(image))
        assertTrue(reader.open())

        val caps = reader.capabilities
        assertNotNull(caps)
        assertEquals(PayloadType.LINUX_LIVE_INSTALLER, caps!!.payloadType)
        assertTrue(caps.isIsohybrid)
        assertEquals("Fedora", caps.osName)
        assertEquals("40", caps.osVersion)
        assertEquals("FEDORA", caps.distroIdentifier)
        assertEquals(RecommendedStrategy.RAW_DD, caps.recommendedStrategy)
    }

    @Test
    fun testWindowsImageCapabilitiesAndWimSplitDetection() {
        val image = SyntheticIsoBuilder("CCCOMA_X64FRE_EN-US_DV9", isIsohybrid = false, hasElToritoBios = false, hasElToritoEfi = true)
            .addFile("/bootmgr", "bootmgr_binary".toByteArray())
            .addFile("/bootmgr.efi", "bootmgr_efi_binary".toByteArray())
            .addFile("/boot/bcd", "bcd_registry_hive".toByteArray())
            .addFile("/EFI/BOOT/BOOTX64.EFI", "UEFI_BOOT_LOADER".toByteArray())
            .addFile("/sources/boot.wim", "boot_wim_bytes".toByteArray(), simulatedSize = 400L * 1024L * 1024L)
            // install.wim with size 5.5 GB (> 4 GB FAT32 limit)
            .addFile("/sources/install.wim", "install_wim_bytes".toByteArray(), simulatedSize = 5500000000L)
            .build()

        val reader = IsoFilesystemReader(ByteArrayIsoSource(image))
        assertTrue(reader.open())

        val caps = reader.capabilities
        assertNotNull(caps)
        assertEquals(PayloadType.WINDOWS_SETUP, caps!!.payloadType)
        assertTrue("Windows ISO should not be detected as isohybrid", !caps.isIsohybrid)
        assertTrue(caps.hasElTorito)
        assertTrue(caps.supportedFirmware.contains(FirmwareArchitecture.X86_64))
        assertTrue(caps.hasInstallWim)
        assertEquals(5500000000L, caps.installWimSize)
        assertTrue("Large install.wim must trigger requiresWimSplit = true", caps.requiresWimSplit)
        assertEquals(RecommendedStrategy.WINDOWS_UEFI, caps.recommendedStrategy)
    }
}
