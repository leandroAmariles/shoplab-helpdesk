"""Paraphrase generation against a fake gateway: no network, no model."""

import json

import pytest
from intent_samples import make_example

from intent_dataset.errors import ConfigurationError, ParaphraseError
from intent_dataset.paraphrase import LlmClient, LlmSettings, generate_paraphrases

API_KEY = "sk-secret-value-123"


class FakeGateway:
    """Returns the queued answers in order and records every request body."""

    def __init__(self, *answers):
        self.answers = list(answers)
        self.requests: list[dict] = []

    def __call__(self, url, headers, payload):
        self.requests.append({"url": url, "headers": headers, "payload": payload})
        content = self.answers.pop(0)
        return {"choices": [{"message": {"content": content}}]}


def _client(gateway):
    return LlmClient(LlmSettings(base_url="http://gateway.test/v1", model="judge", api_key=API_KEY), post=gateway)


def _parent(**changes):
    values = {"text": "¿Dónde está mi pedido? Hace diez días que lo compré.", "label": "pedido", "group_id": "S001"}
    values.update(changes)
    return make_example("I0001", **values)


def _variants(*texts):
    return json.dumps(list(texts), ensure_ascii=False)


def test_the_request_carries_model_sampling_message_intent_language_and_the_privacy_instruction():
    answer = _variants("¿Sabes dónde está mi pedido? Lo compré hace diez días.", "No me llega mi pedido, ¿qué pasa?")
    gateway = FakeGateway(answer)
    generate_paraphrases([_parent()], _client(gateway), per_parent=2, translate=False, seed=117)
    (request,) = gateway.requests
    payload = request["payload"]
    prompt = " ".join(message["content"] for message in payload["messages"])
    assert request["url"] == "http://gateway.test/v1/chat/completions"
    assert payload["model"] == "judge"
    assert "temperature" in payload and payload["seed"] == 117
    assert "¿Dónde está mi pedido? Hace diez días que lo compré." in prompt
    assert "pedido" in prompt and "español" in prompt and "2" in prompt
    assert "nombres" in prompt and "correos" in prompt and "teléfonos" in prompt and "números de pedido" in prompt
    assert request["headers"]["Authorization"] == f"Bearer {API_KEY}"


def test_a_json_array_inside_a_code_fence_is_accepted():
    answer = "```json\n" + _variants("No me ha llegado todavía mi pedido, ¿qué ocurre?") + "\n```"
    result = generate_paraphrases([_parent()], _client(FakeGateway(answer)), per_parent=1, translate=False, seed=1)
    assert [c.text for c in result.candidates] == ["No me ha llegado todavía mi pedido, ¿qué ocurre?"]


def test_candidates_inherit_group_golden_id_and_label_from_the_parent_and_are_pending():
    parent = _parent(group_id="G012", golden_id="G012", label="politica", source="golden")
    gateway = FakeGateway(_variants("¿Puedo devolver algo que ya compré?"))
    (candidate,) = generate_paraphrases([parent], _client(gateway), per_parent=1, translate=False, seed=1).candidates
    assert (candidate.source, candidate.parent_id, candidate.group_id, candidate.golden_id) == (
        "paraphrase",
        "I0001",
        "G012",
        "G012",
    )
    assert (candidate.proposed_label, candidate.language) == ("politica", "es")
    assert (candidate.decision, candidate.label) == ("", "")
    assert candidate.candidate_id.startswith("P")


def test_translate_asks_for_the_other_language_and_keeps_the_group():
    gateway = FakeGateway(_variants("Cadê meu pedido? Comprei faz dez dias."))
    result = generate_paraphrases([_parent()], _client(gateway), per_parent=0, translate=True, seed=1)
    (request,) = gateway.requests
    assert "portugués" in " ".join(m["content"] for m in request["payload"]["messages"])
    assert [(c.language, c.group_id) for c in result.candidates] == [("pt", "S001")]


def test_a_portuguese_parent_is_translated_into_spanish():
    parent = _parent(text="meu pedido ainda não chegou", language="pt")
    gateway = FakeGateway(_variants("mi pedido todavía no ha llegado"))
    result = generate_paraphrases([parent], _client(gateway), per_parent=0, translate=True, seed=1)
    assert result.candidates[0].language == "es"


@pytest.mark.parametrize(
    ("answer", "reason"),
    [
        ("esto no es json", "json_invalido"),
        ('{"a": 1}', "json_invalido"),
        (json.dumps([1, 2]), "json_invalido"),
        (_variants(""), "longitud"),
        (_variants("ok"), "longitud"),
        (_variants("x" * 400), "longitud"),
        (_variants("¿Dónde está mi pedido? Hace diez días que lo compré."), "igual_al_original"),
        (_variants("¿donde esta mi pedido?  hace diez dias que lo compre"), "igual_al_original"),
        (_variants("¿Dónde está mi pedido? Hace diez días que lo compré ya."), "similar"),
        (_variants("escríbeme a ana@correo.com sobre mi pedido atrasado"), "dato_personal"),
        (_variants("mi pedido 0123456789abcdef0123456789abcdef no llega"), "dato_personal"),
    ],
)
def test_unusable_variants_are_discarded_with_their_reason(answer, reason):
    result = generate_paraphrases([_parent()], _client(FakeGateway(answer)), per_parent=1, translate=False, seed=1)
    assert result.candidates == []
    assert result.discarded[reason] >= 1


def test_variants_equal_to_each_other_are_kept_once():
    answer = _variants("No me llega el pedido, ayuda", "no me llega el pedido ayuda!", "Sigo esperando mi compra")
    result = generate_paraphrases([_parent()], _client(FakeGateway(answer)), per_parent=3, translate=False, seed=1)
    assert len(result.candidates) == 2
    assert result.discarded["duplicada"] == 1


def test_a_translation_is_not_compared_for_similarity_with_a_different_language_original():
    parent = _parent(text="pedido atrasado")
    answer = _variants("pedido atrasado agora")  # similar tokens but another language: it is a translation
    result = generate_paraphrases([parent], _client(FakeGateway(answer)), per_parent=0, translate=True, seed=1)
    assert len(result.candidates) == 1


def test_candidate_ids_are_unique_across_parents_and_languages():
    parents = [_parent(), make_example("I0002", text="quiero cancelar mi compra", label="pedido", group_id="S002")]
    gateway = FakeGateway(
        _variants("No quiero seguir esperando mi pedido", "Mi compra sigue sin llegar"),
        _variants("Tudo bem se eu cancelar meu pedido?"),
        _variants("Necesito anular mi compra de ayer", "Deseo cancelar lo que compré"),
        _variants("Quero cancelar a minha compra"),
    )
    result = generate_paraphrases(parents, _client(gateway), per_parent=2, translate=True, seed=1)
    ids = [c.candidate_id for c in result.candidates]
    assert len(ids) == len(set(ids)) == 6


def test_seed_changes_per_parent_so_requests_differ():
    parents = [_parent(), make_example("I0002", text="quiero cancelar mi compra", label="pedido", group_id="S002")]
    gateway = FakeGateway(_variants("Mi compra no llega todavía"), _variants("Anular mi compra por favor"))
    generate_paraphrases(parents, _client(gateway), per_parent=1, translate=False, seed=10)
    assert [r["payload"]["seed"] for r in gateway.requests] == [10, 11]


def test_without_the_api_key_the_error_names_the_variable_and_prints_no_value():
    with pytest.raises(ConfigurationError) as error:
        LlmSettings.from_env({})
    assert "SHOPLAB_ML_LLM_API_KEY" in str(error.value)


def test_settings_defaults_and_overrides():
    settings = LlmSettings.from_env({"SHOPLAB_ML_LLM_API_KEY": API_KEY})
    assert (settings.base_url, settings.model) == ("http://localhost:4000/v1", "judge")
    custom = LlmSettings.from_env(
        {"SHOPLAB_ML_LLM_API_KEY": API_KEY, "SHOPLAB_ML_LLM_BASE_URL": "http://x/v1/", "SHOPLAB_ML_LLM_MODEL": "chat"}
    )
    assert (custom.base_url, custom.model) == ("http://x/v1", "chat")


def test_the_key_never_appears_in_the_repr():
    assert API_KEY not in repr(LlmSettings.from_env({"SHOPLAB_ML_LLM_API_KEY": API_KEY}))


def test_a_gateway_failure_is_a_paraphrase_error_without_the_key():
    def failing(url, headers, payload):
        raise ParaphraseError("el gateway respondió 401")

    with pytest.raises(ParaphraseError) as error:
        generate_paraphrases([_parent()], _client(failing), per_parent=1, translate=False, seed=1)
    assert API_KEY not in str(error.value)


def test_a_response_without_choices_is_a_paraphrase_error():
    def odd(url, headers, payload):
        return {"error": "x"}

    with pytest.raises(ParaphraseError, match="respuesta"):
        _client(odd).complete("sistema", "usuario")
