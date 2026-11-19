"""Warns about local models that the gateway configuration names but Ollama has not downloaded (FR-008).

Reads the rendered LiteLLM config, asks every Ollama ``api_base`` for ``/api/tags`` and prints one line per missing
model: ``WARN model_missing model=<ollama model> name=<logical name>``. It never blocks the start: the fallback model
may be intentionally absent on small machines, and Ollama may still be starting (it waits up to
``CHECK_MODELS_TIMEOUT`` seconds, default 60). It always exits with 0.

Usage: ``python check_models.py <rendered-config.yaml>``
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

import yaml

OLLAMA_PREFIXES = ("ollama/", "ollama_chat/")


def configured_models(config: dict) -> list[tuple[str, str, str]]:
    """(logical name, ollama model, api_base) for every local Ollama deployment."""
    found = []
    for entry in config.get("model_list") or []:
        params = entry.get("litellm_params") or {}
        model = str(params.get("model") or "")
        prefix = next((p for p in OLLAMA_PREFIXES if model.startswith(p)), None)
        if prefix is None:
            continue
        found.append((str(entry.get("model_name")), model[len(prefix) :], str(params.get("api_base") or "")))
    return found


def with_tag(model: str) -> str:
    """Ollama lists ``bge-m3`` as ``bge-m3:latest``."""
    return model if ":" in model else f"{model}:latest"


def fetch_tags(api_base: str, timeout: float = 5.0) -> set[str]:
    with urllib.request.urlopen(f"{api_base.rstrip('/')}/api/tags", timeout=timeout) as response:
        payload = json.load(response)
    names: set[str] = set()
    for item in payload.get("models") or []:
        for key in ("name", "model"):
            if item.get(key):
                names.add(with_tag(item[key]))
    return names


def wait_for_tags(api_base: str, timeout_seconds: float, pause: float = 2.0) -> set[str] | None:
    deadline = time.monotonic() + timeout_seconds
    while True:
        try:
            return fetch_tags(api_base)
        except (urllib.error.URLError, OSError, ValueError):
            if time.monotonic() >= deadline:
                return None
            time.sleep(pause)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: check_models.py <rendered-config.yaml>", file=sys.stderr)
        return 0
    with open(argv[1], encoding="utf-8") as handle:
        config = yaml.safe_load(handle) or {}
    timeout = float(os.environ.get("CHECK_MODELS_TIMEOUT", "60"))
    cache: dict[str, set[str] | None] = {}
    for name, model, api_base in configured_models(config):
        if api_base not in cache:
            cache[api_base] = wait_for_tags(api_base, timeout)
        tags = cache[api_base]
        if tags is None:
            print(f"WARN ollama_unreachable api_base={api_base} name={name}", flush=True)
        elif with_tag(model) not in tags:
            print(f"WARN model_missing model={model} name={name}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
