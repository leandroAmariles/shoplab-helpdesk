"""Shared test fixtures for the intent classifier package (LAB-118)."""

from pathlib import Path

import pytest

from intent.constants import CANONICAL_LABELS
from intent_dataset.dataset import Example, dump_examples

_TEMPLATES: dict[str, tuple[tuple[str, str], ...]] = {
    "politica": (
        ("es", "¿Cuál es el plazo de devolución y cómo funciona el reembolso de la tienda?"),
        ("pt", "Qual é a política de devolução e reembolso para compras com boleto?"),
        ("es", "¿Cuáles son las formas de pago aceptadas y cuántas cuotas sin interés hay?"),
        ("pt", "Como funciona a garantia legal e a política de privacidade dos meus dados?"),
    ),
    "producto": (
        ("es", "¿Este auricular inalámbrico tiene micrófono y stock disponible en color negro?"),
        ("pt", "O produto veio com a qualidade ruim e as medidas diferentes da descrição do catálogo."),
        ("es", "¿Qué dimensiones y materiales tiene la mochila impermeable del catálogo?"),
        ("pt", "Gostaria de saber se essa cafeteira é 220v e se tem estoque para pronta entrega."),
    ),
    "pedido": (
        ("es", "Mi pedido [ID] sigue en preparación y no me llegó el código de seguimiento al [EMAIL]."),
        ("pt", "Meu pedido [ID] atrasou e ainda não chegou no CEP [CEP], onde está minha entrega?"),
        ("es", "Quiero consultar el estado de mi compra [ID] enviada ayer a mi domicilio."),
        ("pt", "O status do meu pedido [ID] consta como entregue mas não recebi nada em casa."),
    ),
    "escalado": (
        ("es", "Exijo hablar con un agente humano ahora mismo o presentaré una denuncia formal."),
        ("pt", "Quero falar com um atendente humano urgente, já reclamei três vezes e vou ao Procon."),
        ("es", "Pásame con un supervisor o persona de soporte, el bot no resuelve mi reclamo."),
        ("pt", "Preciso de um atendente de verdade, vou abrir processo judicial pelo telefone [PHONE]."),
    ),
    "charla": (
        ("es", "¡Hola! Buenos días, muchas gracias por la ayuda, ¿qué puedes hacer por mí?"),
        ("pt", "Oi, bom dia! Muito obrigado pelo atendimento, até logo e bom trabalho."),
        ("es", "Hola, buenas tardes, solo pasaba a saludar y agradecer la atención, adiós."),
        ("pt", "Olá! Tudo bem? Obrigado pelas informações, tchau!"),
    ),
}


def build_synthetic_examples() -> list[Example]:
    """Create a deterministic bilingual synthetic dataset with train/val/test and paraphrase groups."""
    examples: list[Example] = []
    idx = 1
    # 5 labels * (10 train groups + 2 val groups + 2 test groups)
    split_plan: list[tuple[str, int]] = [("train", 10), ("val", 2), ("test", 2)]
    for label in CANONICAL_LABELS:
        templates = _TEMPLATES[label]
        group_seq = 1
        for split_name, n_groups in split_plan:
            for g in range(n_groups):
                lang, base_text = templates[(g + group_seq) % len(templates)]
                group_id = f"S{label[:3]}{group_seq:03d}"
                group_seq += 1
                parent_id = f"I{idx:04d}"
                golden_id = f"G{idx:03d}" if (g == 0 and label in ("politica", "charla")) else None
                source = "golden" if golden_id else "manual_seed"
                examples.append(
                    Example(
                        id=parent_id,
                        text=f"{base_text} ref-{label}-{split_name}-{g}",
                        label=label,
                        language=lang,
                        source=source,
                        group_id=group_id,
                        parent_id=None,
                        golden_id=golden_id,
                        split=split_name,
                        reviewed=True,
                    )
                )
                idx += 1
                # Add a paraphrase in the same group and same split for half the groups
                if g % 2 == 0:
                    other_lang = "pt" if lang == "es" else "es"
                    examples.append(
                        Example(
                            id=f"I{idx:04d}",
                            text=f"Variante {label}: {base_text} ({split_name}-{g})",
                            label=label,
                            language=other_lang,
                            source="paraphrase",
                            group_id=group_id,
                            parent_id=parent_id,
                            golden_id=golden_id,
                            split=split_name,
                            reviewed=True,
                        )
                    )
                    idx += 1
    return examples


@pytest.fixture
def synthetic_examples() -> list[Example]:
    return build_synthetic_examples()


@pytest.fixture
def synthetic_dataset_path(tmp_path: Path, synthetic_examples: list[Example]) -> Path:
    path = tmp_path / "intents.jsonl"
    dump_examples(synthetic_examples, path)
    return path
