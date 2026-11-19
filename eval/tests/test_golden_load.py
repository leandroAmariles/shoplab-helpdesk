from pathlib import Path

from helpers import make_case, write_golden
from shoplab_eval.golden import GoldenCase, parse_golden


def test_valid_file_gives_all_cases_and_no_issues(tmp_path: Path) -> None:
    path = write_golden(tmp_path / "golden.jsonl", [make_case(), make_case(id="G002")])

    parsed = parse_golden(path)

    assert parsed.issues == []
    assert [case.id for case in parsed.cases] == ["G001", "G002"]
    assert parsed.cases[0].expected_documents == ("payments-01-a",)
    assert parsed.cases[1].line == 2


def test_blank_lines_and_missing_final_newline_do_not_count(tmp_path: Path) -> None:
    path = tmp_path / "golden.jsonl"
    path.write_text("\n" + _line(make_case()) + "\n\n   \n" + _line(make_case(id="G002")), encoding="utf-8")

    parsed = parse_golden(path)

    assert parsed.issues == []
    assert [case.id for case in parsed.cases] == ["G001", "G002"]


def test_invalid_json_is_reported_with_the_line_and_reading_continues(tmp_path: Path) -> None:
    path = tmp_path / "golden.jsonl"
    path.write_text(_line(make_case()) + "\n{esto no es json}\n" + _line(make_case(id="G003")) + "\n", encoding="utf-8")

    parsed = parse_golden(path)

    assert [(issue.code, issue.case_id, issue.line) for issue in parsed.issues] == [("G-JSON", "LINE 2", 2)]
    assert [case.id for case in parsed.cases] == ["G001", "G003"]


def test_a_line_that_is_not_an_object_is_a_fields_issue(tmp_path: Path) -> None:
    path = tmp_path / "golden.jsonl"
    path.write_text('["G001"]\n', encoding="utf-8")

    parsed = parse_golden(path)

    assert [(issue.code, issue.case_id) for issue in parsed.issues] == [("G-FIELDS", "LINE 1")]


def test_unknown_and_missing_keys_are_reported_with_the_case_id(tmp_path: Path) -> None:
    unknown = make_case(id="G001", extra="x")
    missing = make_case(id="G002")
    del missing["category"]
    path = write_golden(tmp_path / "golden.jsonl", [unknown, missing])

    parsed = parse_golden(path)

    assert [(issue.code, issue.case_id) for issue in parsed.issues] == [("G-FIELDS", "G001"), ("G-FIELDS", "G002")]
    assert "extra" in parsed.issues[0].message
    assert "category" in parsed.issues[1].message
    assert parsed.cases == []


def test_wrong_types_are_reported(tmp_path: Path) -> None:
    path = write_golden(
        tmp_path / "golden.jsonl",
        [
            make_case(id="G001", expected_documents="payments-01-a"),
            make_case(id="G002", expected_documents=["payments-01-a", 3]),
            make_case(id="G003", question=7),
        ],
    )

    parsed = parse_golden(path)

    assert [(issue.code, issue.case_id) for issue in parsed.issues] == [
        ("G-FIELDS", "G001"),
        ("G-FIELDS", "G002"),
        ("G-FIELDS", "G003"),
    ]


def test_accents_and_enyes_arrive_intact(tmp_path: Path) -> None:
    path = write_golden(tmp_path / "golden.jsonl", [make_case(question="¿Cuántas cuotas para un año de ñandúes?")])

    assert parse_golden(path).cases[0].question == "¿Cuántas cuotas para un año de ñandúes?"


def test_a_byte_order_mark_is_reported_with_a_clear_message(tmp_path: Path) -> None:
    path = tmp_path / "golden.jsonl"
    path.write_bytes(b"\xef\xbb\xbf" + _line(make_case()).encode("utf-8") + b"\n")

    parsed = parse_golden(path)

    assert [issue.code for issue in parsed.issues] == ["G-JSON"]
    assert "BOM" in parsed.issues[0].message
    assert [case.id for case in parsed.cases] == ["G001"]


def test_a_file_that_is_not_utf8_is_reported(tmp_path: Path) -> None:
    path = tmp_path / "golden.jsonl"
    path.write_bytes(b'{"id": "G001", "question": "\xe9"}\n')

    parsed = parse_golden(path)

    assert [issue.code for issue in parsed.issues] == ["G-JSON"]
    assert "UTF-8" in parsed.issues[0].message
    assert parsed.cases == []


def test_windows_line_endings_are_accepted(tmp_path: Path) -> None:
    path = tmp_path / "golden.jsonl"
    path.write_bytes((_line(make_case()) + "\r\n" + _line(make_case(id="G002")) + "\r\n").encode("utf-8"))

    assert [case.id for case in parse_golden(path).cases] == ["G001", "G002"]


def test_derived_properties() -> None:
    answered = GoldenCase("G001", "q" * 10, "a" * 20, ("d1",), "payments")
    two_documents = GoldenCase("G002", "q" * 10, "a" * 20, ("d1", "d2"), "payments")
    unanswered = GoldenCase("G003", "q" * 10, "a" * 20, (), "payments")
    off_topic = GoldenCase("G004", "q" * 10, "a" * 20, (), "off_topic")

    assert [case.kind for case in (answered, two_documents, unanswered, off_topic)] == [
        "answered",
        "answered",
        "unanswered_topic",
        "off_topic",
    ]
    assert [case.answerable for case in (answered, unanswered)] == [True, False]
    assert [case.is_multi_document for case in (answered, two_documents)] == [False, True]


def _line(case: dict) -> str:
    import json

    return json.dumps(case, ensure_ascii=False)
