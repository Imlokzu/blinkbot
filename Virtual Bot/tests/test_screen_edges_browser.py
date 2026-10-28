"""Exercise full screen panels and preferences without running bot services.

The browser loads the real screen modules, with transport isolated at the HTTP
fixture. Test-only exports let us open the same panels their UI buttons open.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import threading
import uuid
import mimetypes
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

BROWSER = shutil.which("agent-browser")
ROOT = Path(__file__).resolve().parents[1]


class ScreenServer(ThreadingHTTPServer):
    # Chromium fetches the scene's scripts/styles concurrently on a cold load.
    request_queue_size = 128


class ScreenFixture(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT / "static"), **kwargs)

    def send_content(self, content, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/screen":
            page = (ROOT / "static/screen/index.html").read_text()
            setup = """<script>
window.fixtureErrors = [];
addEventListener('error', e => fixtureErrors.push(e.message));
addEventListener('unhandledrejection', e => fixtureErrors.push(String(e.reason)));
window.EventSource = class { close() {} };
window.confirm = () => true;
for (const [key, value] of Object.entries({botScreenHomeView:'classic',
  botScreenVoice:'0', botScreenIdleHome:'0', botScreenIdleSleep:'0',
  botScreenMotion:'reduced'})) localStorage.setItem(key, value);
</script>"""
            self.send_content(page.replace("<head>", "<head>" + setup).encode(), "text/html")
        elif path == "/static/screen/screen.js":
            source = (ROOT / "static/screen/screen.js").read_text()
            source += "\nwindow.screenFixture = {openSettings, openGuide, openStore, openStoreApp, appGoBack, appGoHome, applyUiStyle, setLang, applyEdgeDimming, postStoreAppSkin};\n"
            self.send_content(source.encode(), "text/javascript")
        elif path.startswith("/api/"):
            # No requests reach a live service, credentials or saved bot state.
            self.send_content(b'{"enabled":false,"apps":[],"packages":[],"timers":[],"notices":[],"mode":"offline"}', "application/json")
        elif path.startswith("/store-apps/"):
            if path.startswith("/store-apps/fixture/"):
                self.send_content(b"<!doctype html><title>Fixture app</title><body></body>", "text/html")
                return
            packages = ROOT / "store/packages"
            target = (packages / path.removeprefix("/store-apps/")).resolve()
            if not target.is_relative_to(packages) or not target.is_file():
                self.send_error(404)
                return
            content = target.read_bytes()
            if target.suffix == ".html":
                setup = b"""<script>
window.fixtureErrors = [];
addEventListener('error', e => fixtureErrors.push(e.message));
addEventListener('unhandledrejection', e => fixtureErrors.push(String(e.reason)));
</script>"""
                content = content.replace(b"<head>", b"<head>" + setup)
            self.send_content(content, mimetypes.guess_type(target)[0] or "application/octet-stream")
        else:
            self.path = self.path.removeprefix("/static")
            super().do_GET()

    def do_POST(self):
        self.send_error(405)

    def log_message(self, *_args):
        pass


@pytest.fixture
def screen_browser():
    if BROWSER is None:
        pytest.skip("agent-browser is not installed")
    session = "screen-edges-" + uuid.uuid4().hex[:12]
    server = ScreenServer(("127.0.0.1", 0), ScreenFixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    def browser(*args, script=None):
        result = subprocess.run(
            [BROWSER, "--session", session, *args], input=script,
            capture_output=True, text=True, check=True, timeout=40,
        )
        return result.stdout.strip()

    try:
        browser("set", "viewport", "320", "240")
        browser("open", f"http://127.0.0.1:{server.server_port}/screen")
        try:
            browser("wait", "--fn", "!!window.screenFixture")
        except subprocess.CalledProcessError as error:
            diagnostics = browser("eval", "({url:location.href,errors:window.fixtureErrors})")
            raise AssertionError(diagnostics) from error
        yield browser
    finally:
        try:
            browser("close")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


def test_native_panels_fill_stage_and_keep_controls_reachable(screen_browser):
    result = json.loads(screen_browser("eval", "--stdin", script="""
(async () => {
  const f = window.screenFixture;
  const next = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  const rect = el => el.getBoundingClientRect();
  const checks = [];
  for (const lang of ['uk', 'en']) for (const ui of ['material', 'deep'])
    for (const theme of ['dark', 'light']) {
      f.setLang(lang); f.applyUiStyle(ui);
      document.documentElement.dataset.theme = theme;
      f.openSettings(); await next();
      const body = document.querySelector('#appBody');
      const stage = rect(document.querySelector('#stage'));
      const viewport = rect(body);
      const head = document.querySelector('#layerApp > .layer-head');
      const close = head.querySelector('button');
      const headerTop = rect(head).top;
      const first = rect(body.querySelector('button'));
      const hit = el => { const b = rect(el); return el.contains(document.elementFromPoint(b.x+b.width/2,b.y+b.height/2)); };
      const start = first.top >= rect(head).bottom && hit(close);
      body.scrollTop = 90; await next();
      const behind = rect(body.firstElementChild).top < rect(head).bottom;
      const floats = rect(head).top === headerTop;
      body.scrollTop = body.scrollHeight; await next();
      const reset = body.querySelector('.settings-reset');
      const end = rect(reset).bottom <= stage.bottom - 16 && hit(reset);
      checks.push({lang,ui,theme,full:viewport.top===stage.top && viewport.bottom===stage.bottom,
        width:body.scrollWidth===body.clientWidth,start,behind,floats,end});
    }
  f.openGuide(); await next();
  const guide = document.querySelector('#layerApp').classList.contains('edge-to-edge');
  f.openStore(); await next();
  const store = !document.querySelector('#layerApp').classList.contains('edge-to-edge');
  f.openStoreApp({pkg:'fixture',title:'Fixture'}); await next();
  const layer = document.querySelector('#layerApp');
  const frame = rect(layer.querySelector('iframe')), stage = rect(document.querySelector('#stage'));
  const fullscreen = layer.classList.contains('full') && !layer.classList.contains('edge-to-edge') &&
    frame.top===stage.top && frame.bottom===stage.bottom && getComputedStyle(layer.querySelector('.layer-head')).display==='none';
  f.appGoBack(); await next();
  const backToStore = !layer.classList.contains('full') && !layer.classList.contains('edge-to-edge');
  f.appGoBack(); await next();
  const backToGuide = layer.classList.contains('edge-to-edge');
  document.querySelector('[data-app-close]').click(); await next();
  f.appGoHome(); await next();
  const home = !layer.classList.contains('open') && !document.querySelector('.gnav').classList.contains('on');
  return {checks,guide,store,fullscreen,backToStore,backToGuide,home,errors:fixtureErrors};
})()
"""))
    assert result["errors"] == []
    for check in result.pop("checks"):
        assert all(check[key] for key in ("full", "width", "start", "behind", "floats", "end")), check
    assert all(result[key] for key in ("guide", "store", "fullscreen", "backToStore", "backToGuide", "home")), result


def test_edge_dimming_toggles_persists_and_resets(screen_browser):
    initial = json.loads(screen_browser("eval", "--stdin", script="""
screenFixture.setLang('en'); screenFixture.openSettings();
const toggle = document.querySelector('[aria-label="Edge dimming"]');
const scrims = () => [getComputedStyle(document.querySelector('#layerApp'),'::after').content,
                     getComputedStyle(document.querySelector('.gnav-bottom'),'::before').content];
const before = {pressed:toggle.getAttribute('aria-pressed'),scrims:scrims()};
toggle.click();
({before,after:{pressed:toggle.getAttribute('aria-pressed'),scrims:scrims(),
  saved:localStorage.getItem('botScreenEdgeDimming'),state:document.documentElement.dataset.edgeDimming}});
"""))
    assert initial["before"] == {"pressed": "true", "scrims": ['""', '""']}
    assert initial["after"] == {"pressed": "false", "scrims": ["none", "none"], "saved": "0", "state": "off"}
    screen_browser("reload")
    screen_browser("wait", "--fn", "!!window.screenFixture")
    restored = json.loads(screen_browser("eval", "--stdin", script="""
screenFixture.openSettings();
const saved = document.documentElement.dataset.edgeDimming;
screenFixture.setLang('uk');
const toggle = document.querySelector('[aria-label="Затемнення країв"]');
const translated = toggle.getAttribute('aria-pressed') === 'false';
document.querySelector('.settings-reset').click();
({saved,translated,state:document.documentElement.dataset.edgeDimming,
  cleared:localStorage.getItem('botScreenEdgeDimming'),pressed:toggle.getAttribute('aria-pressed'),errors:fixtureErrors});
"""))
    assert restored == {"saved": "off", "translated": True, "state": "on", "cleared": None, "pressed": "true", "errors": []}


@pytest.mark.parametrize("lang", ["uk", "en"])
def test_real_packages_keep_edges_controls_and_overlays(screen_browser, lang):
    packages = sorted(p.parent.name for p in (ROOT / "store/packages").glob("*/package.json")
                      if json.loads(p.read_text())["type"] == "app")
    result = json.loads(screen_browser("eval", "--stdin", script="""
(async () => {
  const packages = PACKAGES, lang = LANG;
  const f = screenFixture, failures = [], visited = [];
  const next = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  const check = (ok, label) => { if (!ok) failures.push(label); };
  f.setLang(lang);
  for (const id of packages) {
    f.appGoHome();
    f.openStoreApp({pkg:id,title:id});
    const frame = document.querySelector('.storeapp-frame');
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('load:'+id)), 5000);
      frame.addEventListener('load', () => { clearTimeout(timer); resolve(); }, {once:true});
    });
    const d = frame.contentDocument, w = frame.contentWindow;
    check(!!w.BotApp, id+':kit');
    const main = d.querySelector('main.kit-app');
    const rect = el => el.getBoundingClientRect();
    const hit = el => { const r=rect(el); return el.contains(d.elementFromPoint(r.x+r.width/2,r.y+r.height/2)); };
    for (const ui of ['material','deep']) for (const theme of ['dark','light']) {
      f.applyUiStyle(ui); document.documentElement.dataset.theme=theme; f.postStoreAppSkin();
      await next();
      const key=id+':'+ui+':'+theme;
      check(w.BotApp.ui===ui && w.BotApp.theme===theme && w.BotApp.lang===lang,key+':tokens');
      check(rect(main).top===0 && rect(main).bottom===240 && rect(main).width===320,key+':root');
      if (!main.classList.contains('kit-edge') && !main.classList.contains('kit-edge-shell')) {
        const css=w.getComputedStyle(main);
        check(parseFloat(css.paddingLeft)>=12 && parseFloat(css.paddingRight)>=12 && parseFloat(css.paddingBottom)>=16,key+':insets');
      }
      for (const page of d.querySelectorAll('.kit-edge')) {
        if (!page.offsetHeight) continue;
        const content=page.querySelector(':scope > .kit-edge-content');
        const top=page.querySelector(':scope > .kit-edge-top');
        check(rect(content).top===rect(page).top && rect(content).bottom===rect(page).bottom,key+':viewport');
        check(parseFloat(w.getComputedStyle(content).paddingTop)>=top.offsetHeight+4,key+':top-clearance');
      }
    }
    // Tabs must not lose clearance when the visible view is not the first sibling.
    if (id==='dice' || id==='clock') {
      for (const tab of d.querySelectorAll('[data-tab]')) {
        tab.click(); await next();
        check(hit(tab),id+':tab:'+tab.dataset.tab);
        const view=d.querySelector('.view:not([hidden])');
        check(rect(view).top>=rect(tab.closest('nav,header')).bottom,id+':alternate-view:'+tab.dataset.tab);
      }
    }
    if (id==='unit-converter') {
      d.querySelector('#category').click(); await next();
      const done=d.querySelector('#done'), picker=d.querySelector('#picker');
      check(hit(done) && rect(picker).top===0 && rect(picker).bottom===240,'converter:picker');
      done.click(); await next(); check(hit(d.querySelector('#category')),'converter:restore');
    }
    if (id==='clock') {
      d.querySelector('#picker').classList.add('show'); await next();
      const picker=d.querySelector('#picker'), button=picker.querySelector('button');
      check(hit(button),'clock:picker-layer');
      picker.classList.remove('show');
    }
    if (id==='youtube') {
      d.querySelector('#cog').click(); await next();
      check(hit(d.querySelector('#sback')),'youtube:sheet-layer');
      const list=d.querySelector('#sheet .kit-edge-content');
      list.scrollTop=list.scrollHeight; await next();
      const last=[...list.querySelectorAll('button')].filter(el=>el.offsetHeight).at(-1);
      check(hit(last) && rect(last).bottom<=224,'youtube:last-setting');
      d.querySelector('#sback').click();
      // Open the actual player surface without starting a media/network request.
      d.querySelector('#player').classList.add('on'); await next();
      check(hit(d.querySelector('#pback')),'youtube:player-layer');
    }
    if (id==='yt-music') {
      const mini=d.querySelector('#miniNp'); mini.hidden=false;
      d.querySelector('#miniTitle').textContent='Fixture track'; await next();
      check(rect(mini).left>=12 && rect(mini).right<=308 && rect(mini).bottom<=224,'music:mini-bounds');
      check(hit(d.querySelector('#miniPlay')),'music:mini-play');
      d.querySelector('#sheet').hidden=false; await next();
      check(hit(d.querySelector('#sheetBack')),'music:sheet-layer');
      d.querySelector('#sheet').hidden=true; d.querySelector('#player').hidden=false; await next();
      check(hit(d.querySelector('#pclose')),'music:player-layer');
    }
    if (id==='daily-checklist') {
      d.querySelector('[data-template="morning"]').click(); await next();
      const notice=d.querySelector('#notice');
      check(!notice.hidden && rect(notice).bottom<=224 && rect(notice).left>=0,'checklist:notice');
      const warning=d.querySelector('#storageNotice'); warning.hidden=false; warning.textContent='Storage unavailable'; await next();
      check(rect(warning).bottom<=224 && w.getComputedStyle(warning).paddingLeft==='12px','checklist:warning');
      check(hit(d.querySelector('#add')),'checklist:add');
      d.querySelector('#add').click(); await next();
      d.querySelector('#taskTitle').value='Edge fixture task';
      d.querySelector('#taskTitle').dispatchEvent(new Event('input',{bubbles:true}));
      check(hit(d.querySelector('#cancel')),'checklist:editor-header');
      d.querySelector('#editorForm').scrollTop=9999; await next();
      check(hit(d.querySelector('#save')),'checklist:save');
      d.querySelector('#save').click(); await next();
      check(!d.querySelector('#listView').hidden,'checklist:saved');
    }
    f.applyEdgeDimming(false); f.postStoreAppSkin(); await next();
    check(w.BotApp.edgeDimming===false,id+':dimming-off');
    for (const page of d.querySelectorAll('.kit-edge')) check(w.getComputedStyle(page,'::before').display==='none',id+':scrim-off');
    f.applyEdgeDimming(true); f.postStoreAppSkin(); await next();
    check(w.BotApp.edgeDimming===true,id+':dimming-on');
    check(w.fixtureErrors.length===0,id+':errors:'+w.fixtureErrors.join(','));
    visited.push(id);
  }
  f.appGoHome();
  return {failures,visited,hostErrors:fixtureErrors};
})()
""".replace("PACKAGES", json.dumps(packages)).replace("LANG", json.dumps(lang))))
    assert result["visited"] == packages
    assert result["failures"] == []
    assert result["hostErrors"] == []
