# 📋 Changelog

All notable changes to the FlashCore project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned
- Physical hardware validation matrix testing across diverse USB flash drive controllers (Phison, SMI, Alcor, Innostor) and Android OEM devices.
- Hardening of partial USB transfer caller validation and error recovery on timeout.
- Multi-module project restructuring (`:core`, `:flashers`, `:app`).
- Complete package namespace migration from `com.example.*` to `com.ashishsinghbora.flashcore`.
- Official signed v1.0.0 release publication on GitHub with verifiable `SHA256SUMS.txt`.

---

## [1.0.0-rc1 - Initial Engineering Baseline] - 2026-09-11

> **Milestone Status:** Engineering and software implementation complete; 93 automated test methods present on abstract block devices. Physical hardware matrix validation and public release publication pending.

### Added
- **Phase 10 — Release & CI Infrastructure:**
  - Automated GitHub Actions CI workflow running `./gradlew lint`, `./gradlew test`, and `./gradlew assembleDebug`.
  - Automated GitHub Actions Release workflow with keystore signing and SHA-256 checksum generation (`SHA256SUMS.txt`).
  - Reproducible build guide (`REPRODUCIBLE_BUILDS.md`) detailing deterministic toolchain setup and verification instructions.
  - GitHub community health templates: Bug Report form, Feature Request form, Hardware Test Report form, Discussions config, and Pull Request template.
  - System architecture document (`ARCHITECTURE.md`) documenting layer decomposition and planned modularization.
  - Security Policy (`SECURITY.md`), Contributor Guide (`CONTRIBUTING.md`), and Code of Conduct (`CODE_OF_CONDUCT.md`).
- **Phase 9 — Performance Benchmarking Framework:**
  - Standardized benchmark harness evaluating streaming performance with synthetic workloads across 1 GB, 4 GB, 8 GB, 16 GB, 32 GB, and 64 GB boundaries.
  - Telemetry capture hooks for throughput (average, peak, min MB/s), CPU utilization, memory footprint (JVM heap vs native heap), GC pause tracking, and Android thermal status.
- **Phase 8 — Android Engineering & Foreground Management:**
  - Android 14+ compliant `FlashForegroundService` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` and `PARTIAL_WAKE_LOCK`.
  - Actionable notification support allowing user cancellation via `ACTION_CANCEL_FLASH`.
  - State persistence and process recreation resilience via `SavedStateHandle` in `FlasherViewModel`.
  - Storage Access Framework (SAF) URI persistence retaining access across reboots.
  - Dynamic `UsbManager.ACTION_USB_DEVICE_DETACHED` broadcast receiver to halt I/O safely on physical cable disconnects.
- **Phase 7 — Target Read-Back Verification:**
  - Target-sector read-back verification engine (`FlashVerifier`) with software validation against block-device test doubles (physical media validation pending).
  - Cache synchronization step (`SYNCHRONIZE_CACHE_10`) before verification.
  - Exact LBA offset and byte index pinpointing on data mismatch.
  - Dual SHA-256 digest comparison (source image hash vs target device hash).
- **Phase 6 — Ventoy Multi-Boot Integration:**
  - Dual-partition geometry generation complying with the Ventoy specification: Partition 1 (FAT32 Data volume) and Partition 2 (32 MiB VTOYEFI volume).
  - Modular asset provider (`VentoyAssetProvider`) supporting offline generator and upstream `ventoy.disk.img` import.
  - Non-destructive update engine (`NON_DESTRUCTIVE_UPDATE`) preserving existing user ISO files in Partition 1.
  - Filesystem-level ISO image storage into `/ISO/` directories.
- **Phase 5 — Windows UEFI Boot Engine:**
  - Windows ISO architecture detector (x64, ARM64, IA32) inspecting directory structures and bootloader signatures.
  - On-the-fly WIM chunking engine (`WimChunker`) splitting large `install.wim` files into `< 4 GB` `.swm` chunks to conform with FAT32 limits.
  - UEFI bootloader (`bootx64.efi`) and BCD registry hive provisioning.
  - Binary PE `MZ` and registry `regf` signature verification.
- **Phase 4 — ISO 9660 & Joliet Filesystem Reader:**
  - Streaming ISO reader (`IsoFilesystemReader`) traversing Primary Volume Descriptors, directory records, and Joliet UCS-2 extensions.
  - Streaming file extraction into FAT32 filesystem structures.
- **Phase 3 — FAT32 Filesystem Writer:**
  - Custom FAT32 writer (`Fat32Writer`) with cluster allocator, File Allocation Tables (FAT1/FAT2), VBR, and FSInfo sector generator.
  - Directory management supporting `mkdir`, `createFile`, and streaming file writes.
- **Phase 2 — Partition Subsystem:**
  - MBR builder supporting standard boot indicators and partition IDs.
  - GPT builder supporting Protective MBR, Primary/Backup GPT headers, and 128 partition entries with 1 MiB boundary alignment and dynamic CRC32 computation.
- **Phase 1 — Abstract Block Device Layer:**
  - Hardware abstraction contract (`BlockDevice`) isolating storage I/O from Android APIs.
  - In-memory sparse block device (`MemoryBlockDevice`) for rapid offline testing.
  - File-backed block device (`FileBackedBlockDevice`) for persistent disk testing.
  - Fault-injecting block device (`FaultInjectingBlockDevice`) for simulating physical sector failures, short transfers, and I/O timeouts.
- **Phase 0 — Repository Baseline & Setup:**
  - Modernized Gradle build setup, dependencies, and test harness.
  - Established 93 automated test methods in software-only environments.
