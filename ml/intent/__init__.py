"""Direct CLI wrappers and package namespace extension for intent (LAB-118)."""

from pathlib import Path

# Extend package search path to include src/intent so that `python -m intent`
# seamlessly resolves modules in src/intent even when run from the ml/ directory.
_SRC_INTENT = str(Path(__file__).resolve().parent.parent / "src" / "intent")
if _SRC_INTENT not in __path__:
    __path__.append(_SRC_INTENT)
