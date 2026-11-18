"""Golden dataset of ShopLab Helpdesk: its cases, their reading and their validation."""

from shoplab_eval.errors import InvalidGoldenError
from shoplab_eval.golden import GoldenCase, load_golden, validate_golden

__all__ = ["GoldenCase", "InvalidGoldenError", "load_golden", "validate_golden"]
