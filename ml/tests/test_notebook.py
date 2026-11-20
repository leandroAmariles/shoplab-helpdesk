"""The saved notebook and its summary: structure, hygiene of the outputs and coherence of the prose with the numbers."""

import html
import json
import re

import nbformat
import pytest

from olist_eda import report
from olist_eda.config import NOTEBOOK_DATA_DIR, NOTEBOOK_RESULTS_DIR, REPO_ROOT

NOTEBOOK_PATH = REPO_ROOT / "ml" / "notebooks" / "olist-eda.ipynb"
SUMMARY_PATH = NOTEBOOK_RESULTS_DIR / "olist-eda-summary.json"
MAX_NOTEBOOK_BYTES = 2_000_000
SECTIONS = [
    "Parámetros",
    "Preflight",
    "Carga de datos",
    "Calidad por tabla",
    "Integridad entre esquemas",
    "Tipos de pago",
    "Estados del pedido",
    "Contraste con políticas y con la consulta de pedidos",
    "Tiempos de entrega",
    "Retrasos por estado del cliente",
    "Retrasos por categoría",
    "Reseñas",
    "Discrepancias y acciones",
    "Conclusiones",
    "Límites",
]
HEADING = re.compile(r"^## (\d+)\. (.+)$")


@pytest.fixture(scope="module")
def notebook():
    return nbformat.read(NOTEBOOK_PATH, as_version=4)


@pytest.fixture(scope="module")
def summary():
    return report.read_summary(SUMMARY_PATH)


def code_cells(notebook):
    return [cell for cell in notebook.cells if cell.cell_type == "code"]


def section_cells(notebook, number):
    """The cells between the heading of a section and the next heading."""
    cells, inside = [], False
    for cell in notebook.cells:
        match = HEADING.match(cell.source.splitlines()[0]) if cell.cell_type == "markdown" and cell.source else None
        if match:
            inside = int(match.group(1)) == number
        if inside:
            cells.append(cell)
    return cells


def output_text(cell, preferred="text/plain"):
    parts = []
    for output in cell.get("outputs", []):
        if output.output_type == "stream":
            parts.append(output.text)
        elif preferred in output.get("data", {}):
            parts.append(output["data"][preferred])
    return "\n".join(parts)


def all_outputs(notebook):
    return "\n".join(output_text(cell) + "\n" + output_text(cell, "text/html") for cell in code_cells(notebook))


def test_the_fifteen_sections_exist_in_order(notebook):
    found = [
        (int(match.group(1)), match.group(2))
        for cell in notebook.cells
        if cell.cell_type == "markdown" and (match := HEADING.match(cell.source.splitlines()[0]))
    ]

    assert found == list(enumerate(SECTIONS))


def test_every_parameter_lives_in_the_first_code_cell(notebook):
    cells = code_cells(notebook)

    assert "AnalysisSettings.from_env()" in cells[0].source
    assert not any("from_env" in cell.source or "os.environ" in cell.source for cell in cells[1:])


def test_preflight_is_the_second_code_cell(notebook):
    assert "db.preflight(settings)" in code_cells(notebook)[1].source


def test_no_cell_walks_the_rows_one_by_one(notebook):
    pattern = re.compile(
        r"iterrows|itertuples|\.apply\([^)]*axis\s*=\s*1|range\(len\(|for\s+\w+\s+in\s+\w*(frame|df|deliveries)\b"
    )

    for cell in code_cells(notebook):
        assert not pattern.search(cell.source), cell.source[:80]


def test_no_cell_shows_raw_rows_of_a_table(notebook):
    for cell in code_cells(notebook):
        lines = [line.strip() for line in cell.source.splitlines() if line.strip() and not line.startswith("#")]
        assert not re.match(r'^tables\[[^\]]+\](\.head\(.*\))?$', lines[-1]) if lines else True, cell.source[:80]
        assert ".sample(" not in cell.source


def test_every_code_cell_was_executed_and_none_failed(notebook):
    for cell in code_cells(notebook):
        assert cell.execution_count is not None, cell.source[:60]
        assert not any(output.output_type == "error" for output in cell.get("outputs", [])), cell.source[:60]


def test_the_notebook_only_uses_the_analysis_package_and_the_scientific_stack(notebook):
    imports = {
        line.split()[1].split(".")[0]
        for cell in code_cells(notebook)
        for line in cell.source.splitlines()
        if line.startswith(("import ", "from "))
    }

    assert imports <= {"numpy", "pandas", "olist_eda"}


def test_outputs_contain_no_identifier_path_or_credential(notebook):
    text = all_outputs(notebook) + "\n".join(cell.source for cell in notebook.cells)

    assert not re.search(r"[0-9a-f]{32}", text)
    for forbidden in ("C:\\Users", "C:/Users", "AppData", "/home/", "change-me-local-only", "127.0.0.1", "localhost:"):
        assert forbidden not in text, forbidden


def test_the_notebook_is_small_enough_to_live_in_the_repository():
    assert NOTEBOOK_PATH.stat().st_size <= MAX_NOTEBOOK_BYTES


def test_there_is_no_csv_of_the_dataset_under_ml():
    ignored = {".venv", ".cache", ".pytest_cache", "__pycache__"}
    csvs = [path for path in (REPO_ROOT / "ml").rglob("*.csv") if not ignored & set(path.parts)]

    assert csvs == []


def test_the_saved_summary_is_valid_and_comes_from_the_full_dataset(summary):
    report.validate_summary(summary)

    assert summary["data_scale"] == "full"
    assert len(summary["tables"]) == 8


def test_the_sizes_shown_in_the_notebook_match_the_summary(notebook, summary):
    text = output_text(section_cells(notebook, 2)[-1])

    for table in summary["tables"]:
        # the exact row of the sizes table: «<table>  <rows>», not a number that happens to appear elsewhere
        assert re.search(rf"{re.escape(table['table'])}\s+{re.escape(f'{table['rows']:,}')}\s", text), table["table"]


def test_the_discrepancy_table_matches_the_summary_and_has_no_action_missing(notebook, summary):
    cell = section_cells(notebook, 12)[2]  # the cell that builds and shows the table
    shown = html.unescape(output_text(cell, "text/html"))
    headline = output_text(cell)

    assert f"{len(summary['findings'])} hallazgos" in headline
    for finding in summary["findings"]:
        assert finding["action"]
        assert finding["subject"] in shown
        assert finding["action"] in shown


def test_the_conclusions_quote_the_numbers_of_the_summary(notebook, summary):
    prose = "\n".join(cell.source for cell in section_cells(notebook, 13) if cell.cell_type == "markdown")
    rows = {table["table"]: table["rows"] for table in summary["tables"]}
    payments = {item["value"]: item for item in summary["values"]["payment_type"]}
    statuses = {item["value"]: item for item in summary["values"]["order_status"]}
    delivery = summary["delivery"]
    expected = [f"{count:,}" for count in rows.values()]
    expected += [
        f"{delivery['analyzed_orders']:,}",
        f"{delivery['early_pct']:.2f}",
        f"{delivery['late_pct']:.2f}",
        f"{delivery['actual_days']['mean']:.2f}",
        f"{delivery['estimated_days']['mean']:.2f}",
        f"{payments['credit_card']['share'] * 100:.2f}",
        f"{statuses['delivered']['share'] * 100:.2f}",
        f"**{len(summary['findings'])} hallazgos**",
    ]
    by_bucket = summary["reviews"]["mean_score_by_delay_bucket"]
    expected += [f"{score:.2f}" for score in by_bucket.values()]

    missing = [number for number in expected if number not in prose]
    assert not missing, f"las conclusiones no citan: {missing}"


def test_the_conclusions_do_not_leave_an_area_without_a_verdict(notebook):
    prose = "\n".join(cell.source for cell in section_cells(notebook, 13) if cell.cell_type == "markdown")

    for heading in ("Calidad de los datos", "Valores de pago y estado frente al corpus", "Entregas, retrasos y reseñas"):
        assert heading in prose
    assert "sin discrepancias" in prose or "no discrepan" in prose


def test_the_limits_declare_what_this_story_does_not_do(notebook):
    limits = "\n".join(cell.source for cell in section_cells(notebook, 14))

    for expected in ("descriptivo", "2016-2018", "sintétic", "no están aplicadas", "LAB-41"):
        assert expected in limits, expected


def test_the_data_files_the_notebook_reads_exist():
    assert (NOTEBOOK_DATA_DIR / "order-tool-states.toml").is_file()
    assert (NOTEBOOK_DATA_DIR / "discrepancy-actions.toml").is_file()
    assert json.loads(SUMMARY_PATH.read_text(encoding="utf-8"))["schema_version"] == 1
