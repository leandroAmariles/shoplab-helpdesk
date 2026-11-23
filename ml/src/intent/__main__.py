"""Multi-command dispatcher for python -m intent (train, evaluate, embed-cache)."""

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from .constants import (
    DEFAULT_DATASET_PATH,
    DEFAULT_EMBEDDINGS_CACHE_PATH,
)
from .data import load_intents_jsonl, validate_dataset_integrity
from .embeddings import (
    EmbeddingCache,
    EmbeddingGatewayClient,
    EmbeddingSettings,
    build_deterministic_embeddings_cache,
    sha256_text,
)
from .evaluate import main as evaluate_main
from .preprocess import preprocess_text
from .train import main as train_main


def _run_embed_cache(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m intent embed-cache",
        description="Genera o verifica el archivo de caché de embeddings para el dataset.",
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=DEFAULT_DATASET_PATH,
        help="Ruta al archivo JSONL del dataset.",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=DEFAULT_EMBEDDINGS_CACHE_PATH,
        help="Ruta de salida para el archivo .npz de caché de embeddings.",
    )
    parser.add_argument(
        "--mode",
        choices=["gateway", "deterministic-projection"],
        default="deterministic-projection",
        help="Modo de generación de embeddings: gateway (HTTP) o deterministic-projection (local CPU).",
    )
    args = parser.parse_args(argv)

    if not args.dataset.exists():
        print(f"Error: dataset no encontrado en {args.dataset}", file=sys.stderr)
        return 2

    examples = load_intents_jsonl(args.dataset)
    validate_dataset_integrity(examples)

    if args.mode == "deterministic-projection":
        cache = build_deterministic_embeddings_cache(examples, args.out)
        print(
            f"Caché determinista generada en {args.out}: {len(cache)} vectores "
            f"(dim={cache.dimension}, model={cache.model_name})."
        )
        return 0

    # Gateway mode
    try:
        settings = EmbeddingSettings.from_env()
        client = EmbeddingGatewayClient(settings)
        unique_texts: dict[str, str] = {}
        for ex in examples:
            prep = preprocess_text(ex.text)
            unique_texts[sha256_text(prep)] = prep

        hashes = list(unique_texts.keys())
        texts = [unique_texts[h] for h in hashes]
        vectors = client.embed_texts(texts)
        cache = EmbeddingCache(
            vectors={h: vectors[idx] for idx, h in enumerate(hashes)},
            dimension=vectors.shape[1],
            model_name=settings.model,
        )
        cache.save(args.out)
        print(
            f"Caché desde gateway generada en {args.out}: {len(cache)} vectores "
            f"(dim={cache.dimension}, model={cache.model_name})."
        )
        return 0
    except Exception as exc:
        print(f"Error al generar caché desde gateway: {exc}", file=sys.stderr)
        return 2


def main(argv: Sequence[str] | None = None) -> int:
    """Dispatch subcommands when run as python -m intent."""
    raw_args = list(sys.argv[1:] if argv is None else argv)
    if not raw_args or raw_args[0] in ("-h", "--help"):
        print("Uso: python -m intent [train|evaluate|embed-cache] [opciones]")
        print("O ejecute directamente: python -m intent.train o python -m intent.evaluate")
        return 0

    subcommand = raw_args[0]
    rest = raw_args[1:]

    if subcommand == "train":
        return train_main(rest)
    elif subcommand == "evaluate":
        return evaluate_main(rest)
    elif subcommand == "embed-cache":
        return _run_embed_cache(rest)
    else:
        print(f"Subcomando desconocido: {subcommand}. Opciones válidas: train, evaluate, embed-cache.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
