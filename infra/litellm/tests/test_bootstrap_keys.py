import json
import pathlib
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

import pytest

import bootstrap_keys

KEYS_YAML = pathlib.Path(bootstrap_keys.__file__).with_name("keys.yaml")
MASTER = "sk-master-test"


class FakeGateway:
    """In-memory stand-in for the LiteLLM admin API (/health/readiness, /key/info, /key/generate, /key/update)."""

    def __init__(self):
        self.keys: dict[str, dict] = {}
        self.calls: list[str] = []
        self.ready = True
        server = self

        class Handler(BaseHTTPRequestHandler):
            def _send(self, status, body):
                payload = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(payload)

            def _authorised(self):
                return self.headers.get("Authorization") == f"Bearer {MASTER}"

            def do_GET(self):
                url = urlparse(self.path)
                server.calls.append(f"GET {url.path}")
                if url.path == "/health/readiness":
                    return self._send(200 if server.ready else 503, {})
                if not self._authorised():
                    return self._send(401, {})
                key = parse_qs(url.query).get("key", [""])[0]
                if key not in server.keys:
                    return self._send(404, {"error": "not found"})
                return self._send(200, {"info": server.keys[key]})

            def do_POST(self):
                server.calls.append(f"POST {self.path}")
                if not self._authorised():
                    return self._send(401, {})
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                key = body["key"]
                if self.path == "/key/generate":
                    server.keys[key] = {k: v for k, v in body.items() if k != "key"}
                    return self._send(200, {"key": key})
                if self.path == "/key/update":
                    server.keys[key].update({k: v for k, v in body.items() if k != "key"})
                    return self._send(200, {})
                return self._send(404, {})

            def log_message(self, *args):
                pass

        self.http = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.http.server_port}"

    def close(self):
        self.http.shutdown()


@pytest.fixture
def gateway():
    fake = FakeGateway()
    yield fake
    fake.close()


@pytest.fixture
def env(gateway):
    return {
        "LITELLM_BASE_URL": gateway.url,
        "LITELLM_MASTER_KEY": MASTER,
        "GATEWAY_ENVIRONMENT": "local",
        "LITELLM_KEY_CHAT": "sk-shoplab-chat-local",
        "LITELLM_KEY_INGEST": "sk-shoplab-ingest-local",
        "LITELLM_KEY_EVAL": "sk-shoplab-eval-local",
        "LITELLM_KEY_RETRIEVAL": "sk-shoplab-retrieval-local",
        "BOOTSTRAP_TIMEOUT": "2",
    }


def _run(env, path=KEYS_YAML):
    return bootstrap_keys.main(["bootstrap_keys.py", str(path)], env)


def test_first_run_creates_every_key_and_prints_only_aliases(gateway, env, capsys):
    assert _run(env) == 0
    out = capsys.readouterr().out.splitlines()
    assert out == [
        "created alias=chat-local",
        "created alias=retrieval-local",
        "created alias=ingest-local",
        "created alias=eval-local",
    ]
    assert "sk-shoplab" not in " ".join(out)
    chat = gateway.keys["sk-shoplab-chat-local"]
    assert chat["models"] == ["chat", "chat-fallback"]
    assert chat["max_budget"] == 5.0
    assert chat["budget_duration"] == "1d"


def test_eval_key_only_reaches_the_judge_and_the_embeddings(gateway, env):
    """LAB-29: the evaluation key cannot call the assistant's chat models, and has a budget of its own."""
    _run(env)
    evaluation = gateway.keys["sk-shoplab-eval-local"]
    assert evaluation["models"] == ["embeddings", "judge"]
    assert evaluation["key_alias"] == "eval-local"
    assert evaluation["max_budget"] > 0


def test_eval_key_is_not_allowed_the_cloud_model_even_with_a_provider_key(gateway, env):
    _run({**env, "CLOUD_PROVIDER_API_KEY": "provider-key"})
    assert "chat-cloud" not in gateway.keys["sk-shoplab-eval-local"]["models"]


def test_second_run_is_idempotent(gateway, env, capsys):
    _run(env)
    capsys.readouterr()
    assert _run(env) == 0
    assert capsys.readouterr().out.splitlines() == [
        "unchanged alias=chat-local",
        "unchanged alias=retrieval-local",
        "unchanged alias=ingest-local",
        "unchanged alias=eval-local",
    ]
    assert sum(call == "POST /key/generate" for call in gateway.calls) == 4
    assert "POST /key/update" not in gateway.calls


def test_changed_budget_updates_the_existing_key(gateway, env, capsys, tmp_path):
    _run(env)
    capsys.readouterr()
    changed = tmp_path / "keys.yaml"
    changed.write_text(
        KEYS_YAML.read_text(encoding="utf-8").replace("max_budget: 5.00", "max_budget: 0.0001"), encoding="utf-8"
    )
    assert _run(env, changed) == 0
    assert "updated alias=chat-local" in capsys.readouterr().out
    assert gateway.keys["sk-shoplab-chat-local"]["max_budget"] == 0.0001


def test_ingest_has_no_model_access_instead_of_an_empty_list(gateway, env):
    _run(env)
    assert gateway.keys["sk-shoplab-ingest-local"]["models"] == [bootstrap_keys.NO_MODEL_ACCESS]


def test_cloud_model_is_allowed_only_with_a_provider_key(gateway, env):
    _run({**env, "CLOUD_PROVIDER_API_KEY": "provider-key"})
    assert gateway.keys["sk-shoplab-chat-local"]["models"] == ["chat", "chat-cloud", "chat-fallback"]


def test_unknown_keys_are_never_deleted(gateway, env):
    gateway.keys["sk-someone-else"] = {"key_alias": "manual-key", "models": ["chat"]}
    _run(env)
    assert "sk-someone-else" in gateway.keys


def test_waits_for_readiness_and_fails_when_it_never_comes(gateway, env, capsys, monkeypatch):
    monkeypatch.setattr(bootstrap_keys.time, "sleep", lambda _: None)
    gateway.ready = False
    env = {**env, "BOOTSTRAP_TIMEOUT": "0"}
    assert _run(env) == 1
    assert "did not become ready" in capsys.readouterr().err


def test_wrong_master_key_fails_without_creating_anything(gateway, env, capsys):
    assert _run({**env, "LITELLM_MASTER_KEY": "wrong"}) == 1
    assert gateway.keys == {}


@pytest.mark.parametrize(
    "mutation, message",
    [
        (lambda t: t.replace("alias: chat-${GATEWAY_ENVIRONMENT}", "alias: Chat_Local"), "alias"),
        (
            lambda t: t.replace("alias: ingest-${GATEWAY_ENVIRONMENT}", "alias: chat-${GATEWAY_ENVIRONMENT}"),
            "duplicated",
        ),
        (lambda t: t.replace("max_budget: 5.00", "max_budget: 0"), "max_budget"),
        (lambda t: t.replace("budget_duration: 1d", "budget_duration: 2w", 1), "budget_duration"),
        (lambda t: t.replace("consumer: ingest", "consumer: other"), "consumer"),
    ],
)
def test_invalid_definitions_fail_before_calling_the_gateway(gateway, env, tmp_path, capsys, mutation, message):
    invalid = tmp_path / "keys.yaml"
    invalid.write_text(mutation(KEYS_YAML.read_text(encoding="utf-8")), encoding="utf-8")
    assert _run(env, invalid) == 1
    assert message in capsys.readouterr().err
    assert gateway.calls == []


def test_missing_key_value_is_rejected(gateway, env, capsys):
    del env["LITELLM_KEY_INGEST"]
    assert _run(env) == 1
    assert "LITELLM_KEY_INGEST" in capsys.readouterr().err
    assert gateway.keys == {}


def test_missing_eval_key_value_is_rejected(gateway, env, capsys):
    del env["LITELLM_KEY_EVAL"]
    assert _run(env) == 1
    assert "LITELLM_KEY_EVAL" in capsys.readouterr().err
    assert gateway.keys == {}


def test_placeholder_values_are_rejected_in_dev(gateway, env, capsys):
    assert _run({**env, "GATEWAY_ENVIRONMENT": "dev"}) == 1
    assert "placeholder" in capsys.readouterr().err
    assert gateway.keys == {}


def test_real_looking_values_are_accepted_in_dev(gateway, env, capsys):
    dev = {
        **env,
        "GATEWAY_ENVIRONMENT": "dev",
        "LITELLM_KEY_CHAT": "sk-7d6c5b4a39281706f5e4d3c2b1a09f8e",
        "LITELLM_KEY_INGEST": "sk-0a1b2c3d4e5f60718293a4b5c6d7e8f9",
        "LITELLM_KEY_EVAL": "sk-5c4b3a2918070615e4d3c2b1a0f9e8d7",
        "LITELLM_KEY_RETRIEVAL": "sk-3e2d1c0b9a8f7e6d5c4b3a2918070615",
    }
    assert _run(dev) == 0
    assert "created alias=chat-dev" in capsys.readouterr().out


def test_unknown_environment_is_rejected(gateway, env, capsys):
    assert _run({**env, "GATEWAY_ENVIRONMENT": "prod"}) == 1
    assert "GATEWAY_ENVIRONMENT" in capsys.readouterr().err
