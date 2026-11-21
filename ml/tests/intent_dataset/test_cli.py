import json

import psycopg
import pytest
from intent_samples import make_example

from intent_dataset import __main__ as cli
from intent_dataset.dataset import dump_examples, load_examples
from intent_dataset.errors import ConfigurationError, InvalidDatasetError, ParaphraseError, ReviewError
from intent_dataset.review import Candidate, read_review_csv, write_candidates_csv


def _register(monkeypatch, raises=None, result=0):
    def run(args, deps):
        if raises:
            raise raises
        return result

    monkeypatch.setitem(cli.COMMANDS, "probe", cli.Command("probe", "prueba", lambda parser: None, run))


def test_help_exits_zero(capsys):
    assert cli.main(["--help"]) == 0
    assert "usage" in capsys.readouterr().out.lower()


def test_unknown_command_is_a_usage_error():
    assert cli.main(["no-existe"]) == 2


def test_no_command_is_a_usage_error():
    assert cli.main([]) == 2


def test_a_command_returns_its_code(monkeypatch):
    _register(monkeypatch, result=0)
    assert cli.main(["probe"]) == 0


@pytest.mark.parametrize(
    ("error", "code"),
    [
        (InvalidDatasetError("el dataset falla"), 1),
        (ReviewError("la revisión falla"), 1),
        (ConfigurationError("falta SHOPLAB_ML_LLM_API_KEY"), 2),
        (ParaphraseError("el gateway no responde"), 2),
    ],
)
def test_typed_errors_become_their_exit_code_and_message(monkeypatch, capsys, error, code):
    _register(monkeypatch, raises=error)
    assert cli.main(["probe"]) == code
    assert str(error) in capsys.readouterr().err


# --- US1 commands: candidates, paraphrase, build ---------------------------------------------------------------------

class _Rows:
    def __init__(self, rows):
        self._rows = rows

    def fetchall(self):
        return self._rows


class _Connection:
    def __init__(self, rows):
        self._rows = rows

    def execute(self, sql, params=None):
        return _Rows(self._rows)

    def close(self):
        pass


def _candidate(**changes):
    values = {
        "candidate_id": "C1", "source": "manual_seed", "group_id": "S001", "parent_id": "", "golden_id": "",
        "language": "es", "text": "¿dónde está mi pedido?", "proposed_label": "", "hint": "", "decision": "accept",
        "label": "pedido", "text_edit": "", "pii_checked": "", "note": "",
    }  # fmt: skip
    values.update(changes)
    return Candidate(**values)


def test_candidates_without_reviews_writes_the_golden_and_refuses_to_overwrite(tmp_path, capsys):
    out = tmp_path / "candidates.csv"
    assert cli.main(["candidates", "--skip-reviews", "--out", str(out)]) == 0
    assert len(read_review_csv(out)) == 120
    assert "golden: 120" in capsys.readouterr().out
    assert cli.main(["candidates", "--skip-reviews", "--out", str(out)]) == 1
    assert "--force" in capsys.readouterr().err
    assert cli.main(["candidates", "--skip-reviews", "--out", str(out), "--force"]) == 0


def test_candidates_reads_masked_reviews_through_the_injected_connection(tmp_path):
    out = tmp_path / "candidates.csv"
    rows = [("a" * 32, "meu pedido não chegou, escreva para ana@correo.com")]
    deps = cli.Dependencies(connector=lambda settings: _Connection(rows))
    assert cli.main(["candidates", "--skip-golden", "--reviews", "5", "--out", str(out)], deps=deps) == 0
    (row,) = read_review_csv(out)
    assert row.source == "olist_review" and row.text == "meu pedido não chegou, escreva para [EMAIL]"


def test_candidates_without_a_database_says_how_to_start_it(tmp_path, capsys):
    def down(settings):
        raise psycopg.OperationalError("connection refused")

    deps = cli.Dependencies(connector=down)
    assert cli.main(["candidates", "--out", str(tmp_path / "c.csv")], deps=deps) == 2
    assert "docker compose" in capsys.readouterr().err


def test_paraphrase_without_the_key_names_the_variable(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("SHOPLAB_ML_LLM_API_KEY", raising=False)
    dataset = tmp_path / "intents.jsonl"
    dump_examples([make_example()], dataset)
    assert cli.main(["paraphrase", "--dataset", str(dataset), "--out", str(tmp_path / "p.csv")]) == 2
    assert "SHOPLAB_ML_LLM_API_KEY" in capsys.readouterr().err


def test_paraphrase_writes_pending_drafts_and_reports_the_discards(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("SHOPLAB_ML_LLM_API_KEY", "sk-test")
    dataset = tmp_path / "intents.jsonl"
    dump_examples([make_example(text="quiero cancelar mi compra", label="pedido")], dataset)

    def post(url, headers, payload):
        content = json.dumps(["Necesito anular mi compra", "quiero cancelar mi compra"], ensure_ascii=False)
        return {"choices": [{"message": {"content": content}}]}

    out = tmp_path / "p.csv"
    deps = cli.Dependencies(post=post)
    assert cli.main(["paraphrase", "--dataset", str(dataset), "--out", str(out), "--per-parent", "2"], deps=deps) == 0
    (row,) = read_review_csv(out)
    assert (row.source, row.decision, row.parent_id) == ("paraphrase", "", "I0001")
    output = capsys.readouterr().out
    assert "igual_al_original" in output
    assert "sk-test" not in output


def test_paraphrase_does_not_start_from_a_paraphrase(tmp_path, monkeypatch):
    monkeypatch.setenv("SHOPLAB_ML_LLM_API_KEY", "sk-test")
    dataset = tmp_path / "intents.jsonl"
    dump_examples([make_example(source="paraphrase", parent_id="I0001")], dataset)
    calls = []

    def post(url, headers, payload):
        calls.append(payload)
        return {"choices": [{"message": {"content": "[]"}}]}

    deps = cli.Dependencies(post=post)
    assert cli.main(["paraphrase", "--dataset", str(dataset), "--out", str(tmp_path / "p.csv")], deps=deps) == 0
    assert calls == []


def test_build_dry_run_does_not_write_and_build_writes_reviewed_examples_without_split(tmp_path, capsys):
    review = tmp_path / "review.csv"
    write_candidates_csv([_candidate()], review)
    dataset = tmp_path / "intents.jsonl"
    assert cli.main(["build", str(review), "--dataset", str(dataset), "--dry-run"]) == 0
    assert not dataset.exists()
    assert "aceptadas: 1" in capsys.readouterr().out
    assert cli.main(["build", str(review), "--dataset", str(dataset)]) == 0
    (example,) = load_examples(dataset)
    assert (example.id, example.reviewed, example.split) == ("I0001", True, None)


def test_build_with_an_invalid_row_exits_one_and_writes_nothing(tmp_path, capsys):
    review = tmp_path / "review.csv"
    write_candidates_csv([_candidate(), _candidate(candidate_id="C2", text="otro", label="inventada")], review)
    dataset = tmp_path / "intents.jsonl"
    assert cli.main(["build", str(review), "--dataset", str(dataset)]) == 1
    assert "C2" in capsys.readouterr().err
    assert not dataset.exists()


def test_build_chains_several_files_and_continues_the_ids(tmp_path):
    first, second = tmp_path / "a.csv", tmp_path / "b.csv"
    write_candidates_csv([_candidate()], first)
    write_candidates_csv([_candidate(candidate_id="C2", group_id="S002", text="¿cuándo llega mi compra?")], second)
    dataset = tmp_path / "intents.jsonl"
    assert cli.main(["build", str(first), str(second), "--dataset", str(dataset)]) == 0
    assert [e.id for e in load_examples(dataset)] == ["I0001", "I0002"]


def test_build_with_a_missing_csv_is_a_review_error(tmp_path, capsys):
    assert cli.main(["build", str(tmp_path / "nope.csv"), "--dataset", str(tmp_path / "d.jsonl")]) == 1
    assert "nope.csv" in capsys.readouterr().err


# --- US3 command: split ----------------------------------------------------------------------------------------------


def _pool_file(tmp_path, groups=8):
    from dataclasses import replace

    from intent_dataset.constants import LABELS, LANGUAGES

    examples, number = [], 0
    for label in LABELS:
        for language in LANGUAGES:
            for group in range(groups):
                number += 1
                examples.append(
                    replace(
                        make_example(f"I{number:04d}", text=f"{label} {language} caso {group}", label=label,
                                     language=language, group_id=f"S{number:03d}"),
                        split=None,
                    )
                )  # fmt: skip
    path = tmp_path / "intents.jsonl"
    dump_examples(examples, path)
    return path


def test_split_assigns_the_missing_splits_and_a_second_run_changes_nothing(tmp_path, capsys):
    path = _pool_file(tmp_path)
    assert cli.main(["split", "--dataset", str(path)]) == 0
    first = path.read_bytes()
    assert all(e.split in ("train", "val", "test") for e in load_examples(path))
    assert "train" in capsys.readouterr().out
    assert cli.main(["split", "--dataset", str(path)]) == 0
    assert path.read_bytes() == first


def test_split_respects_the_seed_and_the_ratios(tmp_path):
    for name in ("a", "b"):
        (tmp_path / name).mkdir()
    first, second = _pool_file(tmp_path / "a"), _pool_file(tmp_path / "b")
    assert cli.main(["split", "--dataset", str(first), "--seed", "5"]) == 0
    assert cli.main(["split", "--dataset", str(second), "--seed", "6", "--ratios", "60/20/20"]) == 0
    assert [e.split for e in load_examples(first)] != [e.split for e in load_examples(second)]


def test_split_with_bad_ratios_is_a_usage_error(tmp_path, capsys):
    path = _pool_file(tmp_path)
    assert cli.main(["split", "--dataset", str(path), "--ratios", "70/20"]) == 2
    assert cli.main(["split", "--dataset", str(path), "--ratios", "50/20/20"]) == 2
    assert "100" in capsys.readouterr().err


def test_split_that_cannot_cover_every_class_exits_one_and_does_not_write(tmp_path, capsys):
    path = tmp_path / "intents.jsonl"
    dump_examples([make_example(f"I{n:04d}", text=f"texto {n}", group_id="S001", split=None) for n in range(1, 5)], path)
    before = path.read_bytes()
    assert cli.main(["split", "--dataset", str(path)]) == 1
    assert path.read_bytes() == before
    assert "se quedaría sin" in capsys.readouterr().err


def test_split_without_a_dataset_is_a_usage_error(tmp_path):
    assert cli.main(["split", "--dataset", str(tmp_path / "nope.jsonl")]) == 2


# --- US2 commands: sample, annotate, agreement -----------------------------------------------------------------------


def _paths(tmp_path):
    return {
        "dataset": _pool_file(tmp_path),
        "sample": tmp_path / "agreement-sample.jsonl",
        "sheet": tmp_path / "annotator-b.csv",
        "report": tmp_path / "agreement.json",
    }


def _sample_args(paths, *extra):
    return ["sample", "--dataset", str(paths["dataset"]), "--size", "10", "--sample-path", str(paths["sample"]),
            "--sheet", str(paths["sheet"]), *extra]  # fmt: skip


def _fill_sheet(paths, change=None):
    import csv

    from intent_dataset.agreement import read_sample

    labels = {row["id"]: row["label_a"] for row in read_sample(paths["sample"])}
    labels.update(change or {})
    with paths["sheet"].open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    with paths["sheet"].open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=["id", "text", "label"])
        writer.writeheader()
        for row in rows:
            writer.writerow({**row, "label": labels[row["id"]]})


def _agreement_args(paths, *extra):
    return ["agreement", "--sample-path", str(paths["sample"]), "--annotator-b", str(paths["sheet"]),
            "--report", str(paths["report"]), *extra]  # fmt: skip


def test_sample_freezes_the_sample_and_writes_the_blind_sheet_and_refuses_to_repeat(tmp_path, capsys):
    paths = _paths(tmp_path)
    assert cli.main(_sample_args(paths)) == 0
    assert len(paths["sample"].read_text(encoding="utf-8").splitlines()) == 10
    assert paths["sheet"].read_text(encoding="utf-8").splitlines()[0] == "id,text,label"
    capsys.readouterr()
    assert cli.main(_sample_args(paths)) == 1
    assert "--force" in capsys.readouterr().err
    assert cli.main(_sample_args(paths, "--force")) == 0


def test_annotate_without_the_key_is_a_usage_error(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("SHOPLAB_ML_LLM_API_KEY", raising=False)
    paths = _paths(tmp_path)
    cli.main(_sample_args(paths))
    guide = tmp_path / "guide.md"
    guide.write_text("guía", encoding="utf-8")
    assert cli.main(["annotate", "--sheet", str(paths["sheet"]), "--guide", str(guide)]) == 2
    assert "SHOPLAB_ML_LLM_API_KEY" in capsys.readouterr().err


def test_annotate_fills_the_sheet_with_the_model_labels_and_reports_the_invalid_ones(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("SHOPLAB_ML_LLM_API_KEY", "sk-test")
    paths = _paths(tmp_path)
    cli.main(_sample_args(paths))
    guide = tmp_path / "guide.md"
    guide.write_text("guía de prueba", encoding="utf-8")
    answers = iter(["pedido"] * 9 + ["no sé"])

    def post(url, headers, payload):
        return {"choices": [{"message": {"content": next(answers)}}]}

    capsys.readouterr()
    arguments = ["annotate", "--sheet", str(paths["sheet"]), "--guide", str(guide)]
    code = cli.main(arguments, deps=cli.Dependencies(post=post))
    output = capsys.readouterr().out
    assert code == 0
    from intent_dataset.agreement import read_annotator_sheet

    filled = read_annotator_sheet(paths["sheet"])
    assert sum(1 for label in filled.values() if label == "pedido") == 9
    assert sum(1 for label in filled.values() if not label) == 1
    assert "local_model" in output and "sk-test" not in output


def test_agreement_reports_the_kappa_and_lists_each_disagreement(tmp_path, capsys):
    from intent_dataset.agreement import read_sample

    paths = _paths(tmp_path)
    cli.main(_sample_args(paths))
    first = read_sample(paths["sample"])[0]
    other = "escalado" if first["label_a"] != "escalado" else "charla"
    _fill_sheet(paths, {first["id"]: other})
    capsys.readouterr()
    assert cli.main(_agreement_args(paths, "--kind", "person")) == 0
    output = capsys.readouterr().out
    assert "kappa" in output and first["id"] in output and other in output
    report = json.loads(paths["report"].read_text(encoding="utf-8"))
    assert (report["n"], report["disagreements"], report["annotator_b"]) == (10, 1, "person")
    resolved = read_sample(paths["sample"])
    assert resolved[0]["label_b"] == other and resolved[0]["final"] is None
    assert all(row["final"] == row["label_a"] for row in resolved[1:])


def test_agreement_refuses_to_recompute_once_resolved_unless_forced(tmp_path, capsys):
    paths = _paths(tmp_path)
    cli.main(_sample_args(paths))
    _fill_sheet(paths)
    assert cli.main(_agreement_args(paths, "--kind", "person")) == 0
    capsys.readouterr()
    assert cli.main(_agreement_args(paths, "--kind", "person")) == 1
    assert "--force" in capsys.readouterr().err
    assert cli.main(_agreement_args(paths, "--kind", "person", "--force")) == 0


def test_agreement_requires_the_kind_and_a_complete_sheet(tmp_path, capsys):
    paths = _paths(tmp_path)
    cli.main(_sample_args(paths))
    assert cli.main(_agreement_args(paths)) == 2  # --kind is required
    capsys.readouterr()
    assert cli.main(_agreement_args(paths, "--kind", "person")) == 1  # the sheet is still empty
    assert "etiqueta inválida" in capsys.readouterr().err


# --- US5 commands: stats and validate --------------------------------------------------------------------------------

SMALL_FLAGS = ["--min-examples", "30", "--min-per-label", "6", "--min-per-label-language", "3", "--sample-size", "10"]


def _published(tmp_path, *, with_docs=True):
    """A complete valid dataset, sample, report, guide and data sheet in ``tmp_path``, and the paths of each."""
    from intent_samples import make_dataset

    from intent_dataset.agreement import compute_agreement, write_report, write_resolved_sample
    from intent_dataset.datasheet import REQUIRED_GUIDE_SECTIONS, REQUIRED_SECTIONS, compute_stats, render_block

    examples = make_dataset()
    names = ("intents.jsonl", "sample.jsonl", "agreement.json", "guide.md", "sheet.md")
    paths = {name: tmp_path / name for name in names}
    dump_examples(examples, paths["intents.jsonl"])
    ids = [f"I{n:04d}" for label_index in range(5) for n in (label_index * 6 + 1, label_index * 6 + 4)]
    sample = [{"id": i, "label_a": next(e.label for e in examples if e.id == i), "label_b": None, "final": None,
               "reason": None} for i in ids]  # fmt: skip
    labels_b = {row["id"]: row["label_a"] for row in sample}
    resolved, report = compute_agreement(sample, labels_b, kind="person", today="2026-11-30")
    write_resolved_sample(paths["sample.jsonl"], resolved)
    write_report(paths["agreement.json"], report)
    paths["guide.md"].write_text("\n\n".join(f"## {n}\n\nx" for n in REQUIRED_GUIDE_SECTIONS), encoding="utf-8")
    sections = "\n\n".join(f"## {n}\n\nx" for n in REQUIRED_SECTIONS)
    paths["sheet.md"].write_text(f"{sections}\n\n{render_block(compute_stats(examples, report))}\n", encoding="utf-8")
    return paths


def _validate_args(paths, *extra):
    return ["validate", "--dataset", str(paths["intents.jsonl"]), "--sample-path", str(paths["sample.jsonl"]),
            "--report", str(paths["agreement.json"]), "--guide", str(paths["guide.md"]),
            "--datasheet", str(paths["sheet.md"]), *SMALL_FLAGS, *extra]  # fmt: skip


def test_validate_passes_on_a_complete_published_dataset(tmp_path, capsys):
    assert cli.main(_validate_args(_published(tmp_path))) == 0
    assert "válido" in capsys.readouterr().out


def test_validate_prints_every_issue_and_exits_one(tmp_path, capsys):
    paths = _published(tmp_path)
    from intent_samples import modify

    examples = modify(load_examples(paths["intents.jsonl"]), "I0001", text="escríbeme a ana@correo.com")
    examples = modify(examples, "I0002", label="inventada")
    dump_examples(examples, paths["intents.jsonl"])
    assert cli.main(_validate_args(paths)) == 1
    output = capsys.readouterr().out
    assert "[pii] I0001" in output and "[label] I0002" in output
    assert "ana@correo.com" not in output


def test_validate_without_docs_skips_the_guide_the_sheet_and_the_agreement(tmp_path):
    paths = _published(tmp_path)
    for name in ("guide.md", "sheet.md", "sample.jsonl", "agreement.json"):
        paths[name].unlink()
    assert cli.main(_validate_args(paths)) == 1
    assert cli.main(_validate_args(paths, "--no-docs")) == 0


def test_validate_reports_a_missing_guide_as_a_failure(tmp_path, capsys):
    paths = _published(tmp_path)
    paths["guide.md"].unlink()
    assert cli.main(_validate_args(paths)) == 1
    assert "guide.md" in capsys.readouterr().out


def test_validate_with_golden_checks_the_golden_ids(tmp_path, capsys):
    paths = _published(tmp_path)
    golden = tmp_path / "golden.jsonl"
    golden.write_text('{"id": "G001", "category": "payments"}\n', encoding="utf-8")
    assert cli.main(_validate_args(paths, "--golden", str(golden))) == 0
    from intent_samples import modify

    dump_examples(modify(load_examples(paths["intents.jsonl"]), "I0001", source="golden", group_id="G777",
                         golden_id="G777"), paths["intents.jsonl"])  # fmt: skip
    capsys.readouterr()
    assert cli.main(_validate_args(paths, "--golden", str(golden), "--no-docs")) == 1
    assert "[golden_ids] I0001" in capsys.readouterr().out


def test_validate_never_touches_the_network_or_the_database(tmp_path, monkeypatch):
    def boom(*args, **kwargs):
        raise AssertionError("validate must work offline")

    monkeypatch.setattr(psycopg, "connect", boom)
    monkeypatch.setattr("urllib.request.urlopen", boom)
    assert cli.main(_validate_args(_published(tmp_path))) == 0


def test_stats_prints_the_figures_and_write_updates_only_the_block(tmp_path, capsys):
    paths = _published(tmp_path)
    sheet = paths["sheet.md"]
    sheet.write_text(sheet.read_text(encoding="utf-8").replace("Total: 30", "Total: 99"), encoding="utf-8")
    assert cli.main(_validate_args(paths)) == 1
    stats_args = ["stats", "--dataset", str(paths["intents.jsonl"]), "--report", str(paths["agreement.json"]),
                  "--datasheet", str(sheet)]  # fmt: skip
    assert cli.main(stats_args) == 0
    assert "Total: 30" in capsys.readouterr().out and "Total: 99" in sheet.read_text(encoding="utf-8")
    assert cli.main([*stats_args, "--write"]) == 0
    assert "Total: 30" in sheet.read_text(encoding="utf-8")
    assert cli.main(_validate_args(paths)) == 0


def test_stats_write_without_markers_exits_one(tmp_path):
    paths = _published(tmp_path)
    paths["sheet.md"].write_text("# Ficha sin marcas\n", encoding="utf-8")
    args = ["stats", "--dataset", str(paths["intents.jsonl"]), "--report", str(paths["agreement.json"]),
            "--datasheet", str(paths["sheet.md"]), "--write"]  # fmt: skip
    assert cli.main(args) == 1
