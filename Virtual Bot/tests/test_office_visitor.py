"""The visitor is purely visual: it walks the real floor plan, through the
doors it actually passes, and only ever teleports onto open floor."""
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
import {{OfficeVisitor, BEDSIDE, canTeleport}} from {json.dumps((SCREEN / 'office-visitor.js').as_uri())};
import {{VISITOR, SPOTS}} from {json.dumps((SCREEN / 'office-world.js').as_uri())};
const walls = [
  [0,0,640,8], [0,432,640,8], [0,8,8,424], [632,8,8,424],
  [208,8,8,180], [424,8,8,180], [304,252,8,180],
  [8,188,72,8], [136,188,72,8], [216,188,76,8], [348,188,76,8], [432,188,72,8], [560,188,72,8],
  [8,244,120,8], [184,244,120,8], [312,244,132,8], [500,244,132,8],
];
const e=1e-7;
const clear=p=>walls.every(([x,y,w,h])=>!(p.x+27>x+e&&p.x-27<x+w-e&&p.y+5>y+e&&p.y-33<y+h-e));
{body}
"""
    result = subprocess.run(["node", "--input-type=module", "-e", source], text=True, capture_output=True, timeout=20)
    assert result.returncode == 0, result.stderr


def test_teleport_accepts_open_floor_only_and_persists_as_home():
    run("""
const v=new OfficeVisitor();
assert.deepEqual(v.home,VISITOR); assert.equal(v.seated,true);
for(const p of [{x:200,y:150},{x:108,y:200},{x:472,y:265},VISITOR,SPOTS.sleep.point,{x:NaN,y:1},null]) {
  assert.equal(canTeleport(p),false,JSON.stringify(p));
  assert.equal(v.teleport(p),false); assert.deepEqual(v.home,VISITOR);
}
assert.equal(canTeleport({x:220,y:360}),true);            // the kitchen spot the browser saved
assert.equal(v.teleport({x:220,y:360}),true);
assert.deepEqual(v.home,{x:220,y:360}); assert.deepEqual(v.position,{x:220,y:360}); assert.equal(v.room,'kitchen');
assert.equal(v.seated,false);                              // seated means the guest chair itself
// Transient trips never move home
assert.equal(v.go(BEDSIDE),true); for(let i=0;i<200;i++)v.step(.05);
assert.deepEqual(v.home,{x:220,y:360});
assert.equal(v.go(v.home,{returning:true}),true); assert.equal(v.returning,true);
for(let i=0;i<200;i++)v.step(.05); assert.equal(v.returning,false); assert.deepEqual(v.position,{x:220,y:360});
""")


def test_bedside_approach_uses_the_intended_doors_and_arrives_in_time():
    run("""
const v=new OfficeVisitor();
assert.equal(v.go(BEDSIDE),true);
assert.ok(v.route.length>0&&v.route.length<=64,'route '+v.route.length);
assert.deepEqual(v.doorIntents,['chat','sleep']);
const rooms=[]; let t=0;
while(v.route.length){ v.step(.05); t+=.05; assert.ok(clear(v.position),JSON.stringify(v.position));
  if(rooms.at(-1)!==v.room)rooms.push(v.room); assert.ok(t<8,'slower than the approach timeout'); }
assert.deepEqual(v.position,BEDSIDE); assert.equal(v.room,'sleep'); assert.deepEqual(v.doorIntents,[]);
assert.deepEqual(rooms,['chat','corridor','sleep']);
// Diagonals are allowed wherever the whole body fits: the way back is
// shorter than a corridor-only path and still door-correct.
assert.equal(v.go(VISITOR,{returning:true}),true); assert.deepEqual(v.doorIntents,['sleep','chat']);
let length=0, from={...v.position}; for(const p of v.route){length+=Math.hypot(p.x-from.x,p.y-from.y); from=p;}
assert.ok(length<Math.abs(VISITOR.x-BEDSIDE.x)+Math.abs(VISITOR.y-BEDSIDE.y)+60,'length '+length.toFixed(0));
while(v.route.length){ v.step(.05); assert.ok(clear(v.position)); }
assert.equal(v.seated,true);
""")


def test_reduced_motion_snaps_and_unreachable_goals_are_refused():
    run("""
const v=new OfficeVisitor();
assert.equal(v.go(BEDSIDE,{reduced:true}),true); assert.deepEqual(v.position,BEDSIDE); assert.deepEqual(v.route,[]);
assert.equal(v.go(VISITOR),true); v.step(.05,true); assert.deepEqual(v.position,VISITOR); assert.equal(v.returning,false);
assert.equal(v.go({x:200,y:150}),false); assert.deepEqual(v.position,VISITOR); assert.deepEqual(v.route,[]);
assert.equal(v.go(v.position),true); assert.deepEqual(v.route,[]);
""")
