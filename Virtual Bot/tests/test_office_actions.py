"""Furniture animations follow the real activity without delaying it."""
import json
from test_screen_office import run as run_scene, SCREEN


def run(body):
    run_scene('import {OfficeActions} from '+json.dumps((SCREEN/'office-actions.js').as_uri())+';\n'+body)


def test_seating_and_standing_are_visual_and_do_not_mutate_navigation():
    run("""
const a=new OfficeActions();
s.setReducedMotion(true);s.setActivity({activity:'work',active:true});s.setReducedMotion(false);
const before={...s.position};let pose;
for(let i=0;i<4;i++)pose=a.update(s,.1);
assert.equal(pose.kind,'type');assert.equal(pose.seat,1);assert.deepEqual(s.position,before);
s.setActivity({activity:'read',active:true});
assert.equal(s.pose,'walk');
pose=a.update(s,.05);assert.equal(pose.kind,'walk');assert.ok(pose.seat<1);
for(let i=0;i<5;i++)pose=a.update(s,.05);
assert.equal(pose.seat,0);assert.equal(s.active,true);assert.ok(s.route.length);
""")


def test_real_memory_and_leisure_have_distinct_contact_and_props():
    run("""
const a=new OfficeActions();s.setReducedMotion(true);
s.setActivity({activity:'read',active:true});let pose=a.update(s,0);
assert.equal(pose.kind,'memory');assert.equal(pose.seat,0);
s.setActivity({activity:'organize',active:true});pose=a.update(s,0);
assert.equal(pose.kind,'sort');assert.equal(pose.seat,0);
s.setActivity({activity:'idle',active:false});s.go('library');pose=a.update(s,0);
assert.equal(pose.kind,'read');assert.equal(pose.seat,1);assert.equal(s.active,false);
""")


def test_bed_and_reduced_motion_snap_without_replaying_entry():
    run("""
const a=new OfficeActions();s.setReducedMotion(true);s.setSleeping(true);
let pose=a.update(s,0);assert.equal(pose.recline,1);assert.equal(pose.offsetY,-30);
const frozen=JSON.stringify(pose);for(let i=0;i<20;i++)pose=a.update(s,.1);
assert.equal(JSON.stringify(pose),frozen);
s.setActivity({activity:'work',active:true});pose=a.update(s,0);
assert.equal(pose.recline,0);assert.equal(pose.seat,1);assert.equal(pose.kind,'type');
""")


def test_food_cycle_and_visitor_greeting_are_only_ambient_actions():
    run("""
const a=new OfficeActions();s.setReducedMotion(true);s.go('kitchen');s.setReducedMotion(false);
const snacks=new Set();for(let i=0;i<80;i++)snacks.add(a.update(s,.1).snack);
assert.deepEqual([...snacks].sort(),['bite','sip']);assert.equal(s.active,false);
s.setReducedMotion(true);s.go('chat');s.setReducedMotion(false);s.visitorPresent=false;
for(let i=0;i<30;i++)assert.equal(a.update(s,.1).wave,false);
s.visitorPresent=true;let waved=false;for(let i=0;i<90;i++)waved ||= a.update(s,.1).wave;
assert.ok(waved);assert.equal(s.active,false);
const guest=new OfficeActions(true).update(s,0,true);assert.equal(guest.offsetY,0);
""")


def test_new_work_at_the_same_station_keeps_the_crab_seated():
    run("""
const a=new OfficeActions();
a.update(s,0,true);assert.equal(a.seat,1);
s.setActivity({activity:'work',active:true});
assert.equal(s.pose,'work');assert.equal(s.route.length,0);
assert.equal(a.update(s,0).seat,1);
s.setReducedMotion(true);s.setActivity({activity:'read',active:true});
s.setReducedMotion(false);const at={...s.position};
s.setActivity({activity:'organize',active:true});
assert.equal(s.pose,'organize');assert.equal(s.route.length,0);assert.deepEqual(s.position,at);
""")


def test_dozing_and_calling_lie_down_and_errands_stand():
    run("""
const a=new OfficeActions();
for(const pose of ['sleep','dozing','calling']){
  const pose1=a.update({pose,activity:'work',room:'sleep',reduced:true},0,true);
  assert.equal(pose1.kind,'sleep'); assert.equal(pose1.recline,1); assert.equal(pose1.offsetY,-30);
}
for(const pose of ['play','water','stretch']){
  const p=a.update({pose,activity:'idle',room:'chat',reduced:true},0,true);
  assert.equal(p.kind,pose); assert.equal(p.seat,0); assert.equal(p.recline,0);
}
assert.equal(a.update({pose:'visit',activity:'idle',room:'kitchen',reduced:true},0,true).kind,'stand');
""")
