"""
The screen's pure JS modules, run through node.

wake.js and reply.js are kept free of any DOM exactly so their rules can be
checked without a browser or a microphone. The screen itself is vanilla ES
modules with no toolchain, so these run the real files, not a copy.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from unittest.mock import patch

import pytest

import app_config
import events

SCREEN = Path(app_config.STATIC_DIR) / "screen"
NODE = shutil.which("node")

needs_node = pytest.mark.skipif(NODE is None, reason="node is not installed")


def _run(module: str, body: str):
    """Import a screen module in node, run `body`, return what it prints as JSON."""
    url = (SCREEN / module).as_uri()
    script = f"import * as m from {json.dumps(url)};\n{body}"
    out = subprocess.run(
        [NODE, "--input-type=module", "-e", script],
        capture_output=True, text=True, timeout=20, check=True,
    )
    return json.loads(out.stdout)


def _wake(phrases: list[str], word: str = "клод", armed: bool = False) -> list[dict]:
    return _run("wake.js", f"console.log(JSON.stringify({json.dumps(phrases)}.map("
                           f"p => m.parseWake(p, {json.dumps(word)}, {json.dumps(armed)}))));")


@needs_node
class TestWakeWord:
    @pytest.mark.parametrize("phrase", [
        "Клод, котра година?",
        "клоде котра година",          # vocative, the natural way to call someone
        "Клоду котра година",
        "Claude котра година",         # recognition switched script
        "хей клауд котра година",
        "гей Cloud котра година",
        "а скажи клод котра година",
        "котра година, Клод?",         # the name at the very end
    ])
    def test_recognition_spellings_of_the_name_wake_it(self, phrase: str):
        [result] = _wake([phrase])
        assert result == {"action": "send", "text": "котра година"}

    @pytest.mark.parametrize("phrase", [
        "відкрий код",                    # a dropped sound is an everyday word
        "кіт спить на дивані",
        "я вчора говорив з клодом про це",  # a mention, not an address
        "клодтест упав",
        "",
    ])
    def test_near_misses_and_mentions_do_not(self, phrase: str):
        [result] = _wake([phrase])
        assert result["action"] == "ignore"

    def test_the_name_alone_arms(self):
        assert _wake(["Клод", "клот!"]) == [{"action": "arm", "text": ""}] * 2

    def test_stop_with_the_name_is_a_barge_in(self):
        assert _wake(["Клод, стоп", "клод тихо", "Claude, stop"]) == [{"action": "stop", "text": ""}] * 3

    def test_armed_takes_the_whole_phrase_and_drops_the_name(self):
        assert _wake(["увімкни музику", "Клод, а ще?", "стоп"], armed=True) == [
            {"action": "send", "text": "увімкни музику"},
            {"action": "send", "text": "а ще"},
            {"action": "stop", "text": ""},
        ]

    def test_english_name(self):
        assert _wake(["hey Claude, what time is it?"], word="claude") == [
            {"action": "send", "text": "what time is it"},
        ]


_TRACE = """
const log = [];
const hooks = {};
for (const k of ["onOpen", "onAppend", "onSet", "onClose", "onRemove"]) {
  hooks[k] = (b) => log.push([k, b.note ? "note" : "answer", b.text]);
}
const turn = new m.ReplyTurn(hooks);
"""


@needs_node
class TestReplyTurn:
    def test_narration_then_answer_bubbles_in_order(self):
        result = _run("reply.js", _TRACE + """
turn.note("n1", ["Секунду, гляну"]);
turn.work();
turn.delta("Знайшов!"); turn.split();
turn.delta("Рейс о "); turn.delta("9:10."); turn.split();
turn.delta("Брати?");
const done = turn.done(["Знайшов!", "Рейс о 9:10.", "Брати?"]);
console.log(JSON.stringify({texts: turn.texts(), replaced: done.replaced.length,
  closed: turn.bubbles.every(b => b.closed), notes: turn.bubbles.map(b => b.note)}));
""")
        assert result == {
            "texts": ["Секунду, гляну", "Знайшов!", "Рейс о 9:10.", "Брати?"],
            "replaced": 0,
            "closed": True,
            "notes": [True, False, False, False],
        }

    def test_each_bubble_closes_before_the_next_opens(self):
        # Speech is fed a whole bubble on close; if the next bubble opened
        # first, its words could be read before the end of the previous one.
        result = _run("reply.js", _TRACE + """
turn.note("n1", ["Гляну"]);
turn.delta("Один."); turn.split(); turn.delta("Два.");
turn.closeAll();
console.log(JSON.stringify(log.filter(e => e[0] === "onOpen" || e[0] === "onClose").map(e => e[0] + ":" + e[2])));
""")
        assert result == [
            "onOpen:", "onClose:Гляну",
            "onOpen:", "onClose:Один.",
            "onOpen:", "onClose:Два.",
        ]

    def test_note_snapshots_update_in_place(self):
        result = _run("reply.js", _TRACE + """
turn.note("n1", ["Зараз"]);
turn.note("n1", ["Зараз гляну."]);
turn.note("n1", ["Зараз гляну.", "Ще секунду"]);
console.log(JSON.stringify({texts: turn.texts(), closed: turn.bubbles.map(b => b.closed)}));
""")
        assert result == {"texts": ["Зараз гляну.", "Ще секунду"], "closed": [True, False]}

    def test_done_replaces_a_stream_that_leaked_an_error(self):
        result = _run("reply.js", _TRACE + """
turn.delta("Error: internal error");
const done = turn.done(["Привіт!", "Як ти?"]);
console.log(JSON.stringify({texts: turn.texts(), replaced: done.replaced.map(b => b.text),
  removed: log.filter(e => e[0] === "onRemove").length}));
""")
        assert result == {
            "texts": ["Привіт!", "Як ти?"],
            "replaced": ["Error: internal error"],
            "removed": 1,
        }

    def test_whitespace_differences_are_not_a_replacement(self):
        result = _run("reply.js", _TRACE + """
turn.delta("Знайшов!  "); turn.split(); turn.delta("\\nРейс о 9:10.");
console.log(JSON.stringify(turn.done(["Знайшов!", "Рейс о 9:10."]).replaced.length));
""")
        assert result == 0


class TestReplyEventBubbles:
    """The screen speaks a reply message by message, so it needs the split."""

    def test_reply_event_carries_bubbles(self):
        with patch.object(events, "publish") as publish:
            events.publish_reply("Один\n\nДва", "happy", ["Один", "Два"])
        event = publish.call_args.args[0]
        assert event["bubbles"] == ["Один", "Два"]
        assert event["text"] == "Один\n\nДва"

    def test_without_bubbles_the_event_is_unchanged(self):
        with patch.object(events, "publish") as publish:
            events.publish_reply("Один", "happy")
        assert "bubbles" not in publish.call_args.args[0]

    def test_bubbles_share_the_text_ceiling(self):
        with patch.object(events, "publish") as publish:
            events.publish_reply("x", "happy", ["a" * 15000, "b" * 5000, "c" * 10])
        bubbles = publish.call_args.args[0]["bubbles"]
        assert sum(map(len, bubbles)) <= 16000


_TYPE = """
const type = (state, keys) => keys.reduce((s, k) => m.applyKey(s, k), state);
const letters = (word) => Array.from(word);
"""


@needs_node
class TestKeyboard:
    """keyboard.js: the on-screen keyboard's layouts and text logic."""

    def test_every_ukrainian_letter_is_reachable(self):
        # ґ lives on a long press of г; the rest must be plain keys, the
        # apostrophe included — it is in half of everyday words.
        result = _run("keyboard.js", """
const keys = new Set(m.LAYOUTS.uk.flat());
const alts = new Set(Object.values(m.ALTERNATES));
const abc = "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя";
console.log(JSON.stringify({
  missing: Array.from(abc).filter(c => !keys.has(c) && !alts.has(c)),
  apostrophe: keys.has("ʼ"),
  widest: Math.max(...Object.values(m.LAYOUTS).flat().map(r => r.length)),
}));
""")
        assert result["missing"] == []
        assert result["apostrophe"] is True
        # 12 keys across 320 px keep each key at the 24 px touch minimum
        assert result["widest"] <= 12

    def test_sentences_start_with_a_capital(self):
        result = _run("keyboard.js", _TYPE + """
let s = type(m.initialState(), letters("привіт"));
const first = s.text;
s = type(s, [m.K.SPACE, m.K.SPACE, ...letters("як")]);   // double space ends the sentence
console.log(JSON.stringify([first, s.text]));
""")
        assert result == ["Привіт", "Привіт. Як"]

    def test_search_fields_stay_lowercase(self):
        result = _run("keyboard.js", _TYPE + """
console.log(JSON.stringify(type(m.initialState({autocap: false, lang: "en"}), letters("lofi")).text));
""")
        assert result == "lofi"

    def test_shift_is_one_shot_then_caps_lock_then_off(self):
        result = _run("keyboard.js", _TYPE + """
const base = m.initialState({autocap: false});
const once = type(base, [m.K.SHIFT, "к", "о"]).text;
const lock = type(base, [m.K.SHIFT, m.K.SHIFT, "к", "о"]).text;
const off = type(base, [m.K.SHIFT, m.K.SHIFT, m.K.SHIFT, "к"]).text;
console.log(JSON.stringify([once, lock, off]));
""")
        assert result == ["Ко", "КО", "к"]

    def test_backspace_and_emoji_safe_deletion(self):
        result = _run("keyboard.js", _TYPE + """
let s = m.initialState({text: "ok 👍", autocap: false});
s = m.applyKey(s, m.K.BACK);
const one = s.text;
s = type(s, [m.K.BACK, m.K.BACK, m.K.BACK]);
console.log(JSON.stringify({one, empty: s.text, capitalAgain: m.initialState().shift}));
""")
        assert result == {"one": "ok ", "empty": "", "capitalAgain": True}

    def test_layers_and_languages(self):
        result = _run("keyboard.js", _TYPE + """
let s = m.initialState({lang: "uk"});
const path = [s.layer];
s = m.applyKey(s, m.K.LANG); path.push(s.layer);
s = m.applyKey(s, m.K.SYM); path.push(s.layer);
s = m.applyKey(s, "7");
s = m.applyKey(s, m.K.ABC); path.push(s.layer);
s = m.applyKey(s, m.K.LANG); path.push(s.layer);
console.log(JSON.stringify({path, text: s.text}));
""")
        assert result == {"path": ["uk", "en", "sym", "en", "uk"], "text": "7"}

    def test_enter_finishes_without_typing_anything(self):
        result = _run("keyboard.js", _TYPE + """
const s = type(m.initialState({autocap: false}), ["т", "а", "к", m.K.ENTER]);
console.log(JSON.stringify({done: s.done, text: s.text}));
""")
        assert result == {"done": True, "text": "так"}


@needs_node
class TestWatchDrawer:
    """drawer.js: the honeycomb layout and the fisheye."""

    def test_honeycomb_packs_without_overlap_and_starts_in_the_middle(self):
        result = _run("drawer.js", """
const pts = m.honeycomb(30);
let closest = Infinity;
for (let i = 0; i < pts.length; i++) for (let j = i + 1; j < pts.length; j++) {
  closest = Math.min(closest, Math.hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y));
}
const xs = pts.map(p => p.x), ys = pts.map(p => p.y);
console.log(JSON.stringify({n: pts.length, first: pts[0], closest, pitch: m.PITCH,
  width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys)}));
""")
        assert result["n"] == 30 and result["first"] == {"x": 0, "y": 0}
        assert result["closest"] >= result["pitch"] - 0.01
        # Filled as wide as the 4:3 screen, not as a round blob
        assert result["width"] > result["height"]

    def test_fisheye_keeps_the_middle_and_shrinks_the_rim(self):
        result = _run("drawer.js", """
const along = [0, 40, 80, 120, 160, 220].map(x => m.fisheye(x, 0, 320, 240));
console.log(JSON.stringify({scales: along.map(f => f.scale), xs: along.map(f => f.x)}));
""")
        scales, xs = result["scales"], result["xs"]
        assert scales[0] == 1 and scales[1] == 1
        assert all(a >= b for a, b in zip(scales, scales[1:])) and scales[-1] >= 0.3
        # Pulled in, but order is kept: the rim never folds over the middle
        assert all(a < b for a, b in zip(xs, xs[1:])) and xs[-1] < 220

    def test_rubber_band_and_nearest(self):
        result = _run("drawer.js", """
console.log(JSON.stringify([m.rubber(5, 0, 10), m.rubber(-10, 0, 10), m.rubber(30, 0, 10),
  m.nearest([{x: 0, y: 0}, {x: 54, y: 0}], {x: 40, y: 3})]));
""")
        assert result == [5, -4.5, 19, 1]


def _screen_ids() -> list[str]:
    import re
    source = (SCREEN / "screen.js").read_text("utf-8")
    block = source[source.index("const SCREENS = ["):]
    block = block[:block.index("];")]
    return re.findall(r'\{ id: "([a-z-]+)"', block)


def _package_ids() -> list[str]:
    root = Path(app_config.BASE_DIR) / "store" / "packages"
    return sorted(p.parent.name for p in root.glob("*/package.json"))


@needs_node
class TestAppIcons:
    """app-icons.js: every app has its own drawn icon, and every icon is valid SVG."""

    def test_every_screen_and_package_has_its_own_design(self):
        apps = [{"id": i} for i in _screen_ids()] + [{"id": "app:" + p, "pkg": p} for p in _package_ids()]
        keys = _run("app-icons.js", f"console.log(JSON.stringify({json.dumps(apps)}.map(a => m.appIconKey(a))));")
        missing = [a["id"] for a, k in zip(apps, keys) if k == "letter"]
        assert missing == [], f"no icon drawn for {missing}"
        assert len(apps) >= 30

    def test_every_design_renders_as_well_formed_svg(self):
        import xml.etree.ElementTree as ET
        svgs = _run("app-icons.js", """
console.log(JSON.stringify(Object.keys(m.DESIGNS).map(k => [k, m.appIconSvg(k, {label: "<Zed & co>"})])));
""")
        ids = set()
        for key, svg in svgs:
            root = ET.fromstring(svg)                      # raises on broken markup
            assert root.get("viewBox") == "0 0 48 48", key
            gradient = root.find(".//{http://www.w3.org/2000/svg}linearGradient")
            ids.add(gradient.get("id"))
            assert "NaN" not in svg and "undefined" not in svg, key
        # SVG ids are page-global: each icon needs its own gradient
        assert len(ids) == len(svgs)

    def test_unknown_apps_fall_back_to_the_icon_name_then_a_letter(self):
        result = _run("app-icons.js", """
console.log(JSON.stringify([
  m.appIconKey({id: "app:someone-elses", pkg: "someone-elses", icon: "dice"}),
  m.appIconKey({id: "app:x", pkg: "x", icon: "no-such-icon"}),
  m.appIconSvg("letter", {label: "ёжик"}).includes(">Ё<"),
]));
""")
        assert result == ["dice", "letter", True]


@needs_node
class TestWeatherPictures:
    """weather-icons.js: every WMO code gets a picture and a sky."""

    def test_every_wmo_code_maps_to_a_drawn_kind(self):
        from tools.weather import _WMO_MAP
        codes = sorted(_WMO_MAP)
        result = _run("weather-icons.js", f"""
const codes = {json.dumps(codes)};
console.log(JSON.stringify({{kinds: codes.map(c => m.wxKind(c)), known: m.KINDS,
  samples: [m.wxKind(0), m.wxKind(3), m.wxKind(63), m.wxKind(75), m.wxKind(95), m.wxKind(null)]}}));
""")
        assert set(result["kinds"]) <= set(result["known"])
        assert set(result["kinds"]) == set(result["known"])            # no kind drawn for nothing
        assert result["samples"] == ["clear", "cloudy", "rain", "snow", "storm", "cloudy"]

    def test_pictures_are_well_formed_and_ids_never_clash(self):
        import xml.etree.ElementTree as ET
        svgs = _run("weather-icons.js", """
const out = [];
for (const night of [false, true]) for (const k of m.KINDS) out.push(m.wxIconSvg(k, {night}));
out.push(m.windArrowSvg(90));
console.log(JSON.stringify(out));
""")
        ids = []
        for svg in svgs:
            root = ET.fromstring(svg)
            assert "NaN" not in svg and "undefined" not in svg
            ids += [el.get("id") for el in root.iter() if el.get("id")]
        assert len(ids) == len(set(ids))

    def test_every_sky_has_a_darker_night(self):
        result = _run("weather-icons.js", """
const lum = (hex) => { const n = parseInt(hex.slice(1), 16); return (n >> 16) + ((n >> 8) & 255) + (n & 255); };
console.log(JSON.stringify(m.KINDS.filter(k => !(lum(m.wxSky(k, true)[0]) < lum(m.wxSky(k, false)[0])))));
""")
        assert result == []

    def test_every_kind_is_worded_in_both_languages(self):
        text = (SCREEN / "i18n.js").read_text("utf-8")
        kinds = _run("weather-icons.js", "console.log(JSON.stringify(m.KINDS));")
        for kind in kinds:
            assert text.count(f'"wx.c.{kind}"') == 2, kind


@needs_node
class TestGestureNav:
    """gesture-nav.js: Android's edges over an open app."""

    def test_swipe_up_from_the_pill_is_home(self):
        result = _run("gesture-nav.js", """
console.log(JSON.stringify([
  m.classify("bottom", 0, -60),          // a clear pull up
  m.classify("bottom", 3, -20, 120),     // a short flick
  m.classify("bottom", 0, -20, 400),     // short and slow: nothing
  m.classify("bottom", 60, -30),         // mostly sideways: nothing
  m.classify("bottom", 0, 30),           // down: nothing
]));
""")
        assert result == ["home", "home", None, None, None]

    def test_swipe_in_from_either_edge_is_back(self):
        result = _run("gesture-nav.js", """
console.log(JSON.stringify([
  m.classify("left", 50, 4),
  m.classify("right", -50, -4),
  m.classify("left", -50, 0),            // outwards: nothing
  m.classify("right", -20, 40),          // mostly vertical: nothing
  m.classify("left", 16, 0, 100),        // flick
]));
""")
        assert result == ["back", "back", None, None, "back"]

    def test_progress_is_clamped(self):
        result = _run("gesture-nav.js", """
console.log(JSON.stringify([m.progress("bottom", 0, 20), m.progress("bottom", 0, -500), m.progress("left", 24, 0)]));
""")
        assert result[0] == 0 and result[1] == 1 and 0 < result[2] < 1

    def test_the_strips_leave_the_app_most_of_the_screen(self):
        result = _run("gesture-nav.js", "console.log(JSON.stringify([m.EDGE, m.BOTTOM]));")
        edge, bottom = result
        # 320×240: the app keeps at least 90% of the width and 90% of the height
        assert 2 * edge <= 32 and bottom <= 24


@needs_node
class TestInterfaceStyles:
    """Two interface styles over one screen: "Material You" (default) and "Deep UI"."""

    def test_themed_icons_use_only_their_three_tones(self):
        import re
        import xml.etree.ElementTree as ET
        result = _run("app-icons.js", """
const out = {};
for (const theme of ["dark", "light"]) {
  const tones = m.themedColors("#d98263", theme);
  out[theme] = {tones, svgs: Object.keys(m.DESIGNS).map(k => [k, m.appIconSvg(k, {label: "Z", themed: tones})])};
}
console.log(JSON.stringify(out));
""")
        for theme, data in result.items():
            tones = {c.lower() for c in data["tones"].values()}
            for key, svg in data["svgs"]:
                ET.fromstring(svg)
                assert "url(#" not in svg, key                   # a flat tonal disc, no gradient
                used = {c.lower() for c in re.findall(r'(?:fill|stroke)="(#[0-9a-fA-F]{3,6})"', svg)}
                assert used <= tones, (theme, key, used - tones)

    def test_themed_tones_follow_the_theme(self):
        result = _run("app-icons.js", """
const lum = (h) => { const n = parseInt(h.slice(1), 16); return (n >> 16) + ((n >> 8) & 255) + (n & 255); };
const d = m.themedColors("#5fb0a8", "dark"), l = m.themedColors("#5fb0a8", "light");
console.log(JSON.stringify([lum(d.bg) < lum(d.fg), lum(l.bg) > lum(l.fg), m.mixHex("#000000", "#ffffff", 0.5)]));
""")
        assert result == [True, True, "#808080"]

    def test_deep_css_never_touches_the_material_style(self):
        # Every rule in deep.css must be scoped to :root[data-ui="deep"];
        # an unscoped one would quietly restyle the default look.
        import re
        css = (SCREEN / "deep.css").read_text("utf-8")
        css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
        selectors = re.findall(r"([^{}]+)\{", css)
        unscoped = []
        for group in selectors:
            for sel in group.split(","):
                sel = sel.strip()
                if not sel or sel.startswith("@") or sel in ("from", "to") or sel.endswith("%"):
                    continue
                if not sel.startswith(':root[data-ui="deep"]'):
                    unscoped.append(sel)
        assert unscoped == []

    def test_styles_are_worded_and_linked(self):
        i18n = (SCREEN / "i18n.js").read_text("utf-8")
        for key in ("set.uiStyle", "uistyle.material", "uistyle.deep"):
            assert i18n.count(f'"{key}"') == 2, key
        html = (SCREEN / "index.html").read_text("utf-8")
        assert 'href="/static/screen/deep.css"' in html and 'data-ui="material"' in html
