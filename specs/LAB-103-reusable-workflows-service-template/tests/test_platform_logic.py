import re
import unittest

def evaluate_policy_text(content: str) -> dict:
    has_owner_placeholder = "CAMBIA-ESTE-OWNER" in content
    has_latest_tag = bool(re.search(r"image:[ \t]*[^:]+:latest(\s|$)", content))
    
    passed = not has_owner_placeholder and not has_latest_tag
    violations = []
    if has_owner_placeholder:
        violations.append("CAMBIA-ESTE-OWNER")
    if has_latest_tag:
        violations.append(":latest")
        
    return {"passed": passed, "violations": violations}

def validate_error_code(code: str, expected_prefix: str) -> bool:
    pattern = rf"^{expected_prefix}_(GE|DB|EXT|AUTH)_\d{{5}}$"
    return bool(re.match(pattern, code))

def validate_image_tag(tag: str) -> bool:
    pattern = r"^v\d+\.\d+\.\d+$"
    return bool(re.match(pattern, tag))

class PlatformLogicTest(unittest.TestCase):
    def test_policy_check_passes_on_clean_content(self):
        content = "image: ghcr.io/leandroamariles/chat-service:v1.0.0\nauthor: dev"
        result = evaluate_policy_text(content)
        self.assertTrue(result["passed"])
        self.assertEqual(len(result["violations"]), 0)

    def test_policy_check_fails_on_owner_placeholder(self):
        content = "metadata:\n  owner: CAMBIA-ESTE-OWNER\n"
        result = evaluate_policy_text(content)
        self.assertFalse(result["passed"])
        self.assertIn("CAMBIA-ESTE-OWNER", result["violations"])

    def test_policy_check_fails_on_latest_image_tag(self):
        content = "spec:\n  containers:\n    - name: app\n      image: ghcr.io/leandroamariles/chat-service:latest\n"
        result = evaluate_policy_text(content)
        self.assertFalse(result["passed"])
        self.assertIn(":latest", result["violations"])

    def test_error_code_format_valid(self):
        self.assertTrue(validate_error_code("CHAT_GE_00001", "CHAT"))
        self.assertTrue(validate_error_code("CATL_DB_00002", "CATL"))
        self.assertTrue(validate_error_code("TMPL_AUTH_00001", "TMPL"))

    def test_error_code_format_invalid(self):
        self.assertFalse(validate_error_code("INVALID_CODE", "TMPL"))
        self.assertFalse(validate_error_code("TMPL_XX_00001", "TMPL"))
        self.assertFalse(validate_error_code("CHAT_GE_01", "CHAT"))

    def test_image_tag_format(self):
        self.assertTrue(validate_image_tag("v1.0.0"))
        self.assertTrue(validate_image_tag("v0.1.25"))
        self.assertFalse(validate_image_tag("latest"))
        self.assertFalse(validate_image_tag("1.0.0"))
        self.assertFalse(validate_image_tag("v1.0.0-SNAPSHOT"))

if __name__ == "__main__":
    unittest.main()
