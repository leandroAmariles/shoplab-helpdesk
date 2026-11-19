"""The saved notebook is checked without running it: structure, saved outputs and coherence with the results file."""

import json
import re
from pathlib import Path

import nbformat
import pytest

from chunking_lab.config import repo_root
from chunking_lab.report import PARAM_KEYS, read_results, results_digest, validate_results

NOTEBOOK = repo_root() / "notebooks" / "chunking-strategies.ipynb"
RESULTS = repo_root() / "notebooks" / "results" / "chunking-results.json"


@pytest.fixture(scope="module")
def notebook():
    return nbformat.read(NOTEBOOK, as_version=4)


@pytest.fixture(scope="module")
def code_cells(notebook):
    return [cell for cell in notebook.cells if cell.cell_type == "code"]


def output_text(cell) -> str:
    parts = []
    for output in cell.outputs:
        if output.output_type == "stream":
            parts.append(output.text)
        elif "data" in output:
            parts.append(output.data.get("text/plain", ""))
    return "\n".join(parts)


def test_there_is_exactly_one_parameters_cell_and_it_is_the_first_code_cell(notebook, code_cells):
    tagged = [c for c in notebook.cells if "parameters" in c.metadata.get("tags", [])]
    assert len(tagged) == 1
    assert tagged[0] is code_cells[0]


def test_the_second_code_cell_is_the_preflight(code_cells):
    assert "preflight()" in code_cells[1].source


def test_every_code_cell_was_executed_and_none_failed(code_cells):
    assert all(cell.execution_count is not None for cell in code_cells)
    assert not [o for cell in code_cells for o in cell.outputs if o.output_type == "error"]


def test_the_last_cell_prints_the_digest_of_the_results_file(code_cells):
    assert f"sha256: {results_digest(RESULTS)}" in output_text(code_cells[-1])


def test_the_parameters_cell_matches_the_saved_results(code_cells):
    source = code_cells[0].source
    declared = {key: int(value) for key, value in re.findall(r"(\w+)=(\d+)", source)}
    params = read_results(RESULTS)["meta"]["params"]
    assert {key: declared[key] for key in PARAM_KEYS} == params


def test_the_saved_results_are_valid_and_cover_the_twenty_questions():
    results = read_results(RESULTS)
    validate_results(results)
    assert len(results["rows"]) == 20
    assert results["meta"]["corpus_documents"] == 24


def test_the_notebook_has_no_local_paths_or_user_names():
    text = NOTEBOOK.read_text(encoding="utf-8") + RESULTS.read_text(encoding="utf-8")
    assert not re.search(r"[A-Za-z]:(\\\\|\\|/)(Users|home)", text)
    assert not re.search(r"/(Users|home)/\w+", text)


def test_the_sections_the_spec_asks_for_are_there(notebook):
    headings = " ".join(c.source for c in notebook.cells if c.cell_type == "markdown")
    for expected in ("Las tres estrategias", "preguntas de prueba", "tabla de resultados", "Conclusiones y límites",
                     "Reproducibilidad"):
        assert expected.lower() in headings.lower(), expected


@pytest.mark.parametrize("folder", ["backend/src", "ingest/app"])
def test_production_code_does_not_import_anything_from_the_notebook(folder):
    pattern = re.compile(r"chunking_lab|from notebooks|import notebooks")
    offenders = [str(p) for p in (repo_root() / folder).rglob("*")
                 if p.is_file() and p.suffix in {".py", ".java", ".kt", ".yml", ".xml"}
                 and pattern.search(p.read_text(encoding="utf-8", errors="ignore"))]
    assert offenders == []


def test_the_notebook_is_valid_nbformat(notebook):
    nbformat.validate(notebook)
    assert isinstance(json.loads(Path(NOTEBOOK).read_text(encoding="utf-8")), dict)
