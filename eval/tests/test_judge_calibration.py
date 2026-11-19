"""Does the real judge tell a good answer from a bad one? (specs/LAB-29, SC-002 and SC-003.)

These tests call the judge through the gateway, so they are not part of the default run (``-m "not judge"``). Run them
with the stack up and the key of the evaluation: ``SHOPLAB_EVAL_JUDGE_API_KEY=… uv run pytest -m judge``. They are what
tells whether a judge is good enough to decide anything: the 3B model of the chat failed them (it does not fill the
structured outputs) and ``qwen2.5:7b`` passes them (``validation.md``, S-1).
"""

import asyncio
import os
import urllib.error
import urllib.request

import pytest

from calibration_pairs import FAITHFULNESS_PAIRS, PRECISION_TRIPLES, RELEVANCE_PAIRS
from shoplab_eval.constants import (
    DEFAULT_JUDGE_BASE_URL,
    DEFAULT_JUDGE_MODEL,
    JUDGE_API_KEY_ENV,
    JUDGE_BASE_URL_ENV,
    JUDGE_MODEL_ENV,
)
from shoplab_eval.quality_judge import JudgeSettings, RagasJudge, build_ragas_judge

pytestmark = pytest.mark.judge


@pytest.fixture
def judge() -> RagasJudge:
    """One judge per test: its HTTP client is tied to the event loop where it is first used (see ``RagasJudge``)."""
    api_key = os.environ.get(JUDGE_API_KEY_ENV, "").strip()
    if not api_key:
        pytest.skip(f"Define {JUDGE_API_KEY_ENV} (la clave `eval` del gateway) para ejecutar la calibración del juez")
    settings = JudgeSettings(
        model=os.environ.get(JUDGE_MODEL_ENV, DEFAULT_JUDGE_MODEL),
        base_url=os.environ.get(JUDGE_BASE_URL_ENV, DEFAULT_JUDGE_BASE_URL),
    )
    request = urllib.request.Request(
        f"{settings.base_url.rstrip('/')}/models", headers={"Authorization": f"Bearer {api_key}"}
    )
    try:
        with urllib.request.urlopen(request, timeout=5):
            pass
    except (urllib.error.URLError, OSError) as error:
        pytest.skip(f"El gateway del juez no responde en {settings.base_url}: {error}")
    return build_ragas_judge(settings, api_key)


@pytest.mark.parametrize("pair", FAITHFULNESS_PAIRS, ids=lambda pair: pair.case_id)
def test_an_invented_answer_is_less_faithful_than_the_faithful_one(judge: RagasJudge, pair) -> None:
    """SC-002: the answer with a fact no fragment supports scores lower on the same question and context."""
    async def both() -> tuple[float | None, float | None]:
        return (
            await judge.faithfulness(pair.question, pair.faithful_answer, list(pair.context)),
            await judge.faithfulness(pair.question, pair.invented_answer, list(pair.context)),
        )

    faithful, invented = asyncio.run(both())

    assert faithful is not None and invented is not None
    assert invented < faithful, f"{pair.case_id}: inventada {invented} no es menor que fiel {faithful}"


@pytest.mark.parametrize("pair", RELEVANCE_PAIRS, ids=lambda pair: pair.case_id)
def test_an_answer_to_another_question_is_less_relevant_than_the_right_one(judge: RagasJudge, pair) -> None:
    """SC-003: a true answer that answers something else scores lower on relevancy."""
    async def both() -> tuple[float | None, float | None]:
        return (
            await judge.answer_relevancy(pair.question, pair.relevant_answer),
            await judge.answer_relevancy(pair.question, pair.off_target_answer),
        )

    relevant, off_target = asyncio.run(both())

    assert relevant is not None and off_target is not None
    assert off_target < relevant, f"{pair.case_id}: otra cosa {off_target} no es menor que pertinente {relevant}"


@pytest.mark.parametrize("triple", PRECISION_TRIPLES, ids=lambda triple: triple.case_id)
def test_the_useful_fragment_last_gives_a_lower_context_precision_than_first(judge: RagasJudge, triple) -> None:
    """SC-003: the same fragments with the useful one after two irrelevant ones score lower on precision."""
    async def both() -> tuple[float | None, float | None]:
        return (
            await judge.context_precision(triple.question, triple.reference, triple.useful_first),
            await judge.context_precision(triple.question, triple.reference, triple.useful_last),
        )

    first, last = asyncio.run(both())

    assert first is not None and last is not None
    assert last < first, f"{triple.case_id}: útil al final {last} no es menor que útil primero {first}"


def test_a_refusal_is_recognized_as_an_abstention_and_an_affirmation_is_not(judge: RagasJudge) -> None:
    """The abstention check is a prompt of ours: it has to separate «no tengo información» from a concrete claim."""
    question = "¿Qué porcentaje de interés cobran en las cuotas?"
    refusal = "No tengo información sobre el porcentaje de interés en los documentos. Consulta con atención al cliente."
    claim = "Las cuotas de 4 a 12 llevan un interés del 2,5 % mensual."
    # Found by reading the real answers of a run (validation.md, T026): a partial answer states something concrete and
    # only says that another part is not indicated. It is not an abstention, and version 1 of the prompt said it was.
    partial = (
        "Puedes pagar con tarjeta de crédito y combinarlo con un vale, "
        "pero no se indica que se pueda pagar en varias cuotas."
    )

    async def all_three() -> tuple[bool, bool, bool]:
        return (
            await judge.abstains(question, refusal),
            await judge.abstains(question, claim),
            await judge.abstains("¿Puedo combinar un vale con cuotas?", partial),
        )

    assert asyncio.run(all_three()) == (True, False, False)
