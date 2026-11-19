"""Static invariants of the gateway configuration (FR-009, FR-012, FR-022, FR-026, SC-007)."""

import pathlib
import re

import pytest
import yaml

import render_config

INFRA = pathlib.Path(__file__).resolve().parents[2]
CONFIG = INFRA / "litellm" / "config.yaml"


def _render(env: dict[str, str]) -> dict:
    return yaml.safe_load(render_config.render(CONFIG.read_text(encoding="utf-8"), env))


@pytest.fixture(params=["without-cloud", "with-cloud"])
def config(request):
    env = {}
    if request.param == "with-cloud":
        env = {"CLOUD_PROVIDER_API_KEY": "test-key", "CLOUD_PROVIDER_MODEL": "openai/some-model"}
    return _render(env)


def test_every_model_has_a_reference_price(config):
    for entry in config["model_list"]:
        info = entry["model_info"]
        assert isinstance(info.get("input_cost_per_token"), (int, float)), entry["model_name"]
        assert isinstance(info.get("output_cost_per_token"), (int, float)), entry["model_name"]


def test_every_model_has_a_stable_id(config):
    ids = [entry["model_info"]["id"] for entry in config["model_list"]]
    assert len(ids) == len(set(ids))


def test_only_chat_falls_back_and_only_to_the_local_fallback(config):
    fallbacks = config["router_settings"]["fallbacks"]
    assert fallbacks == [{"chat": ["chat-fallback"]}]
    flattened = {name for rule in fallbacks for key, targets in rule.items() for name in [key, *targets]}
    assert "embeddings" not in flattened
    assert "chat-cloud" not in flattened


def test_judge_model_exists_and_never_falls_back(config):
    """LAB-29: the evaluation judge is a logical name of its own; a silent fallback would change the scores."""
    names = {entry["model_name"] for entry in config["model_list"]}
    assert "judge" in names
    rules = config["router_settings"]["fallbacks"]
    flattened = {name for rule in rules for key, targets in rule.items() for name in [key, *targets]}
    assert "judge" not in flattened


def test_judge_defaults_to_a_stronger_local_model_than_the_chat_one_and_is_overridable():
    """S-1 (LAB-29): the 3B chat model cannot fill the structured outputs of the judge; qwen2.5:7b can."""

    def models(env):
        return {e["model_name"]: e["litellm_params"]["model"] for e in _render(env)["model_list"]}

    default = models({})
    assert default["judge"] == "ollama_chat/qwen2.5:7b"
    assert default["chat"] == "ollama_chat/llama3.2:3b"
    # The model the Ollama container downloads is the one the gateway judges with.
    assert models({"OLLAMA_JUDGE_MODEL": "qwen2.5:14b"})["judge"] == "ollama_chat/qwen2.5:14b"
    # LITELLM_JUDGE_MODEL wins, and moving the chat model never moves the judge.
    overridden = models({"LITELLM_JUDGE_MODEL": "llama3.1:8b", "OLLAMA_JUDGE_MODEL": "other"})
    assert overridden["judge"] == "ollama_chat/llama3.1:8b"
    assert models({"OLLAMA_CHAT_MODEL": "other:1b"})["judge"] == "ollama_chat/qwen2.5:7b"


def test_judge_has_the_same_context_window_as_chat(config):
    by_name = {e["model_name"]: e["litellm_params"] for e in config["model_list"]}
    assert by_name["judge"]["num_ctx"] == by_name["chat"]["num_ctx"]
    assert by_name["judge"]["api_base"] == by_name["chat"]["api_base"]


def test_eval_key_is_wired_in_compose_and_env_example():
    compose = (INFRA / "docker-compose.yml").read_text(encoding="utf-8")
    assert "LITELLM_KEY_EVAL" in compose
    assert "LITELLM_JUDGE_MODEL" in compose
    assert "OLLAMA_JUDGE_MODEL" in compose
    env_example = (INFRA / ".env.example").read_text(encoding="utf-8")
    assert "LITELLM_KEY_EVAL=" in env_example
    assert "LITELLM_JUDGE_MODEL=" in env_example
    assert "OLLAMA_JUDGE_MODEL=" in env_example
    assert "SHOPLAB_EVAL_JUDGE_API_KEY" in env_example


def test_cloud_model_exists_only_with_its_key():
    names_without = {e["model_name"] for e in _render({})["model_list"]}
    names_with = {
        e["model_name"]
        for e in _render({"CLOUD_PROVIDER_API_KEY": "k", "CLOUD_PROVIDER_MODEL": "openai/m"})["model_list"]
    }
    assert "chat-cloud" not in names_without
    assert "chat-cloud" in names_with


def test_messages_are_never_logged_or_stored(config):
    assert config["litellm_settings"]["turn_off_message_logging"] is True
    assert config["general_settings"]["store_prompts_in_spend_logs"] is False


def test_unsupported_openai_params_are_dropped_not_rejected(config):
    assert config["litellm_settings"]["drop_params"] is True


def test_mid_stream_fallback_stays_disabled(config):
    assert config["litellm_settings"]["enable_mid_stream_fallback_continuation"] is False


def test_secrets_are_referenced_not_inlined(config):
    assert str(config["general_settings"]["master_key"]).startswith("os.environ/")
    assert str(config["general_settings"]["database_url"]).startswith("os.environ/")


def test_local_models_use_ollama_by_default(config):
    local = {
        e["model_name"]: e["litellm_params"]["model"] for e in config["model_list"] if e["model_name"] != "chat-cloud"
    }
    assert all(model.startswith(("ollama/", "ollama_chat/")) for model in local.values())


def test_no_latest_images_and_no_real_looking_keys_in_compose_and_env_example():
    for name in ("docker-compose.yml", ".env.example"):
        text = (INFRA / name).read_text(encoding="utf-8")
        assert not re.search(r"image:\s*\S+:latest\b", text), name
        assert not re.search(r"_IMAGE=\S+:latest\b", text), name
        assert not re.search(r"sk-[A-Za-z0-9]{20,}", text), name
