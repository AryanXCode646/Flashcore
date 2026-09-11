# ⚡ FlashCore

> **Non-root bootable USB creator for Android via USB OTG.**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL_v3-blue.svg)](LICENSE)
[![CI](https://github.com/ashishsinghbora/Flashcore/actions/workflows/ci.yml/badge.svg)](https://github.com/ashishsinghbora/Flashcore/actions/workflows/ci.yml)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-green.svg)](https://developer.android.com)
[![JDK](https://img.shields.io/badge/JDK-21-red.svg)](https://adoptium.net)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2+-purple.svg)](https://kotlinlang.org)
[![Tests](https://img.shields.io/badge/Tests-80%2B%20Passing-brightgreen.svg)]()
[![Documentation](https://img.shields.io/badge/Docs-Architecture%20%7C%20Limitations-orange.svg)](ARCHITECTURE.md)

**FlashCore** is an Android utility designed to turn an Android device into a PC rescue toolkit. It communicates directly with USB flash drives over USB OTG using Android's USB Host API and raw SCSI Bulk-Only Transport (BOT) protocols — **without requiring root privileges.**

---

## 🧭 The FlashCore Philosophy: Trustworthy over Feature-Rich

Flashing operating systems over USB OTG is low-level, high-consequence systems programming. Corrupting a single sector or miscalculating partition alignment produces unbootable media or corrupts flash drives.

FlashCore rejects the hype trap. We do **not** prioritize flashy graphs, network ISO downloads, SD cards, or AI features. 

Our guiding principle is **evidence over claims**:
> *"FlashCore safely writes an image to USB, handles disconnects, verifies every single block, has automated tests, and is validated across real USB devices."*

### Engineering Priorities
1. 🥇 **Correctness:** Bit-for-bit exactness in sector writing and verification.
2. 🥈 **Safety:** Hardened disconnect handling (`ACTION_USB_DEVICE_DETACHED`) and target drive validation.
3. 🥉 **Testability:** 100% of core logic runs offline via pure `BlockDevice` abstractions.
4. **USB Reliability:** SCSI BOT stall recovery, retry loops, and sense error handling.
5. **Block-Device Abstraction:** Zero coupling between UI/engines and Android hardware APIs.
6. **Partition Correctness:** Strict GPT/MBR alignment, CRC32 checks, and protective structures.
7. **Filesystem Correctness:** Fully conforming FAT32/ISO structures, directory records, and cluster maps.
8. **Linux Flashing:** Production-grade hybrid streaming with bit-for-bit target read-back verification.
9. **Windows Flashing:** UEFI FAT32 extraction and dynamic WIM chunking.
10. **Ventoy:** Compliant multi-boot dual-partitioning and non-destructive updating.
11. **Android UX:** Foreground service (`dataSync`), notification cancellation, process death persistence.
12. **Performance Optimization:** Direct ring buffer decoupling and GC pause reduction.
13. **Release Engineering:** Automated CI/CD, lint checks, test suites, reproducible builds, and signed releases.

---

## 🏛️ System Architecture

FlashCore enforces a strict downward dependency flow where UI components never speak to USB hardware directly:

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

| Component | Status | Details |
| :--- | :---: | :--- |
| **Linux Hybrid (Raw DD)** | 🟢 **Production-Grade** | 8-stage pipeline: validation, checksum pre-flight, raw streaming, cache flush (`SYNCHRONIZE_CACHE_10`), and bit-for-bit target read-back verification (`FlashVerifier`). |
| **Windows UEFI Flasher** | 🟡 **Engine Implemented** | Dynamic capability detection (x64/ARM64/IA32), WIM/SWM chunking, FAT32 cluster writing, BCD/bootloader provisioning, and binary PE verification. Physical PC firmware testing ongoing. |
| **Ventoy Multi-Boot Engine** | 🟡 **Engine Implemented** | Dual-partition MBR/GPT layout, 32 MB VTOYEFI asset provider, FAT32 data volume, filesystem ISO storage, and non-destructive update. Physical PC firmware testing ongoing. |
| **Non-Root USB Mass Storage Driver** | 🟢 **Production-Grade** | SCSI Bulk-Only Transport (BOT) via Android `UsbManager` (CBW, CSW, INQUIRY, READ_CAPACITY_10/16, READ_10/16, WRITE_10/16). |
| **Target Read-Back Verification** | 🟢 **Production-Grade** | Real bit-for-bit physical sector read-back pass (`FlashVerifier`) with exact LBA error pinpointing and dual SHA-256 validation. |
| **Block Device Test Framework** | 🟢 **Production-Grade** | In-memory sparse, file-backed, and fault-injecting block devices with 80+ automated offline unit tests (`./gradlew test`). |
| **FAT32 Filesystem Writer** | 🟢 **Production-Grade** | Full cluster allocator, directory parser, VBR/FSInfo writer, mkdir, createFile, and streaming file writer (`Fat32Writer`). |
| **ISO Filesystem Engine** | 🟢 **Production-Grade** | ISO 9660 & Joliet volume descriptor parser, directory tree reader, and streaming file extractor (`IsoFilesystemReader`). |
| **Android Production Engineering** | 🟢 **Production-Grade** | ForegroundService (`dataSync`), user cancellation action, `SavedStateHandle` process death recovery, OTG disconnect handling, persistable SAF URIs, and 1GB–64GB benchmarking suite. |
| **SPSC Direct Ring Buffer** | 🟢 **Production-Grade** | Off-heap `ByteBuffer.allocateDirect` circular buffer for producer-consumer I/O rate decoupling. |

See [`LIMITATIONS.md`](LIMITATIONS.md) for transparent hardware boundaries and firmware considerations.

---

## 📂 Multi-Module Roadmap & Package Namespace

To ensure long-term maintainability, the project is structured to transition from a single application module into modular subprojects with a clean domain namespace:

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

> **Namespace Migration:** Legacy internal packages under `com.example.*` are being migrated to `com.ashishsinghbora.flashcore` across modules to reflect production-grade project ownership.

---

## 📱 System Requirements

* **Android Version:** Android 8.0 (API Level 26) or higher (tested up to Android 15 / API 36).
* **Hardware:** USB On-The-Go (OTG) support.
* **Accessories:** USB Type-C or Micro-USB OTG adapter + USB flash drive.
* **Root Privileges:** **None.** Operates entirely within standard Android user-space USB Host permissions.

---

## 🔨 Building and Testing from Source

### Prerequisites
1. **JDK 21** (Eclipse Temurin recommended)
2. **Android SDK Platform API 36**
3. **Android Build Tools 36.0.0+**

### Local Verification Pipeline
Before submitting code, run the standard quality verification pipeline:

```bash
# 1. Run Android Lint
./gradlew lint

# 2. Run automated test suite (80+ unit and Robolectric tests)
./gradlew test

# 3. Assemble Debug APK
./gradlew assembleDebug

# 4. Assemble Release APK
./gradlew assembleRelease
```

---

## 🔄 Reproducible Builds & Verification

FlashCore supports deterministic, reproducible builds. Anyone building the source code with the reference toolchain can reproduce bit-for-bit identical release APKs.

Every official GitHub Release includes:
- Signed Release APK (`flashcore-vX.Y.Z-release.apk`)
- SHA-256 Checksums (`SHA256SUMS.txt`)

To verify the integrity of a downloaded release:
```bash
sha256sum -c SHA256SUMS.txt
```

Read [`REPRODUCIBLE_BUILDS.md`](REPRODUCIBLE_BUILDS.md) for full reproduction steps and `diffoscope` verification details.

---

## 🤝 Contributing

Contributions, bug reports, and hardware compatibility reports are welcome! 

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
* **Disclaimer**: FlashCore is an independent open-source implementation. It is not affiliated with, endorsed by, or sponsored by Microsoft, Canonical, or the Ventoy project.
* **Data Loss Warning**: Flashing an image permanently overwrites existing data on the chosen USB device. Always confirm target drive capacity and serial numbers before proceeding.
