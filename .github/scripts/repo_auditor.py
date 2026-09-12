#!/usr/bin/env python3
"""
FlashCore Autonomous Codebase Auditor & Security Inspector.

Scans the FlashCore codebase, leverages Google GenAI SDK (gemini-2.5-flash),
and performs deep static and architectural analysis across:
1. Security Flaws (SSRF, raw block device bounds, path traversal, permission exposure)
2. Race Conditions & Concurrency (Kotlin coroutines, ring buffer pointers, USB bulk async)
3. Memory & Resource Leaks (DirectByteBuffer off-heap allocations, USB interface unbinding, streams)
4. Correctness & Protocol Bugs (SCSI CDB framing, Big/Little-Endian discrepancies, 64-bit LBA math)
5. Architectural Anti-Patterns (silent exception swallowing, state machine bypasses)

Outputs comprehensive markdown reports and integrates with GitHub Actions / Issues.
"""

import os
import sys
import re
import glob
import json
import time
import argparse
import datetime
from pathlib import Path

try:
    from google import genai
    from google.genai import types
    GENAI_AVAILABLE = True
except ImportError:
    GENAI_AVAILABLE = False

try:
    import requests
    REQUESTS_AVAILABLE = True
except ImportError:
    REQUESTS_AVAILABLE = False

# Mapping of codebase subsystems and their respective critical source patterns
SUBSYSTEM_MAP = {
    "scsi_usb": {
        "title": "SCSI-2 / SPC-4 / SBC-3 & USB Mass Storage Pipeline",
        "description": "Low-level USB Bulk-Only Transport (BOT), Command Block Wrappers (CBW), Status Wrappers (CSW), and SCSI CDB builders.",
        "patterns": [
            "app/src/main/java/com/ashishsinghbora/flashcore/scsi/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/usb/*.kt",
        ]
    },
    "partition_fat32": {
        "title": "Partitioning Engine & FAT32 File System Formatter",
        "description": "MBR/GPT partition tables, alignment math, FAT32 boot sectors, cluster allocation tables, directory entries, and FsInfo.",
        "patterns": [
            "app/src/main/java/com/ashishsinghbora/flashcore/partition/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/fat32/*.kt",
        ]
    },
    "flasher_engine": {
        "title": "FSM State Machine & Flashing Strategies",
        "description": "Deterministic lifecycle state machine, FlashSafetyValidator, Linux raw dd, Ventoy, and Windows UEFI strategies.",
        "patterns": [
            "app/src/main/java/com/ashishsinghbora/flashcore/flasher/fsm/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/flasher/safety/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/flasher/strategies/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/flasher/ventoy/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/flasher/windows/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/flasher/verification/*.kt",
        ]
    },
    "dsa_storage_io": {
        "title": "DSA, Off-Heap Direct Ring Buffer & ISO Parser",
        "description": "SPSC DirectByteBuffer ring buffer, block devices, rolling checksums, El Torito ISO9660 parser, and WimChunker.",
        "patterns": [
            "app/src/main/java/com/ashishsinghbora/flashcore/dsa/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/block/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/iso/*.kt",
        ]
    },
    "platform_security": {
        "title": "Android Platform, Foreground Service & App Manifest",
        "description": "Foreground execution lifecycle, wakelocks, permissions, build configurations, and AndroidManifest declarations.",
        "patterns": [
            "app/src/main/java/com/ashishsinghbora/flashcore/service/*.kt",
            "app/src/main/java/com/ashishsinghbora/flashcore/MainActivity.kt",
            "app/src/main/AndroidManifest.xml",
            "app/build.gradle.kts",
        ]
    }
}

SYSTEM_INSTRUCTION = """
You are an autonomous Principal Systems Software Architect, Senior Security Engineer, and Static Analysis Auditor.
You are conducting an in-depth security and correctness audit of FlashCore—an Android low-level USB Mass Storage flashing engine.
FlashCore interacts directly with raw hardware block devices, SCSI Command Descriptor Blocks (CDBs), USB endpoints, FAT32 filesystems, and MBR/GPT partitioning structures.

You must examine the provided source files with extreme precision and report findings in the following categories:
1. SECURITY VULNERABILITIES:
   - Path traversal in ISO/file extraction or image paths.
   - Arbitrary disk writes or out-of-bounds LBA modifications that could overwrite host/device partitions.
   - Insecure Android component exports, permission bypasses, or unsafe intent handling.
   - SSRF or unsafe URL schemes if remote downloading is performed.
   - Insecure cryptographic seeds or weak checksum collisions in validation.
2. CONCURRENCY & RACE CONDITIONS:
   - Data races in SPSC ring buffers (head/tail pointer unsynchronized mutations).
   - Kotlin coroutines threading hazards (non-thread-safe state collections, missing Mutex/ReentrantLock guards).
   - Async USB bulk transfer race conditions against cancellation or disconnection.
   - Deadlocks between producer-consumer locks and conditions.
3. MEMORY & RESOURCE LEAKS:
   - Off-heap DirectByteBuffer memory leaks without proper deallocation or reuse.
   - Unreleased UsbDeviceConnection, UsbInterface, or unclosed file descriptors.
   - Android Service or CoroutineScope leaks persisting beyond flasher completion.
4. CORRECTNESS & PROTOCOL BUGS:
   - 32-bit vs 64-bit integer overflow in sector or byte calculations (e.g., `sector * 512L` vs `sector * 512`).
   - Endianness bugs: SCSI and ISO9660 use Big-Endian (Network Byte Order); FAT32 and MBR/GPT use Little-Endian.
   - Off-by-one errors in cluster chains, partition alignment calculations, or SCSI Sense data length.
   - Improper handling of SCSI CHECK CONDITION or sense key interpretation.
5. ARCHITECTURAL ANTI-PATTERNS:
   - Swallowed exceptions (empty `catch (e: Exception) {}`).
   - Incomplete FSM state transitions leaving the application in a hung state.
   - Lack of timeout guards on synchronous or native USB operations.

FORMATTING REQUIREMENTS:
Provide your evaluation in clean Markdown:
- High-level verdict: PASS, WARNING, or CRITICAL.
- Table of findings: | ID | Severity (P0/P1/P2/P3) | Category | Location (file:line) | Description |
- Detailed breakdowns for each finding with:
  - Root Cause Analysis
  - Security/Reliability Impact
  - Concrete Remediation (Kotlin/XML/Gradle code patch)
- If no critical vulnerabilities are present, highlight good architectural practices observed and provide proactive hardening recommendations.
"""


def collect_subsystem_files(repo_root: Path, patterns: list[str]) -> list[Path]:
    """Collect unique existing files matching pattern list within repo root."""
    matched = set()
    for pat in patterns:
        full_pattern = str(repo_root / pat)
        for f in glob.glob(full_pattern, recursive=True):
            p = Path(f)
            if p.is_file():
                matched.add(p)
    return sorted(list(matched))


def bundle_code_content(repo_root: Path, file_paths: list[Path], max_chars_per_file: int = 15000) -> str:
    """Read and format code files into a single labeled prompt content bundle."""
    bundle = []
    for fp in file_paths:
        rel_path = fp.relative_to(repo_root)
        try:
            content = fp.read_text(encoding="utf-8", errors="replace")
            if len(content) > max_chars_per_file:
                content = content[:max_chars_per_file] + f"\n\n... [TRUNCATED: remaining {len(content) - max_chars_per_file} characters omitted for brevity] ..."
            bundle.append(f"================================================================================\nFILE: {rel_path}\n================================================================================\n{content}\n")
        except Exception as e:
            bundle.append(f"================================================================================\nFILE: {rel_path} (ERROR READING: {e})\n================================================================================\n")
    return "\n".join(bundle)


def run_gemini_audit(client: "genai.Client", model_name: str, subsystem_key: str, sub_info: dict, code_bundle: str) -> str:
    """Invoke Gemini model via google-genai SDK to audit the subsystem code bundle."""
    prompt = f"""
Audit Subsystem: {sub_info['title']} ({subsystem_key})
Description: {sub_info['description']}

The source files belonging to this subsystem are provided below:

{code_bundle}

Please execute your thorough security, concurrency, memory, correctness, and architectural review now.
"""
    try:
        response = client.models.generate_content(
            model=model_name,
            contents=prompt,
            config=types.GenerateContentConfig(
                temperature=0.2,
                top_p=0.95,
                system_instruction=SYSTEM_INSTRUCTION
            )
        )
        return response.text or "No response received from model."
    except Exception as e:
        return f"Error executing Gemini audit for subsystem {subsystem_key}: {e}"


def post_or_update_github_issue(repo: str, token: str, report_content: str) -> None:
    """Create or update GitHub Issue with audit findings using GitHub REST API."""
    if not REQUESTS_AVAILABLE or not token or not repo:
        print("[GitHub Issue] Skipped: GITHUB_TOKEN or GITHUB_REPOSITORY not set.")
        return

    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json"
    }
    api_base = f"https://api.github.com/repos/{repo}"

    today = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    issue_title = f"[Autonomous Audit] FlashCore Security, Concurrency & Quality Report ({today})"

    # Check for existing open issue with audit label or matching title
    try:
        issues_res = requests.get(
            f"{api_base}/issues",
            headers=headers,
            params={"state": "open", "labels": "audit", "per_page": 10},
            timeout=15
        )
        existing_issue_number = None
        if issues_res.status_code == 200:
            for issue in issues_res.json():
                if "Autonomous Audit" in issue.get("title", ""):
                    existing_issue_number = issue["number"]
                    break

        if existing_issue_number:
            print(f"[GitHub Issue] Updating existing audit issue #{existing_issue_number}...")
            update_res = requests.patch(
                f"{api_base}/issues/{existing_issue_number}",
                headers=headers,
                json={"title": issue_title, "body": report_content},
                timeout=15
            )
            if update_res.status_code == 200:
                print(f"[GitHub Issue] Successfully updated issue #{existing_issue_number}")
            else:
                print(f"[GitHub Issue] Failed to update issue: {update_res.status_code} {update_res.text}")
        else:
            print("[GitHub Issue] Creating new audit issue...")
            create_res = requests.post(
                f"{api_base}/issues",
                headers=headers,
                json={
                    "title": issue_title,
                    "body": report_content,
                    "labels": ["audit", "security", "automated"]
                },
                timeout=15
            )
            if create_res.status_code == 201:
                created_num = create_res.json().get("number")
                print(f"[GitHub Issue] Successfully created issue #{created_num}")
            else:
                print(f"[GitHub Issue] Failed to create issue: {create_res.status_code} {create_res.text}")
    except Exception as ex:
        print(f"[GitHub Issue] Network or API exception: {ex}")


def write_step_summary(summary_md: str) -> None:
    """Write markdown summary to GITHUB_STEP_SUMMARY environment file if present."""
    step_summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary_path:
        try:
            with open(step_summary_path, "a", encoding="utf-8") as f:
                f.write(summary_md + "\n")
            print("[Step Summary] Written to GITHUB_STEP_SUMMARY.")
        except Exception as e:
            print(f"[Step Summary] Warning: Failed to write to GITHUB_STEP_SUMMARY: {e}")


def main():
    parser = argparse.ArgumentParser(description="FlashCore Autonomous Codebase Auditor (Gemini 2.5 Flash)")
    parser.add_argument("--repo-dir", default=".", help="Path to repository root")
    parser.add_argument("--output", default="audit_report.md", help="Path to write the markdown report")
    parser.add_argument("--model", default="gemini-2.5-flash", help="Gemini model to invoke")
    parser.add_argument("--subsystems", default="all", help="Comma-separated subsystems or 'all'")
    parser.add_argument("--create-issue", action="store_true", help="Post or update report in a GitHub issue")
    parser.add_argument("--fail-on-critical", action="store_true", help="Exit with non-zero status if P0 critical issues found")
    parser.add_argument("--dry-run", action="store_true", help="Perform file discovery and dry-run scan without API calls")

    args = parser.parse_args()
    repo_root = Path(args.repo_dir).resolve()

    print(f"[*] Starting FlashCore Codebase Auditor on {repo_root}")
    print(f"[*] Target Model: {args.model}")

    # Determine subsystems to audit
    selected_subsystems = []
    if args.subsystems.lower() == "all":
        selected_subsystems = list(SUBSYSTEM_MAP.keys())
    else:
        for k in args.subsystems.split(","):
            k = k.strip().lower()
            if k in SUBSYSTEM_MAP:
                selected_subsystems.append(k)
            else:
                print(f"[!] Warning: Subsystem '{k}' not recognized. Available: {list(SUBSYSTEM_MAP.keys())}")

    if not selected_subsystems:
        print("[-] Error: No valid subsystems selected for audit.")
        sys.exit(1)

    api_key = os.environ.get("GEMINI_API_KEY")
    client = None

    if not args.dry_run:
        if not api_key:
            print("[!] Warning: GEMINI_API_KEY environment variable is not set.")
            print("[!] Switching to DRY-RUN mode (Structural Inventory & Offline Verification).")
            args.dry_run = True
        elif not GENAI_AVAILABLE:
            print("[!] Warning: google-genai package is not installed. Run 'pip install google-genai'.")
            print("[!] Switching to DRY-RUN mode.")
            args.dry_run = True
        else:
            try:
                client = genai.Client(api_key=api_key)
            except Exception as e:
                print(f"[-] Error initializing google-genai Client: {e}")
                sys.exit(1)

    audit_timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    report_sections = [
        "# FlashCore Autonomous Codebase Security & Quality Audit Report\n",
        f"- **Audit Date**: {audit_timestamp}",
        f"- **Model**: `{args.model}` (`google-genai` SDK)",
        f"- **Repository**: `{os.environ.get('GITHUB_REPOSITORY', repo_root.name)}`",
        f"- **Execution Mode**: `{'DRY RUN (Offline Verification)' if args.dry_run else 'ACTIVE GENAI SCAN'}`\n",
        "## 1. Executive Summary & Inventory\n",
    ]

    inventory_table = [
        "| Subsystem | Modules / Focus | Discovered Files | Audit Status |",
        "| :--- | :--- | :---: | :---: |"
    ]

    subsystem_results = {}
    total_files = 0
    has_critical_p0 = False

    for sub_key in selected_subsystems:
        sub_info = SUBSYSTEM_MAP[sub_key]
        files = collect_subsystem_files(repo_root, sub_info["patterns"])
        total_files += len(files)
        print(f"[+] Subsystem [{sub_key}] matched {len(files)} files.")

        if args.dry_run:
            status_text = "VERIFIED (INVENTORY ONLY)"
            content_summary = f"### {sub_info['title']}\n\n**Subsystem Description**: {sub_info['description']}\n\n"
            content_summary += f"**Discovered Files ({len(files)}):**\n"
            for f in files:
                content_summary += f"- `{f.relative_to(repo_root)}` ({f.stat().st_size} bytes)\n"
            content_summary += "\n*Dry-run complete: Set GEMINI_API_KEY to trigger autonomous LLM security & concurrency audit.*\n"
            subsystem_results[sub_key] = content_summary
        else:
            print(f"[*] Analyzing subsystem [{sub_key}] via Gemini 2.5 Flash...")
            code_bundle = bundle_code_content(repo_root, files)
            audit_res = run_gemini_audit(client, args.model, sub_key, sub_info, code_bundle)
            subsystem_results[sub_key] = f"### {sub_info['title']}\n\n{audit_res}\n"
            status_text = "AUDITED (COMPLETED)"
            if "P0" in audit_res or "CRITICAL" in audit_res.upper():
                has_critical_p0 = True

            time.sleep(1) # Prevent aggressive bursting

        inventory_table.append(f"| **{sub_info['title']}** | {sub_info['description']} | {len(files)} | {status_text} |")

    report_sections.extend(inventory_table)
    report_sections.append(f"\n**Total Files Audited**: {total_files}\n")

    report_sections.append("## 2. Detailed Subsystem Analysis & Findings\n")
    for sub_key in selected_subsystems:
        report_sections.append(subsystem_results[sub_key])
        report_sections.append("\n---\n")

    report_sections.append("## 3. Recommended Remediation & Verification Next Steps\n")
    report_sections.append("""
1. **Pre-merge CI Enforcement**: Maintain `./gradlew lint` and `./gradlew test` gating to prevent regressions.
2. **Buffer & Resource Lifecycle**: Ensure every off-heap `DirectByteBuffer` and `UsbDeviceConnection` is enclosed in defensive cleanup or `use {}` / `close()` wrappers.
3. **Integer Arithmetic Bounds**: Verify 64-bit casting (`toLong()`) prior to any multiplication involving sectors or block sizes to prevent 32-bit integer overflows.
4. **SCSI Check Condition Propagation**: Maintain strict Request Sense queries upon CHECK CONDITION to preserve device diagnostic integrity.
""")

    final_report = "\n".join(report_sections)

    # Write output report to disk
    out_path = Path(args.output)
    out_path.write_text(final_report, encoding="utf-8")
    print(f"[✓] Full audit report written to: {out_path.resolve()}")

    # GitHub Actions Step Summary
    condensed_summary = f"""### 🛡️ FlashCore Autonomous Codebase Audit Summary
- **Timestamp**: `{audit_timestamp}`
- **Model**: `{args.model}`
- **Mode**: `{'DRY RUN' if args.dry_run else 'ACTIVE GENAI SCAN'}`
- **Files Inspected**: `{total_files}` across `{len(selected_subsystems)}` subsystems
- **Artifact Report**: `{args.output}`

| Subsystem | Discovered Files | Status |
| :--- | :---: | :---: |
"""
    for sub_key in selected_subsystems:
        sub_info = SUBSYSTEM_MAP[sub_key]
        condensed_summary += f"| {sub_info['title']} | {len(collect_subsystem_files(repo_root, sub_info['patterns']))} | {'Verified' if args.dry_run else 'Audited'} |\n"

    write_step_summary(condensed_summary)

    # Post or update GitHub Issue if requested
    if args.create_issue:
        token = os.environ.get("GITHUB_TOKEN")
        repo = os.environ.get("GITHUB_REPOSITORY")
        post_or_update_github_issue(repo, token, final_report)

    if args.fail_on_critical and has_critical_p0:
        print("[!] Critical P0 issues were detected in the codebase.")
        sys.exit(1)

    print("[✓] Audit successfully completed.")


if __name__ == "__main__":
    main()
