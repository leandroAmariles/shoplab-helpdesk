"""The rules of the dataset: one function per rule; all of them collect every failure instead of stopping at the first.

``validate_dataset`` is what ``python -m intent_dataset validate`` and the test over the real files run, with no
network, database or model (FR-023, FR-024). Rules that need more than the examples (the agreement sample, the data
sheet, the guide, the golden ids) are skipped when that input is not given.
"""

import re
from collections import Counter
from collections.abc import Callable, Sequence
from dataclasses import dataclass

from intent_dataset.agreement import cohen_kappa
from intent_dataset.constants import (
    ANNOTATOR_KINDS,
    GOLDEN,
    LABELS,
    LANGUAGES,
    PARAPHRASE,
    SOURCES,
    SPLITS,
    DatasetRules,
)
from intent_dataset.dataset import Example
from intent_dataset.datasheet import (
    REQUIRED_GUIDE_SECTIONS,
    REQUIRED_SECTIONS,
    check_block,
    compute_stats,
    missing_sections,
    render_block,
)
from intent_dataset.errors import Issue, sort_issues
from intent_dataset.masking import find_personal_data
from intent_dataset.splitting import effective_groups
from intent_dataset.text import normalize_text


@dataclass(frozen=True)
class ValidationContext:
    """Everything a rule may look at. Optional inputs are ``None`` when the caller does not validate them."""

    examples: list[Example]
    rules: DatasetRules
    golden_ids: frozenset[str] | None = None
    sample: list[dict] | None = None
    report: dict | None = None
    datasheet: str | None = None
    guide: str | None = None


Rule = Callable[[ValidationContext], list[Issue]]

_ID = re.compile(r"I\d{4}")
_GROUP_FORMAT = {
    "golden": re.compile(r"G\d{3}"),
    "olist_review": re.compile(r"R[0-9a-f]{8}"),
    "manual_seed": re.compile(r"S\d{3}"),
}
_STRING_FIELDS = ("id", "text", "label", "language", "source", "group_id")
_OPTIONAL_STRING_FIELDS = ("parent_id", "golden_id", "split")


def rule_schema(context: ValidationContext) -> list[Issue]:
    """Every field has its type (the loader already rejected unknown and missing keys)."""
    issues = []
    for example in context.examples:
        wrong = [name for name in _STRING_FIELDS if not isinstance(getattr(example, name), str)]
        wrong += [
            name
            for name in _OPTIONAL_STRING_FIELDS
            if getattr(example, name) is not None and not isinstance(getattr(example, name), str)
        ]
        if not isinstance(example.reviewed, bool):
            wrong.append("reviewed")
        if wrong:
            issues.append(Issue("schema", example.id, f"tipo incorrecto en: {', '.join(wrong)}"))
    return issues


def rule_id(context: ValidationContext) -> list[Issue]:
    """The id is ``I`` + 4 digits and unique."""
    issues = []
    for example in context.examples:
        if not isinstance(example.id, str) or not _ID.fullmatch(example.id):
            issues.append(Issue("id", example.id, "el id debe ser I + 4 dígitos (p. ej. I0001)"))
    counts = Counter(example.id for example in context.examples)
    issues.extend(
        Issue("id", example_id, f"id duplicado ({count} ejemplos)") for example_id, count in counts.items() if count > 1
    )
    return issues


def _allowed_value_rule(rule: str, attribute: str, allowed: tuple[str, ...]) -> Rule:
    def check(context: ValidationContext) -> list[Issue]:
        issues = []
        for example in context.examples:
            value = getattr(example, attribute)
            if attribute == "split" and value is None:
                issues.append(Issue(rule, example.id, "sin partición asignada: ejecuta el comando split"))
            elif value not in allowed:
                valid = ", ".join(allowed)
                issues.append(Issue(rule, example.id, f"{attribute} «{value}» no permitido (válidos: {valid})"))
        return issues

    return check


def rule_text(context: ValidationContext) -> list[Issue]:
    """The text has between ``min_text_chars`` and ``max_text_chars`` characters, is not blank and has no line break."""
    low, high = context.rules.min_text_chars, context.rules.max_text_chars
    issues = []
    for example in context.examples:
        text = example.text
        if not isinstance(text, str):
            continue  # the schema rule reports it
        if not text.strip():
            issues.append(Issue("text", example.id, "el texto está vacío"))
        elif not low <= len(text) <= high:
            issues.append(Issue("text", example.id, f"el texto tiene {len(text)} caracteres (permitido {low}–{high})"))
        elif "\n" in text or "\r" in text:
            issues.append(Issue("text", example.id, "el texto tiene saltos de línea"))
    return issues


def rule_reviewed(context: ValidationContext) -> list[Issue]:
    """FR-006: only reviewed examples are published."""
    return [
        Issue("reviewed", example.id, "el ejemplo no está revisado (reviewed debe ser true)")
        for example in context.examples
        if example.reviewed is not True
    ]


def rule_provenance(context: ValidationContext) -> list[Issue]:
    """FR-005: golden id, parent and group are coherent; a paraphrase keeps group, golden id and label of its parent."""
    by_id = {example.id: example for example in context.examples}
    issues = []
    for example in context.examples:
        problems = []
        if example.source == PARAPHRASE:
            parent = by_id.get(example.parent_id) if example.parent_id else None
            if parent is None:
                problems.append(f"el padre «{example.parent_id}» no existe")
            else:
                if parent.group_id != example.group_id:
                    problems.append("el grupo debe ser el de su padre")
                if parent.golden_id != example.golden_id:
                    problems.append("el golden_id debe ser el de su padre")
                if parent.label != example.label:
                    problems.append("la etiqueta debe ser la de su padre")
        else:
            if example.parent_id is not None:
                problems.append("solo una paráfrasis tiene parent_id")
            pattern = _GROUP_FORMAT.get(example.source)
            if pattern and not (isinstance(example.group_id, str) and pattern.fullmatch(example.group_id)):
                problems.append(f"el group_id «{example.group_id}» no tiene el formato de {example.source}")
            if example.source == GOLDEN and example.golden_id != example.group_id:
                problems.append("un ejemplo del golden tiene golden_id igual a su group_id")
        if problems:
            issues.append(Issue("provenance", example.id, "; ".join(problems)))
    return issues


def rule_min_total(context: ValidationContext) -> list[Issue]:
    minimum = context.rules.min_examples
    if len(context.examples) >= minimum:
        return []
    return [Issue("min_total", None, f"hay {len(context.examples)} ejemplos y se exigen al menos {minimum}")]


def rule_min_label(context: ValidationContext) -> list[Issue]:
    minimum = context.rules.min_per_label
    counts = Counter(example.label for example in context.examples)
    return [
        Issue("min_label", None, f"la clase {label} tiene {counts[label]} ejemplos y se exigen al menos {minimum}")
        for label in LABELS
        if counts[label] < minimum
    ]


def rule_min_label_language(context: ValidationContext) -> list[Issue]:
    minimum = context.rules.min_per_label_language
    counts = Counter((example.label, example.language) for example in context.examples)
    return [
        Issue(
            "min_label_language",
            None,
            f"la clase {label} tiene {counts[(label, language)]} ejemplos en {language} y se exigen al menos {minimum}",
        )
        for label in LABELS
        for language in LANGUAGES
        if counts[(label, language)] < minimum
    ]


def rule_pii(context: ValidationContext) -> list[Issue]:
    """FR-019: no personal data recognisable by pattern. The message names the kind, never the data."""
    issues = []
    for example in context.examples:
        if not isinstance(example.text, str):
            continue  # the schema rule reports it
        kinds = sorted({match.kind for match in find_personal_data(example.text)})
        if kinds:
            message = f"el texto contiene datos personales sin enmascarar: {', '.join(kinds)}"
            issues.append(Issue("pii", example.id, message))
    return issues


def rule_group_split(context: ValidationContext) -> list[Issue]:
    """FR-014: no effective group (same group id, or same normalized text) is in more than one split."""
    issues = []
    for group in effective_groups(context.examples):
        splits = sorted({example.split for example in group if example.split in SPLITS})
        if len(splits) > 1:
            groups = ", ".join(sorted({example.group_id for example in group}))
            ids = ", ".join(example.id for example in group)
            message = f"el grupo {groups} está en varias particiones ({', '.join(splits)}): {ids}"
            issues.append(Issue("group_split", group[0].id, message))
    return issues


def rule_duplicates(context: ValidationContext) -> list[Issue]:
    """FR-014: no normalized text appears in two splits."""
    by_text: dict[str, list[Example]] = {}
    for example in context.examples:
        if isinstance(example.text, str):
            by_text.setdefault(normalize_text(example.text), []).append(example)
    issues = []
    for members in by_text.values():
        splits = sorted({example.split for example in members if example.split in SPLITS})
        if len(splits) > 1:
            ids = ", ".join(example.id for example in members)
            issues.append(Issue("duplicates", members[0].id, f"el mismo texto está en {', '.join(splits)}: {ids}"))
    return issues


def rule_split_coverage(context: ValidationContext) -> list[Issue]:
    """FR-016: every split has the 5 labels and the 2 languages. Silent until a split has been assigned."""
    if not any(example.split in SPLITS for example in context.examples):
        return []
    issues = []
    for split in SPLITS:
        members = [example for example in context.examples if example.split == split]
        missing = [f"la clase {label}" for label in LABELS if not any(e.label == label for e in members)]
        for language in LANGUAGES:
            if not any(example.language == language for example in members):
                missing.append(f"el idioma {language}")
        if missing:
            issues.append(Issue("split_coverage", None, f"la partición {split} no tiene {', '.join(missing)}"))
    return issues


def _sample_case_issues(context: ValidationContext, sample: list[dict]) -> list[Issue]:
    by_id = {example.id: example for example in context.examples}
    issues = []
    seen: set[str] = set()
    for row in sample:
        case = row.get("id")
        if case in seen:
            issues.append(Issue("agreement", case, "id repetido en la muestra"))
            continue
        seen.add(case)
        example = by_id.get(case)
        if example is None:
            issues.append(Issue("agreement", case, f"{case} no existe en el dataset"))
            continue
        problems = []
        if row.get("label_b") not in LABELS:
            problems.append("falta la etiqueta del anotador B (ejecuta agreement)")
        elif row.get("label_a") != row["label_b"] and not (row.get("reason") or "").strip():
            problems.append("hay desacuerdo y falta el motivo de la etiqueta final")
        if row.get("final") not in LABELS:
            problems.append("falta la etiqueta final")
        elif example.label != row["final"]:
            problems.append(f"la etiqueta del dataset ({example.label}) no es la final ({row['final']})")
        if problems:
            issues.append(Issue("agreement", case, "; ".join(problems)))
    return issues


def _sample_balance_issues(context: ValidationContext, sample: list[dict]) -> list[Issue]:
    by_id = {example.id: example for example in context.examples}
    members = [by_id[row["id"]] for row in sample if row.get("id") in by_id]
    per_label, extra = divmod(context.rules.agreement_sample_size, len(LABELS))
    # The sample was drawn stratified by the label of the first annotator, frozen as label_a: resolving a
    # disagreement later may change the label of an example, but not how the sample was drawn.
    counts = Counter(row.get("label_a") for row in sample)
    issues = []
    for position, label in enumerate(LABELS):
        expected = per_label + (1 if position < extra else 0)
        if counts[label] != expected:
            issues.append(
                Issue("agreement", None, f"la muestra debe tener {expected} por clase y {label} tiene {counts[label]}")
            )
    for language in LANGUAGES:
        if not any(example.language == language for example in members):
            issues.append(Issue("agreement", None, f"la muestra no tiene ningún ejemplo en el idioma {language}"))
    return issues


def _report_issues(sample: list[dict], report: dict) -> list[Issue]:
    issues = []
    if report.get("annotator_b") not in ANNOTATOR_KINDS:
        issues.append(Issue("agreement", None, f"annotator_b debe ser uno de {', '.join(ANNOTATOR_KINDS)}"))
    if report.get("n") != len(sample):
        message = f"n del informe ({report.get('n')}) no coincide con la muestra ({len(sample)})"
        issues.append(Issue("agreement", None, message))
    pairs = [(row.get("label_a"), row.get("label_b")) for row in sample]
    if pairs and all(a in LABELS and b in LABELS for a, b in pairs):
        result = cohen_kappa([a for a, _ in pairs], [b for _, b in pairs])
        expected = None if result.kappa is None else round(result.kappa, 4)
        if report.get("kappa") != expected:
            message = f"el kappa del informe ({report.get('kappa')}) no coincide con el recalculado ({expected})"
            issues.append(Issue("agreement", None, message))
    return issues


def rule_agreement(context: ValidationContext) -> list[Issue]:
    """FR-011/FR-012: the sample is complete and resolved, and the saved kappa is the one the sample gives."""
    if context.sample is None and context.report is None:
        return []
    if context.sample is None:
        return [Issue("agreement", None, "hay informe de acuerdo pero falta la muestra agreement-sample.jsonl")]
    if context.report is None:
        return [Issue("agreement", None, "hay muestra de acuerdo pero falta agreement.json: ejecuta agreement")]
    sample = context.sample
    issues = []
    if len(sample) != context.rules.agreement_sample_size:
        required = context.rules.agreement_sample_size
        issues.append(Issue("agreement", None, f"la muestra tiene {len(sample)} casos y se exigen {required}"))
    issues += _sample_case_issues(context, sample)
    issues += _sample_balance_issues(context, sample)
    issues += _report_issues(sample, context.report)
    return issues


def rule_datasheet(context: ValidationContext) -> list[Issue]:
    """FR-021/FR-023: the data sheet has every required section and its generated figures match the dataset."""
    if context.datasheet is None:
        return []
    issues = [
        Issue("datasheet", None, f"a la ficha le falta el apartado «{name}»")
        for name in missing_sections(context.datasheet, REQUIRED_SECTIONS)
    ]
    expected = render_block(compute_stats(context.examples, context.report))
    issues += [Issue("datasheet", None, problem) for problem in check_block(context.datasheet, expected)]
    return issues


def rule_guide(context: ValidationContext) -> list[Issue]:
    """FR-010: the labeling guide has a section per class and the borderline cases."""
    if context.guide is None:
        return []
    return [
        Issue("guide", None, f"a la guía le falta el apartado «{name}»")
        for name in missing_sections(context.guide, REQUIRED_GUIDE_SECTIONS)
    ]


def rule_golden_ids(context: ValidationContext) -> list[Issue]:
    """FR-005: every golden id of the dataset exists in the golden. Only checked when the golden ids are given."""
    if context.golden_ids is None:
        return []
    return [
        Issue("golden_ids", example.id, f"el golden_id {example.golden_id} no existe en eval/golden.jsonl")
        for example in context.examples
        if example.golden_id is not None and example.golden_id not in context.golden_ids
    ]


RULES: list[Rule] = [
    rule_schema,
    rule_id,
    _allowed_value_rule("label", "label", LABELS),
    _allowed_value_rule("language", "language", LANGUAGES),
    _allowed_value_rule("source", "source", SOURCES),
    _allowed_value_rule("split", "split", SPLITS),
    rule_text,
    rule_reviewed,
    rule_provenance,
    rule_min_total,
    rule_min_label,
    rule_min_label_language,
    rule_group_split,
    rule_duplicates,
    rule_split_coverage,
    rule_agreement,
    rule_datasheet,
    rule_guide,
    rule_golden_ids,
    rule_pii,
]


def run_rules(context: ValidationContext, rules: Sequence[Rule]) -> list[Issue]:
    """Runs every rule and returns all the issues in a stable order."""
    issues: list[Issue] = []
    for rule in rules:
        issues.extend(rule(context))
    return sort_issues(issues)


def validate_dataset(
    examples: list[Example],
    rules: DatasetRules = DatasetRules(),  # noqa: B008 - frozen dataclass, safe as a default
    *,
    golden_ids: frozenset[str] | None = None,
    sample: list[dict] | None = None,
    report: dict | None = None,
    datasheet: str | None = None,
    guide: str | None = None,
) -> list[Issue]:
    """Every failed rule of the dataset, ordered by rule and example."""
    context = ValidationContext(examples, rules, golden_ids, sample, report, datasheet, guide)
    return run_rules(context, RULES)
