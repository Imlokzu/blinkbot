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
            source += "\nwindow.screenFixture = {openSettings, openGuide, openStore, openStoreApp, appGoBack, appGoHome, applyUiStyle, setLang};\n"
            self.send_content(source.encode(), "text/javascript")
        elif path.startswith("/api/"):
            # No requests reach a live service, credentials or saved bot state.
            self.send_content(b'{"enabled":false,"apps":[],"packages":[],"timers":[],"notices":[],"mode":"offline"}', "application/json")
        elif path.startswith("/store-apps/"):
            self.send_content(b"<!doctype html><title>Fixture app</title><body></body>", "text/html")
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
