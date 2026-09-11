# 🤝 Contributing to FlashCore

Thank you for your interest in contributing to FlashCore! We welcome contributions, bug reports, documentation enhancements, and hardware test reports.

---

## 🎯 Our Philosophy: Evidence over Claims

FlashCore is an open-source USB flashing utility where reliability is paramount. Corrupting a user's USB flash drive or creating an unbootable OS image damages trust.

Therefore:
- We prioritize **correctness, safety, and testability** over rapid accumulation of features.
- We value **automated tests and physical hardware validation** above speculative implementations.
- Every contribution touching block devices, partition tables, filesystems, or flashing strategies must include automated test coverage using the `BlockDevice` abstraction.

---

## 🛠️ Development Environment & Setup

### Prerequisites
- **Java Development Kit:** JDK 21 (Eclipse Temurin or OpenJDK recommended)
- **Android SDK:** Platform API Level 36, Build-Tools 36+
- **IDE:** Android Studio Ladybug (or newer), or VS Code with Kotlin and Gradle extensions
- **Build System:** Gradle 9.7+ (via `./gradlew` wrapper)

### Initial Setup
```bash
# Clone the repository
git clone https://github.com/ashishsinghbora/Flashcore.git
cd Flashcore

# Set up environment variables (adjust paths as needed)
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
```

---

## 🧪 Verification & Local Testing

Before submitting any Pull Request, you **MUST** run and pass the following three verification steps locally:

```bash
# 1. Static code analysis and Android lint checks
./gradlew lint

# 2. Complete unit, Robolectric, and Roborazzi test suite
./gradlew test

# 3. Assemble debug APK
./gradlew assembleDebug
```

All automated unit tests (107 unit/Robolectric tests) must pass with zero failures. If modifying code that affects UI screenshots, update Roborazzi test baselines accordingly.

---

## 🏗️ Architectural Rules

When contributing code, adhere strictly to the layered architecture described in [`ARCHITECTURE.md`](ARCHITECTURE.md):

1. **No Hardware Direct Access in UI:** The UI and ViewModel layers must **never** reference `UsbMassStorageDriver`, `UsbEndpoint`, or raw SCSI CDBs directly. They communicate exclusively through the `FlashEngineStrategy` and `BlockDevice` interfaces.
2. **Mock-First Hardware I/O:** Any logic that writes or reads sectors must work against the `BlockDevice` interface. Use `MemoryBlockDevice` or `FaultInjectingBlockDevice` in unit tests.
3. **No Unbounded Memory Allocations:** ISO images can range from 1 GB to 64 GB+. Never load whole files into memory. Stream data in bounded chunks (e.g. 64 KiB – 1 MiB) via `DirectRingBuffer` or streaming channels.
4. **Resilient Cancellation:** Long-running I/O operations must poll the cooperative `cancellationSignal` periodically and gracefully abort without leaving lingering background threads.

---

## 🔌 Hardware Testing & Reporting

Hardware validation on physical USB flash drives and PC motherboards is vital to the project. If you have tested FlashCore on real hardware, please submit a **Hardware Compatibility Report** using our issue template:

- **Android Device:** Model name, manufacturer, Android OS version.
- **OTG Adapter:** Brand and type (Type-C, Micro-USB, powered hub).
- **USB Drive:** Vendor, model, capacity, USB controller (e.g. Phison, SMI, SanDisk, Samsung).
- **Target OS Tested:** ISO name, version, architecture (x86_64, ARM64).
- **Host PC Boot Result:** Motherboard model, BIOS/UEFI mode, boot success/failure details.

---

## 🌿 Git Workflow & Pull Request Process

1. **Fork the Repository:** Create your own fork and branch from `main`.
2. **Branch Naming:**
   - `fix/issue-description` for bug fixes.
   - `feat/feature-name` for enhancements.
   - `docs/topic` for documentation updates.
   - `test/test-description` for test additions.
3. **Commit Messages:**
   - Use clear, concise commit messages following Conventional Commits (e.g., `feat(scsi): add SCSI WRITE_16 command support`, `fix(fat32): fix cluster allocation off-by-one`).
4. **PR Checklist:**
   - [ ] `./gradlew lint` passes with 0 errors.
   - [ ] `./gradlew test` passes with 100% success rate.
   - [ ] `./gradlew assembleDebug` compiles cleanly.
   - [ ] Unit tests added or updated for new functionality.
   - [ ] Relevant documentation updated (`README.md`, `ARCHITECTURE.md`, `LIMITATIONS.md`).
   - [ ] Pull request description links any relevant GitHub issue.

---

## 📜 Code of Conduct

All contributors are expected to adhere to our [Code of Conduct](CODE_OF_CONDUCT.md). Please be respectful and constructive in discussions, code reviews, and issue threads.
