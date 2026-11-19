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
# Knowledge cases are questions about the store (or clearly outside it) that the knowledge base answers or admits it
# cannot answer. Conversation cases (LAB-114) are courtesies and prompt-injection attempts: they have no search by
# design, so they have no expected documents and no place in the retrieval metrics.
SMALL_TALK = "small_talk"
INJECTION = "injection"
KNOWLEDGE_CATEGORIES = (*TOPICS, OFF_TOPIC)
CONVERSATION_CATEGORIES = (SMALL_TALK, INJECTION)
CATEGORIES = (*KNOWLEDGE_CATEGORIES, *CONVERSATION_CATEGORIES)

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
    # A greeting is a valid question: "hola" has four characters (specs/LAB-114-bounded-small-talk).
    conversation_question_length: tuple[int, int] = (2, 300)
    min_small_talk: int = 10
    min_injection: int = 10
    answer_length: tuple[int, int] = (20, 800)
    max_expected_documents: int = 2
    min_low_overlap_share: float = 0.30


# Retrieval metrics (specs/LAB-28-retrieval-metrics). The defaults come from the plan and research R4, R9 and R10.
DEFAULT_KS = (1, 3, 5)
# Chunks asked of the search per question. 50 is the most the backend accepts (RetrievalConstants.MAX_SEARCH_LIMIT).
DEFAULT_DEPTH = 50
MAX_DEPTH = 50
DEFAULT_BASE_URL = "http://localhost:8080"
BASE_URL_ENV = "SHOPLAB_EVAL_BASE_URL"
DEFAULT_TIMEOUT_SECONDS = 10
DEFAULT_RESULTS_DIR = REPO_ROOT / "eval" / "results"
SUMMARY_FILE = "retrieval-summary.csv"
DETAIL_FILE = "retrieval-detail.csv"
ALL_SCOPE = "all"
# Joins the document ids of one CSV cell; it cannot appear in an id (letters, digits and hyphens).
LIST_SEPARATOR = "|"
DECIMALS = 4
INDEXED_STATUS = "INDEXED"
SEARCH_PATH = "/api/knowledge/search"
DOCUMENTS_PATH = "/api/documents"

# Answer quality metrics (specs/LAB-29-answer-quality-metrics). The defaults come from the plan and research R2 to R8.
QUALITY_SUMMARY_FILE = "quality-summary.csv"
QUALITY_DETAIL_FILE = "quality-detail.csv"
METRIC_FAITHFULNESS = "faithfulness"
METRIC_ANSWER_RELEVANCY = "answer_relevancy"
METRIC_CONTEXT_PRECISION = "context_precision"
QUALITY_METRICS = (METRIC_FAITHFULNESS, METRIC_ANSWER_RELEVANCY, METRIC_CONTEXT_PRECISION)

# The judge is a model of the LiteLLM gateway (LAB-84) under its logical name; the real model sits behind it (R2).
DEFAULT_JUDGE_MODEL = "judge"
DEFAULT_JUDGE_BASE_URL = "http://localhost:4000/v1"
DEFAULT_JUDGE_TEMPERATURE = 0.0
MAX_JUDGE_TEMPERATURE = 2.0
DEFAULT_JUDGE_TIMEOUT_SECONDS = 120
# Characters of question, answer, context and reference sent to the judge for one case; above it the case is an error.
DEFAULT_MAX_INPUT_CHARS = 24000
JUDGE_SEED = 7
DEFAULT_CONCURRENCY = 2
MIN_CONCURRENCY = 1
MAX_CONCURRENCY = 16
DEFAULT_TURN_TIMEOUT_SECONDS = 120
EMBEDDINGS_MODEL = "embeddings"
# One search of this text tells which embedding model the knowledge base is indexed with (the answer names it).
EMBEDDING_PROBE_QUERY = "evaluación de calidad de respuesta"

# Identities of a run (data-model): the request id is `eval-<run_id>-<case id>` and must stay a safe id of the backend's
# RequestIdFilter (letters, digits, dot, underscore and hyphen, 1 to 64 characters).
RUN_ID_PATTERN = r"^[A-Za-z0-9._-]{1,32}$"
REQUEST_ID_PREFIX = "eval"
MAX_REQUEST_ID_LENGTH = 64
# ADR-0022 section 4: the trace id is the first 32 hexadecimal characters of SHA-256(X-Request-Id).
TRACE_ID_LENGTH = 32
SCORE_ID_LENGTH = 32

CHAT_PATH = "/api/chat"
MODELS_PATH = "/models"
MODEL_INFO_PATH = "/model/info"
DEFAULT_LANGFUSE_HOST = "http://localhost:3001"
LANGFUSE_TRACES_PATH = "/api/public/traces"
LANGFUSE_SCORES_PATH = "/api/public/scores"
TRACE_WAIT_SECONDS = 30
TRACE_POLL_SECONDS = 1.0

# The ingress of the cluster routes by the Host header: with it the backend is reached through 127.0.0.1 without
# relying on the public DNS of *.localtest.me (shoplab-infra/scripts/seed.sh does the same).
HOST_HEADER_ENV = "SHOPLAB_EVAL_HOST_HEADER"
HOST_HEADER_PATTERN = r"^[A-Za-z0-9.-]+(:[0-9]{1,5})?$"
JUDGE_MODEL_ENV = "SHOPLAB_EVAL_JUDGE_MODEL"
JUDGE_BASE_URL_ENV = "SHOPLAB_EVAL_JUDGE_BASE_URL"
JUDGE_API_KEY_ENV = "SHOPLAB_EVAL_JUDGE_API_KEY"
ASSISTANT_MODEL_ENV = "SHOPLAB_EVAL_ASSISTANT_MODEL"
LANGFUSE_HOST_ENV = "SHOPLAB_EVAL_LANGFUSE_HOST"
LANGFUSE_PUBLIC_KEY_ENV = "SHOPLAB_EVAL_LANGFUSE_PUBLIC_KEY"
LANGFUSE_SECRET_KEY_ENV = "SHOPLAB_EVAL_LANGFUSE_SECRET_KEY"

UNKNOWN = "unknown"
# Version of the abstention check of the judge (a prompt of ours, not of RAGAS): bump it when its text changes (R6).
ABSTENTION_PROMPT_VERSION = "2"
