#!/usr/bin/env python3
"""
Unit tests for FlashCore autonomous automation scripts:
- .github/scripts/repo_auditor.py
- .github/scripts/pr_reviewer.py
"""

import sys
import unittest
from pathlib import Path

# Add repo root and .github/scripts to sys.path
REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / ".github" / "scripts"))

import repo_auditor
import pr_reviewer


class TestRepoAuditor(unittest.TestCase):

    def test_subsystem_mapping_completeness(self):
        """Verify all critical subsystems are defined in SUBSYSTEM_MAP."""
        expected = ["scsi_usb", "partition_fat32", "flasher_engine", "dsa_storage_io", "platform_security"]
        for key in expected:
            self.assertIn(key, repo_auditor.SUBSYSTEM_MAP)
            self.assertIn("title", repo_auditor.SUBSYSTEM_MAP[key])
            self.assertIn("patterns", repo_auditor.SUBSYSTEM_MAP[key])

    def test_collect_subsystem_files(self):
        """Verify file pattern collection locates actual files in the repository."""
        scsi_info = repo_auditor.SUBSYSTEM_MAP["scsi_usb"]
        files = repo_auditor.collect_subsystem_files(REPO_ROOT, scsi_info["patterns"])
        self.assertGreater(len(files), 0)
        file_names = [f.name for f in files]
        self.assertIn("ScsiCdbBuilder.kt", file_names)
        self.assertIn("UsbMassStorageDriver.kt", file_names)

    def test_bundle_code_content_and_truncation(self):
        """Verify bundling format and character truncation."""
        test_file = REPO_ROOT / "scripts" / "audit_claims.py"
        bundle = repo_auditor.bundle_code_content(REPO_ROOT, [test_file], max_chars_per_file=500)
        self.assertIn("FILE: scripts/audit_claims.py", bundle)
        self.assertIn("TRUNCATED", bundle)


class TestPrReviewer(unittest.TestCase):

    def test_diff_filter_skips_binary_assets(self):
        """Verify non-code binary assets like PNGs or JARs are stripped from PR diff."""
        sample_diff = """diff --git a/app/src/test/screenshots/greeting.png b/app/src/test/screenshots/greeting.png
index 1234567..89abcdef 100644
Binary files a/app/src/test/screenshots/greeting.png and b/app/src/test/screenshots/greeting.png differ
diff --git a/app/src/main/java/com/ashishsinghbora/flashcore/scsi/ScsiCdbBuilder.kt b/app/src/main/java/com/ashishsinghbora/flashcore/scsi/ScsiCdbBuilder.kt
--- a/app/src/main/java/com/ashishsinghbora/flashcore/scsi/ScsiCdbBuilder.kt
+++ b/app/src/main/java/com/ashishsinghbora/flashcore/scsi/ScsiCdbBuilder.kt
@@ -10,2 +10,3 @@
+// Added safe validation check
"""
        filtered, is_truncated = pr_reviewer.filter_diff(sample_diff, max_chars=1000)
        self.assertNotIn("greeting.png", filtered)
        self.assertIn("ScsiCdbBuilder.kt", filtered)
        self.assertIn("Added safe validation check", filtered)
        self.assertFalse(is_truncated)

    def test_idempotency_tag_present(self):
        """Verify the PR review marker tag is defined and recognized."""
        self.assertTrue(pr_reviewer.PR_REVIEW_TAG.startswith("<!--"))
        self.assertIn("flashcore-autonomous-pr-review", pr_reviewer.PR_REVIEW_TAG)


if __name__ == "__main__":
    unittest.main()
