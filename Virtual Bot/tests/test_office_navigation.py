"""Continuous full-sprite clearance and bounded routing for the white office."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

SCREEN = Path(__file__).resolve().parents[1] / "static" / "screen"
pytestmark = pytest.mark.skipif(shutil.which("node") is None, reason="node is not installed")


def run(body):
    source = f"""
import assert from 'node:assert/strict';
import {{performance}} from 'node:perf_hooks';
import {{planRoute, canPlace, roomAt, routeDoors}} from {json.dumps((SCREEN / 'office-navigation.js').as_uri())};
import {{SPOTS, VISITOR}} from {json.dumps((SCREEN / 'office-world.js').as_uri())};
// Independent physical rectangles from drawHouse, before sprite inflation.
const walls = [
  [0,0,640,8], [0,432,640,8], [0,8,8,424], [632,8,8,424],
  [208,8,8,180], [424,8,8,180], [304,252,8,180],
  [8,188,72,8], [136,188,72,8], [216,188,76,8], [348,188,76,8],
  [432,188,72,8], [560,188,72,8],
  [8,244,120,8], [184,244,120,8], [312,244,132,8], [500,244,132,8],
];
const furniture = [
  [36,34,76,108], [126,43,27,28], [164,87,24,53], [169,25,24,24],
  [228,19,56,22], [290,19,56,22], [352,19,56,22],
  [228,116,44,38], [346,110,45,38], [387,153,20,20],
  [493,25,109,38], [522,58,44,38], [446,31,28,76], [590,141,28,28],
  [16,394,244,27], [15,264,30,43], [96,294,56,56], [80,310,18,23],
  [151,310,18,23], [102,336,44,38], [270,268,24,24],
  [336,378,124,35], [379,324,61,29], [514,324,44,38],
  [587,262,28,28], [321,263,22,22],
];
const overlaps = (p, [x,y,w,h]) => p.x+27>x+1e-7 && p.x-27<x+w-1e-7
  && p.y+5>y+1e-7 && p.y-33<y+h-1e-7;
const stations = [...Object.values(SPOTS).flatMap(s => [s.point, ...(s.leisure?[s.leisure]:[])]),
  VISITOR, {{x:574,y:172}}, {{x:480,y:350}}, {{x:152,y:176}}];
function checkRoute(start, goal) {{
  const route=planRoute(start,goal);
  assert.notEqual(route,null,`unreachable ${{JSON.stringify([start,goal])}}`);
  if(start.x===goal.x&&start.y===goal.y) {{assert.deepEqual(route,[]);return route;}}
  assert.ok(route.length>0&&route.length<64);
  assert.deepEqual(route.at(-1),goal);
  assert.notDeepEqual(route[0],start);
  const solids=[...walls,...furniture.filter(r=>!overlaps(start,r)&&!overlaps(goal,r))];
  let from=start;
  for(const to of route) {{
    assert.ok(Number.isFinite(to.x)&&Number.isFinite(to.y));
    // Sub-pixel steps catch diagonal corner cutting and the tight door edges.
    const steps=Math.max(1,Math.ceil(Math.hypot(to.x-from.x,to.y-from.y)*4));
    for(let i=0;i<=steps;i++) {{
      const p={{x:from.x+(to.x-from.x)*i/steps,y:from.y+(to.y-from.y)*i/steps}};
      assert.ok(!solids.some(r=>overlaps(p,r)),`collision ${{JSON.stringify(p)}}`);
    }}
    from=to;
  }}
  return route;
}}
{body}
"""
    result = subprocess.run(
        ["node", "--input-type=module", "-e", source],
        text=True, capture_output=True, timeout=20,
    )
    assert result.returncode == 0, result.stderr


def test_free_room_diagonal_is_direct_and_inputs_are_not_mutated():
    run("""
const start=Object.freeze({x:480,y:150}), goal=Object.freeze({x:530,y:174});
assert.equal(canPlace(start),true);assert.equal(canPlace(goal),true);
const route=checkRoute(start,goal);
assert.deepEqual(route,[goal]);assert.notEqual(route[0],goal);
const diagonal=Math.hypot(goal.x-start.x,goal.y-start.y);
assert.ok(diagonal<Math.abs(goal.x-start.x)+Math.abs(goal.y-start.y));
assert.deepEqual(planRoute(start,start),[]);
assert.deepEqual(routeDoors(start,route),[]);
""")


def test_all_stations_connect_with_full_sprite_clearance():
    run("""
for(const start of stations)for(const goal of stations)checkRoute(start,goal);
// Both endpoints are open floor: the coffee table must force a detour.
const start={x:480,y:310},goal={x:350,y:360};
assert.ok(canPlace(start)&&canPlace(goal));
const detour=checkRoute(start,goal);assert.ok(detour.length>1);
assert.deepEqual(routeDoors(start,detour),[]);
""")


def test_teleports_validate_entire_footprint_without_station_exemptions():
    run("""
for(const p of [null,undefined,{}, {x:NaN,y:232},{x:100,y:Infinity},
  {x:'100',y:232},{x:-1,y:232},{x:641,y:232},{x:100,y:441},
  {x:30,y:232},{x:610,y:232},{x:480,y:30},{x:480,y:430},
  // Centers are on floor, but heads or sides overlap a wall or furniture.
  {x:200,y:150},{x:280,y:210},{x:109.5,y:200},{x:480,y:270},
  {x:355,y:330},{x:574,y:172},...Object.values(SPOTS).map(s=>s.point),VISITOR]) {
  assert.equal(canPlace(p),false,JSON.stringify(p));
}
for(const p of [{x:108,y:200},{x:320,y:210},{x:532,y:220},
  {x:156,y:260},{x:472,y:265},{x:40,y:232},{x:600,y:232},
  {x:480,y:350},{x:152,y:176}])assert.equal(canPlace(p),true,JSON.stringify(p));
for(const p of [{x:0,y:0},{x:100,y:200},{x:200,y:150},{x:NaN,y:232},null]) {
  assert.equal(planRoute(p,{x:480,y:350}),null);
  assert.equal(planRoute({x:480,y:350},p),null);
  assert.equal(planRoute(p,p),null);
}
// The arbitrary floor teleport contract agrees with independent full bounds.
for(let x=0;x<640;x+=11)for(let y=0;y<440;y+=7) {
  const p={x,y};const open=![...walls,...furniture].some(r=>overlaps(p,r));
  assert.equal(canPlace(p),open,JSON.stringify(p));
}
""")


def test_replans_from_mid_door_and_corridor_do_not_clip_walls():
    run("""
for(const spot of Object.values(SPOTS)) {
  for(const y of spot.door.y<220?[190,210,224]:[242,260,280]) {
    const start={x:spot.door.x,y};assert.ok(canPlace(start));
    for(const goal of stations)checkRoute(start,goal);
  }
}
for(const x of [40,108,156,250,320,400,472,532,600]) {
  for(const goal of stations)checkRoute({x,y:232},goal);
}
// Interrupt actual diagonal segments as well as hand-picked doorway centers.
const original=planRoute(SPOTS.work.point,SPOTS.sleep.point);
let from=SPOTS.work.point;
for(const to of original) {
  const start={x:(from.x+to.x)/2,y:(from.y+to.y)/2};
  for(const goal of [SPOTS.library.leisure,SPOTS.kitchen.point,VISITOR])checkRoute(start,goal);
  from=to;
}
""")


def test_door_intent_follows_swept_route_and_includes_partial_exit():
    run("""
for(const [id,spot] of Object.entries(SPOTS)) {
  const start={x:spot.door.x,y:spot.door.y<220?224:242};
  const route=checkRoute(start,{x:400,y:232});
  assert.deepEqual(routeDoors(start,route),[id]);
}
for(const [a,b,expected] of [
  ['work','sleep',['work','sleep']],['sleep','kitchen',['sleep','kitchen']],
  ['library','chat',['library','chat']],['kitchen','work',['kitchen','work']],
  ['chat','sleep',['chat','sleep']]]) {
  const start=SPOTS[a].point,route=checkRoute(start,SPOTS[b].point);
  assert.deepEqual(routeDoors(start,route),expected);
}
const hallway=planRoute({x:40,y:232},{x:600,y:232});
assert.deepEqual(hallway,[{x:600,y:232}]);
assert.deepEqual(routeDoors({x:40,y:232},hallway),[]);
assert.deepEqual(routeDoors({x:600,y:232},[{x:40,y:232}]),[]);
// Upper/lower doors have asymmetric foot bounds; mere tangency is no intent.
assert.deepEqual(routeDoors({x:80,y:229},[{x:140,y:229}]),[]);
assert.deepEqual(routeDoors({x:130,y:239},[{x:180,y:239}]),[]);
assert.deepEqual(routeDoors(null,hallway),[]);
assert.deepEqual(routeDoors({x:40,y:232},null),[]);
assert.deepEqual(routeDoors({x:40,y:232},[{x:NaN,y:232}]),[]);
assert.deepEqual(routeDoors({x:108,y:210},[]),[]);
""")


def test_room_lookup_and_bounded_replanning_cost():
    run("""
for(const [id,spot] of Object.entries(SPOTS))assert.equal(roomAt(spot.point),id);
assert.equal(roomAt({x:574,y:172}),'work');
assert.equal(roomAt({x:480,y:350}),'chat');
assert.equal(roomAt({x:152,y:176}),'sleep');
assert.equal(roomAt({x:320,y:232}),'corridor');
for(const p of [null,{}, {x:-1,y:10},{x:640,y:10},{x:10,y:440},{x:NaN,y:10}]) {
  assert.equal(roomAt(p),null);
}
const began=performance.now();
for(let i=0;i<250;i++) {
  const route=planRoute(stations[i%stations.length],stations[(i*7+3)%stations.length]);
  assert.ok(route!==null&&route.length<64);
}
// Deliberately generous for CI; the finite static graph is also bounded by design.
assert.ok(performance.now()-began<5000,'250 replans exceeded the bounded-work budget');
""")
