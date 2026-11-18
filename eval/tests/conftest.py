from pathlib import Path

import pytest

from helpers import write_toy_kb


@pytest.fixture
def toy_kb(tmp_path: Path) -> Path:
    return write_toy_kb(tmp_path / "kb")
