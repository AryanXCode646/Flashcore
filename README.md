# ⚡ FlashCore

> **High-performance, non-root bootable USB creator for Android via USB OTG.**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL_v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-purple.svg)](https://kotlinlang.org)
[![NDK](https://img.shields.io/badge/NDK-C%2B%2B17-orange.svg)](https://developer.android.com/ndk)
[![Status](https://img.shields.io/badge/Status-Beta-brightgreen.svg)]()

**FlashCore** turns your Android phone into an emergency PC rescue toolkit. Format, flash, and create bootable USB media for **GNU/Linux, Windows 10/11 UEFI, and Ventoy Multi-Boot** directly over a USB OTG adapter — **no root privileges, external PCs, or cloud dependencies required.**

---

## 📑 Table of Contents
- [Key Features](#-key-features)
- [Architecture & Tech Stack](#-architecture--tech-stack)
- [How It Works](#-how-it-works)
- [System Requirements](#-system-requirements)
- [Project Structure](#-project-structure)
- [Building from Source](#-building-from-source)
- [Supported Formats & Images](#-supported-formats--images)
- [Roadmap](#-roadmap)
- [Contributing](#-contributing)
- [License & Disclaimers](#-license--disclaimers)

---

## 🚀 Key Features

* 🐧 **Linux Raw Hybrid Flasher:** Direct sector-by-sector raw streaming (`dd` equivalent) with chunked checksums for Arch Linux, Ubuntu, Fedora, Debian, Pop!_OS, Void Linux, and Raspberry Pi images.
* 🪟 **Windows UEFI & Auto-WIM Splitter:**
  * Auto-formats UEFI-compliant FAT32/NTFS partition structures.
  * Native NDK bridge utilizing cross-compiled `wimlib` (C++17) to inspect `sources/install.wim` and automatically split files exceeding 4 GB into `.swm` chunks on the fly.
* 🧰 **Ventoy Multi-Boot Engine:** Partition drives with the official Ventoy layout (VTOYEFI bootloader + exFAT storage) to store and boot multiple ISOs from a single flash drive.
* ⚡ **Non-Root USB Mass Storage (SCSI BOT):** Communicates directly with USB flash drives using Android's USB Host API (`UsbManager` / `UsbEndpoint`) over raw SCSI Bulk-Only Transport protocols without requiring root or custom kernels.
* 🔄 **High-Throughput Direct Ring Buffer:** Lock-free / Direct Memory allocation (`ByteBuffer.allocateDirect` / native pointers) for continuous producer-consumer I/O, preventing GC pauses and buffer stalls.
* 🛡️ **Resilient Background Service:** Android 14+ compatible `ForegroundService` with `PARTIAL_WAKE_LOCK`, dynamic write-speed metering (MB/s), LBA progress tracking, and safe OTG unmount handling.

---

## 🛠️ Architecture & Tech Stack

```
[ UI Layer (Jetpack Compose / Material 3 / Coroutines StateFlow) ]
                               │
[ Flasher State Machine (FSM) & Strategy Engine ]
      ┌────────────────────────┼────────────────────────┐
      ▼                        ▼                        ▼
[ LinuxRawDdStrategy ]   [ WindowsUefiStrategy ]  [ VentoyStrategy ]
      │                        │ (wimlib JNI)           │
      └────────────────────────┼────────────────────────┘
                               ▼
            [ High-Throughput Direct Ring Buffer ]
                               │
            [ Non-Root SCSI BOT USB Controller ]
                               │
                  [ USB OTG Flash Drive ]
```

| Subsystem | Technology / Library | Purpose |
| :--- | :--- | :--- |
| **UI & Presentation** | Jetpack Compose + Material 3 | Modern, dark-mode-first reactive UI with live progress graphs |
| **Concurrency & Lifecycle**| Kotlin Coroutines + Flow + WorkManager | Background execution and UI state synchronization |
| **USB Communication** | Android USB Host API + Custom SCSI Engine | Raw sector I/O via Bulk-Only Transport (BOT) |
| **Native Processing** | Android NDK + C++17 + CMake | High-speed byte streaming and memory mapping (`mmap`) |
| **WIM Splitting** | `wimlib` (cross-compiled for Android) | Windows ISO extraction and `.swm` chunking |
| **Data Integrity** | Rolling SHA-256 / MurmurHash3 | Real-time block checksumming and sector validation |

---

## ⚙️ How It Works

1. **Device Enumeration:** The app detects an OTG-connected USB drive and requests user permission via `android.hardware.usb.UsbManager`.
2. **SCSI Handshake:** Negotiates logical block addressing (LBA) and sector sizing (512B vs. 4096B) using `INQUIRY` and `READ_CAPACITY_10/16` SCSI commands.
3. **Flashing Pipeline:**
   - **Linux Mode:** The ISO file is opened in streaming mode and pushed into a native Direct Ring Buffer, sending `WRITE_10` SCSI command blocks directly to Sector 0.
   - **Windows Mode:** Partitions the drive, formats FAT32, mounts ISO filesystem structures, extracts boot files, and passes `install.wim` to `wimlib` to split into `<4 GB` chunks.
   - **Ventoy Mode:** Writes the master boot record (MBR) and VTOYEFI partition images, formatting the remainder as an accessible exFAT data partition.

---

## 📱 System Requirements

* **Android Version:** Android 8.0 (API Level 26) or higher.
* **Hardware:** USB On-The-Go (OTG) support.
* **Accessories:** USB Type-C / Micro-USB OTG adapter + USB Flash Drive (8 GB+ recommended).
* **Root Required:** **No.** Operates entirely via standard Android USB Host permissions.

---

## 📂 Project Structure

```text
FlashCore/
├── app/                      # Application entry point & Manifest
├── core-usb/                 # USB Host API & SCSI BOT communication
│   ├── scsi/                 # CommandBlockWrapper, CommandStatusWrapper, CDB Builders
│   ├── driver/               # UsbMassStorageDriver implementation
│   └── buffer/               # DirectByteBuffer Concurrent Ring Buffer
├── core-flasher/             # Strategy pattern & state machine logic
│   ├── fsm/                  # FlasherStateMachine & Lifecycle events
│   └── strategies/           # Linux, Windows UEFI, and Ventoy flashers
├── core-native/              # C++ NDK Layer
│   ├── CMakeLists.txt        # Native build definitions
│   ├── cpp/                  # JNI bindings for sector writes and mmap
│   └── third_party/          # Cross-compiled wimlib source
└── feature-ui/               # Jetpack Compose screens, ViewModels, and navigation
```

---

## 🔨 Building from Source

### Prerequisites
1. **Android Studio Ladybug (or newer)**
2. **Android SDK & NDK** (NDK version `r26b` or higher)
3. **CMake** (3.22.1+)
4. **JDK 17 or 21**

### Clone & Build
```bash
# Clone the repository recursively (including submodules)
git clone --recursive https://github.com/your-username/FlashCore.git
cd FlashCore

# Build the native NDK libraries and assemble Debug APK
./gradlew assembleDebug

# Install to connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 💿 Supported Formats & Images

* **GNU/Linux:** Ubuntu, Arch Linux, Fedora, Debian, Linux Mint, Kali Linux, Manjaro, Pop!_OS, Alpine, Void Linux, Raspberry Pi OS.
* **Microsoft Windows:** Windows 11, Windows 10, Windows Server (UEFI-bootable ISOs).
* **Rescue & Utility:** Ventoy, MemTest86, Clonezilla, SystemRescue, GParted Live.

---

## 🗺️ Roadmap

- [x] Non-root SCSI BOT mass storage sector writer.
- [x] Raw hybrid Linux ISO streaming with live throughput telemetry.
- [x] Android NDK `wimlib` integration for Windows `install.wim` splitting.
- [x] Ventoy multi-boot partition scaffolding.
- [ ] Direct network ISO downloading directly to USB (bypassing phone internal storage).
- [ ] SD Card / MicroSD adapter flashing support.
- [ ] ISO hash / checksum auto-verifier against official distribution mirrors.

---

## 🤝 Contributing

Contributions, bug reports, and feature requests are welcome!

1. Fork the Project.
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`).
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`).
4. Push to the Branch (`git push origin feature/AmazingFeature`).
5. Open a Pull Request.

---

## ⚖️ License & Disclaimers

Distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See [`LICENSE`](LICENSE) for details.

* **Disclaimer:** Formatting a USB flash drive will permanently erase all existing data on the target storage device. Ensure proper drive selection before confirming operations. The developers are not responsible for accidental data loss.
* *Windows is a registered trademark of Microsoft Corporation. Ventoy is an open-source project by longpanda.*
