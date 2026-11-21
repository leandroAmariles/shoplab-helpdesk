from dataclasses import replace

import pytest
from intent_samples import SMALL_RULES, make_dataset, make_example, modify

from intent_dataset.errors import Issue
from intent_dataset.validation import ValidationContext, run_rules, validate_dataset


def test_a_valid_dataset_has_no_issues():
    assert validate_dataset(make_dataset(), SMALL_RULES) == []


def test_run_rules_collects_every_failure_instead_of_stopping_at_the_first():
    def first(context):
        return [Issue("zeta", "I0002", "segundo"), Issue("alfa", None, "del conjunto")]

    def second(context):
        return [Issue("alfa", "I0001", "primero")]

    context = ValidationContext(examples=make_dataset(), rules=SMALL_RULES)
    issues = run_rules(context, [first, second])
    assert [(i.rule, i.example_id) for i in issues] == [("alfa", None), ("alfa", "I0001"), ("zeta", "I0002")]


def test_issue_formats_the_rule_and_the_example():
    assert Issue("pii", "I0007", "correo").format() == "[pii] I0007: correo"
    assert Issue("min_total", None, "faltan").format() == "[min_total] SET: faltan"


# --- US4: personal data (FR-019) -------------------------------------------------------------------------------------


def test_pii_rule_flags_an_email_and_a_phone_with_their_example_and_kind():
    examples = modify(make_dataset(), "I0001", text="escríbeme a ana@correo.com")
    examples = modify(examples, "I0002", text="llámame al 11 91234-5678")
    issues = [i for i in validate_dataset(examples, SMALL_RULES) if i.rule == "pii"]
    assert [(i.example_id) for i in issues] == ["I0001", "I0002"]
    assert "EMAIL" in issues[0].message and "PHONE" in issues[1].message


def test_pii_message_never_repeats_the_personal_data():
    examples = modify(make_dataset(), "I0001", text="escríbeme a ana@correo.com")
    (issue,) = [i for i in validate_dataset(examples, SMALL_RULES) if i.rule == "pii"]
    assert "ana@correo.com" not in issue.message


def test_pii_rule_is_silent_for_masked_text():
    examples = modify(make_dataset(), "I0001", text="escríbeme a [EMAIL] o al [PHONE]")
    assert [i for i in validate_dataset(examples, SMALL_RULES) if i.rule == "pii"] == []


# --- US1: structure, provenance and minimums (FR-001 to FR-006) ------------------------------------------------------


def _of(examples, rule, rules=SMALL_RULES):
    return [i for i in validate_dataset(examples, rules) if i.rule == rule]


def test_schema_rejects_values_of_the_wrong_type():
    examples = modify(make_dataset(), "I0001", reviewed="yes")
    examples = modify(examples, "I0002", text=123)
    assert [i.example_id for i in _of(examples, "schema")] == ["I0001", "I0002"]


@pytest.mark.parametrize("bad_id", ["I001", "X0001", "I00001", "i0001", ""])
def test_id_must_be_I_plus_four_digits(bad_id):
    examples = modify(make_dataset(), "I0001", id=bad_id)
    assert [i.example_id for i in _of(examples, "id")] == [bad_id]


def test_ids_must_be_unique():
    examples = modify(make_dataset(), "I0002", id="I0001")
    issues = _of(examples, "id")
    assert len(issues) == 1 and "duplicado" in issues[0].message


@pytest.mark.parametrize(
    ("field", "value", "rule"),
    [("label", "otra", "label"), ("language", "en", "language"), ("source", "web", "source"), ("split", "dev", "split")],
)
def test_values_outside_the_allowed_set_are_rejected(field, value, rule):
    examples = modify(make_dataset(), "I0001", **{field: value})
    assert [i.example_id for i in _of(examples, rule)] == ["I0001"]


def test_a_published_example_must_have_a_split():
    examples = modify(make_dataset(), "I0001", split=None)
    (issue,) = _of(examples, "split")
    assert issue.example_id == "I0001" and "partición" in issue.message


@pytest.mark.parametrize(
    "text",
    ["", "   ", "x", "a" * 501, "dos\nlíneas"],
)
def test_text_must_be_two_to_five_hundred_characters_on_one_line(text):
    examples = modify(make_dataset(), "I0001", text=text)
    assert [i.example_id for i in _of(examples, "text")] == ["I0001"]


def test_text_at_the_limits_is_accepted():
    examples = modify(make_dataset(), "I0001", text="ok")
    examples = modify(examples, "I0002", text="a" * 500)
    assert _of(examples, "text") == []


def test_only_reviewed_examples_are_published():
    examples = modify(make_dataset(), "I0001", reviewed=False)
    assert [i.example_id for i in _of(examples, "reviewed")] == ["I0001"]


def test_a_golden_example_has_the_golden_id_of_its_group():
    base = modify(make_dataset(), "I0001", source="golden", group_id="G001", golden_id="G001")
    assert _of(base, "provenance") == []
    broken = modify(base, "I0001", golden_id="G002")
    assert [i.example_id for i in _of(broken, "provenance")] == ["I0001"]
    missing = modify(base, "I0001", golden_id=None)
    assert [i.example_id for i in _of(missing, "provenance")] == ["I0001"]


@pytest.mark.parametrize(
    ("source", "group_id"),
    [("golden", "S001"), ("olist_review", "G001"), ("olist_review", "R123"), ("manual_seed", "G001")],
)
def test_the_group_id_format_follows_the_source(source, group_id):
    golden_id = group_id if source == "golden" else None
    examples = modify(make_dataset(), "I0001", source=source, group_id=group_id, golden_id=golden_id)
    assert [i.example_id for i in _of(examples, "provenance")] == ["I0001"]


def _with_paraphrase(**changes):
    examples = modify(make_dataset(), "I0001", label="pedido", group_id="S001")
    values = {
        "text": "paráfrasis de prueba",
        "label": "pedido",
        "language": "es",
        "source": "paraphrase",
        "group_id": "S001",
        "parent_id": "I0001",
        "split": "train",
    }
    values.update(changes)
    return [*examples, make_example("I0031", **values)]


def test_a_valid_paraphrase_passes_provenance():
    assert _of(_with_paraphrase(), "provenance") == []


@pytest.mark.parametrize(
    "changes",
    [
        {"parent_id": None},
        {"parent_id": "I0099"},
        {"group_id": "S999"},
        {"label": "politica"},
        {"golden_id": "G001"},
    ],
)
def test_a_paraphrase_needs_an_existing_parent_with_the_same_group_label_and_golden_id(changes):
    assert [i.example_id for i in _of(_with_paraphrase(**changes), "provenance")] == ["I0031"]


def test_only_a_paraphrase_has_a_parent():
    examples = modify(make_dataset(), "I0002", parent_id="I0001")
    assert [i.example_id for i in _of(examples, "provenance")] == ["I0002"]


def test_the_total_minimum():
    examples = make_dataset()[:-1]
    assert [i.example_id for i in _of(examples, "min_total")] == [None]


def test_the_minimum_per_label():
    examples = [e for e in make_dataset() if not (e.label == "charla" and e.split == "test")]
    issues = _of(examples, "min_label")
    assert len(issues) == 1 and "charla" in issues[0].message


def test_the_minimum_per_label_and_language():
    examples = [e for e in make_dataset() if not (e.label == "pedido" and e.language == "pt" and e.split == "test")]
    issues = _of(examples, "min_label_language")
    assert len(issues) == 1 and "pedido" in issues[0].message and "pt" in issues[0].message


def test_a_label_missing_in_one_language_reports_that_cell():
    examples = [e for e in make_dataset() if not (e.label == "escalado" and e.language == "pt")]
    issues = _of(examples, "min_label_language")
    assert len(issues) == 1 and "escalado" in issues[0].message


# --- US3: splits (FR-014, FR-016) ------------------------------------------------------------------------------------


def test_group_split_flags_a_group_in_two_splits():
    examples = modify(make_dataset(), "I0001", group_id="S002")  # I0001 is train, I0002 (S002) is val
    (issue,) = _of(examples, "group_split")
    assert "S002" in issue.message and "train" in issue.message and "val" in issue.message


def test_group_split_flags_a_group_joined_by_normalized_text():
    # I0001 (train) and I0002 (val) are in different groups but end up with the same normalized text.
    examples = modify(make_dataset(), "I0002", text="¡CHARLA es TRAIN caso 1!")
    examples = modify(examples, "I0001", text="charla es train caso 1")
    assert len(_of(examples, "group_split")) == 1


def test_duplicates_flags_the_same_normalized_text_in_two_splits():
    examples = modify(make_dataset(), "I0002", text="Charla es TRAIN caso 1")
    examples = modify(examples, "I0001", text="charla es train caso 1")
    (issue,) = _of(examples, "duplicates")
    assert "I0001" in issue.message and "I0002" in issue.message


def test_duplicates_ignores_the_same_text_inside_one_split():
    examples = modify(make_dataset(), "I0001", text="mismo texto", group_id="S001")
    examples = modify(examples, "I0002", text="mismo texto", group_id="S001", split="train")
    assert _of(examples, "duplicates") == []


def test_split_coverage_flags_a_split_without_a_class():
    examples = [e for e in make_dataset() if not (e.split == "test" and e.label == "pedido")]
    (issue,) = _of(examples, "split_coverage")
    assert "test" in issue.message and "pedido" in issue.message


def test_split_coverage_flags_a_split_without_a_language():
    examples = [e for e in make_dataset() if not (e.split == "val" and e.language == "pt")]
    (issue,) = _of(examples, "split_coverage")
    assert "val" in issue.message and "pt" in issue.message


def test_split_coverage_is_silent_before_any_split_is_assigned():
    examples = [replace(e, split=None) for e in make_dataset()]
    assert _of(examples, "split_coverage") == []


# --- US2: agreement sample and report (FR-011, FR-012) ---------------------------------------------------------------

AGREEMENT_RULES = replace(SMALL_RULES, agreement_sample_size=10)


def _agreement():
    """A valid dataset, a resolved 10-case sample (2 per class, both languages, one resolved disagreement) and report."""
    from intent_dataset.agreement import compute_agreement

    examples = make_dataset()
    ids = [f"I{n:04d}" for label_index in range(5) for n in (label_index * 6 + 1, label_index * 6 + 4)]
    sample = [{"id": i, "label_a": next(e.label for e in examples if e.id == i), "label_b": None, "final": None,
               "reason": None} for i in ids]  # fmt: skip
    labels_b = {row["id"]: row["label_a"] for row in sample}
    labels_b[ids[0]] = "escalado"
    resolved, report = compute_agreement(sample, labels_b, kind="person", today="2026-11-30")
    resolved[0]["final"] = resolved[0]["label_a"]
    resolved[0]["reason"] = "gana la prioridad de la guía"
    return examples, resolved, report


def _agreement_issues(examples, sample, report):
    issues = validate_dataset(examples, AGREEMENT_RULES, sample=sample, report=report)
    return [issue for issue in issues if issue.rule == "agreement"]


def test_a_resolved_sample_with_its_report_passes():
    assert _agreement_issues(*_agreement()) == []


def test_the_agreement_rule_is_skipped_when_no_sample_or_report_is_given():
    assert _of(make_dataset(), "agreement", AGREEMENT_RULES) == []


def test_the_sample_needs_the_configured_size():
    examples, sample, report = _agreement()
    issues = _agreement_issues(examples, sample[:-1], report)
    assert any("9" in i.message and "10" in i.message for i in issues)


def test_a_sample_id_must_exist_in_the_dataset_and_not_repeat():
    examples, sample, report = _agreement()
    sample[1] = {**sample[1], "id": "I9999"}
    sample[2] = {**sample[2], "id": sample[3]["id"]}
    messages = " ".join(i.message for i in _agreement_issues(examples, sample, report))
    assert "I9999" in messages and "repetido" in messages


def test_every_case_needs_a_final_label_and_label_b():
    examples, sample, report = _agreement()
    sample[1] = {**sample[1], "final": None}
    sample[2] = {**sample[2], "label_b": None}
    ids = [i.example_id for i in _agreement_issues(examples, sample, report)]
    assert sample[1]["id"] in ids and sample[2]["id"] in ids


def test_a_disagreement_needs_a_reason():
    examples, sample, report = _agreement()
    sample[0] = {**sample[0], "reason": None}
    (issue,) = [i for i in _agreement_issues(examples, sample, report) if i.example_id == sample[0]["id"]]
    assert "motivo" in issue.message


def test_the_dataset_label_must_be_the_final_label():
    examples, sample, report = _agreement()
    examples = modify(examples, sample[1]["id"], label="producto")
    assert sample[1]["id"] in [i.example_id for i in _agreement_issues(examples, sample, report)]


def test_the_sample_needs_the_same_number_per_class_and_both_languages():
    examples, sample, report = _agreement()
    other = [e for e in examples if e.label == "politica" and e.id not in {r["id"] for r in sample}][0]
    sample[2] = {**sample[2], "id": other.id}  # replaces a pedido case with a politica one, same language
    messages = " ".join(i.message for i in _agreement_issues(examples, sample, report))
    assert "por clase" in messages


def test_a_sample_in_a_single_language_is_flagged():
    examples, sample, report = _agreement()
    examples = [replace(e, language="es") if e.id in {r["id"] for r in sample} else e for e in examples]
    assert any("idioma" in i.message for i in _agreement_issues(examples, sample, report))


def test_the_report_needs_its_kind_of_annotator_b_and_a_matching_n():
    examples, sample, report = _agreement()
    assert any("annotator_b" in i.message for i in _agreement_issues(examples, sample, {**report, "annotator_b": "x"}))
    assert any("n" in i.message for i in _agreement_issues(examples, sample, {**report, "n": 49}))


def test_the_report_kappa_is_recomputed_so_a_hand_edit_is_detected():
    examples, sample, report = _agreement()
    issues = _agreement_issues(examples, sample, {**report, "kappa": 0.99})
    assert any("kappa" in i.message for i in issues)


def test_a_sample_without_a_report_or_a_report_without_a_sample_is_flagged():
    examples, sample, report = _agreement()
    assert any("agreement.json" in i.message for i in _agreement_issues(examples, sample, None))
    assert any("muestra" in i.message for i in _agreement_issues(examples, None, report))


# --- US5: data sheet, guide and golden ids (FR-005, FR-010, FR-021, FR-023) ------------------------------------------


def _sheet(examples, report=None, drop=None):
    from intent_dataset.datasheet import REQUIRED_SECTIONS, compute_stats, render_block

    sections = "\n\n".join(f"## {name}\n\ntexto" for name in REQUIRED_SECTIONS if name != drop)
    return f"# Ficha\n\n{sections}\n\n{render_block(compute_stats(examples, report))}\n"


def _rule(examples, rule, **inputs):
    return [i for i in validate_dataset(examples, SMALL_RULES, **inputs) if i.rule == rule]


def test_a_current_datasheet_passes():
    examples = make_dataset()
    assert _rule(examples, "datasheet", datasheet=_sheet(examples)) == []


def test_a_datasheet_with_an_altered_figure_fails_and_says_how_to_fix_it():
    examples = make_dataset()
    (issue,) = _rule(examples, "datasheet", datasheet=_sheet(examples).replace("Total: 30", "Total: 31"))
    assert "stats --write" in issue.message


def test_a_datasheet_that_does_not_follow_the_dataset_fails():
    examples = make_dataset()
    stale = _sheet(examples)
    assert len(_rule(examples[:-1], "datasheet", datasheet=stale)) == 1


def test_a_datasheet_missing_a_required_section_fails_once_per_section():
    examples = make_dataset()
    issues = _rule(examples, "datasheet", datasheet=_sheet(examples, drop="Limitaciones"))
    assert len(issues) == 1 and "Limitaciones" in issues[0].message


def test_the_datasheet_figures_use_the_agreement_report_when_given():
    examples = make_dataset()
    report = {"n": 10, "kappa": 0.5, "disagreements": 4, "annotator_b": "person", "annotator_b_model": None}
    assert _rule(examples, "datasheet", datasheet=_sheet(examples, report), report=report, sample=[]) == []
    assert len(_rule(examples, "datasheet", datasheet=_sheet(examples, None), report=report, sample=[])) == 1


def test_the_datasheet_rule_is_skipped_when_no_sheet_is_given():
    assert _rule(make_dataset(), "datasheet") == []


def _guide(drop=None):
    from intent_dataset.datasheet import REQUIRED_GUIDE_SECTIONS

    return "# Guía\n\n" + "\n\n".join(f"## {name}\n\ntexto" for name in REQUIRED_GUIDE_SECTIONS if name != drop)


def test_a_complete_guide_passes_and_a_missing_class_or_borderline_section_fails():
    assert _rule(make_dataset(), "guide", guide=_guide()) == []
    assert "pedido" in _rule(make_dataset(), "guide", guide=_guide(drop="pedido"))[0].message
    assert "Casos límite" in _rule(make_dataset(), "guide", guide=_guide(drop="Casos límite"))[0].message
    assert _rule(make_dataset(), "guide") == []


def test_golden_ids_must_exist_in_the_golden_when_it_is_given():
    examples = modify(make_dataset(), "I0001", source="golden", group_id="G001", golden_id="G001")
    assert _rule(examples, "golden_ids", golden_ids=frozenset({"G001"})) == []
    (issue,) = _rule(examples, "golden_ids", golden_ids=frozenset({"G002"}))
    assert issue.example_id == "I0001" and "G001" in issue.message
    assert _rule(examples, "golden_ids") == []
