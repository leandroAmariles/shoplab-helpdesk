import pytest

from olist_eda.config import REPO_ROOT, AnalysisSettings


def test_defaults_match_the_local_compose_values():
    settings = AnalysisSettings.from_env({})

    assert settings.postgres_url == "postgresql://localhost:5432/shoplab"
    assert settings.postgres_user == "shoplab"
    assert settings.postgres_password == "change-me-local-only"
    assert settings.min_group_orders == 30
    assert settings.top_n == 10
    assert settings.kb_dir == REPO_ROOT / "data" / "kb"


def test_reads_every_variable_from_the_environment(tmp_path):
    settings = AnalysisSettings.from_env(
        {
            "POSTGRES_URL": "postgresql://db.example:5433/other",
            "POSTGRES_USER": "reader",
            "POSTGRES_PASSWORD": "secret",
            "EDA_MIN_GROUP_ORDERS": "5",
            "KB_DIR": str(tmp_path),
        }
    )

    assert settings.postgres_url == "postgresql://db.example:5433/other"
    assert settings.postgres_user == "reader"
    assert settings.postgres_password == "secret"
    assert settings.min_group_orders == 5
    assert settings.kb_dir == tmp_path


def test_jdbc_prefix_of_the_backend_is_removed():
    settings = AnalysisSettings.from_env({"POSTGRES_URL": "jdbc:postgresql://localhost:5432/shoplab"})

    assert settings.postgres_url == "postgresql://localhost:5432/shoplab"


def test_relative_kb_dir_is_resolved_from_the_repository_root():
    settings = AnalysisSettings.from_env({"KB_DIR": "data/kb"})

    assert settings.kb_dir == REPO_ROOT / "data" / "kb"


def test_password_never_appears_in_repr():
    settings = AnalysisSettings.from_env({"POSTGRES_PASSWORD": "top-secret-value"})

    assert "top-secret-value" not in repr(settings)


def test_empty_variables_count_as_not_defined():
    settings = AnalysisSettings.from_env({"EDA_MIN_GROUP_ORDERS": "", "POSTGRES_URL": "", "KB_DIR": ""})

    assert settings.min_group_orders == 30
    assert settings.postgres_url == "postgresql://localhost:5432/shoplab"
    assert settings.kb_dir == REPO_ROOT / "data" / "kb"


@pytest.mark.parametrize("value", ["0", "-3", "abc", "1.5"])
def test_invalid_min_group_orders_names_the_variable(value):
    with pytest.raises(ValueError, match="EDA_MIN_GROUP_ORDERS"):
        AnalysisSettings.from_env({"EDA_MIN_GROUP_ORDERS": value})


def test_direct_construction_validates_too():
    with pytest.raises(ValueError, match="EDA_MIN_GROUP_ORDERS"):
        AnalysisSettings(min_group_orders=0)


def test_repository_root_is_the_real_repository():
    assert (REPO_ROOT / "data" / "kb").is_dir()
    assert (REPO_ROOT / "ml" / "pyproject.toml").is_file()


def test_describe_hides_the_password_the_host_and_absolute_paths():
    settings = AnalysisSettings.from_env({"POSTGRES_PASSWORD": "top-secret-value", "POSTGRES_URL": "postgresql://10.1.2.3:55432/lab"})

    description = settings.describe()

    assert description == {
        "base de datos": "lab",
        "usuario": "shoplab",
        "min_group_orders": 30,
        "kb_dir": "data/kb",
        "top_n": 10,
    }
    assert "top-secret-value" not in str(description)
    assert "10.1.2.3" not in str(description)


def test_describe_keeps_only_the_name_of_a_kb_dir_outside_the_repository(tmp_path):
    description = AnalysisSettings.from_env({"KB_DIR": str(tmp_path)}).describe()

    assert description["kb_dir"] == tmp_path.name
