"""Constants for the classic intent classifier package (LAB-118)."""

from pathlib import Path

CANONICAL_LABELS: tuple[str, ...] = ("politica", "producto", "pedido", "escalado", "charla")
LANGUAGES: tuple[str, ...] = ("es", "pt")
SPLIT_TRAIN: str = "train"
SPLIT_VAL: str = "val"
SPLIT_TEST: str = "test"
SPLITS: tuple[str, ...] = (SPLIT_TRAIN, SPLIT_VAL, SPLIT_TEST)

DEFAULT_SEED: int = 118
MIN_TEST_F1_MACRO: float = 0.85
CV_FOLDS: int = 5
SELECTION_TIE_MARGIN: float = 0.01

CONFIDENCE_THRESHOLDS: tuple[float, ...] = (0.50, 0.60, 0.70, 0.75, 0.80, 0.85, 0.90)
CALIBRATION_BINS: int = 10

VARIANT_DUMMY: str = "dummy_majority"
VARIANT_TFIDF_LOGREG: str = "tfidf_logreg"
VARIANT_TFIDF_LINEARSVC: str = "tfidf_linearsvc"
VARIANT_EMBEDDING_LOGREG: str = "embedding_logreg"
VARIANT_AUTO: str = "auto"

VARIANT_NAMES: tuple[str, ...] = (
    VARIANT_DUMMY,
    VARIANT_TFIDF_LOGREG,
    VARIANT_TFIDF_LINEARSVC,
    VARIANT_EMBEDDING_LOGREG,
)

MASK_TOKENS: dict[str, str] = {
    "[URL]": "__mask_url__",
    "[EMAIL]": "__mask_email__",
    "[ID]": "__mask_id__",
    "[CNPJ]": "__mask_cnpj__",
    "[CPF]": "__mask_cpf__",
    "[CEP]": "__mask_cep__",
    "[PHONE]": "__mask_phone__",
    "[NAME]": "__mask_name__",
}

REPO_ROOT: Path = Path(__file__).resolve().parents[3]
ML_ROOT: Path = REPO_ROOT / "ml"
INTENTS_DIR: Path = ML_ROOT / "data" / "intents"
DEFAULT_DATASET_PATH: Path = INTENTS_DIR / "intents.jsonl"
DEFAULT_REVIEW_PATH: Path = INTENTS_DIR / "REVIEW.md"
DEFAULT_EMBEDDINGS_CACHE_PATH: Path = INTENTS_DIR / "embeddings_cache.npz"

ARTIFACTS_DIR: Path = ML_ROOT / "artifacts" / "intent"
DEFAULT_ARTIFACT_PATH: Path = ARTIFACTS_DIR / "intent_classifier.joblib"
DEFAULT_TRAIN_METADATA_PATH: Path = ARTIFACTS_DIR / "train_metadata.json"
DEFAULT_METADATA_OUT_PATH: Path = DEFAULT_TRAIN_METADATA_PATH
DEFAULT_EVAL_REPORT_PATH: Path = ARTIFACTS_DIR / "eval_report.json"
DEFAULT_REPORT_OUT_PATH: Path = DEFAULT_EVAL_REPORT_PATH

SCHEMA_VERSION: str = "1.0"
