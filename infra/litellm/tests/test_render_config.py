import pytest

import render_config
from render_config import MissingVariableError, apply_cloud_block, expand, render


def test_expands_variable_with_value():
    assert expand("model: ${MODEL:-llama}", {"MODEL": "qwen"}) == "model: qwen"


def test_uses_default_when_variable_is_missing_or_empty():
    assert expand("model: ${MODEL:-llama3.2:3b}", {}) == "model: llama3.2:3b"
    assert expand("model: ${MODEL:-llama}", {"MODEL": ""}) == "model: llama"


def test_nested_default_resolves_inner_variable_first():
    template = "${LITELLM_CHAT_PRIMARY_MODEL:-${OLLAMA_CHAT_MODEL:-llama3.2:3b}}"
    assert expand(template, {}) == "llama3.2:3b"
    assert expand(template, {"OLLAMA_CHAT_MODEL": "qwen3:4b"}) == "qwen3:4b"
    assert expand(template, {"OLLAMA_CHAT_MODEL": "qwen3:4b", "LITELLM_CHAT_PRIMARY_MODEL": "gemma"}) == "gemma"


def test_required_variable_without_default_fails():
    with pytest.raises(MissingVariableError, match="GATEWAY_ENVIRONMENT"):
        expand("alias: backend-${GATEWAY_ENVIRONMENT}", {})


def test_does_not_touch_litellm_environment_references():
    template = "master_key: os.environ/LITELLM_MASTER_KEY"
    assert expand(template, {"LITELLM_MASTER_KEY": "secret"}) == template


def test_unterminated_expression_fails():
    with pytest.raises(ValueError, match="unterminated"):
        expand("${OPEN", {})


def test_cloud_block_is_dropped_without_key():
    template = "a\n#@cloud-begin\n  - model_name: chat-cloud\n#@cloud-end\nb"
    assert apply_cloud_block(template, {}) == "a\nb"
    assert apply_cloud_block(template, {"CLOUD_PROVIDER_API_KEY": ""}) == "a\nb"


def test_cloud_block_is_kept_with_key_and_markers_removed():
    template = "a\n#@cloud-begin\n  - model_name: chat-cloud\n#@cloud-end\nb"
    assert apply_cloud_block(template, {"CLOUD_PROVIDER_API_KEY": "k"}) == "a\n  - model_name: chat-cloud\nb"


def test_cloud_variables_are_not_required_without_key():
    template = "#@cloud-begin\nmodel: ${CLOUD_PROVIDER_MODEL}\n#@cloud-end\nok: 1"
    assert render(template, {}) == "ok: 1"


def test_cloud_block_without_end_fails():
    with pytest.raises(ValueError, match="cloud-end"):
        apply_cloud_block("#@cloud-begin\nx", {})


def test_main_writes_the_rendered_file(tmp_path, monkeypatch):
    source = tmp_path / "config.yaml"
    target = tmp_path / "out.yaml"
    source.write_text("model: ${MODEL:-llama}\n", encoding="utf-8")
    monkeypatch.setenv("MODEL", "qwen")
    assert render_config.main(["render_config.py", str(source), str(target)]) == 0
    assert target.read_text(encoding="utf-8") == "model: qwen\n"


def test_main_fails_with_non_zero_on_missing_variable(tmp_path, capsys):
    source = tmp_path / "config.yaml"
    source.write_text("alias: ${NOT_DEFINED_ANYWHERE}\n", encoding="utf-8")
    assert render_config.main(["render_config.py", str(source), str(tmp_path / "out.yaml")]) == 1
    assert "NOT_DEFINED_ANYWHERE" in capsys.readouterr().err


def test_main_rejects_wrong_arguments(capsys):
    assert render_config.main(["render_config.py"]) == 2
