"""Typed errors of the golden dataset."""

import sys
from dataclasses import dataclass


@dataclass(frozen=True)
class Issue:
    """One rule that the dataset breaks.

    ``case_id`` is the id of the case, ``SET`` for a rule of the whole dataset or ``LINE n`` when the line could not be
    read. ``line`` is the line of the file, used only to order the report.
    """

    code: str
    case_id: str
    message: str
    line: int | None = None

    def format(self) -> str:
        return f"{self.code} {self.case_id}: {self.message}"


def sort_issues(issues: list[Issue]) -> list[Issue]:
    """Orders by line of the file (rules of the whole dataset last), then by code and case, so reports are stable."""
    return sorted(issues, key=lambda issue: (issue.line if issue.line else sys.maxsize, issue.code, issue.case_id))


class InvalidGoldenError(Exception):
    """The dataset breaks one or more rules; the message lists all of them, one per line."""

    def __init__(self, issues: list[Issue]):
        self.issues = sort_issues(issues)
        super().__init__("\n".join(issue.format() for issue in self.issues))


class MeasurementError(Exception):
    """The retrieval measurement cannot give a reliable result (search down, index incomplete, bad response).

    The message names the cause and, when it applies, the case, the documents or the URL involved. It is never raised
    for a search that works but does not return the expected document: that is a zero in the metrics.
    """


class StoreError(Exception):
    """The history of evaluation runs cannot be reached or refuses a run (LAB-31).

    The message names the cause and what to do about it. It never carries the token. Before the measurement it stops the
    command (nothing has been asked of the assistant or the judge yet); after it, the CSV files are already written and
    the command ends with a non-zero code because the run was not saved.
    """
