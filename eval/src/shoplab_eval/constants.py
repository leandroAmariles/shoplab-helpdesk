"""Paths, categories and the numeric rules of the golden dataset.

The numbers in ``GoldenRules`` come from ``specs/LAB-27-golden-dataset/spec.md``: changing one is a decision of the
spec, not of the code, so both must be updated together.
"""

from dataclasses import dataclass
from pathlib import Path

# eval/src/shoplab_eval/constants.py -> parents[3] is the repository root, whatever the working directory is.
REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_GOLDEN_PATH = REPO_ROOT / "eval" / "golden.jsonl"
DEFAULT_KB_DIR = REPO_ROOT / "data" / "kb"

TOPICS = ("payments", "privacy", "returns", "shipping", "warranty")
OFF_TOPIC = "off_topic"
CATEGORIES = (*TOPICS, OFF_TOPIC)

# Files of data/kb that are control files, not documents of the knowledge base.
CONTROL_FILES = ("INDEX.md", "REVIEW.md")

REQUIRED_KEYS = ("id", "question", "reference_answer", "expected_documents", "category")

# A content word is a word of this many letters or more once the text is normalized (FR-013).
CONTENT_WORD_MIN_LETTERS = 4


@dataclass(frozen=True)
class GoldenRules:
    """Thresholds of the specification (FR-001, FR-007 to FR-010, FR-013)."""

    min_cases: int = 80
    max_cases: int = 100
    min_answered_per_topic: int = 12
    min_unanswered: int = 12
    unanswered_min_share: float = 0.15
    unanswered_max_share: float = 0.25
    min_unanswered_topic: int = 5
    min_off_topic: int = 5
    min_multi_document: int = 8
    question_length: tuple[int, int] = (10, 300)
    answer_length: tuple[int, int] = (20, 800)
    max_expected_documents: int = 2
    min_low_overlap_share: float = 0.30
