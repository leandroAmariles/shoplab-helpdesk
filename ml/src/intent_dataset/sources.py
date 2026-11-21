"""Where candidates come from: the golden file and, read-only, the Olist reviews. Both come out already masked.

The golden is read as a plain JSON file (``shoplab_eval`` is another project and is not imported). Reviews are read with
the read-only connection of ``olist_eda`` and masked **before** they are returned, so an unmasked comment never reaches
a file. The review id is never kept: the group id is a short hash of it.
"""

import hashlib
import json
import random
import re
from collections.abc import Callable
from pathlib import Path
from typing import Any

import psycopg

from intent_dataset.constants import GOLDEN, GOLDEN_CATEGORY_LABELS, OLIST_REVIEW
from intent_dataset.errors import ConfigurationError
from intent_dataset.masking import mask_text
from intent_dataset.review import Candidate
from intent_dataset.text import normalize_text
from olist_eda.config import AnalysisSettings
from olist_eda.db import connect

MIN_REVIEW_CHARS = 20
MAX_REVIEW_CHARS = 280
MIN_REAL_CHARS = 10  # characters left once the placeholders are removed; below it the comment says nothing
SELECT_REVIEWS = (
    "SELECT review_id, comment_message FROM orders.order_reviews WHERE comment_message IS NOT NULL ORDER BY id"
)

_PLACEHOLDER = re.compile(r"\[[A-Z]+\]")
# Keyword hints, checked in the priority order of the labeling guide. A hint is NOT a label: it only spreads the
# candidates over the classes so the human work is not looking for a needle among a hundred thousand reviews.
_HINTS: tuple[tuple[str, re.Pattern[str]], ...] = (
    (
        "escalado",
        re.compile(r"reclam|procon|cancel|atendente|reembols|devol|processo|justi[cç]a|falar com|golpe|enganad"),
    ),
    ("pedido", re.compile(r"pedido|entreg|chegou|chegar|prazo|receb|atras|rastre")),
    ("producto", re.compile(r"produto|qualidade|defeito|tamanho|funciona|material|original|cor\b")),
)
HINT_ORDER = ("pedido", "producto", "escalado", "")


def hint_for(text: str) -> str:
    """A keyword guess of the class a comment may belong to, or empty. It is never written as a label."""
    lowered = text.lower()
    for hint, pattern in _HINTS:
        if pattern.search(lowered):
            return hint
    return ""


def read_golden(path: Path) -> list[Candidate]:
    """One candidate per golden case, with the label proposed for its category (a person confirms each one)."""
    if not path.is_file():
        raise ConfigurationError(f"No se encuentra el golden en {path}: la historia lo lee de eval/golden.jsonl.")
    candidates = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        case = json.loads(line)
        category = case.get("category")
        if category not in GOLDEN_CATEGORY_LABELS:
            raise ConfigurationError(
                f"{path.name}, línea {number}: categoría «{category}» sin etiqueta propuesta; "
                "añádela a GOLDEN_CATEGORY_LABELS."
            )
        candidates.append(
            Candidate(
                candidate_id=f"C{case['id']}",
                source=GOLDEN,
                group_id=case["id"],
                parent_id="",
                golden_id=case["id"],
                language="es",
                text=case["question"],
                proposed_label=GOLDEN_CATEGORY_LABELS[category],
                hint="",
                decision="",
                label="",
                text_edit="",
                pii_checked="",
                note="",
            )
        )
    return candidates


def _group_id(review_id: str) -> str:
    return "R" + hashlib.sha256(review_id.encode()).hexdigest()[:8]


def _eligible(rows: list[tuple[Any, ...]]) -> list[tuple[str, str, str]]:
    """(group id, masked text, hint) of the comments worth reviewing: right length, with content, no duplicates."""
    seen_texts: set[str] = set()
    seen_groups: set[str] = set()
    eligible = []
    for review_id, message in rows:
        if not message or not MIN_REVIEW_CHARS <= len(message.strip()) <= MAX_REVIEW_CHARS:
            continue
        text = mask_text(message.strip())
        if len(normalize_text(_PLACEHOLDER.sub(" ", text))) < MIN_REAL_CHARS:
            continue
        group_id = _group_id(review_id)
        key = normalize_text(text)
        if key in seen_texts or group_id in seen_groups:
            continue
        seen_texts.add(key)
        seen_groups.add(group_id)
        eligible.append((group_id, text, hint_for(text)))
    return eligible


def _spread(eligible: list[tuple[str, str, str]], limit: int, seed: int) -> list[tuple[str, str, str]]:
    """Up to ``limit`` comments, taken in turn from each hint bucket, in a seeded order (deterministic)."""
    random_source = random.Random(seed)
    buckets: dict[str, list[tuple[str, str, str]]] = {hint: [] for hint in HINT_ORDER}
    for item in sorted(eligible):
        buckets[item[2]].append(item)
    for bucket in buckets.values():
        random_source.shuffle(bucket)
    chosen: list[tuple[str, str, str]] = []
    while len(chosen) < limit and any(buckets.values()):
        for hint in HINT_ORDER:
            if buckets[hint] and len(chosen) < limit:
                chosen.append(buckets[hint].pop())
    return chosen


def read_reviews(
    settings: AnalysisSettings,
    *,
    connector: Callable[[AnalysisSettings], Any] = connect,
    limit: int,
    seed: int,
) -> list[Candidate]:
    """Comments of Olist reviews, masked, as candidates. Read-only; nothing is written anywhere."""
    try:
        connection = connector(settings)
    except psycopg.Error as error:
        detail = str(error).strip().splitlines()[0] if str(error).strip() else "sin detalle"
        raise ConfigurationError(
            "No se pudo conectar a la base con Olist. Levántala con "
            "`docker compose -f infra/docker-compose.yml up -d --wait` y carga Olist (data/olist), "
            f"o usa --skip-reviews. Detalle: {detail}"
        ) from error
    try:
        rows = connection.execute(SELECT_REVIEWS).fetchall()
    except psycopg.Error as error:
        raise ConfigurationError(
            "No se pudo leer orders.order_reviews: arranca el backend para aplicar las migraciones y carga Olist."
        ) from error
    finally:
        connection.close()
    return [
        Candidate(
            candidate_id=f"C{group_id}",
            source=OLIST_REVIEW,
            group_id=group_id,
            parent_id="",
            golden_id="",
            language="pt",
            text=text,
            proposed_label="",
            hint=hint,
            decision="",
            label="",
            text_edit="",
            pii_checked="",
            note="",
        )
        for group_id, text, hint in _spread(_eligible(rows), limit, seed)
    ]
