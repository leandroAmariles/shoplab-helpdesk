"""The evaluation history during one measurement: checked before it, read again at its end and written after it (LAB-31).

The order is the point (research R8). Everything that can make saving impossible is found **before** the assistant or the
judge is asked anything, so a measurement that could not be saved is never spent; the configuration of the assistant is
read at both ends so a run that straddles a change of prompt is refused instead of attributed to one version; and the run
is sent only when the CSV files are already written, so a failure to save never costs the result.
"""

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from shoplab_eval.constants import (
    DEFAULT_ORIGIN,
    DEFAULT_TIMEOUT_SECONDS,
    EVAL_ORIGIN_ENV,
    EVAL_TOKEN_ENV,
    ORIGIN_PATTERN,
)
from shoplab_eval.errors import MeasurementError, StoreError
from shoplab_eval.prompt_selection import PromptChoice
from shoplab_eval.quality_run import QualityReport
from shoplab_eval.retrieval_run import RetrievalReport
from shoplab_eval.store_client import EvalStoreClient, SavedRun
from shoplab_eval.store_payload import (
    AssistantSettings,
    dataset_hash,
    git_commit,
    quality_payload,
    retrieval_payload,
    settings_drift,
)

_ORIGIN = re.compile(ORIGIN_PATTERN)


@dataclass(frozen=True)
class StoreConfig:
    """What saving needs from the environment: the token of the backend and the label of where the run was launched."""

    token: str
    origin: str


def store_config(save: bool | None, env: Mapping[str, str]) -> StoreConfig | None:
    """Whether this run is saved, and with what; ``None`` when it is not. Raises ``ValueError`` on a wrong usage.

    Without an option a run is saved if and only if the token is defined (the convention of Langfuse in LAB-29):
    ``--save`` without the token is a usage error, and ``--no-save`` never saves.
    """
    if save is False:
        return None
    token = env.get(EVAL_TOKEN_ENV, "").strip()
    if not token:
        if save:
            raise ValueError(
                f"--save necesita el token del backend: define {EVAL_TOKEN_ENV} con el mismo valor que "
                "shoplab.eval.persistence.token (si el backend no lo tiene definido, el guardado está apagado allí)"
            )
        return None
    origin = env.get(EVAL_ORIGIN_ENV, "").strip() or DEFAULT_ORIGIN
    if not _ORIGIN.match(origin):
        raise ValueError(
            f"{EVAL_ORIGIN_ENV}={origin!r} no es válido: una etiqueta corta de dónde se lanza (local, dev, ci) con "
            f"letras, cifras, punto, guion y guion bajo ({ORIGIN_PATTERN}); no pongas un usuario ni una máquina"
        )
    return StoreConfig(token, origin)


class StoreSession:
    """One run on its way to the history."""

    def __init__(
        self,
        base_url: str,
        config: StoreConfig,
        run_id: str,
        golden: Path,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        host_header: str | None = None,
        client: EvalStoreClient | None = None,
    ) -> None:
        self.run_id = run_id
        self._config = config
        self._golden = golden
        self._client = client or EvalStoreClient(base_url, config.token, timeout, host_header)
        self._start: AssistantSettings | None = None

    @property
    def assistant(self) -> AssistantSettings:
        if self._start is None:
            raise RuntimeError("prepare() has to run before the settings of the assistant are used")
        return self._start

    def prepare(self) -> None:
        """Before anything is measured: the backend answers, the token is right and the run id is free.

        Reading the settings proves the backend has the history and accepts the token; asking for the run id proves the
        database and the schema work. Any failure is a ``StoreError`` and nothing has been asked of the assistant yet.
        """
        self._start = self._client.settings()
        if self._client.run_exists(self.run_id):
            raise StoreError(
                f"Ya hay una ejecución guardada con el identificador {self.run_id!r}: indica otro con --run-id "
                "(una ejecución guardada no se sobrescribe ni se duplica)"
            )

    def verify_unchanged(self, pinned: Sequence[PromptChoice]) -> None:
        """After the measurement: the assistant still uses what it used at the start, or the run is not valid."""
        changes = settings_drift(self.assistant, self._client.settings(), [choice.name for choice in pinned])
        if changes:
            raise MeasurementError(
                "La configuración del asistente cambió durante la medición (" + ", ".join(changes) + "): unos turnos "
                "se respondieron con una configuración y otros con otra, así que la ejecución no es válida y no se "
                "escribe ni se guarda. Repítela sin tocar los prompts ni reiniciar el backend"
            )

    def save_quality(
        self, report: QualityReport, pinned: Sequence[PromptChoice], started_at: datetime, finished_at: datetime
    ) -> SavedRun:
        payload = quality_payload(
            report,
            assistant=self.assistant,
            pinned=pinned,
            started_at=started_at,
            finished_at=finished_at,
            origin=self._config.origin,
            commit_sha=git_commit(self._golden.resolve().parent),
            dataset_digest=dataset_hash(self._golden),
        )
        return self._client.save(payload)

    def save_retrieval(
        self,
        report: RetrievalReport,
        assistant_model: str | None,
        started_at: datetime,
        finished_at: datetime,
    ) -> SavedRun:
        payload = retrieval_payload(
            report,
            run_id=self.run_id,
            assistant=self.assistant,
            assistant_model=assistant_model,
            started_at=started_at,
            finished_at=finished_at,
            origin=self._config.origin,
            commit_sha=git_commit(self._golden.resolve().parent),
            dataset_digest=dataset_hash(self._golden),
        )
        return self._client.save(payload)


def describe_saved(saved: SavedRun) -> str:
    return (
        f"Guardada en el historial: ejecución {saved.run_id} ({saved.kind.lower()}, {saved.results} resultados, "
        f"{saved.new_case_revisions} revisiones de caso nuevas)"
    )


NOT_SAVED = (
    f"No se guardó nada en el historial (sin {EVAL_TOKEN_ENV} o con --no-save): solo se escribieron los CSV"
)

