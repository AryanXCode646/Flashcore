# 🏛️ FlashCore Architecture

> **Defensible, layered architecture for a non-root Android USB flashing engine.**

This document details the system architecture, component layers, data pipelines, hardware abstraction interfaces, and the long-term modularization roadmap of FlashCore.

---

## 📑 Table of Contents

1. [High-Level Architecture](#1-high-level-architecture)
2. [Architectural Philosophy: Trustworthy over Feature-Rich](#2-architectural-philosophy-trustworthy-over-feature-rich)
3. [Layer-by-Layer Decomposition](#3-layer-by-layer-decomposition)
   - [Layer 1: UI & API Layer](#layer-1-ui--api-layer)
   - [Layer 2: Flash Engine & Strategy Interface](#layer-2-flash-engine--strategy-interface)
   - [Layer 3: Strategy Implementations](#layer-3-strategy-implementations)
   - [Layer 4: Filesystem Layer](#layer-4-filesystem-layer)
   - [Layer 5: Partition Layer](#layer-5-partition-layer)
   - [Layer 6: Block Device Abstraction](#layer-6-block-device-abstraction)
   - [Layer 7: Hardware Transport & Physical Device](#layer-7-hardware-transport--physical-device)
4. [Verification & Safety Pipeline](#4-verification--safety-pipeline)
5. [Engineering Priorities](#5-engineering-priorities)
6. [Multi-Module Modularization Roadmap](#6-multi-module-modularization-roadmap)
7. [Namespace Migration Plan](#7-namespace-migration-plan)

---

## 1. High-Level Architecture

The core of FlashCore is built around strict separation of concerns, ensuring that high-level user interface elements and flashing workflows never interact directly with raw USB or SCSI hardware. Instead, operations flow downward through well-defined, testable abstractions:

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

## 2. Architectural Philosophy: Trustworthy over Feature-Rich

Flashing bootable operating systems over USB OTG carries inherent risk: writing corrupted sectors or improperly aligned partition tables renders physical media unbootable or causes silent data corruption. 

FlashCore adheres to a fundamental guiding principle:
> **"Evidence over claims."** A project that safely writes an image to USB, handles mid-flight disconnects, verifies every sector, has an exhaustive automated test suite, and is validated across physical devices is infinitely superior to a fragile utility packed with unverified features.

Every layer in FlashCore is designed to be **isolated, mockable, and verifiable offline** without requiring a physical Android device or USB drive plugged in.

---

## 3. Layer-by-Layer Decomposition

### Layer 1: UI & API Layer
* **Components:** `FlasherViewModel`, Jetpack Compose Screens (`FlashScreen`, `TelemetryDashboard`), `FlashForegroundService`.
* **Responsibilities:**
  - Presents interactive status, throughput sparklines, circular progress, and real-time LBA stride visualization.
  - Collects user selections (ISO source via Storage Access Framework, target USB drive).
  - Handles Android lifecycle events: process death recovery via `SavedStateHandle`, persistable SAF URI permissions (`takePersistableUriPermission`).
  - Manages background longevity via an Android 14+ compatible `ForegroundService` (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`) holding a `PARTIAL_WAKE_LOCK`.
  - Exposes user cancellation via notification action (`ACTION_CANCEL_FLASH`).

### Layer 2: Flash Engine & Strategy Interface
* **Components:** `FlasherStateMachine` (FSM), `FlashEngineStrategy`, `FlashState`, `FlashProgress`.
* **Responsibilities:**
  - Governs operational state transitions: `Idle` ➔ `Validating` ➔ `Flashing` ➔ `Verifying` ➔ `Completed` (or `ErrorRecovery`).
  - Provides a unified contract:
    ```kotlin
    interface FlashEngineStrategy {
        val name: String
        suspend fun execute(
            context: Context,
            sourceUri: Uri,
            targetDevice: BlockDevice,
            progressCallback: (FlashProgress) -> Unit,
            cancellationSignal: () -> Boolean
        ): FlashResult
    }
    ```
  - Isolates flashing mechanics from Android Service/ViewModel lifecycles.

### Layer 3: Strategy Implementations
1. **Linux Raw Hybrid Flasher (`LinuxRawDdStrategy`):**
   - Direct Sector 0 streaming (`dd` equivalent) for hybrid images (Ubuntu, Arch, Fedora, Debian).
   - Validates El Torito and partition geometry pre-flight.
   - Decoupled reader/writer ring buffer.
   - Synchronous cache flush (`SYNCHRONIZE_CACHE_10`) followed by bit-for-bit target read-back verification (`FlashVerifier`).
2. **Windows UEFI Boot Engine (`WindowsUefiStrategy`):**
   - Dynamically detects image architecture (x64, ARM64, IA32) and bootloader presence.
   - Calculates target FAT32 geometry and partition boundaries.
   - Partitions target via GPT (Protective MBR + Primary/Backup GPT).
   - Formats FAT32 filesystem (VBR, FSInfo, FAT tables, root directory).
   - Streams ISO files into FAT32 clusters.
   - Performs on-the-fly WIM splitting (`install.wim` ➔ `install.swm`, `install2.swm`) when file exceeds the 4 GiB FAT32 limit.
   - Validates extracted PE `MZ` bootloader binaries (`/EFI/BOOT/BOOTX64.EFI`) and BCD hive signatures.
3. **Ventoy Multi-Boot Engine (`VentoyStrategy`):**
   - Dual-partition architecture: Partition 1 (ExFAT/FAT32 Data) + Partition 2 (32 MiB VTOYEFI bootloader).
   - Supports `FRESH_INSTALL` (full clean partition layout) and `NON_DESTRUCTIVE_UPDATE` (upgrades VTOYEFI and Sector 0 code while preserving existing user ISO files in Partition 1).
   - Provisions standard Ventoy directory structures (`/ventoy/ventoy.json`, `/ISO/`).

### Layer 4: Filesystem Layer
* **Components:** `IsoFilesystemReader`, `Fat32Writer`, `Fat32ClusterAllocator`, `DirectoryEntryParser`.
* **Responsibilities:**
  - **ISO 9660 & Joliet Engine:** Traverses Primary Volume Descriptors (PVD), supplementary volume descriptors, directory trees, path tables, and streams individual files directly from ISO sources.
  - **FAT32 Engine:** Constructs Volume Boot Records (VBR), FSInfo sectors, File Allocation Tables (FAT1 and FAT2), allocates cluster chains, manages short/long directory entries, and streams data into allocated clusters.

### Layer 5: Partition Layer
* **Components:** `MbrBuilder`, `GptBuilder`, `PartitionEntry`, `Crc32Calculator`.
* **Responsibilities:**
  - Constructs legacy MBR partition tables with standard boot indicators and partition types (e.g. `0x0C` FAT32 LBA, `0xEF` EFI System Partition).
  - Generates UEFI-compliant GPT structures: Protective MBR (LBA 0), Primary GPT Header (LBA 1), Partition Table Entries (LBA 2-33), Backup Partition Table Entries, and Backup GPT Header.
  - Enforces 1 MiB (2048 sector @ 512B) boundary alignment for flash endurance and performance.
  - Computes partition table and header CRC32 digests dynamically.

### Layer 6: Block Device Abstraction
* **Components:** `BlockDevice` interface, `MemoryBlockDevice`, `FileBackedBlockDevice`, `FaultInjectingBlockDevice`.
* **Responsibilities:**
  - Decouples all upper layers (filesystems, partition engines, flashing strategies) from physical hardware.
  - Core contract:
    ```kotlin
    interface BlockDevice {
        val totalCapacityBytes: Long
        val sectorSizeBytes: Int
        val totalSectors: Long
        
        suspend fun read(startLba: Long, sectorCount: Int): ByteArray
        suspend fun write(startLba: Long, data: ByteArray, offset: Int, length: Int)
        suspend fun sync()
    }
    ```
  - Enables comprehensive offline automated testing:
    - `MemoryBlockDevice`: Sparse map-backed in-memory sector device for lightning-fast unit tests.
    - `FileBackedBlockDevice`: Local temporary file block device for large-scale integration tests.
    - `FaultInjectingBlockDevice`: Injects I/O failures, short writes, and timeouts to test error recovery paths.

### Layer 7: Hardware Transport & Physical Device
* **Components:** `UsbMassStorageDriver`, `ScsiCbwBuilder`, `ScsiCswParser`, `UsbDiskInfo`.
* **Responsibilities:**
  - Interacts directly with Android's `UsbManager`, `UsbDeviceConnection`, and `UsbEndpoint`.
  - Implements SCSI Bulk-Only Transport (BOT, USB Mass Storage Class specification):
    - Encapsulates SCSI commands into 31-byte Command Block Wrappers (`CBW`).
    - Executes data phase (IN/OUT bulk transfers).
    - Evaluates 13-byte Command Status Wrappers (`CSW`).
  - Supports standard SCSI command set:
    - `INQUIRY` (`0x12`): Device identification, vendor, product, revision.
    - `READ_CAPACITY_10` (`0x25`) & `READ_CAPACITY_16` (`0x9E`): Sector sizing and drive geometry.
    - `READ_10` (`0x28`) & `WRITE_10` (`0x2A`): 32-bit LBA block streaming.
    - `SYNCHRONIZE_CACHE_10` (`0x35`): Flushes volatile drive caches to persistent flash.
    - `REQUEST_SENSE` (`0x03`): Error diagnosis.
  - Fault handling: Bulk endpoint stall detection, `CLEAR_FEATURE(ENDPOINT_HALT)`, and Bulk-Only Mass Storage Reset (BOMSR).

---

## 4. Verification & Safety Pipeline

Data corruption detection is a foundational requirement:

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

1. **In-Flight Source Hashing:** As data is read from the input ISO, a rolling SHA-256 digest is accumulated.
2. **Hardware Cache Flush:** `SYNCHRONIZE_CACHE_10` forces physical flash controllers to commit internal write buffers to non-volatile NAND.
3. **Physical Read-Back Pass (`FlashVerifier`):** The driver reads written sectors back from physical storage, accumulating the target digest and comparing byte-for-byte against the expected digest.
4. **LBA Error Localization:** If verification fails, the exact sector offset and byte index of mismatch are isolated and logged.

---

## 5. Engineering Priorities

To guarantee stability and maintainability, engineering tasks must adhere to this prioritized hierarchy:

1. 🥇 **Correctness:** Bit-for-bit exactness in sector writing and verification.
2. 🥈 **Safety:** Guarding against writes to unintended devices; bulletproof OTG disconnect handling.
3. 🥉 **Testability:** 100% of core logic must run offline via `BlockDevice` abstractions.
4. **USB Reliability:** Robust BOT stall recovery, retry handling, and SCSI error diagnosis.
5. **Block-Device Abstraction:** Zero coupling between upper layers and Android hardware APIs.
6. **Partition Correctness:** Strict GPT/MBR alignment, CRC validation, and protective structures.
7. **Filesystem Correctness:** Fully conforming FAT32/ISO structures, directory records, and cluster maps.
8. **Linux Flashing:** Flawless hybrid streaming and verification.
9. **Windows Flashing:** Robust UEFI FAT32 extraction and WIM splitting.
10. **Ventoy:** Compliant multi-boot dual-partitioning and non-destructive updating.
11. **Android UX:** Responsive Compose UI, background service, and process death persistence.
12. **Performance Optimization:** Throughput tuning, memory-efficient direct ring buffers, and GC pause reduction.
13. **Release Engineering:** Automated CI/CD, lint checks, test suites, reproducible builds, and checksumming.

---

## 6. Multi-Module Modularization Roadmap

The project is evolving from a single `app` module into a structured multi-module architecture:

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

### Benefits of Modularization:
- **Build Isolation:** Changes in flashing strategies do not trigger recompilation of UI or USB drivers.
- **Pure JVM Testing:** Modules like `core/partition`, `core/filesystem`, and `core/iso` have zero Android dependencies and run at maximum speed under standard JVM test runners.
- **Enforced Architectural Boundaries:** Gradle dependencies prevent illegal cross-layer imports (e.g. UI importing SCSI classes directly).

---

## 7. Namespace Migration Plan

The legacy package namespace `com.example.*` will be migrated systematically to the project-owned namespace `com.ashishsinghbora.flashcore`:

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

This migration will be executed alongside module extraction to ensure zero regression in automated test suites and continuous git history tracking.
