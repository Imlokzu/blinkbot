"""Characterization tests for the spontaneous ambient mood state machine.

`vision_watcher._check_spontaneous_mood` decides, once per second, whether the
bot should put a random "alive" emotion from `_AMBIENT_MOODS` on the screen
while nothing else is happening. These tests pin the current contract: the
feature flag, the sleepy/sad guards, the calm period after an interaction, the
schedule bounds (MOOD_MIN_S..MOOD_MAX_S), and expiry back to idle.

They were written against the pre-weighting behaviour and all of them must
keep passing now that the picker is time-aware: the weighting only changes
WHICH emotion is drawn, never the guards, the timings or the schedule bounds.
The `now` argument of `_check_spontaneous_mood`
is the injectable clock for the state machine; no real sleeping happens.
"""

from __future__ import annotations

import unittest
from unittest.mock import patch

import vision_watcher as vw

# Module state that _check_spontaneous_mood reads and mutates. Every test
# snapshots it and restores it afterwards, so tests never leak state into each
# other (or into whatever runs next in the same process).
_STATE_ATTRS = (
    "_last_interaction_ts",
    "_mood_until",
    "_next_mood_at",
    "_sad_until",
    "_sleepy_active",
)


class SpontaneousMoodTests(unittest.TestCase):
    def setUp(self) -> None:
        self._saved = {name: getattr(vw, name) for name in _STATE_ATTRS}
        self.addCleanup(self._restore_state)
        constants = patch.multiple(vw, MOOD_MIN_S=25.0, MOOD_MAX_S=75.0,
                                   MOOD_DURATION_S=4.0, MOOD_CALM_S=12.0)
        constants.start()
        self.addCleanup(constants.stop)

        self.emotions: list[str] = []
        emotion_patcher = patch.object(
            vw, "_set_emotion", side_effect=self.emotions.append
        )
        emotion_patcher.start()
        self.addCleanup(emotion_patcher.stop)

        # Never touch the owner's bot_profile.json: the profile is an object
        # the tests can flip per case ("spontaneous": True is the default).
        self.profile = {"spontaneous": True}
        profile_patcher = patch.object(
            vw.profile_store, "load", side_effect=lambda: self.profile
        )
        profile_patcher.start()
        self.addCleanup(profile_patcher.stop)

        self.now = 10_000.0
        self._set_state()

    def _restore_state(self) -> None:
        for name, value in self._saved.items():
            setattr(vw, name, value)

    def _set_state(
        self,
        *,
        last_interaction_ts: float | None = None,
        mood_until: float | None = None,
        next_mood_at: float | None = None,
        sad_until: float | None = None,
        sleepy: bool = False,
    ) -> None:
        """Place the state machine at a known point; long calm by default."""
        vw._last_interaction_ts = (
            self.now - float(vw.MOOD_CALM_S)
            if last_interaction_ts is None
            else last_interaction_ts
        )
        vw._mood_until = mood_until
        vw._next_mood_at = next_mood_at
        vw._sad_until = sad_until
        vw._sleepy_active = sleepy

    # ------------------------------------------------------------- flag off

    def test_nonpositive_duration_disables_moods_entirely(self) -> None:
        # mood_duration_s == 0 is the documented off switch: nothing is
        # scheduled and nothing fires, even when a mood is already due.
        for duration in (0.0, -1.0):
            with self.subTest(duration=duration), patch.object(
                vw, "MOOD_DURATION_S", duration
            ):
                self.emotions.clear()
                self._set_state(next_mood_at=self.now - 1.0, mood_until=None)
                vw._check_spontaneous_mood(self.now)
                self.assertEqual(self.emotions, [])
                self.assertIsNone(vw._mood_until)
                # The stale schedule is left exactly as it was: the early
                # return wins before any due-check or rescheduling.
                self.assertEqual(vw._next_mood_at, self.now - 1.0)

    def test_nonpositive_duration_ignores_active_mood(self) -> None:
        # Even an in-flight mood never expires while the flag reads <= 0:
        # the early return precedes the expiry branch. Pins current ordering.
        with patch.object(vw, "MOOD_DURATION_S", 0.0):
            self._set_state(mood_until=self.now - 5.0, next_mood_at=None)
            vw._check_spontaneous_mood(self.now)
            self.assertEqual(self.emotions, [])
            self.assertEqual(vw._mood_until, self.now - 5.0)
            self.assertIsNone(vw._next_mood_at)

    # --------------------------------------------------- sleepy / sad guards

    def test_mood_does_not_start_while_sleepy(self) -> None:
        # Drowsiness owns the screen: even a due mood is left pending and the
        # schedule is untouched (the guard returns before the due-check).
        self._set_state(sleepy=True, next_mood_at=self.now)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(self.emotions, [])
        self.assertIsNone(vw._mood_until)
        self.assertEqual(vw._next_mood_at, self.now)

    def test_mood_does_not_start_while_sad(self) -> None:
        # The post-vision sad moment has its own timed return to idle; the
        # ambient mood must not stomp over it.
        self._set_state(sad_until=self.now + 3.0, next_mood_at=self.now)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(self.emotions, [])
        self.assertIsNone(vw._mood_until)
        self.assertEqual(vw._next_mood_at, self.now)

    def test_expiring_mood_still_returns_to_idle_while_sleepy(self) -> None:
        # Characterizes branch order: the expiry branch runs BEFORE the
        # sleepy/sad guards, so an already-running mood still hands the screen
        # back to idle even if drowsiness began in the meantime.
        self._set_state(sleepy=True, mood_until=self.now - 1.0, next_mood_at=None)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(self.emotions, ["idle"])
        self.assertIsNone(vw._mood_until)
        self.assertScheduleWithinBounds()

    # -------------------------------------------------------- calm period

    def test_mood_does_not_start_before_calm_period_elapses(self) -> None:
        # An interaction happened less than MOOD_CALM_S ago: no scheduling and
        # no firing, even though a previously scheduled mood is due right now.
        recent = self.now - (float(vw.MOOD_CALM_S) - 1.0)
        self._set_state(last_interaction_ts=recent, next_mood_at=self.now)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(self.emotions, [])
        self.assertIsNone(vw._mood_until)
        self.assertEqual(vw._next_mood_at, self.now)

    def test_first_check_after_calm_schedules_without_emitting(self) -> None:
        # The first quiet tick after an interaction only PLANS the next mood;
        # the screen keeps whatever emotion it had.
        self._set_state(next_mood_at=None)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(self.emotions, [])
        self.assertIsNone(vw._mood_until)
        self.assertScheduleWithinBounds()

    # ------------------------------------------------------------- firing

    def test_due_mood_fires_allowed_emotion_for_configured_duration(self) -> None:
        self._set_state(next_mood_at=self.now - 0.5)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(len(self.emotions), 1)
        self.assertIn(self.emotions[0], vw._AMBIENT_MOODS)
        self.assertEqual(vw._mood_until, self.now + float(vw.MOOD_DURATION_S))
        # Rescheduling happens at expiry, not at fire time: the stale schedule
        # survives until the mood is retired.
        self.assertEqual(vw._next_mood_at, self.now - 0.5)

    def test_running_mood_before_expiry_changes_nothing(self) -> None:
        mood_until = self.now + 2.0
        self._set_state(mood_until=mood_until, next_mood_at=self.now + 30.0)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(self.emotions, [])
        self.assertEqual(vw._mood_until, mood_until)
        self.assertEqual(vw._next_mood_at, self.now + 30.0)

    def test_expired_mood_returns_to_idle_and_reschedules(self) -> None:
        self._set_state(mood_until=self.now - 1.0, next_mood_at=None)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(self.emotions, ["idle"])
        self.assertIsNone(vw._mood_until)
        self.assertScheduleWithinBounds()

    # ----------------------------------------------- spontaneous=false gate

    def test_spontaneous_false_reschedules_without_firing(self) -> None:
        # The setup-wizard switch silences the behaviour, but the loop keeps
        # rescheduling so it recovers by itself if the switch is re-enabled.
        self.profile["spontaneous"] = False
        self._set_state(next_mood_at=self.now - 0.5)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(self.emotions, [])
        self.assertIsNone(vw._mood_until)
        self.assertScheduleWithinBounds()

    def test_spontaneous_missing_from_profile_defaults_to_enabled(self) -> None:
        # profile_store.load() fills defaults, but the .get() guard is what
        # keeps a hand-edited profile from killing the behaviour silently.
        self.profile.clear()
        self._set_state(next_mood_at=self.now - 0.5)
        vw._check_spontaneous_mood(self.now)
        self.assertEqual(len(self.emotions), 1)
        self.assertIn(self.emotions[0], vw._AMBIENT_MOODS)

    # ----------------------------------------------------- schedule bounds

    def test_every_schedule_lands_within_min_max_window(self) -> None:
        # All three scheduling paths (first plan, expiry, refusal) funnel
        # through _schedule_next_mood; hammer it many times so a uniform draw
        # outside MOOD_MIN_S..MOOD_MAX_S cannot hide.
        for path in ("first-plan", "expiry", "refusal"):
            for _ in range(100):
                with self.subTest(path=path):
                    self.emotions.clear()
                    self.profile["spontaneous"] = path != "refusal"
                    if path == "first-plan":
                        self._set_state(next_mood_at=None)
                    elif path == "expiry":
                        self._set_state(mood_until=self.now - 1.0, next_mood_at=None)
                    else:
                        self.profile["spontaneous"] = False
                        self._set_state(next_mood_at=self.now - 1.0)
                    vw._check_spontaneous_mood(self.now)
                    self.assertScheduleWithinBounds()
                    if path == "first-plan" or path == "refusal":
                        self.assertEqual(self.emotions, [])
                    else:
                        self.assertEqual(self.emotions, ["idle"])

    def assertScheduleWithinBounds(self) -> None:
        self.assertIsNotNone(vw._next_mood_at)
        delay = vw._next_mood_at - self.now
        self.assertGreaterEqual(delay, float(vw.MOOD_MIN_S))
        self.assertLessEqual(delay, float(vw.MOOD_MAX_S))

    # ----------------------------------------------- interaction cancels it

    def test_note_interaction_cancels_pending_and_active_mood(self) -> None:
        # A real interaction must not be interrupted by the ambient loop: the
        # running mood is dropped (its →idle would stomp the real emotion) and
        # the pending schedule is cleared so the calm period restarts.
        self._set_state(
            mood_until=self.now + 2.0,
            next_mood_at=self.now + 30.0,
            sad_until=self.now + 1.0,
        )
        with patch.object(vw.time, "monotonic", return_value=self.now + 0.1):
            vw.note_interaction()
        self.assertIsNone(vw._mood_until)
        self.assertIsNone(vw._next_mood_at)
        self.assertIsNone(vw._sad_until)
        self.assertEqual(vw._last_interaction_ts, self.now + 0.1)

    # --------------------------------------------------- day/night weighting

    def test_weight_tables_cover_all_allowed_moods_and_sum_to_one(self) -> None:
        # _pick_ambient_mood indexes the table by mood name, so one typo in a
        # key would KeyError on every mood tick (swallowed by the loop's guard)
        # and one bad weight would skew the ambience silently.
        for table, label in (
            (vw._DAY_MOOD_WEIGHTS, "day"),
            (vw._NIGHT_MOOD_WEIGHTS, "night"),
        ):
            with self.subTest(table=label):
                self.assertEqual(set(table), set(vw._AMBIENT_MOODS))
                self.assertTrue(all(weight > 0 for weight in table.values()))
                self.assertAlmostEqual(sum(table.values()), 1.0, places=9)

    def test_mood_weights_day_night_and_boundaries(self) -> None:
        # Day is [8, 21) local: hour 7 still night, 8 opens the day, 20 is the
        # last day hour, 21 opens the night, midnight stays night.
        for hour, expected in (
            (0, vw._NIGHT_MOOD_WEIGHTS),
            (7, vw._NIGHT_MOOD_WEIGHTS),
            (8, vw._DAY_MOOD_WEIGHTS),
            (12, vw._DAY_MOOD_WEIGHTS),
            (20, vw._DAY_MOOD_WEIGHTS),
            (21, vw._NIGHT_MOOD_WEIGHTS),
            (23, vw._NIGHT_MOOD_WEIGHTS),
        ):
            with self.subTest(hour=hour):
                self.assertIs(vw._mood_weights(hour), expected)

    def test_night_table_is_calmer_and_day_table_livelier(self) -> None:
        # The semantic contract behind the numbers: quiet-attention moods lead
        # at night, expressive moods lead by day. Holds even if the exact
        # weights are retuned later.
        calm = {"thinking", "listening"}

        def lively_share(table: dict[str, float]) -> float:
            return sum(weight for mood, weight in table.items() if mood not in calm)

        def calm_share(table: dict[str, float]) -> float:
            return sum(weight for mood, weight in table.items() if mood in calm)

        self.assertGreater(lively_share(vw._DAY_MOOD_WEIGHTS), lively_share(vw._NIGHT_MOOD_WEIGHTS))
        self.assertGreater(calm_share(vw._NIGHT_MOOD_WEIGHTS), calm_share(vw._DAY_MOOD_WEIGHTS))

    def test_pick_ambient_mood_uses_day_weights_by_injected_hour(self) -> None:
        captured: dict[str, list] = {}

        def fake_choices(population, weights=None, **_kwargs):
            captured["population"] = list(population)
            captured["weights"] = list(weights or [])
            return ["love"]

        with patch.object(vw, "_local_hour", return_value=12), patch.object(
            vw.random, "choices", side_effect=fake_choices
        ):
            self.assertEqual(vw._pick_ambient_mood(), "love")

        self.assertEqual(captured["population"], vw._AMBIENT_MOODS)
        self.assertEqual(
            captured["weights"],
            [vw._DAY_MOOD_WEIGHTS[name] for name in vw._AMBIENT_MOODS],
        )

    def test_pick_ambient_mood_uses_night_weights_by_injected_hour(self) -> None:
        captured: dict[str, list] = {}

        def fake_choices(population, weights=None, **_kwargs):
            captured["population"] = list(population)
            captured["weights"] = list(weights or [])
            return ["thinking"]

        with patch.object(vw, "_local_hour", return_value=23), patch.object(
            vw.random, "choices", side_effect=fake_choices
        ):
            self.assertEqual(vw._pick_ambient_mood(), "thinking")

        self.assertEqual(
            captured["weights"],
            [vw._NIGHT_MOOD_WEIGHTS[name] for name in vw._AMBIENT_MOODS],
        )

    def test_spontaneous_mood_fires_through_weighted_picker(self) -> None:
        # Wiring proof: the due-mood path routes through _pick_ambient_mood
        # (injected night hour) instead of the old flat random.choice.
        self._set_state(next_mood_at=self.now - 0.5)
        with patch.object(vw, "_local_hour", return_value=23), patch.object(
            vw.random, "choices", return_value=["surprised"]
        ):
            vw._check_spontaneous_mood(self.now)
        self.assertEqual(self.emotions, ["surprised"])
        self.assertEqual(vw._mood_until, self.now + float(vw.MOOD_DURATION_S))


if __name__ == "__main__":
    unittest.main()
