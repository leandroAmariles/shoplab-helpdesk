"""The adapter between the measurement and RAGAS, with its metrics replaced by doubles (specs/LAB-29, R1 and R6).

Nothing here calls a model or the network. The adapter only *translates*: it calls the metrics with the data of a turn,
turns ``NaN`` into an empty score and turns every failure of the judge into a ``MeasurementError``. The rules about an
empty context or an abstention belong to the orchestration (``test_quality_run.py``), not to this module.
"""

import asyncio
import builtins
import math
import os
from types import SimpleNamespace

import pytest

from shoplab_eval.constants import ABSTENTION_PROMPT_VERSION
from shoplab_eval.errors import MeasurementError
from shoplab_eval.quality_judge import (
    JudgeSettings,
    RagasJudge,
    abstention_decision,
    abstention_prompt,
    build_ragas_judge,
    ragas_version,
)

SECRET = "sk-secret-do-not-print"


class FakeMetric:
    """Stands for a RAGAS metric: records what it was asked and answers what it was told."""

    def __init__(self, value: object = 0.75, error: Exception | None = None, delay: float = 0.0) -> None:
        self.value = value
        self.error = error
        self.delay = delay
        self.calls: list[dict[str, object]] = []

    async def ascore(self, **kwargs: object) -> SimpleNamespace:
        self.calls.append(kwargs)
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        return SimpleNamespace(value=self.value)


class FakeAbstentionLlm:
    def __init__(self, abstains: bool = True) -> None:
        self.abstains = abstains
        self.prompts: list[str] = []

    async def __call__(self, prompt: str) -> bool:
        self.prompts.append(prompt)
        return self.abstains


def judge_with(
    faithfulness: FakeMetric | None = None,
    relevancy: FakeMetric | None = None,
    precision: FakeMetric | None = None,
    abstention: FakeAbstentionLlm | None = None,
    timeout: float = 5.0,
) -> RagasJudge:
    return RagasJudge(
        JudgeSettings(timeout=timeout),
        faithfulness=faithfulness or FakeMetric(),
        answer_relevancy=relevancy or FakeMetric(),
        context_precision=precision or FakeMetric(),
        abstention=abstention or FakeAbstentionLlm(),
        redact=(SECRET,),
    )


# --- each metric gets the data of the turn that RAGAS asks for ------------------------------------------------


def test_faithfulness_is_asked_the_question_the_answer_and_the_context_in_order() -> None:
    metric = FakeMetric(0.8333)

    value = asyncio.run(judge_with(faithfulness=metric).faithfulness("¿Cuántas cuotas?", "Hasta 12.", ["uno", "dos"]))

    assert value == 0.8333
    assert metric.calls == [
        {"user_input": "¿Cuántas cuotas?", "response": "Hasta 12.", "retrieved_contexts": ["uno", "dos"]}
    ]


def test_answer_relevancy_is_asked_only_the_question_and_the_answer() -> None:
    metric = FakeMetric(0.6)

    value = asyncio.run(judge_with(relevancy=metric).answer_relevancy("¿Cuántas cuotas?", "Hasta 12."))

    assert value == 0.6
    assert metric.calls == [{"user_input": "¿Cuántas cuotas?", "response": "Hasta 12."}]


def test_context_precision_is_asked_the_reference_answer_and_the_context_in_order() -> None:
    metric = FakeMetric(1.0)

    value = asyncio.run(
        judge_with(precision=metric).context_precision("¿Cuántas cuotas?", "Hasta 12 cuotas.", ["útil", "ruido"])
    )

    assert value == 1.0
    assert metric.calls == [
        {"user_input": "¿Cuántas cuotas?", "reference": "Hasta 12 cuotas.", "retrieved_contexts": ["útil", "ruido"]}
    ]


# --- an empty score is empty, never a zero --------------------------------------------------------------------


@pytest.mark.parametrize("raw", [math.nan, None])
def test_a_metric_that_is_not_a_number_is_an_empty_score_for_every_metric(raw: object) -> None:
    judge = judge_with(FakeMetric(raw), FakeMetric(raw), FakeMetric(raw))

    assert asyncio.run(judge.faithfulness("p", "r", ["c"])) is None
    assert asyncio.run(judge.answer_relevancy("p", "r")) is None
    assert asyncio.run(judge.context_precision("p", "ref", ["c"])) is None


def test_a_real_zero_stays_a_zero() -> None:
    assert asyncio.run(judge_with(faithfulness=FakeMetric(0.0)).faithfulness("p", "r", ["c"])) == 0.0


# --- the abstention check is ours, not RAGAS's ----------------------------------------------------------------


def test_the_abstention_prompt_carries_the_question_and_the_answer_and_asks_for_a_decision() -> None:
    prompt = abstention_prompt("¿Cuánto cuesta el envío?", "No tengo información sobre eso.")

    assert "¿Cuánto cuesta el envío?" in prompt
    assert "No tengo información sobre eso." in prompt
    assert "abstains" in prompt


def test_the_judge_reports_the_decision_of_the_abstention_check() -> None:
    llm = FakeAbstentionLlm(abstains=True)

    assert asyncio.run(judge_with(abstention=llm).abstains("¿Cuánto cuesta?", "No tengo información.")) is True
    assert "No tengo información." in llm.prompts[0]
    assert asyncio.run(judge_with(abstention=FakeAbstentionLlm(abstains=False)).abstains("p", "Cuesta R$ 10.")) is False


def test_the_abstention_prompt_asks_for_the_concrete_claims_before_the_decision() -> None:
    """S-1 review: asked only for a yes or no, the judge took a partial answer as an abstention (version 2)."""
    answer = "Puedes combinarlo con un vale, pero no se indica lo de las cuotas."
    prompt = abstention_prompt("¿Se puede combinar?", answer)

    assert "concrete_claims" in prompt
    assert prompt.index("concrete_claims") < prompt.index("abstains")


def test_an_answer_that_lists_a_concrete_claim_is_never_an_abstention_whatever_the_flag_says() -> None:
    assert abstention_decision(True, ["Puedes combinar la tarjeta con un vale"]) is False
    assert abstention_decision(False, ["Un plazo de 7 días"]) is False


def test_an_answer_with_no_concrete_claim_is_an_abstention_only_if_the_judge_says_so() -> None:
    assert abstention_decision(True, []) is True
    assert abstention_decision(True, ["", "  "]) is True  # blank entries are not claims
    assert abstention_decision(False, []) is False


def test_the_abstention_prompt_has_a_version_that_the_result_records() -> None:
    assert ABSTENTION_PROMPT_VERSION == "2"


# --- a failure of the judge is an error that names the metric, never a score ----------------------------------


@pytest.mark.parametrize("method", ["faithfulness", "answer_relevancy", "context_precision"])
def test_a_failing_metric_names_itself_and_never_leaks_the_key(method: str) -> None:
    broken = FakeMetric(error=RuntimeError(f"boom, Authorization: Bearer {SECRET}"))
    judge = judge_with(faithfulness=broken, relevancy=broken, precision=broken)
    call = {
        "faithfulness": lambda: judge.faithfulness("p", "r", ["c"]),
        "answer_relevancy": lambda: judge.answer_relevancy("p", "r"),
        "context_precision": lambda: judge.context_precision("p", "ref", ["c"]),
    }[method]

    with pytest.raises(MeasurementError) as error:
        asyncio.run(call())

    assert method in str(error.value)
    assert "RuntimeError" in str(error.value)
    assert SECRET not in str(error.value)


def test_a_failing_abstention_check_names_itself() -> None:
    class Broken:
        async def __call__(self, prompt: str) -> bool:
            raise ValueError("salida ilegible")

    with pytest.raises(MeasurementError, match="abstención.*ValueError"):
        asyncio.run(judge_with(abstention=Broken()).abstains("p", "r"))  # type: ignore[arg-type]


def test_a_metric_that_takes_longer_than_the_timeout_is_an_error_not_a_score() -> None:
    judge = judge_with(faithfulness=FakeMetric(delay=0.5), timeout=0.05)

    with pytest.raises(MeasurementError, match="Tiempo agotado.*faithfulness"):
        asyncio.run(judge.faithfulness("p", "r", ["c"]))


# --- building the real judge ----------------------------------------------------------------------------------


def test_building_the_judge_switches_the_telemetry_of_ragas_off(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("RAGAS_DO_NOT_TRACK", raising=False)

    build_ragas_judge(JudgeSettings(), SECRET)

    assert os.environ["RAGAS_DO_NOT_TRACK"] == "true"


def test_the_built_judge_never_shows_the_key_and_the_settings_do_not_hold_it() -> None:
    judge = build_ragas_judge(JudgeSettings(model="judge"), SECRET)

    assert SECRET not in repr(judge)
    assert SECRET not in str(judge)
    assert SECRET not in repr(JudgeSettings())
    assert not hasattr(JudgeSettings(), "api_key")


def test_without_ragas_installed_the_error_says_how_to_install_it(monkeypatch: pytest.MonkeyPatch) -> None:
    real_import = builtins.__import__

    def import_without_ragas(name: str, *args: object, **kwargs: object):
        if name == "ragas" or name.startswith("ragas."):
            raise ModuleNotFoundError(f"No module named {name!r}")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", import_without_ragas)

    with pytest.raises(MeasurementError, match="uv sync"):
        build_ragas_judge(JudgeSettings(), SECRET)


def test_the_installed_version_of_ragas_is_the_pinned_one() -> None:
    assert ragas_version() == "0.4.4"
