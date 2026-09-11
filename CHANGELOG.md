# 📋 Changelog

All notable changes to the FlashCore project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned
- SCSI `WRITE_16` / `READ_16` 64-bit LBA command execution hardening for drives > 2 TiB.
- USB partial transfer retry loop and BOT stall recovery hardening across rare USB controller firmware quirks.
- Multi-module project restructuring (`:core`, `:flashers`, `:app`).
- Complete package namespace migration to `com.ashishsinghbora.flashcore`.

---

## [1.0.0] - 2026-09-11

### Added
- **Phase 10 — Release Engineering:**
  - Automated GitHub Actions CI workflow running `./gradlew lint`, `./gradlew test`, and `./gradlew assembleDebug`.
  - Automated GitHub Actions Release workflow with keystore signing, SHA-256 checksum verification (`SHA256SUMS.txt`), and asset publication.
  - Reproducible build documentation (`REPRODUCIBLE_BUILDS.md`) detailing deterministic toolchains, JDK 21, and APK verification.
  - Full suite of GitHub community health templates: Bug Report form, Feature Request form, Hardware Test Report form, Discussions config, and Pull Request template.
  - Architecture blueprint (`ARCHITECTURE.md`) documenting the 7-tier defensible layered architecture and modularization roadmap.
  - Comprehensive Security Policy (`SECURITY.md`), Contributor Guide (`CONTRIBUTING.md`), and Code of Conduct (`CODE_OF_CONDUCT.md`).
- **Phase 9 — Performance Benchmarking Suite:**
  - Standardized benchmark harness evaluating streaming performance across 1 GB, 4 GB, 8 GB, 16 GB, 32 GB, and 64 GB workloads.
  - Telemetry capture for throughput (average, peak, min MB/s), CPU utilization (user/kernel elapsed time), memory footprint (JVM heap vs native heap), GC pause frequencies, and Android thermal headroom status.
- **Phase 8 — Android Production Engineering:**
  - Android 14+ compliant `FlashForegroundService` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` and `PARTIAL_WAKE_LOCK`.
  - Actionable notification support allowing user cancellation via `ACTION_CANCEL_FLASH`.
  - State persistence and process recreation resilience via `SavedStateHandle` in `FlasherViewModel`.
  - Persistable Storage Access Framework (SAF) URI permissions retaining access across reboots.
  - Dynamic `UsbManager.ACTION_USB_DEVICE_DETACHED` broadcast receiver handling OTG power loss and physical cable disconnection gracefully.
- **Phase 7 — Target Read-Back Verification:**
  - Bit-for-bit physical sector read-back verification engine (`FlashVerifier`).
  - Cache synchronization pass (`SYNCHRONIZE_CACHE_10`) ensuring volatile controller write buffers commit to physical NAND before verification.
  - Exact LBA offset and byte index pinpointing for data mismatch detection.
  - Dual SHA-256 digest comparison (source image hash vs physical target hash).
- **Phase 6 — Ventoy Multi-Boot Integration:**
  - Dual-partition geometry generation complying strictly with the Ventoy specification: Partition 1 (FAT32 Data volume) and Partition 2 (32 MiB VTOYEFI volume).
  - Modular asset provider (`VentoyAssetProvider`) with offline compliant UEFI/MBR bootloader generation and upstream `ventoy.disk.img` import capabilities.
  - Non-destructive update engine (`NON_DESTRUCTIVE_UPDATE`) preserving existing user ISO files in Partition 1 while upgrading Sector 0 boot code and VTOYEFI assets.
  - Filesystem-level ISO image storage into `/ISO/` directories.
- **Phase 5 — Windows UEFI Boot Engine:**
  - Dynamic Windows ISO architecture detector (x64, ARM64, IA32) inspecting directory structures and bootloader signatures.
  - On-the-fly WIM chunking engine (`WimChunker`) splitting large `install.wim` files into `< 4 GB` `.swm` chunks to conform with FAT32 file size limits.
  - Automatic UEFI bootloader (`bootx64.efi`) and BCD registry hive provisioning.
  - Binary PE `MZ` and registry `regf` signature verification.
- **Phase 4 — ISO 9660 & Joliet Filesystem Reader:**
  - High-performance ISO reader (`IsoFilesystemReader`) traversing Primary Volume Descriptors, directory records, and Joliet UCS-2 extensions.
  - Direct streaming file extraction decoupling disk reading from memory exhaustion.
- **Phase 3 — Real FAT32 Filesystem Writer:**
  - Production-grade FAT32 writer (`Fat32Writer`) with cluster allocator, File Allocation Tables (FAT1/FAT2), VBR, and FSInfo sector generator.
  - Directory management supporting `mkdir`, `createFile`, and incremental cluster streaming.
- **Phase 2 — Partition Subsystem:**
  - MBR builder supporting standard boot indicators and partition IDs.
  - GPT builder supporting Protective MBR, Primary/Backup GPT headers, and 128 partition entries with 1 MiB boundary alignment and dynamic CRC32 computation.
- **Phase 1 — Abstract Block Device Layer:**
  - Hardware abstraction contract (`BlockDevice`) isolating storage I/O from Android APIs.
  - In-memory sparse block device (`MemoryBlockDevice`) for rapid offline testing.
  - File-backed block device (`FileBackedBlockDevice`) for persistent disk testing.
  - Fault-injecting block device (`FaultInjectingBlockDevice`) for simulating physical sector read/write failures, short transfers, and I/O timeouts.
- **Phase 0 — Repository Baseline & Cleanup:**
  - Modernized Gradle build setup, dependencies, and test harness.
  - Established 80+ unit test suite passing with 100% reliability.
