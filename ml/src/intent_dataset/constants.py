"""Fixed values of the dataset: labels, languages, sources, splits, thresholds and paths.

The labels are data values fixed by the story (LAB-117), not identifiers: they stay in Spanish.
"""

from dataclasses import dataclass
from pathlib import Path

LABELS: tuple[str, ...] = ("politica", "producto", "pedido", "escalado", "charla")
LANGUAGES: tuple[str, ...] = ("es", "pt")
SOURCES: tuple[str, ...] = ("golden", "olist_review", "manual_seed", "paraphrase")
SPLITS: tuple[str, ...] = ("train", "val", "test")

GOLDEN = "golden"
OLIST_REVIEW = "olist_review"
MANUAL_SEED = "manual_seed"
PARAPHRASE = "paraphrase"

# Keys of one line of intents.jsonl, in the order they are written (contracts/dataset-format.md).
EXAMPLE_KEYS: tuple[str, ...] = (
    "id",
    "text",
    "label",
    "language",
    "source",
    "group_id",
    "parent_id",
    "golden_id",
    "split",
    "reviewed",
)

# Columns of the review CSV (contracts/dataset-format.md).
CANDIDATE_COLUMNS: tuple[str, ...] = (
    "candidate_id",
    "source",
    "group_id",
    "parent_id",
    "golden_id",
    "language",
    "text",
    "proposed_label",
    "hint",
    "decision",
    "label",
    "text_edit",
    "pii_checked",
    "note",
)

ACCEPT = "accept"
REJECT = "reject"
PII_CHECKED_YES = "si"

ANNOTATOR_PERSON = "person"
ANNOTATOR_LOCAL_MODEL = "local_model"
ANNOTATOR_KINDS: tuple[str, ...] = (ANNOTATOR_PERSON, ANNOTATOR_LOCAL_MODEL)

# Categories of eval/golden.jsonl mapped to the label proposed for them (research R4); a person confirms each one.
GOLDEN_CATEGORY_LABELS: dict[str, str] = {
    "payments": "politica",
    "privacy": "politica",
    "returns": "politica",
    "shipping": "politica",
    "warranty": "politica",
    "small_talk": "charla",
    "off_topic": "charla",
    "injection": "charla",
}

REPO_ROOT = Path(__file__).resolve().parents[3]
INTENTS_DIR = REPO_ROOT / "ml" / "data" / "intents"
DATASET_PATH = INTENTS_DIR / "intents.jsonl"
WORK_DIR = INTENTS_DIR / "work"
ANNOTATIONS_DIR = INTENTS_DIR / "annotations"
SAMPLE_PATH = ANNOTATIONS_DIR / "agreement-sample.jsonl"
AGREEMENT_PATH = ANNOTATIONS_DIR / "agreement.json"
GUIDE_PATH = INTENTS_DIR / "LABELING-GUIDE.md"
DATASHEET_PATH = INTENTS_DIR / "DATASHEET.md"
GOLDEN_PATH = REPO_ROOT / "eval" / "golden.jsonl"

STATS_BEGIN = "<!-- stats:begin -->"
STATS_END = "<!-- stats:end -->"


@dataclass(frozen=True)
class DatasetRules:
    """Thresholds of the dataset. The defaults are the ones of the spec; tests pass smaller ones."""

    min_examples: int = 600
    min_per_label: int = 60
    min_per_label_language: int = 20
    min_text_chars: int = 2
    max_text_chars: int = 500
    ratios: tuple[float, float, float] = (0.70, 0.15, 0.15)
    seed: int = 117
    agreement_sample_size: int = 50
    kappa_reference: float = 0.70
