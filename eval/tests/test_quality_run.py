"""The orchestration of the answer quality measurement, with a double of the assistant and a double of the judge.

It is where the rules live that the pure modules cannot know: which cases are asked, in which order, what an abstention
or an empty context does to each score and how a failure is reported (specs/LAB-29, research R5 and R6, FR-006, FR-011).
No test calls a model or the network.
"""

import asyncio
from collections.abc import Sequence

import pytest

from shoplab_eval.chat_client import ChatTurn, ContextItem
from shoplab_eval.constants import ABSTENTION_PROMPT_VERSION
from shoplab_eval.errors import MeasurementError
from shoplab_eval.golden import GoldenCase
from shoplab_eval.langfuse_client import LangfuseStatus, trace_id_for
from shoplab_eval.quality_judge import JudgeSettings
from shoplab_eval.quality_run import RunInfo, answer_has_content, measure_quality, request_id_for
from shoplab_eval.quality_scores import CaseKind

RUN_ID = "20261118T153000Z"


def case(case_id: str, category: str = "payments", documents: tuple[str, ...] = ("payments-01-a",)) -> GoldenCase:
    return GoldenCase(
        id=case_id,
        question=f"Pregunta {case_id}",
        reference_answer=f"Referencia {case_id}",
        expected_documents=documents,
        category=category,
    )


def unanswered(case_id: str, category: str = "payments") -> GoldenCase:
    return case(case_id, category, ())


def conversation(case_id: str, category: str = "small_talk") -> GoldenCase:
    return case(case_id, category, ())


def turn_for(request_id: str, answer: str = "Respuesta del asistente.", fragments: int = 2) -> ChatTurn:
    context = tuple(
        ContextItem(number + 1, f"Título {number + 1}", "", f"Texto {number + 1}", f"doc-{number + 1}")
        for number in range(fragments)
    )
    return ChatTurn(request_id=request_id, answer=answer, context=context)


class FakeChat:
    """The assistant: it answers every question the same way unless a case is given its own turn or its own failure."""

    def __init__(self, log: list[str]) -> None:
        self.log = log
        self.requests: list[tuple[str, str]] = []
        self.turns: dict[str, ChatTurn | Exception] = {}

    def ask(self, question: str, request_id: str) -> ChatTurn:
        self.log.append(f"ask:{request_id}")
        self.requests.append((question, request_id))
        planned = self.turns.get(question)
        if isinstance(planned, Exception):
            raise planned
        if planned is not None:
            return ChatTurn(request_id=request_id, answer=planned.answer, context=planned.context)
        return turn_for(request_id)


class FakeJudge:
    """The judge: a fixed value per metric, a list of the calls it received and the concurrency it saw."""

    def __init__(self, log: list[str], delay: float = 0.0) -> None:
        self.log = log
        self.delay = delay
        self.calls: list[str] = []
        self.abstain_answers: set[str] = set()
        self.values = {"faithfulness": 0.8, "answer_relevancy": 0.6, "context_precision": 1.0}
        self.errors: dict[str, Exception] = {}
        self.running = 0
        self.max_running = 0

    async def _call(self, name: str, question: str):
        self.log.append(f"judge:{name}:{question}")
        self.calls.append(name)
        self.running += 1
        self.max_running = max(self.max_running, self.running)
        try:
            if self.delay:
                await asyncio.sleep(self.delay)
            if name in self.errors:
                raise self.errors[name]
            return self.values.get(name)
        finally:
            self.running -= 1

    async def abstains(self, question: str, answer: str) -> bool:
        await self._call("abstains", question)
        return answer in self.abstain_answers

    async def faithfulness(self, question: str, answer: str, contexts: Sequence[str]) -> float | None:
        return await self._call("faithfulness", question)

    async def answer_relevancy(self, question: str, answer: str) -> float | None:
        return await self._call("answer_relevancy", question)

    async def context_precision(self, question: str, reference: str, contexts: Sequence[str]) -> float | None:
        return await self._call("context_precision", question)


@pytest.fixture
def log() -> list[str]:
    return []


@pytest.fixture
def chat(log: list[str]) -> FakeChat:
    return FakeChat(log)


@pytest.fixture
def judge(log: list[str]) -> FakeJudge:
    return FakeJudge(log)


def info(concurrency: int = 2, run_id: str = RUN_ID) -> RunInfo:
    return RunInfo(
        run_id=run_id,
        judge=JudgeSettings(concurrency=concurrency),
        judge_underlying_model="qwen2.5:7b",
        ragas_version="0.4.4",
        abstention_prompt_version=ABSTENTION_PROMPT_VERSION,
        assistant_model="llama3.2:3b",
        embedding_model="bge-m3",
        langfuse="skipped",
    )


def run(cases, chat, judge, **kwargs):
    return measure_quality(cases, chat, judge, kwargs.pop("run_info", info()), **kwargs)


# --- which cases are asked and in which order -----------------------------------------------------------------


def test_only_the_knowledge_cases_are_asked_and_the_conversation_ones_are_counted_as_excluded(
    chat: FakeChat, judge: FakeJudge
) -> None:
    cases = [case("G001"), conversation("G091"), unanswered("G050"), conversation("G101", "injection")]

    report = run(cases, chat, judge)

    assert [question for question, _ in chat.requests] == ["Pregunta G001", "Pregunta G050"]
    assert [result.case.id for result in report.results] == ["G001", "G050"]
    assert report.excluded == 2
    assert report.summaries[0].excluded == 2


def test_every_turn_is_asked_before_the_judge_is_called_even_once(
    chat: FakeChat, judge: FakeJudge, log: list[str]
) -> None:
    run([case("G001"), case("G002"), unanswered("G003")], chat, judge)

    last_turn = max(index for index, line in enumerate(log) if line.startswith("ask:"))
    first_judgement = min(index for index, line in enumerate(log) if line.startswith("judge:"))
    assert last_turn < first_judgement


def test_the_report_keeps_the_order_of_the_dataset(chat: FakeChat, judge: FakeJudge) -> None:
    cases = [case("G003"), unanswered("G001"), case("G002")]

    assert [result.case.id for result in run(cases, chat, judge).results] == ["G003", "G001", "G002"]


def test_a_dataset_with_only_conversation_cases_gives_an_empty_report(chat: FakeChat, judge: FakeJudge) -> None:
    report = run([conversation("G091"), conversation("G101", "injection")], chat, judge)

    assert report.results == ()
    assert report.excluded == 2
    assert chat.requests == []
    assert judge.calls == []


# --- the identities of the run --------------------------------------------------------------------------------


def test_the_request_id_names_the_run_and_the_case_and_is_a_safe_id_of_the_backend() -> None:
    request_id = request_id_for(RUN_ID, "G001")

    assert request_id == "eval-20261118T153000Z-G001"
    assert len(request_id) <= 64


def test_the_request_id_differs_per_case_and_per_run() -> None:
    assert request_id_for(RUN_ID, "G001") != request_id_for(RUN_ID, "G002")
    assert request_id_for(RUN_ID, "G001") != request_id_for("20261118T153001Z", "G001")


def test_a_run_id_or_case_id_that_would_not_be_a_safe_request_id_is_rejected() -> None:
    with pytest.raises(ValueError, match="identificador"):
        request_id_for("con espacios", "G001")
    with pytest.raises(ValueError, match="identificador"):
        request_id_for("a" * 33, "G001")
    with pytest.raises(ValueError, match="identificador"):
        request_id_for(RUN_ID, "G" * 60)


def test_each_case_is_asked_with_its_request_id_and_its_trace_is_derived_from_it(
    chat: FakeChat, judge: FakeJudge
) -> None:
    report = run([case("G001"), case("G002")], chat, judge)

    asked = [request_id for _, request_id in chat.requests]
    assert asked == ["eval-20261118T153000Z-G001", "eval-20261118T153000Z-G002"]
    for result in report.results:
        assert result.turn.request_id == request_id_for(RUN_ID, result.case.id)
        assert result.trace_id == trace_id_for(result.turn.request_id)
        assert result.langfuse is LangfuseStatus.SKIPPED


# --- what each kind of case is scored on ----------------------------------------------------------------------


def test_an_answered_case_gets_the_three_metrics(chat: FakeChat, judge: FakeJudge) -> None:
    result = run([case("G001")], chat, judge).results[0]

    assert result.kind is CaseKind.ANSWERED
    assert (result.scores.faithfulness, result.scores.answer_relevancy, result.scores.context_precision) == (
        0.8,
        0.6,
        1.0,
    )
    assert result.scores.abstained is False


def test_an_unanswered_case_is_only_scored_on_faithfulness(chat: FakeChat, judge: FakeJudge) -> None:
    result = run([unanswered("G050")], chat, judge).results[0]

    assert result.kind is CaseKind.UNANSWERED
    assert result.scores.faithfulness == 0.8
    assert result.scores.answer_relevancy is None
    assert result.scores.context_precision is None
    assert "answer_relevancy" not in judge.calls
    assert "context_precision" not in judge.calls


def test_scores_are_rounded_to_the_decimals_of_the_csv_so_that_the_files_the_means_and_langfuse_agree(
    chat: FakeChat, judge: FakeJudge
) -> None:
    judge.values = {"faithfulness": 0.83333333, "answer_relevancy": 0.59044999, "context_precision": 0.33336}

    result = run([case("G001")], chat, judge).results[0]

    assert result.scores.faithfulness == 0.8333
    assert result.scores.answer_relevancy == 0.5904
    assert result.scores.context_precision == 0.3334


def test_the_mean_of_a_scope_is_the_mean_of_the_rounded_values_the_csv_shows(chat: FakeChat, log: list[str]) -> None:
    class Varied(FakeJudge):
        async def answer_relevancy(self, question: str, answer: str) -> float | None:
            return {"Pregunta G001": 0.58334, "Pregunta G002": 0.59999}[question]

    report = run([case("G001"), case("G002")], chat, Varied(log))

    shown = [result.scores.answer_relevancy for result in report.results]
    assert shown == [0.5833, 0.6]
    assert report.summaries[0].answer_relevancy.mean == pytest.approx(sum(shown) / 2, abs=1e-12)


# --- the abstention and the empty context ---------------------------------------------------------------------


def test_an_abstention_leaves_faithfulness_empty_without_asking_for_it_and_the_other_metrics_are_still_computed(
    chat: FakeChat, judge: FakeJudge
) -> None:
    chat.turns["Pregunta G001"] = turn_for("x", answer="No tengo información sobre eso.")
    judge.abstain_answers = {"No tengo información sobre eso."}

    result = run([case("G001")], chat, judge).results[0]

    assert result.scores.abstained is True
    assert result.scores.faithfulness is None
    assert result.scores.answer_relevancy == 0.6
    assert result.scores.context_precision == 1.0
    assert "faithfulness" not in judge.calls


def test_an_empty_context_gives_a_precision_of_zero_and_a_faithfulness_of_zero_without_calling_the_judge(
    chat: FakeChat, judge: FakeJudge
) -> None:
    chat.turns["Pregunta G001"] = turn_for("x", answer="Puedes devolverlo en 30 días.", fragments=0)

    result = run([case("G001")], chat, judge).results[0]

    assert result.scores.context_precision == 0.0
    assert result.scores.faithfulness == 0.0  # it affirmed something and no fragment backs it
    assert result.scores.answer_relevancy == 0.6  # still judged: it does not need the context
    assert "context_precision" not in judge.calls
    assert "faithfulness" not in judge.calls


def test_an_abstention_without_context_leaves_faithfulness_empty_and_precision_zero(
    chat: FakeChat, judge: FakeJudge
) -> None:
    chat.turns["Pregunta G001"] = turn_for("x", answer="No encontré información.", fragments=0)
    judge.abstain_answers = {"No encontré información."}

    result = run([case("G001")], chat, judge).results[0]

    assert result.scores.abstained is True
    assert result.scores.faithfulness is None
    assert result.scores.context_precision == 0.0


def test_an_unanswered_case_that_affirms_something_without_context_has_faithfulness_zero(
    chat: FakeChat, judge: FakeJudge
) -> None:
    chat.turns["Pregunta G050"] = turn_for("x", answer="El interés es del 2 %.", fragments=0)

    result = run([unanswered("G050")], chat, judge).results[0]

    assert result.scores.faithfulness == 0.0
    assert result.scores.abstained is False


def test_the_context_handed_to_the_judge_is_the_text_of_the_fragments_in_order(chat: FakeChat, log: list[str]) -> None:
    seen: list[Sequence[str]] = []

    class Spy(FakeJudge):
        async def faithfulness(self, question: str, answer: str, contexts: Sequence[str]) -> float | None:
            seen.append(list(contexts))
            return 0.5

    run([case("G001")], chat, Spy(log))

    assert seen == [["Texto 1", "Texto 2"]]


# --- an answer with no content --------------------------------------------------------------------------------


@pytest.mark.parametrize("answer", ["[2]", "  [1] [2] .  ", "", "...", "\n[3]\n"])
def test_an_answer_made_only_of_citation_markers_and_punctuation_has_no_content(answer: str) -> None:
    assert answer_has_content(answer) is False


@pytest.mark.parametrize("answer", ["Sí [1]", "7 días corridos", "No."])
def test_an_answer_with_words_or_figures_has_content(answer: str) -> None:
    assert answer_has_content(answer) is True


def test_an_answered_case_whose_answer_is_only_a_citation_marker_answers_nothing(
    chat: FakeChat, judge: FakeJudge
) -> None:
    chat.turns["Pregunta G001"] = turn_for("x", answer="[2]")

    result = run([case("G001")], chat, judge).results[0]

    assert result.scores.answer_relevancy == 0.0  # it answered nothing, with no need to ask the judge
    assert result.scores.faithfulness is None  # no claim to check against the context
    assert result.scores.abstained is False  # it did not decline: it produced nothing
    assert result.scores.context_precision == 1.0  # the context is judged on its own
    assert "abstains" not in judge.calls and "faithfulness" not in judge.calls and "answer_relevancy" not in judge.calls


def test_an_unanswered_case_whose_answer_is_only_a_citation_marker_has_an_empty_faithfulness_not_a_perfect_one(
    chat: FakeChat, judge: FakeJudge
) -> None:
    chat.turns["Pregunta G050"] = turn_for("x", answer="[2]")

    result = run([unanswered("G050")], chat, judge).results[0]

    assert result.scores.faithfulness is None
    assert result.scores.abstained is False
    assert judge.calls == []


# --- the judge is asked with a bounded concurrency ------------------------------------------------------------


@pytest.mark.parametrize("concurrency", [1, 2, 3])
def test_the_judge_never_sees_more_cases_at_once_than_the_configured_concurrency(
    chat: FakeChat, log: list[str], concurrency: int
) -> None:
    judge = FakeJudge(log, delay=0.02)
    cases = [case(f"G{number:03d}") for number in range(1, 8)]

    run(cases, chat, judge, run_info=info(concurrency=concurrency))

    assert judge.max_running == concurrency


# --- failures are errors that name the case, never scores -----------------------------------------------------


def test_a_failing_turn_names_the_case_and_nothing_is_judged(chat: FakeChat, judge: FakeJudge) -> None:
    chat.turns["Pregunta G002"] = MeasurementError("La petición eval-x-G002 falló: el backend respondió 503")

    with pytest.raises(MeasurementError, match="G002"):
        run([case("G001"), case("G002"), case("G003")], chat, judge)

    assert judge.calls == []
    assert [question for question, _ in chat.requests] == ["Pregunta G001", "Pregunta G002"]


def test_a_failing_judge_names_the_case_and_is_not_counted_as_a_zero(chat: FakeChat, judge: FakeJudge) -> None:
    judge.errors["answer_relevancy"] = MeasurementError("El juez falló calculando answer_relevancy: ValueError: x")

    with pytest.raises(MeasurementError, match=r"G001.*answer_relevancy"):
        run([case("G001")], chat, judge)


def test_a_score_out_of_range_from_the_judge_names_the_case(chat: FakeChat, judge: FakeJudge) -> None:
    judge.values["faithfulness"] = 1.4

    with pytest.raises(MeasurementError, match=r"G001.*fuera de 0 a 1"):
        run([case("G001")], chat, judge)


# --- the progress is reported ---------------------------------------------------------------------------------


def test_the_progress_of_both_phases_is_reported_case_by_case(chat: FakeChat, judge: FakeJudge) -> None:
    lines: list[str] = []

    run([case("G001"), unanswered("G002")], chat, judge, progress=lines.append)

    assert lines[0].startswith("[1/2]") and "G001" in lines[0]
    assert any("[2/2]" in line and "G002" in line for line in lines)
    turns = [line for line in lines if "turno" in line]
    judged = [line for line in lines if "juez" in line]
    assert len(turns) == 2
    assert len(judged) == 2


def test_the_run_info_is_kept_in_the_report(chat: FakeChat, judge: FakeJudge) -> None:
    report = run([case("G001")], chat, judge)

    assert report.info == info()
    assert report.info.abstention_prompt_version == ABSTENTION_PROMPT_VERSION
