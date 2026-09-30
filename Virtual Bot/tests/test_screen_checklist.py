"""Exercise the checklist's real offline model through Node, without a browser.

Templates are additive and resets clear only completion: a daily helper must
never turn a person's custom list into a disposable preset.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

import app_config
import screen_store

PACKAGE = Path(__file__).resolve().parents[1] / "store" / "packages" / "daily-checklist"
NODE = shutil.which("node")
needs_node = pytest.mark.skipif(NODE is None, reason="node is not installed")


def _run(body: str) -> None:
    script = f"const assert = require('node:assert/strict');\nconst M = require({json.dumps(str(PACKAGE / 'model.js'))});\n{body}"
    run = subprocess.run([NODE, "-e", script], capture_output=True, text=True, timeout=15)
    assert run.returncode == 0, run.stderr or run.stdout


def _run_ui(body: str, saved: str = "null", storage_denied: bool = False) -> None:
    """Run the shipped UI and keyboard handlers against a small eventful DOM."""
    _run(f"""
      const fs = require('node:fs'), vm = require('node:vm');
      const html = fs.readFileSync({json.dumps(str(PACKAGE / 'index.html'))}, 'utf8');
      const elements = {{}}, nodes = [], messages = [], changes = [], records = new Map();
      const initial = {saved};
      if (initial) records.set(M.STORAGE_KEY, JSON.stringify(initial));
      let document;
      class UiEvent {{
        constructor(type, options = {{}}) {{ this.type = type; Object.assign(this, options); }}
        preventDefault() {{ this.defaultPrevented = true; }}
      }}
      class Element {{
        constructor(tag = 'div') {{
          this.tagName = tag.toUpperCase(); this.dataset = {{}}; this.attributes = {{}};
          this.children = []; this.listeners = {{}}; this.hidden = false; this.value = ''; this.scrollTop = 0;
          this.style = {{setProperty() {{}}}};
        }}
        set textContent(value) {{ this.text = value; this.children = []; }}
        get textContent() {{ return this.text || ''; }}
        setAttribute(key, value) {{ this.attributes[key] = String(value); }}
        getAttribute(key) {{ return this.attributes[key] ?? null; }}
        hasAttribute(key) {{ return key in this.attributes; }}
        appendChild(child) {{ this.children.push(child); return child; }}
        append(...children) {{ children.forEach(child => this.appendChild(child)); }}
        addEventListener(type, callback) {{ (this.listeners[type] ||= []).push(callback); }}
        dispatchEvent(event) {{
          event.target ||= this;
          for (const callback of this.listeners[event.type] || []) callback(event);
          if (event.bubbles && this !== document) document.dispatchEvent(event);
        }}
        focus() {{ document.activeElement = this; this.dispatchEvent(new UiEvent('focusin', {{bubbles: true}})); }}
        blur() {{ if (document.activeElement === this) document.activeElement = null; }}
        requestSubmit() {{ this.dispatchEvent(new UiEvent('submit')); }}
        querySelectorAll(tag) {{
          return this.children.flatMap(child => [
            ...(child.tagName.toLowerCase() === tag ? [child] : []), ...child.querySelectorAll(tag)
          ]);
        }}
      }}
      for (const match of html.split('<script>')[0].matchAll(/<([a-z]+)\\b([^>]*)>/g)) {{
        const el = new Element(match[1]);
        for (const attr of match[2].matchAll(/([a-z-]+)="([^"]*)"/g)) {{
          el.setAttribute(attr[1], attr[2]);
          if (attr[1] === 'id') elements[attr[2]] = el;
          if (attr[1] === 'type') el.type = attr[2];
          if (attr[1].startsWith('data-')) el.dataset[attr[1].slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = attr[2];
        }}
        el.hidden = /\\bhidden(?:\\s|$)/.test(match[2]);
        nodes.push(el);
      }}
      document = new Element('document');
      document.documentElement = {{}};
      document.getElementById = id => elements[id];
      document.createElement = tag => new Element(tag);
      document.createElementNS = (_, tag) => new Element(tag);
      document.querySelectorAll = selector => selector === 'input, textarea' ? nodes.filter(el =>
        ['INPUT', 'TEXTAREA'].includes(el.tagName)) : nodes.filter(el => el.hasAttribute(selector.slice(1, -1)));
      elements.taskTitle.form = elements.editorForm;
      const context = {{document, ChecklistModel: M, Event: UiEvent, KeyboardEvent: UiEvent,
        location: {{origin: 'http://localhost'}}, setTimeout: () => 1, clearTimeout() {{}},
        parent: {{postMessage: (message, origin) => messages.push({{message, origin}})}},
        BotApp: {{lang: 'en', onChange: callback => changes.push(callback)}}}};
      context.window = context;
      Object.defineProperty(context, 'localStorage', {{get() {{
        if ({str(storage_denied).lower()}) throw Error('denied');
        return {{getItem: key => records.get(key) ?? null, setItem: (key, value) => records.set(key, value)}};
      }}}});
      vm.createContext(context);
      const execute = code => vm.runInContext(code, context);
      const state = () => JSON.parse(execute('JSON.stringify(state)'));
      const click = id => elements[id].dispatchEvent(new UiEvent('click'));
      const reply = (data, overrides = {{}}) => context.dispatchEvent(new UiEvent('message', {{
        data: {{type: 'botKeyboardInput', ...data}}, source: context.parent, origin: context.location.origin, ...overrides
      }}));
      context.listeners = {{}};
      context.addEventListener = Element.prototype.addEventListener;
      context.dispatchEvent = event => {{ for (const callback of context.listeners[event.type] || []) callback(event); }};
      execute(html.match(/<script>([\\s\\S]*?)<\\/script>/)[1]);
      {body}
    """)


def test_package_installs_with_its_offline_model(tmp_path, monkeypatch):
    """The installed iframe must get the model too, not just an HTML shell."""
    shutil.copytree(PACKAGE, tmp_path / "packages" / PACKAGE.name)
    monkeypatch.setattr(app_config, "STORE_DIR", str(tmp_path))
    manifest = screen_store.load_manifest(PACKAGE.name)
    assert manifest["category"] == "tools"
    assert manifest["label"] == manifest["locales"]["en"]["label"]
    assert manifest["description"] == manifest["locales"]["en"]["description"]
    assert set(manifest["locales"]) == {"uk", "en"}
    screen_store.install(PACKAGE.name)
    installed = tmp_path / "installed" / "apps" / PACKAGE.name
    for name in ("package.json", "index.html", "model.js"):
        assert (installed / name).read_bytes() == (PACKAGE / name).read_bytes()


@needs_node
def test_custom_task_lifecycle_and_reset_preserve_titles():
    _run("""
      const empty = M.empty();
      const added = M.add(empty, '  Write\\n the report  ');
      assert.equal(added.error, null);
      assert.deepEqual(empty.tasks, []);
      let state = M.add(added.state, 'Reply to the team').state;
      const id = state.tasks[0].id;
      state = M.toggle(state, id).state;
      assert.deepEqual(M.progress(state), {done: 1, total: 2, ratio: 0.5});
      const edited = M.edit(state, id, 'Submit the report').state;
      assert.deepEqual(edited.tasks[0], {id, title: 'Submit the report', key: null, done: true});
      assert.equal(state.tasks[0].title, 'Write the report');
      const reset = M.reset(edited).state;
      assert.equal(edited.tasks[0].done, true);
      assert.deepEqual(reset.tasks.map(t => t.title), ['Submit the report', 'Reply to the team']);
      assert.deepEqual(M.progress(reset), {done: 0, total: 2, ratio: 0});
      assert.equal(M.remove(reset, id).state.tasks.length, 1);
      assert.deepEqual(M.progress(M.empty()), {done: 0, total: 0, ratio: 0});
    """)


@needs_node
def test_templates_merge_and_do_not_revert_edited_or_completed_tasks():
    _run("""
      let state = M.add(M.empty(), 'My own task').state;
      state = M.toggle(state, state.tasks[0].id).state;
      const custom = state.tasks[0];
      const morning = M.applyTemplate(state, 'morning');
      assert.equal(morning.added, 3);
      state = morning.state;
      const starterId = state.tasks[1].id;
      state = M.toggle(state, starterId).state;
      state = M.edit(state, starterId, 'My morning variation').state;
      const again = M.applyTemplate(state, 'morning');
      assert.equal(again.added, 0);
      assert.deepEqual(again.state, state);
      assert.equal(again.state.tasks[1].key, null);
      assert.equal(again.state.tasks[1].done, true);
      for (const name of ['work', 'evening']) state = M.applyTemplate(state, name).state;
      assert.equal(state.tasks.length, 10);
      assert.deepEqual(state.tasks[0], custom);
      assert.equal(new Set(state.tasks.map(t => t.id)).size, 10);
      assert.equal(M.decode(JSON.stringify(state)).status, 'ok');
      state = M.remove(state, starterId).state;
      assert.equal(M.applyTemplate(state, 'morning').added, 1);
      for (const name of ['unknown', '__proto__', 'constructor']) {
        const result = M.applyTemplate(state, name);
        assert.equal(result.error, 'unknownTemplate');
        assert.strictEqual(result.state, state);
      }
    """)


@needs_node
def test_task_and_title_limits_are_atomic_and_ids_do_not_collide():
    _run("""
      let state = M.empty();
      for (let n = 0; n < M.MAX_TASKS - 1; n++) state = M.add(state, 'Task ' + n).state;
      const before = JSON.stringify(state);
      const rejected = M.applyTemplate(state, 'evening');
      assert.equal(rejected.error, 'full');
      assert.strictEqual(rejected.state, state);
      assert.equal(JSON.stringify(state), before);
      state = M.add(state, 'x'.repeat(M.MAX_TITLE)).state;
      assert.equal(state.tasks.length, M.MAX_TASKS);
      assert.equal(M.add(state, 'One too many').error, 'full');
      assert.strictEqual(M.add(state, 'One too many').state, state);
      const id = state.tasks[4].id;
      state = M.remove(state, id).state;
      state = M.add(state, 'Replacement').state;
      assert.equal(new Set(state.tasks.map(t => t.id)).size, M.MAX_TASKS);
      for (const value of ['', '  ', '\\u0000\\u202e', null, {}, 12]) {
        assert.equal(M.add(state, value).error, 'emptyTitle');
        assert.equal(M.edit(state, id, value).error, 'emptyTitle');
      }
      assert.equal(M.edit(state, id, 'x'.repeat(M.MAX_TITLE + 1)).error, 'longTitle');
      assert.equal(M.add(M.empty(), 'x'.repeat(M.MAX_TITLE + 1)).error, 'longTitle');
      assert.equal(M.edit(state, 'not-there', 'Valid').error, 'missing');
    """)


@needs_node
def test_every_operation_leaves_input_objects_unchanged():
    _run("""
      let state = M.applyTemplate(M.add(M.empty(), 'Custom').state, 'work').state;
      state.tasks.forEach(Object.freeze);
      Object.freeze(state.tasks);
      Object.freeze(state);
      const before = JSON.stringify(state), id = state.tasks[0].id;
      M.add(state, 'Another'); M.edit(state, id, 'Edited'); M.toggle(state, id);
      M.remove(state, id); M.reset(state); M.applyTemplate(state, 'morning');
      assert.equal(JSON.stringify(state), before);
    """)


@needs_node
@pytest.mark.parametrize("raw", ["", "{", "null", "[]", "{}", '{"version":2,"tasks":[]}', '{"version":1,"tasks":{}}'])
def test_corrupt_or_unknown_storage_has_a_safe_empty_fallback(raw):
    _run(f"""
      const loaded = M.decode({json.dumps(raw)});
      assert.equal(loaded.status, 'corrupt');
      assert.deepEqual(loaded.state, M.empty());
      assert.equal(M.add(loaded.state, 'Still usable').state.tasks.length, 1);
    """)


@needs_node
def test_storage_repair_preserves_valid_rows_and_bounds_untrusted_data():
    _run("""
      const starter = M.applyTemplate(M.empty(), 'morning').state.tasks[0];
      const tasks = [
        {id: 'task-1', title: ' Keep\\n this ', key: null, done: true},
        null,
        {id: '../bad', title: 'Unsafe id', key: null, done: true},
        {id: 'task-1', title: 'Duplicate', key: null, done: true},
        {id: 'task-2', title: 'Keep unfinished', key: 'bogus', done: 'false'},
        {id: 'task-3', title: 'x'.repeat(M.MAX_TITLE + 1), key: null, done: false},
        starter,
        {id: 'fake', title: '', key: starter.key, done: false}
      ];
      const decoded = M.decode(JSON.stringify({version: 1, tasks}));
      assert.equal(decoded.status, 'recovered');
      assert.deepEqual(decoded.state.tasks.map(t => t.id), ['task-1', 'task-2', starter.id]);
      assert.equal(decoded.state.tasks[0].title, 'Keep this');
      assert.equal(decoded.state.tasks[0].done, true);
      assert.equal(decoded.state.tasks[1].done, false);
      assert.equal(decoded.state.tasks[1].key, null);
      const over = M.decode(JSON.stringify({version: 1, tasks: Array.from({length: 100}, (_, n) =>
        ({id: 'item-' + n, title: 'Task', key: null, done: false}))}));
      assert.equal(over.status, 'recovered');
      assert.equal(over.state.tasks.length, M.MAX_TASKS);
      assert.equal(M.decode(' '.repeat(M.MAX_STORAGE + 1)).status, 'corrupt');
    """)


@needs_node
def test_guarded_storage_roundtrip_and_read_write_failures():
    _run("""
      const records = new Map();
      const storage = {getItem: k => records.has(k) ? records.get(k) : null,
        setItem: (k, v) => records.set(k, v)};
      assert.deepEqual(M.load(storage), {state: M.empty(), status: 'ok'});
      let state = M.applyTemplate(M.add(M.empty(), '<img src=x onerror=bad()>').state, 'evening').state;
      state = M.toggle(state, state.tasks[0].id).state;
      assert.equal(M.save(storage, state), true);
      assert.deepEqual(M.load(storage), {state, status: 'ok'});
      assert.deepEqual([...records.keys()], [M.STORAGE_KEY]);
      for (const blocked of [null, {}, {getItem() {throw Error('blocked');}},
        {get getItem() {throw Error('denied');}}]) {
        assert.equal(M.load(blocked).status, 'unavailable');
        assert.equal(M.save(blocked, state), false);
      }
      const full = {getItem: storage.getItem, setItem() {throw Error('quota');}};
      assert.equal(M.save(full, state), false);
      assert.deepEqual(M.load(storage).state, state);
      assert.equal(M.save(storage, {version: 1, tasks: [{id: 'bad', title: '', done: false}]}), false);
      records.set(M.STORAGE_KEY, '{broken');
      assert.equal(M.load(storage).status, 'corrupt');
      assert.equal(records.get(M.STORAGE_KEY), '{broken'); // Loading never overwrites unreadable data.
    """)


@needs_node
def test_storage_repair_limits_valid_tasks_instead_of_raw_rows():
    # Invalid rows must not consume the capacity reserved for people's tasks.
    _run("""
      const tasks = [...Array(M.MAX_TASKS).fill(null),
        {id: 'custom', title: 'Keep this late row', key: null, done: true},
        ...M.applyTemplate(M.empty(), 'morning').state.tasks];
      const restored = M.decode(JSON.stringify({version: 1, tasks}));
      assert.equal(restored.status, 'recovered');
      assert.equal(restored.state.tasks.length, 4);
      assert.deepEqual(restored.state.tasks[0], tasks[M.MAX_TASKS]);
      const overflow = M.decode(JSON.stringify({version: 1, tasks: [...tasks,
        ...Array.from({length: M.MAX_TASKS}, (_, n) =>
          ({id: 'extra-' + n, title: 'Extra', key: null, done: false}))]}));
      assert.equal(overflow.state.tasks.length, M.MAX_TASKS);
      assert.equal(overflow.state.tasks[0].id, 'custom');
      assert.equal(new Set(overflow.state.tasks.map(t => t.id)).size, M.MAX_TASKS);
    """)


@needs_node
def test_storage_repair_keeps_a_custom_title_with_a_stale_starter_key():
    # A damaged translation key can be repaired without discarding custom text.
    _run("""
      const starter = M.applyTemplate(M.empty(), 'work').state.tasks[0];
      const custom = {...starter, title: 'My edited priority', done: true};
      const decoded = M.decode(JSON.stringify({version: 1, tasks: [custom]}));
      assert.equal(decoded.status, 'recovered');
      assert.deepEqual(decoded.state.tasks, [{...custom, key: null}]);
      const merged = M.applyTemplate(decoded.state, 'work');
      assert.deepEqual(merged.state.tasks[0], {...custom, key: null});
      assert.equal(merged.added, 2);
    """)


@needs_node
def test_saving_an_unchanged_starter_keeps_its_live_translation():
    # Merely visiting Edit must not pin a starter to the language used there.
    _run_ui("""
      const original = state().tasks[0];
      execute('openEditor(state.tasks[0])');
      const draft = elements.taskTitle.value;
      changes[0]({lang: 'uk'});
      assert.equal(elements.taskTitle.value, draft);
      elements.editorForm.requestSubmit();
      assert.deepEqual(state().tasks[0], original);
      assert.equal(elements.editorView.hidden, true);
      assert.equal(elements.tasks.children[0].children[0].children[1].textContent,
        execute('t(state.tasks[0].key)'));
    """, saved="M.applyTemplate(M.empty(), 'work').state")


@needs_node
def test_explicit_keyboard_validates_sender_cancellation_and_duplicate_done():
    _run_ui("""
      click('add'); click('keyboard');
      assert.equal(messages.at(-1).message.action, 'open');
      assert.equal(messages.at(-1).origin, context.location.origin);
      reply({value: 'Foreign', done: true}, {source: {}});
      reply({value: 'Foreign', done: true}, {origin: 'https://untrusted.invalid'});
      assert.equal(state().tasks.length, 0);
      reply({cancelled: true});
      reply({value: 'Cancelled', done: true});
      assert.equal(state().tasks.length, 0);
      click('keyboard');
      reply({value: 'Buy bread', done: false});
      assert.equal(elements.taskTitle.value, 'Buy bread');
      reply({value: 'Buy bread', done: true});
      reply({value: 'Buy bread', done: true});
      assert.deepEqual(state().tasks.map(task => task.title), ['Buy bread']);
      assert.equal(elements.editorView.hidden, true);
      assert.equal(messages.at(-1).message.action, 'close');
      assert.deepEqual(JSON.parse(records.get(M.STORAGE_KEY)), state());
    """)


@needs_node
def test_native_keyboard_bridge_enter_and_form_submit_add_only_one_task():
    # The parent sends synthetic Enter and requestSubmit; a local Enter handler
    # would add the same task twice when the native keyboard's Done is pressed.
    _run_ui(f"""
      const screen = fs.readFileSync({json.dumps(str(PACKAGE.parents[2] / 'static/screen/screen.js'))}, 'utf8');
      const start = screen.indexOf('function isTextField(');
      const end = screen.indexOf('/* Sandboxed', start);
      context.kbMode = 'always'; context.kbAuto = () => true; context.kbLang = () => 'en';
      context.osk = {{isOpen: false, open(options) {{ this.isOpen = true; this.options = options; }}}};
      context.frame = {{contentDocument: document, contentWindow: context, dataset: {{}}}};
      execute(screen.slice(start, end));
      execute('bridgeFrameKeyboard(frame)');
      click('add');
      assert.equal(context.osk.isOpen, true);
      context.osk.options.onInput('Native task');
      context.osk.options.onDone('Native task');
      assert.deepEqual(state().tasks.map(task => task.title), ['Native task']);
      assert.equal(elements.editorView.hidden, true);
    """)


@needs_node
def test_storage_denial_keeps_the_real_ui_usable_in_memory():
    _run_ui("""
      assert.equal(elements.storageNotice.hidden, false);
      click('add'); elements.taskTitle.value = 'Works without storage';
      elements.editorForm.requestSubmit();
      assert.equal(state().tasks[0].title, 'Works without storage');
      execute('commit(M.toggle(state, state.tasks[0].id))');
      assert.equal(state().tasks[0].done, true);
      execute('commit(M.reset(state))');
      assert.equal(state().tasks[0].done, false);
      assert.equal(elements.storageNotice.hidden, false);
    """, storage_denied=True)


@needs_node
def test_browser_umd_export_needs_no_dom_and_locale_keys_match():
    _run(f"""
      const fs = require('node:fs'), vm = require('node:vm');
      const context = {{}};
      vm.runInNewContext(fs.readFileSync({json.dumps(str(PACKAGE / 'model.js'))}, 'utf8'), context);
      assert.equal(context.ChecklistModel.progress(context.ChecklistModel.empty()).total, 0);
      const html = fs.readFileSync({json.dumps(str(PACKAGE / 'index.html'))}, 'utf8');
      const dictionary = html.match(/const STR = ({{[\\s\\S]*?}});/)[1];
      const strings = vm.runInNewContext('(' + dictionary + ')');
      assert.deepEqual(Object.keys(strings.uk).sort(), Object.keys(strings.en).sort());
      for (const lang of ['uk', 'en']) {{
        for (const value of Object.values(strings[lang])) assert.ok(value.length);
        for (const name of ['morning', 'work', 'evening']) {{
          for (const task of M.applyTemplate(M.empty(), name).state.tasks) assert.ok(strings[lang][task.key]);
        }}
      }}
      for (const match of html.matchAll(/data-i18n(?:-aria|-placeholder)?="([^"]+)"/g)) {{
        assert.ok(strings.en[match[1]], match[1]);
      }}
    """)


@needs_node
def test_inline_ui_script_parses_and_user_text_stays_out_of_markup():
    html = (PACKAGE / "index.html").read_text("utf-8")
    scripts = re.findall(r"<script>(.*?)</script>", html, re.S)
    assert len(scripts) == 1
    run = subprocess.run([NODE, "--check"], input=scripts[0], capture_output=True, text=True, timeout=15)
    assert run.returncode == 0, run.stderr
    assert "label.textContent = taskText(task)" in scripts[0]
    assert "innerHTML" not in scripts[0]
    assert "event.source !== window.parent" in scripts[0]
    assert "keyboardPending" in scripts[0]
    assert "botKeyboardInput" in scripts[0]
