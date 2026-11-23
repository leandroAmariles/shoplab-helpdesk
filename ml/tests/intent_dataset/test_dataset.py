import json

import pytest

from intent_dataset.constants import EXAMPLE_KEYS
from intent_dataset.dataset import Example, dump_examples, load_examples, next_id
from intent_dataset.errors import InvalidDatasetError


def _example(example_id: str = "I0001", **changes) -> Example:
    values = {
        "id": example_id,
        "text": "hola",
        "label": "charla",
        "language": "es",
        "source": "manual_seed",
        "group_id": "S001",
        "parent_id": None,
        "golden_id": None,
        "split": "train",
        "reviewed": True,
    }
    values.update(changes)
    return Example(**values)


def test_round_trip(tmp_path):
    path = tmp_path / "intents.jsonl"
    examples = [_example("I0002", text="adiós"), _example("I0001", split=None, parent_id=None)]
    dump_examples(examples, path)
    loaded = load_examples(path)
    assert [e.id for e in loaded] == ["I0001", "I0002"]
    assert loaded[0].split is None
    assert loaded[1].text == "adiós"


def test_file_has_ordered_keys_no_bom_and_unix_newlines(tmp_path):
    path = tmp_path / "intents.jsonl"
    dump_examples([_example()], path)
    raw = path.read_bytes()
    assert not raw.startswith(b"\xef\xbb\xbf")
    assert b"\r" not in raw
    assert raw.endswith(b"\n")
    assert list(json.loads(raw.decode("utf-8")).keys()) == list(EXAMPLE_KEYS)


def test_non_ascii_text_is_written_as_utf8_not_escaped(tmp_path):
    path = tmp_path / "intents.jsonl"
    dump_examples([_example(text="adiós")], path)
    assert "adiós".encode() in path.read_bytes()


def test_blank_lines_are_tolerated_on_read(tmp_path):
    path = tmp_path / "intents.jsonl"
    dump_examples([_example()], path)
    path.write_text("\n" + path.read_text(encoding="utf-8") + "\n\n", encoding="utf-8")
    assert len(load_examples(path)) == 1


def test_unknown_key_is_rejected_with_the_line_number(tmp_path):
    path = tmp_path / "intents.jsonl"
    dump_examples([_example()], path)
    line = json.loads(path.read_text(encoding="utf-8"))
    line["extra"] = 1
    path.write_text(json.dumps(line) + "\n", encoding="utf-8")
    with pytest.raises(InvalidDatasetError, match=r"línea 1.*extra"):
        load_examples(path)


def test_missing_key_is_rejected(tmp_path):
    path = tmp_path / "intents.jsonl"
    path.write_text(json.dumps({"id": "I0001"}) + "\n", encoding="utf-8")
    with pytest.raises(InvalidDatasetError, match=r"línea 1.*falta"):
        load_examples(path)


def test_broken_json_reports_the_line(tmp_path):
    path = tmp_path / "intents.jsonl"
    dump_examples([_example()], path)
    path.write_text(path.read_text(encoding="utf-8") + "{no es json\n", encoding="utf-8")
    with pytest.raises(InvalidDatasetError, match="línea 2"):
        load_examples(path)


def test_missing_file_is_a_clear_error(tmp_path):
    with pytest.raises(InvalidDatasetError, match="No existe"):
        load_examples(tmp_path / "nope.jsonl")


def test_next_id_starts_at_one_and_never_reuses():
    assert next_id([]) == "I0001"
    assert next_id([_example("I0001"), _example("I0007")]) == "I0008"


def test_dump_is_atomic_and_leaves_no_temp_file(tmp_path):
    path = tmp_path / "intents.jsonl"
    dump_examples([_example()], path)
    assert [p.name for p in tmp_path.iterdir()] == ["intents.jsonl"]
