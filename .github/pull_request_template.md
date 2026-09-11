## 📌 Description
<!-- Provide a clear, concise description of the changes proposed in this Pull Request. -->

Fixes #(issue)

---

## 🏛️ Architectural Layer Impacted
<!-- Please select the primary layer modified by this PR (see ARCHITECTURE.md) -->
- [ ] Layer 1: UI & API Layer (`app/src/main/java/.../ui`, `service`)
- [ ] Layer 2: Flash Engine & Strategy Interface (`flasher`)
- [ ] Layer 3: Flashing Strategies (`linux`, `windows`, `ventoy`)
- [ ] Layer 4: Filesystem Layer (`iso`, `fat32`)
- [ ] Layer 5: Partition Layer (`gpt`, `mbr`)
- [ ] Layer 6: Block Device Abstraction (`BlockDevice`, mock devices)
- [ ] Layer 7: Hardware Transport (`scsi`, `usb`, BOT driver)
- [ ] Build & Release Engineering (Gradle, CI/CD, documentation)

---

## 🧪 Verification & Quality Checklist
<!-- All checks are REQUIRED before merging. -->
- [ ] `./gradlew lint` passes with 0 errors.
- [ ] `./gradlew test` passes 100% of unit tests.
- [ ] `./gradlew assembleDebug` compiles cleanly.
- [ ] New unit tests have been added using `BlockDevice` abstractions for modified logic.
- [ ] Memory safety verified (no large heap allocations or whole-file memory mappings).
- [ ] Cooperative cancellation verified (no hanging threads on user cancel).
- [ ] Relevant documentation updated (`README.md`, `ARCHITECTURE.md`, `LIMITATIONS.md`).

---

## 🔌 Real Hardware Testing (Optional but Strongly Encouraged)
- **Phone Model:**
- **USB Flash Drive:**
- **Test Result:**
