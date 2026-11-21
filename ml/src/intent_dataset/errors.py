"""Typed errors of the dataset tooling. Messages are in Spanish, say how to fix the problem and never carry a key."""

from dataclasses import dataclass


class InvalidDatasetError(Exception):
    """The dataset, or a file it is built from, breaks a rule."""


class ReviewError(Exception):
    """The review CSV is incomplete or inconsistent; the message lists every offending row."""


class ParaphraseError(Exception):
    """The model of the gateway could not produce usable output."""


class ConfigurationError(Exception):
    """A required variable, file or service is missing (exit code 2)."""


@dataclass(frozen=True)
class Issue:
    """One failed rule of the dataset: which rule, which example (``None`` for set-level rules) and why."""

    rule: str
    example_id: str | None
    message: str

    def format(self) -> str:
        where = self.example_id if self.example_id else "SET"
        return f"[{self.rule}] {where}: {self.message}"


def sort_issues(issues: list[Issue]) -> list[Issue]:
    """Stable, readable order: by rule, then by example (set-level issues first)."""
    return sorted(issues, key=lambda issue: (issue.rule, issue.example_id or "", issue.message))
