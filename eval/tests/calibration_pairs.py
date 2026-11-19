"""Hand-written pairs to check that a judge tells a good answer from a bad one (LAB-29, SC-002 and SC-003).

Only the standard library: the pairs are plain data. Questions and reference answers come from ``eval/golden.jsonl`` (so
they are never copied by hand); the context fragments are literal excerpts of ``data/kb`` (``test_calibration_pairs.py``
checks it). Nothing here calls a model: ``test_judge_calibration.py`` (marker ``judge``) is the one that does.

An *invented* answer is the faithful one plus a sentence no fragment supports (or one that contradicts a figure):
exactly what faithfulness has to catch.
"""

import json
from dataclasses import dataclass
from pathlib import Path

GOLDEN_PATH = Path(__file__).resolve().parents[1] / "golden.jsonl"


def golden_case(case_id: str) -> dict:
    """The record of one golden case, read straight from the dataset."""
    for line in GOLDEN_PATH.read_text(encoding="utf-8").splitlines():
        if line.strip():
            record = json.loads(line)
            if record["id"] == case_id:
                return record
    raise KeyError(case_id)


@dataclass(frozen=True)
class FaithfulnessPair:
    """Same question and context: the faithful answer says only what the context says; the invented one adds more."""

    case_id: str
    context: tuple[str, ...]
    invented_addition: str

    @property
    def question(self) -> str:
        return golden_case(self.case_id)["question"]

    @property
    def faithful_answer(self) -> str:
        return golden_case(self.case_id)["reference_answer"]

    @property
    def invented_answer(self) -> str:
        return f"{self.faithful_answer} {self.invented_addition}"


@dataclass(frozen=True)
class RelevancePair:
    """Same question: the relevant answer is the reference; the off-target one is true but answers another thing."""

    case_id: str
    off_target_answer: str

    @property
    def question(self) -> str:
        return golden_case(self.case_id)["question"]

    @property
    def relevant_answer(self) -> str:
        return golden_case(self.case_id)["reference_answer"]


@dataclass(frozen=True)
class PrecisionTriple:
    """The same three fragments in two orders: the useful one first, and after two irrelevant ones."""

    case_id: str
    useful: str
    irrelevant: tuple[str, str]

    @property
    def question(self) -> str:
        return golden_case(self.case_id)["question"]

    @property
    def reference(self) -> str:
        return golden_case(self.case_id)["reference_answer"]

    @property
    def useful_first(self) -> list[str]:
        return [self.useful, *self.irrelevant]

    @property
    def useful_last(self) -> list[str]:
        return [*self.irrelevant, self.useful]


FAITHFULNESS_PAIRS = (
    FaithfulnessPair(
        case_id="G029",
        context=(
            "Derecho de arrepentimiento: dentro de los 7 días corridos desde la entrega puedes devolver cualquier "
            "producto sin dar explicaciones. La devolución es gratis y recibes el reembolso total, incluido el flete "
            "que pagaste.",
        ),
        invented_addition="El reembolso se acredita en tu cuenta en 24 horas y además recibes un vale del 10 % para "
        "tu próxima compra.",
    ),
    FaithfulnessPair(
        case_id="G030",
        context=(
            "Cambio de opinión: hasta 30 días corridos desde la entrega. Del día 8 al día 30, el flete de devolución "
            "lo pagas tú (R$ 19,90).",
        ),
        invented_addition="Además, el primer cambio de opinión del año es gratis y el flete de devolución cuesta solo "
        "R$ 9,90.",
    ),
    FaithfulnessPair(
        case_id="G043",
        context=(
            "El plazo de entrega se cuenta en dos partes: el despacho y el transporte.",
            "Despacho tras la aprobación del pago hasta 2 días hábiles",
            "Envío estándar 5 a 10 días hábiles desde el despacho",
        ),
        invented_addition="Además, con el envío estándar recibes un código de seguimiento por SMS y la entrega queda "
        "garantizada en 3 días.",
    ),
    FaithfulnessPair(
        case_id="G058",
        context=(
            "Exclusiones: daños por mal uso, caídas, líquidos, reparaciones hechas por terceros y desgaste normal.",
        ),
        invented_addition="Aun así, ShopLab te hace un 50 % de descuento en una reparación de pago.",
    ),
    FaithfulnessPair(
        case_id="G016",
        context=(
            "Plazo de respuesta a una solicitud 15 días corridos",
            "Costo de ejercer un derecho sin costo",
        ),
        invented_addition="En casos urgentes el plazo se reduce a 24 horas y cada solicitud adicional cuesta R$ 10,00.",
    ),
)

RELEVANCE_PAIRS = (
    RelevancePair(
        case_id="G030",
        off_target_answer="No se pueden devolver los productos personalizados, los de higiene personal abiertos ni el "
        "contenido digital descargado.",
    ),
    RelevancePair(
        case_id="G043",
        off_target_answer="Los pedidos con productos de varios vendedores pueden llegar en envíos separados, cada uno "
        "con su propio plazo.",
    ),
)

PRECISION_TRIPLES = (
    PrecisionTriple(
        case_id="G030",
        useful="Cambio de opinión: hasta 30 días corridos desde la entrega. Del día 8 al día 30, el flete de "
        "devolución lo pagas tú (R$ 19,90).",
        irrelevant=(
            "Si pagas con boleto, el plazo de despacho empieza cuando el pago se aprueba, no cuando haces el pedido.",
            "La tarjeta de débito, el boleto y el vale no se pagan en cuotas.",
        ),
    ),
    PrecisionTriple(
        case_id="G057",
        useful="Duración de la garantía legal 90 días corridos desde la entrega",
        irrelevant=(
            "Los días hábiles son de lunes a viernes, sin festivos.",
            "La tarjeta de débito, el boleto y el vale no se pagan en cuotas.",
        ),
    ),
    PrecisionTriple(
        case_id="G016",
        useful="Plazo de respuesta a una solicitud 15 días corridos",
        irrelevant=(
            "Envío exprés 2 a 4 días hábiles desde el despacho",
            "Pasados los 30 días corridos, un defecto se tramita por la garantía",
        ),
    ),
)
