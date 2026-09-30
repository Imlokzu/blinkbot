"""Run the real guide in Chromium: a language remount during installation
must not install twice or reopen an app after its original view has gone.
The fixture serves only local screen assets, without starting bot services.
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

import app_config

BROWSER = shutil.which("agent-browser")
SCREEN = Path(app_config.STATIC_DIR) / "screen"


class GuideFixture(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(SCREEN), **kwargs)

    def do_GET(self):
        if self.path != "/":
            return super().do_GET()
        content = b'<!doctype html><html lang="en"><title>Guide test</title><main id="fixture"></main></html>'
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, *_args):
        pass


@pytest.mark.skipif(BROWSER is None, reason="agent-browser is not installed")
def test_install_is_shared_across_actual_guide_remount():
    session = "guide-regression-" + uuid.uuid4().hex[:12]
    server = ThreadingHTTPServer(("127.0.0.1", 0), GuideFixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    def browser(*args, script=None):
        result = subprocess.run(
            [BROWSER, "--session", session, *args], input=script,
            capture_output=True, text=True, check=True, timeout=40,
        )
        return result.stdout.strip()

    try:
        browser("open", f"http://127.0.0.1:{server.server_port}/")
        result = json.loads(browser("eval", "--stdin", script="""
(async () => {
  const { mountGuide } = await import('/guide.js');
  const fixture = document.querySelector('#fixture');
  const shared = { scenario: 'work' };
  let requests = 0, staleOpens = 0, finish;
  let packages = [{id:'pomodoro',type:'app',installed:false},
                  {id:'daily-checklist',type:'app',installed:false}];
  const deps = { state:shared, t:key=>key,
    icon:()=>document.createElement('span'), catalog:async()=>packages,
    onScreen:()=>{}, onPackage:async(_id,_install,active)=>{
      requests++;
      await new Promise(resolve=>{ finish=()=>{packages[0].installed=true;resolve();}; });
      if (active()) staleOpens++;
    }
  };
  const settle = async () => { for (let i=0;i<12;i++) await Promise.resolve(); };
  const remount = () => { fixture.replaceChildren(); mountGuide(fixture,deps); };
  remount();
  await settle();
  fixture.querySelector('[data-action="focus"]').click();
  await settle();
  remount();
  await settle();
  const during = fixture.querySelector('[data-action="focus"]').disabled;
  fixture.querySelector('[data-action="focus"]').click();
  await settle();
  const pending = !!shared.pending;
  finish();
  await settle();
  const row = fixture.querySelector('[data-action="focus"]');
  const completed = {disabled:row.disabled,label:row.textContent,requests,staleOpens,
                     pendingCleared:shared.pending===null,scenario:shared.scenario};
  fixture.querySelector('.guide-back').click();
  const back = {scenario:shared.scenario,focus:document.activeElement.dataset.scenario};
  shared.scenario='../../settings';
  remount();
  await settle();
  return {during,pending,completed,back,invalidShowsOverview:!!fixture.querySelector('.guide-grid')};
})()
"""))
        assert result["during"] and result["pending"]
        assert result["completed"] == {
            "disabled": False,
            "label": "guide.action.focusguide.action.focus.bodyguide.connection.offlinestore.open",
            "requests": 1, "staleOpens": 0, "pendingCleared": True, "scenario": "work",
        }
        assert result["back"] == {"scenario": None, "focus": "work"}
        assert result["invalidShowsOverview"]
    finally:
        try:
            browser("close")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
