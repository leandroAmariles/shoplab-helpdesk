import pytest

from kb_tools.validate import validate
from tests.conftest import make_corpus


def pii_issues(tmp_path, text: str):
    kb_dir = make_corpus(tmp_path)
    path = kb_dir / "shipping-01-doc-1.md"
    path.write_text(path.read_text(encoding="utf-8") + f"\n{text}\n", encoding="utf-8")
    return [issue for issue in validate(kb_dir) if issue.code == "KB009"]


def test_corpus_with_only_shop_addresses_has_no_pii(tmp_path):
    kb_dir = make_corpus(tmp_path)
    assert [issue for issue in validate(kb_dir) if issue.code == "KB009"] == []


@pytest.mark.parametrize(
    "text",
    [
        "Escribe a ana@gmail.com",
        "Escribe a soporte@otra-tienda.com",
        "Llama al +55 11 91234-5678",
        "Llama al (11) 3456-7890",
        "Llama al 11912345678",
        "CPF 123.456.789-09",
    ],
)
def test_personal_data_is_kb009(tmp_path, text):
    issues = pii_issues(tmp_path, text)
    assert issues, text
    assert "línea" in issues[0].message


@pytest.mark.parametrize(
    "text",
    [
        "Escribe a soporte@shoplab.example o a privacidad@shoplab.example",
        "El importe es R$ 1.234.567,00 en total",
        "Pedido PED-0001 con 12 cuotas de R$ 30,00",
        "Atención de 9:00 a 18:00 en 2 días hábiles",
    ],
)
def test_shop_contacts_amounts_and_ids_are_not_pii(tmp_path, text):
    assert pii_issues(tmp_path, text) == []
