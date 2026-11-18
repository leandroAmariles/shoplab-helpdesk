from collections.abc import Iterator
from pathlib import Path

import pytest

from fake_backend import FakeBackend
from helpers import write_toy_kb


@pytest.fixture
def toy_kb(tmp_path: Path) -> Path:
    return write_toy_kb(tmp_path / "kb")


@pytest.fixture
def backend() -> Iterator[FakeBackend]:
    """A local HTTP server playing the backend; a test sets its handlers and reads the requests it received."""
    server = FakeBackend().start()
    try:
        yield server
    finally:
        server.stop()
