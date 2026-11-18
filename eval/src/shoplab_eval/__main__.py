"""``python -m shoplab_eval validate``: validates the golden dataset and prints its summary."""

import argparse
import sys
from pathlib import Path

from shoplab_eval.constants import DEFAULT_GOLDEN_PATH, DEFAULT_KB_DIR
from shoplab_eval.errors import InvalidGoldenError
from shoplab_eval.golden import validate_golden
from shoplab_eval.summary import format_summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="shoplab_eval", description="Dataset golden de ShopLab Helpdesk")
    commands = parser.add_subparsers(dest="command")
    validate = commands.add_parser("validate", help="valida eval/golden.jsonl contra el corpus y muestra el resumen")
    validate.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN_PATH, help="ruta del archivo JSONL")
    validate.add_argument("--kb", type=Path, default=DEFAULT_KB_DIR, help="directorio del corpus (data/kb)")
    args = parser.parse_args(argv)
    _use_utf8_output()
    if args.command != "validate":
        parser.print_help()
        return 2
    try:
        summary = validate_golden(args.golden, args.kb)
    except InvalidGoldenError as error:
        print(error)
        return 1
    except (FileNotFoundError, ValueError) as error:
        print(error)
        return 1
    print(format_summary(summary))
    return 0


def _use_utf8_output() -> None:
    """The report is in Spanish; the default console encoding of Windows would garble the accents."""
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        reconfigure(encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
