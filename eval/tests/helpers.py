"""Helpers shared by the tests: a toy knowledge base and valid cases that tests then break on purpose."""

import json
from pathlib import Path
from typing import Any

# id -> (topic, body). Bodies are short on purpose; tests quote them to trigger the verbatim rule.
TOY_DOCS: dict[str, tuple[str, str]] = {
    "payments-01-a": ("payments", "# Pagos\nPuedes pagar con tarjeta de crédito en hasta 12 cuotas sin interés."),
    "payments-02-b": ("payments", "# Boleto\nSi el boleto vence sin pago, puedes volver a hacer el pedido."),
    "privacy-01-a": ("privacy", "# Privacidad\nGuardamos tus datos de contacto solo para gestionar tus pedidos."),
    "returns-01-a": ("returns", "# Devoluciones\nTienes 7 días corridos desde la entrega para devolver un producto."),
    "shipping-01-a": ("shipping", "# Envíos\nEl envío estándar llega en 5 días hábiles a todo el país."),
    "warranty-01-a": ("warranty", "# Garantía\nLa garantía legal cubre defectos de fabricación durante 90 días."),
}
TOY_DOC_BY_TOPIC = {
    "payments": "payments-01-a",
    "privacy": "privacy-01-a",
    "returns": "returns-01-a",
    "shipping": "shipping-01-a",
    "warranty": "warranty-01-a",
}
NO_ANSWER = "No tengo información disponible sobre ese tema en la tienda."


def write_toy_kb(kb_dir: Path) -> Path:
    kb_dir.mkdir(parents=True, exist_ok=True)
    for doc_id, (topic, body) in TOY_DOCS.items():
        front = f"---\ntitle: {doc_id}\ntopic: {topic}\ntype: policy\nlanguage: es\nscope: x\n---\n"
        (kb_dir / f"{doc_id}.md").write_text(front + body + "\n", encoding="utf-8")
    (kb_dir / "INDEX.md").write_text("| id | title |\n|----|-------|\n", encoding="utf-8")
    (kb_dir / "REVIEW.md").write_text("| id | reviewer |\n|----|----------|\n", encoding="utf-8")
    (kb_dir / "payments-01-a.pdf").write_bytes(b"%PDF-1.4")
    return kb_dir


def make_case(**overrides: Any) -> dict[str, Any]:
    case: dict[str, Any] = {
        "id": "G001",
        "question": "¿Hasta cuántas cuotas puedo dividir una compra con tarjeta?",
        "reference_answer": "Puedes dividir la compra con tarjeta de crédito en hasta doce cuotas sin interés.",
        "expected_documents": ["payments-01-a"],
        "category": "payments",
    }
    case.update(overrides)
    return case


def valid_default_set() -> list[dict[str, Any]]:
    """The smallest dataset that satisfies the default rules against the toy corpus: 80 cases.

    68 answered (12 or more per topic, 8 of them with two documents, every toy document expected at least once) and
    12 unanswered (6 of the topic, 6 off topic), which is exactly 15 %.
    """
    cases: list[dict[str, Any]] = []

    def add(**fields: Any) -> None:
        number = len(cases) + 1
        question = f"Pregunta de prueba número {number:03d} sobre un tema distinto"
        cases.append(make_case(id=f"G{number:03d}", question=question, **fields))

    for _ in range(8):
        add(expected_documents=["payments-01-a", "payments-02-b"], category="payments")
    for topic, doc_id in TOY_DOC_BY_TOPIC.items():
        for _ in range(12 if topic != "payments" else 4):
            add(expected_documents=[doc_id], category=topic)
    for _ in range(8):
        add(expected_documents=["returns-01-a"], category="returns")
    for topic in ("payments", "privacy", "returns", "shipping", "warranty", "payments"):
        add(expected_documents=[], category=topic, reference_answer=NO_ANSWER)
    for _ in range(6):
        add(expected_documents=[], category="off_topic", reference_answer=NO_ANSWER)
    return cases


def write_golden(path: Path, cases: list[dict[str, Any]], *, trailing_newline: bool = True) -> Path:
    text = "\n".join(json.dumps(case, ensure_ascii=False) for case in cases)
    path.write_text(text + ("\n" if trailing_newline else ""), encoding="utf-8")
    return path
