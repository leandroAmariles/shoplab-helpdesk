"""The generated figures of the data sheet: they are computed from the dataset, never typed."""

import pytest
from intent_samples import make_dataset, modify

from intent_dataset.constants import LABELS, SPLITS, STATS_BEGIN, STATS_END
from intent_dataset.datasheet import (
    REQUIRED_GUIDE_SECTIONS,
    REQUIRED_SECTIONS,
    check_block,
    compute_stats,
    missing_sections,
    render_block,
    write_block,
)
from intent_dataset.errors import InvalidDatasetError

REPORT = {"n": 50, "kappa": 0.82, "disagreements": 6, "annotator_b": "person", "annotator_b_model": None}


def test_stats_count_the_total_per_label_and_language_per_split_and_per_source():
    stats = compute_stats(make_dataset(), None)
    assert stats.total == 30
    assert stats.by_label_language[("pedido", "es")] == 3 and stats.by_label_language[("pedido", "pt")] == 3
    assert stats.by_split_label[("train", "charla")] == 2
    assert stats.by_source["manual_seed"] == 30 and stats.by_source["golden"] == 0


def test_golden_cases_are_counted_by_split_once_per_case_including_their_paraphrases():
    examples = make_dataset()
    examples = modify(examples, "I0001", source="golden", group_id="G001", golden_id="G001", split="train")
    examples = modify(examples, "I0002", source="paraphrase", parent_id="I0001", group_id="G001", golden_id="G001",
                      split="train")  # fmt: skip
    examples = modify(examples, "I0003", source="golden", group_id="G002", golden_id="G002", split="test")
    stats = compute_stats(examples, None)
    assert stats.golden_cases_by_split == {"train": 1, "val": 0, "test": 1}


def test_the_block_has_the_markers_every_class_split_source_and_the_agreement_line():
    block = render_block(compute_stats(make_dataset(), REPORT))
    assert block.startswith(STATS_BEGIN) and block.rstrip().endswith(STATS_END)
    for label in LABELS:
        assert f"| {label} |" in block
    for split in SPLITS:
        assert f"| {split} |" in block
    assert all(f"| {source} |" in block for source in ("golden", "olist_review", "manual_seed", "paraphrase"))
    assert "0.82" in block and "50" in block and "persona" in block
    assert "Total: 30" in block


def test_a_local_model_as_annotator_is_called_out_in_the_block():
    report = {**REPORT, "annotator_b": "local_model", "annotator_b_model": "judge"}
    block = render_block(compute_stats(make_dataset(), report))
    assert "modelo local" in block and "judge" in block and "no es un acuerdo entre personas" in block


def test_without_a_report_the_block_says_the_agreement_is_pending():
    assert "pendiente" in render_block(compute_stats(make_dataset(), None))


def test_an_undefined_kappa_is_shown_as_such():
    block = render_block(compute_stats(make_dataset(), {**REPORT, "kappa": None}))
    assert "no definido" in block


def _sheet(block: str) -> str:
    sections = "\n\n".join(f"## {name}\n\ntexto" for name in REQUIRED_SECTIONS)
    return f"# Ficha\n\n{sections}\n\n{block}\n\nfin\n"


def test_check_block_accepts_a_current_block_and_detects_an_altered_figure():
    block = render_block(compute_stats(make_dataset(), REPORT))
    assert check_block(_sheet(block), block) == []
    altered = _sheet(block.replace("Total: 30", "Total: 31"))
    problems = check_block(altered, block)
    assert len(problems) == 1 and "stats --write" in problems[0]


def test_check_block_reports_missing_markers():
    block = render_block(compute_stats(make_dataset(), REPORT))
    problems = check_block("# Ficha sin marcas", block)
    assert len(problems) == 1 and "marcas" in problems[0]


def test_write_block_replaces_only_what_is_between_the_markers():
    old = render_block(compute_stats(make_dataset(), None))
    new = render_block(compute_stats(make_dataset(), REPORT))
    text = _sheet(old)
    written = write_block(text, new)
    assert check_block(written, new) == []
    assert written.startswith("# Ficha") and written.rstrip().endswith("fin")
    assert write_block(written, new) == written


def test_write_block_without_markers_is_an_error():
    with pytest.raises(InvalidDatasetError, match="marcas"):
        write_block("# Ficha sin marcas", "x")


def test_missing_sections_reads_markdown_headings_ignoring_case_and_level():
    text = "# Origen\n\n### composición\n\n## Licencias y condiciones\n"
    assert missing_sections(text, ("Origen", "Composición", "Licencias", "Limitaciones")) == ["Limitaciones"]


def test_the_required_sections_cover_what_the_spec_demands():
    assert set(REQUIRED_SECTIONS) >= {"Origen", "Licencias", "Enmascarado", "Limitaciones", "Acuerdo entre anotadores"}
    assert set(REQUIRED_GUIDE_SECTIONS) == {*LABELS, "Casos límite"}
