#!/usr/bin/env python3
"""
FlashCore Forensic Engineering Audit & Claims Verifier.

Independently and non-circularly derives facts from repository source code,
build configuration, git state, and documentation to verify that:
1. Exact test method count is derived directly from source files.
2. Documentation matches the derived test method inventory without claiming
   unverified passes.
3. No unsupported positive "zero-copy" marketing claims exist.
4. No unsupported "Production-Grade" classifications exist in status matrices.
5. No non-existent releases or git tags are presented as published.
6. Hardware validation boundaries are explicitly disclosed.
"""

import os
import re
import sys
import subprocess

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def count_test_methods():
    test_dirs = [
        ("app/src/test/java", "unit"),
        ("app/src/androidTest/java", "instrumentation")
    ]
    test_method_regex = re.compile(
        r"@Test(?:\([^)]*\))?\s*(?:@\w+(?:\([^)]*\))?\s*)*fun\s+(`[^`]+`|[a-zA-Z0-9_]+)"
    )

    inventory = {
        "unit": 0,
        "instrumentation": 0,
        "total": 0,
        "files_count": 0,
        "per_file": {}
    }

    for rel_path, test_type in test_dirs:
        abs_path = os.path.join(REPO_ROOT, rel_path)
        if not os.path.exists(abs_path):
            continue
        for root, _, files in os.walk(abs_path):
            for file in sorted(files):
                if file.endswith("Test.kt"):
                    inventory["files_count"] += 1
                    file_path = os.path.join(root, file)
                    with open(file_path, "r", encoding="utf-8") as f:
                        content = f.read()
                    matches = test_method_regex.findall(content)
                    count = len(matches)
                    inventory["per_file"][file] = {"count": count, "type": test_type}
                    if test_type == "unit":
                        inventory["unit"] += count
                    else:
                        inventory["instrumentation"] += count

    inventory["total"] = inventory["unit"] + inventory["instrumentation"]
    return inventory

def check_local_environment():
    has_java = subprocess.run(["which", "java"], capture_output=True).returncode == 0
    test_report_dir = os.path.join(REPO_ROOT, "app", "build", "reports", "tests")
    has_reports = os.path.exists(test_report_dir)
    return {
        "has_java": has_java,
        "has_reports": has_reports,
        "report_dir": test_report_dir
    }

def audit_zero_copy():
    files_to_check = ["README.md", "LIMITATIONS.md", "ARCHITECTURE.md"]
    violations = []
    for rel_f in files_to_check:
        p = os.path.join(REPO_ROOT, rel_f)
        if not os.path.exists(p):
            continue
        with open(p, "r", encoding="utf-8") as f:
            for lnum, line in enumerate(f, 1):
                if "zero-copy" in line.lower():
                    lower = line.lower()
                    # Allow negative / explanatory references: e.g. "not zero-copy", "isn't zero-copy", "never"
                    if "not zero-copy" not in lower and "isn't zero-copy" not in lower and "never" not in lower and "not possible" not in lower and "past" not in lower:
                        violations.append(f"{rel_f}:{lnum}: Unsupported zero-copy assertion: {line.strip()}")
    return violations

def audit_production_grade_claims():
    readme_p = os.path.join(REPO_ROOT, "README.md")
    violations = []
    if os.path.exists(readme_p):
        with open(readme_p, "r", encoding="utf-8") as f:
            content = f.read()
        matrix_match = re.search(r"## 📊 Feature Status Matrix(.*?)(?:---|\n## )", content, re.DOTALL)
        if matrix_match:
            table = matrix_match.group(1)
            for lnum, line in enumerate(table.splitlines(), 1):
                if "Production-Grade" in line or "Production Grade" in line:
                    violations.append(f"README.md status matrix line: {line.strip()}")
    return violations

def audit_git_release_state():
    violations = []
    try:
        res = subprocess.run(["git", "tag", "-l"], capture_output=True, text=True, check=True)
        tags = [t.strip() for t in res.stdout.splitlines() if t.strip()]
        has_v1 = "v1.0.0" in tags or "1.0.0" in tags
    except Exception:
        has_v1 = False

    files_to_check = ["README.md", "LIMITATIONS.md", "REPRODUCIBLE_BUILDS.md"]
    for rel_f in files_to_check:
        p = os.path.join(REPO_ROOT, rel_f)
        if not os.path.exists(p):
            continue
        with open(p, "r", encoding="utf-8") as f:
            content = f.read()
        if not has_v1:
            if "git checkout tags/v1.0.0" in content:
                violations.append(f"{rel_f}: references checking out unreleased git tag v1.0.0")
            if "downloaded release: v1.0.0" in content.lower():
                violations.append(f"{rel_f}: claims v1.0.0 release is already downloadable")
    return violations, has_v1

def audit_hardware_notices():
    violations = []
    lim_p = os.path.join(REPO_ROOT, "LIMITATIONS.md")
    if os.path.exists(lim_p):
        with open(lim_p, "r", encoding="utf-8") as f:
            content = f.read()
        if "PHYSICAL HARDWARE VALIDATION STATUS: NOT VALIDATED" not in content:
            violations.append("LIMITATIONS.md missing explicit 'PHYSICAL HARDWARE VALIDATION STATUS: NOT VALIDATED' notice")
    else:
        violations.append("LIMITATIONS.md not found")
    return violations

def audit_documentation_test_numbers(inventory):
    violations = []
    total_str = str(inventory["total"])
    unit_str = str(inventory["unit"])

    readme_p = os.path.join(REPO_ROOT, "README.md")
    if os.path.exists(readme_p):
        with open(readme_p, "r", encoding="utf-8") as f:
            c = f.read()
        if total_str not in c:
            violations.append(f"README.md does not accurately reference derived total test count ({total_str})")
        if unit_str not in c:
            violations.append(f"README.md does not accurately reference derived unit test count ({unit_str})")

    return violations

def main():
    print("==================================================")
    print("FLASHCORE FORENSIC REALITY AUDIT")
    print("==================================================")

    # 1. Independent Test Discovery
    inventory = count_test_methods()
    print(f"PASS: Discovered {inventory['total']} @Test methods across {inventory['files_count']} test files")
    print(f"      - JVM / Robolectric Unit Tests: {inventory['unit']}")
    print(f"      - Android Instrumentation:     {inventory['instrumentation']}")
    for fname, meta in sorted(inventory["per_file"].items()):
        print(f"        * {fname:<36}: {meta['count']} ({meta['type']})")

    # 2. Local Execution Environment
    env = check_local_environment()
    if not env["has_java"]:
        print("WARN: Local execution environment lacks JDK/Android SDK in PATH; local ./gradlew test cannot run")
    else:
        print("PASS: Local Java runtime found")

    if not env["has_reports"]:
        print("INFO: No local Gradle test execution reports in app/build/reports/tests/ (source-level audit)")
    else:
        print(f"INFO: Test reports present in {env['report_dir']}")

    has_errors = False

    # 3. Documentation Test Number Consistency
    doc_test_errs = audit_documentation_test_numbers(inventory)
    if doc_test_errs:
        has_errors = True
        for err in doc_test_errs:
            print(f"FAIL: {err}")
    else:
        print("PASS: Documentation accurately matches derived test inventory counts")

    # 4. Zero-Copy Audit
    zc_errs = audit_zero_copy()
    if zc_errs:
        has_errors = True
        for err in zc_errs:
            print(f"FAIL: {err}")
    else:
        print("PASS: No unsupported positive zero-copy marketing claims found")

    # 5. Production-Grade Audit
    pg_errs = audit_production_grade_claims()
    if pg_errs:
        has_errors = True
        for err in pg_errs:
            print(f"FAIL: {err}")
    else:
        print("PASS: No unsupported 'Production-Grade' status labels in feature status matrix")

    # 6. Git Release Audit
    rel_errs, has_v1 = audit_git_release_state()
    if rel_errs:
        has_errors = True
        for err in rel_errs:
            print(f"FAIL: {err}")
    else:
        print(f"PASS: Git release state truthful (repository contains { 'v1.0.0' if has_v1 else 'no v1.0.0' } git tag)")

    # 7. Hardware Disclosure Audit
    hw_errs = audit_hardware_notices()
    if hw_errs:
        has_errors = True
        for err in hw_errs:
            print(f"FAIL: {err}")
    else:
        print("PASS: Hardware validation boundaries explicitly disclosed in LIMITATIONS.md")

    print("==================================================")
    if has_errors:
        print("AUDIT FAILED: Discrepancies detected between reality and documentation.")
        sys.exit(1)
    else:
        print("AUDIT PASSED: All claims, dynamic test metrics, and status labels are defensible.")
        sys.exit(0)

if __name__ == "__main__":
    main()
