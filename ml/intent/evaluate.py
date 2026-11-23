"""Wrapper script for invoking intent.evaluate directly (e.g. python intent/evaluate.py)."""

import importlib.util
import sys
from pathlib import Path

_SRC_DIR = Path(__file__).resolve().parent.parent / "src"
if str(_SRC_DIR) not in sys.path:
    sys.path.insert(0, str(_SRC_DIR))

_target = _SRC_DIR / "intent" / "evaluate.py"
_spec = importlib.util.spec_from_file_location("_intent_eval_impl", _target)
if _spec is None or _spec.loader is None:
    raise ImportError(f"Cannot load evaluate implementation from {_target}")
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

main = _mod.main

if __name__ == "__main__":
    sys.exit(main())
