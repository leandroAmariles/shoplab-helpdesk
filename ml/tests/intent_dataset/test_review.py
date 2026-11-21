"""Review CSV: export, and import of the human decisions into new examples."""

from dataclasses import replace

import pytest
from intent_samples import SMALL_RULES, make_example

from intent_dataset.constants import CANDIDATE_COLUMNS
from intent_dataset.errors import ReviewError
from intent_dataset.review import Candidate, build_examples, read_review_csv, write_candidates_csv


def _row(**changes) -> Candidate:
    values = {
        "candidate_id": "C0001",
        "source": "manual_seed",
        "group_id": "S001",
        "parent_id": "",
        "golden_id": "",
        "language": "es",
        "text": "¿dónde está mi pedido?",
        "proposed_label": "",
        "hint": "",
        "decision": "accept",
        "label": "pedido",
        "text_edit": "",
        "pii_checked": "",
        "note": "",
    }
    values.update(changes)
    return Candidate(**values)


def _olist(**changes) -> Candidate:
    return _row(candidate_id="CR1", source="olist_review", group_id="R1", language="pt", text="meu pedido atrasou",
                pii_checked="si", **changes)


def test_the_exported_csv_has_exactly_the_contract_columns(tmp_path):
    path = tmp_path / "candidates.csv"
    write_candidates_csv([_row()], path)
    assert path.read_text(encoding="utf-8").splitlines()[0] == ",".join(CANDIDATE_COLUMNS)


def test_round_trip_keeps_accents_commas_and_quotes(tmp_path):
    path = tmp_path / "candidates.csv"
    original = _row(text='Dijo "hola", ¿qué tal? ñandú', note="línea, con coma")
    write_candidates_csv([original], path)
    assert read_review_csv(path) == [original]


def test_an_existing_csv_is_not_overwritten_without_force(tmp_path):
    path = tmp_path / "candidates.csv"
    write_candidates_csv([_row()], path)
    with pytest.raises(ReviewError, match="--force"):
        write_candidates_csv([_row(text="otro")], path)
    write_candidates_csv([_row(text="otro")], path, force=True)
    assert read_review_csv(path)[0].text == "otro"


def test_a_csv_saved_by_excel_with_a_bom_is_read(tmp_path):
    path = tmp_path / "candidates.csv"
    write_candidates_csv([_row()], path)
    path.write_bytes(b"\xef\xbb\xbf" + path.read_bytes())
    assert read_review_csv(path)[0].candidate_id == "C0001"


def test_a_csv_with_missing_columns_is_rejected(tmp_path):
    path = tmp_path / "candidates.csv"
    path.write_text("candidate_id,text\nC1,hola\n", encoding="utf-8")
    with pytest.raises(ReviewError, match="columnas"):
        read_review_csv(path)


def test_an_accepted_row_becomes_a_reviewed_example_without_split():
    result = build_examples([_row()], [], SMALL_RULES)
    (example,) = result.examples
    assert (example.id, example.label, example.reviewed, example.split) == ("I0001", "pedido", True, None)
    assert (example.source, example.group_id, example.language) == ("manual_seed", "S001", "es")
    assert (example.parent_id, example.golden_id) == (None, None)


def test_ids_continue_after_the_existing_ones():
    existing = [make_example("I0007", text="otro mensaje")]
    rows = [_row(), _row(candidate_id="C2", group_id="S2", text="¿cuándo llega?")]
    result = build_examples(rows, existing, SMALL_RULES)
    assert [e.id for e in result.examples] == ["I0008", "I0009"]


def test_rejected_and_pending_rows_do_not_enter_and_are_counted():
    rows = [_row(), _row(candidate_id="C2", text="a", decision="reject"), _row(candidate_id="C3", text="b", decision="")]
    result = build_examples(rows, [], SMALL_RULES)
    assert len(result.examples) == 1
    assert (result.summary.accepted, result.summary.rejected, result.summary.pending) == (1, 1, 1)


def test_text_edit_replaces_the_text_and_is_masked_again():
    result = build_examples([_row(text_edit="escríbeme a ana@correo.com")], [], SMALL_RULES)
    assert result.examples[0].text == "escríbeme a [EMAIL]"


def test_every_bad_row_is_listed_and_nothing_is_built():
    rows = [
        _row(candidate_id="C1", label="otra"),
        _row(candidate_id="C2", text="b", label=""),
        _row(candidate_id="C3", text="c", source="olist_review", pii_checked=""),
    ]
    with pytest.raises(ReviewError) as error:
        build_examples(rows, [], SMALL_RULES)
    message = str(error.value)
    assert "C1" in message and "C2" in message and "C3" in message
    assert "pii_checked" in message


def test_an_olist_row_needs_pii_checked_to_be_accepted():
    assert build_examples([_olist(label="pedido")], [], SMALL_RULES).examples[0].source == "olist_review"
    with pytest.raises(ReviewError, match="pii_checked"):
        build_examples([replace(_olist(label="pedido"), pii_checked="no")], [], SMALL_RULES)


def test_a_text_already_in_the_dataset_is_skipped_and_counted():
    existing = [make_example("I0001", text="¿Dónde está mi pedido?")]
    result = build_examples([_row(text="donde esta mi pedido")], existing, SMALL_RULES)
    assert result.examples == []
    assert result.summary.duplicated == 1


def test_a_duplicate_inside_the_same_file_is_skipped_once():
    result = build_examples([_row(), _row(candidate_id="C2", text="¿Dónde está mi pedido?")], [], SMALL_RULES)
    assert len(result.examples) == 1
    assert result.summary.duplicated == 1


def test_a_paraphrase_keeps_the_label_of_its_parent():
    parent = make_example("I0001", label="pedido", text="mi pedido no llega", group_id="S001", golden_id=None)
    ok = _row(candidate_id="P1", source="paraphrase", parent_id="I0001", group_id="S001", text="no me llega el pedido")
    assert build_examples([ok], [parent], SMALL_RULES).examples[0].parent_id == "I0001"
    bad = replace(ok, label="politica")
    with pytest.raises(ReviewError, match="padre"):
        build_examples([bad], [parent], SMALL_RULES)


def test_a_paraphrase_inherits_group_and_golden_id_from_its_parent():
    parent = make_example("I0001", label="politica", text="¿puedo devolver?", group_id="G012", golden_id="G012")
    row = _row(candidate_id="P1", source="paraphrase", parent_id="I0001", group_id="G012", golden_id="G012",
               label="politica", text="¿se puede devolver un producto?")
    example = build_examples([row], [parent], SMALL_RULES).examples[0]
    assert (example.group_id, example.golden_id, example.parent_id) == ("G012", "G012", "I0001")


def test_a_paraphrase_of_an_unknown_parent_is_rejected():
    row = _row(source="paraphrase", parent_id="I0099")
    with pytest.raises(ReviewError, match="I0099"):
        build_examples([row], [], SMALL_RULES)


def test_the_summary_counts_by_source_and_label():
    rows = [_row(), _olist(label="producto")]
    summary = build_examples(rows, [], SMALL_RULES).summary
    assert summary.by_source == {"manual_seed": 1, "olist_review": 1}
    assert summary.by_label == {"pedido": 1, "producto": 1}


def test_text_outside_the_allowed_length_is_rejected():
    with pytest.raises(ReviewError, match="longitud"):
        build_examples([_row(text="x" * 600)], [], SMALL_RULES)
