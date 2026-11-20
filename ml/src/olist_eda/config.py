"""Settings read from the environment. Defaults are the local-only values of infra/docker-compose.yml."""

import os
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parents[3]
NOTEBOOK_DATA_DIR = REPO_ROOT / "ml" / "notebooks" / "data"
NOTEBOOK_RESULTS_DIR = REPO_ROOT / "ml" / "notebooks" / "results"
DEFAULT_URL = "postgresql://localhost:5432/shoplab"
DEFAULT_USER = "shoplab"
DEFAULT_PASSWORD = "change-me-local-only"  # local-only value of infra/docker-compose.yml, not a secret
DEFAULT_MIN_GROUP_ORDERS = 30
DEFAULT_TOP_N = 10
MIN_GROUP_ORDERS_VARIABLE = "EDA_MIN_GROUP_ORDERS"


def normalize_url(url: str) -> str:
    """Accepts the JDBC form the backend uses in POSTGRES_URL (jdbc:postgresql://...) as well as a plain URI."""
    return url.removeprefix("jdbc:")


def _positive_int(raw: str) -> int:
    try:
        return int(raw)
    except ValueError:
        raise ValueError(f"{MIN_GROUP_ORDERS_VARIABLE} debe ser un entero mayor o igual que 1, no {raw!r}") from None


@dataclass(frozen=True)
class AnalysisSettings:
    """Connection, thresholds and paths of the analysis. The password never appears in repr or logs."""

    postgres_url: str = DEFAULT_URL
    postgres_user: str = DEFAULT_USER
    postgres_password: str = field(default=DEFAULT_PASSWORD, repr=False)
    min_group_orders: int = DEFAULT_MIN_GROUP_ORDERS
    kb_dir: Path = REPO_ROOT / "data" / "kb"
    top_n: int = DEFAULT_TOP_N

    def __post_init__(self) -> None:
        if self.min_group_orders < 1:
            raise ValueError(
                f"{MIN_GROUP_ORDERS_VARIABLE} debe ser un entero mayor o igual que 1, no {self.min_group_orders}"
            )

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "AnalysisSettings":
        """Reads the settings; an empty variable counts as not defined, like in the project's compose file."""
        source = os.environ if env is None else env

        def read(name: str, default: str) -> str:
            return source.get(name) or default

        kb_dir = Path(read("KB_DIR", str(REPO_ROOT / "data" / "kb")))
        if not kb_dir.is_absolute():
            kb_dir = REPO_ROOT / kb_dir
        return cls(
            postgres_url=normalize_url(read("POSTGRES_URL", DEFAULT_URL)),
            postgres_user=read("POSTGRES_USER", DEFAULT_USER),
            postgres_password=read("POSTGRES_PASSWORD", DEFAULT_PASSWORD),
            min_group_orders=_positive_int(read(MIN_GROUP_ORDERS_VARIABLE, str(DEFAULT_MIN_GROUP_ORDERS))),
            kb_dir=kb_dir,
        )

    def describe(self) -> dict[str, str | int]:
        """What is safe to print in a saved notebook: no password, no host and no absolute local path."""
        try:
            kb_dir = self.kb_dir.relative_to(REPO_ROOT).as_posix()
        except ValueError:
            kb_dir = self.kb_dir.name
        return {
            "base de datos": urlparse(self.postgres_url).path.lstrip("/"),
            "usuario": self.postgres_user,
            "min_group_orders": self.min_group_orders,
            "kb_dir": kb_dir,
            "top_n": self.top_n,
        }
