# ⚡ FlashCore

> **Non-root bootable USB creator for Android via USB OTG.**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL_v3-blue.svg)](LICENSE)
[![CI](https://github.com/ashishsinghbora/Flashcore/actions/workflows/ci.yml/badge.svg)](https://github.com/ashishsinghbora/Flashcore/actions/workflows/ci.yml)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-green.svg)](https://developer.android.com)
[![JDK](https://img.shields.io/badge/JDK-21-red.svg)](https://adoptium.net)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2+-purple.svg)](https://kotlinlang.org)
[![Tests](https://img.shields.io/badge/Tests-102%20Automated%20Test%20Methods-blue.svg)]()
[![Hardware Validation](https://img.shields.io/badge/Hardware%20Validation-Pending-yellow.svg)](LIMITATIONS.md)
[![Documentation](https://img.shields.io/badge/Docs-Architecture%20%7C%20Limitations-orange.svg)](ARCHITECTURE.md)

**FlashCore** is an open-source Android utility designed to turn an Android device into a PC rescue toolkit. It communicates directly with USB flash drives over USB OTG using Android's USB Host API and raw SCSI Bulk-Only Transport (BOT) protocols — **without requiring root privileges.**

---

## 🧭 The FlashCore Philosophy: Evidence Over Claims

Flashing operating systems over USB OTG is low-level, high-consequence systems programming. Corrupting a single sector or miscalculating partition alignment produces unbootable media or corrupts flash drives.

FlashCore rejects marketing exaggeration. We do **not** prioritize flashy graphs, network ISO downloads, SD cards, or AI features. 

Our guiding principle is **engineering truthfulness**:
> *"Never claim production-grade reliability without concrete physical hardware validation. Code and test-suite verification must be separated clearly from real-world device and firmware compatibility."*

### Engineering Priorities & Baseline Reality
1. 🥇 **Correctness:** Bit-for-bit exactness in sector writing and verification logic.
2. 🥈 **Safety:** Hardened disconnect handling (`ACTION_USB_DEVICE_DETACHED`) and target drive safety checks.
3. 🥉 **Testability:** Core logic is decoupled from Android hardware APIs and covered by 102 automated tests (101 unit/Robolectric in JVM + 1 Android instrumentation test) on abstract `BlockDevice` doubles.
4. **USB Reliability:** SCSI BOT stall recovery routines, clear-halt, and reset recovery (physical controller compatibility matrix pending).
5. **Block-Device Abstraction:** Zero coupling between UI/engines and Android hardware APIs.
6. **Partition Correctness:** Strict GPT/MBR alignment, CRC32 checks, and protective structures.
7. **Filesystem Correctness:** FAT32/ISO structures, directory records, and cluster allocation verified in software.
8. **Linux Flashing:** Sector 0 streaming pipeline with optional bit-for-bit target read-back verification (`FlashVerifier`).
9. **Windows Flashing:** Dynamic architecture detection, UEFI FAT32 layout, and on-the-fly WIM stream chunking (physical motherboard boot validation pending).
10. **Ventoy:** Compliant multi-boot dual-partitioning and non-destructive updating (real PC boot testing pending).
11. **Android UX:** Foreground service (`dataSync`), notification cancellation action, `SavedStateHandle` process death restoration.
12. **Streaming Pipeline:** Direct-buffer based streaming pipeline intended to reduce allocation and GC pressure; USB transfer currently includes a heap staging copy due to Android API constraints.
13. **Release Engineering:** Automated CI/CD workflows, lint checks, test suites, and reproducible build configuration (official signed public release pending).

---

## 🏛️ System Architecture

FlashCore enforces a downward dependency flow where UI components never speak to USB hardware directly:

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

For complete technical specifications, review [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## 📊 Feature Status Matrix

| Component | Implementation | Software Tests | Hardware Validation | Current Status | Known Limitations | Evidence |
| :--- | :--- | :--- | :--- | :---: | :--- | :--- |
| **Linux Hybrid (Raw DD)** | Implemented | 8 unit tests in `LinuxFlashingPipelineTest` | Not validated | 🟡 **Implemented — hardware validation pending** | Requires hybrid ISOs (MBR/GPT at Sector 0); controller write drops and OTG disconnect quirks not validated on physical media | [`LinuxRawDdStrategy.kt`](app/src/main/java/com/example/flasher/strategies/LinuxRawDdStrategy.kt), [`LinuxFlashingPipelineTest.kt`](app/src/test/java/com/example/LinuxFlashingPipelineTest.kt) |
| **Windows UEFI Flasher** | Implemented | 8 unit tests in `WindowsUefiPipelineTest` | Not validated | 🟡 **Implemented — hardware validation pending** | Boot compatibility across diverse PC UEFI motherboards, split SWM discovery, and Secure Boot implementations not validated on physical media | [`WindowsUefiStrategy.kt`](app/src/main/java/com/example/flasher/strategies/WindowsUefiStrategy.kt), [`WindowsUefiPipelineTest.kt`](app/src/test/java/com/example/WindowsUefiPipelineTest.kt) |
| **Ventoy Multi-Boot Engine** | Implemented | 9 unit tests in `VentoyPipelineTest` | Not validated | 🟡 **Implemented — hardware validation pending** | Dual-partition geometry verified in software; physical PC bootloader execution across legacy BIOS / UEFI motherboards not validated on physical media | [`VentoyStrategy.kt`](app/src/main/java/com/example/flasher/strategies/VentoyStrategy.kt), [`VentoyPipelineTest.kt`](app/src/test/java/com/example/VentoyPipelineTest.kt) |
| **Non-Root USB Mass Storage Driver** | Implemented | Unit/mock tests in `FlashCoreUnitTest` | Not validated | 🟡 **Implemented — hardware validation pending** | Android USB API requires heap staging copy (`ByteArray`); caller short transfer validation on timeout gap; >2 TiB commands untested on physical media | [`UsbMassStorageDriver.kt`](app/src/main/java/com/example/usb/UsbMassStorageDriver.kt), [`FlashCoreUnitTest.kt`](app/src/test/java/com/example/FlashCoreUnitTest.kt) |
| **Target Read-Back Verification** | Implemented | Unit/mock tests in `LinuxFlashingPipelineTest` | Not validated | 🟡 **Implemented — hardware validation pending** | Target-sector read-back verification engine; software validation performed against block-device test doubles, physical-media validation pending | [`FlashVerifier.kt`](app/src/main/java/com/example/flasher/verification/FlashVerifier.kt), [`LinuxFlashingPipelineTest.kt`](app/src/test/java/com/example/LinuxFlashingPipelineTest.kt) |
| **Block Device Test Framework** | Implemented | 12 unit tests in `BlockDeviceFrameworkTest` | N/A (Software Test Double) | 🟢 **Implemented — software tested** | In-memory sparse and file-backed simulation; does not emulate physical controller hangs, power drops, or bus resets | [`BlockDevice.kt`](app/src/main/java/com/example/block/BlockDevice.kt), [`BlockDeviceFrameworkTest.kt`](app/src/test/java/com/example/BlockDeviceFrameworkTest.kt) |
| **FAT32 Filesystem Writer** | Implemented | 9 unit tests in `Fat32WriterTest` | Not validated | 🟢 **Implemented — software tested** | Custom minimal FAT32 engine; lacks fsck/repair; cluster allocation not validated against physical OS mount drivers | [`Fat32Writer.kt`](app/src/main/java/com/example/fat32/Fat32Writer.kt), [`Fat32WriterTest.kt`](app/src/test/java/com/example/Fat32WriterTest.kt) |
| **ISO Filesystem Engine** | Implemented | 6 unit tests in `IsoEngineTest` & `IsoFilesystemReaderTest` | N/A (Software Parser) | 🟢 **Implemented — software tested** | Supports ISO 9660 Level 1/2/3 and Joliet; no Rock Ridge POSIX permissions or pure UDF 2.60 support | [`IsoFilesystemReader.kt`](app/src/main/java/com/example/iso/IsoFilesystemReader.kt), [`IsoEngineTest.kt`](app/src/test/java/com/example/IsoEngineTest.kt) |
| **Partition Subsystem** | Implemented | 9 unit tests in `PartitionEngineTest` | Not validated | 🟢 **Implemented — software tested** | MBR and GPT layout generation verified in memory; partition table detection not validated on physical drives | [`PartitionEngine.kt`](app/src/main/java/com/example/partition/PartitionEngine.kt), [`PartitionEngineTest.kt`](app/src/test/java/com/example/PartitionEngineTest.kt) |
| **Android Production Engineering** | Implemented | 10 Robolectric tests in `AndroidProductionEngineeringTest` | Not validated | 🟡 **Implemented — hardware validation pending** | Foreground service and wake lock tested via Robolectric; synthetic benchmark/scalability harness; physical flash-drive performance and thermal telemetry not validated | [`FlashForegroundService.kt`](app/src/main/java/com/example/service/FlashForegroundService.kt), [`AndroidProductionEngineeringTest.kt`](app/src/test/java/com/example/AndroidProductionEngineeringTest.kt) |
| **SPSC Direct Ring Buffer** | Implemented | 1 unit test in `FlashCoreUnitTest` | Not validated | 🟢 **Implemented — software tested** | Off-heap direct buffers reduce GC churn, but USB transfer path still includes a heap staging copy (not zero-copy); uses ReentrantLock | [`DirectRingBuffer.kt`](app/src/main/java/com/example/dsa/DirectRingBuffer.kt), [`FlashCoreUnitTest.kt`](app/src/test/java/com/example/FlashCoreUnitTest.kt) |
| **CI & Release Infrastructure** | Workflows configured | Configured in `.github/workflows` | Not validated | 🟡 **Configured — no published releases** | GitHub Actions workflows configured for lint, test, and signing; no official release tags or published APKs exist yet | [`.github/workflows/ci.yml`](.github/workflows/ci.yml), [`.github/workflows/release.yml`](.github/workflows/release.yml) |

> [!IMPORTANT]
> **Status Definitions:**
> - **Implemented — software tested:** Complete code implementation covered by automated offline unit tests on software abstractions.
> - **Implemented — hardware validation pending:** Software pipeline is implemented and passes software test suites, but requires empirical validation on physical USB flash drives, OTG cables, or PC UEFI/BIOS motherboards.
> - **Production-grade:** Reserved strictly for features backed by both comprehensive software tests AND empirical physical hardware/device matrix validation.

See [`LIMITATIONS.md`](LIMITATIONS.md) for transparent hardware boundaries and firmware considerations.

---

## 📂 Architecture & Package Namespace Status

The codebase is currently organized as a single application module (`:app`) under the package namespace `com.ashishsinghbora.flashcore`. A modular architecture is planned for upcoming milestones:

```text
flashcore/
├── app/                        # Current single module (UI, ViewModels, Compose, Drivers, Flashing engines)
│   └── src/main/java/com/ashishsinghbora/flashcore/
│       ├── block/              # BlockDevice abstraction and test doubles
│       ├── dsa/                # DirectRingBuffer, WimChunker, IsoTrieParser
│       ├── fat32/              # FAT32 formatting and file writing
│       ├── flasher/            # Flashing strategies (Linux, Windows, Ventoy) and verification
│       ├── iso/                # ISO 9660 & Joliet filesystem reader
│       ├── partition/          # MBR, GPT, GUID builders
│       ├── scsi/               # SCSI CDB builder and CBW/CSW protocols
│       ├── service/            # Android ForegroundService and wake lock
│       ├── ui/                 # Jetpack Compose UI components and ViewModel
│       └── usb/                # Android USB Host Mass Storage driver
```

> **Namespace Migration Status:** Migrated to `com.ashishsinghbora.flashcore`. Multi-module extraction (`:core`, `:flashers`, `:app`) is planned in future architectural refactoring.

---

## 📱 System Requirements

* **Android Version:** Android 8.0 (API Level 26) or higher (target SDK: Android 15 / API 36).
* **Hardware:** USB On-The-Go (OTG) host controller support.
* **Accessories:** USB Type-C or Micro-USB OTG adapter + USB flash drive.
* **Root Privileges:** **None.** Operates within standard Android user-space USB Host permissions.

---

## 🔨 Building and Testing from Source

### Prerequisites
1. **JDK 21** (Eclipse Temurin recommended)
2. **Android SDK Platform API 36**
3. **Android Build Tools 36.0.0+**

### Local Verification Pipeline
When building in an environment configured with JDK 21 and Android SDK:

```bash
# 1. Run Android Lint
./gradlew lint

# 2. Run automated test suite (101 JVM/Robolectric unit tests)
./gradlew test

# 3. Assemble Debug APK
./gradlew assembleDebug

# 4. Assemble Release APK
./gradlew assembleRelease
```

### Automated Test Suite Details
The repository contains **102 automated test methods** across 14 test files:
- **101 Unit & Robolectric tests** in `app/src/test` (across 13 test files): Covering block device doubles, SCSI CDB construction, FAT32 formatting/allocation, ISO 9660 parsing, GPT/MBR partition engines, Linux/Windows/Ventoy strategies, and foreground service lifecycle.
- **1 Instrumentation test** in `app/src/androidTest`: Context verification (`ExampleInstrumentedTest.kt`).
- **Physical Hardware Tests:** 0. (All tests run against mock/in-memory abstractions; physical USB hardware and PC boot testing are not automated in CI).

---

## 🔄 Release Engineering & Reproducibility

FlashCore includes build scripts and GitHub Actions workflows designed to support deterministic and reproducible builds.

### Current Release Status
* **Published Releases:** **None.** No public release tags (e.g. `v1.0.0`) or release APK binaries have been published yet.
* **Workflow Automation:** Build and release workflows are configured in [`.github/workflows/ci.yml`](.github/workflows/ci.yml) and [`.github/workflows/release.yml`](.github/workflows/release.yml).
* **Future Releases:** Official releases will publish signed APKs along with corresponding `SHA256SUMS.txt` digests.

Read [`REPRODUCIBLE_BUILDS.md`](REPRODUCIBLE_BUILDS.md) for details on build determinism and verification instructions.

---

## 🤝 Contributing

Contributions, bug reports, and especially physical hardware compatibility reports are welcome!

Please read our contributing guides before opening a PR:
* 📘 [Contributor Guide (`CONTRIBUTING.md`)](CONTRIBUTING.md)
* 🏛️ [Architecture Blueprint (`ARCHITECTURE.md`)](ARCHITECTURE.md)
* ⚠️ [Technical Limitations (`LIMITATIONS.md`)](LIMITATIONS.md)
* 🔒 [Security Policy (`SECURITY.md`)](SECURITY.md)
* 📜 [Code of Conduct (`CODE_OF_CONDUCT.md`)](CODE_OF_CONDUCT.md)

---

## ⚖️ License & Attribution

Distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See [`LICENSE`](LICENSE) for details.

### Third-Party Attribution
* **Ventoy**: Copyright (C) 2019-2024 longpanda `<admin@ventoy.net>`. Licensed under GPL-3.0. Source code available at [https://github.com/ventoy/Ventoy](https://github.com/ventoy/Ventoy).
* **GRUB2**: Copyright (C) Free Software Foundation, Inc. Licensed under GPL-3.0.
* **Disclaimer**: FlashCore is an independent open-source project. It is not affiliated with, endorsed by, or sponsored by Microsoft, Canonical, or the Ventoy project.
* **Data Loss Warning**: Flashing an image permanently overwrites data on the target USB storage device. Always confirm target drive capacity and serial numbers before flashing.
