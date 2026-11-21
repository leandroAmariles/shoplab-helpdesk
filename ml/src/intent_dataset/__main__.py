"""``python -m intent_dataset <command>``: build, mask, split and validate the intent dataset (cli-contract.md).

Exit codes: ``0`` done, ``1`` the dataset or a review breaks a rule, ``2`` usage or environment (missing file, variable,
database or gateway). Messages are in Spanish, say how to fix the problem and never include a key.
"""

import argparse
import csv
import json
import sys
from collections import Counter
from collections.abc import Callable, Sequence
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

from intent_dataset.agreement import (
    annotate_sheet,
    compute_agreement,
    read_annotator_sheet,
    read_sample,
    select_sample,
    sheet_texts,
    write_annotator_sheet,
    write_report,
    write_resolved_sample,
    write_sample,
)
from intent_dataset.constants import (
    AGREEMENT_PATH,
    ANNOTATOR_KINDS,
    ANNOTATOR_LOCAL_MODEL,
    DATASET_PATH,
    DATASHEET_PATH,
    GOLDEN_PATH,
    GUIDE_PATH,
    LABELS,
    PARAPHRASE,
    SAMPLE_PATH,
    SPLITS,
    WORK_DIR,
    DatasetRules,
)
from intent_dataset.dataset import dump_examples, load_examples
from intent_dataset.datasheet import compute_stats, render_block, write_block
from intent_dataset.errors import (
    ConfigurationError,
    InvalidDatasetError,
    Issue,
    ParaphraseError,
    ReviewError,
    sort_issues,
)
from intent_dataset.paraphrase import LlmClient, LlmSettings, generate_paraphrases
from intent_dataset.review import build_examples, read_review_csv, write_candidates_csv
from intent_dataset.sources import read_golden, read_reviews
from intent_dataset.splitting import assign_splits
from intent_dataset.validation import validate_dataset
from olist_eda.config import AnalysisSettings
from olist_eda.db import connect

EXIT_OK = 0
EXIT_RULES = 1
EXIT_USAGE = 2


@dataclass
class Dependencies:
    """What commands reach outside for; tests replace them so nothing touches the network or the database."""

    connector: Callable[..., Any] | None = None
    post: Callable[..., Any] | None = None


@dataclass(frozen=True)
class Command:
    name: str
    help: str
    configure: Callable[[argparse.ArgumentParser], None]
    run: Callable[[argparse.Namespace, Dependencies], int]


COMMANDS: dict[str, Command] = {}


def register(command: Command) -> None:
    COMMANDS[command.name] = command


def _load_dataset_for_command(path: Path) -> list:
    """The published dataset; a missing file is an environment problem for the commands that start from it."""
    if not path.is_file():
        raise ConfigurationError(f"No existe {path}: construye primero el dataset con el comando build.")
    return load_examples(path)


# --- candidates --------------------------------------------------------------------------------------------------------


def _configure_candidates(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--golden", type=Path, default=GOLDEN_PATH, help="eval/golden.jsonl")
    parser.add_argument("--reviews", type=int, default=400, help="comentarios de Olist a extraer (def. 400)")
    parser.add_argument("--seed", type=int, default=DatasetRules().seed)
    parser.add_argument("--out", type=Path, default=WORK_DIR / "candidates.csv")
    parser.add_argument("--skip-golden", action="store_true", help="no incluir los casos del golden")
    parser.add_argument("--skip-reviews", action="store_true", help="no leer la base de datos de Olist")
    parser.add_argument("--force", action="store_true", help="pisar un CSV existente (puede tener decisiones)")


def _run_candidates(args: argparse.Namespace, deps: Dependencies) -> int:
    candidates = []
    if not args.skip_golden:
        candidates += read_golden(args.golden)
    if not args.skip_reviews:
        candidates += read_reviews(
            AnalysisSettings.from_env(), connector=deps.connector or connect, limit=args.reviews, seed=args.seed
        )
    write_candidates_csv(candidates, args.out, force=args.force)
    for source, count in Counter(candidate.source for candidate in candidates).items():
        print(f"{source}: {count}")
    hints = Counter(candidate.hint or "sin pista" for candidate in candidates if candidate.source == "olist_review")
    if hints:
        print("pistas de las reseñas: " + ", ".join(f"{hint}={count}" for hint, count in sorted(hints.items())))
    print(f"Candidatos escritos en {args.out} ({len(candidates)}). Revísalos: decision, label y pii_checked.")
    return EXIT_OK


# --- paraphrase --------------------------------------------------------------------------------------------------------


def _configure_paraphrase(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--dataset", type=Path, default=DATASET_PATH)
    parser.add_argument("--per-parent", type=int, default=2, help="variantes por mensaje (def. 2)")
    parser.add_argument("--translate", action="store_true", help="pedir además una variante en el otro idioma")
    parser.add_argument("--labels", default="", help="solo estas clases, separadas por comas")
    parser.add_argument("--limit", type=int, default=0, help="máximo de mensajes de origen (0 = todos)")
    parser.add_argument("--seed", type=int, default=DatasetRules().seed)
    parser.add_argument("--out", type=Path, default=WORK_DIR / "paraphrases.csv")
    parser.add_argument("--force", action="store_true")


def _run_paraphrase(args: argparse.Namespace, deps: Dependencies) -> int:
    labels = [label for label in args.labels.split(",") if label]
    unknown = [label for label in labels if label not in LABELS]
    if unknown:
        raise ConfigurationError(f"Clases desconocidas en --labels: {', '.join(unknown)}. Válidas: {', '.join(LABELS)}.")
    settings = LlmSettings.from_env()
    examples = _load_dataset_for_command(args.dataset)
    parents = [e for e in examples if e.source != PARAPHRASE and (not labels or e.label in labels)]
    if args.limit:
        parents = parents[: args.limit]
    client = LlmClient(settings, post=deps.post)
    result = generate_paraphrases(
        parents, client, per_parent=args.per_parent, translate=args.translate, seed=args.seed
    )
    write_candidates_csv(result.candidates, args.out, force=args.force)
    print(f"Mensajes de origen: {len(parents)}. Variantes generadas: {len(result.candidates)}.")
    if result.discarded:
        print("Descartadas por el filtro: " + ", ".join(f"{k}={v}" for k, v in sorted(result.discarded.items())))
    print(f"Borradores en {args.out}: una persona debe revisar cada fila (decision, label).")
    return EXIT_OK


# --- build -------------------------------------------------------------------------------------------------------------


def _configure_build(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("csv", nargs="+", type=Path, help="CSV de revisión, en orden")
    parser.add_argument("--dataset", type=Path, default=DATASET_PATH)
    parser.add_argument("--dry-run", action="store_true", help="validar y resumir sin escribir")


def _run_build(args: argparse.Namespace, deps: Dependencies) -> int:
    current = load_examples(args.dataset) if args.dataset.is_file() else []
    rules = DatasetRules()
    for path in args.csv:
        result = build_examples(read_review_csv(path), current, rules)
        current = [*current, *result.examples]
        summary = result.summary
        print(
            f"{path.name}: aceptadas: {summary.accepted}, rechazadas: {summary.rejected}, "
            f"pendientes: {summary.pending}, duplicadas: {summary.duplicated}"
        )
        print(f"  por origen: {summary.by_source}; por clase: {summary.by_label}")
    if args.dry_run:
        print("Simulación: no se ha escrito nada.")
    else:
        dump_examples(current, args.dataset)
        print(f"Dataset escrito en {args.dataset}: {len(current)} ejemplos (sin partición hasta ejecutar split).")
    return EXIT_OK


# --- split -------------------------------------------------------------------------------------------------------------


def _ratios(text: str) -> tuple[float, float, float]:
    try:
        parts = [float(part) for part in text.split("/")]
    except ValueError:
        parts = []
    if len(parts) != 3 or any(part <= 0 for part in parts) or abs(sum(parts) - 100) > 1e-6:
        raise ConfigurationError(f"--ratios debe ser train/val/test y sumar 100 (p. ej. 70/15/15), no «{text}».")
    return parts[0] / 100, parts[1] / 100, parts[2] / 100


def _configure_split(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--dataset", type=Path, default=DATASET_PATH)
    parser.add_argument("--seed", type=int, default=DatasetRules().seed)
    parser.add_argument("--ratios", default="70/15/15", help="train/val/test, suman 100 (def. 70/15/15)")


def _run_split(args: argparse.Namespace, deps: Dependencies) -> int:
    rules = replace(DatasetRules(), ratios=_ratios(args.ratios), seed=args.seed)
    examples = _load_dataset_for_command(args.dataset)
    before = {example.id: example.split for example in examples}
    assigned = assign_splits(examples, rules)
    dump_examples(assigned, args.dataset)
    new = sum(1 for example in assigned if before[example.id] is None)
    print(f"Particiones asignadas a {new} ejemplos nuevos de {len(assigned)} (semilla {args.seed}, {args.ratios}).")
    counts = Counter(example.split for example in assigned)
    print("  " + ", ".join(f"{split}: {counts[split]}" for split in SPLITS))
    return EXIT_OK


# --- sample / annotate / agreement -------------------------------------------------------------------------------------


def _configure_sample(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--dataset", type=Path, default=DATASET_PATH)
    parser.add_argument("--size", type=int, default=DatasetRules().agreement_sample_size)
    parser.add_argument("--seed", type=int, default=DatasetRules().seed)
    parser.add_argument("--sample-path", type=Path, default=SAMPLE_PATH)
    parser.add_argument("--sheet", type=Path, default=WORK_DIR / "annotator-b.csv")
    parser.add_argument("--force", action="store_true", help="repetir la muestra (invalida el acuerdo ya medido)")


def _run_sample(args: argparse.Namespace, deps: Dependencies) -> int:
    examples = _load_dataset_for_command(args.dataset)
    chosen = select_sample(examples, replace(DatasetRules(), agreement_sample_size=args.size), seed=args.seed)
    write_sample(args.sample_path, chosen, force=args.force)
    write_annotator_sheet(args.sheet, chosen)
    print(f"Muestra congelada: {len(chosen)} casos en {args.sample_path} (con la etiqueta del primer anotador).")
    print(f"Hoja del anotador B (sin etiquetas): {args.sheet}")
    return EXIT_OK


def _configure_annotate(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--sheet", type=Path, default=WORK_DIR / "annotator-b.csv")
    parser.add_argument("--guide", type=Path, default=GUIDE_PATH)


def _run_annotate(args: argparse.Namespace, deps: Dependencies) -> int:
    settings = LlmSettings.from_env()
    if not args.guide.is_file():
        raise ConfigurationError(f"No existe la guía {args.guide}: el modelo etiqueta siguiéndola.")
    texts = sheet_texts(args.sheet) if args.sheet.is_file() else {}
    if not texts:
        raise ConfigurationError(f"La hoja {args.sheet} no existe o está vacía: ejecuta primero el comando sample.")
    labels, invalid = annotate_sheet(texts, args.guide.read_text(encoding="utf-8"), LlmClient(settings, post=deps.post))
    with args.sheet.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=["id", "text", "label"])
        writer.writeheader()
        for example_id, text in texts.items():
            writer.writerow({"id": example_id, "text": text, "label": labels[example_id]})
    print(f"Anotador B = modelo local ({settings.model}).")
    print(f"Luego ejecuta: agreement --kind local_model --model {settings.model}")
    print("Un acuerdo con un modelo NO es un acuerdo entre personas y la ficha debe decirlo.")
    if invalid:
        print(f"Sin etiqueta válida (rellénalas a mano o repite): {', '.join(invalid)}")
    return EXIT_OK


def _configure_agreement(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--sample-path", type=Path, default=SAMPLE_PATH)
    parser.add_argument("--annotator-b", type=Path, default=WORK_DIR / "annotator-b.csv")
    parser.add_argument("--report", type=Path, default=AGREEMENT_PATH)
    parser.add_argument("--kind", required=True, choices=ANNOTATOR_KINDS, help="person o local_model")
    parser.add_argument("--model", default="", help="modelo, si el anotador B es local_model")
    parser.add_argument("--force", action="store_true", help="recalcular aunque ya haya etiquetas finales")


def _run_agreement(args: argparse.Namespace, deps: Dependencies) -> int:
    if args.kind == ANNOTATOR_LOCAL_MODEL and not args.model:
        raise ConfigurationError("Con --kind local_model indica también --model (p. ej. judge).")
    sample = read_sample(args.sample_path)
    if any(row.get("final") for row in sample) and not args.force:
        raise ReviewError(
            "La muestra ya tiene etiquetas finales: el kappa original se calcula antes de resolver. "
            "Usa --force solo si vas a repetir el acuerdo."
        )
    labels_b = read_annotator_sheet(args.annotator_b)
    updated, report = compute_agreement(sample, labels_b, kind=args.kind, model=args.model or None)
    write_resolved_sample(args.sample_path, updated)
    write_report(args.report, report)
    print(f"kappa de Cohen = {report['kappa']} (n = {report['n']}, acuerdo observado = {report['observed_agreement']})")
    model = f" ({report['annotator_b_model']})" if report["annotator_b_model"] else ""
    print(f"Anotador B: {report['annotator_b']}{model}")
    disagreements = [row for row in updated if row["label_a"] != row["label_b"]]
    print(f"Desacuerdos: {len(disagreements)}")
    for row in disagreements:
        print(f"  - {row['id']}: A = {row['label_a']}, B = {row['label_b']}  → rellena final y reason")
    return EXIT_OK


# --- stats / validate --------------------------------------------------------------------------------------------------


def _read_report(path: Path) -> dict | None:
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None


def _configure_stats(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--dataset", type=Path, default=DATASET_PATH)
    parser.add_argument("--report", type=Path, default=AGREEMENT_PATH)
    parser.add_argument("--datasheet", type=Path, default=DATASHEET_PATH)
    parser.add_argument("--write", action="store_true", help="reescribe el bloque de cifras de la ficha")


def _run_stats(args: argparse.Namespace, deps: Dependencies) -> int:
    examples = _load_dataset_for_command(args.dataset)
    block = render_block(compute_stats(examples, _read_report(args.report)))
    print(block)
    if args.write:
        if not args.datasheet.is_file():
            raise ConfigurationError(f"No existe la ficha {args.datasheet}: escríbela con las marcas del bloque.")
        args.datasheet.write_text(write_block(args.datasheet.read_text(encoding="utf-8"), block), encoding="utf-8")
        print(f"Bloque de cifras actualizado en {args.datasheet}.")
    return EXIT_OK


def _configure_validate(parser: argparse.ArgumentParser) -> None:
    rules = DatasetRules()
    parser.add_argument("--dataset", type=Path, default=DATASET_PATH)
    parser.add_argument("--golden", type=Path, default=None, help="eval/golden.jsonl: comprueba los golden_id")
    parser.add_argument("--no-docs", action="store_true", help="omite guía, ficha y acuerdo (dataset a medio construir)")
    parser.add_argument("--sample-path", type=Path, default=SAMPLE_PATH)
    parser.add_argument("--report", type=Path, default=AGREEMENT_PATH)
    parser.add_argument("--guide", type=Path, default=GUIDE_PATH)
    parser.add_argument("--datasheet", type=Path, default=DATASHEET_PATH)
    parser.add_argument("--min-examples", type=int, default=rules.min_examples)
    parser.add_argument("--min-per-label", type=int, default=rules.min_per_label)
    parser.add_argument("--min-per-label-language", type=int, default=rules.min_per_label_language)
    parser.add_argument("--sample-size", type=int, default=rules.agreement_sample_size)


def _golden_ids(path: Path) -> frozenset[str]:
    if not path.is_file():
        raise ConfigurationError(f"No existe el golden {path}.")
    lines = (line for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    return frozenset(json.loads(line)["id"] for line in lines)


def _run_validate(args: argparse.Namespace, deps: Dependencies) -> int:
    rules = replace(
        DatasetRules(),
        min_examples=args.min_examples,
        min_per_label=args.min_per_label,
        min_per_label_language=args.min_per_label_language,
        agreement_sample_size=args.sample_size,
    )
    examples = _load_dataset_for_command(args.dataset)
    inputs: dict = {}
    missing: list[Issue] = []
    if args.golden:
        inputs["golden_ids"] = _golden_ids(args.golden)
    if not args.no_docs:
        for key, path, hint in (
            ("guide", args.guide, "escribe la guía de etiquetado"),
            ("datasheet", args.datasheet, "escribe la ficha del dataset"),
            ("sample", args.sample_path, "ejecuta sample y agreement"),
            ("report", args.report, "ejecuta agreement"),
        ):
            if not path.is_file():
                missing.append(Issue("docs", None, f"no existe {path.name}: {hint}"))
            elif key == "sample":
                inputs[key] = read_sample(path)
            elif key == "report":
                inputs[key] = _read_report(path)
            else:
                inputs[key] = path.read_text(encoding="utf-8")
    issues = sort_issues([*missing, *validate_dataset(examples, rules, **inputs)])
    for issue in issues:
        print(issue.format())
    if issues:
        print(f"{len(issues)} incidencias.", file=sys.stderr)
        return EXIT_RULES
    print(f"Dataset válido: {len(examples)} ejemplos.")
    return EXIT_OK


register(Command("candidates", "extrae candidatos del golden y de Olist", _configure_candidates, _run_candidates))
register(Command("paraphrase", "genera paráfrasis con el modelo local", _configure_paraphrase, _run_paraphrase))
register(Command("build", "incorpora al dataset las filas aceptadas", _configure_build, _run_build))
register(Command("split", "asigna train/val/test sin mover lo ya asignado", _configure_split, _run_split))
register(Command("sample", "congela la muestra de acuerdo y la hoja del anotador B", _configure_sample, _run_sample))
register(Command("annotate", "el modelo local etiqueta la hoja del anotador B", _configure_annotate, _run_annotate))
register(Command("agreement", "calcula el kappa de Cohen de la muestra", _configure_agreement, _run_agreement))
register(Command("stats", "cifras del dataset; --write actualiza la ficha", _configure_stats, _run_stats))
register(Command("validate", "ejecuta todas las reglas del dataset", _configure_validate, _run_validate))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m intent_dataset", description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True, metavar="command")
    for command in COMMANDS.values():
        command.configure(subparsers.add_parser(command.name, help=command.help))
    return parser


def main(argv: Sequence[str] | None = None, *, deps: Dependencies | None = None) -> int:
    try:
        args = build_parser().parse_args(argv)
    except SystemExit as exit_request:
        return int(exit_request.code or 0)
    try:
        return COMMANDS[args.command].run(args, deps or Dependencies())
    except (InvalidDatasetError, ReviewError) as error:
        print(str(error), file=sys.stderr)
        return EXIT_RULES
    except (ConfigurationError, ParaphraseError) as error:
        print(str(error), file=sys.stderr)
        return EXIT_USAGE


if __name__ == "__main__":
    sys.exit(main())
