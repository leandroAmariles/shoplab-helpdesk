"""Fixtures of the intent dataset tests; the builders live in ``intent_samples`` (a unique module name)."""

import pytest
from intent_samples import SMALL_RULES, make_dataset

from intent_dataset.constants import DatasetRules
from intent_dataset.dataset import Example


@pytest.fixture
def rules() -> DatasetRules:
    return SMALL_RULES


@pytest.fixture
def dataset() -> list[Example]:
    return make_dataset()
