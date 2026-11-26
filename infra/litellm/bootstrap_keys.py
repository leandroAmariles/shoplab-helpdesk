"""Idempotent creation of the gateway's virtual keys from ``keys.yaml`` (LAB-84, FR-018/019/023).

LiteLLM has no declarative section for virtual keys, so this script upserts them through the admin API using the master
key: it creates a key that does not exist and updates budget, period and allowed models when they changed. It never
deletes keys it does not know and never prints key values (only aliases).

Environment: ``LITELLM_BASE_URL`` (default http://litellm:4000), ``LITELLM_MASTER_KEY``, ``GATEWAY_ENVIRONMENT``
(``local`` | ``dev``), the variables named by ``key_env`` in ``keys.yaml``, ``BOOTSTRAP_TIMEOUT`` seconds (default 120)
and optionally ``CLOUD_PROVIDER_API_KEY`` (enables the ``models_when`` entries).

Usage: ``python bootstrap_keys.py [keys.yaml]``
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Mapping
from typing import Any

import yaml

from render_config import render

ALIAS_PATTERN = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
DURATIONS = {"1h", "1d", "7d", "30d"}
CONSUMERS = {"chat", "ingest", "eval", "retrieval"}
ENVIRONMENTS = {"local", "dev"}
PLACEHOLDER_MARKERS = ("change-me", "local")
# LiteLLM treats an empty model list as "every model"; a key that must reach none gets a name that does not exist.
NO_MODEL_ACCESS = "shoplab-no-model-access"


class ConfigError(ValueError):
    """keys.yaml or its environment is invalid; nothing was sent to the gateway."""


class GatewayError(RuntimeError):
    """The gateway answered with an unexpected error."""


def load_keys(text: str, env: Mapping[str, str]) -> tuple[str, list[dict[str, Any]]]:
    environment = env.get("GATEWAY_ENVIRONMENT", "")
    if environment not in ENVIRONMENTS:
        raise ConfigError(f"GATEWAY_ENVIRONMENT must be one of {sorted(ENVIRONMENTS)}, got {environment!r}")
    document = yaml.safe_load(render(text, env)) or {}
    keys = document.get("keys") or []
    if not keys:
        raise ConfigError("keys.yaml defines no keys")
    return environment, keys


def validate(environment: str, keys: list[dict[str, Any]], env: Mapping[str, str]) -> list[dict[str, Any]]:
    """Returns the keys normalised for the API, or raises ConfigError."""
    seen: set[str] = set()
    resolved = []
    for entry in keys:
        alias = str(entry.get("alias", ""))
        if not ALIAS_PATTERN.match(alias):
            raise ConfigError(f"alias {alias!r} must match {ALIAS_PATTERN.pattern}")
        if alias in seen:
            raise ConfigError(f"alias {alias!r} is duplicated")
        seen.add(alias)
        if entry.get("consumer") not in CONSUMERS:
            raise ConfigError(f"{alias}: consumer must be one of {sorted(CONSUMERS)}")
        budget = entry.get("max_budget")
        if isinstance(budget, bool) or not isinstance(budget, (int, float)) or budget <= 0:
            raise ConfigError(f"{alias}: max_budget must be a number greater than 0")
        duration = str(entry.get("budget_duration", ""))
        if duration not in DURATIONS:
            raise ConfigError(f"{alias}: budget_duration must be one of {sorted(DURATIONS)}")
        key_env = str(entry.get("key_env", ""))
        value = env.get(key_env, "") if key_env else ""
        if not value:
            raise ConfigError(f"{alias}: variable {key_env or '(key_env missing)'} is not defined or is empty")
        if environment == "dev" and any(marker in value.lower() for marker in PLACEHOLDER_MARKERS):
            raise ConfigError(f"{alias}: placeholder key values are not allowed in dev")
        models = list(entry.get("models") or [])
        for extra in entry.get("models_when") or []:
            if env.get(str(extra.get("env", "")), ""):
                models.extend(extra.get("models") or [])
        resolved.append(
            {
                "alias": alias,
                "key": value,
                "max_budget": float(budget),
                "budget_duration": duration,
                "models": sorted(set(models)) or [NO_MODEL_ACCESS],
            }
        )
    return resolved


class Gateway:
    def __init__(self, base_url: str, master_key: str, timeout: float = 10.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.master_key = master_key
        self.timeout = timeout

    def _request(self, method: str, path: str, body: dict | None = None, auth: bool = True) -> tuple[int, Any]:
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(f"{self.base_url}{path}", data=data, method=method)
        request.add_header("Content-Type", "application/json")
        if auth:
            request.add_header("Authorization", f"Bearer {self.master_key}")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw) if raw else {}
            except ValueError:
                return error.code, {}

    def wait_until_ready(self, timeout_seconds: float, pause: float = 2.0) -> None:
        deadline = time.monotonic() + timeout_seconds
        while True:
            try:
                status, _ = self._request("GET", "/health/readiness", auth=False)
                if status == 200:
                    return
            except (urllib.error.URLError, OSError):
                pass
            if time.monotonic() >= deadline:
                raise GatewayError("the gateway did not become ready in time")
            time.sleep(pause)

    def key_info(self, key: str) -> dict | None:
        status, body = self._request("GET", f"/key/info?key={urllib.parse.quote(key, safe='')}")
        if status in (400, 404):
            return None
        if status != 200:
            raise GatewayError(f"/key/info answered {status}")
        return body.get("info") or body

    def create(self, wanted: dict) -> None:
        status, _ = self._request("POST", "/key/generate", self._payload(wanted, with_key=True))
        if status != 200:
            raise GatewayError(f"/key/generate answered {status} for {wanted['alias']}")

    def update(self, wanted: dict) -> None:
        status, _ = self._request("POST", "/key/update", self._payload(wanted, with_key=True))
        if status != 200:
            raise GatewayError(f"/key/update answered {status} for {wanted['alias']}")

    @staticmethod
    def _payload(wanted: dict, *, with_key: bool) -> dict:
        payload = {
            "key_alias": wanted["alias"],
            "max_budget": wanted["max_budget"],
            "budget_duration": wanted["budget_duration"],
            "models": wanted["models"],
        }
        if with_key:
            payload["key"] = wanted["key"]
        return payload


def differs(info: dict, wanted: dict) -> bool:
    current_models = sorted(info.get("models") or [])
    return (
        info.get("max_budget") is None
        or float(info["max_budget"]) != wanted["max_budget"]
        or info.get("budget_duration") != wanted["budget_duration"]
        or current_models != wanted["models"]
    )


def converge(gateway: Gateway, wanted_keys: list[dict]) -> list[str]:
    lines = []
    for wanted in wanted_keys:
        info = gateway.key_info(wanted["key"])
        if info is None:
            gateway.create(wanted)
            lines.append(f"created alias={wanted['alias']}")
        elif differs(info, wanted):
            gateway.update(wanted)
            lines.append(f"updated alias={wanted['alias']}")
        else:
            lines.append(f"unchanged alias={wanted['alias']}")
    return lines


def main(argv: list[str], env: Mapping[str, str] | None = None) -> int:
    env = os.environ if env is None else env
    path = argv[1] if len(argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "keys.yaml")
    try:
        with open(path, encoding="utf-8") as handle:
            environment, keys = load_keys(handle.read(), env)
        wanted_keys = validate(environment, keys, env)
        master = env.get("LITELLM_MASTER_KEY", "")
        if not master:
            raise ConfigError("LITELLM_MASTER_KEY is not defined")
    except (ConfigError, OSError, yaml.YAMLError, ValueError) as error:
        print(f"bootstrap_keys: {error}", file=sys.stderr)
        return 1
    gateway = Gateway(env.get("LITELLM_BASE_URL", "http://litellm:4000"), master)
    try:
        gateway.wait_until_ready(float(env.get("BOOTSTRAP_TIMEOUT", "120")))
        for line in converge(gateway, wanted_keys):
            print(line, flush=True)
    except (GatewayError, urllib.error.URLError, OSError) as error:
        print(f"bootstrap_keys: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
