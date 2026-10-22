"""Office motion cannot delay work, lose an interrupt, or fabricate memory jobs."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

SCREEN = Path(__file__).resolve().parents[1] / 'static' / 'screen'
pytestmark = pytest.mark.skipif(shutil.which('node') is None, reason='node is not installed')


def run(body):
    source = f"""
import {{ OfficeState }} from {json.dumps((SCREEN / 'office-state.js').as_uri())};
import {{ SPOTS }} from {json.dumps((SCREEN / 'office-world.js').as_uri())};
import assert from 'node:assert/strict';
const s = new OfficeState(SPOTS, () => 0);
s.setConnected(true);
const advance = seconds => {{for(let i=0;i<seconds*20;i++) s.step(.05);}};
{body}
"""
    result = subprocess.run(['node', '--input-type=module', '-e', source], text=True, capture_output=True, timeout=20)
    assert result.returncode == 0, result.stderr


def test_sleeping_message_wakes_without_delaying_real_activity():
    run("""
s.setReducedMotion(true); s.setSleeping(true);
assert.equal(s.pose,'sleep');
s.setReducedMotion(false); s.setActivity({activity:'work',active:true});
assert.equal(s.active,true); assert.equal(s.pose,'waking');
s.setSleeping(false);
assert.equal(s.pose,'waking');
advance(.7); assert.equal(s.pose,'hop');
advance(12); assert.equal(s.room,'work'); assert.equal(s.pose,'work');
""")


def test_fast_reply_during_wake_finishes_in_a_valid_idle_state():
    run("""
s.setReducedMotion(true); s.setSleeping(true); s.setReducedMotion(false);
s.setActivity({activity:'work',active:true});
s.setActivity({activity:'idle',active:false});
advance(8); assert.equal(s.active,false); assert.equal(s.room,'work');
assert.notEqual(s.pose,'waking'); assert.notEqual(s.pose,'hop');
""")


def test_active_memory_and_leisure_reading_have_different_status():
    run("""
s.setReducedMotion(true); s.setActivity({activity:'organize',active:true});
assert.equal(s.room,'library'); assert.equal(s.status,'organize');
s.setSleeping(true); assert.equal(s.room,'library'); assert.equal(s.sleeping,false);
s.setActivity({activity:'idle',active:false}); s.go('library');
assert.equal(s.pose,'leisure'); assert.equal(s.status,'leisure');
assert.equal(s.active,false);
""")


def test_new_job_interrupts_travel_and_disconnect_clears_work():
    run("""
s.go('kitchen'); advance(2);
s.setActivity({activity:'read',active:true}); advance(12);
assert.equal(s.room,'library'); assert.equal(s.status,'read');
s.setConnected(false); assert.equal(s.active,false); assert.equal(s.status,'offline');
s.setConnected(true); assert.equal(s.status,'idle');
""")


def test_reduced_motion_snaps_activity_and_stops_idle_wandering():
    run("""
s.setReducedMotion(true); s.setActivity({activity:'read',active:true});
assert.equal(s.room,'library'); assert.deepEqual(s.position,SPOTS.library.point);
s.setActivity({activity:'idle',active:false}); const before={...s.position};
advance(300); assert.deepEqual(s.position,before);
""")


def test_every_label_has_both_languages_and_state_never_claims_idle_work():
    run(f"""
import {{ OFFICE_TEXT }} from {json.dumps((SCREEN / 'office-i18n.js').as_uri())};
assert.deepEqual(Object.keys(OFFICE_TEXT.en).sort(),Object.keys(OFFICE_TEXT.uk).sort());
for(const dict of Object.values(OFFICE_TEXT)) assert.ok(Object.values(dict).every(v=>typeof v==='string'&&v.length));
advance(180); assert.equal(s.active,false); assert.equal(s.activity,'idle');
""")


def test_interrupted_routes_keep_the_whole_crab_inside_real_doorways():
    run("""
const walls=[{x:208,y:8,w:8,h:180},{x:424,y:8,w:8,h:180},{x:304,y:252,w:8,h:180}];
for(const [a,b,x,y] of [[8,208,108,188],[216,424,320,188],[432,632,532,188],[8,304,156,244],[312,632,472,244]]) {
  walls.push({x:a,y,w:x-28-a,h:8},{x:x+28,y,w:b-x-28,h:8});
}
const clear=()=>{
  const box={x:s.position.x-27,y:s.position.y-33,w:54,h:38};
  for(const wall of walls)assert.ok(!(box.x<wall.x+wall.w&&box.x+box.w>wall.x&&box.y<wall.y+wall.h&&box.y+box.h>wall.y),JSON.stringify({box,wall,room:s.room,target:s.target}));
};
for(const room of ['sleep','library','kitchen','chat','work']) {
  s.go(room);
  for(let i=0;i<400;i++) {s.step(.05);clear();if(!s.route.length)break;}
  for(const delay of [1,15,45,85]) {
    s.go('sleep');for(let i=0;i<delay;i++){s.step(.05);clear();}
    s.setActivity({activity:'read',active:true});
    for(let i=0;i<300;i++){s.step(.05);clear();if(!s.route.length)break;}
    assert.equal(s.room,'library');s.setActivity({activity:'idle',active:false});
  }
}
""")


def test_a_memory_job_replans_from_the_chair_walk_to_the_cart():
    # Idle reading walks to the armchair; a job that arrives mid-walk must
    # redirect to the sorting cart, not finish the stroll and "work" from
    # the chair. Same room, different spot: the old early return kept the
    # stale destination.
    run("""
s.go('library'); advance(1);
assert.deepEqual(s.destination,SPOTS.library.leisure);
s.setActivity({activity:'organize',active:true});
assert.deepEqual(s.destination,SPOTS.library.point);
advance(15); assert.deepEqual(s.position,SPOTS.library.point); assert.equal(s.pose,'organize');
s.setActivity({activity:'idle',active:false});
assert.equal(s.pose,'idle'); advance(3); assert.deepEqual(s.position,SPOTS.library.point);
// The other way round: a job cancelled on the way to the cart turns into
// a stroll to the chair, from wherever the crab is at that moment.
s.go('kitchen'); advance(15); assert.equal(s.room,'kitchen');
s.setActivity({activity:'read',active:true}); advance(1);
s.setActivity({activity:'idle',active:false}); assert.deepEqual(s.destination,SPOTS.library.leisure);
advance(8); assert.deepEqual(s.position,SPOTS.library.leisure); assert.equal(s.pose,'leisure');
""")


def test_walks_read_as_moving_unless_the_job_already_started():
    run("""
s.go('kitchen'); assert.equal(s.status,'moving');
s.setActivity({activity:'work',active:true}); assert.equal(s.status,'work');
s.setActivity({activity:'help',active:false}); assert.equal(s.status,'help');
advance(2); assert.equal(s.room,'work'); assert.equal(s.status,'help');
s.setActivity({activity:'idle',active:false}); s.setSleeping(true); assert.equal(s.status,'moving');
advance(20); assert.equal(s.status,'sleep');
""")


def test_transition_churn_stays_bounded_and_clear_of_walls():
    # Every host call, in every order the screen can produce, with steps of
    # random length between them: the crab must keep finite coordinates
    # inside the house, a pose the painter knows, a localized status, and
    # its whole body out of the walls at every step of every reroute.
    run(f"""
import {{ OFFICE_TEXT }} from {json.dumps((SCREEN / 'office-i18n.js').as_uri())};
import {{ ROOMS, WORLD }} from {json.dumps((SCREEN / 'office-world.js').as_uri())};
const walls=[{{x:208,y:8,w:8,h:180}},{{x:424,y:8,w:8,h:180}},{{x:304,y:252,w:8,h:180}}];
for(const [a,b,x,y] of [[8,208,108,188],[216,424,320,188],[432,632,532,188],[8,304,156,244],[312,632,472,244]]) {{
  walls.push({{x:a,y,w:x-28-a,h:8}},{{x:x+28,y,w:b-x-28,h:8}});
}}
const poses=['idle','walk','waking','hop','sleep','work','read','organize','chat','help','eat','leisure'];
const statusKey={{work:'working',chat:'talking',sleep:'sleeping'}};
const rooms=[...Object.keys(ROOMS),'corridor'];
let seed=7; const rnd=()=>(seed=seed*48271%2147483647)/2147483647;
const check=(what)=>{{
  const tag=()=>what+' '+JSON.stringify({{pose:s.pose,room:s.room,target:s.target,route:s.route.length,position:s.position}});
  assert.ok(Number.isFinite(s.position.x)&&Number.isFinite(s.position.y),tag());
  assert.ok(s.position.x>=8&&s.position.x<=WORLD.width-8&&s.position.y>=8&&s.position.y<=WORLD.height-8,tag());
  assert.ok(poses.includes(s.pose),tag());
  assert.ok(rooms.includes(s.room),tag());
  assert.equal(s.pose==='walk',s.route.length>0,tag());
  assert.ok(s.route.length<=5&&Number.isFinite(s.hold),tag());
  assert.ok(typeof OFFICE_TEXT.en[statusKey[s.status]||s.status]==='string',tag());
  const box={{x:s.position.x-27,y:s.position.y-33,w:54,h:38}};
  for(const wall of walls)assert.ok(!(box.x<wall.x+wall.w&&box.x+box.w>wall.x&&box.y<wall.y+wall.h&&box.y+box.h>wall.y),tag()+' hits '+JSON.stringify(wall));
}};
const ops=[
  ['stroll',()=>s.go('kitchen')],['work',()=>s.setActivity({{activity:'work',active:true}})],
  ['done',()=>s.setActivity({{activity:'idle',active:false}})],['sleep',()=>s.setSleeping(true)],
  ['read',()=>s.setActivity({{activity:'read',active:true}})],['wake',()=>s.setSleeping(false)],
  ['organize',()=>s.setActivity({{activity:'organize',active:true}})],['help',()=>s.setActivity({{activity:'help',active:false}})],
  ['offline',()=>s.setConnected(false)],['online',()=>s.setConnected(true)],['chat',()=>s.setActivity({{activity:'chat',active:true}})],
  ['reduce',()=>s.setReducedMotion(true)],['done',()=>s.setActivity({{activity:'idle',active:false}})],['motion',()=>s.setReducedMotion(false)],
  ['sleep',()=>s.setSleeping(true)],['work',()=>s.setActivity({{activity:'work',active:true}})],['done',()=>s.setActivity({{activity:'idle',active:false}})],
  ['wake',()=>s.setSleeping(false)],['unknown',()=>s.setActivity({{activity:'juggling',active:true}})],['done',()=>s.setActivity({{}})],
];
let steps=0;
for(let round=0;round<40;round++) for(const [name,op] of ops) {{
  op(); check(name);
  const n=1+Math.floor(rnd()*90);
  for(let i=0;i<n;i++){{s.step(.05);check(name+'+'+i);steps++;}}
}}
assert.ok(steps>20000);
""")
