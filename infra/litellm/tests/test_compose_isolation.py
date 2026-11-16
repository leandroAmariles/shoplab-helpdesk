"""Ollama is reachable only through the gateway (FR-002, US1 scenario 4). Skipped when docker compose is missing."""

import json
import pathlib
import shutil
import subprocess

import pytest

INFRA = pathlib.Path(__file__).resolve().parents[2]

pytestmark = pytest.mark.skipif(shutil.which("docker") is None, reason="docker compose is not installed")


def _config(*extra_files: str) -> dict:
    command = ["docker", "compose", "-f", str(INFRA / "docker-compose.yml")]
    for name in extra_files:
        command += ["-f", str(INFRA / name)]
    command += ["config", "--format", "json"]
    result = subprocess.run(command, capture_output=True, text=True, check=False, encoding="utf-8")
    if result.returncode != 0:
        pytest.skip(f"docker compose config is not available: {result.stderr.strip()[:120]}")
    return json.loads(result.stdout)


def _networks(service: dict) -> set[str]:
    return set((service.get("networks") or {"default": None}).keys())


def test_ollama_does_not_publish_a_port_by_default():
    assert "ports" not in _config()["services"]["ollama"]


def test_models_network_has_only_ollama_and_the_gateway():
    services = _config()["services"]
    members = {name for name, service in services.items() if "models" in _networks(service)}
    assert members == {"ollama", "litellm"}


def test_application_services_cannot_reach_ollama():
    services = _config()["services"]
    for name in ("ingest", "postgres", "litellm-postgres", "litellm-bootstrap"):
        assert "models" not in _networks(services[name]), name
    assert _networks(services["ollama"]) == {"models"}


def test_the_gateway_bridges_both_networks():
    assert _networks(_config()["services"]["litellm"]) == {"default", "models"}


def test_host_override_publishes_the_ollama_port_for_dev():
    ports = _config("docker-compose.ollama-host.yml")["services"]["ollama"]["ports"]
    assert any(str(port["target"]) == "11434" and str(port["published"]) == "11434" for port in ports)
