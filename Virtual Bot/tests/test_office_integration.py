"""The living office is a presentation layer over the home tile. It must never
delay a chat, hide the caption, steal a tap or a swipe, hold the display on
another tile, or take the face down with it. These run the real screen.js
functions in node with only their environment stubbed, like the voice reviews.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest


SCREEN = Path(__file__).resolve().parents[1] / "static" / "screen"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="node is not installed")


def run_screen(body: str) -> dict:
    """Extract production functions from screen.js; stub only their environment."""
    script = r"""
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source = readFileSync(SCREEN_SOURCE, 'utf8');
const extract = name => {
  const match = source.match(new RegExp('^(?:async )?function ' + name + '\\([^\\n]*\\) \\{[\\s\\S]*?^\\}', 'm'));
  assert.ok(match, 'Missing production function: ' + name);
  return match[0];
};
const constant = name => {
  const match = source.match(new RegExp('^const ' + name + ' = .*$', 'm'));
  assert.ok(match, 'Missing production constant: ' + name);
  return match[0];
};
// An office double that only remembers what the screen asked of it.
const recorder = log => new Proxy({}, {get: (_, method) => (...args) => { log.push([method, ...args]); }});
const classes = () => ({set: new Set(), add(c) { this.set.add(c); }, remove(c) { this.set.delete(c); }, contains(c) { return this.set.has(c); }});
const el = () => ({classList: classes()});
function environment(extra = {}) {
  const ctx = vm.createContext({
    calls: [], timers: new Map(), nextTimer: 0, t: key => key, console: {error() {}},
    ...extra,
  });
  ctx.setTimeout = (callback, ms) => { const id = ++ctx.nextTimer; ctx.timers.set(id, {callback, ms}); return id; };
  ctx.clearTimeout = id => ctx.timers.delete(id);
  if (!('office' in extra)) ctx.office = recorder(ctx.calls);
  return ctx;
}
const install = (ctx, names, constants = []) =>
  vm.runInContext([...constants.map(constant), ...names.map(extract)].join('\n'), ctx);
BODY
"""
    script = script.replace("SCREEN_SOURCE", json.dumps(str(SCREEN / "screen.js"))).replace("BODY", body)
    result = subprocess.run(
        [NODE, "--input-type=module"], input=script, capture_output=True,
        text=True, timeout=15, check=False,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


def test_listening_face_holds_while_the_microphone_is_open():
    # Routing the mic through setEmotion (so the office hears it too) must not
    # inherit its 15 s beat: the crab used to drop to "idle" mid-sentence.
    result = run_screen("""
const emotions = [];
const ctx = environment({
  crab: {setEmotion: e => emotions.push(e), setAudioLevel() {}},
  asleep: false, emotionTimer: 0, listening: false, voiceMode: 'open', wakeArmed: false,
  micButtons: [], micLabel: {textContent: ''}, micStateLabel: () => 'label', warmWakeReplies() {},
  voiceAudio: {wakeCueActive: false}, speechReset() {}, wakeCueCache: {cancel() {}}, wakeCuePendingFinal: false,
  meter: null, faceCaption: {classList: {contains: () => false}}, hideCaption() {}, wake() {},
});
install(ctx, ['setEmotion', 'setListening']);
ctx.setListening(true);
const listening = {emotion: emotions.at(-1), office: ctx.calls.at(-1), pending: ctx.timers.size};
ctx.setEmotion('speaking');
const beat = ctx.timers.size;
ctx.setListening(true);                       // the mic is still open: no beat may end it
const held = ctx.timers.size;
ctx.setListening(false);
console.log(JSON.stringify({listening, beat, held, last: emotions.at(-1), pendingAtEnd: ctx.timers.size}));
""")
    assert result["listening"] == {"emotion": "listening", "office": ["setEmotion", "listening"], "pending": 0}
    assert result["beat"] == 1
    assert result["held"] == 0
    assert result["last"] == "idle" and result["pendingAtEnd"] == 0


def test_server_activity_wakes_on_change_only_and_normalizes_the_payload():
    # The server resends its snapshot every quiet 15 s. Waking on each one
    # would reset the idle timer forever, so the display never came home.
    result = run_screen("""
const wakes = [];
const ctx = environment({officeActivity: {activity: 'idle', active: false}, wake: () => wakes.push(1)});
install(ctx, ['onOfficeActivity'], ['OFFICE_ACTIVITIES']);
const feed = ev => { ctx.onOfficeActivity(ev); return [ctx.officeActivity.activity, ctx.officeActivity.active, wakes.length]; };
const out = [
  feed({type: 'office_activity', activity: 'work', active: true}),
  feed({type: 'office_activity', activity: 'work', active: true}),   // the 15 s repair
  feed({type: 'office_activity', activity: 'read', active: true}),   // another tool, same job
  feed({type: 'office_activity', activity: 'idle', active: false}),
  feed({type: 'office_activity', activity: 'idle', active: false}),  // quiet repair while idle
  feed({type: 'office_activity', activity: 'help', active: false}),
  feed({type: 'office_activity', activity: 'juggling', active: true}),
  feed({type: 'office_activity', activity: 'juggling', active: false}),
  feed({type: 'office_activity', activity: 'chat'}),                 // no "active" at all
];
console.log(JSON.stringify({out, office: ctx.calls}));
""")
    assert result["out"] == [
        ["work", True, 1], ["work", True, 1], ["read", True, 1],
        ["idle", False, 2], ["idle", False, 2], ["help", False, 3],
        ["work", True, 4], ["idle", False, 5], ["chat", False, 5],
    ]
    # The office receives the contract shape, never the raw SSE event
    assert all(call[0] == "setActivity" and set(call[1]) == {"activity", "active"} for call in result["office"])
    assert len(result["office"]) == 9


def test_link_loss_forgets_the_activity_so_the_display_can_sleep():
    result = run_screen("""
const wakes = [];
const ctx = environment({officeActivity: {activity: 'work', active: true}, linkAlive: true,
  wake: () => wakes.push(1), $: () => ({classList: {toggle() {}}}), setState() {}});
install(ctx, ['setLink', 'onOfficeActivity'], ['OFFICE_ACTIVITIES']);
ctx.setLink(false);
const lost = {state: ctx.officeActivity, wakes: wakes.length, calls: ctx.calls.slice()};
ctx.setLink(false);      // EventSource retries keep firing onerror
ctx.setLink(true);
console.log(JSON.stringify({lost, wakes: wakes.length, calls: ctx.calls}));
""")
    assert result["lost"] == {
        "state": {"activity": "idle", "active": False}, "wakes": 1,
        "calls": [["setConnected", False], ["setActivity", {"activity": "idle", "active": False}]],
    }
    assert result["wakes"] == 1
    assert result["calls"][2:] == [["setConnected", False], ["setConnected", True]]


def test_external_work_keeps_the_display_awake_but_not_on_another_tile():
    result = run_screen("""
const ctx = environment({asleep: false, crab: {setEmotion() {}}, applyDim() {}, idleTimer: 0, sleepTimer: 0,
  chatBusy: false, listening: false, osk: null, idleHomeMs: 20000, idleSleepMs: 180000,
  goHome() { ctx.calls.push(['goHome']); }, syncQuickButtons() {},
  officeActivity: {activity: 'work', active: true}});
install(ctx, ['wake', 'sleep']);
ctx.wake();
const working = [...ctx.timers.values()].map(x => x.ms);
ctx.sleep();                                   // a timer armed before the work fires now
const refused = {asleep: ctx.asleep, told: ctx.calls.some(c => c[0] === 'setSleeping')};
ctx.officeActivity = {activity: 'idle', active: false};
ctx.wake();
const idle = [...ctx.timers.values()].map(x => x.ms);
ctx.sleep();
const slept = {asleep: ctx.asleep, told: ctx.calls.find(c => c[0] === 'setSleeping'), home: ctx.calls.some(c => c[0] === 'goHome')};
ctx.wake();
console.log(JSON.stringify({working, refused, idle, slept, woke: ctx.calls.at(-1), asleep: ctx.asleep}));
""")
    assert result["working"] == [20000]            # home yes, sleep no
    assert result["refused"] == {"asleep": False, "told": False}
    assert result["idle"] == [20000, 180000]
    # The idle timer's sleep is automatic: the office starts a finite nap
    assert result["slept"] == {"asleep": True, "told": ["setSleeping", True, {"manual": False}], "home": True}
    assert result["woke"] == ["setSleeping", False] and result["asleep"] is False


def test_office_pauses_under_photos_shades_apps_other_tiles_and_hidden_tabs():
    result = run_screen("""
const host = {attrs: {}, toggleAttribute(name, on) { this.attrs[name] = on; }, setAttribute(name, v) { this.attrs[name] = v; }};
const face = {dataset: {tile: 'face'}, classList: classes()};
const clock = {dataset: {tile: 'clock'}, classList: classes()};
const classic = [];
const ctx = environment({homeView: 'office', tiles: [face, clock], tileIndex: 0, layer: null, appOpen: false,
  layerApp: {classList: {contains: () => ctx.appOpen}}, document: {hidden: false}, $: () => host,
  crab: {setVisible: on => classic.push(on)}});
install(ctx, ['syncOfficeVisibility']);
const probe = () => { ctx.calls.length = 0; ctx.syncOfficeVisibility(); return [ctx.calls[0][0], ctx.calls[0][1], host.attrs.inert, host.attrs['aria-hidden'], classic.at(-1)]; };
const out = {home: probe()};
face.classList.add('photo'); out.photo = probe(); face.classList.remove('photo');
ctx.tileIndex = 1; out.otherTile = probe(); ctx.tileIndex = 0;
ctx.layer = 'quick'; out.shade = probe(); ctx.layer = null;
ctx.appOpen = true; out.app = probe(); ctx.appOpen = false;
ctx.document.hidden = true; out.hiddenTab = probe(); ctx.document.hidden = false;
ctx.homeView = 'classic'; out.classic = probe();
face.classList.add('photo'); out.classicPhoto = probe(); face.classList.remove('photo');
ctx.layer = 'apps'; out.classicShade = probe(); ctx.layer = null;
ctx.homeView = 'office';
out.back = probe();
console.log(JSON.stringify(out));
""")
    # The last element is the classic crab's own loop: it runs only in classic
    # view on an uncovered face tile, never under the office.
    shown, hidden = ["setVisible", True, False, "false", False], ["setVisible", False, True, "true", False]
    assert result["home"] == shown and result["back"] == shown
    for case in ("photo", "otherTile", "shade", "app", "hiddenTab"):
        assert result[case] == hidden, case
    assert result["classic"] == ["setVisible", False, True, "true", True]
    assert result["classicPhoto"] == hidden and result["classicShade"] == hidden


def test_a_photo_frame_pauses_the_office_only_when_it_appears_or_goes():
    # Every caption calls showFacePhotos; the office must not be poked each time.
    result = run_screen("""
const ctx = environment({facePhotos: [], facePhotoIdx: 0, facePhoto: el(), faceHolder: el(), faceTile: el(),
  facePhotoImg: {removeAttribute() {}}, renderFacePhoto() {}, drawHolder() {},
  syncOfficeVisibility() { ctx.calls.push(['sync']); }});
install(ctx, ['showFacePhotos']);
const syncs = () => ctx.calls.filter(c => c[0] === 'sync').length;
const out = [];
ctx.showFacePhotos(['a.png']); out.push(syncs());
ctx.showFacePhotos(['b.png']); out.push(syncs());
ctx.showFacePhotos([]); out.push(syncs());
ctx.showFacePhotos([]); out.push(syncs());
ctx.showFacePhotos(null); out.push(syncs());
console.log(JSON.stringify({out, photo: ctx.faceTile.classList.contains('photo')}));
""")
    assert result == {"out": [1, 1, 2, 2, 2], "photo": False}


def test_chat_from_the_office_returns_home_before_sending():
    # The person left the office to type; Enter brings them back to watch the
    # bot work. The chat tile's own keyboard keeps its old behaviour.
    result = run_screen("""
const ctx = environment({chatBusy: false, chatTile: () => 3, kbLang: () => 'en', chatLive: {textContent: ''},
  goTile(i) { ctx.calls.push(['goTile', i]); }, sendChat(text, voice) { ctx.calls.push(['sendChat', text, voice]); },
  osk: {open(opts) { ctx.opts = opts; }}});
install(ctx, ['openChatKeyboard']);
ctx.openChatKeyboard({returnHome: true});
ctx.opts.onInput('hi'); ctx.opts.onDone('  hi there ');
const office = ctx.calls.splice(0);
ctx.openChatKeyboard();
ctx.opts.onDone('from the chat tile');
const plain = ctx.calls.splice(0);
ctx.openChatKeyboard({returnHome: true});
ctx.opts.onDone('   ');
const empty = ctx.calls.splice(0);
ctx.chatBusy = true;
ctx.openChatKeyboard({returnHome: true});
console.log(JSON.stringify({office, plain, empty, busy: ctx.calls, live: ctx.chatLive.textContent}));
""")
    assert result["office"] == [["goTile", 3], ["goTile", 0], ["sendChat", "hi there", False]]
    assert result["plain"] == [["goTile", 3], ["sendChat", "from the chat tile", False]]
    assert result["empty"] == [["goTile", 3]]
    assert result["busy"] == [] and result["live"] == ""


def test_office_controls_keep_their_taps_like_buttons_do():
    # The stage captures the pointer for a swipe, which retargets the click;
    # buttons were already exempt, and the office draws its own controls.
    result = run_screen("""
const ctx = environment({});
install(ctx, ['handlesOwnTap']);
const node = (...matches) => ({closest: sel => sel.split(',').map(s => s.trim()).some(s => matches.includes(s)) ? 'hit' : null});
console.log(JSON.stringify({
  button: ctx.handlesOwnTap(node('button')), office: ctx.handlesOwnTap(node('.office-home')),
  crab: ctx.handlesOwnTap(node('.crab')), none: ctx.handlesOwnTap(null), text: ctx.handlesOwnTap({}),
}));
""")
    assert result == {"button": True, "office": True, "crab": False, "none": False, "text": False}
    source = (SCREEN / "screen.js").read_text("utf-8")
    assert source.count("const ownTap = handlesOwnTap(e.target);") == 1
    assert source.count("if (!ownTap && !scroller) {") == 1


def test_a_broken_office_falls_back_to_the_classic_face_without_saving_it():
    result = run_screen("""
const ctx = environment({mountOffice() { throw new Error('canvas exploded'); }, $: () => ({}), office: null,
  homeView: 'office', document: {documentElement: {dataset: {home: 'office'}}}, writePref() { ctx.calls.push(['pref']); },
  syncOfficeVisibility() { ctx.calls.push(['sync']); }, applyDim() {}, HOME_VIEW_KEY: 'k',
  linkAlive: true, officeActivity: {activity: 'idle', active: false}, crab: {emotion: 'idle'}, reducedMotion: false, asleep: false,
  console: {error() { ctx.calls.push(['error']); }}});
install(ctx, ['mountHomeOffice', 'setHomeView']);
ctx.mountHomeOffice();
const failed = {home: ctx.homeView, attr: ctx.document.documentElement.dataset.home, office: ctx.office, calls: ctx.calls.slice()};
ctx.setHomeView('office');           // Settings or the switch: nothing to show
const refused = {home: ctx.homeView, calls: ctx.calls.length};
console.log(JSON.stringify({failed, refused}));
""")
    assert result["failed"] == {"home": "classic", "attr": "classic", "office": None, "calls": [["error"], ["sync"]]}
    assert result["refused"] == {"home": "classic", "calls": 2}


def test_a_mounted_office_is_primed_and_its_callbacks_reach_the_screen():
    result = run_screen("""
const mounted = [];
let callbacks = null;
const ctx = environment({mountOffice(host, cb) { callbacks = cb; return recorder(mounted); }, $: () => 'host', office: null,
  homeView: 'office', document: {documentElement: {dataset: {home: 'office'}}}, writePref(k, v) { ctx.calls.push(['pref', k, v]); },
  syncOfficeVisibility() { ctx.calls.push(['sync']); }, applyDim() {}, HOME_VIEW_KEY: 'k',
  linkAlive: true, officeActivity: {activity: 'read', active: true}, crab: {emotion: 'happy'}, reducedMotion: true, asleep: false,
  wake(o) { ctx.calls.push(o ? ['wake', o] : ['wake']); }, openChatKeyboard(o) { ctx.calls.push(['chat', o]); }, openSettings() { ctx.calls.push(['settings']); }});
install(ctx, ['mountHomeOffice', 'setHomeView']);
ctx.mountHomeOffice();
callbacks.onChat(); callbacks.onSettings(); callbacks.onInteract(); callbacks.onClassic();
console.log(JSON.stringify({mounted, calls: ctx.calls, home: ctx.homeView, attr: ctx.document.documentElement.dataset.home}));
""")
    assert result["mounted"] == [
        ["setConnected", True], ["setActivity", {"activity": "read", "active": True}], ["setEmotion", "happy"],
        ["setReducedMotion", True], ["setSleeping", False],
    ]
    # Chat wakes the display but leaves the scene napping until the message
    # is actually sent, so the visitor cue can play on send.
    assert result["calls"] == [
        ["sync"], ["wake", {"keepOfficeSleep": True}], ["chat", {"returnHome": True}], ["wake"], ["settings"], ["wake"],
        ["pref", "k", "classic"], ["sync"], ["wake"],
    ]
    assert result["home"] == "classic" and result["attr"] == "classic"


def test_home_view_strings_and_host_styles_are_wired():
    locale = (SCREEN / "i18n.js").read_text("utf-8")
    for key in ("home.office", "home.classic", "set.homeView", "set.homeView.hint"):
        assert locale.count(json.dumps(key)) == 2, key
    html = (SCREEN / "index.html").read_text("utf-8")
    assert 'id="officeHome" class="office-home"' in html
    assert 'id="homeOfficeSwitch"' in html and 'data-i18n="home.office"' in html
    assert 'data-home="office"' in html
    css = (SCREEN / "office-host.css").read_text("utf-8")
    # Its own stacking context keeps office z-indexes under the caption and mic
    assert ".office-home { position: absolute; inset: 0; z-index: 0; }" in css
    assert ':root[data-home="office"] .home-office-switch { display: none; }' in css
    assert ':root[data-home="classic"] .office-home { display: none; }' in css
    for state in ("captioned", "photo", "busy"):
        assert f".tile-face.{state} .home-office-switch" in css, state


def test_chat_wake_keeps_the_scene_napping_until_the_message_is_sent():
    # Opening the office Chat used to wake the napping crab before a word
    # was typed, so the send never showed the visitor cue. The display still
    # wakes (dim, timers), only the office is left asleep; every other wake
    # and both sleeps are unchanged.
    result = run_screen("""
const ctx = environment({asleep: true, crab: {setEmotion(e) { ctx.calls.push(['crab', e]); }}, applyDim() { ctx.calls.push(['dim']); },
  idleTimer: 0, sleepTimer: 0, chatBusy: false, listening: false, osk: null, idleHomeMs: 20000, idleSleepMs: 180000,
  goHome() {}, syncQuickButtons() {}, officeActivity: {activity: 'idle', active: false}});
install(ctx, ['wake', 'sleep']);
ctx.wake({keepOfficeSleep: true});
const kept = {asleep: ctx.asleep, calls: ctx.calls.slice(), timers: [...ctx.timers.values()].map(x => x.ms)};
ctx.asleep = true; ctx.calls.length = 0;
ctx.wake();
const woke = ctx.calls.slice();
ctx.calls.length = 0; ctx.sleep(); const automatic = ctx.calls.find(c => c[0] === 'setSleeping');
ctx.asleep = false; ctx.calls.length = 0; ctx.sleep(true); const manual = ctx.calls.find(c => c[0] === 'setSleeping');
console.log(JSON.stringify({kept, woke, automatic, manual}));
""")
    assert result["kept"] == {"asleep": False, "calls": [["crab", "idle"], ["dim"]], "timers": [20000, 180000]}
    assert result["woke"] == [["crab", "idle"], ["setSleeping", False], ["dim"]]
    assert result["automatic"] == ["setSleeping", True, {"manual": False}]
    assert result["manual"] == ["setSleeping", True, {"manual": True}]
