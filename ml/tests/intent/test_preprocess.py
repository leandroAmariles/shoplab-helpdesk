"""Unit tests for TextPreprocessor and mask token preservation (LAB-118)."""

from pathlib import Path

import joblib
import pytest
from sklearn.base import clone

from intent.constants import MASK_TOKENS
from intent.errors import EmptyTextError
from intent.preprocess import TextPreprocessor, preprocess_text


def test_preprocess_text_lowercases_and_preserves_spanish_and_portuguese_chars() -> None:
    raw = "  ¡Hola!   ¿Cuándo llegará mi camión con café, açaí y coração? \n\t "
    processed = preprocess_text(raw)
    assert processed == "¡hola! ¿cuándo llegará mi camión con café, açaí y coração?"


@pytest.mark.parametrize(("placeholder", "expected_token"), list(MASK_TOKENS.items()))
def test_preprocess_text_preserves_all_lab117_mask_placeholders_as_tokens(
    placeholder: str,
    expected_token: str,
) -> None:
    raw = f"Consulta sobre #{placeholder}. y ({placeholder.lower()})!"
    processed = preprocess_text(raw)
    assert expected_token in processed.split()
    assert placeholder.lower() not in processed


def test_preprocess_text_is_idempotent() -> None:
    raw = "Meu pedido [ID] atrasou no CEP [CEP], ligue para [PHONE] ou [EMAIL]."
    once = preprocess_text(raw)
    twice = preprocess_text(once)
    assert once == twice
    assert "__mask_id__" in once
    assert "__mask_cep__" in once
    assert "__mask_phone__" in once
    assert "__mask_email__" in once


@pytest.mark.parametrize("bad_input", ["", "   ", "\n\t\r", "\x00\x07", None, 123])
def test_preprocess_text_rejects_empty_or_non_string_input(bad_input: object) -> None:
    with pytest.raises(EmptyTextError):
        preprocess_text(bad_input)  # type: ignore[arg-type]


def test_text_preprocessor_transformer_clone_and_joblib_roundtrip(tmp_path: Path) -> None:
    transformer = TextPreprocessor()
    cloned = clone(transformer)
    texts = [
        "Mi pedido [ID] no llegó",
        "Quero falar com atendente sobre o CPF [CPF]",
    ]
    out = cloned.fit_transform(texts)
    assert len(out) == 2
    assert "__mask_id__" in out[0]
    assert "__mask_cpf__" in out[1]

    dump_path = tmp_path / "preprocessor.joblib"
    joblib.dump(cloned, dump_path)
    restored = joblib.load(dump_path)
    assert restored.transform(texts) == out
