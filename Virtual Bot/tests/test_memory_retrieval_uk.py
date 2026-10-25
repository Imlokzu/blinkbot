"""Inflection recall supplements, rather than reorders, existing keyword hits.

The dependency-free heuristic removes one conservative noun ending and requires
four remaining Cyrillic letters. It deliberately leaves short names and stem
alternations unsupported instead of matching unrelated words by tiny prefixes.
Every fixture lives in a supplied temporary brain root.
"""
from pathlib import Path

import pytest

import memory


def note(root: Path, path: str, text: str) -> Path:
    destination = root / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text, encoding="utf-8")
    return destination


@pytest.mark.parametrize("query,stored", [
    ("кішку", "кішка"), ("кішка", "кішку"),
    ("роботі", "робота"), ("робота", "роботі"),
    ("проєкти", "проєкт"), ("проєкт", "проєкти"),
    ("книжками", "книжка"), ("книжка", "книжками"),
    ("проєктом", "проєкти"), ("проєктах", "проєкт"),
    ("КІШКОЮ", "КІШКА"),
])
def test_common_noun_endings_find_a_durable_note(tmp_path, query, stored):
    note(tmp_path, "topics/fixture.md", "# Fixture\n" + stored)
    result = memory.find_relevant_notes(query, root=tmp_path)
    assert [row["path"] for row in result] == ["topics/fixture.md"]


@pytest.mark.parametrize("query,stored", [
    ("кіт", "кит"), ("сік", "сіно"), ("рука", "рух"),
    ("дару", "даремно"), ("проєктами", "проєктор"),
    ("тестами", "тестовий"), ("runners", "runner"),
    ("Мія", "Мії"), ("Мії", "Мія"),
])
def test_short_names_unrelated_words_and_latin_are_not_folded(tmp_path, query, stored):
    note(tmp_path, "topics/fixture.md", "# Fixture\n" + stored)
    assert memory.find_relevant_notes(query, root=tmp_path) == []


def test_exact_ranking_and_path_ties_precede_any_inflected_score(tmp_path):
    note(tmp_path, "topics/a.md", "кішка " * 100)
    note(tmp_path, "topics/b.md", "кішку")
    note(tmp_path, "topics/c.md", "кішку")
    note(tmp_path, "topics/d.md", "кішку кішку")
    results = memory.find_relevant_notes("кішку", top_n=4, root=tmp_path)
    assert [row["path"] for row in results] == [
        "topics/d.md", "topics/b.md", "topics/c.md", "topics/a.md",
    ]
    assert [row["path"] for row in memory.find_relevant_notes("кішку", top_n=1, root=tmp_path)] == ["topics/d.md"]


def test_legacy_substring_scoring_remains_unchanged(tmp_path):
    note(tmp_path, "topics/a.md", "robotics robotics")
    note(tmp_path, "topics/b.md", "robot")
    assert [r["path"] for r in memory.find_relevant_notes("robot", root=tmp_path)] == [
        "topics/a.md", "topics/b.md",
    ]


def test_morphology_is_whole_word_and_ties_break_by_path(tmp_path):
    note(tmp_path, "topics/z.md", "кішка")
    note(tmp_path, "topics/a.md", "кішка")
    note(tmp_path, "topics/prefix.md", "суперкішкатест")
    assert [r["path"] for r in memory.find_relevant_notes("кішку", root=tmp_path)] == [
        "topics/a.md", "topics/z.md",
    ]


def test_inflected_primary_keeps_link_expansion_and_root_boundary(tmp_path):
    root = tmp_path / "brain"
    note(root, "topics/a.md", "кішка [Details](../pets/b.md) [Escape](../../outside.md)")
    note(root, "pets/b.md", "Detailed fixture.")
    note(tmp_path, "outside.md", "Never retrieve this fixture.")
    result = memory.find_relevant_notes("кішку", top_n=1, root=root)
    assert [r["path"] for r in result] == ["topics/a.md", "pets/b.md"]


def test_other_brain_and_log_files_stay_excluded(tmp_path):
    mine = tmp_path / "mine"
    other = tmp_path / "other"
    note(mine, "logs/history.md", "кішка")
    note(mine, "topics/_index.md", "кішка")
    note(other, "topics/private.md", "кішка")
    assert memory.find_relevant_notes("кішку", root=mine) == []


def test_inflected_results_preserve_snippet_and_result_limits(tmp_path):
    for name in ("a", "b", "c", "d"):
        note(tmp_path, f"topics/{name}.md", "кішка " * 3000)
    results = memory.find_relevant_notes("кішку", top_n=3, root=tmp_path)
    assert len(results) <= 3
    assert all(len(r["snippet"]) <= memory._NOTE_SNIPPET_LIMIT for r in results)
    assert sum(len(r["snippet"]) for r in results) <= memory._TOTAL_SNIPPET_LIMIT
    assert memory.find_relevant_notes("кішку", top_n=0, root=tmp_path) == []
    assert memory.find_relevant_notes("кішку", top_n=-1, root=tmp_path) == []
    assert memory.find_relevant_notes("", root=tmp_path) == []
