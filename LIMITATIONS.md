# ⚠️ FlashCore — Known Technical Limitations & Reality Audit

This document provides a transparent, engineering-level breakdown of the current technical limitations, architectural boundaries, and hardware considerations in FlashCore.

---

## 📑 Table of Contents
1. [Flashing Strategies & Filesystem Pipeline](#1-flashing-strategies--filesystem-pipeline)
2. [SCSI & USB Mass Storage (BOT) Layer](#2-scsi--usb-mass-storage-bot-layer)
3. [Memory Pipeline & Buffer Architecture](#3-memory-pipeline--buffer-architecture)
4. [Data Integrity & Verification](#4-data-integrity--verification)
5. [ISO Parsing & WIM Handling](#5-iso-parsing--wim-handling)
6. [Android Platform & OS Constraints](#6-android-platform--os-constraints)
7. [Feature Maturity Classification](#7-feature-maturity-classification)

---

## 1. Flashing Strategies & Filesystem Pipeline

### 🐧 Linux Hybrid Strategy (`LinuxRawDdStrategy`)
- **Status:** 🟢 **Production-Grade**
- **How it works:** Directly streams source image bytes to Sector 0 (`dd` equivalent) over SCSI `WRITE_10`, issues `SYNCHRONIZE_CACHE_10` to flush drive caches, and executes a full bit-for-bit target read-back pass via `FlashVerifier`.
- **Limitation:** Works exclusively for hybrid ISOs (Ubuntu, Arch, Fedora, Debian, Pop!_OS) that already embed MBR/GPT partition tables and El Torito boot structures at Sector 0. It does not construct or alter filesystem structures. Non-hybrid legacy optical-only ISOs will not boot via raw sector streaming.

### 🪟 Windows UEFI Strategy (`WindowsUefiStrategy`)
- **Status:** 🟡 **Engine Implemented & Verified in Test Suite; Physical Firmware Testing Ongoing**
- **What is Implemented:**
  1. Full recursive directory traversal via `IsoFilesystemReader` (ISO 9660 & Joliet extensions).
  2. GPT partition generation (Protective MBR + Primary/Backup GPT with CRC32).
  3. Real FAT32 formatting via `Fat32Writer` (VBR, FSInfo, FAT1/FAT2, directory tables, cluster allocation).
  4. File-by-file extraction into target FAT32 volume.
  5. On-the-fly chunking of `install.wim` into `< 4 GB` `.swm` chunks (`WimChunker`) to satisfy FAT32 file size limits.
  6. Fallback UEFI bootloader (`/efi/boot/bootx64.efi`) and BCD hive provisioning.
  7. Binary PE `MZ` and registry `regf` header verification.
- **Physical Device Reality & Validation Constraint:**
  While the pipeline is 100% verified against abstract `BlockDevice` unit tests, booting Windows installers on varied physical PC motherboards depends on UEFI firmware quirks:
  - Some older UEFI firmware implementations do not recognize split `.swm` files without specific BCD registry modifications.
  - Secure Boot keys on certain OEM motherboards require strictly signed Microsoft bootloaders.
  - Physical testing across diverse motherboards is an ongoing community effort.

### 🧰 Ventoy Multi-Boot Integration (`VentoyStrategy` & `VentoyInstaller`)
- **Status:** 🟡 **Engine Implemented & Verified in Test Suite; Physical Firmware Testing Ongoing**
- **Architecture & What Flashcore Does:**
  1. **Ventoy Installation / Update:** Implements both `FRESH_INSTALL` (full dual-partition formatting) and `NON_DESTRUCTIVE_UPDATE` (refreshes Sector 0 MBR/GPT and Partition 2 VTOYEFI while preserving Partition 1 and all existing user ISO files intact).
  2. **Partition Layout:** Strictly complies with the Ventoy specification — Partition 1 (Data) aligned to 1 MiB (LBA 2048), Partition 2 (VTOYEFI) exactly 32 MiB (65,536 sectors @ 512B) at disk end with active boot flags, and 34-sector tail reservation for GPT mode.
  3. **Bootloader Assets (`VentoyAssetProvider`):** Modular asset provider architecture. Includes a built-in offline generator providing compliant 32 MiB FAT filesystem structures with genuine PE `MZ` headers (`/EFI/BOOT/BOOTX64.EFI`, etc.), and supports importing official pre-compiled upstream `ventoy.disk.img` images.
  4. **Data Partition Filesystem:** Formats Partition 1 with FAT32 (label "Ventoy"), initializes `/ventoy/` directory with `ventoy.json` plugin configurations, and `/ISO/` directory.
  5. **Filesystem-Level ISO Storage:** Writes OS images as regular files into `/ISO/<name>.iso` within the FAT32 filesystem rather than raw sector writes, enabling dynamic Ventoy menu discovery.
- **Physical Validation Constraint:**
  Booting across varied legacy BIOS CSM and modern UEFI hardware depends on firmware quirks. Physical PC booting across various motherboards requires ongoing real-hardware community validation before designating as battle-tested production firmware.
- **Third-Party Licensing Compliance:**
  Ventoy is an open-source project by longpanda licensed under GPL-3.0. GRUB2 is licensed under GPL-3.0. Flashcore complies with GPL-3.0 by releasing under GPL-3.0, maintaining author attribution, referencing upstream source code (`https://github.com/ventoy/Ventoy`), and clearly disclaiming official affiliation.

---

## 2. SCSI & USB Mass Storage (BOT) Layer

### 32-Bit LBA Addressing Limit (`WRITE_10` / `READ_10`)
- FlashCore currently relies on SCSI `WRITE_10` (Opcode `0x2A`) and `READ_10` (Opcode `0x28`).
- Both commands accept a 32-bit Logical Block Address (LBA).
- With 512-byte logical sectors, the maximum addressable drive offset is:
  $$\text{Max Capacity} = 2^{32} \times 512 \text{ bytes} = 2,199,023,255,552 \text{ bytes} \approx 2.0 \text{ TiB}$$
- Target drives larger than 2 TiB require SCSI `WRITE_16` (Opcode `0x8A`) and `READ_16` (Opcode `0x88`) support. While `READ_CAPACITY_16` is implemented to detect >2 TiB capacities, write and read operations are currently clamped to 32-bit LBAs.

### Short Bulk Transfer Handling
- In Android's `UsbDeviceConnection.bulkTransfer()`, transfers can occasionally be partial depending on hardware FIFOs and host controller capabilities.
- The current driver verifies that the return code is non-negative (`sent >= 0`), but does not loop until all requested bytes in a chunk are transferred if an incomplete transfer occurs.

### Controller Quirks & BOT Stall Recovery
- USB flash drives (controllers from Phison, SMI, Alcor, Innostor, Realtek) vary drastically in firmware quality:
  - Some controllers fail to recover cleanly from bulk endpoint stalls via standard `CLEAR_FEATURE(ENDPOINT_HALT)`.
  - Some drives silently drop writes or reset the USB bus during sustained high-queue writes.
- FlashCore includes basic clear-halt and Bulk-Only Mass Storage Reset (BOMSR) routines, but has not yet undergone rigorous hardware matrix testing across diverse physical USB controllers.

---

## 3. Memory Pipeline & Buffer Architecture

### Buffer Pipeline Reality
- While `DirectRingBuffer` uses off-heap `ByteBuffer.allocateDirect()` to prevent JVM GC pauses and decouple reading from writing, Android's public `UsbDeviceConnection.bulkTransfer()` method does not accept a direct memory pointer or `ByteBuffer` offset/length directly in public APIs without copying.
- `UsbMassStorageDriver.writeDirectBuffer()` copies bytes from the direct buffer into a temporary heap-allocated `ByteArray`:
  ```kotlin
  val tempArray = ByteArray(length)
  directBuffer.get(tempArray, 0, length)
  conn.bulkTransfer(outEp, tempArray, length, timeoutMs)
  ```
- **Consequence:** An intermediate copy (`DirectByteBuffer` -> `ByteArray` -> USB kernel driver) occurs on every write block. The ring buffer still provides effective producer-consumer rate decoupling, but it is not a true zero-copy native pointer pipeline.

### Synchronization Model
- `DirectRingBuffer` uses Java standard `ReentrantLock` and `Condition` variables for synchronization. It is thread-safe and non-allocating during loop execution, but not lock-free.

---

## 4. Data Integrity & Verification

### Target Read-Back Verification Overhead
- `FlashVerifier` performs a full bit-for-bit physical read-back pass using SCSI `READ_10` after issuing `SYNCHRONIZE_CACHE_10`.
- **Trade-Off:** Reading back every sector from a physical USB flash drive over USB 2.0 / USB 3.0 OTG doubles the total operation time (e.g. a 4 GB write at 15 MB/s takes ~4.5 minutes to write and ~4.5 minutes to verify).
- While verification can be disabled by users who prioritize speed over safety, skipping verification leaves potential flash write errors or fake capacity drives undetected.

---

## 5. ISO Parsing & WIM Handling

### ISO 9660 & Joliet Support
- `IsoFilesystemReader` supports standard ISO 9660 Level 1/2/3 and Joliet UCS-2 supplementary volume descriptors.
- It does not currently support Rock Ridge POSIX permission extensions or pure UDF 2.60 filesystems.

### WIM Splitting Implementation
- `WimChunker` splits `install.wim` into `< 4 GB` `.swm` chunks using stream-boundary chunk segmentation.
- It does **not** include a native cross-compiled `wimlib` C++ library to perform LZMS/XPRESS dictionary re-compression or modify embedded WIM XML image catalogs. For typical Windows installation media, stream chunking is sufficient for UEFI bootloaders, but complex multi-index edition splitting is not supported.

---

## 6. Android Platform & OS Constraints

### USB Host Permission & Disconnect Lifecycle
- Standard Android applications cannot access USB storage without explicit runtime permission via `UsbManager.requestPermission()`.
- Unplugging and reconnecting the OTG drive revokes granted permissions on many Android distributions, requiring re-prompting.
- **OTG Power Loss & Mid-Flash Disconnection:** FlashCore registers dynamic broadcast receivers for `UsbManager.ACTION_USB_DEVICE_DETACHED`. If a drive is physically disconnected or loses power during active writes, the service halts I/O immediately, transitions the FSM to `ErrorRecovery`, and posts an alert notification to prevent hanging coroutines or zombie background processes.

### Storage Access Framework (SAF) & Scoped Storage
- To access external disk images (ISOs) without broad storage permissions, FlashCore integrates with the Android Storage Access Framework (SAF).
- Persistable URI permissions are retained via `contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)` to guarantee read access survives app restarts and process recreation.
- All file reading and hashing streams data incrementally via direct buffers; entire ISOs are never mapped into memory, preventing heap exhaustion on files ranging from 1 GB to 64+ GB.

### Process Lifecycle & Foreground Service
- Background flashing operations are bound to a persistent Android `ForegroundService` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` and acquire a `PARTIAL_WAKE_LOCK` to prevent OS CPU sleep.
- Users can cancel active operations directly from the ongoing system notification via an embedded `ACTION_CANCEL_FLASH` pending intent.
- `FlasherViewModel` utilizes `SavedStateHandle` to preserve user configuration, selected flashing strategies, and ISO metadata across process death and configuration changes.

---

## 7. Feature Maturity Classification

| Feature | Classification | Current State |
| :--- | :--- | :--- |
| **Linux Hybrid Flasher** | 🟢 **Production-Grade** | 8-stage pipeline with destructive check, checksum pre-flight, and bit-for-bit read-back verification. |
| **Non-Root USB Mass Storage Driver** | 🟢 **Production-Grade** | SCSI Bulk-Only Transport (BOT), CBW/CSW transactions, INQUIRY, READ_CAPACITY_10/16, READ_10/16, WRITE_10/16. |
| **Block Device Test Framework** | 🟢 **Production-Grade** | In-memory sparse, file-backed, and fault-injecting block devices running 80+ automated offline unit tests. |
| **Android Production Engineering** | 🟢 **Production-Grade** | ForegroundService (`dataSync`), notification cancellation, SavedStateHandle restoration, persistable SAF URIs, OTG disconnect handling, and 1GB–64GB benchmarking suite. |
| **Partition Subsystem** | 🟢 **Production-Grade** | Standard MBR, Protective MBR, and UEFI Primary/Backup GPT with CRC32 calculation and 1 MiB alignment. |
| **FAT32 Filesystem Writer** | 🟢 **Production-Grade** | Full cluster allocator, directory parser, VBR/FSInfo writer, mkdir, createFile, and streaming file writer. |
| **ISO Filesystem Engine** | 🟢 **Production-Grade** | ISO 9660 & Joliet volume descriptor parser, directory tree reader, and streaming file extractor. |
| **Target Read-Back Verification** | 🟢 **Production-Grade** | Real bit-for-bit target sector read-back pass (`FlashVerifier`) with exact LBA error pinpointing and dual SHA-256. |
| **Windows UEFI Boot Engine** | 🟡 **Engine Implemented** | Dynamic capability detection (x64/ARM64/IA32), WIM/SWM chunking, BCD/bootloader provisioning, and binary PE verification. Real-hardware PC validation ongoing. |
| **Ventoy Multi-Boot Engine** | 🟡 **Engine Implemented** | Dual-partition MBR/GPT geometry, 32 MB VTOYEFI asset provider, FAT32 data volume, filesystem ISO storage, and non-destructive update. Physical firmware testing ongoing. |
| **SCSI 64-bit Addressing (> 2 TiB)** | 🔴 **Roadmap** | `READ_CAPACITY_16` geometry detection implemented; `WRITE_16` / `READ_16` command execution planned. |
