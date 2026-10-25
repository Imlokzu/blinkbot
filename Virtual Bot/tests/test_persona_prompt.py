"""The persona part carries a compact identity block and an optional greeting seed.

The identity block tells the model what it is (a virtual pixel crab with an
expressive face) without overriding the temperament the owner picked in the
setup wizard. The greeting is a STYLE SEED, not a script: repeated hellos on
every turn read as broken, and there is deliberately no first-contact-of-day
tracking to lean on.

Everything here roots out the owner's private data: profile_store.load, the
user profiles and the note search are patched, so no real bot_profile.json or
brain/ content is ever read by these tests.
"""

from __future__ import annotations

import re
import json
from unittest.mock import patch

import brain_context
import brains
import profile_store


def _profile(**overrides) -> dict:
    """A controlled profile: defaults from the wizard shape, no file reads."""
    base = {
        "configured": True,
        "name": "Test Bot",
        "language": "en",
        "persona": "friendly",
        "persona_custom": "",
        "greeting": "",
        "reply_length": "medium",
        "use_emoji": True,
        "spontaneous": False,
    }
    base.update(overrides)
    return base


def _parts(profile: dict, **flags) -> dict[str, str]:
    """system_prompt_parts with every private-data source patched out."""
    with (
        patch.object(profile_store, "load", return_value=profile),
        patch.object(brain_context, "init_user_brain", return_value=None),
        patch.object(brains, "load_user_profile", return_value=""),
        patch.object(brains, "find_relevant_notes", return_value=[]),
    ):
        return dict(brains.system_prompt_parts("hello there", **flags))


_CYRILLIC = re.compile(r"[А-Яа-яЄєІіЇїҐґ]")


def test_identity_block_is_present_in_the_default_persona():
    persona = _parts(_profile())["persona"]
    assert brains._IDENTITY_BLOCK in persona
    # The wizard persona and the rules continue to frame the identity
    assert "Ти теплий, доброзичливий" in persona
    assert "Правила:" in persona


def test_identity_block_stays_under_600_characters():
    # The tools block already floods the prompt; identity longer than this
    # would drown exactly like a verbose rule at the end of the prompt.
    assert 0 < len(brains._IDENTITY_BLOCK) < 600


def test_identity_block_uses_english_only():
    # New prompt instructions added by edits are English; Cyrillic here
    # would mean the block drifted into untranslated locale text.
    assert _CYRILLIC.search(brains._IDENTITY_BLOCK) is None
    assert brains._IDENTITY_BLOCK.isascii()


def test_custom_persona_is_preserved_and_not_forced_warm():
    persona = _parts(_profile(persona_custom="Dry, factual, no small talk."))["persona"]
    assert "Dry, factual, no small talk." in persona
    assert brains._IDENTITY_BLOCK in persona
    # The identity block itself must not smuggle a temperament in
    assert "always wins" in brains._IDENTITY_BLOCK


def test_concise_preset_survives_next_to_the_identity():
    persona = _parts(_profile(persona="concise"))["persona"]
    assert "стриманий і лаконічний" in persona
    assert brains._IDENTITY_BLOCK in persona


def test_configured_name_still_names_the_bot():
    persona = _parts(_profile(name="Bramble"))["persona"]
    assert "«Bramble»" in persona
    assert "Test Bot" not in persona


def test_empty_greeting_adds_no_instruction():
    for greeting in ("", "   ", "\n\t "):
        assert "STYLE SEED" not in _parts(_profile(greeting=greeting))["persona"]


def test_nonempty_greeting_appears_once_as_a_seed():
    persona = _parts(_profile(greeting="Good morning"))["persona"]
    assert persona.count("STYLE SEED") == 1
    assert "Good morning" in persona
    # Adapted to the local time the time part supplies, and never a script
    assert "adapted to the current local time" in persona
    assert "never repeat the same greeting" in persona


def test_greeting_seed_uses_english_scaffold_only():
    # The seed's own instructions must be English; the owner's greeting text
    # is stored user content and is reported back verbatim, in any language.
    for greeting in ('Say "hi" first', 'Доброго ранку\nЯк справи?'):
        seed = brains._greeting_style_seed(_profile(greeting=greeting))
        quoted = json.dumps(greeting, ensure_ascii=False)
        assert seed.count(quoted) == 1
        assert _CYRILLIC.search(seed.replace(quoted, "")) is None

def test_prompt_part_names_and_order_are_exactly_preserved():
    # Adding a new part would ripple into the panel breakdown and every
    # ordering expectation; identity and seed fold into "persona" instead.
    names = list(_parts(_profile(), voice=True, spoken=True, channel="telegram"))
    assert names == ["persona", "self", "time", "tools", "memory_rule",
                     "channel", "voice", "tts", "asr"]


if __name__ == "__main__":
    import pytest

    raise SystemExit(pytest.main([__file__]))
