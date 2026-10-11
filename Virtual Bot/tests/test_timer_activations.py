"""Timer restarts deliver a new alarm while ordinary edits retain their cycle."""

from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess
from unittest.mock import patch

from fastapi.testclient import TestClient
import pytest

import screen_widgets


SCREEN = Path(__file__).resolve().parents[1] / 'static/screen'
NODE = shutil.which('node')


@pytest.fixture
def timer_state(tmp_path):
    clock = {'now': 1000.0}
    path = tmp_path / 'widgets.json'
    with patch.object(screen_widgets, '_state_path', lambda: path), \
         patch.object(screen_widgets, '_now', lambda: clock['now']), \
         patch.object(screen_widgets.events, 'publish') as publish:
        yield clock, path, publish


def test_completed_timer_restart_persists_a_new_activation(timer_state):
    clock, path, publish = timer_state
    initial = screen_widgets.add_timer(1, 'Fixture tea')
    assert initial['activation'] == 1
    clock['now'] = 1002
    assert screen_widgets.list_timers()[0]['state'] == 'done'
    [restarted] = screen_widgets.update_timer('add', initial['id'], 60)
    assert restarted['id'] == initial['id']
    assert restarted['state'] == 'running' and restarted['activation'] == 2
    assert json.loads(path.read_text())['timers'][0]['activation'] == 2
    assert screen_widgets.list_timers()[0]['activation'] == 2
    assert publish.call_args.args[0]['timers'][0]['activation'] == 2


def test_extension_pause_resume_keep_the_running_activation(timer_state):
    clock, _, _ = timer_state
    timer = screen_widgets.add_timer(60)
    clock['now'] += 5
    [extended] = screen_widgets.update_timer('add', timer['id'], 10)
    assert extended['activation'] == 1
    [paused] = screen_widgets.update_timer('pause', timer['id'])
    clock['now'] += 200
    [more] = screen_widgets.update_timer('add', timer['id'], 15)
    [resumed] = screen_widgets.update_timer('resume', timer['id'])
    assert all(t['activation'] == 1 for t in [paused, more, resumed])
    assert resumed['left'] == 80


def test_completed_pause_add_resume_creates_only_one_new_activation(timer_state):
    clock, _, _ = timer_state
    timer = screen_widgets.add_timer(1)
    clock['now'] += 2
    [paused] = screen_widgets.update_timer('pause', timer['id'])
    assert paused['left'] == 0 and paused['activation'] == 1
    [added] = screen_widgets.update_timer('add', timer['id'], 60)
    [more] = screen_widgets.update_timer('add', timer['id'], 10)
    [resumed] = screen_widgets.update_timer('resume', timer['id'])
    assert all(t['activation'] == 2 for t in [added, more, resumed])
    assert resumed['left'] == 70


@pytest.mark.parametrize('extra', [-5, 0, 5])
def test_any_add_that_restarts_an_expired_timer_renews_activation(timer_state, extra):
    clock, _, _ = timer_state
    timer = screen_widgets.add_timer(1)
    clock['now'] += 2
    [restarted] = screen_widgets.update_timer('add', timer['id'], extra)
    assert restarted['activation'] == 2 and restarted['left'] >= 1


def test_completed_pause_resume_without_added_time_is_still_historical(timer_state):
    clock, _, _ = timer_state
    timer = screen_widgets.add_timer(1)
    clock['now'] += 2
    screen_widgets.update_timer('pause', timer['id'])
    [resumed] = screen_widgets.update_timer('resume', timer['id'])
    assert resumed['state'] == 'done' and resumed['activation'] == 1


@pytest.mark.parametrize('legacy', [None, True, '2', 0, -1])
def test_legacy_activation_migrates_once_on_server_and_survives_reads(timer_state, legacy):
    _, path, _ = timer_state
    original = {'id': 'legacy', 'kind': 'timer', 'label': 'Legacy', 'seconds': 1,
                'ends_at': 999, 'paused_left': None}
    if legacy is not None:
        original['activation'] = legacy
    path.write_text(json.dumps({'timers': [original], 'weather_city': 'Fixture city'}))
    with patch.object(screen_widgets, '_save', wraps=screen_widgets._save) as save:
        [first] = screen_widgets.list_timers()
        [second] = screen_widgets.list_timers()
    assert first['activation'] == second['activation'] == 1
    assert save.call_count == 1
    persisted = json.loads(path.read_text())
    assert persisted['timers'][0]['activation'] == 1
    assert persisted['weather_city'] == 'Fixture city'
    [restarted] = screen_widgets.update_timer('add', 'legacy', 60)
    assert restarted['activation'] == 2


def test_reminder_activation_uses_the_same_delivery_contract(timer_state):
    clock, _, _ = timer_state
    reminder = screen_widgets.add_timer(1, 'Fixture reminder', 'reminder')
    clock['now'] += 2
    [restarted] = screen_widgets.update_timer('add', reminder['id'], 30)
    assert restarted['kind'] == 'reminder' and restarted['activation'] == 2


def test_cancelled_timer_is_absent_and_new_timer_has_its_own_activation(timer_state):
    timer = screen_widgets.add_timer(1)
    screen_widgets.update_timer('cancel', timer['id'])
    assert screen_widgets.list_timers() == []
    new = screen_widgets.add_timer(1)
    assert new['id'] != timer['id'] and new['activation'] == 1


def test_api_exports_restart_activation(timer_state):
    import main

    clock, _, _ = timer_state
    with TestClient(main.app) as client:
        body = client.post('/api/screen/timers', json={'action': 'set', 'seconds': 1}).json()
        timer = body['timers'][0]
        clock['now'] += 2
        restarted = client.post('/api/screen/timers', json={
            'action': 'add', 'id': timer['id'], 'seconds': 60,
        }).json()['timers'][0]
        assert restarted['activation'] == timer['activation'] + 1
        assert client.get('/api/screen/timers').json()['timers'][0]['activation'] == 2


def run_screen(body: str) -> dict:
    """Run shipped timer functions and delivery module with an inert alarm adapter."""
    script = f"""
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {{readFileSync}} from 'node:fs';
import {{TimerDeliveries}} from {json.dumps((SCREEN / 'timer-delivery.js').as_uri())};
const source=readFileSync({json.dumps(str(SCREEN / 'screen.js'))},'utf8');
const extract=name=>{{
  const match=source.match(new RegExp('^function '+name+'\\\\([^\\\\n]*\\\\) \\\\{{[\\\\s\\\\S]*?^\\\\}}','m'));
  assert.ok(match,'Missing actual screen function: '+name);return match[0];
}};
const clock={{now:1000}}, counts={{rings:0}};
class ClockDate extends Date {{static now(){{return clock.now*1000;}}}}
const ctx=vm.createContext({{
  Date:ClockDate, TimerDeliveries, clock, counts,
  renderTimers(){{}},noticesChanged(){{}},wake(){{}},setEmotion(){{}},showCaption(){{}},speechSay(){{}},
  alarmBeep(){{counts.rings++;}}, t:key=>key,setInterval:()=>1,clearInterval(){{}}
}});
vm.runInContext(`let timers=[],timerSkew=0;const timerDeliveries=new TimerDeliveries();
let ringUntil=0,ringTimer=0;const RING_MS=30000,RING_EVERY_MS=2500;
${{['timerLeft','setTimers','ringTimerDone','stopRing','tickTimers'].map(extract).join('\\n')}}`,ctx);
const run=code=>vm.runInContext(code,ctx);
const receive=(list,now=clock.now,serverNow=now)=>{{ctx.incoming=list;ctx.serverNow=serverNow;
  clock.now=now;run('setTimers(incoming,serverNow)');}};
const timer=(activation=1,state='running',end=1001)=>({{id:'fixture',activation,state,ends_at:end,left:0,label:'Fixture'}});
{body}
"""
    result = subprocess.run([NODE, '--input-type=module', '-e', script], text=True,
                            capture_output=True, timeout=15)
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


@pytest.mark.skipif(NODE is None, reason='node is not installed')
@pytest.mark.parametrize('historical', [False, True])
def test_screen_restart_rings_once_after_the_original_alarm(historical):
    result = run_screen(f"""
receive([timer(1,{json.dumps('done' if historical else 'running')})]);clock.now=1002;run('tickTimers()');
const first=counts.rings;run('stopRing()');receive([timer(2,'running',1062)],1002);
clock.now=1063;run('tickTimers()');run('tickTimers()');receive([timer(2,'running',1062)]);run('tickTimers()');
console.log(JSON.stringify({{first,final:counts.rings,records:run('timerDeliveries.records.size')}}));
""")
    assert result == {'first': 0 if historical else 1, 'final': 1 if historical else 2, 'records': 1}


@pytest.mark.skipif(NODE is None, reason='node is not installed')
def test_backend_restart_snapshot_drives_the_actual_screen(timer_state):
    clock, _, _ = timer_state
    first = screen_widgets.add_timer(1)
    clock['now'] = 1002
    [restarted] = screen_widgets.update_timer('add', first['id'], 60)
    result = run_screen(f"""
receive([{json.dumps(first)}],1000);clock.now=1002;run('tickTimers()');run('stopRing()');
receive([{json.dumps(restarted)}],1002);clock.now=1063;run('tickTimers()');run('tickTimers()');
console.log(JSON.stringify({{rings:counts.rings}}));
""")
    assert result['rings'] == 2


@pytest.mark.skipif(NODE is None, reason='node is not installed')
def test_delayed_older_activation_cannot_replace_or_suppress_restart():
    result = run_screen("""
receive([timer(1,'done')]);receive([timer(2,'running',1010)]);
receive([timer(1,'done')]);clock.now=1011;run('tickTimers()');run('stopRing()');
receive([timer(1,'running',999)]);run('tickTimers()');
console.log(JSON.stringify({rings:counts.rings,activation:run('timers[0].activation')}));
""")
    assert result == {'rings': 1, 'activation': 2}


@pytest.mark.skipif(NODE is None, reason='node is not installed')
def test_older_activation_cannot_rewind_clock_sample_for_newer_countdown():
    result = run_screen("""
receive([timer(2,'running',1062)],1002);receive([timer(1,'done')],1003,1000);
clock.now=1062;run('tickTimers()');
console.log(JSON.stringify({rings:counts.rings,activation:run('timers[0].activation'),skew:run('timerSkew')}));
""")
    assert result == {'rings': 1, 'activation': 2, 'skew': 0}


@pytest.mark.skipif(NODE is None, reason='node is not installed')
def test_clock_correction_pause_and_extension_do_not_replay_delivery():
    result = run_screen("""
receive([timer()]);clock.now=1002;run('tickTimers()');run('stopRing()');
receive([timer(1,'running',1010)],990);clock.now=1011;run('tickTimers()');
receive([{...timer(1,'paused'),left:5}]);clock.now=1050;run('tickTimers()');
receive([timer(1,'running',1060)]);clock.now=1061;run('tickTimers()');
console.log(JSON.stringify({rings:counts.rings}));
""")
    assert result['rings'] == 1


@pytest.mark.skipif(NODE is None, reason='node is not installed')
def test_cancel_removes_countdown_and_reconnect_does_not_ring_done_history():
    result = run_screen("""
receive([timer()]);receive([]);clock.now=1002;run('tickTimers()');
receive([timer(2,'done',999)]);run('tickTimers()');
receive([timer(3,'running',1003)]);clock.now=1004;run('tickTimers()');
console.log(JSON.stringify({rings:counts.rings}));
""")
    assert result['rings'] == 1


@pytest.mark.skipif(NODE is None, reason='node is not installed')
def test_legacy_history_matches_migration_and_restart_generation():
    result = run_screen("""
const legacy=timer(1,'done');delete legacy.activation;receive([legacy]);
receive([timer(1,'running',999)]);run('tickTimers()');
receive([timer(2,'running',1001)]);clock.now=1002;run('tickTimers()');
console.log(JSON.stringify({rings:counts.rings}));
""")
    assert result['rings'] == 1


@pytest.mark.skipif(NODE is None, reason='node is not installed')
def test_delivery_history_is_bounded_without_evicting_a_live_rung_timer():
    result = run_screen("""
receive([timer()]);clock.now=1002;run('tickTimers()');run('stopRing()');
const history=Array.from({length:2000},(_,i)=>({...timer(1,'done'),id:'old-'+i}));
receive([timer(),...history]);run('tickTimers()');
const retained=run('timerDeliveries.records.size');receive([timer()]);run('tickTimers()');
for(let i=0;i<1000;i++) receive([{...timer(1,'done'),id:'retired-'+i}]);
console.log(JSON.stringify({rings:counts.rings,retained,after:run('timerDeliveries.records.size')}));
""")
    assert result['rings'] == 1
    assert result['retained'] == 2001
    assert result['after'] <= 513  # At most 512 retired IDs plus the current snapshot.


@pytest.mark.skipif(NODE is None, reason='node is not installed')
def test_done_timer_in_large_current_snapshot_keeps_its_delivery_fence():
    result = run_screen("""
receive([timer()]);clock.now=1002;run('tickTimers()');run('stopRing()');
const history=Array.from({length:512},(_,i)=>({...timer(1,'done'),id:'old-'+i}));
receive([timer(1,'done'),...history]);receive([timer(1,'running',1001)]);run('tickTimers()');
console.log(JSON.stringify({rings:counts.rings}));
""")
    assert result['rings'] == 1
