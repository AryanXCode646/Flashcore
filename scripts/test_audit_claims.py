#!/usr/bin/env python3
"""
Unit test suite for scripts/audit_claims.py.

Verifies the 8 mandatory maintainer scenarios:
1. wrong test count: flags mismatch between discovered test methods and documentation
2. unsupported Production-Grade: flags unvalidated 'Production-Grade' label in status matrix
3. positive zero-copy: flags unsupported positive zero-copy claims in docs
4. unreleased version: flags checkout or download claims for unreleased tags (e.g. v1.0.0)
5. clean docs: passes cleanly when documentation contains accurate, qualified claims
6. missing execution reports: reports TEST EXECUTION: NOT VERIFIED and OVERALL: NOT VERIFIED
7. failing reports: reports TEST EXECUTION: FAIL and OVERALL: FAIL when test reports contain failures
8. matching passing reports: reports TEST EXECUTION: PASS and OVERALL: PASS when reports match and pass
"""

import os
import sys
import shutil
import tempfile
import unittest

# Add scripts directory to module path
SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPTS_DIR)

from audit_claims import (
    discover_test_methods,
    parse_execution_reports,
    audit_zero_copy,
    audit_production_grade,
    audit_release_state,
    audit_documentation_claims,
    run_audit,
    REPO_ROOT_DEFAULT,
)


class TestAuditClaimsScenarios(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()

    def tearDown(self):
        if os.path.exists(self.test_dir):
            shutil.rmtree(self.test_dir)

    def _create_mock_repo(self, unit_tests=5, readme_content=None, limitations_content=None):
        """Creates a mock repo directory structure with Kotlin test files and docs."""
        # 1. Create test file in app/src/test/java
        test_dir = os.path.join(self.test_dir, "app", "src", "test", "java", "com", "flashcore")
        os.makedirs(test_dir, exist_ok=True)
        test_methods = "\n".join([f"    @Test\n    fun `test method {i}`() {{}}" for i in range(unit_tests)])
        with open(os.path.join(test_dir, "SampleTest.kt"), "w", encoding="utf-8") as f:
            f.write(f"package com.flashcore\nimport org.junit.Test\nclass SampleTest {{\n{test_methods}\n}}\n")

        # 2. Create LIMITATIONS.md
        if limitations_content is None:
            limitations_content = "# Limitations\nPHYSICAL HARDWARE VALIDATION STATUS: NOT VALIDATED\n"
        with open(os.path.join(self.test_dir, "LIMITATIONS.md"), "w", encoding="utf-8") as f:
            f.write(limitations_content)

        # 3. Create README.md
        if readme_content is None:
            readme_content = (
                f"# FlashCore\n"
                f"Discovered test methods: {unit_tests}\n"
                f"JVM unit tests: {unit_tests}\n"
                f"## 📊 Feature Status Matrix\n| Feature | Status |\n| Boot | Experimental |\n\n"
            )
        with open(os.path.join(self.test_dir, "README.md"), "w", encoding="utf-8") as f:
            f.write(readme_content)

    def _create_xml_report(self, tests, failures=0, errors=0, skipped=0):
        """Creates a sample JUnit XML report."""
        report_dir = os.path.join(self.test_dir, "reports")
        os.makedirs(report_dir, exist_ok=True)
        xml_file = os.path.join(report_dir, "TEST-sample.xml")
        xml_content = (
            f'<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<testsuite name="com.flashcore.SampleTest" tests="{tests}" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}" time="0.1">\n'
        )
        for i in range(tests):
            if i < failures:
                xml_content += f'  <testcase name="test{i}"><failure message="failed"/></testcase>\n'
            else:
                xml_content += f'  <testcase name="test{i}"/>\n'
        xml_content += '</testsuite>\n'
        with open(xml_file, "w", encoding="utf-8") as f:
            f.write(xml_content)
        return report_dir

    # Scenario 1: Wrong test count
    def test_scenario_1_wrong_test_count(self):
        """Audit flags mismatch when README claims test count different from discovered tests."""
        # Mock repo has 5 discovered tests, but README says 99
        self._create_mock_repo(
            unit_tests=5,
            readme_content="# FlashCore\nDiscovered test methods: 99\nJVM unit tests: 99\n"
        )
        inventory = discover_test_methods(self.test_dir)
        self.assertEqual(inventory["total"], 5)
        violations, _ = audit_documentation_claims(self.test_dir, inventory, exec_results=None)
        self.assertTrue(any("does not accurately reference" in v for v in violations))

    # Scenario 2: Unsupported Production-Grade
    def test_scenario_2_unsupported_production_grade(self):
        """Audit flags unvalidated 'Production-Grade' label in status matrix."""
        readme = (
            "# FlashCore\n"
            "Discovered test methods: 5\n"
            "JVM unit tests: 5\n"
            "## 📊 Feature Status Matrix\n"
            "| Component | Status |\n"
            "| USB Driver | Production-Grade |\n\n"
        )
        self._create_mock_repo(unit_tests=5, readme_content=readme)
        violations = audit_production_grade(self.test_dir)
        self.assertTrue(len(violations) > 0)
        self.assertIn("Production-Grade", violations[0])

    # Scenario 3: Positive zero-copy
    def test_scenario_3_positive_zero_copy(self):
        """Audit flags unsupported positive zero-copy claims in documentation."""
        readme = (
            "# FlashCore\n"
            "Discovered test methods: 5\n"
            "JVM unit tests: 5\n"
            "FlashCore provides true zero-copy buffer streaming.\n"
        )
        self._create_mock_repo(unit_tests=5, readme_content=readme)
        violations = audit_zero_copy(self.test_dir)
        self.assertTrue(len(violations) > 0)
        self.assertTrue(any("zero-copy" in v.lower() for v in violations))

    # Scenario 4: Unreleased version
    def test_scenario_4_unreleased_version(self):
        """Audit flags instructions to checkout or download unreleased tag v1.0.0."""
        readme = (
            "# FlashCore\n"
            "Discovered test methods: 5\n"
            "JVM unit tests: 5\n"
            "Run git checkout tags/v1.0.0 to test the release.\n"
        )
        self._create_mock_repo(unit_tests=5, readme_content=readme)
        violations, has_v1 = audit_release_state(self.test_dir)
        self.assertFalse(has_v1)
        self.assertTrue(len(violations) > 0)
        self.assertTrue(any("unreleased git tag v1.0.0" in v for v in violations))

    # Scenario 5: Clean docs
    def test_scenario_5_clean_docs(self):
        """Clean documentation produces zero violations on source audit."""
        self._create_mock_repo(unit_tests=5)
        overall_status, details = run_audit(self.test_dir, test_report_path=None, verbose=False)
        self.assertEqual(details["source_audit_status"], "PASS")
        self.assertEqual(len(details["violations"]), 0)

    # Scenario 6: Missing execution reports
    def test_scenario_6_missing_execution_reports(self):
        """When execution reports are missing, status is NOT VERIFIED (never false PASS)."""
        self._create_mock_repo(unit_tests=5)
        non_existent_report_path = os.path.join(self.test_dir, "non_existent_dir")
        overall_status, details = run_audit(self.test_dir, test_report_path=non_existent_report_path, verbose=False)
        self.assertEqual(details["source_audit_status"], "PASS")
        self.assertIsNone(details["execution"])
        self.assertEqual(details["test_execution_status"], "NOT VERIFIED")
        self.assertEqual(overall_status, "NOT VERIFIED")

    # Scenario 7: Failing reports
    def test_scenario_7_failing_reports(self):
        """When XML reports show failed tests, execution status is FAIL and overall is FAIL."""
        self._create_mock_repo(unit_tests=5)
        report_dir = self._create_xml_report(tests=5, failures=2)
        overall_status, details = run_audit(self.test_dir, test_report_path=report_dir, verbose=False)
        self.assertIsNotNone(details["execution"])
        self.assertEqual(details["execution"]["failed"], 2)
        self.assertEqual(details["test_execution_status"], "FAIL")
        self.assertEqual(overall_status, "FAIL")

    # Scenario 8: Matching passing reports
    def test_scenario_8_matching_passing_reports(self):
        """When XML reports show all matching tests passed, overall status is PASS."""
        self._create_mock_repo(unit_tests=5)
        report_dir = self._create_xml_report(tests=5, failures=0)
        overall_status, details = run_audit(self.test_dir, test_report_path=report_dir, verbose=False)
        self.assertIsNotNone(details["execution"])
        self.assertEqual(details["execution"]["passed"], 5)
        self.assertEqual(details["execution"]["failed"], 0)
        self.assertEqual(details["test_execution_status"], "PASS")
        self.assertEqual(overall_status, "PASS")


class TestActualRepositoryCleanliness(unittest.TestCase):
    """Verifies that the actual repository passes source audit checks."""
    def test_flashcore_repo_source_audit(self):
        overall_status, details = run_audit(REPO_ROOT_DEFAULT, test_report_path=None, verbose=False)
        self.assertEqual(
            details["source_audit_status"], "PASS",
            f"Repository source audit failed with violations: {details['violations']}"
        )
        self.assertEqual(details["inventory"]["total"], 93)
        self.assertEqual(details["inventory"]["unit"], 92)
        self.assertEqual(details["inventory"]["instrumentation"], 1)


if __name__ == "__main__":
    unittest.main()
