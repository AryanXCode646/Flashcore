# 🔒 Security Policy

FlashCore takes the security, stability, and integrity of low-level hardware operations seriously. Because FlashCore directly manipulates raw storage media over USB On-The-Go (OTG), security boundaries and safety invariants are central to its design.

---

## 🛡️ Supported Versions

Only the latest active development branch and recent tagged releases receive active security updates.

| Version | Supported | Notes |
| :--- | :---: | :--- |
| `main` branch (pre-release) | ✅ | Active development & security fixes |
| `< 1.0.0` (alphas / prototypes) | ❌ | Deprecated prototype builds |

---

## 🎯 Threat Model & Security Boundaries

FlashCore operates under a well-defined security perimeter:

### 1. Non-Root Principle & Sandbox Isolation
- FlashCore **never** requests or requires root (`su`) privileges.
- All hardware interactions are mediated strictly via the Android USB Host API (`android.hardware.usb.UsbManager`).
- The application relies on standard Android runtime permissions and Storage Access Framework (SAF) scoped URI access. No arbitrary external filesystem browsing is performed without explicit user consent.

### 2. Physical Storage & Destructive Write Safeguards
- Flashing an operating system is inherently destructive to the target drive. To prevent accidental data loss:
  - FlashCore queries device descriptors (`INQUIRY`, `READ_CAPACITY`) to identify external USB drives specifically.
  - The internal storage of the Android device is isolated from the USB Host API and cannot be targeted.
  - Clear confirmation dialogs display target vendor, model, capacity, and serial number before destructive operations commence.
  - Dynamic monitoring of `ACTION_USB_DEVICE_DETACHED` terminates write loops immediately if a disconnect occurs, preventing corrupted orphan writes.

### 3. Untrusted Input Handling (ISOs, Images, and WIM Files)
- ISO images and WIM archives are treated as untrusted binary inputs.
- All filesystem parsers (`IsoFilesystemReader`, `Fat32Writer`, `WimChunker`) implement boundary checking to mitigate buffer overflows, integer wrap-arounds, directory traversal attacks (`../`), and malicious El Torito boot records.
- Verification passes (`FlashVerifier`) validate image integrity via SHA-256 digests.

### 4. Memory Safety & Buffer Management
- Ring buffers allocating direct memory (`ByteBuffer.allocateDirect`) are bounded with strict capacity limits to avoid Out-Of-Memory (OOM) denial-of-service.
- Thread-safe synchronization models prevent race conditions and concurrent write hazards.

---

## 🚨 Reporting a Vulnerability

If you discover a security vulnerability or critical data-loss defect in FlashCore, please do **NOT** report it via public GitHub issues or discussions.

Instead, please report it privately:

1. **Email:** Send details to `ashishsinghbora@users.noreply.github.com` (or project maintainers via GitHub Security Advisories).
2. **Include in Report:**
   - Detailed description of the vulnerability.
   - Steps to reproduce or proof-of-concept (PoC).
   - Affected Android versions, device models, and USB flash drive controllers (if hardware-specific).
   - Potential impact (e.g. data corruption, app crash, sandbox escape).

### Response Timeline
- **Initial Acknowledgment:** Within **48 hours**.
- **Assessment & Triage:** Within **7 days**.
- **Fix & Public Disclosure:** Within **30 days** (coordinated disclosure following release of a patch).

---

## ⚖️ Responsible Disclosure

We appreciate the efforts of security researchers and open-source contributors who adhere to responsible disclosure. Maintainers will credit reporters in release notes and changelogs unless requested otherwise.
