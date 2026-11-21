"""Masking of personal data by pattern (research R3). Depends on nothing else of the package, so it can be reused.

Each kind found is replaced by a fixed placeholder (``[EMAIL]``, ``[PHONE]``…). The placeholders match none of the
patterns, so masking is idempotent. **Personal names are NOT recognised here**: no reliable pattern exists, and they
are covered by the manual review of every Olist comment (column ``pii_checked``).

It over-masks on purpose: a long number that was not personal loses its value, a personal one that slips through
cannot be taken back. Any run of 8 or more digits is masked as a phone, and so is an unformatted CPF, which is
indistinguishable from one. A hyphenated pair of 4-digit numbers (``2016-2018``) is masked too.
"""

import re
from dataclasses import dataclass

# Applied in this order: a URL may contain an email-like part, a CNPJ must go before a CPF, and the phone and digit-run
# patterns go last because they are the loosest.
_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("URL", re.compile(r"(?:https?://|www\.)\S+", re.IGNORECASE)),
    ("EMAIL", re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")),
    ("ID", re.compile(r"(?<![0-9A-Za-z])[0-9a-fA-F]{32}(?![0-9A-Za-z])")),
    ("CNPJ", re.compile(r"(?<!\d)\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}(?!\d)")),
    ("CPF", re.compile(r"(?<!\d)\d{3}\.\d{3}\.\d{3}-\d{2}(?!\d)")),
    ("CEP", re.compile(r"(?<!\d)\d{5}-\d{3}(?!\d)")),
    (
        "PHONE",
        re.compile(
            r"(?<![\w(+])(?:\+\d{1,3}[\s.-]?)?(?:\(\d{2}\)|\d{2})[\s.-]?9?\d{4}[\s.-]?\d{4}(?!\d)"  # with area code
            r"|(?<!\d)9?\d{4}[\s.-]\d{4}(?!\d)"  # local number with a separator
            r"|(?<!\d)\d{8,}(?!\d)"  # any long run of digits
        ),
    ),
)


@dataclass(frozen=True)
class PersonalDataMatch:
    """A piece of personal data found in a text: its kind and the fragment (never print the fragment in a log)."""

    kind: str
    fragment: str


def _scan(text: str) -> tuple[str, list[PersonalDataMatch]]:
    matches: list[PersonalDataMatch] = []
    masked = text
    for kind, pattern in _PATTERNS:

        def replace(match: re.Match[str], kind: str = kind) -> str:
            matches.append(PersonalDataMatch(kind, match.group(0)))
            return f"[{kind}]"

        masked = pattern.sub(replace, masked)
    return masked, matches


def mask_text(text: str) -> str:
    """The text with every personal datum recognised by pattern replaced by the placeholder of its kind."""
    return _scan(text)[0]


def find_personal_data(text: str) -> list[PersonalDataMatch]:
    """The personal data a text still contains; empty for a masked text. The validator fails if it finds any."""
    return _scan(text)[1]
