from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import chat_store


class ChatListPreviewTests(unittest.TestCase):
    """The phone history shows one line of each chat so same-titled chats differ."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.chats = Path(self.temp.name)
        self.patch = patch.object(chat_store, "CHATS_DIR", self.chats)
        self.patch.start()

    def tearDown(self) -> None:
        self.patch.stop()
        self.temp.cleanup()

    def test_preview_is_the_latest_message_on_one_line(self) -> None:
        chat_store.append("a", "Hi", "Hello!\n\nHow can   I help?")
        chat_store.append("b", "Hi", "Morning. Your build passed.")
        previews = {s["id"]: s["preview"] for s in chat_store.list_sessions()}
        # Both chats are titled "Hi"; the preview is what tells them apart.
        self.assertEqual(previews["a"], "Hello! How can I help?")
        self.assertEqual(previews["b"], "Morning. Your build passed.")

    def test_long_previews_are_cut_and_bubble_parts_are_read(self) -> None:
        chat_store.append("long", "Long", "x" * 500)
        path = self.chats / "parts.json"
        path.write_text(json.dumps({"id": "parts", "title": "Parts", "updated": 5, "messages": [
            {"role": "assistant", "content": "", "parts": [{"type": "text", "text": "first"}, {"type": "text", "text": "second"}]},
            {"role": "assistant", "content": "   "},
        ]}))
        previews = {s["id"]: s["preview"] for s in chat_store.list_sessions()}
        self.assertEqual(previews["long"], "x" * chat_store.LIST_PREVIEW + "…")
        # A blank last message falls back to the newest one that has text.
        self.assertEqual(previews["parts"], "first second")


if __name__ == "__main__":
    unittest.main()
