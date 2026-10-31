"""Prometheus metrics of the service. Labels never carry data of the document."""

from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

REQUESTS = Counter("ingest_requests", "POST /ingest requests by outcome", ["outcome"])
DURATION = Histogram("ingest_duration_seconds", "Time spent answering POST /ingest")

OUTCOME_OK = "ok"
OUTCOME_REJECTED = "rejected"
OUTCOME_FAILED = "failed"


def outcome_of(status_code: int) -> str:
    if status_code < 400:
        return OUTCOME_OK
    if status_code < 500:
        return OUTCOME_REJECTED
    return OUTCOME_FAILED


def record(status_code: int, seconds: float) -> None:
    REQUESTS.labels(outcome=outcome_of(status_code)).inc()
    DURATION.observe(seconds)


def render() -> tuple[bytes, str]:
    return generate_latest(), CONTENT_TYPE_LATEST
