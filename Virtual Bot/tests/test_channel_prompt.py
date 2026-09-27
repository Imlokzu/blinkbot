"""The prompt says where the conversation is happening.

Without it the model guessed: tables in Telegram (raw | and # there),
"look at the screen" to someone reading on their phone.
"""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, patch

import brains
import main


def _parts(**kwargs) -> dict[str, str]:
    return dict(brains.system_prompt_parts("привіт", **kwargs))


def test_each_channel_gets_its_own_block():
    assert "Telegram" in _parts(channel="telegram")["channel"]
    assert "320x240" in _parts(channel="screen")["channel"]
    assert "channel" not in _parts()
    assert "channel" not in _parts(channel="fax")


def test_telegram_rules_cover_tables_and_reactions_with_words():
    block = _parts(channel="telegram")["channel"]
    assert "table" in block and "[react:" in block and "[[msg]]" in block


def test_channel_sits_before_the_voice_blocks_and_asr_stays_last():
    names = [name for name, _ in brains.system_prompt_parts("x", voice=True, spoken=True, channel="screen")]
    assert names.index("channel") < names.index("voice")
    assert names[-1] == "asr"


def test_messenger_turn_passes_its_channel_to_the_brain():
    seen: dict = {}

    async def chat(message, history, **kwargs):
        seen.update(kwargs)
        return "ok", "idle", "test", []

    async def turn():
        req = main.ChatRequest(message="hi", session_id="channel-telegram")
        return await main.chat_turn(req, "", "telegram")

    with patch.object(main.brains, "chat", chat), patch.object(main, "_autoname_chat", AsyncMock()):
        loop = asyncio.new_event_loop()
        try:
            loop.run_until_complete(turn())
        finally:
            loop.close()
    assert seen.get("channel") == "telegram"
    assert main._chat_channel_kwargs("unknown") == {}
