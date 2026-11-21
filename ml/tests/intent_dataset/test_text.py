from intent_dataset.text import normalize_text, tokens


def test_lowercases_and_strips_accents_and_punctuation():
    assert normalize_text("¿Qué tal?") == "que tal"
    assert normalize_text("¿Qué tal?") == normalize_text("que tal")


def test_collapses_whitespace_and_newlines():
    assert normalize_text("  hola \n\t  mundo  ") == "hola mundo"


def test_folds_enye_and_cedilla():
    assert normalize_text("Año") == "ano"
    assert normalize_text("Devolução") == "devolucao"


def test_same_text_in_two_languages_with_and_without_accents():
    assert normalize_text("Olá") == normalize_text("ola")


def test_is_idempotent():
    once = normalize_text("¡Gracias, MUCHAS gracias!")
    assert normalize_text(once) == once


def test_keeps_digits_and_drops_symbols():
    assert normalize_text("Pagué R$ 120,50 en 4 cuotas") == "pague r 120 50 en 4 cuotas"


def test_tokens_is_a_set_of_normalized_words():
    assert tokens("Hola, hola MUNDO") == frozenset({"hola", "mundo"})


def test_empty_text():
    assert normalize_text("") == ""
    assert tokens("   ") == frozenset()
