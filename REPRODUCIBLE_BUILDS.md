# 🔄 Reproducible Builds Guide

FlashCore strives to ensure deterministic, verifiable, and reproducible builds. Anyone building the project from source under the reference environment should obtain bit-for-bit identical binary artifacts (APKs).

> [!NOTE]
> **Release Status Notice:**
> Official public release tags (e.g. `v1.0.0`) have not yet been published on GitHub. The instructions below describe how to reproduce builds from the current development baseline (`main` branch) and outline the verification process that will apply to all future official tagged releases.

---

## 🎯 Why Reproducible Builds Matter

In a low-level utility that executes raw block writes to USB storage over USB OTG, users must be able to verify that the distributed APK binary matches the public, audited source code on GitHub without injected backdoors, telemetry, or third-party tampering.

---

## 🛠️ Reference Build Environment

To reproduce FlashCore binaries deterministically, use the identical toolchain:

| Tool | Reference Version |
| :--- | :--- |
| **Operating System** | Linux x86_64 (Ubuntu 22.04 / 24.04 or Arch Linux) |
| **Java Development Kit** | Eclipse Temurin JDK `21.0.6+7` (`x64`) |
| **Gradle** | `9.7.1` (enforced via `./gradlew`) |
| **Android Gradle Plugin** | `8.8.0` |
| **Kotlin** | `2.2.0` |
| **Android SDK Platform** | API Level 36 (`compileSdk = 36`) |
| **Android Build Tools** | `36.0.0` |

---

## ⚙️ Deterministic Build Configurations

FlashCore applies several Gradle settings to eliminate non-deterministic build inputs:

1. **Fixed Timestamps (`SOURCE_DATE_EPOCH`):**
   - ZIP entries and APK zip-flinger records use deterministic timestamps.
2. **Deterministic File Sorting:**
   - Resource and asset packaging sorts files alphabetically.
3. **Locale & Encoding Invariance:**
   - Builds enforce `-Dfile.encoding=UTF-8 -Duser.country=US -Duser.language=en`.
4. **Stripped Debug Metadata:**
   - Release builds strip local file paths and debug symbol maps.

---

## 🔨 Reproduction Instructions

### 1. Check out the Source Code
For development verification:
```bash
git clone https://github.com/ashishsinghbora/Flashcore.git
cd Flashcore
git checkout main
```

For future official releases:
```bash
git checkout tags/vX.Y.Z
```

### 2. Verify JDK Environment
Ensure your `JAVA_HOME` points to Eclipse Temurin JDK 21:
```bash
$JAVA_HOME/bin/java -version
# Expected: openjdk version "21.0.6" ... Temurin
```

### 3. Build the Unsigned Release APK
Build the release APK without signing:
```bash
./gradlew clean assembleRelease \
  -Dorg.gradle.java.home="$JAVA_HOME" \
  -Dfile.encoding=UTF-8 \
  -Duser.country=US \
  -Duser.language=en
```

The resulting artifact is located at:
`app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 🔍 Verification & Comparison

### Compare SHA-256 Checksums
Compute the SHA-256 hash of your reproduced artifact:
```bash
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
```

When official releases are published, compare this digest against the published `SHA256SUMS.txt` on the corresponding GitHub Release.

### Deep Inspection with `diffoscope`
To inspect any differences (e.g. metadata or signing records):
```bash
diffoscope official-release.apk reproduced-release.apk
```
