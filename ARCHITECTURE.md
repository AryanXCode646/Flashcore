# 🏛️ FlashCore Architecture

> **Layered architecture, hardware abstraction interfaces, and technical roadmap for a non-root Android USB flashing engine.**

This document details the system architecture, component layers, data pipelines, hardware abstraction interfaces, and the planned modularization roadmap of FlashCore.

---

## 📑 Table of Contents

1. [High-Level Architecture](#1-high-level-architecture)
2. [Architectural Philosophy: Evidence Over Claims](#2-architectural-philosophy-evidence-over-claims)
3. [Current Implementation vs Planned Evolution](#3-current-implementation-vs-planned-evolution)
4. [Layer-by-Layer Decomposition (Current Monolithic `:app` Module)](#4-layer-by-layer-decomposition-current-monolithic-app-module)
   - [Layer 1: UI & API Layer](#layer-1-ui--api-layer)
   - [Layer 2: Flash Engine & Strategy Interface](#layer-2-flash-engine--strategy-interface)
   - [Layer 3: Strategy Implementations](#layer-3-strategy-implementations)
   - [Layer 4: Filesystem Layer](#layer-4-filesystem-layer)
   - [Layer 5: Partition Layer](#layer-5-partition-layer)
   - [Layer 6: Block Device Abstraction](#layer-6-block-device-abstraction)
   - [Layer 7: Hardware Transport & SCSI Driver](#layer-7-hardware-transport--scsi-driver)
5. [Memory Pipeline & Buffer Architecture](#5-memory-pipeline--buffer-architecture)
6. [Verification & Safety Pipeline](#6-verification--safety-pipeline)
7. [Engineering Priorities](#7-engineering-priorities)
8. [Planned Multi-Module Roadmap (Future Evolution)](#8-planned-multi-module-roadmap-future-evolution)
9. [Planned Namespace Migration (Future Evolution)](#9-planned-namespace-migration-future-evolution)

---

## 1. High-Level Architecture

The core architecture of FlashCore enforces strict separation of concerns, ensuring that user interface components and high-level flashing workflows never interact directly with raw USB or SCSI hardware. Operations flow downward through well-defined, testable abstractions:

```
                         FlashCore
                            │
                    ┌───────┴────────┐
                    │                │
                 UI/API          Flash Engine
                                      │
                              Strategy Interface
                                      │
              ┌───────────────────────┼──────────────────────┐
              │                       │                      │
           Linux                   Windows                Ventoy
              │                       │                      │
              └───────────────────────┼──────────────────────┘
                                      │
                              Filesystem Layer
                                      │
                         ┌────────────┴────────────┐
                         │                         │
                      ISO9660                   FAT32
                         │                         │
                         └────────────┬────────────┘
                                      │
                              Partition Layer
                                      │
                              GPT / MBR / etc.
                                      │
                               Block Device API
                                      │
                              ┌───────┴────────┐
                              │                │
                         USB/SCSI BOT       Test Device
                              │
                        USB Mass Storage
                              │
                          Physical USB
```

---

## 2. Architectural Philosophy: Evidence Over Claims

Flashing bootable operating systems over USB OTG carries inherent risk: corrupted sectors or miscalculated partition boundaries produce unbootable media or damage file structures.

FlashCore adheres to a fundamental principle:
> **"Evidence over claims."** An implementation backed by 109 automated tests on abstract block devices and honest documentation of physical hardware limits is far more defensible than marketing unvalidated features as "production-grade."

Every layer in FlashCore is designed to be **isolated, mockable, and verifiable offline** without requiring physical Android devices or USB drives.

---

## 3. Current Implementation vs Planned Evolution

To maintain engineering transparency, the architectural reality of the repository must be clearly distinguished from its future roadmap:

| Dimension | Current Implementation Reality | Planned Architectural Roadmap |
| :--- | :--- | :--- |
| **Gradle Modules** | Single monolithic `:app` module containing all layers | Multi-module separation (`:core`, `:flashers`, `:app`) |
| **Package Namespace** | `com.ashishsinghbora.flashcore.*` across all source packages | Multi-module package organization |
| **Testing Scope** | 109 automated software tests on `BlockDevice` doubles | Automated CI + physical USB controller test matrix |
| **USB Memory Pipeline** | Direct-buffer circular ring buffer with heap staging copy | Direct ring buffer (true zero-copy is not possible via public Android APIs; requires staging copy) |
| **Hardware Status** | Software tested; hardware validation pending | Physical qualification across OEM and controller matrix |

---

## 4. Layer-by-Layer Decomposition (Current Monolithic `:app` Module)

All layers currently reside within the `app` module under `com.example.*`.

### Layer 1: UI & API Layer
* **Components:** `FlasherViewModel`, Jetpack Compose Screens (`MainFlasherScreen`, `TelemetryGauges`, `DriveSelectorCard`), `FlashForegroundService`.
* **Responsibilities:**
  - Presents real-time status, throughput metrics, and progress.
  - Collects user selections via Storage Access Framework (SAF) and USB device picker.
  - Handles process death and recreation via `SavedStateHandle`.
  - Manages background longevity via an Android 14+ `ForegroundService` (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`) with a `PARTIAL_WAKE_LOCK`.
  - Supports user cancellation directly from system notification (`ACTION_CANCEL_FLASH`).

### Layer 2: Flash Engine & Strategy Interface
* **Components:** `FlasherStateMachine` (FSM), `FlashEngineStrategy`, `FlasherState`.
* **Responsibilities:**
  - Governs operational state transitions: `Idle` ➔ `Validating` ➔ `Flashing` ➔ `Verifying` ➔ `Completed` (or `ErrorRecovery`).
  - Standardizes the strategy contract:
    ```kotlin
    interface FlashEngineStrategy {
        val id: String
        val displayName: String
        val description: String
        suspend fun execute(
            context: Context,
            device: BlockDevice,
            targetDrive: UsbDiskInfo,
            sourceUri: Uri,
            isoAnalysis: IsoTrieParser.AnalysisResult,
            config: FlashConfig,
            callback: ProgressCallback,
            isCancelled: () -> Boolean
        ): StrategyResult
    }
    ```

### Layer 3: Strategy Implementations
1. **Linux Raw Hybrid Flasher (`LinuxRawDdStrategy`):**
   - Sector 0 direct streaming for isohybrid images (Ubuntu, Arch, Fedora, Debian).
   - Validates hybrid MBR/GPT and El Torito boot structures pre-flight.
   - Decoupled reader/writer ring buffer.
   - Cache flush (`SYNCHRONIZE_CACHE_10`) followed by optional bit-for-bit target read-back verification (`FlashVerifier`).
2. **Windows UEFI Boot Engine (`WindowsUefiStrategy`):**
   - Detects architecture (x64, ARM64, IA32) and bootloader presence.
   - Partitions target drive via GPT (Protective MBR + Primary/Backup GPT).
   - Formats FAT32 volume and streams ISO files into allocated clusters.
   - On-the-fly WIM stream chunking (`install.wim` ➔ `.swm` parts) when exceeding FAT32's 4 GiB limit.
   - Provisions UEFI bootloaders (`/efi/boot/bootx64.efi`) and BCD registry hives.
3. **Ventoy Multi-Boot Engine (`VentoyStrategy`):**
   - Dual-partition architecture: Partition 1 (FAT32 Data volume) + Partition 2 (32 MiB VTOYEFI bootloader).
   - Implements `FRESH_INSTALL` and `NON_DESTRUCTIVE_UPDATE` (preserves Partition 1 ISO files while upgrading boot code).
   - Provisions standard Ventoy directories (`/ventoy/`, `/ISO/`).

### Layer 4: Filesystem Layer
* **Components:** `IsoFilesystemReader`, `Fat32Writer`, `Fat32ClusterAllocator`, `Fat32Table`, `Fat32Directory`.
* **Responsibilities:**
  - **ISO 9660 & Joliet Engine:** Parses Primary Volume Descriptors, Joliet UCS-2 extensions, El Torito catalogs, and streams individual files directly from ISO sources.
  - **FAT32 Engine:** Constructs Volume Boot Records (VBR), FSInfo sectors, File Allocation Tables (FAT1/FAT2), allocates cluster chains, manages directory entries (including LFN), and writes files.

### Layer 5: Partition Layer
* **Components:** `MbrBuilder`, `GptBuilder`, `PartitionEngine`, `PartitionBlockDevice`.
* **Responsibilities:**
  - Constructs legacy MBR partition tables with standard boot indicators.
  - Generates UEFI-compliant GPT structures: Protective MBR, Primary GPT Header, Partition Entries, Backup Partition Entries, Backup GPT Header.
  - Enforces 1 MiB (2048 sector @ 512B) boundary alignment.
  - Computes partition table and header CRC32 digests dynamically.

### Layer 6: Block Device Abstraction
* **Components:** `BlockDevice` interface, `MemoryBlockDevice`, `FileBackedBlockDevice`, `FaultInjectingBlockDevice`, `FakeBlockDevice`.
* **Responsibilities:**
  - Isolates upper layers from physical hardware APIs:
    ```kotlin
    interface BlockDevice : Closeable {
        val isConnected: Boolean
        val sectorSizeBytes: Int
        suspend fun capacity(): DeviceCapacity
        suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int = 0): Boolean
        suspend fun write(lba: Long, blockCount: Int, src: ByteArray, offset: Int = 0): Boolean
        suspend fun writeDirectBuffer(lba: Long, blockCount: Int, directBuffer: ByteBuffer, offset: Int, length: Int): Boolean
        suspend fun flush(): Boolean
    }
    ```
  - Powers automated offline testing via in-memory, file-backed, and fault-injecting test doubles.
  - **File-Backed Disk Images (`FileBackedBlockDevice`):**
    - Enables persistent raw disk images (`.img`) on host filesystems for integration testing without physical USB hardware.
    - **Configurable Sector Sizes:** Supports 512-byte and 4096-byte (4K native) geometries.
    - **Capacity & Geometry Semantics:**
      - *Explicit Logical Capacity:* New or sparse images specify `totalSectors`, with optional preallocation.
      - *Auto-Capacity Detection:* Existing images opened via `openExisting(file)` derive capacity from backing file byte length (`file.length() / sectorSizeBytes`) with strict alignment validation.
    - **Sparse & Unwritten Region Semantics:** Unallocated or trailing sparse regions read back as deterministic zeroes.
    - **Safety & Verification:** Overflow-safe 64-bit LBA bounds checks, buffer offset/length validation, direct `ByteBuffer` integrity verification, and media cache persistence via `FileChannel.force(true)` on `flush()`.
    - **Lifecycle:** Idempotent `close()` and consistent `DeviceDisconnectedException` enforcement upon disconnection.
  - **Fault-Injecting Block Device (`FaultInjectingBlockDevice`):**
    - Decorator wrapping any underlying `BlockDevice` to deterministically simulate physical hardware and bus failures without physical USB OTG media.
    - **Read & Write Faults:** Injects hard I/O exceptions at exact LBAs, across LBA ranges, or on next operations with configurable one-shot or persistent semantics, executing before touching the delegate.
    - **Short Transfers (Partial I/O):** Supports partial sector transfers for both standard array and direct `ByteBuffer` paths (`read`, `write`, `writeDirectBuffer`), faithfully modeling hardware rejection without ambiguous success states.
    - **Device Disconnect Simulation:** Triggers persistent `DeviceDisconnectedException` at exact LBAs or after configurable byte/block transfer thresholds, transitioning `isConnected` to false and rejecting all subsequent operations.
    - **Timeouts:** Coroutine-safe cancellable delays with configurable `InterruptedIOException` injection on read, write, or specific LBAs.
    - **Flush / Cache Sync Failures:** Simulates volatile-to-NAND cache sync failures (either throwing `IOException` or returning false), ensuring flashing pipelines never falsely report completion after a flush fault.
    - **Sector Corruption:** Emulates bit-rot and transmission corruption on read and write paths for verification engine testing without corrupting caller buffers.

### Layer 7: Hardware Transport & SCSI Driver
* **Components:** `UsbMassStorageDriver`, `CommandBlockWrapper` (CBW), `CommandStatusWrapper` (CSW), `ScsiCdbBuilder`, `ScsiCheckConditionException`, `ScsiCommandResult`.
* **Responsibilities:**
  - Interacts with Android's `UsbManager` and `UsbDeviceConnection`.
  - Implements SCSI Bulk-Only Transport (BOT, USB Mass Storage Class specification).
  - Encapsulates SCSI commands in 31-byte CBWs, executes bulk IN/OUT data transfers, and evaluates 13-byte CSWs.
  - Supports standard SCSI command set: `INQUIRY` (0x12), `READ_CAPACITY_10` (0x25), `READ_CAPACITY_16` (0x9E), `READ_10` (0x28), `WRITE_10` (0x2A), `READ_16` (0x88), `WRITE_16` (0x8A), `SYNCHRONIZE_CACHE_10` (0x35), `REQUEST_SENSE` (0x03), `MODE_SENSE_6` (0x1A).
  - Automatically intercepts SCSI `CHECK CONDITION` (`bCSWStatus == 0x01`), issues SCSI `REQUEST SENSE` (opcode 0x03), parses fixed (0x70/0x71) and descriptor (0x72/0x73) sense data, and attaches structured diagnostics (`SenseDataResponse`, `ScsiCheckConditionException`, `ScsiCommandResult`) without recursive recovery loops.
  - Handles endpoint halt clearing (`CLEAR_FEATURE`) and Bulk-Only Mass Storage Reset (BOMSR).

---

## 5. Memory Pipeline & Buffer Architecture

### Direct Ring Buffer (`DirectRingBuffer`)
- An off-heap Single-Producer Single-Consumer (SPSC) circular buffer backed by `ByteBuffer.allocateDirect` chunks (typically 1 MB or 2 MB per slot).
- Decouples disk stream reading (producer) from USB Bulk-Only Transport writing (consumer) using `ReentrantLock` and condition variables.
- Eliminates repeated JVM heap allocations within the ring buffer itself, reducing Garbage Collection (GC) pauses.

### USB Transfer Staging Copy (Architectural Reality)
- Android's public USB Host API (`UsbDeviceConnection.bulkTransfer()`) does not support passing off-heap native memory addresses directly to the kernel.
- In `UsbMassStorageDriver.writeDirectBuffer()`, bytes from the direct buffer are read into a temporary heap `ByteArray` before being passed to `conn.bulkTransfer()`.
- **Engineering Note:** FlashCore is **not zero-copy**. It is a direct-buffer based streaming pipeline designed to decouple I/O rates and reduce steady-state allocation, with an intermediate staging copy at the Android USB Host API boundary.

---

## 6. Verification & Safety Pipeline

```
[ ISO Source Stream ] ──(Streaming SHA-256)──► Expected Digest
        │
   (Sector Write)
        ▼
[ Target Block Device / USB ]
        │
   (Sync / Cache Flush)
        ▼
[ FlashVerifier Read-Back ] ──(Read-Back SHA-256)──► Actual Target Digest
        │
   (Comparison)
        ▼
 [ Bit-for-Bit Verified or Target LBA Pinpoint Error ]
```

1. **In-Flight Source Hashing:** Accumulates a rolling SHA-256 digest as data streams from the source ISO.
2. **Hardware Cache Flush:** `SYNCHRONIZE_CACHE_10` requests the USB drive controller to flush internal volatile write buffers to persistent flash memory.
3. **Target Read-Back Pass (`FlashVerifier`):** Reads sectors back from the target block device, computing the target SHA-256 digest and comparing byte-for-byte against the source stream.
4. **LBA Error Localization:** Pinpoints the exact sector offset and byte index if a mismatch occurs.
5. **Optional Trade-Off:** Verification roughly doubles total flashing duration. It can be disabled by users in `FlashConfig` (`verifyAfterWrite = false`), accepting the reliability trade-off that silent NAND write failures will not be detected.

---

## 7. Engineering Priorities

To maintain system integrity, engineering work strictly adheres to this priority hierarchy:

1. 🥇 **Correctness:** Bit-for-bit exactness in sector writing and verification logic.
2. 🥈 **Safety:** Hardened disconnect handling (`ACTION_USB_DEVICE_DETACHED`) and target drive validation.
3. 🥉 **Testability:** 100% of core logic must run offline via `BlockDevice` abstractions.
4. **USB Reliability:** SCSI BOT stall recovery routines, retry loops, and error diagnosis.
5. **Block-Device Abstraction:** Zero coupling between upper layers and Android hardware APIs.
6. **Partition Correctness:** Strict GPT/MBR alignment, CRC32 validation, and protective structures.
7. **Filesystem Correctness:** Fully conforming FAT32/ISO structures, directory records, and cluster maps.
8. **Linux Flashing:** Flawless hybrid streaming and verification.
9. **Windows Flashing:** Robust UEFI FAT32 extraction and WIM splitting.
10. **Ventoy:** Compliant multi-boot dual-partitioning and non-destructive updating.
11. **Android UX:** Responsive Compose UI, foreground service, and process death persistence.
12. **Release Engineering:** Automated CI/CD, lint checks, test suites, reproducible builds, and signed releases.

---

## 8. Planned Multi-Module Roadmap (Future Evolution)

The planned evolution from the current single `:app` module into isolated Gradle subprojects:

```text
flashcore/
├── app/                        # Android UI, ViewModels, Compose, ForegroundService
├── core/
│   ├── blockdevice/            # BlockDevice interface, Memory & Fault-injecting devices
│   ├── scsi/                   # CBW/CSW protocol, SCSI command builder, sense parser
│   ├── usb/                    # UsbMassStorageDriver, Android UsbManager host driver
│   ├── partition/              # MBR, GPT, GUIDs, CRC32 builders
│   ├── filesystem/             # FAT32 formatter, cluster allocator, directory parser
│   ├── iso/                    # ISO 9660, Joliet, El Torito parser and extractor
│   └── verification/           # FlashVerifier, checksum engines, read-back validators
├── flashers/
│   ├── linux/                  # LinuxRawDdStrategy and hybrid verification
│   ├── windows/                # WindowsUefiStrategy, WIM splitter, BCD generator
│   └── ventoy/                 # VentoyStrategy, dual-partition installer, update engine
├── native/                     # (Optional future) C++17 accelerated routines / wimlib
└── test/                       # Shared fixtures, test images, hardware test harnesses
```

---

## 9. Planned Namespace Migration (Future Evolution)

The planned migration of legacy `com.example.*` packages to `com.ashishsinghbora.flashcore`:

| Current Namespace | Target Namespace | Target Module |
| :--- | :--- | :--- |
| `com.example.dsa` | `com.ashishsinghbora.flashcore.core.buffer` | `:core:blockdevice` |
| `com.example.scsi` | `com.ashishsinghbora.flashcore.core.scsi` | `:core:scsi` |
| `com.example.usb` | `com.ashishsinghbora.flashcore.core.usb` | `:core:usb` |
| `com.example.partition` | `com.ashishsinghbora.flashcore.core.partition` | `:core:partition` |
| `com.example.iso` | `com.ashishsinghbora.flashcore.core.iso` | `:core:iso` |
| `com.example.flasher` | `com.ashishsinghbora.flashcore.flashers` | `:flashers` |
| `com.example.flasher.verification` | `com.ashishsinghbora.flashcore.core.verification` | `:core:verification` |
| `com.example.service` | `com.ashishsinghbora.flashcore.app.service` | `:app` |
| `com.example.ui` | `com.ashishsinghbora.flashcore.app.ui` | `:app` |

*Note: This migration is planned alongside multi-module extraction to prevent merge conflicts and preserve test stability.*
