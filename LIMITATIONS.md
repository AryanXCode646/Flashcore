# ⚠️ FlashCore — Known Technical Limitations & Forensic Engineering Audit

This document provides a transparent, engineering-level breakdown of the current technical limitations, architectural boundaries, software test scope, and hardware validation status of FlashCore.

---

## 📑 Table of Contents
1. [Validation Scope](#1-validation-scope)
2. [Software-Only Validation](#2-software-only-validation)
3. [Hardware Validation Status](#3-hardware-validation-status)
4. [USB & SCSI Protocol Limitations](#4-usb--scsi-protocol-limitations)
5. [Large-Device Limitations (> 2 TiB)](#5-large-device-limitations--2-tib)
6. [Firmware & Operating System Boot Limitations](#6-firmware--operating-system-boot-limitations)
7. [Performance & Memory Architecture Limitations](#7-performance--memory-architecture-limitations)
8. [Android Platform & OS Constraints](#8-android-platform--os-constraints)
9. [Release & Distribution Limitations](#9-release--distribution-limitations)
10. [Audit of Past Documentation & Code Discrepancies](#10-audit-of-past-documentation--code-discrepancies)
11. [How to Reproduce Validation](#11-how-to-reproduce-validation)
12. [Future Hardening Work](#12-future-hardening-work)

---

## 1. Validation Scope

FlashCore is an open-source Android utility designed to communicate directly with USB flash drives over USB On-The-Go (OTG) via Android's user-space USB Host API (`android.hardware.usb.UsbManager`) and raw SCSI Bulk-Only Transport (BOT).

- **What FlashCore is today:** A fully implemented, software-tested flashing engine capable of Sector 0 raw streaming (Linux hybrid), GPT/FAT32 partitioning and ISO extraction with WIM chunking (Windows UEFI), and dual-partition multi-boot preparation (Ventoy), backed by an automated 108-test suite on abstract block devices.
- **What FlashCore is NOT today:** FlashCore is **not yet hardware-matrix validated** across diverse physical USB flash drive controllers, Android OEM hardware, or PC motherboards. No claims of "battle-tested" or "production-grade" reliability on real hardware are made without empirical qualification data.

---

## 2. Software-Only Validation

All automated test verification in FlashCore is conducted strictly in **software-only environments** using pure JVM unit tests, Robolectric Android runtime simulations, and in-memory or file-backed storage abstractions.

### Automated Test Inventory (108 Tests Total)
* **107 Unit & Robolectric Tests (`app/src/test`):**
  - `BlockDeviceFrameworkTest.kt` (26 tests): Validates sector reads, writes, GPT headers, FAT32 boot records, 100 MB throughput simulation, 4 GB sparse boundaries, sector failure injection, disconnect simulation, short write simulation, timeout simulation, configurable sector sizes (512/4096B), multi-sector transfers with buffer offsets, auto-capacity detection, strict LBA/overflow bounds checking, direct buffer validation, sparse zero-fill reads, closed device lifecycle, independent MBR/GPT structural and CRC32 verification, concurrent operations and close races, and constructor descriptor leak prevention.
  - `AndroidProductionEngineeringTest.kt` (10 tests): Validates foreground service lifecycle, cancellation action dispatch, `SavedStateHandle` restoration across process recreation, dynamic USB detachment broadcast handling, SAF 64-bit integer arithmetic, and synthetic 50 MB benchmark scaling.
  - `Fat32WriterTest.kt` (9 tests): Validates volume formatting, VBR/FSInfo boot sectors, directory creation (`mkdir`), multi-cluster file writes, cluster appending, directory expansion, Long File Names (LFN), and FSInfo free cluster tracking.
  - `PartitionEngineTest.kt` (9 tests): Validates MBR construction, Protective MBR generation, GPT table generation, dynamic CRC32 computation, round-trip GPT parsing, tamper detection, mixed-endian GUID conversions, and 1 MiB alignment arithmetic.
  - `LinuxFlashingPipelineTest.kt` (8 tests): Validates end-to-end raw streaming, target capacity verification, write-protect detection, partition wipe warnings, destructive write confirmation, source checksum pre-flight validation, read-back sector corruption detection, and cooperative cancellation.
  - `WindowsUefiPipelineTest.kt` (8 tests): Validates x64/ARM64/dual-arch Windows ISO capability detection, FAT32 cluster slack and capacity analysis, `WimChunker` SWM header creation, bootloader provisioning (`bootx64.efi`), PE `MZ` and registry `regf` header inspection, and end-to-end pipeline execution on in-memory storage.
  - `VentoyPipelineTest.kt` (9 tests): Validates MBR and GPT Ventoy geometry calculations (Partition 1 data + Partition 2 32 MiB VTOYEFI), default asset provider generation, existing media detection, ISO listing in `/ISO/`, fresh install, and non-destructive update preserving user ISO files.
  - `FlashCoreUnitTest.kt` (12 tests): Validates SCSI CBW/CSW byte encoding/decoding, off-heap `DirectRingBuffer` concurrency, `IsoTrieParser`, WIM split planning, rolling checksums (CRC32, Murmur3, SHA-256), SCSI 16-byte CDB construction (`READ_16`, `WRITE_16`), and `MemoryBlockDevice` boundary conditions.
  - `ExampleRobolectricTest.kt` (7 tests): Validates app name strings, ViewModel initial state, and Compose UI component nodes.
  - `IsoEngineTest.kt` (5 tests): Validates Ubuntu, Debian, Arch, Fedora, and Windows synthetic ISO capability detection.
  - `IsoFilesystemReaderTest.kt` (1 test): Validates ISO 9660 / Joliet directory parsing and extraction into `Fat32Writer`.
  - `GreetingScreenshotTest.kt` (1 test): Screenshot validation test.
  - `ExampleUnitTest.kt` (1 test): Basic arithmetic check.
* **1 Android Instrumentation Test (`app/src/androidTest`):**
  - `ExampleInstrumentedTest.kt` (1 test): Package name verification under Android instrumentation runner.

### What Software Tests Validate vs What They Cannot Validate
| Area | Validated in Software Tests | Cannot Validate in Software |
| :--- | :--- | :--- |
| **SCSI / BOT Protocol** | CBW (31B) & CSW (13B) structure, CDB byte layout, tag matching | Physical USB controller endpoint stall recovery, electrical power dips, bus resets |
| **Partitioning** | Protective MBR, GPT header CRC32, LBA math, 1 MiB alignment | PC BIOS/UEFI firmware parsing quirks, partition table re-read by host OS |
| **FAT32 Filesystem** | VBR, FSInfo, cluster chains, short/long filenames | Physical flash NAND wear leveling, dirty bit handling on abrupt removal |
| **Windows Bootloader** | SWM header format, PE `MZ` header check, BCD hive presence | Motherboard Secure Boot key validation, vendor UEFI driver compatibility |
| **Android Lifecycle** | Foreground service intents, notification actions, SavedState | Real Android OEM aggressive background task killers (OneUI, MIUI, etc.) |

---

## 3. Hardware Validation Status

> [!CAUTION]
> **PHYSICAL HARDWARE VALIDATION STATUS: NOT VALIDATED**
> 
> The FlashCore repository currently contains **no empirical test reports, hardware matrix logs, or automated CI runs conducted against physical USB flash drives, OTG adapters, or physical PC motherboards.**

To achieve hardware-validated status, the project requires empirical testing across:
1. **Physical USB Flash Drives:** Diverse controller silicon (Phison, Silicon Motion/SMI, Alcor Micro, Innostor, Realtek) across USB 2.0, USB 3.0, and USB 3.2 Gen 1/2 drives.
2. **OTG Adapters & Cables:** Passive USB-C to USB-A dongles, micro-USB OTG cables, and powered OTG hubs.
3. **Physical Android Devices:** Android 8.0 through Android 15 devices across Qualcomm, MediaTek, Tensor, and Exynos chipsets, testing OTG host controller power negotiation.
4. **PC Hardware Motherboards:** Real x86_64, IA32, and ARM64 PC motherboards running AMI, Insyde, Phoenix, and open-source Coreboot/EDK2 firmware.

---

## 4. USB & SCSI Protocol Limitations

### Bulk Endpoint Stall & BOMSR Recovery
- USB flash drives frequently experience bulk endpoint stalls (`STALL` PID) when internal controller write buffers fill or flash erase blocks take longer than expected.
- FlashCore implements basic recovery via `UsbDeviceConnection.controlTransfer()`:
  - Endpoint clear-halt (`CLEAR_FEATURE(ENDPOINT_HALT)`).
  - Bulk-Only Mass Storage Reset (BOMSR, class request `0xFF`).
- **Limitation:** Inexpensive USB drives (particularly promotional or low-grade drives) often exhibit firmware bugs where issuing `CLEAR_FEATURE` causes the controller to hang permanently or drop off the USB bus entirely, requiring physical replugging. This behavior cannot be mitigated purely in software.

### Partial Bulk Transfers & Caller-Level Verification
- In `UsbMassStorageDriver.kt`, the driver implements looping read/write functions (`bulkTransferInAll` and `bulkTransferOutAll`) that continue reading or writing until `transferred >= length`.
- **Limitation / Edge Case:** If a transfer times out (`elapsed >= timeoutMs`) or encounters a negative return code after sending partial bytes, the loop terminates and returns the partial byte count. In `writeDirectBuffer`, if `sentData < length`, the driver does not currently retry the remaining unsent bytes before attempting to read the CSW; it immediately proceeds to the status phase, which will trigger a CSW phase error or tag mismatch.

### Heap Staging Buffer Copy (Not Zero-Copy)
- Android's public USB Host API (`UsbDeviceConnection.bulkTransfer()`) accepts only Java heap arrays (`ByteArray`). There is no public Android API to pass native memory pointers (`ByteBuffer.allocateDirect`) directly into the underlying Linux `usbfs` kernel driver.
- Consequently, in `UsbMassStorageDriver.writeDirectBuffer()`, data must be copied from the direct ring buffer into a temporary heap-allocated `ByteArray(length)`:
  ```kotlin
  val tempArray = ByteArray(length)
  directBuffer.position(offset)
  directBuffer.get(tempArray, 0, length)
  bulkTransferOutAll(conn, outEp, tempArray, 0, length, timeoutMs)
  ```
- Similarly, the producer thread in `LinuxRawDdStrategy` reads from the source `InputStream` into a temporary heap array before writing into the ring buffer.
- **Accurate Architectural Description:** FlashCore uses a direct-buffer based streaming pipeline designed to decouple I/O rates and reduce steady-state GC allocation; however, USB transfers currently include an intermediate heap staging copy. It is **not zero-copy**.

---

## 5. Large-Device Limitations (> 2 TiB)

### 32-Bit vs 64-Bit SCSI Command Dispatch
- Standard SCSI `WRITE_10` (Opcode `0x2A`) and `READ_10` (Opcode `0x28`) utilize 32-bit Logical Block Addressing (LBA).
- With 512-byte logical sectors, 32-bit LBA caps maximum addressable capacity at:
  $$	ext{Max 32-bit Capacity} = 2^{32} 	imes 512 	ext{ bytes} pprox 2.199 	ext{ TB (2.0 TiB)}$$
- `ScsiCdbBuilder` implements 16-byte SCSI commands:
  - `READ_CAPACITY_16` (Opcode `0x9E`, Service Action `0x10`)
  - `READ_16` (Opcode `0x88`)
  - `WRITE_16` (Opcode `0x8A`)
- `UsbMassStorageDriver` contains code to dynamically dispatch to `write16` and `read16` when `lba > 0xFFFFFFFFL || blockCount > 0xFFFF`.
- **Limitation:** While 16-byte CDB construction is unit tested in `FlashCoreUnitTest.kt`, **no physical validation has been performed on drives > 2 TiB** (such as external 4 TB–16 TB USB HDDs/SSDs). Many USB-to-SATA and USB-to-NVMe bridge chipsets have known firmware bugs when handling 16-byte CDBs over Bulk-Only Transport.

---

## 6. Firmware & Operating System Boot Limitations

### Linux Flashing (`LinuxRawDdStrategy`)
- Operates strictly as a Sector 0 raw streaming copy (`dd` equivalent).
- **Limitation:** Only boots images that are formatted as **isohybrid ISOs** (embedding MBR/GPT partition tables and El Torito boot structures at Sector 0). Standard optical-only legacy ISOs (such as older distribution discs or Windows ISOs) will NOT boot when written via raw sector streaming.

### Windows UEFI Flashing (`WindowsUefiStrategy`)
- Partitions the target drive with GPT, formats Partition 1 with FAT32, and splits `install.wim` files exceeding 4 GiB into `< 4 GB` `.swm` chunks (`WimChunker`).
- **Limitations & Quirks:**
  - **Motherboard UEFI Quirks:** Some legacy or buggy UEFI implementations fail to recognize split `.swm` chunks without specific BCD boot configuration entries.
  - **Secure Boot:** Booting Windows installers requires UEFI firmware to accept Microsoft's third-party UEFI CA certificate. On PCs with strict Secure Boot configurations, user intervention in BIOS setup may be required.
  - **No wimlib Integration:** FlashCore uses stream-boundary chunk segmentation; it does not contain a native C++ `wimlib` library to recompress LZMS/XPRESS dictionaries or modify embedded WIM XML catalogs.

### Ventoy Integration (`VentoyStrategy`)
- Implements Ventoy-compliant dual-partitioning: Partition 1 (FAT32 Data volume with `/ventoy/` and `/ISO/`) and Partition 2 (32 MiB VTOYEFI boot volume).
- **Limitations:**
  - Bootloader assets generated by the built-in offline asset provider use synthetic FAT structures and stub PE binaries for testing. For genuine bootability, users should supply official upstream `ventoy.disk.img` assets.
  - Physical multi-booting across legacy BIOS CSM and diverse UEFI architectures (IA32, x64, ARM64) is subject to motherboard firmware quirks and has not been validated on real hardware.

---

## 7. Performance & Memory Architecture Limitations

### Verification Performance Trade-Off
- `FlashVerifier` executes a full bit-for-bit target read-back pass after `SYNCHRONIZE_CACHE_10`.
- **Overhead:** Reading back every sector over USB OTG roughly doubles total operation duration. For example, writing a 4 GB ISO at 15 MB/s takes ~4.5 minutes, and reading it back for verification takes an additional ~4.5 minutes.
- **Reliability Trade-Off:** While verification can be disabled in `FlashConfig` (`verifyAfterWrite = false`) for users prioritizing speed, doing so means NAND write errors, controller drops, or fake-capacity USB drives will go undetected.

### Performance Benchmarks Reality
- Benchmark tests in `AndroidProductionEngineeringTest.kt` evaluate arithmetic scaling and write 50 MB synthetic payloads to an in-memory sink (`SyntheticBenchmarkSinkDevice`).
- **Limitation:** There are no real-hardware performance benchmarks in the repository. Actual USB OTG flashing throughput is heavily constrained by:
  - Phone OTG controller hardware (USB 2.0 speeds are typically limited to 15–35 MB/s regardless of drive capability).
  - USB flash drive thermal throttling during sustained multi-gigabyte sequential writes.

---

## 8. Android Platform & OS Constraints

### USB Host Permission & Detach Lifecycle
- Android applications cannot access USB devices without runtime permission granted via `UsbManager.requestPermission()`.
- **Limitation:** On many Android OEM distributions, disconnecting the OTG adapter immediately revokes granted permissions. Reconnecting the drive requires prompting the user again.
- If a drive is disconnected mid-flash, the dynamic `ACTION_USB_DEVICE_DETACHED` receiver halts writes and transitions the FSM to `ErrorRecovery`. The target drive may be left with a partial, corrupt partition table.

### OEM Background Execution Limits
- FlashCore runs active operations in a persistent `ForegroundService` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` and holds a `PARTIAL_WAKE_LOCK`.
- **Limitation:** Certain aggressive Android OEM battery managers (e.g. Samsung OneUI, Xiaomi MIUI, Huawei EMUI) may terminate long-running foreground services during screen-off states despite wake locks. Users are advised to keep the application in the foreground and the device connected to power during flashing operations.

---

## 9. Release & Distribution Limitations

### No Official Published Releases
- As of the current audit, **no official release tags (e.g. `v1.0.0`) or release APK binaries have been published.**
- Automated CI and release workflows exist (`.github/workflows/ci.yml`, `.github/workflows/release.yml`), but they represent automation infrastructure rather than evidence of shipped releases.

### Reproducible Builds
- Reproducible build settings are configured in `app/build.gradle.kts`. However, until an official release is tagged and built in CI, end-to-end binary reproducibility cannot be verified against published `SHA256SUMS.txt` digests.

---

## 10. Audit of Past Documentation & Code Discrepancies

This audit resolved several contradictions between past documentation and the actual codebase:

| Previous Documentation Claim | Codebase & Forensic Reality | Resolution in Current Documentation |
| :--- | :--- | :--- |
| **"Production-Grade"** labeled on 8+ subsystems | Implementation exists and passes offline unit tests; zero physical hardware validation | Downgraded to **"Implemented — hardware validation pending"** or **"Implemented — software tested"** |
| **Past "Zero-Copy" Claim** | Android USB API requires `ByteArray` (not zero-copy), necessitating an intermediate heap copy in `writeDirectBuffer()` | Replaced with **"Direct-buffer based streaming pipeline with staging copy"** |
| **"Driver does not loop on partial transfers"** | `bulkTransferInAll` and `bulkTransferOutAll` explicitly loop while `transferred < length` | Corrected to explain that the loop exists, but caller-level full-length verification on timeout remains a gap |
| **"SCSI 16-bit / >2 TB is roadmap only"** | `write16`, `read16`, and `readCapacity16` CDB builders and dispatch are already in code | Clarified that 16-byte CDBs are implemented in software but untested on physical >2 TiB drives |
| **"v1.0.0 Release Shipped"** in changelog & build docs | No git tags exist (`git tag -l` is empty); no release APKs published | Corrected changelog to reflect development baseline; updated build instructions |
| **"1GB–64GB benchmarking suite"** | Benchmark tests execute 50 MB synthetic workloads on in-memory mocks or arithmetic assertions | Clarified as synthetic benchmark tests; real hardware telemetry pending |

---

## 11. How to Reproduce Validation

### 1. Running the Automated Test Suite
Ensure JDK 21 and Android SDK Platform 36 are installed and configured:
```bash
# Execute the complete 92-test JVM/Robolectric test suite
./gradlew test

# Run Android Lint quality checks
./gradlew lint
```

### 2. Running the Repository Forensic Audit Script
To verify that no unsupported claims, fake test counts, or banned terminology have regressed:
```bash
python3 scripts/audit_claims.py
```

### 3. Community Hardware Validation Checklist
Community members wishing to validate FlashCore on physical hardware should record:
- [ ] Android Device: Make, Model, Android OS version, Chipset
- [ ] OTG Adapter: Manufacturer, connector type, active/passive
- [ ] USB Flash Drive: Vendor, Model, Capacity, Controller Chipset (via ChipEasy/Flash Drive Information Extractor)
- [ ] Test Workload: Linux ISO (distro, version, isohybrid status) or Windows ISO (edition, architecture)
- [ ] Outcome: Flashing success, verification result, PC motherboard boot result (Motherboard model, BIOS version, UEFI/CSM mode)

---

## 12. Future Hardening Work

The following engineering tasks represent the concrete technical roadmap:
1. **Physical Hardware Matrix:** Establish empirical test records across 10+ distinct USB flash drive controllers and 5+ Android device families.
2. **Short Transfer Caller Hardening:** Verify `sentData == length` in `UsbMassStorageDriver.writeDirectBuffer` and retry unsent tail blocks prior to CSW phase.
3. **Multi-Module Refactoring:** Extract pure Kotlin modules (`:core:scsi`, `:core:partition`, `:core:filesystem`, `:core:iso`) out of `:app` to enforce strict architectural boundaries.
4. **Namespace Migration:** Transition package namespace from `com.example.*` to `com.ashishsinghbora.flashcore`.
5. **Real-Device Benchmark Telemetry:** Measure actual thermal throttling and transfer rates on physical OTG devices.
