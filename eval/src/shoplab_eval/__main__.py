"""``python -m shoplab_eval``: ``validate`` checks the golden dataset, ``retrieval`` measures the search with it and
``quality`` measures the answers of the assistant (faithfulness, answer relevancy and context precision)."""

import argparse
import os
import re
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path

from shoplab_eval.chat_client import SseChatClient
from shoplab_eval.compare import SummaryError, compare_summaries, comparison_csv, format_comparison, read_summary
from shoplab_eval.constants import (
    ABSTENTION_PROMPT_VERSION,
    ASSISTANT_MODEL_ENV,
    BASE_URL_ENV,
    DEFAULT_BASE_URL,
    DEFAULT_CONCURRENCY,
    DEFAULT_DEPTH,
    DEFAULT_GOLDEN_PATH,
    DEFAULT_JUDGE_BASE_URL,
    DEFAULT_JUDGE_MODEL,
    DEFAULT_JUDGE_TEMPERATURE,
    DEFAULT_JUDGE_TIMEOUT_SECONDS,
    DEFAULT_KB_DIR,
    DEFAULT_KS,
    DEFAULT_LANGFUSE_HOST,
    DEFAULT_MAX_INPUT_CHARS,
    DEFAULT_RESULTS_DIR,
    DEFAULT_TIMEOUT_SECONDS,
    DEFAULT_TURN_TIMEOUT_SECONDS,
    EMBEDDING_PROBE_QUERY,
    HOST_HEADER_ENV,
    HOST_HEADER_PATTERN,
    JUDGE_BASE_URL_ENV,
    JUDGE_MODEL_ENV,
    LANGFUSE_HOST_ENV,
    LANGFUSE_PUBLIC_KEY_ENV,
    LANGFUSE_SECRET_KEY_ENV,
    MAX_CONCURRENCY,
    MAX_DEPTH,
    MIN_CONCURRENCY,
    PROMPT_NAMES,
    PROMPT_TOKEN_ENV,
    UNKNOWN,
)
from shoplab_eval.corpus import load_corpus, title_index
from shoplab_eval.errors import InvalidGoldenError, MeasurementError
from shoplab_eval.golden import GoldenCase, load_golden, validate_golden
from shoplab_eval.langfuse_client import LangfuseClient, TraceScores
from shoplab_eval.prompt_client import PromptClient, PromptResolver
from shoplab_eval.prompt_selection import (
    PromptChoice,
    PromptRequest,
    format_prompt_versions,
    parse_prompt_arguments,
    versions_header,
)
from shoplab_eval.quality_judge import (
    GatewayClient,
    Judge,
    JudgeSettings,
    build_ragas_judge,
    ragas_version,
    read_judge_api_key,
    resolve_judge_settings,
)
from shoplab_eval.quality_report import format_quality_report, write_quality_report
from shoplab_eval.quality_run import RunInfo, measure_quality, new_run_id, request_id_for
from shoplab_eval.retrieval_report import format_report, write_report
from shoplab_eval.retrieval_run import check_index, measure_retrieval
from shoplab_eval.search_client import KnowledgeSearchClient
from shoplab_eval.summary import format_summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="shoplab_eval", description="Dataset golden de ShopLab Helpdesk")
    commands = parser.add_subparsers(dest="command")
    validate = commands.add_parser("validate", help="valida eval/golden.jsonl contra el corpus y muestra el resumen")
    validate.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN_PATH, help="ruta del archivo JSONL")
    validate.add_argument("--kb", type=Path, default=DEFAULT_KB_DIR, help="directorio del corpus (data/kb)")
    retrieval = commands.add_parser(
        "retrieval", help="mide hit rate@k, recall@k y MRR de la búsqueda real contra el dataset golden"
    )
    retrieval.add_argument("--k", default=",".join(str(k) for k in DEFAULT_KS), help="valores de k separados por comas")
    retrieval.add_argument("--depth", type=int, default=DEFAULT_DEPTH, help="fragmentos que se piden por pregunta")
    retrieval.add_argument(
        "--base-url",
        default=os.environ.get(BASE_URL_ENV, DEFAULT_BASE_URL),
        help=f"backend que sirve la búsqueda (variable {BASE_URL_ENV})",
    )
    retrieval.add_argument("--out", type=Path, default=DEFAULT_RESULTS_DIR, help="carpeta de salida de los CSV")
    retrieval.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN_PATH, help="ruta del archivo JSONL")
    retrieval.add_argument("--kb", type=Path, default=DEFAULT_KB_DIR, help="directorio del corpus (data/kb)")
    retrieval.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS, help="segundos por petición")
    retrieval.add_argument("--host-header", default=None, help=_HOST_HEADER_HELP)
    quality = commands.add_parser(
        "quality", help="mide fidelidad, relevancia y precisión del contexto de las respuestas del asistente real"
    )
    quality.add_argument(
        "--base-url",
        default=os.environ.get(BASE_URL_ENV, DEFAULT_BASE_URL),
        help=f"backend del chat (variable {BASE_URL_ENV})",
    )
    quality.add_argument("--host-header", default=None, help=_HOST_HEADER_HELP)
    quality.add_argument("--out", type=Path, default=DEFAULT_RESULTS_DIR, help="carpeta de salida de los CSV")
    quality.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN_PATH, help="ruta del archivo JSONL")
    quality.add_argument("--kb", type=Path, default=DEFAULT_KB_DIR, help="directorio del corpus (data/kb)")
    quality.add_argument("--run-id", default=None, help="identificador de la ejecución (por defecto, el momento UTC)")
    quality.add_argument(
        "--concurrency",
        type=int,
        default=None,
        help=f"casos que el juez puntúa a la vez ({MIN_CONCURRENCY} a {MAX_CONCURRENCY}; defecto {DEFAULT_CONCURRENCY})",
    )
    quality.add_argument(
        "--judge-model",
        default=None,
        help=f"nombre lógico del juez en el gateway (variable {JUDGE_MODEL_ENV}; defecto {DEFAULT_JUDGE_MODEL})",
    )
    quality.add_argument(
        "--judge-base-url",
        default=None,
        help=f"gateway compatible con OpenAI (variable {JUDGE_BASE_URL_ENV}; defecto {DEFAULT_JUDGE_BASE_URL})",
    )
    quality.add_argument(
        "--judge-temperature",
        type=float,
        default=None,
        help=f"temperatura del juez, de 0 a 2 (por defecto {DEFAULT_JUDGE_TEMPERATURE:g})",
    )
    quality.add_argument(
        "--judge-timeout",
        type=float,
        default=None,
        help=f"segundos máximos por métrica del juez (por defecto {DEFAULT_JUDGE_TIMEOUT_SECONDS})",
    )
    quality.add_argument(
        "--max-input-chars",
        type=int,
        default=None,
        help=f"tope de caracteres que se envían al juez por caso (por defecto {DEFAULT_MAX_INPUT_CHARS})",
    )
    quality.add_argument(
        "--turn-timeout",
        type=float,
        default=DEFAULT_TURN_TIMEOUT_SECONDS,
        help="segundos máximos esperando al asistente",
    )
    quality.add_argument(
        "--assistant-model",
        default=None,
        help=f"modelo de chat del gateway que se registra (variable {ASSISTANT_MODEL_ENV}); el backend no lo expone",
    )
    quality.add_argument(
        "--langfuse",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="adjunta las puntuaciones a las trazas de Langfuse; sin la opción se adjuntan si "
        f"{LANGFUSE_PUBLIC_KEY_ENV} y {LANGFUSE_SECRET_KEY_ENV} están definidas "
        f"(servidor: {LANGFUSE_HOST_ENV}, por defecto {DEFAULT_LANGFUSE_HOST})",
    )
    quality.add_argument(
        "--prompt",
        action="append",
        default=[],
        metavar="NOMBRE=VALOR",
        help="fija un prompt en una versión (número) o en la versión a la que apunta una etiqueta, sin tocar "
        f"production; se repite por prompt ({', '.join(PROMPT_NAMES)}). Necesita {PROMPT_TOKEN_ENV} y las claves de "
        f"Langfuse ({LANGFUSE_PUBLIC_KEY_ENV}, {LANGFUSE_SECRET_KEY_ENV})",
    )
    compare = commands.add_parser(
        "compare",
        help="compara dos resúmenes de calidad (quality-summary.csv): medias, diferencia y prompts que difieren",
    )
    compare.add_argument("summary_a", type=Path, help="quality-summary.csv del resultado A")
    compare.add_argument("summary_b", type=Path, help="quality-summary.csv del resultado B")
    compare.add_argument("--out", type=Path, default=None, help="escribe también la tabla como CSV en esta ruta")
    args = parser.parse_args(argv)
    _use_utf8_output()
    if args.command == "compare":
        return _run_compare(args)
    if args.command == "quality":
        return _run_quality(args)
    if args.command == "retrieval":
        return _run_retrieval(args)
    if args.command != "validate":
        parser.print_help()
        return 2
    try:
        summary = validate_golden(args.golden, args.kb)
    except InvalidGoldenError as error:
        print(error)
        return 1
    except (FileNotFoundError, ValueError) as error:
        print(error)
        return 1
    print(format_summary(summary))
    return 0


def _run_retrieval(args: argparse.Namespace) -> int:
    """Exit codes: 0 measured and written, 1 the measurement is not valid (nothing written), 2 wrong usage.

    The CSV files are written only after the whole measurement succeeded, so a failure never leaves a result that
    looks valid (and never touches the files of an earlier run).
    """
    try:
        ks = _parse_ks(args.k, args.depth)
        host_header = _host_header(args, os.environ)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    try:
        cases = load_golden(args.golden, args.kb)
        client = KnowledgeSearchClient(args.base_url, args.timeout, host_header)
        report = measure_retrieval(cases, client, ks, args.depth)
    except (InvalidGoldenError, MeasurementError, FileNotFoundError) as error:
        print(error)
        return 1
    print(format_report(report))
    summary_path, detail_path = write_report(report, args.out)
    print(f"\nResumen: {summary_path}\nDetalle: {detail_path}")
    return 0


_HOST_HEADER_HELP = (
    f"valor de la cabecera Host de las peticiones al backend (variable {HOST_HEADER_ENV}): el ingress de DEV enruta por "
    "ella (shoplab.localtest.me), y así se entra por 127.0.0.1 sin depender del DNS"
)


def _host_header(args: argparse.Namespace, env: Mapping[str, str]) -> str | None:
    """The ``Host`` to send, from the flag or the environment; ``None`` when none is given. Raises ``ValueError``."""
    value = args.host_header if args.host_header is not None else env.get(HOST_HEADER_ENV)
    if value is None:
        return None
    if not re.fullmatch(HOST_HEADER_PATTERN, value):
        raise ValueError(
            f"--host-header {value!r} no es válido: debe ser un nombre de host (letras, cifras, puntos y guiones, y "
            "opcionalmente :puerto), por ejemplo shoplab.localtest.me"
        )
    return value


def build_judge(settings: JudgeSettings, api_key: str) -> Judge:
    """The real judge over the gateway. It is a seam: the tests put a double here, because a judge needs a model."""
    return build_ragas_judge(settings, api_key)


def build_langfuse(host: str, public_key: str, secret_key: str) -> TraceScores:
    """The real Langfuse client. It is a seam: the tests put a client with short waits here."""
    return LangfuseClient(host, public_key, secret_key)


def build_prompt_client(host: str, public_key: str, secret_key: str) -> PromptResolver:
    """The real resolver of prompt versions. It is a seam: the tests put a double here."""
    return PromptClient(host, public_key, secret_key)


def _resolve_prompts(requests: tuple[PromptRequest, ...], env: Mapping[str, str]) -> tuple[PromptChoice, ...]:
    """Turns every ``--prompt`` into the version it means, before the assistant is asked anything (FR-019, FR-023).

    Raises ``MeasurementError`` when Langfuse cannot be asked, a version does not exist or is not text. It never falls
    back to the default text: a run measured with other prompts than the ones asked for would be a wrong result.
    """
    if not requests:
        return ()
    public_key = env.get(LANGFUSE_PUBLIC_KEY_ENV, "").strip()
    secret_key = env.get(LANGFUSE_SECRET_KEY_ENV, "").strip()
    if not (public_key and secret_key):
        raise MeasurementError(
            f"--prompt necesita consultar a Langfuse: define {LANGFUSE_PUBLIC_KEY_ENV} y {LANGFUSE_SECRET_KEY_ENV} "
            "(las claves del proyecto); sin ellas no se sabe qué versión se mide y no se mide con el texto por defecto"
        )
    host = env.get(LANGFUSE_HOST_ENV, "").strip() or DEFAULT_LANGFUSE_HOST
    resolver = build_prompt_client(host, public_key, secret_key)
    return tuple(resolver.resolve(request) for request in requests)


def _langfuse_config(args: argparse.Namespace, env: Mapping[str, str]) -> tuple[str, str, str] | None:
    """The host and keys to attach scores with, or ``None`` when they must not be attached (FR-014).

    Without an option they are attached when both keys are defined. ``--no-langfuse`` never attaches and an explicit
    ``--langfuse`` without the keys is a usage error: asking for something that cannot be done is not skipped silently.
    """
    if args.langfuse is False:
        return None
    public_key = env.get(LANGFUSE_PUBLIC_KEY_ENV, "").strip()
    secret_key = env.get(LANGFUSE_SECRET_KEY_ENV, "").strip()
    if not (public_key and secret_key):
        if args.langfuse is True:
            raise ValueError(
                f"--langfuse necesita las claves del proyecto de Langfuse: define {LANGFUSE_PUBLIC_KEY_ENV} y "
                f"{LANGFUSE_SECRET_KEY_ENV} (en local, las de LANGFUSE_INIT_* de infra/.env.example)"
            )
        return None
    return env.get(LANGFUSE_HOST_ENV, "").strip() or DEFAULT_LANGFUSE_HOST, public_key, secret_key


def _run_quality(args: argparse.Namespace) -> int:
    """Exit codes: 0 measured, written and attached; 1 the measurement is not valid (nothing written) or some scores
    could not be attached (the files are written anyway); 2 wrong usage.

    The files are written only after the whole measurement succeeded, so a failure never leaves a result that looks
    valid. Results go to the standard output; progress and problems go to the error output. What is wrong with the
    options is found before anything is asked of the gateway, of Langfuse or of the assistant.
    """
    try:
        run_id = args.run_id or new_run_id()
        request_id_for(run_id, "G001")  # a run id that would not make a safe request id is rejected before anything runs
        settings = resolve_judge_settings(args, os.environ)
        api_key = read_judge_api_key(os.environ)
        langfuse_config = _langfuse_config(args, os.environ)
        host_header = _host_header(args, os.environ)
        prompt_requests = parse_prompt_arguments(args.prompt)
        prompt_token = os.environ.get(PROMPT_TOKEN_ENV, "").strip()
        if prompt_requests and not prompt_token:
            raise ValueError(
                f"--prompt necesita {PROMPT_TOKEN_ENV}: el mismo valor que shoplab.chat.prompts.override-token del "
                "backend, que es lo que permite fijar versiones en una petición"
            )
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    assistant_model = args.assistant_model or os.environ.get(ASSISTANT_MODEL_ENV) or UNKNOWN
    if assistant_model == UNKNOWN:
        print(
            f"Aviso: el modelo del asistente se registrará como «{UNKNOWN}»: el backend no lo expone; indícalo con "
            f"--assistant-model o {ASSISTANT_MODEL_ENV} para que los resultados sean comparables",
            file=sys.stderr,
        )
    try:
        # The versions are resolved first: a prompt that does not exist stops the run before anything else is asked.
        choices = _resolve_prompts(prompt_requests, os.environ)
        if choices:
            _progress("Prompts fijados: " + format_prompt_versions(choices))
        cases = load_golden(args.golden, args.kb)
        titles = title_index(load_corpus(args.kb))
        gateway = GatewayClient(settings.base_url, api_key)
        gateway.check_ready(settings.model)  # before the assistant is asked anything
        langfuse = build_langfuse(*langfuse_config) if langfuse_config else None
        if langfuse is not None:
            langfuse.check_access()
        # An empty or partial index would make the answers look bad for a reason that is not the assistant's.
        _check_index(args.base_url, args.turn_timeout, host_header, cases)
        info = RunInfo(
            run_id=run_id,
            judge=settings,
            judge_underlying_model=_underlying_model(gateway, settings.model),
            ragas_version=ragas_version(),
            abstention_prompt_version=ABSTENTION_PROMPT_VERSION,
            assistant_model=assistant_model,
            embedding_model=_embedding_model(args.base_url, args.turn_timeout, host_header),
            langfuse="skipped",
            prompt_versions=format_prompt_versions(choices),
        )
        chat = SseChatClient(
            args.base_url, args.turn_timeout, titles, host_header, versions_header(choices), prompt_token
        )
        report = measure_quality(
            cases, chat, build_judge(settings, api_key), info, progress=_progress, langfuse=langfuse
        )
    except (InvalidGoldenError, MeasurementError, FileNotFoundError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(format_quality_report(report))
    summary_path, detail_path = write_quality_report(report, args.out)
    print(f"\nResumen: {summary_path}\nDetalle: {detail_path}")
    if report.attach_failures:
        # The scores cost hundreds of calls of the judge, so the files are kept; the code says it is not complete.
        print(
            f"\n{len(report.attach_failures)} casos no se adjuntaron a Langfuse (columna langfuse = no):\n  "
            + "\n  ".join(report.attach_failures),
            file=sys.stderr,
        )
        return 1
    return 0


def _run_compare(args: argparse.Namespace) -> int:
    """Exit codes: 0 compared (with or without warnings of comparability); 2 a summary cannot be read.

    It only reads the two files: no model, no Langfuse and no backend.
    """
    try:
        comparison = compare_summaries(read_summary(args.summary_a), read_summary(args.summary_b))
    except SummaryError as error:
        print(error, file=sys.stderr)
        return 2
    print(format_comparison(comparison))
    if args.out is not None:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(comparison_csv(comparison), encoding="utf-8", newline="")
        print(f"\nComparación: {args.out}")
    return 0


def _check_index(base_url: str, timeout: float, host_header: str | None, cases: Sequence[GoldenCase]) -> None:
    """Every document the dataset expects must be indexed (the same check as the retrieval measurement of LAB-28)."""
    expected = {document for case in cases for document in case.expected_documents}
    if expected:
        check_index(KnowledgeSearchClient(base_url, timeout, host_header).list_documents(), expected)


def _underlying_model(gateway: GatewayClient, model: str) -> str:
    """The real model behind the logical name of the judge: it is what makes two results comparable (FR-018)."""
    real = gateway.underlying_model(model)
    if real is None:
        print(
            f"Aviso: no se pudo leer el modelo real detrás de «{model}» (GET /model/info del gateway); "
            f"judge_underlying_model queda como «{UNKNOWN}» y dos resultados con ese valor no se pueden comparar",
            file=sys.stderr,
        )
        return UNKNOWN
    return real


def _embedding_model(base_url: str, timeout: float, host_header: str | None) -> str:
    """The embedding model the knowledge base was indexed with, read with a single search of the backend."""
    try:
        return KnowledgeSearchClient(base_url, timeout, host_header).search(EMBEDDING_PROBE_QUERY, 1).model or UNKNOWN
    except MeasurementError as error:
        print(
            f"Aviso: no se pudo leer el modelo de embeddings ({error}); embedding_model queda como «{UNKNOWN}»",
            file=sys.stderr,
        )
        return UNKNOWN


def _progress(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def _parse_ks(raw: str, depth: int) -> tuple[int, ...]:
    """The k to report: positive integers, no repeats, none above the depth. Raises ``ValueError`` with the fix."""
    if not 1 <= depth <= MAX_DEPTH:
        raise ValueError(f"--depth {depth} no es válido: debe ser un entero entre 1 y {MAX_DEPTH}")
    problem = (
        f"--k {raw!r} no es válido: usa enteros positivos separados por comas, sin repetir y no mayores que "
        f"--depth ({depth}); por ejemplo 1,3,5"
    )
    try:
        ks = tuple(int(value) for value in raw.split(","))
    except ValueError:
        raise ValueError(problem) from None
    if any(k < 1 or k > depth for k in ks) or len(set(ks)) != len(ks):
        raise ValueError(problem)
    return ks


def _use_utf8_output() -> None:
    """The report is in Spanish; the default console encoding of Windows would garble the accents."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
