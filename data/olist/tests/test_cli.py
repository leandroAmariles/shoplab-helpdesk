import pytest
from typer.testing import CliRunner

from olist_loader import cli
from olist_loader.cli import app
from olist_loader.transform import transform

from .test_atomicity import with_a_bad_payment
from .test_idempotence import counts

runner = CliRunner()


def use_database(monkeypatch, database) -> None:
    monkeypatch.setenv("POSTGRES_URL", database.postgres_url)
    monkeypatch.setenv("POSTGRES_USER", database.postgres_user)
    monkeypatch.setenv("POSTGRES_PASSWORD", database.postgres_password)


@pytest.fixture(autouse=True)
def no_kaggle_credentials(monkeypatch):
    monkeypatch.delenv("KAGGLE_USERNAME", raising=False)
    monkeypatch.delenv("KAGGLE_KEY", raising=False)


def test_a_complete_load_exits_with_zero_and_prints_the_summary(conn, settings, fixtures_dir, monkeypatch):
    use_database(monkeypatch, settings)

    result = runner.invoke(app, ["load", "--source", str(fixtures_dir)])

    assert result.exit_code == 0, result.output
    assert "Esquema orders" in result.output and "Tiempo total:" in result.output
    assert counts(conn)["orders.orders"] == 3


def test_a_dry_run_reports_without_writing(conn, settings, fixtures_dir, monkeypatch):
    use_database(monkeypatch, settings)

    result = runner.invoke(app, ["load", "--source", str(fixtures_dir), "--dry-run"])

    assert result.exit_code == 0, result.output
    assert "no se escribió" in result.output
    assert all(count == 0 for count in counts(conn).values())


def test_a_dry_run_does_not_need_a_database(fixtures_dir, monkeypatch):
    monkeypatch.setenv("POSTGRES_URL", "postgresql://127.0.0.1:1/none")

    result = runner.invoke(app, ["load", "--source", str(fixtures_dir), "--dry-run"])

    assert result.exit_code == 0, result.output


def test_an_unknown_option_exits_with_two():
    assert runner.invoke(app, ["load", "--bogus"]).exit_code == 2


def test_without_credentials_nor_source_the_exit_code_is_three(tmp_path):
    result = runner.invoke(app, ["load", "--data-dir", str(tmp_path)])

    assert result.exit_code == 3
    assert "KAGGLE_USERNAME" in result.output


def test_skip_download_without_the_files_exits_with_three(tmp_path):
    result = runner.invoke(app, ["load", "--data-dir", str(tmp_path), "--skip-download"])

    assert result.exit_code == 3
    assert "--skip-download" in result.output


def test_a_database_without_migrations_exits_with_four(new_database, fixtures_dir, monkeypatch):
    use_database(monkeypatch, new_database(migrate=False))

    result = runner.invoke(app, ["load", "--source", str(fixtures_dir)])

    assert result.exit_code == 4
    assert "arranca el backend" in result.output


def test_an_unreachable_database_exits_with_four(fixtures_dir, monkeypatch):
    monkeypatch.setenv("POSTGRES_URL", "postgresql://127.0.0.1:1/none")

    result = runner.invoke(app, ["load", "--source", str(fixtures_dir)])

    assert result.exit_code == 4
    assert "POSTGRES_URL" in result.output


def test_a_failure_while_loading_exits_with_five_and_rolls_back(conn, settings, fixtures_dir, monkeypatch):
    use_database(monkeypatch, settings)
    monkeypatch.setattr(cli, "transform", lambda folder: with_a_bad_payment(transform(folder)))

    result = runner.invoke(app, ["load", "--source", str(fixtures_dir)])

    assert result.exit_code == 5
    assert all(count == 0 for count in counts(conn).values())
