"""The activity guide must lead to real capabilities and tell the truth about
their connection needs; a failed catalogue must never offer a blind install.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

import app_config

SCREEN = Path(app_config.STATIC_DIR) / "screen"
PACKAGES = Path(app_config.STORE_DIR) / "packages"
NODE = shutil.which("node")


def run_js(body: str):
    script = f"import * as m from {json.dumps((SCREEN / 'guide.js').as_uri())};\n{body}"
    result = subprocess.run(
        [NODE, "--input-type=module", "-e", script],
        capture_output=True, text=True, check=True, timeout=20,
    )
    return json.loads(result.stdout)


@pytest.mark.skipif(NODE is None, reason="node is not installed")
class TestGuide:
    def test_scenarios_have_real_actions_and_destinations(self):
        data = run_js("console.log(JSON.stringify({scenarios:m.SCENARIOS, actions:m.ACTIONS}));")
        scenarios, actions = data["scenarios"], data["actions"]
        assert len(scenarios) == 8
        assert len({s["id"] for s in scenarios}) == len(scenarios)
        source = (SCREEN / "screen.js").read_text("utf-8")
        screens = source.split("const SCREENS = [", 1)[1].split("\n];", 1)[0]
        ids = set(re.findall(r'id: "([a-z-]+)"', screens))
        for scenario in scenarios:
            assert 2 <= len(scenario["actions"]) <= 3
            for key in scenario["actions"]:
                action = actions[key]
                assert ("pkg" in action) != ("screen" in action)
                assert action["connection"] in {"offline", "server", "internet"}
                if "screen" in action:
                    assert action["screen"] in ids
                else:
                    manifest = json.loads((PACKAGES / action["pkg"] / "package.json").read_text("utf-8"))
                    assert manifest["id"] == action["pkg"] and manifest["type"] == "app"

    def test_unknown_missing_and_installed_apps_are_distinct(self):
        result = run_js("""
const a = m.ACTIONS.focus;
console.log(JSON.stringify([
  m.actionState(m.ACTIONS.timer, null),
  m.actionState(a, null), m.actionState(a, []),
  m.actionState(a, [null, {id:a.pkg, type:'skin', installed:true}]),
  m.actionState(a, [{id:a.pkg, type:'app', installed:false}]),
  m.actionState(a, [{id:a.pkg, type:'app', installed:true}])
]));
""")
        assert result == ["ready", "unknown", "unavailable", "unavailable", "install", "ready"]

    def test_catalog_rejects_errors_and_bad_shapes_instead_of_empty_success(self):
        result = run_js("""
const out = [];
for (const response of [
  {ok:false, json:async()=>({packages:[]})},
  {ok:true, json:async()=>({detail:'oops'})},
  {ok:true, json:async()=>({packages:null})},
  {ok:true, json:async()=>{throw new Error('invalid JSON');}},
  {ok:true, json:async()=>({packages:[]})}
]) {
  try { out.push(await m.loadGuideCatalog(async()=>response)); }
  catch (_) { out.push('rejected'); }
}
console.log(JSON.stringify(out));
""")
        assert result == ["rejected"] * 4 + [[]]

    def test_connection_badges_do_not_claim_streams_or_server_timers_are_offline(self):
        actions = run_js("console.log(JSON.stringify(m.ACTIONS));")
        assert actions["music"]["connection"] == actions["video"]["connection"] == "internet"
        assert actions["timer"]["connection"] == actions["memory"]["connection"] == "server"
        assert actions["checklist"]["connection"] == actions["convert"]["connection"] == "offline"

    def test_every_activity_and_action_has_both_languages(self):
        data = run_js("console.log(JSON.stringify({scenarios:m.SCENARIOS, actions:m.ACTIONS}));")
        locale = (SCREEN / "i18n.js").read_text("utf-8")
        keys = {"screen.guide", "guide.intro", "guide.back", "guide.navigation", "guide.local",
                "guide.getOpen", "guide.retry", "guide.unavailable", "guide.catalogFailed", "guide.launchFailed"}
        for scenario in data["scenarios"]:
            keys.update(f"guide.{scenario['id']}.{suffix}" for suffix in ("title", "short", "body"))
        for key, action in data["actions"].items():
            keys.update({f"guide.action.{key}", f"guide.action.{key}.body",
                         f"guide.connection.{action['connection']}"})
        for key in keys:
            assert locale.count(json.dumps(key)) == 2, key

    def test_language_rebuild_preserves_native_back_history(self):
        # Run the real layer function with a minimal DOM, so a language change
        # cannot silently add another Settings/Guide entry to the back stack.
        result = run_js(f"""
const fs = await import('node:fs'), vm = await import('node:vm');
const source = fs.readFileSync({json.dumps(str(SCREEN / 'screen.js'))}, 'utf8');
const layer = source.slice(source.indexOf('function openAppLayer('), source.indexOf('function closeAppLayer('));
const classes = {{add(){{}}, remove(){{}}}};
const context = {{camTimer:0, clearTimeout(){{}}, openApp:null, appHistory:[], launchingFromDrawer:true,
  $:()=>({{textContent:''}}), t:k=>k, applyFrost(){{}},
  appBody:{{className:'',innerHTML:'',scrollTop:90,removeAttribute(){{}}}},
  layerApp:{{classList:classes}}, stage:{{classList:classes,scrollTop:20,scrollLeft:10}},
  gestureNav:{{setOn(){{}}}}, renderIsland(){{}}, wake(){{}}}};
vm.runInNewContext(layer, context);
context.openAppLayer('screen.guide', ()=>{{}});
context.launchingFromDrawer = false;
context.openAppLayer('screen.settings', ()=>{{}});
context.openAppLayer('screen.settings', ()=>{{}}, {{rebuild:true}});
context.openAppLayer('screen.settings', ()=>{{}}, {{rebuild:true}});
console.log(JSON.stringify({{keys:context.appHistory.map(a=>typeof a==='string'?a:a.key), current:context.openApp.key,
  offsets:[context.appBody.scrollTop,context.stage.scrollTop,context.stage.scrollLeft]}}));
""")
        assert result == {"keys": ["drawer", "screen.guide"], "current": "screen.settings", "offsets": [0, 0, 0]}

    def test_guide_keeps_selection_and_does_not_launch_after_leaving(self):
        result = run_js(f"""
const fs = await import('node:fs'), vm = await import('node:vm');
const source = fs.readFileSync({json.dumps(str(SCREEN / 'screen.js'))}, 'utf8');
const guide = source.slice(source.indexOf('function openGuide('), source.indexOf('function openStore('));
const visits = [], builds = [];
let build;
const box = {{classList:{{add(){{}}}}}};
const context = {{t:k=>k, makeSvgIcon(){{}},
  openAppLayer:(_key, fn)=>{{build=fn;fn(box);}}, mountGuide:(_box,deps)=>builds.push(deps),
  openSettings:()=>visits.push('settings'), openMemory:()=>visits.push('memory'),
  openServices:()=>visits.push('services'), showScreen:id=>visits.push('tile:'+id),
  storePost:async()=>visits.push('install'), refreshInstalledApps:async()=>{{}},
  installedApps:[{{pkg:'daily-checklist'}}], openStoreApp:()=>visits.push('app')}};
vm.runInNewContext(guide, context);
context.openGuide();
const first = builds[0];
first.state.scenario = 'work';
build(box);
for (const id of ['settings','memory','services','timer']) first.onScreen(id);
await first.onPackage('daily-checklist', true, ()=>false);
await first.onPackage('daily-checklist', false, ()=>true);
console.log(JSON.stringify({{sameState:first.state===builds[1].state, scenario:builds[1].state.scenario, visits}}));
""")
        assert result == {
            "sameState": True, "scenario": "work",
            "visits": ["settings", "memory", "services", "tile:timer", "install", "app"],
        }
