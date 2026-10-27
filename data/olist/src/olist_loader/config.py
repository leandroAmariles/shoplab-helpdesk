"""Settings read from the environment; secrets never appear in repr or logs."""

import os
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_URL = "postgresql://localhost:5432/shoplab"
DEFAULT_USER = "shoplab"
DEFAULT_PASSWORD = "change-me-local-only"  # local-only value of infra/docker-compose.yml
DEFAULT_DATA_DIR = Path("data/raw")
CONNECT_TIMEOUT_SECONDS = 5


def normalize_url(url: str) -> str:
    """Accepts the JDBC form the backend uses in POSTGRES_URL (jdbc:postgresql://...) as well as a plain URI."""
    return url.removeprefix("jdbc:")


@dataclass(frozen=True)
class Settings:
    postgres_url: str = DEFAULT_URL
    postgres_user: str = DEFAULT_USER
    postgres_password: str = field(default=DEFAULT_PASSWORD, repr=False)
    kaggle_username: str | None = None
    kaggle_key: str | None = field(default=None, repr=False)
    data_dir: Path = DEFAULT_DATA_DIR

    @classmethod
    def from_env(cls, data_dir: Path | None = None) -> "Settings":
        env = os.environ
        return cls(
            postgres_url=normalize_url(env.get("POSTGRES_URL", DEFAULT_URL)),
            postgres_user=env.get("POSTGRES_USER", DEFAULT_USER),
            postgres_password=env.get("POSTGRES_PASSWORD", DEFAULT_PASSWORD),
            kaggle_username=env.get("KAGGLE_USERNAME") or None,
            kaggle_key=env.get("KAGGLE_KEY") or None,
            data_dir=data_dir if data_dir is not None else DEFAULT_DATA_DIR,
        )

    def connect_kwargs(self) -> dict[str, str | int]:
        # Without a timeout an unreachable host leaves the command hanging for minutes (seen on Windows).
        return {
            "conninfo": self.postgres_url,
            "user": self.postgres_user,
            "password": self.postgres_password,
            "connect_timeout": CONNECT_TIMEOUT_SECONDS,
        }
