"""Jev, the automatic model pick: routing rules and how the pick is stored."""

from __future__ import annotations

import asyncio
import json
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

import app_config as cfg
import brains
import jev_router
import main
import openclaw_models
import openclaw_settings

CATALOG = json.dumps({"models": [
    {"key": "openai/gpt-6-luna", "name": "GPT-6 Luna", "tags": ["default"]},
    {"key": "openai/gpt-6-sol", "name": "GPT-6 Sol"},
    {"key": "regolo/gpt-oss-20b", "name": "GPT-OSS 20B"},
]})


class ClassifyTests(unittest.TestCase):
    def assertTier(self, tier: str, *messages: str) -> None:
        for message in messages:
            with self.subTest(message=message):
                self.assertEqual(jev_router.classify(message), tier)

    def test_quick_questions_go_fast(self) -> None:
        self.assertTier(
            "fast",
            "Яка зараз погода в Києві?",
            "Котра година?",
            "яке сьогодні число",
            "Чи буде завтра дощ?",
            "What time is it?",
            "what's the weather like in Lviv",
            "курс долара",
            "постав таймер на 5 хвилин",
            "Привіт!",
            "дякую",
            "thanks",
            "2+2",
            "15 * 7 = ?",
        )

    def test_making_something_goes_to_build(self) -> None:
        self.assertTier(
            "build",
            "Зроби мені сайт для кав'ярні",
            "напиши скрипт, який перейменує фото",
            "створи гру змійка",
            "build me a todo app in React",
            "write a python function that parses dates",
            "fix this bug:\n```js\nlet x = 1\n```",
        )

    def test_everything_else_is_smart(self) -> None:
        self.assertTier(
            "smart",
            "Поясни, чому небо синє",
            "Що краще для старту: Python чи JavaScript?",
            "Розкажи про історію Києва",
            "Summarize the plot of Dune",
            "",
        )

    def test_long_message_is_not_fast_even_about_weather(self) -> None:
        text = "Погода сьогодні дивна, " + "і я думаю про те, що варто переглянути план подорожі " * 4
        self.assertEqual(jev_router.classify(text), "smart")

    def test_building_beats_a_quick_word(self) -> None:
        """«Зроби віджет погоди» — це робота, а не питання про погоду."""
        self.assertEqual(jev_router.classify("зроби віджет погоди"), "build")


class RouteTests(unittest.TestCase):
    def test_fast_tier_thinks_low_for_this_message_only(self) -> None:
        tier, model, text = jev_router.route("Котра година?")
        self.assertEqual((tier, model), ("fast", cfg.JEV_FAST_MODEL))
        self.assertEqual(text, f"/think:{cfg.JEV_FAST_THINKING} Котра година?")

    def test_own_directive_is_kept(self) -> None:
        _tier, _model, text = jev_router.route("/think:high котра година")
        self.assertEqual(text, "/think:high котра година")

    def test_other_tiers_send_the_message_as_typed(self) -> None:
        for message, model in (
            ("Поясни теорію відносності", cfg.JEV_SMART_MODEL),
            ("Зроби сайт-портфоліо", cfg.JEV_BUILD_MODEL),
        ):
            with self.subTest(message=message):
                _tier, routed, text = jev_router.route(message)
                self.assertEqual((routed, text), (model, message))


class SelectionTests(unittest.TestCase):
    def setUp(self) -> None:
        openclaw_models.set_selected("")

    def tearDown(self) -> None:
        openclaw_models.set_selected("")

    def test_jev_routes_each_message(self) -> None:
        openclaw_models.set_selected(jev_router.JEV_ID)
        self.assertEqual(openclaw_models.get_selected(), jev_router.JEV_ID)
        self.assertEqual(openclaw_models.chat_headers(), {})
        headers, _text, tier = openclaw_models.chat_route("Зроби гру")
        self.assertEqual(tier, "build")
        self.assertEqual(headers, {"x-openclaw-model": cfg.JEV_BUILD_MODEL})

    def test_fixed_pick_is_untouched(self) -> None:
        openclaw_models.set_selected("openai/gpt-6-sol")
        self.assertEqual(
            openclaw_models.chat_route("Котра година?"),
            ({"x-openclaw-model": "openai/gpt-6-sol"}, "Котра година?", ""),
        )

    def test_real_model_turns_jev_off(self) -> None:
        openclaw_models.set_selected(jev_router.JEV_ID)
        openclaw_models.set_selected("openai/gpt-6-luna")
        self.assertFalse(openclaw_models.is_auto())
        self.assertEqual(openclaw_models.get_selected(), "openai/gpt-6-luna")

    def test_choice_survives_a_restart(self) -> None:
        openclaw_models.set_selected(jev_router.JEV_ID)
        with patch.object(openclaw_models, "_auto", None):
            self.assertTrue(openclaw_models.is_auto())
        openclaw_models.set_selected("")
        with patch.object(openclaw_models, "_auto", None):
            self.assertFalse(openclaw_models.is_auto())

    def test_header_names_the_routed_model(self) -> None:
        openclaw_models.set_selected(jev_router.JEV_ID)
        with (
            patch.object(brains, "_last_successful_brain", "openclaw"),
            patch.object(brains, "_last_model", "openclaw"),
            patch.object(brains, "_last_jev_model", "regolo/gpt-oss-20b"),
        ):
            self.assertEqual(brains.get_last_model(), "gpt-oss-20b · OpenClaw")

    def test_settings_page_can_pick_jev_without_the_cli(self) -> None:
        with patch.object(openclaw_models, "_run_cli", AsyncMock()) as run:
            ok = asyncio.run(openclaw_settings.apply("agents.defaults.model.primary", jev_router.JEV_ID))
        self.assertTrue(ok)
        run.assert_not_awaited()
        self.assertTrue(openclaw_models.is_auto())


class ChatCallTests(unittest.TestCase):
    """What the gateway actually receives when Jev is on."""

    def setUp(self) -> None:
        openclaw_models.set_selected(jev_router.JEV_ID)

    def tearDown(self) -> None:
        openclaw_models.set_selected("")

    def _send(self, message: str, images=None) -> dict:
        captured = {}

        async def fake_call(*args, **kwargs):
            captured["headers"] = args[1]
            captured["payload"] = args[2]
            return "[емоція:idle] ok", []

        with (
            patch.object(brains.cfg, "get_openclaw_token", return_value="token"),
            patch.object(brains, "_call_openai_compatible_with_tools", side_effect=fake_call),
            patch.object(brains.openclaw_config, "image_model", return_value="regolo/qwen3.5-122b"),
        ):
            asyncio.run(brains.chat_openclaw(message, "system", [], images=images, session_key="k"))
        return captured

    def test_quick_question_reaches_the_fast_model_with_low_thinking(self) -> None:
        sent = self._send("Котра година?")
        self.assertEqual(sent["headers"]["x-openclaw-model"], cfg.JEV_FAST_MODEL)
        self.assertEqual(
            sent["payload"]["messages"][-1]["content"],
            f"/think:{cfg.JEV_FAST_THINKING} Котра година?",
        )

    def test_build_request_reaches_sol_as_typed(self) -> None:
        sent = self._send("Зроби мені сайт")
        self.assertEqual(sent["headers"]["x-openclaw-model"], cfg.JEV_BUILD_MODEL)
        self.assertEqual(sent["payload"]["messages"][-1]["content"], "Зроби мені сайт")

    def test_image_turn_still_goes_to_the_image_model(self) -> None:
        image = {"mime": "image/png", "data": "iVBORw0KGgo="}
        sent = self._send("Котра година на цьому фото?", images=[image])
        self.assertEqual(sent["headers"]["x-openclaw-model"], "regolo/qwen3.5-122b")


class EndpointTests(unittest.TestCase):
    def setUp(self) -> None:
        self._disk = openclaw_models._DISK_CACHE
        openclaw_models._DISK_CACHE = None
        openclaw_models._catalog = None
        openclaw_models.set_selected("")
        self.client = TestClient(main.app)

    def tearDown(self) -> None:
        openclaw_models._catalog = None
        openclaw_models._DISK_CACHE = self._disk
        openclaw_models.set_selected("")

    def test_jev_leads_the_catalog(self) -> None:
        with (
            patch.object(openclaw_models, "reachable", return_value=True),
            patch.object(openclaw_models, "_thinking_from_config", return_value=""),
            patch.object(openclaw_models, "_run_cli", AsyncMock(return_value=(0, CATALOG, ""))),
        ):
            body = self.client.get("/api/brain/models").json()
        self.assertEqual(body["models"][0]["id"], jev_router.JEV_ID)
        self.assertTrue(body["models"][0]["auto"])
        self.assertEqual(body["default"], "openai/gpt-6-luna")

    def test_picking_jev_leaves_openclaw_config_alone(self) -> None:
        with patch.object(openclaw_models, "_run_cli", AsyncMock()) as run:
            response = self.client.post("/api/brain/model", json={"model": jev_router.JEV_ID})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["selected"], jev_router.JEV_ID)
        run.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
