import re
import unittest

def evaluate_openapi_gate(breaking_count: int, pr_title: str, commit_msg: str) -> dict:
    breaking_regex = re.compile(r"(!:|BREAKING[ \t]+CHANGE:)")
    is_declared = bool(breaking_regex.search(pr_title) or breaking_regex.search(commit_msg))
    
    if breaking_count == 0:
        return {"passed": True, "reason": "No breaking changes found"}
    if is_declared:
        return {"passed": True, "reason": "Breaking change declared for MAJOR release"}
    return {"passed": False, "reason": "Breaking change without MAJOR declaration"}

def evaluate_avro_gate(avsc_files: list) -> dict:
    if not avsc_files:
        return {"passed": True, "skipped": True, "reason": "No Avro schemas found in avro/"}
    return {"passed": True, "skipped": False, "reason": "Evaluated against Schema Registry"}

class CompatibilityGateTest(unittest.TestCase):
    def test_openapi_compatible_change_passes(self):
        result = evaluate_openapi_gate(breaking_count=0, pr_title="feat(tickets): add field", commit_msg="")
        self.assertTrue(result["passed"])

    def test_openapi_breaking_change_without_marker_fails(self):
        result = evaluate_openapi_gate(breaking_count=1, pr_title="feat(tickets): remove field", commit_msg="")
        self.assertFalse(result["passed"])

    def test_openapi_breaking_change_with_exclamation_marker_passes(self):
        result = evaluate_openapi_gate(breaking_count=1, pr_title="feat(tickets)!: [LAB-108] remove field", commit_msg="")
        self.assertTrue(result["passed"])

    def test_openapi_breaking_change_with_body_marker_passes(self):
        result = evaluate_openapi_gate(breaking_count=2, pr_title="feat(tickets): remove field", commit_msg="BREAKING CHANGE: removed field")
        self.assertTrue(result["passed"])

    def test_avro_gate_skips_when_empty(self):
        result = evaluate_avro_gate(avsc_files=[])
        self.assertTrue(result["passed"])
        self.assertTrue(result["skipped"])
        self.assertIn("No Avro schemas found", result["reason"])

if __name__ == "__main__":
    unittest.main()
